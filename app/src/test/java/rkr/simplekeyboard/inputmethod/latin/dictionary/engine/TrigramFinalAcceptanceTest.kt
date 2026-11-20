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
import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil

/**
 * Item-5 acceptance harness: a real schema-3 bigram-only baseline versus the shipped schema-4
 * combined table through the production [CompositePrefixComputer] NEXT_WORD chain.
 *
 * Disabled unless the driver job file `dist/item05-trigram-acceptance/job.properties` exists with
 * `enabled=true`; the driver is `scripts/trigram_final_acceptance.py`, which packs the schema-3
 * baseline with the unchanged training configuration and removes the job file after the run.
 * The evaluation set, the baseline files and the report path arrive through that file, so this
 * class is inert in the ordinary unit-test suite and independent of the Gradle daemon's
 * environment.
 *
 * One replay produces per-sentence top-1, visible top-3 and engine top-4 hit counts for both
 * arms, interleaved per-moment latency samples, per-round allocation minima, the two-word
 * coverage count and the raw/compressed table sizes. The JSON report is written once, to the
 * configured path.
 */
class TrigramFinalAcceptanceTest {
    @Test
    fun runAcceptance() {
        val job = locateJob()
        assumeTrue("item-5 acceptance is driver-invoked only", job != null)
        val properties = java.util.Properties().also { job!!.inputStream().use(it::load) }
        assumeTrue("item-5 acceptance job is disabled", properties["enabled"] == "true")
        val evalSet = File(properties.require(EVAL_KEY))
        val baselineAsset = File(properties.require(BASELINE_ASSET_KEY))
        val baselineRaw = File(properties.require(BASELINE_RAW_KEY))
        val baselineHeads = properties.require(BASELINE_HEADS_KEY).toLong()
        val reportOut = File(properties.require(REPORT_KEY))
        check(!reportOut.exists()) { "refusing to overwrite an existing report: $reportOut" }

        val dictSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val dictionary = openDictionary(dictSpec)
        val candidateSpec = BigramArtifactSpec.TATAR_BIGRAMS_V2
        val candidateAsset = locate(
            "app/src/main/assets/${candidateSpec.assetPath}",
            "src/main/assets/${candidateSpec.assetPath}",
            "../app/src/main/assets/${candidateSpec.assetPath}",
        )
        val candidateBigram = openBigrams(candidateSpec, candidateAsset, dictionary)
        val baselineSpec = BigramArtifactSpec(
            family = candidateSpec.family,
            generation = candidateSpec.generation,
            fileLanguageTag = candidateSpec.fileLanguageTag,
            subtypeId = candidateSpec.subtypeId,
            storageDirectoryName = candidateSpec.storageDirectoryName,
            assetPath = candidateSpec.assetPath,
            expectedCompressedSize = baselineAsset.length(),
            expectedCompressedSha256 = sha256(baselineAsset.readBytes()),
            expectedRawSize = baselineRaw.length(),
            expectedRawSha256 = sha256(baselineRaw.readBytes()),
            expectedDictionaryRawSha256 = candidateSpec.expectedDictionaryRawSha256,
            expectedHeadCount = baselineHeads,
            schemaId = TatBigrFormat.SCHEMA_ID,
            formatVersion = TatBigrFormat.FORMAT_VERSION,
        )
        val baselineBigram = openBigrams(baselineSpec, baselineAsset, dictionary)
        val baseline = composite(dictSpec, dictionary, baselineBigram)
        val candidate = composite(dictSpec, dictionary, candidateBigram)

        val sentences = evalSet.readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.split(' ') }
        check(sentences.isNotEmpty())
        val perSentence = ArrayList<SentenceResult>(sentences.size)
        val probes = ArrayList<Probe>()
        var trigramCovered = 0
        for (words in sentences) {
            var result = SentenceResult(pairs = words.size - 1)
            for (position in 1 until words.size) {
                val probe = Probe(
                    ImmutableUtf8Prefix.copyOf(
                        if (position >= 2) words[position - 2].toByteArray() else ByteArray(0)
                    ),
                    ImmutableUtf8Prefix.copyOf(words[position - 1].toByteArray()),
                    words[position],
                )
                probes += probe
                val baselineAnswer = baseline.predict(probe.second)
                val candidateAnswer = if (probe.first.byteCount == 0) {
                    candidate.predict(probe.second)
                } else {
                    candidate.predict(probe.first, probe.second)
                }
                if (probe.first.byteCount > 0 &&
                    candidateBigram.predict(probe.first, probe.second).isNotEmpty()
                ) {
                    trigramCovered++
                }
                result = result.accumulate(probe.target, baselineAnswer, candidateAnswer)
            }
            perSentence += result
        }

        val moments = probes.size
        repeat(2_000) {
            val probe = probes[it % moments]
            baseline.predict(probe.second)
            if (probe.first.byteCount == 0) candidate.predict(probe.second)
            else candidate.predict(probe.first, probe.second)
        }
        val samples = maxOf(SAMPLES, moments)
        val baselineTimes = LongArray(samples)
        val candidateTimes = LongArray(samples)
        var consumed = 0
        for (sample in 0 until samples) {
            val probe = probes[sample % moments]
            if (sample and 1 == 0) {
                var started = System.nanoTime()
                consumed = consumed xor baseline.predict(probe.second).hashCode()
                baselineTimes[sample] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor candidate.answer(probe).hashCode()
                candidateTimes[sample] = System.nanoTime() - started
            } else {
                var started = System.nanoTime()
                consumed = consumed xor candidate.answer(probe).hashCode()
                candidateTimes[sample] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor baseline.predict(probe.second).hashCode()
                baselineTimes[sample] = System.nanoTime() - started
            }
        }
        assertTrue(consumed != Int.MIN_VALUE)

        val bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        var baselineAllocation = -1L
        var candidateAllocation = -1L
        if (bean != null) {
            val threadId = Thread.currentThread().id
            baselineAllocation = Long.MAX_VALUE
            candidateAllocation = Long.MAX_VALUE
            repeat(8) {
                var started = bean.getThreadAllocatedBytes(threadId)
                for (probe in probes) consumed = consumed xor baseline.predict(probe.second).hashCode()
                baselineAllocation = minOf(
                    baselineAllocation, bean.getThreadAllocatedBytes(threadId) - started
                )
                started = bean.getThreadAllocatedBytes(threadId)
                for (probe in probes) consumed = consumed xor candidate.answer(probe).hashCode()
                candidateAllocation = minOf(
                    candidateAllocation, bean.getThreadAllocatedBytes(threadId) - started
                )
            }
        }
        assertTrue(consumed != Int.MIN_VALUE)

        val report = buildJson(
            evalSet, perSentence, baselineTimes, candidateTimes,
            baselineAllocation, candidateAllocation, moments, trigramCovered,
            baselineRaw.length(), baselineAsset.length(),
            candidateBigramRawSize, candidateAsset.length(),
        )
        reportOut.parentFile?.mkdirs()
        reportOut.writeText(report, Charsets.UTF_8)
        println("TRIGRAM_FINAL_ACCEPTANCE|report=$reportOut")
    }

    private fun composite(
        spec: DictionaryArtifactSpec,
        dictionary: TdictPrefixIndex,
        bigram: TatBigrPrefixIndex,
    ): CompositePrefixComputer = CompositePrefixComputer(
        dictionary,
        PersonalCandidateSource.EMPTY,
        GlobalTopFrequencyFallbackFactory(spec.harmonyTwins).createFallbackWords(dictionary),
    ).also { it.attachBigramSource(bigram) }

    private fun CompositePrefixComputer.answer(probe: Probe): List<String> =
        if (probe.first.byteCount == 0) predict(probe.second) else predict(probe.first, probe.second)

    private data class Probe(
        val first: ImmutableUtf8Prefix,
        val second: ImmutableUtf8Prefix,
        val target: String,
    )

    private data class SentenceResult(
        val pairs: Int,
        val baselineTop1: Int = 0,
        val baselineTop3: Int = 0,
        val baselineTop4: Int = 0,
        val candidateTop1: Int = 0,
        val candidateTop3: Int = 0,
        val candidateTop4: Int = 0,
    ) {
        fun accumulate(
            target: String, baseline: List<String>, candidate: List<String>,
        ): SentenceResult = SentenceResult(
            pairs = pairs,
            baselineTop1 = baselineTop1 + if (target == baseline.firstOrNull()) 1 else 0,
            baselineTop3 = baselineTop3 + if (target in baseline.take(3)) 1 else 0,
            baselineTop4 = baselineTop4 + if (target in baseline.take(4)) 1 else 0,
            candidateTop1 = candidateTop1 + if (target == candidate.firstOrNull()) 1 else 0,
            candidateTop3 = candidateTop3 + if (target in candidate.take(3)) 1 else 0,
            candidateTop4 = candidateTop4 + if (target in candidate.take(4)) 1 else 0,
        )
    }

    companion object {
        private const val JOB_RELATIVE = "dist/item05-trigram-acceptance/job.properties"
        private const val EVAL_KEY = "eval.set"
        private const val BASELINE_ASSET_KEY = "baseline.asset"
        private const val BASELINE_RAW_KEY = "baseline.raw"
        private const val BASELINE_HEADS_KEY = "baseline.heads"
        private const val REPORT_KEY = "report.out"
        private const val SAMPLES = 5_000
        private var candidateBigramRawSize = 0L

        private fun java.util.Properties.require(key: String): String =
            requireNotNull(getProperty(key)) { "$key is missing from the acceptance job" }

        private fun locateJob(): File? = listOf(
            File(JOB_RELATIVE), File("../$JOB_RELATIVE"),
        ).firstOrNull(File::isFile)

        private fun openDictionary(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val asset = locate(
                "app/src/main/assets/${spec.assetPath}",
                "src/main/assets/${spec.assetPath}",
                "../app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("trigram-final-dict-", ".tdict")
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
            asset: File,
            dictionary: TdictPrefixIndex,
        ): TatBigrPrefixIndex {
            val rawFile = File.createTempFile("trigram-final-bigr-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                if (spec.schemaId == TatBigrFormat.SCHEMA_ID_V4) {
                    candidateBigramRawSize = validated.rawSize
                }
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

        private fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun percentile(sorted: LongArray, percentile: Int): Long =
            sorted[ceil(sorted.size * percentile / 100.0).toInt() - 1]

        private fun latencyJson(name: String, source: LongArray): String {
            val timings = source.copyOf()
            timings.sort()
            return "\"$name\":{\"samples\":${timings.size}," +
                "\"p50_ns\":${percentile(timings, 50)},\"p95_ns\":${percentile(timings, 95)}," +
                "\"max_ns\":${timings.last()}}"
        }

        private fun buildJson(
            evalSet: File,
            sentences: List<SentenceResult>,
            baselineTimes: LongArray,
            candidateTimes: LongArray,
            baselineAllocation: Long,
            candidateAllocation: Long,
            moments: Int,
            trigramCovered: Int,
            baselineRawSize: Long,
            baselineCompressedSize: Long,
            candidateRawSize: Long,
            candidateCompressedSize: Long,
        ): String = buildString {
            append("{\"eval_set\":").append(jsonString(evalSet.path))
            append(",\"eval_sha256\":").append(jsonString(sha256(evalSet.readBytes())))
            append(",\"moments\":").append(moments)
            append(",\"sentences_total\":").append(sentences.size)
            append(",\"trigram_covered_moments\":").append(trigramCovered)
            append(",\"latency_ns\":{")
            append(latencyJson("baseline", baselineTimes)).append(',')
            append(latencyJson("candidate", candidateTimes)).append('}')
            append(",\"allocation_bytes_per_round\":{\"baseline\":").append(baselineAllocation)
            append(",\"candidate\":").append(candidateAllocation)
            append(",\"rounds\":8,\"moments_per_round\":").append(moments).append('}')
            append(",\"sizes\":{\"baseline_raw\":").append(baselineRawSize)
            append(",\"baseline_compressed\":").append(baselineCompressedSize)
            append(",\"candidate_raw\":").append(candidateRawSize)
            append(",\"candidate_compressed\":").append(candidateCompressedSize).append('}')
            append(",\"sentences\":[")
            sentences.forEachIndexed { index, sentence ->
                if (index > 0) append(',')
                append("{\"pairs\":").append(sentence.pairs)
                append(",\"baseline_top1\":").append(sentence.baselineTop1)
                append(",\"baseline_top3\":").append(sentence.baselineTop3)
                append(",\"baseline_top4\":").append(sentence.baselineTop4)
                append(",\"candidate_top1\":").append(sentence.candidateTop1)
                append(",\"candidate_top3\":").append(sentence.candidateTop3)
                append(",\"candidate_top4\":").append(sentence.candidateTop4).append('}')
            }
            append("]}")
        }

        private fun jsonString(value: String): String = buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"', '\\' -> append('\\').append(character)
                    else -> append(character)
                }
            }
            append('"')
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate ${paths.joinToString()}")
    }
}
