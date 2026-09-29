package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class RealDictionaryPrefixIndexTest {
    @Test
    fun kotlinResultsMatchEveryRecordedQueryAuditPrefix() {
        val index = requireNotNull(realIndex)
        val rows = reviewRows()

        assertEquals(22, rows.size)
        for ((prefix, expected) in rows) {
            assertEquals(
                prefix,
                expected,
                index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))),
            )
        }
    }

    @Test
    fun computeP95OverRealDictionaryIsAtMostFiveMilliseconds() {
        val index = requireNotNull(realIndex)
        val prefixes = reviewRows().map {
            ImmutableUtf8Prefix.copyOf(it.first.toByteArray(Charsets.UTF_8))
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
        val medianNanos = timings[timings.size / 2]
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        val medianMillis = medianNanos / 1_000_000.0
        val p95Millis = p95Nanos / 1_000_000.0
        println("D1d compute median=${"%.3f".format(java.util.Locale.ROOT, medianMillis)} ms p95=${"%.3f".format(java.util.Locale.ROOT, p95Millis)} ms consumed=$consumed")
        assertTrue("p95=${p95Millis}ms", p95Nanos <= 5_000_000L)
        assertTrue(consumed != Long.MIN_VALUE)
    }

    @Test
    fun realMaximumOneLetterKFanoutComputeP95IsAtMostFiveMilliseconds() {
        val index = requireNotNull(realIndex)
        val prefix = ImmutableUtf8Prefix.copyOf("к".toByteArray(Charsets.UTF_8))
        repeat(500) { index.lookup(prefix) }

        val timings = LongArray(2_000)
        var consumed = 0
        for (sample in timings.indices) {
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            consumed = consumed xor results.hashCode()
        }
        timings.sort()
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "D1d max one-letter к fanout p95=" +
                "${"%.3f".format(java.util.Locale.ROOT, p95Nanos / 1_000_000.0)} ms " +
                "consumed=$consumed",
        )
        assertTrue("one-letter p95=${p95Nanos / 1_000_000.0}ms", p95Nanos <= 5_000_000L)
    }

    /**
     * TT-TYPO-NEXT Phases B/C (G3/G3-C, host): the same p95 measurement over the 22 review
     * prefixes, but with the calibrated Tatar fuzzy policy (Phase C: classes #1 + #4 +
     * same-length bonus) and the layout-derived neighbour table engaged. Most of these prefixes
     * fill all three cells from the exact pass, so this measures the COMMON typing path with the
     * fuzzy pass armed — the typo-set p95 lives in [TtTypoPhaseBCalibrationTest] (Phase B) and
     * [TtTypoPhaseCCalibrationTest] (Phase C).
     */
    @Test
    fun computeP95WithTheTatarFuzzyPolicyOverReviewPrefixesIsAtMostFiveMilliseconds() {
        val index = requireNotNull(tatarPolicyIndex)
        val prefixes = reviewRows().map {
            ImmutableUtf8Prefix.copyOf(it.first.toByteArray(Charsets.UTF_8))
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
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        val medianNanos = timings[timings.size / 2]
        println(
            "PhaseB review-prefix compute policy=tatar " +
                "median=${"%.3f".format(java.util.Locale.ROOT, medianNanos / 1_000_000.0)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95Nanos / 1_000_000.0)} ms consumed=$consumed",
        )
        assertTrue("tatar-policy p95=${p95Nanos / 1_000_000.0}ms", p95Nanos <= 5_000_000L)
        assertTrue(consumed != Long.MIN_VALUE)
    }

    @Test
    fun requestToNonApplyingHandoffP95IsAtMostSixteenMilliseconds() {
        val index = requireNotNull(realIndex)
        val handoffs = ArrayBlockingQueue<TimedHandoff>(1)
        val engine = LatestOnlyPrefixEngine(
            index.identity,
            index,
            ExecutorServiceEngineExecutor.singleThread(),
            ResultHandoff { handoffs.put(TimedHandoff(it, System.nanoTime())) },
        )
        val prefixes = reviewRows().map { it.first.toByteArray(Charsets.UTF_8) }
        try {
            repeat(200) { sample ->
                val token = requireNotNull(engine.request(1, "tt", prefixes[sample % prefixes.size]))
                assertEquals(token, handoffs.poll(1, TimeUnit.SECONDS)?.result?.token)
            }

            val timings = LongArray(1_000)
            for (sample in timings.indices) {
                val started = System.nanoTime()
                val token = requireNotNull(
                    engine.request(1, "tt", prefixes[sample % prefixes.size]),
                )
                val handoff = requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
                assertEquals(token, handoff.result.token)
                timings[sample] = handoff.handedOffAtNanos - started
            }
            timings.sort()
            val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
            println(
                "D1d request-to-handoff p95=" +
                    "${"%.3f".format(java.util.Locale.ROOT, p95Nanos / 1_000_000.0)} ms",
            )
            assertTrue(
                "request-to-handoff p95=${p95Nanos / 1_000_000.0}ms",
                p95Nanos <= 16_000_000L,
            )
        } finally {
            assertTrue(engine.destroy(2, TimeUnit.SECONDS))
        }
    }

    private fun reviewRows(): List<Pair<String, List<String>>> {
        val review = locate("docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv", "../docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv")
        return review.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            require(fields.size >= 2)
            fields[0] to fields[1].split('|').filter(String::isNotEmpty)
        }
    }

    companion object {
        private var realIndex: TdictPrefixIndex? = null
        // Phase B (G3): the same dictionary under the calibrated Tatar fuzzy policy.
        private var tatarPolicyIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionary() {
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val rawFile = File.createTempFile("d1d-real-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                realIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw),
                    identity,
                    validated.entryCount,
                    validated.rawSize,
                )
                check(realIndex != null)
                tatarPolicyIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw),
                    identity,
                    validated.entryCount,
                    validated.rawSize,
                    null,
                    FuzzyEditPolicy.TATAR,
                )?.also { it.updateKeyNeighbors(E3bTestFixtures.tatarNeighborTable()) }
                check(tatarPolicyIndex != null)
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }

    private data class TimedHandoff(
        val result: LookupResult,
        val handedOffAtNanos: Long,
    )
}
