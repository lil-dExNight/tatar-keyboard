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
 *    bound, with equal steady-state allocation per lookup round.
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
        for ((strategy, index) in strategies()) {
            measureWorkload(index, strategy, "review", reviewPrefixes())
            measureWorkload(index, strategy, "typo", TYPO_PROBES)
        }
        // The lookup path allocates the same result objects regardless of where the dictionary
        // bytes live, so the steady-state bytes of one identical round must match exactly; any
        // delta means the strategies' hot paths diverged.
        val bean = requireNotNull(allocationBean()) { "the host JVM reports no thread allocation" }
        for ((workload, prefixes) in listOf("review" to reviewPrefixes(), "typo" to TYPO_PROBES)) {
            val allocated = steadyStateRoundAllocation(bean, prefixes)
            val heapAlloc = allocated.getValue("heap")
            val mappedAlloc = allocated.getValue("mmap")
            println(
                "io-strategy host alloc workload=$workload heap=$heapAlloc mmap=$mappedAlloc " +
                    "bytes per round of ${prefixes.size * LOOKUPS_PER_PREFIX} lookups",
            )
            assertEquals(
                "$workload allocation diverged between io strategies " +
                    "(heap=$heapAlloc mmap=$mappedAlloc bytes per round)",
                heapAlloc,
                mappedAlloc,
            )
        }
    }

    /**
     * Fewest bytes one round of [prefixesCp] lookups allocates, per strategy, over interleaved
     * rounds after both are warm. Compiling or deoptimizing code only adds bytes (interpreted
     * frames skip escape analysis), so the minimum is the compiled path's exact cost.
     */
    private fun steadyStateRoundAllocation(
        bean: com.sun.management.ThreadMXBean,
        prefixesCp: List<String>,
    ): Map<String, Long> {
        val prefixes = prefixesCp.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        val lookups = prefixes.size * LOOKUPS_PER_PREFIX
        val threadId = Thread.currentThread().id
        val minimum = LinkedHashMap<String, Long>()
        var consumed = 0L
        repeat(ALLOCATION_ROUNDS) {
            for ((strategy, index) in strategies()) {
                val before = bean.getThreadAllocatedBytes(threadId)
                for (sample in 0 until lookups) {
                    val results = index.lookup(prefixes[sample % prefixes.size])
                    // Indexed, so the harness itself allocates no iterator.
                    for (slot in results.indices) consumed = consumed * 31 + results[slot].length
                }
                val allocated = bean.getThreadAllocatedBytes(threadId) - before
                minimum[strategy] = minOf(minimum[strategy] ?: Long.MAX_VALUE, allocated)
            }
        }
        assertTrue(consumed != Long.MIN_VALUE)
        return minimum
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
                    null, FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
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
                    null, FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                ),
            ).entryCount
            openMapped[rep] = System.nanoTime() - started
        }
        acquireHeap.sort()
        openHeap.sort()
        acquireMapped.sort()
        openMapped.sort()
        println(
            "io-strategy host cold-load (warm page cache, medians of $REPS) " +
                "acquireHeap=${fmt(median(acquireHeap))} ms openHeap=${fmt(median(openHeap))} ms " +
                "acquireMmap=${fmt(median(acquireMapped))} ms openMmap=${fmt(median(openMapped))} ms " +
                "consumed=$consumed",
        )
        // The analytic heap footprint: the heap strategy pins the whole dictionary in the
        // anonymous heap; the mmap strategy's bytes live in evictable file-backed pages.
        println(
            "io-strategy host load-footprint heapBytes=${spec.expectedRawSize} " +
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
    ) {
        val prefixes = prefixesCp.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
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
        val maxNanos = timings.last()
        println(
            "io-strategy host compute strategy=$strategy workload=$workload " +
                "median=${fmt(medianNanos)} ms p95=${fmt(p95Nanos)} ms max=${fmt(maxNanos)} ms " +
                "consumed=$consumed",
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
        // The typo workload, inlined as in FuzzyLookupComputeInstrumentationTest: the target case
        // at 3/4/5 code points, "сйл", and the 10-code-point "сцләмәтлек", the heaviest reader of
        // the dictionary bytes.
        private val TYPO_PROBES = listOf("сцл", "сцлә", "сцләм", "сйл", "сцләмәтлек")
        private const val REPS = 21
        private const val ALLOCATION_ROUNDS = 8
        private const val LOOKUPS_PER_PREFIX = 40

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
            val file = File.createTempFile("io-strategy-", ".tdict")
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
                null, FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            ),
        ).also { it.updateKeyNeighbors(TatarLayoutTestFixtures.tatarNeighborTable()) }

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
