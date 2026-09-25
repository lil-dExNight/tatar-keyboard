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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * The 2026-09-25 SAFE wave (docs/OPTIMIZE-2026-09-25.md): a p95 pin over the FULL composite
 * NEXT_WORD predict path — static bigram successors, then after-word forms, then the global
 * top-frequency fallback — on the real shipped assets, wired exactly as production does (the
 * fixture mirrors [TtNextWordFillE2ETest], which pins the answers themselves).
 *
 * The probe set exercises every stage of the chain: bigram heads (`сәлам`, `һәм` — the pure
 * successor path), a form-only context (`сәләм` — forms then fallback), and a fallback-only
 * context (`сәләмә` — no successors, no forms). The budget is the same 5 ms the prefix-side
 * real-asset pins use ([RealDictionaryPrefixIndexTest]): prediction shares the keystroke path's
 * worker budget.
 */
class TtNextWordPredictP95Test {

    @Test
    fun compositeNextWordPredictP95OverRealAssetsIsAtMostFiveMilliseconds() {
        val computer = requireNotNull(tatarComputer)
        val probes = PROBES.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        repeat(500) { computer.predict(probes[it % probes.size]) }

        val timings = LongArray(2_000)
        var consumed = 0
        for (sample in timings.indices) {
            val probe = probes[sample % probes.size]
            val started = System.nanoTime()
            val result = computer.predict(probe)
            timings[sample] = System.nanoTime() - started
            consumed = consumed xor result.hashCode()
        }
        timings.sort()
        val medianNanos = timings[timings.size / 2]
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "NEXT_WORD composite predict median=" +
                "${"%.3f".format(java.util.Locale.ROOT, medianNanos / 1_000_000.0)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95Nanos / 1_000_000.0)} ms " +
                "consumed=$consumed",
        )
        assertTrue("NEXT_WORD composite p95=${p95Nanos / 1_000_000.0}ms", p95Nanos <= 5_000_000L)
    }

    companion object {
        // Heads with stored successors (сәлам, һәм), a context with an attested form but no
        // successors (сәләм), and one with neither (сәләмә) — see TtNextWordFillE2ETest for the
        // pinned answers of exactly these words on these assets.
        private val PROBES = listOf("сәлам", "һәм", "сәләм", "сәләмә")

        private var tatarComputer: CompositePrefixComputer? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val index = openDictionary(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            tatarComputer = CompositePrefixComputer(
                index, PersonalCandidateSource.EMPTY,
                TatarSuffixRules.createAfterWordForms(index),
                GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
            ).also {
                it.attachBigramSource(openBigrams(BigramArtifactSpec.TATAR_BIGRAMS_V1, index))
            }
        }

        private fun openDictionary(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val raw = inflate(spec)
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                ),
            )
        }

        private fun openBigrams(spec: BigramArtifactSpec, dictionary: TdictPrefixIndex): TatBigrPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-predict-p95-bigr-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                        validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-predict-p95-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                return rawFile.readBytes()
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
