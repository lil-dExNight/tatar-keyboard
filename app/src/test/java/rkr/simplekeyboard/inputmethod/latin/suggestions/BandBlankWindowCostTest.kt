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
 * How long the suggestion band stays blank after a keystroke.
 *
 * The words leave the strip the moment the candidates behind them are unbound
 * ([SuggestionsController.unbindPaintedBand]), so no stale word can be tapped. The cost is a blank
 * band on every keystroke that moves the prefix, until the engine answers the lookup the same call
 * dispatched. Android composes at vsync: a blank and a repaint inside the same 16.7 ms interval
 * reach the screen as one frame, so the budget is one frame at 60 Hz (also the request-to-handoff
 * budget of the input path).
 *
 * Measured on the REAL bundled Tatar dictionary over every proper prefix of common words. Not
 * measured: the [UiPoster] `Handler.post` from the handoff thread to the idle main looper, the
 * same hop every repaint takes.
 */
class BandBlankWindowCostTest {

    @Test
    fun theBandBlankWindowIsShorterThanOneFrame() {
        val index = requireNotNull(tatarIndex)
        val handoffs = ArrayBlockingQueue<Pair<LookupResult, Long>>(1)
        val engine = LatestOnlyPrefixEngine(
            index.identity,
            index,
            ExecutorServiceEngineExecutor.singleThread(),
            ResultHandoff { handoffs.put(it to System.nanoTime()) },
        )
        val prefixes = TYPED_PREFIXES.map { it.toByteArray(Charsets.UTF_8) }
        try {
            repeat(200) { sample ->
                requireNotNull(engine.request(1, "tt", prefixes[sample % prefixes.size]))
                requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
            }
            val timings = LongArray(1_000)
            for (sample in timings.indices) {
                val started = System.nanoTime()
                requireNotNull(engine.request(1, "tt", prefixes[sample % prefixes.size]))
                val handoff = requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
                timings[sample] = handoff.second - started
            }
            timings.sort()
            val median = timings[timings.size / 2 - 1] / 1_000_000.0
            val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
            val worst = timings[timings.size - 1] / 1_000_000.0
            println(
                "band blank window request-to-handoff " +
                    "median=${format(median)} ms p95=${format(p95)} ms worst=${format(worst)} ms",
            )
            assertTrue("p95=$p95 ms is longer than one 60 Hz frame", p95 <= 16.7)
        } finally {
            assertTrue(engine.destroy(2, TimeUnit.SECONDS))
        }
    }

    private fun format(value: Double) = "%.3f".format(java.util.Locale.ROOT, value)

    companion object {
        /**
         * Every proper prefix of four ordinary Tatar words, in typing order. The window this test
         * measures opens once per keystroke, so the prefixes it is measured over must be exactly
         * the ones a finger produces — including the short, wide ones that cost the most.
         */
        private val TYPED_PREFIXES: List<String> = listOf("китап", "сүзләр", "бару", "мәктәп")
            .flatMap { word -> (1..word.length).map { word.substring(0, it) } }

        private var tatarIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedTatarDictionary() {
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val rawFile = File.createTempFile("band-blank-tt-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                tatarIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()),
                    DictionaryIdentity(
                        spec.generation, validated.schemaId, validated.formatVersion,
                        validated.rawSha256,
                    ),
                    validated.entryCount,
                    validated.rawSize,
                )
                check(tatarIndex != null)
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate the committed Tatar dictionary")
    }
}
