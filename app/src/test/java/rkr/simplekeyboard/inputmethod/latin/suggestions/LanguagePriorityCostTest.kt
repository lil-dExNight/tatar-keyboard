/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ExecutorServiceEngineExecutor
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LatestOnlyPrefixEngine
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ResultHandoff
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * The cost of the language-priority rule.
 *
 * The rule adds exactly ONE thing to the input path: when the active language leaves a cell empty,
 * the other language's already-warm engine is asked the same query. That second lookup runs on the
 * other engine's own worker thread, so it is bounded by the same two budgets as the first lookup:
 * compute p95 <= 5 ms and request-to-handoff p95 <= 16 ms. This measures the second lookup against
 * both, on the REAL bundled Russian dictionary, over the REAL prefixes a Tatar-layout user leaves
 * the band under-filled with. How often the second lookup happens depends only on the two bundled
 * dictionaries, not on the runtime, so it is not measured here.
 */
class LanguagePriorityCostTest {

    @Test
    fun theSecondLookupComputeP95IsAtMostFiveMilliseconds() {
        val index = requireNotNull(russianIndex)
        val prefixes = UNDERFILLED_TATAR_PREFIXES.map {
            ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8))
        }
        repeat(500) { index.lookup(prefixes[it % prefixes.size]) }

        val timings = LongArray(2_000)
        var consumed = 0L
        for (sample in timings.indices) {
            val prefix = prefixes[sample % prefixes.size]
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            for (result in results) consumed = consumed * 31 + result.length
        }
        timings.sort()
        val median = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        println(
            "lang-priority second lookup compute median=" +
                "${"%.3f".format(java.util.Locale.ROOT, median)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95)} ms consumed=$consumed",
        )
        assertTrue("p95=$p95 ms", p95 <= 5.0)
    }

    @Test
    fun theSecondLookupRequestToHandoffP95IsAtMostSixteenMilliseconds() {
        val index = requireNotNull(russianIndex)
        val handoffs = ArrayBlockingQueue<Pair<LookupResult, Long>>(1)
        val engine = LatestOnlyPrefixEngine(
            index.identity,
            index,
            ExecutorServiceEngineExecutor.singleThread(),
            ResultHandoff { handoffs.put(it to System.nanoTime()) },
        )
        val prefixes = UNDERFILLED_TATAR_PREFIXES.map { it.toByteArray(Charsets.UTF_8) }
        try {
            repeat(200) { sample ->
                requireNotNull(engine.request(1, "ru", prefixes[sample % prefixes.size]))
                requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
            }
            val timings = LongArray(1_000)
            for (sample in timings.indices) {
                val started = System.nanoTime()
                requireNotNull(engine.request(1, "ru", prefixes[sample % prefixes.size]))
                val handoff = requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
                timings[sample] = handoff.second - started
            }
            timings.sort()
            val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
            println(
                "lang-priority second lookup request-to-handoff p95=" +
                    "${"%.3f".format(java.util.Locale.ROOT, p95)} ms",
            )
            assertTrue("p95=$p95 ms", p95 <= 16.0)
        } finally {
            assertTrue(engine.destroy(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun theWorstCaseSecondLookupIsStillWithinTheSameComputeBudget() {
        val index = requireNotNull(russianIndex)
        // The under-filled prefixes above are long and rare, which is WHY they are under-filled —
        // and why their second lookup is nearly free. The real upper bound is the opposite case: a
        // one-letter prefix, the widest block the Russian dictionary has. The active language would
        // have to answer a one-letter prefix with fewer than three words for this to happen at all,
        // but the budget must hold even then.
        val widest = ImmutableUtf8Prefix.copyOf("п".toByteArray(Charsets.UTF_8))
        repeat(500) { index.lookup(widest) }

        val timings = LongArray(2_000)
        var consumed = 0
        for (sample in timings.indices) {
            val started = System.nanoTime()
            val results = index.lookup(widest)
            timings[sample] = System.nanoTime() - started
            consumed = consumed xor results.hashCode()
        }
        timings.sort()
        val median = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        println(
            "lang-priority worst-case second lookup (one letter) median=" +
                "${"%.3f".format(java.util.Locale.ROOT, median)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95)} ms consumed=$consumed",
        )
        assertTrue("p95=$p95 ms", p95 <= 5.0)
    }

    companion object {
        /**
         * Real prefixes a Tatar-layout user can type that leave the Tatar band under-filled, so the
         * Russian engine is the one that answers them. Every one of them is a live second lookup on
         * a real device.
         */
        private val UNDERFILLED_TATAR_PREFIXES = listOf(
            "поздрав", "спасиб", "здравств", "пожалуйст", "которы", "конечн",
            "извини", "можн", "нужн", "хорош", "сейчас", "поэтом",
        )

        private var russianIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedRussianDictionary() {
            val asset = locate(
                "src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
            val rawFile = File.createTempFile("lang-priority-ru-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                russianIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()),
                    DictionaryIdentity(
                        spec.generation, validated.schemaId, validated.formatVersion,
                        validated.rawSha256,
                    ),
                    validated.entryCount,
                    validated.rawSize,
                )
                check(russianIndex != null)
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate the committed Russian dictionary")
    }
}
