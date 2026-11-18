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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/** Host measurement of the committed two-word table through the production composite chain. */
class TrigramCandidateMeasurementTest {
    @Test
    fun compareCompositeLatencyAndAllocation() {
        val computer = requireNotNull(computer)
        val probes = probes
        var baselineTop1 = 0
        var candidateTop1 = 0
        var baselineVisible = 0
        var candidateVisible = 0
        for (probe in probes) {
            val baseline = computer.baseline(probe.second)
            val candidate = computer.candidate(probe.first, probe.second)
            if (probe.target == baseline.firstOrNull()) baselineTop1++
            if (probe.target == candidate.firstOrNull()) candidateTop1++
            if (probe.target in baseline.take(3)) baselineVisible++
            if (probe.target in candidate.take(3)) candidateVisible++
        }
        println(
            "TRIGRAM_MEASURE|quality|baseline_top1=$baselineTop1|" +
                "candidate_top1=$candidateTop1|baseline_visible_top3=$baselineVisible|" +
                "candidate_visible_top3=$candidateVisible|denominator=${probes.size}",
        )
        assertEquals(EXPECTED_BASELINE_TOP1, baselineTop1)
        assertEquals(EXPECTED_CANDIDATE_TOP1, candidateTop1)
        assertEquals(EXPECTED_BASELINE_VISIBLE, baselineVisible)
        assertEquals(EXPECTED_CANDIDATE_VISIBLE, candidateVisible)

        repeat(2_000) {
            val probe = probes[it % probes.size]
            computer.baseline(probe.second)
            computer.candidate(probe.first, probe.second)
        }
        val baselineTimes = LongArray(SAMPLES)
        val candidateTimes = LongArray(SAMPLES)
        var consumed = 0
        for (sample in 0 until SAMPLES) {
            val probe = probes[sample % probes.size]
            if (sample and 1 == 0) {
                var started = System.nanoTime()
                consumed = consumed xor computer.baseline(probe.second).hashCode()
                baselineTimes[sample] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor computer.candidate(probe.first, probe.second).hashCode()
                candidateTimes[sample] = System.nanoTime() - started
            } else {
                var started = System.nanoTime()
                consumed = consumed xor computer.candidate(probe.first, probe.second).hashCode()
                candidateTimes[sample] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor computer.baseline(probe.second).hashCode()
                baselineTimes[sample] = System.nanoTime() - started
            }
        }
        printTimings("baseline", baselineTimes)
        printTimings("trigram", candidateTimes)
        assertTrue(
            "trigram composite p95=${percentile(candidateTimes.sortedArray(), 95) / 1e6}ms",
            percentile(candidateTimes.sortedArray(), 95) <= 5_000_000L,
        )
        assertTrue(consumed != Int.MIN_VALUE)

        val bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        if (bean != null) {
            val threadId = Thread.currentThread().id
            var baselineMin = Long.MAX_VALUE
            var candidateMin = Long.MAX_VALUE
            repeat(8) {
                var started = bean.getThreadAllocatedBytes(threadId)
                for (probe in probes) consumed = consumed xor computer.baseline(probe.second).hashCode()
                baselineMin = minOf(baselineMin, bean.getThreadAllocatedBytes(threadId) - started)
                started = bean.getThreadAllocatedBytes(threadId)
                for (probe in probes) {
                    consumed = consumed xor computer.candidate(probe.first, probe.second).hashCode()
                }
                candidateMin = minOf(candidateMin, bean.getThreadAllocatedBytes(threadId) - started)
            }
            println(
                "TRIGRAM_MEASURE|allocation_round|baseline_bytes=$baselineMin|" +
                    "candidate_bytes=$candidateMin|probes=${probes.size}",
            )
        }
        val spec = BigramArtifactSpec.TATAR_BIGRAMS_V2
        println(
            "TRIGRAM_MEASURE|memory|strategy=mmap|raw_bytes=${spec.expectedRawSize}|" +
                "compressed_bytes=${spec.expectedCompressedSize}|heap_payload_bytes=0",
        )
    }

    private fun printTimings(name: String, source: LongArray) {
        val timings = source.copyOf()
        timings.sort()
        println(
            "TRIGRAM_MEASURE|latency|arm=$name|samples=${timings.size}|" +
                "median_ms=${percentile(timings, 50) / 1e6}|" +
                "p95_ms=${percentile(timings, 95) / 1e6}|max_ms=${timings.last() / 1e6}",
        )
    }

    private fun percentile(sorted: LongArray, percentile: Int): Long =
        sorted[ceil(sorted.size * percentile / 100.0).toInt() - 1]

    private data class Probe(
        val first: ImmutableUtf8Prefix,
        val second: ImmutableUtf8Prefix,
        val target: String,
    )

    private class CandidateComputer(private val composite: CompositePrefixComputer) {
        fun baseline(second: ImmutableUtf8Prefix): List<String> = composite.predict(second)

        fun candidate(first: ImmutableUtf8Prefix, second: ImmutableUtf8Prefix): List<String> =
            if (first.byteCount == 0) composite.predict(second) else composite.predict(first, second)
    }

    companion object {
        private const val SAMPLES = 5_000
        private const val EXPECTED_BASELINE_TOP1 = 345
        private const val EXPECTED_CANDIDATE_TOP1 = 409
        private const val EXPECTED_BASELINE_VISIBLE = 652
        private const val EXPECTED_CANDIDATE_VISIBLE = 733
        private var computer: CandidateComputer? = null
        private var probes: List<Probe> = emptyList()

        @JvmStatic
        @BeforeClass
        fun loadAssets() {
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val dictionary = openDictionary(spec)
            val bigram = openBigrams(BigramArtifactSpec.TATAR_BIGRAMS_V2, dictionary)
            val fallback = GlobalTopFrequencyFallbackFactory(spec.harmonyTwins)
                .createFallbackWords(dictionary)
            val composite = CompositePrefixComputer(
                dictionary,
                PersonalCandidateSource.EMPTY,
                fallback,
            ).also { it.attachBigramSource(bigram) }
            computer = CandidateComputer(composite)
            val eval = locate(
                "app/src/test/resources/tt_eval_sentences.txt",
                "src/test/resources/tt_eval_sentences.txt",
                "../app/src/test/resources/tt_eval_sentences.txt",
            )
            probes = eval.readLines(Charsets.UTF_8).asSequence()
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .flatMap { line ->
                    val words = line.split(' ')
                    (1 until words.size).asSequence().map { position ->
                        Probe(
                            ImmutableUtf8Prefix.copyOf(
                                if (position >= 2) words[position - 2].toByteArray() else ByteArray(0)
                            ),
                            ImmutableUtf8Prefix.copyOf(words[position - 1].toByteArray()),
                            words[position],
                        )
                    }
                }
                .toList()
            check(probes.isNotEmpty())
        }

        private fun openDictionary(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val asset = locate(
                "app/src/main/assets/${spec.assetPath}",
                "src/main/assets/${spec.assetPath}",
                "../app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("trigram-measure-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation, spec.schemaId, spec.formatVersion,
                    MessageDigest.getInstance("SHA-256").digest(raw)
                        .joinToString("") { "%02x".format(it) },
                )
                return requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                        autocorrectMinCandidateFrequency = 0L,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun openBigrams(
            spec: BigramArtifactSpec,
            dictionary: TdictPrefixIndex,
        ): TatBigrPrefixIndex {
            val asset = locate(
                "app/src/main/assets/${spec.assetPath}",
                "src/main/assets/${spec.assetPath}",
                "../app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("trigram-measure-bigr-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                val mapped = MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(rawFile, rawFile.length())
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        mapped, identity, dictionary, validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate ${paths.joinToString()}")
    }
}
