package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Compares two I/O strategies for the dictionary on the committed Tatar asset: the shipped
 * read-only mmap (`MappedDictionaryEngine.FILE_MAPPER`) and a heap copy
 * (`ByteBuffer.wrap(readBytes())`), both through the `DictionaryMapper` seam.
 *
 *  - [lookupResultsAreByteIdenticalAcrossIoStrategies]: `TdictPrefixIndex` only calls
 *    `ByteBuffer.get` on a read-only buffer; this pins identical results on the real asset,
 *    including the typo path and the cold reads (whole-word membership, glide inventory walk).
 *  - [perLookupLatencyAndAllocationStayWithinTheShippedBoundForBothStrategies]: p50/p95 over the
 *    22 review prefixes and 5 typo probes under the Tatar fuzzy policy, both held to the 5 ms host
 *    bound, with equal per-lookup allocation.
 *  - [coldLoadCostOfBothStrategiesIsRecorded]: acquisition + `open()` medians, print-only and
 *    warm-cache on the host; the cold-read and PSS measurements belong to
 *    `DictionaryIoStrategyInstrumentationTest` on a device.
 */
class DictionaryIoStrategyCalibrationTest {

    @Test
    fun lookupResultsAreByteIdenticalAcrossIoStrategies() {
        val heap = requireNotNull(heapIndex)
        val mapped = requireNotNull(mappedIndex)
        for (prefix in reviewPrefixes() + TYPO_PROBES) {
            val query = ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            assertEquals(
                "lookup results differ between io strategies for prefix $prefix",
                heap.lookup(query),
                mapped.lookup(query),
            )
        }
        // The other read surfaces of the same buffer (whole-word membership, used by the
        // learned-pairs store's worker; the glide inventory walk) must agree as well.
        for (word in listOf("сәләм", "татарча", "китап", "сцләмәткәй-absent")) {
            assertEquals(
                "containsWordCold differs between io strategies for $word",
                heap.containsWordCold(word),
                mapped.containsWordCold(word),
            )
        }
        var heapWords = 0
        var heapHash = 0L
        heap.forEachWordCold { word, frequency ->
            heapWords++
            heapHash = heapHash * 31 + (word.hashCode() * 31 + frequency)
        }
        var mappedWords = 0
        var mappedHash = 0L
        mapped.forEachWordCold { word, frequency ->
            mappedWords++
            mappedHash = mappedHash * 31 + (word.hashCode() * 31 + frequency)
        }
        assertEquals(heapWords, mappedWords)
        assertEquals(heapHash, mappedHash)
    }

    @Test
    fun perLookupLatencyAndAllocationStayWithinTheShippedBoundForBothStrategies() {
        val allocPerLookup = LinkedHashMap<String, Long>()
        for ((strategy, index) in strategies()) {
            measureWorkload(index, strategy, "review", reviewPrefixes(), allocPerLookup)
            measureWorkload(index, strategy, "typo", TYPO_PROBES, null)
        }
        // The lookup path allocates the same result objects regardless of where the dictionary
        // bytes live; the thread-local allocation counter is exact for identical executed paths,
        // so any delta here means the strategies' hot paths diverged.
        val heapAlloc = allocPerLookup.getValue("heap")
        val mappedAlloc = allocPerLookup.getValue("mmap")
        println("O7 host io-strategy alloc-review heap=$heapAlloc mmap=$mappedAlloc bytes/lookup")
        assertEquals(
            "per-lookup allocation diverged between io strategies (heap=$heapAlloc mmap=$mappedAlloc)",
            heapAlloc,
            mappedAlloc,
        )
    }

    /**
     * Acquisition + open() medians over interleaved repetitions, print-only: wall-clock ratios on
     * a shared CI runner are noise. The host page cache is warm after the first repetition, so
     * these are warm numbers; the device test owns the cold read.
     */
    @Test
    fun coldLoadCostOfBothStrategiesIsRecorded() {
        val file = requireNotNull(rawFile)
        val identity = requireNotNull(identity)
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val acquireHeap = LongArray(REPS)
        val openHeap = LongArray(REPS)
        val acquireMapped = LongArray(REPS)
        val openMapped = LongArray(REPS)
        var consumed = 0
        for (rep in 0 until REPS) {
            var started = System.nanoTime()
            val heapBuffer = ByteBuffer.wrap(file.readBytes())
            acquireHeap[rep] = System.nanoTime() - started
            started = System.nanoTime()
            consumed = consumed xor requireNotNull(
                TdictPrefixIndex.open(
                    heapBuffer, identity, spec.expectedEntryCount, spec.expectedRawSize,
                    null, FuzzyEditPolicy.TATAR,
                ),
            ).entryCount
            openHeap[rep] = System.nanoTime() - started

            started = System.nanoTime()
            val mappedBuffer = MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(file, spec.expectedRawSize)
            acquireMapped[rep] = System.nanoTime() - started
            started = System.nanoTime()
            consumed = consumed xor requireNotNull(
                TdictPrefixIndex.open(
                    mappedBuffer, identity, spec.expectedEntryCount, spec.expectedRawSize,
                    null, FuzzyEditPolicy.TATAR,
                ),
            ).entryCount
            openMapped[rep] = System.nanoTime() - started
        }
        acquireHeap.sort()
        openHeap.sort()
        acquireMapped.sort()
        openMapped.sort()
        println(
            "O7 host io-strategy cold-load (warm page cache, medians of $REPS) " +
                "acquireHeap=${fmt(median(acquireHeap))} ms openHeap=${fmt(median(openHeap))} ms " +
                "acquireMmap=${fmt(median(acquireMapped))} ms openMmap=${fmt(median(openMapped))} ms " +
                "consumed=$consumed",
        )
        // The analytic heap footprint: the heap strategy pins the whole dictionary in the
        // anonymous heap; the mmap strategy's bytes live in evictable file-backed pages.
        println(
            "O7 host io-strategy load-footprint heapBytes=${spec.expectedRawSize} " +
                "mmapHeapBytes=0 rawSize=${file.length()}",
        )
    }

    private fun strategies(): List<Pair<String, TdictPrefixIndex>> = listOf(
        "heap" to requireNotNull(heapIndex),
        "mmap" to requireNotNull(mappedIndex),
    )

    private fun measureWorkload(
        index: TdictPrefixIndex,
        strategy: String,
        workload: String,
        prefixesCp: List<String>,
        allocPerLookup: MutableMap<String, Long>?,
    ) {
        val prefixes = prefixesCp.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        repeat(500) { index.lookup(prefixes[it % prefixes.size]) }
        val bean = allocationBean()
        val threadId = Thread.currentThread().id
        val allocatedBefore = bean?.getThreadAllocatedBytes(threadId) ?: -1L
        val timings = LongArray(2_000)
        var consumed = 0L
        for (sample in timings.indices) {
            val prefix = prefixes[sample % prefixes.size]
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            for (result in results) consumed = consumed * 31 + result.length
        }
        val allocatedAfter = bean?.getThreadAllocatedBytes(threadId) ?: -1L
        timings.sort()
        val medianNanos = timings[timings.size / 2]
        val p95Nanos = timings[ceil(timings.size * 0.95).toInt() - 1]
        val maxNanos = timings.last()
        val allocText: String
        if (allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) {
            val perLookup = (allocatedAfter - allocatedBefore) / timings.size
            allocPerLookup?.put(strategy, perLookup)
            allocText = " allocPerLookup=$perLookup"
        } else {
            allocText = " allocPerLookup=n/a"
        }
        println(
            "O7 host io-strategy compute strategy=$strategy workload=$workload " +
                "median=${fmt(medianNanos)} ms p95=${fmt(p95Nanos)} ms max=${fmt(maxNanos)} ms" +
                "$allocText consumed=$consumed",
        )
        assertTrue(
            "$strategy/$workload p95=${p95Nanos / 1_000_000.0}ms exceeds the shipped 5 ms host bound",
            p95Nanos <= 5_000_000L,
        )
        assertTrue(consumed != Long.MIN_VALUE)
    }

    private fun reviewPrefixes(): List<String> {
        val review = locate(
            "data/dictionary/tt-query-review.tsv",
            "../data/dictionary/tt-query-review.tsv",
        )
        val rows = review.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }
        require(rows.size == 22)
        return rows.map { it.split('\t')[0] }
    }

    companion object {
        // The typo workload, inlined as in E3bComputeInstrumentationTest: the target case at
        // 3/4/5 code points, "сйл", and the 10-code-point "сцләмәтлек", the heaviest reader of the
        // dictionary bytes.
        private val TYPO_PROBES = listOf("сцл", "сцлә", "сцләм", "сйл", "сцләмәтлек")
        private const val REPS = 21

        private var rawFile: File? = null
        private var identity: DictionaryIdentity? = null
        private var heapIndex: TdictPrefixIndex? = null
        private var mappedIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionary() {
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val file = File.createTempFile("o7-io-strategy-", ".tdict")
            rawFile = file
            asset.inputStream().use { input ->
                file.outputStream().use { output -> TdictValidator().inflateAsset(input, output, spec) }
            }
            val validated = TdictValidator().validateRaw(file, spec)
            val loadedIdentity = DictionaryIdentity(
                spec.generation,
                validated.schemaId,
                validated.formatVersion,
                validated.rawSha256,
            )
            identity = loadedIdentity
            val heapBuffer = ByteBuffer.wrap(file.readBytes())
            val mappedBuffer = MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(file, validated.rawSize)
            // The strategies are what they claim to be; MappedDictionaryEngineTest covers the
            // production mmap side, these pin the two arms compared here.
            assertTrue(heapBuffer.hasArray())
            assertTrue(mappedBuffer.isDirect && mappedBuffer.isReadOnly)
            heapIndex = openIndex(heapBuffer, loadedIdentity, spec)
            mappedIndex = openIndex(mappedBuffer, loadedIdentity, spec)
        }

        @JvmStatic
        @AfterClass
        fun releaseCommittedDictionary() {
            heapIndex = null
            mappedIndex = null
            rawFile?.delete()
            rawFile = null
        }

        private fun openIndex(
            buffer: ByteBuffer,
            identity: DictionaryIdentity,
            spec: DictionaryArtifactSpec,
        ): TdictPrefixIndex = requireNotNull(
            TdictPrefixIndex.open(
                buffer, identity, spec.expectedEntryCount, spec.expectedRawSize,
                null, FuzzyEditPolicy.TATAR,
            ),
        ).also { it.updateKeyNeighbors(E3bTestFixtures.tatarNeighborTable()) }

        private fun allocationBean(): com.sun.management.ThreadMXBean? {
            val bean = ManagementFactory.getThreadMXBean()
            if (bean !is com.sun.management.ThreadMXBean) return null
            // Unit tests compile against the android.jar stubs (no capability query there);
            // at runtime the host JDK's real bean answers, so probe with one actual call.
            return try {
                bean.getThreadAllocatedBytes(Thread.currentThread().id)
                bean
            } catch (_: UnsupportedOperationException) {
                null
            }
        }

        private fun median(sorted: LongArray): Long = sorted[sorted.size / 2]

        private fun fmt(nanos: Long): String =
            String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0)

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }
}
