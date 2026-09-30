package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * NEXT_WORD compute p95 <= 5 ms and full request-to-publish warm p95 <= 16 ms, the same bounds as
 * the prefix search. Mirrors [RealDictionaryPrefixIndexTest] against the real committed
 * `tatar_bigrams_v1.tatbigr.zlib`.
 */
class RealBigramPrefixIndexTest {
    @Test
    fun computeP95OverRealTableIsAtMostFiveMilliseconds() {
        val index = requireNotNull(realIndex)
        val words = sampleContextWords()
        repeat(500) { index.predict(words[it % words.size]) }

        val timings = LongArray(2_000)
        var consumed = 0L
        for (sample in timings.indices) {
            val word = words[sample % words.size]
            val started = System.nanoTime()
            val results = index.predict(word)
            timings[sample] = System.nanoTime() - started
            for (result in results) consumed = consumed * 31 + result.length
        }
        timings.sort()
        val medianNanos = timings[timings.size / 2]
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "E5c bigram compute median=${"%.3f".format(java.util.Locale.ROOT, medianNanos / 1_000_000.0)} ms " +
                "p95=${"%.3f".format(java.util.Locale.ROOT, p95Nanos / 1_000_000.0)} ms consumed=$consumed",
        )
        assertTrue("p95=${p95Nanos / 1_000_000.0}ms", p95Nanos <= 5_000_000L)
        assertTrue(consumed != Long.MIN_VALUE)
    }

    @Test
    fun requestNextWordToNonApplyingHandoffP95IsAtMostSixteenMilliseconds() {
        val index = requireNotNull(realIndex)
        val handoffs = ArrayBlockingQueue<TimedHandoff>(1)
        // LatestOnlyPrefixEngine's computer slot is typed PrefixComputer; NEXT_WORD dispatch casts
        // to NextWordComputer dynamically inside drain(). This measurement only ever issues
        // requestNextWord, so lookup() is provided but never exercised.
        val computer = object : PrefixComputer, NextWordComputer by index {
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> = emptyList()
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            computer,
            ExecutorServiceEngineExecutor.singleThread(),
            ResultHandoff { handoffs.put(TimedHandoff(it, System.nanoTime())) },
        )
        val words = sampleContextWords().map { it.decodeUtf8().toByteArray(Charsets.UTF_8) }
        try {
            repeat(200) { sample ->
                val token = requireNotNull(
                    engine.requestNextWord(1, "tt", words[sample % words.size]),
                )
                assertEquals(token, handoffs.poll(1, TimeUnit.SECONDS)?.result?.token)
            }

            val timings = LongArray(200)
            for (sample in timings.indices) {
                val started = System.nanoTime()
                val token = requireNotNull(
                    engine.requestNextWord(1, "tt", words[sample % words.size]),
                )
                val handoff = requireNotNull(handoffs.poll(1, TimeUnit.SECONDS))
                assertEquals(token, handoff.result.token)
                timings[sample] = handoff.handedOffAtNanos - started
            }
            timings.sort()
            val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
            println(
                "E5c bigram request-to-handoff p95=" +
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

    /**
     * Common Tatar words used as NEXT_WORD context queries. There is no reviewed query file for
     * bigram heads, and none is needed: a miss and a hit cost the same binary search, so a fixed
     * word list is enough for a compute-cost measurement.
     */
    private fun sampleContextWords(): List<ImmutableUtf8Prefix> = listOf(
        "мин", "син", "ул", "без", "сез", "алар", "һәм", "бу", "теге", "әйе",
        "юк", "инде", "әле", "бик", "бар", "юл", "өй", "кеше", "сүз", "көн",
    ).map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    companion object {
        private var realIndex: TatBigrPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedBigramTable() {
            // Schema 3 resolves words through the linked dictionary: open the committed Tatar
            // dictionary first, exactly like RealDictionaryPrefixIndexTest does for itself.
            val dictSpec = rkr.simplekeyboard.inputmethod.latin.dictionary.storage
                .DictionaryArtifactSpec.TATAR_TOP100K_V1
            val dictAsset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val dictRawFile = File.createTempFile("e5c-real-dict-", ".tdict")
            val dictionary: TdictPrefixIndex
            try {
                dictRawFile.outputStream().use { output ->
                    rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator()
                        .inflateAsset(dictAsset.inputStream(), output, dictSpec)
                }
                val validated = rkr.simplekeyboard.inputmethod.latin.dictionary.storage
                    .TdictValidator().validateRaw(dictRawFile, dictSpec)
                val dictIdentity = DictionaryIdentity(
                    dictSpec.generation, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                dictionary = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(dictRawFile.readBytes()), dictIdentity,
                        validated.entryCount, validated.rawSize,
                    ),
                )
            } finally {
                dictRawFile.delete()
            }

            val asset = locate(
                "src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
                "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
            )
            val spec = BigramArtifactSpec.TATAR_BIGRAMS_V1
            val rawFile = File.createTempFile("e5c-real-bigrams-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                realIndex = TatBigrPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, dictionary, validated.headCount, validated.rawSize,
                )
                check(realIndex != null)
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed bigram test resource")
    }

    private data class TimedHandoff(
        val result: LookupResult,
        val handedOffAtNanos: Long,
    )
}
