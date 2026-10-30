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

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Calibration of the shared DL-1 typo-recovery tier (substitution, deletion, insertion and
 * transposition under one rank tag, engaged on an empty exact pass at >= 4 code points) on the
 * committed Tatar dictionary. The typo set is the typo_eval_pack mutated set rebuilt on the JVM
 * bit-for-bit — the same generator [TypoMutatedEvalTest] pins. Measured values are printed raw
 * ("DL1 ..." lines) and asserted against the pre-registered bars: per-class top-3 recovery
 * (sub / del / ins / trans), zero autocorrect false triggers, activation <= 25 % of the >= 4-cp
 * eval prefixes, host p95 <= 5 ms, allocation <= 8 bytes per lookup, zero fuzzy-budget trips.
 */
class TtDl1TierCalibrationTest {

    // ---- Portable deterministic primitives (bit-identical to scripts/typo_eval_pack.py). ----

    private fun fnv1a64(data: ByteArray): Long {
        var hash = 0xCBF29CE484222325uL.toLong()
        for (byte in data) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001B3L
        }
        return hash
    }

    private fun splitmix64(seed: Long): Long {
        var z = seed + SPLITMIX_GAMMA
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /**
     * One private SplitMix64 stream for a (word, class) pair: [next] returns the current mixed
     * state, then advances the state by the gamma — the sequence scripts/typo_eval_pack.py walks.
     */
    private inner class SplitMixStream(seed: Long) {
        private var state = seed

        fun next(): Long {
            val output = splitmix64(state)
            state += SPLITMIX_GAMMA
            return output
        }

        /** The next output mapped to `[0, choices)` by unsigned modulo (Python's `%`). */
        fun index(choices: Int): Int {
            require(choices > 0) { "selection over an empty choice set" }
            return java.lang.Long.remainderUnsigned(next(), choices.toLong()).toInt()
        }
    }

    private fun streamSeed(word: String, classTag: Int): Long =
        splitmix64(SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))) xor classTag.toLong()

    // ---- The four mutation primitives, mirroring the Python generator. ----

    /** `sub`: one code point replaced by a geometric neighbor; position draw, neighbor draw. */
    private fun mutateSub(
        codePoints: IntArray,
        geometric: Map<Int, IntArray>,
        stream: SplitMixStream,
    ): IntArray? {
        val positions = ArrayList<Int>(codePoints.size)
        for (position in codePoints.indices) {
            if (geometric[codePoints[position]]?.isNotEmpty() == true) positions.add(position)
        }
        if (positions.isEmpty()) return null
        val position = positions[stream.index(positions.size)]
        val neighbors = geometric.getValue(codePoints[position])
        val mutated = codePoints.copyOf()
        mutated[position] = neighbors[stream.index(neighbors.size)]
        return mutated
    }

    /** `del`: one code point removed; a single position draw. */
    private fun mutateDel(codePoints: IntArray, stream: SplitMixStream): IntArray {
        val position = stream.index(codePoints.size)
        val mutated = IntArray(codePoints.size - 1)
        codePoints.copyInto(mutated, 0, 0, position)
        codePoints.copyInto(mutated, position, position + 1)
        return mutated
    }

    /** `ins`: one alphabet letter inserted; position draw (0..size), then the letter draw. */
    private fun mutateIns(codePoints: IntArray, alphabet: IntArray, stream: SplitMixStream): IntArray {
        val position = stream.index(codePoints.size + 1)
        val letter = alphabet[stream.index(alphabet.size)]
        val mutated = IntArray(codePoints.size + 1)
        codePoints.copyInto(mutated, 0, 0, position)
        mutated[position] = letter
        codePoints.copyInto(mutated, position + 1, position)
        return mutated
    }

    /** `trans`: two adjacent distinct code points swapped; a single pivot draw. */
    private fun mutateTrans(codePoints: IntArray, stream: SplitMixStream): IntArray? {
        val pivots = ArrayList<Int>(codePoints.size - 1)
        for (pivot in 0 until codePoints.size - 1) {
            if (codePoints[pivot] != codePoints[pivot + 1]) pivots.add(pivot)
        }
        if (pivots.isEmpty()) return null
        val pivot = pivots[stream.index(pivots.size)]
        val mutated = codePoints.copyOf()
        val held = mutated[pivot]
        mutated[pivot] = mutated[pivot + 1]
        mutated[pivot + 1] = held
        return mutated
    }

    private fun IntArray.toCodePointString(): String {
        val builder = StringBuilder(size)
        for (codePoint in this) builder.appendCodePoint(codePoint)
        return builder.toString()
    }

    // ---- The mutated set. ----

    private data class EvalRow(val original: String, val editClass: String, val mutated: String)

    private class MutatedSet(val rows: List<EvalRow>, val text: String) {
        val bytes: Int get() = text.toByteArray(Charsets.UTF_8).size
        val sha256: String
            get() = MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    /** Code-point lexicographic order: Python's string sort, which UTF-16 order matches on BMP. */
    private fun compareCodePoints(first: String, second: String): Int {
        val a = first.codePoints().toArray()
        val b = second.codePoints().toArray()
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            if (a[index] != b[index]) return if (a[index] < b[index]) -1 else 1
        }
        return a.size - b.size
    }

    private fun buildSet(
        words: List<String>,
        geometric: Map<Int, IntArray>,
        alphabet: IntArray,
    ): MutatedSet {
        val rows = ArrayList<EvalRow>(words.size * CLASS_TAGS.size)
        for (word in words) {
            val codePoints = word.codePoints().toArray()
            for ((className, tag) in CLASS_TAGS) {
                val stream = SplitMixStream(streamSeed(word, tag))
                val mutated = when (className) {
                    "sub" -> mutateSub(codePoints, geometric, stream)
                    "del" -> mutateDel(codePoints, stream)
                    "ins" -> mutateIns(codePoints, alphabet, stream)
                    "trans" -> mutateTrans(codePoints, stream)
                    else -> error("unknown class $className")
                } ?: continue
                rows.add(EvalRow(word, className, mutated.toCodePointString()))
            }
        }
        check(rows.isNotEmpty()) { "no substrate word yielded a mutation" }
        rows.sortWith { first, second ->
            val byOriginal = compareCodePoints(first.original, second.original)
            if (byOriginal != 0) byOriginal else compareCodePoints(first.editClass, second.editClass)
        }
        val text = rows.joinToString("") { "${it.original}\t${it.editClass}\t${it.mutated}\n" }
        return MutatedSet(rows, text)
    }

    // ---- The set identity: the same input pins [TypoMutatedEvalTest] carries. ----

    @Test
    fun theMutatedSetIsByteIdenticalToTheGeneratorRun() {
        val set = buildSet(substrate, GEOMETRIC_NEIGHBORS, ALPHABET)
        assertEquals(PIN_SUBSTRATE_WORDS, substrate.size)
        assertEquals(PIN_SET_ROWS, set.rows.size)
        assertEquals(PIN_SET_BYTES, set.bytes)
        assertEquals(PIN_SET_SHA256, set.sha256)
    }

    // ---- The pre-registered gates. ----

    /**
     * Per-class top-3 recovery on the mutated set, under the production Tatar wiring. Bars:
     * del >= 45 % (previous pin 19.8 %), ins >= 70 % (0 %), trans >= 60 % (0 %), sub >= 90.0 %
     * (pin 91.6 %, tolerance -1.6 pp). The fuzzy budget must never trip on the eval set.
     */
    @Test
    fun gateRecoveryPerClassMeetsThePreregisteredBars() {
        val index = requireNotNull(tatarIndex)
        val set = buildSet(substrate, GEOMETRIC_NEIGHBORS, ALPHABET)
        assertEquals(PIN_SET_ROWS, set.rows.size)
        assertEquals(PIN_SET_SHA256, set.sha256)

        val hits = LinkedHashMap<String, Int>(CLASS_TAGS.size)
        for ((className, _) in CLASS_TAGS) hits[className] = 0
        var overBudget = 0
        for (row in set.rows) {
            val results = index.lookup(
                ImmutableUtf8Prefix.copyOf(row.mutated.toByteArray(Charsets.UTF_8))
            )
            if (index.lastFuzzyOverBudget) overBudget++
            if (results.contains(row.original)) hits[row.editClass] = hits.getValue(row.editClass) + 1
        }
        val rowsPerClass = set.rows.groupingBy { it.editClass }.eachCount()
        val rates = LinkedHashMap<String, Double>(CLASS_TAGS.size)
        for ((className, _) in CLASS_TAGS) {
            val classRows = rowsPerClass.getValue(className)
            val classHits = hits.getValue(className)
            rates[className] = classHits * 100.0 / classRows
            // Raw calibration line (grep target: "DL1 recovery").
            println(
                "DL1 recovery@3 class=$className rows=$classRows hits=$classHits " +
                    "rate=${fmt(rates.getValue(className))}%",
            )
        }
        println("DL1 over_budget=$overBudget set_lookups=${set.rows.size}")

        assertTrue(
            "sub recovery ${fmt(rates.getValue("sub"))}% is below the 90.0% bar",
            rates.getValue("sub") >= 90.0,
        )
        assertTrue(
            "del recovery ${fmt(rates.getValue("del"))}% is below the 45% bar",
            rates.getValue("del") >= 45.0,
        )
        assertTrue(
            "ins recovery ${fmt(rates.getValue("ins"))}% is below the 70% bar",
            rates.getValue("ins") >= 70.0,
        )
        assertTrue(
            "trans recovery ${fmt(rates.getValue("trans"))}% is below the 60% bar",
            rates.getValue("trans") >= 60.0,
        )
        assertEquals("the fuzzy budget must never trip on the eval set", 0, overBudget)
    }

    /**
     * The autocorrect path is structurally untouched (the advice classes stay {1}): a correctly
     * typed substrate word must never name a replacement.
     */
    @Test
    fun gateAutocorrectFalseTriggersStayZero() {
        val index = requireNotNull(tatarIndex)
        var triggers = 0
        val offenders = ArrayList<String>()
        for (word in substrate) {
            index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
            val advice = index.lastAutocorrectAdvice ?: continue
            if (advice.replacement == word) continue
            triggers++
            if (offenders.size < 10) offenders.add("${advice.typedWord}->${advice.replacement}")
        }
        // Raw calibration line (grep target: "DL1 autocorrect").
        println("DL1 autocorrect correct_words=${substrate.size} false_triggers=$triggers")
        if (offenders.isNotEmpty()) {
            println("DL1 autocorrect false_trigger_examples=${offenders.joinToString(",")}")
        }
        assertEquals("autocorrect false triggers must stay zero", 0, triggers)
    }

    /**
     * The tier engages exactly where the exact pass is empty, so its activation rate is the share
     * of >= 4-cp eval prefixes with an empty exact pass; the bar is <= 25 %. The fill rate among
     * activated prefixes is reported without a threshold.
     */
    @Test
    fun gateActivationRateStaysUnder25Percent() {
        val index = requireNotNull(tatarIndex)
        var emptyExact = 0
        var tierFilled = 0
        for (prefix in evalPrefixes) {
            val results = index.lookup(
                ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            )
            if (index.lastExactCount != 0) continue
            emptyExact++
            if (results.isNotEmpty()) tierFilled++
        }
        val activationRate = emptyExact * 100.0 / evalPrefixes.size
        // Raw calibration line (grep target: "DL1 activation").
        println(
            "DL1 activation eval_prefixes_>=4cp=${evalPrefixes.size} exact_empty=$emptyExact " +
                "(${fmt(activationRate)}%) tier_filled=$tierFilled " +
                "(${fmt(tierFilled * 100.0 / emptyExact)}% of empty)",
        )
        assertEquals(PIN_EVAL_PREFIXES_GE4, evalPrefixes.size)
        assertTrue(
            "tier activation ${fmt(activationRate)}% exceeds the 25% bar",
            activationRate <= 25.0,
        )
    }

    /**
     * Host compute with the tier engaged: a deterministic sample of the mutated set (every 5th
     * row of >= 4 code points), two warm-up and two measured rounds, p95 <= 5 ms. Probe, variant
     * and visited percentiles of the tier-active lookups are reported from a fresh pass.
     */
    @Test
    fun gateHostP95WithTheTierEngagedStaysUnder5ms() {
        val index = requireNotNull(tatarIndex)
        val set = buildSet(substrate, GEOMETRIC_NEIGHBORS, ALPHABET)
        val sample = set.rows
            .filter { it.mutated.codePointCount(0, it.mutated.length) >= 4 }
            .filterIndexed { rowIndex, _ -> rowIndex % 5 == 0 }
            .map { ImmutableUtf8Prefix.copyOf(it.mutated.toByteArray(Charsets.UTF_8)) }
        assertTrue("the perf sample is meaningful", sample.size > 1_000)

        repeat(2) { for (prefix in sample) index.lookup(prefix) }
        val timings = LongArray(sample.size * 2)
        var consumed = 0
        var at = 0
        repeat(2) {
            for (prefix in sample) {
                val started = System.nanoTime()
                val results = index.lookup(prefix)
                timings[at++] = System.nanoTime() - started
                consumed = consumed xor results.size
            }
        }
        // Counters of the tier-active lookups (empty exact pass) from a fresh single pass.
        val probeCounts = ArrayList<Int>(sample.size)
        val variantCounts = ArrayList<Int>(sample.size)
        val visitedCounts = ArrayList<Int>(sample.size)
        var active = 0
        var overBudget = 0
        for (prefix in sample) {
            index.lookup(prefix)
            if (index.lastExactCount != 0) continue
            active++
            probeCounts.add(index.lastFuzzyProbeCount)
            variantCounts.add(index.lastFuzzyVariantCount)
            visitedCounts.add(index.lastFuzzyVisitedCount)
            if (index.lastFuzzyOverBudget) overBudget++
        }
        timings.sort()
        val p50 = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        val max = timings.last() / 1_000_000.0
        // Raw calibration line (grep target: "DL1 perf").
        println(
            "DL1 perf host samples=${timings.size} tier_active=$active " +
                "p50=${fmtMs(p50)} ms p95=${fmtMs(p95)} ms max=${fmtMs(max)} ms " +
                "probe_p95=${percentile(probeCounts.sorted(), 0.95)} probe_max=${probeCounts.maxOrNull() ?: 0} " +
                "variant_p95=${percentile(variantCounts.sorted(), 0.95)} variant_max=${variantCounts.maxOrNull() ?: 0} " +
                "visited_p95=${percentile(visitedCounts.sorted(), 0.95)} visited_max=${visitedCounts.maxOrNull() ?: 0} " +
                "over_budget=$overBudget consumed=$consumed",
        )
        assertTrue("tier-engaged p95=${fmtMs(p95)}ms exceeds the 5 ms budget", p95 <= 5.0)
        assertEquals("the fuzzy budget must never trip on the perf sample", 0, overBudget)
    }

    /**
     * Zero-allocation contract with the tier engaged: both prefixes return the SAME empty result
     * (result materialization is out of scope). "ъъ" never engages the tier (under 3 code
     * points); "ъъъъ" engages it (no Tatar word contains ъ, so every probe misses) — the delta is
     * the tier's own cost, and the absolute bar is <= 8 bytes per lookup.
     */
    @Test
    fun gateAllocationStaysWithin8BytesPerLookup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val index = requireNotNull(tatarIndex)
        val fewProbes = ImmutableUtf8Prefix.copyOf("ъъ".toByteArray(Charsets.UTF_8))
        val manyProbes = ImmutableUtf8Prefix.copyOf("ъъъъ".toByteArray(Charsets.UTF_8))
        assertTrue(index.lookup(fewProbes).isEmpty())
        assertTrue(index.lookup(manyProbes).isEmpty())
        assertTrue("premise: the tier engages on the heavy arm", index.lastFuzzyProbeCount > 0)

        repeat(50_000) {
            index.lookup(fewProbes)
            index.lookup(manyProbes)
        }

        val iterations = 200_000
        fun perLookupBytes(prefix: ImmutableUtf8Prefix): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) index.lookup(prefix)
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        val few = perLookupBytes(fewProbes)
        val many = perLookupBytes(manyProbes)
        // Raw calibration line (grep target: "DL1 alloc").
        println("DL1 alloc few=$few many=$many bytes/lookup")
        assertTrue("few=$few many=$many bytes/lookup", abs(many - few) <= 8L)
        assertTrue("many=$many bytes/lookup exceeds the 8-byte budget", many <= 8L)
    }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        if (sorted.isEmpty()) return 0
        val rank = maxOf(1, ceil(sorted.size * fraction).toInt())
        return sorted[rank - 1]
    }

    private fun fmt(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)
    private fun fmtMs(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    companion object {
        // The fixed arbitrary seed of scripts/typo_eval_pack.py; changing it changes the whole
        // set and every measurement above.
        private const val SEED = 0x54544556L
        private const val MIN_WORD_CODE_POINTS = 3
        private val SPLITMIX_GAMMA = 0x9E3779B97F4A7C15uL.toLong()

        // Class tags in the generator's class order; they salt each (word, class) stream.
        private val CLASS_TAGS = listOf("sub" to 1, "del" to 2, "ins" to 3, "trans" to 4)

        // The geometric key-neighbor relation of the Tatar layout, pasted from
        // scripts/typo_pack.py's read_layout_geometric_map (neighbor lists sorted ascending);
        // TypoMutatedEvalTest carries the same literal and pins its well-formedness.
        private val GEOMETRIC_NEIGHBORS: Map<Int, IntArray> = mapOf(
            0x0430 to intArrayOf(0x043A, 0x0441), // "а" -> "кс"
            0x0431 to intArrayOf(0x0434), // "б" -> "д"
            0x0432 to intArrayOf(0x0443, 0x0447), // "в" -> "уч"
            0x0433 to intArrayOf(0x043E, 0x0497), // "г" -> "оҗ"
            0x0434 to intArrayOf(0x0431, 0x0449), // "д" -> "бщ"
            0x0435 to intArrayOf(0x043F, 0x04AF), // "е" -> "пү"
            0x0436 to intArrayOf(0x0437, 0x044E), // "ж" -> "зю"
            0x0437 to intArrayOf(0x0436, 0x04BB), // "з" -> "жһ"
            0x0438 to intArrayOf(0x0440), // "и" -> "р"
            0x0439 to intArrayOf(0x0444, 0x04D9), // "й" -> "фә"
            0x043A to intArrayOf(0x0430, 0x04E9), // "к" -> "аө"
            0x043B to intArrayOf(0x0448, 0x044C), // "л" -> "шь"
            0x043C to intArrayOf(0x043F), // "м" -> "п"
            0x043D to intArrayOf(0x0440, 0x0497, 0x04AF), // "н" -> "рҗү"
            0x043E to intArrayOf(0x0433, 0x0442), // "о" -> "гт"
            0x043F to intArrayOf(0x0435, 0x043C), // "п" -> "ем"
            0x0440 to intArrayOf(0x0438, 0x043D), // "р" -> "ин"
            0x0441 to intArrayOf(0x0430), // "с" -> "а"
            0x0442 to intArrayOf(0x043E), // "т" -> "о"
            0x0443 to intArrayOf(0x0432, 0x04E9), // "у" -> "вө"
            0x0444 to intArrayOf(0x0439), // "ф" -> "й"
            0x0445 to intArrayOf(0x044D, 0x04BB), // "х" -> "эһ"
            0x0446 to intArrayOf(0x044B, 0x04D9), // "ц" -> "ыә"
            0x0447 to intArrayOf(0x0432), // "ч" -> "в"
            0x0448 to intArrayOf(0x043B, 0x04A3), // "ш" -> "лң"
            0x0449 to intArrayOf(0x0434, 0x04A3), // "щ" -> "дң"
            0x044B to intArrayOf(0x0446, 0x044F), // "ы" -> "ця"
            0x044C to intArrayOf(0x043B), // "ь" -> "л"
            0x044D to intArrayOf(0x0445), // "э" -> "х"
            0x044E to intArrayOf(0x0436), // "ю" -> "ж"
            0x044F to intArrayOf(0x044B), // "я" -> "ы"
            0x0497 to intArrayOf(0x0433, 0x043D), // "җ" -> "гн"
            0x04A3 to intArrayOf(0x0448, 0x0449), // "ң" -> "шщ"
            0x04AF to intArrayOf(0x0435, 0x043D), // "ү" -> "ен"
            0x04BB to intArrayOf(0x0437, 0x0445), // "һ" -> "зх"
            0x04D9 to intArrayOf(0x0439, 0x0446), // "ә" -> "йц"
            0x04E9 to intArrayOf(0x043A, 0x0443), // "ө" -> "ку"
        )

        // Input pins over the committed dictionary, eval set and layout — the same identities
        // TypoMutatedEvalTest pins; re-pin when an input changes.
        private const val PIN_SUBSTRATE_WORDS = 2_381
        private const val PIN_SET_ROWS = 9_524
        private const val PIN_SET_BYTES = 310_498
        private const val PIN_SET_SHA256 =
            "8b60e4b146981d737b351abbbdfd478a3aaa5eedb51a8769ff0cab54c5b5636e"
        private const val PIN_EVAL_PREFIXES_GE4 = 7_451

        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
        private val ALPHABET: IntArray get() = neighborTable.nodes
        private var tatarIndex: TdictPrefixIndex? = null
        private lateinit var vocabulary: List<String>
        private lateinit var substrate: List<String>
        private lateinit var evalPrefixes: List<String>

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndEvalSet() {
            val evalWords = HashSet<String>()
            for (line in locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                for (token in trimmed.split(" ")) if (token.isNotEmpty()) evalWords.add(token)
            }
            check(evalWords.isNotEmpty()) { "the eval set has no words" }

            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val rawFile = File.createTempFile("tt-dl1-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                val raw = rawFile.readBytes()
                // The production Tatar wiring: suffix rules + the TATAR fuzzy policy + the
                // layout-derived neighbor table.
                tatarIndex = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize, TatarSuffixRules,
                        FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                )
                tatarIndex!!.updateKeyNeighbors(neighborTable)
                vocabulary = DictionaryTestFixtures.words(raw)
                check(vocabulary.size == spec.expectedEntryCount.toInt())
            } finally {
                rawFile.delete()
            }
            substrate = vocabulary.filter { word ->
                word.codePointCount(0, word.length) >= MIN_WORD_CODE_POINTS && word in evalWords
            }

            // The activation prefix set: every distinct >= 4-cp prefix of every unique eval word
            // (shorter prefixes can never meet the tier gate).
            evalPrefixes = evalWords.flatMap { word ->
                val codePoints = word.codePoints().toArray()
                (4..codePoints.size).map { length ->
                    val builder = StringBuilder(length)
                    for (slot in 0 until length) builder.appendCodePoint(codePoints[slot])
                    builder.toString()
                }
            }.distinct()
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
