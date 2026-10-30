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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Rebuilds the Python typo set and measures the production-wired Tatar index.
 * Positive recall is reported per edit class with layout neighbors and with the exact-only
 * control. Separate safety measurements cover correctly typed dictionary words and every
 * reviewed valid-OOV row by lemma stratum. Set identities and measured counts are pinned.
 */
class TypoMutatedEvalTest {

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

    internal data class EvalRow(val original: String, val editClass: String, val mutated: String)
    private data class SafetyRow(val word: String, val stratum: String)

    internal class MutatedSet(val rows: List<EvalRow>, val text: String) {
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

    internal fun buildSet(
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

    // ---- Golden vectors, shared with tests/typo_eval_pack. ----

    @Test
    fun primitivesMatchThePythonGeneratorGoldenVectors() {
        assertEquals(0x54544556L, SEED)
        assertEquals(listOf("sub" to 1, "del" to 2, "ins" to 3, "trans" to 4), CLASS_TAGS)
        assertEquals(unsigned("11854883575165184619"), streamSeed("китап", 1))
        assertEquals(666962004492003703L, streamSeed("сәләм", 3))
        assertEquals(926136100999027154L, streamSeed("бала", 2))
        assertEquals(unsigned("11225868616927759322"), streamSeed("абә", 1))
        val stream = SplitMixStream(streamSeed("китап", 1))
        assertEquals(unsigned("10703956154878274768"), stream.next())
        assertEquals(6640773307694223639L, stream.next())
        // draw1 = 6053160482508434007 for ("бала", tag 2); 6053160482508434007 mod 4 == 3.
        assertEquals(3, SplitMixStream(streamSeed("бала", 2)).index(4))
    }

    /**
     * Hand-computed tiny cases on the same fixtures as the Python test: "а" neighbors "б" and
     * "ә", "ә" neighbors "а"; the alphabet is ("а", "б", "ә"). The draw math is in the Python
     * twin of this test.
     */
    @Test
    fun mutationsMatchThePythonGeneratorGoldenVectors() {
        val geometric = mapOf(0x0430 to intArrayOf(0x0431, 0x04D9), 0x04D9 to intArrayOf(0x0430))
        val alphabet = intArrayOf(0x0430, 0x0431, 0x04D9)
        fun cps(word: String) = word.codePoints().toArray()
        fun stream(word: String, className: String) =
            SplitMixStream(streamSeed(word, CLASS_TAGS.first { it.first == className }.second))
        assertEquals("аба", mutateSub(cps("абә"), geometric, stream("абә", "sub"))!!.toCodePointString())
        assertEquals("ббба", mutateSub(cps("баба"), geometric, stream("баба", "sub"))!!.toCodePointString())
        assertNull(mutateSub(cps("бббб"), geometric, stream("бббб", "sub")))
        assertEquals("бал", mutateDel(cps("бала"), stream("бала", "del")).toCodePointString())
        assertEquals("бәала", mutateIns(cps("бала"), alphabet, stream("бала", "ins")).toCodePointString())
        assertEquals("абла", mutateTrans(cps("бала"), stream("бала", "trans"))!!.toCodePointString())
        assertEquals("аба", mutateTrans(cps("ааб"), stream("ааб", "trans"))!!.toCodePointString())
        assertNull(mutateTrans(cps("ааа"), stream("ааа", "trans")))
    }

    @Test
    fun theFixtureSetIsByteIdenticalToThePythonRun() {
        val geometric = mapOf(0x0430 to intArrayOf(0x0431, 0x04D9), 0x04D9 to intArrayOf(0x0430))
        val alphabet = intArrayOf(0x0430, 0x0431, 0x04D9)
        val set = buildSet(listOf("ааа", "абә", "бала", "бббб"), geometric, alphabet)
        assertEquals(13, set.rows.size)
        assertEquals("c138969b3ddccd5847fdba9ce57d2d8a30c0e9e53aa2de5d73eef58671396153", set.sha256)
    }

    // ---- The committed set. ----

    @Test
    fun theGeometricMapLiteralIsWellFormed() {
        // The literal below is the layout-derived relation pasted by hand; it is pinned
        // transitively by the set SHA-256, and the Python test asserts the same relation from
        // the layout resources. These assertions catch a paste error locally: symmetric,
        // sorted-ascending neighbor lists, 32 undirected pairs over 37 letters.
        assertEquals(37, GEOMETRIC_NEIGHBORS.size)
        var edges = 0
        for ((node, neighbors) in GEOMETRIC_NEIGHBORS) {
            assertTrue(neighbors.contentEquals(neighbors.sortedArray()))
            for (neighbor in neighbors) {
                edges++
                assertTrue(GEOMETRIC_NEIGHBORS.getValue(neighbor).contains(node))
            }
        }
        assertEquals(64, edges)
    }

    @Test
    fun theAlphabetIsTheLayoutNodeSet() {
        // The class #4/#ins alphabet is the neighbor table's node set (39 letters), exactly what
        // scripts/typo_pack.py reads as the layout alphabet.
        assertEquals(39, ALPHABET.size)
        assertTrue(ALPHABET.contentEquals(ALPHABET.sortedArray()))
        assertEquals(ALPHABET.size, ALPHABET.distinct().size)
    }

    @Test
    fun theMutatedSetIsByteIdenticalToThePythonRun() {
        val set = buildSet(substrate, GEOMETRIC_NEIGHBORS, ALPHABET)
        assertEquals(PIN_SUBSTRATE_WORDS, substrate.size)
        assertEquals(PIN_SET_ROWS, set.rows.size)
        assertEquals(PIN_SET_BYTES, set.bytes)
        assertEquals(PIN_SET_SHA256, set.sha256)
        // Every class is present, one row per substrate word on this input.
        val rowsPerClass = set.rows.groupingBy { it.editClass }.eachCount()
        for ((className, _) in CLASS_TAGS) {
            assertEquals(PIN_SUBSTRATE_WORDS, rowsPerClass.getValue(className))
        }
    }

    // ---- Today's recovery baseline. ----

    @Test
    fun typoRecoveryBaselinePerEditClass() {
        val index = requireNotNull(tatarIndex)
        val set = buildSet(substrate, GEOMETRIC_NEIGHBORS, ALPHABET)
        assertEquals(PIN_SET_ROWS, set.rows.size)
        assertEquals(PIN_SET_SHA256, set.sha256)

        // The hit path is the completion path: TdictPrefixIndex.lookup returns the strip cells
        // (at most MAX_RESULTS = 4), exact candidates first, typo-recovery candidates only in
        // cells the exact pass left empty. The production arm carries the layout-derived neighbor
        // table (KeyNeighborTableBuilder.fromKeyboard on device, the equivalent fixture here);
        // the control arm has no table, so only the exact pass runs.
        index.updateKeyNeighbors(neighborTable)
        val withFuzzy = countHits(index, set.rows)
        index.updateKeyNeighbors(null)
        val exactOnly = countHits(index, set.rows)

        val rowsPerClass = set.rows.groupingBy { it.editClass }.eachCount()
        var totalTop1 = 0
        var totalVisible = 0
        var totalEngine = 0
        var totalExactTop1 = 0
        var totalExactVisible = 0
        var totalExactEngine = 0
        for ((className, _) in CLASS_TAGS) {
            val classRows = rowsPerClass.getValue(className)
            val top1 = withFuzzy.top1PerClass.getValue(className)
            val visible = withFuzzy.visibleTop3PerClass.getValue(className)
            val engine = withFuzzy.engineTop4PerClass.getValue(className)
            val exactTop1 = exactOnly.top1PerClass.getValue(className)
            val exactVisible = exactOnly.visibleTop3PerClass.getValue(className)
            val exactEngine = exactOnly.engineTop4PerClass.getValue(className)
            totalTop1 += top1
            totalVisible += visible
            totalEngine += engine
            totalExactTop1 += exactTop1
            totalExactVisible += exactVisible
            totalExactEngine += exactEngine
            println("EVAL|typo_${className}_rows|$classRows")
            println("EVAL|typo_${className}_top1_hits|$top1")
            println("EVAL|typo_${className}_top1_pct|${format(top1 * 100.0 / classRows)}")
            println("EVAL|typo_${className}_visible_top3_hits|$visible")
            println("EVAL|typo_${className}_visible_top3_pct|${format(visible * 100.0 / classRows)}")
            println("EVAL|typo_${className}_engine_top4_hits|$engine")
            println("EVAL|typo_${className}_engine_top4_pct|${format(engine * 100.0 / classRows)}")
            println("EVAL|typo_${className}_top1_hits_exactonly|$exactTop1")
            println("EVAL|typo_${className}_visible_top3_hits_exactonly|$exactVisible")
            println("EVAL|typo_${className}_engine_top4_hits_exactonly|$exactEngine")
            assertTrue(top1 <= visible && visible <= engine)
        }
        println("EVAL|typo_total_rows|${set.rows.size}")
        println("EVAL|typo_total_top1_hits|$totalTop1")
        println("EVAL|typo_total_top1_pct|${format(totalTop1 * 100.0 / set.rows.size)}")
        println("EVAL|typo_total_visible_top3_hits|$totalVisible")
        println("EVAL|typo_total_visible_top3_pct|${format(totalVisible * 100.0 / set.rows.size)}")
        println("EVAL|typo_total_engine_top4_hits|$totalEngine")
        println("EVAL|typo_total_engine_top4_pct|${format(totalEngine * 100.0 / set.rows.size)}")
        println("EVAL|typo_total_top1_hits_exactonly|$totalExactTop1")
        println("EVAL|typo_total_visible_top3_hits_exactonly|$totalExactVisible")
        println("EVAL|typo_total_engine_top4_hits_exactonly|$totalExactEngine")

        assertEquals(PIN_SUB_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("sub"))
        assertEquals(PIN_DEL_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("del"))
        assertEquals(PIN_INS_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("ins"))
        assertEquals(PIN_TRANS_ENGINE_TOP4_HITS, withFuzzy.engineTop4PerClass.getValue("trans"))
        assertEquals(PIN_SUB_ENGINE_TOP4_HITS_EXACTONLY, exactOnly.engineTop4PerClass.getValue("sub"))
        assertEquals(PIN_DEL_ENGINE_TOP4_HITS_EXACTONLY, exactOnly.engineTop4PerClass.getValue("del"))
        assertEquals(PIN_INS_ENGINE_TOP4_HITS_EXACTONLY, exactOnly.engineTop4PerClass.getValue("ins"))
        assertEquals(PIN_TRANS_ENGINE_TOP4_HITS_EXACTONLY, exactOnly.engineTop4PerClass.getValue("trans"))
    }

    private class HitCount(
        val top1PerClass: Map<String, Int>,
        val visibleTop3PerClass: Map<String, Int>,
        val engineTop4PerClass: Map<String, Int>,
    )

    private fun countHits(index: TdictPrefixIndex, rows: List<EvalRow>): HitCount {
        val top1 = HashMap<String, Int>()
        val visible = HashMap<String, Int>()
        val engine = HashMap<String, Int>()
        for ((className, _) in CLASS_TAGS) {
            top1[className] = 0
            visible[className] = 0
            engine[className] = 0
        }
        for (row in rows) {
            val results = index.lookup(
                ImmutableUtf8Prefix.copyOf(row.mutated.toByteArray(Charsets.UTF_8))
            )
            if (results.firstOrNull() == row.original) {
                top1[row.editClass] = top1.getValue(row.editClass) + 1
            }
            if (results.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT)
                .contains(row.original)
            ) {
                visible[row.editClass] = visible.getValue(row.editClass) + 1
            }
            if (results.take(SuggestionStripState.CELL_COUNT).contains(row.original)) {
                engine[row.editClass] = engine.getValue(row.editClass) + 1
            }
        }
        return HitCount(top1, visible, engine)
    }

    // ---- The autocorrect false-trigger gate. ----

    /**
     * A correctly typed word must never be replaced: the index answers autocorrect only for a
     * typed string that is absent from the dictionary, so the expected count is zero. The pin is
     * exact and the assertion is an upper bound, so a regression fails loudly.
     */
    @Test
    fun autocorrectNeverReplacesACorrectlyTypedEvalWord() {
        val index = requireNotNull(tatarIndex)
        index.updateKeyNeighbors(neighborTable)
        var triggers = 0
        val offenders = ArrayList<String>()
        for (word in substrate) {
            index.lookup(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))
            val advice = index.lastAutocorrectAdvice ?: continue
            if (advice.replacement == word) continue
            triggers++
            if (offenders.size < 10) offenders.add("${advice.typedWord}->${advice.replacement}")
        }
        println("EVAL|autocorrect_correct_words|${substrate.size}")
        println("EVAL|autocorrect_false_triggers|$triggers")
        println(
            "EVAL|autocorrect_false_trigger_pct|${format(triggers * 100.0 / substrate.size)}"
        )
        if (offenders.isNotEmpty()) {
            println("EVAL|autocorrect_false_trigger_examples|${offenders.joinToString(",")}")
        }
        assertEquals(PIN_SUBSTRATE_WORDS, substrate.size)
        assertEquals(PIN_AUTOCORRECT_FALSE_TRIGGERS, triggers)
        assertTrue(
            "autocorrect false triggers $triggers exceed the pinned $PIN_AUTOCORRECT_FALSE_TRIGGERS",
            triggers <= PIN_AUTOCORRECT_FALSE_TRIGGERS,
        )
    }

    @Test
    fun validOovSafetySetMatchesItsPins() {
        val index = requireNotNull(tatarIndex)
        assertEquals(PIN_VALID_OOV_ROWS, safetyRows.size)
        assertEquals(PIN_VALID_OOV_ROWS, safetyRows.map { it.word }.distinct().size)
        assertEquals(PIN_VALID_OOV_SET_BYTES, safetyText.toByteArray(Charsets.UTF_8).size)
        assertEquals(PIN_VALID_OOV_SET_SHA256, sha256(safetyText))
        val counts = safetyRows.groupingBy { it.stratum }.eachCount()
        assertEquals(PIN_VALID_OOV_SEEN_FORM_WORDS, counts.getValue("seen_form"))
        assertEquals(PIN_VALID_OOV_NEW_FORM_WORDS, counts.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_VALID_OOV_UNSEEN_STEM_WORDS, counts.getValue("unseen_stem"))
        for (row in safetyRows) {
            val bytes = row.word.toByteArray(Charsets.UTF_8)
            assertTrue("safety word entered the dictionary: ${row.word}",
                index.indexOfWord(bytes, bytes.size) < 0)
        }
    }

    /** Measures every valid-OOV row; no corpus form is excluded from the denominator. */
    @Test
    fun autocorrectFalseReplacementsOnValidOovWords() {
        val index = requireNotNull(tatarIndex)
        index.updateKeyNeighbors(neighborTable)
        val replacements = HashMap<String, Int>()
        for (stratum in SAFETY_STRATA) replacements[stratum] = 0
        val offenders = ArrayList<String>()
        var measured = 0
        for (row in safetyRows) {
            val bytes = row.word.toByteArray(Charsets.UTF_8)
            assertTrue("safety word entered the dictionary: ${row.word}",
                index.indexOfWord(bytes, bytes.size) < 0)
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            measured++
            val advice = index.lastAutocorrectAdvice ?: continue
            replacements[row.stratum] = replacements.getValue(row.stratum) + 1
            offenders.add("${advice.typedWord}->${advice.replacement}")
        }
        for (stratum in SAFETY_STRATA) {
            val words = safetyRows.count { it.stratum == stratum }
            println("EVAL|autocorrect_valid_oov_${stratum}_words|$words")
            println("EVAL|autocorrect_valid_oov_${stratum}_false_replacements|${replacements.getValue(stratum)}")
        }
        val total = replacements.values.sum()
        println("EVAL|autocorrect_valid_oov_words|$measured")
        println("EVAL|autocorrect_valid_oov_false_replacements|$total")
        println("EVAL|autocorrect_valid_oov_false_replacement_pct|${format(total * 100.0 / measured)}")
        if (offenders.isNotEmpty()) {
            println("EVAL|autocorrect_valid_oov_false_replacement_examples|${offenders.joinToString(",")}")
        }
        assertEquals(PIN_VALID_OOV_ROWS, measured)
        assertEquals(PIN_VALID_OOV_SEEN_FORM_FALSE_REPLACEMENTS, replacements.getValue("seen_form"))
        assertEquals(PIN_VALID_OOV_NEW_FORM_FALSE_REPLACEMENTS,
            replacements.getValue("new_form_of_seen_stem"))
        assertEquals(PIN_VALID_OOV_UNSEEN_STEM_FALSE_REPLACEMENTS,
            replacements.getValue("unseen_stem"))
        assertEquals(PIN_VALID_OOV_FALSE_REPLACEMENTS, total)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun unsigned(decimal: String): Long = java.lang.Long.parseUnsignedLong(decimal)

    companion object {
        // The fixed arbitrary seed of scripts/typo_eval_pack.py; changing it changes the whole
        // set and every pin below.
        private const val SEED = 0x54544556L
        private const val MIN_WORD_CODE_POINTS = 3
        private val SPLITMIX_GAMMA = 0x9E3779B97F4A7C15uL.toLong()

        // Class tags in the generator's class order; they salt each (word, class) stream.
        private val CLASS_TAGS = listOf("sub" to 1, "del" to 2, "ins" to 3, "trans" to 4)

        // The geometric key-neighbor relation of the Tatar layout, pasted from
        // scripts/typo_pack.py's read_layout_geometric_map (neighbor lists sorted ascending).
        internal val GEOMETRIC_NEIGHBORS: Map<Int, IntArray> = mapOf(
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

        // Pins over the committed dictionary, eval set and layout; re-pin when an input changes.
        private const val PIN_SUBSTRATE_WORDS = 2_381
        private const val PIN_SET_ROWS = 9_524
        private const val PIN_SET_BYTES = 310_498
        private const val PIN_SET_SHA256 =
            "8b60e4b146981d737b351abbbdfd478a3aaa5eedb51a8769ff0cab54c5b5636e"

        // Measured baseline recovery counts (the production arm, then the exact-only control).
        private const val PIN_SUB_ENGINE_TOP4_HITS = 2_194
        private const val PIN_DEL_ENGINE_TOP4_HITS = 1_841
        private const val PIN_INS_ENGINE_TOP4_HITS = 2_340
        private const val PIN_TRANS_ENGINE_TOP4_HITS = 2_177
        private const val PIN_SUB_ENGINE_TOP4_HITS_EXACTONLY = 0
        private const val PIN_DEL_ENGINE_TOP4_HITS_EXACTONLY = 349
        private const val PIN_INS_ENGINE_TOP4_HITS_EXACTONLY = 0
        private const val PIN_TRANS_ENGINE_TOP4_HITS_EXACTONLY = 0
        private const val PIN_AUTOCORRECT_FALSE_TRIGGERS = 0

        private const val PIN_VALID_OOV_ROWS = 175
        private const val PIN_VALID_OOV_SET_BYTES = 6_157
        private const val PIN_VALID_OOV_SET_SHA256 =
            "e843019198ef8df8d9d4feef1895e3048dbd842e1fc3c0820d7dce8caaa2761d"
        private const val PIN_VALID_OOV_SEEN_FORM_WORDS = 12
        private const val PIN_VALID_OOV_NEW_FORM_WORDS = 59
        private const val PIN_VALID_OOV_UNSEEN_STEM_WORDS = 104
        private const val PIN_VALID_OOV_SEEN_FORM_FALSE_REPLACEMENTS = 0
        private const val PIN_VALID_OOV_NEW_FORM_FALSE_REPLACEMENTS = 2
        private const val PIN_VALID_OOV_UNSEEN_STEM_FALSE_REPLACEMENTS = 0
        private const val PIN_VALID_OOV_FALSE_REPLACEMENTS = 2

        private val SAFETY_STRATA = listOf("seen_form", "new_form_of_seen_stem", "unseen_stem")
        private val neighborTable = E3bTestFixtures.tatarNeighborTable()
        internal val ALPHABET: IntArray get() = neighborTable.nodes
        private var tatarIndex: TdictPrefixIndex? = null
        private lateinit var vocabulary: List<String>
        internal lateinit var substrate: List<String>
        private lateinit var safetyText: String
        private lateinit var safetyRows: List<SafetyRow>

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
            val rawFile = File.createTempFile("typo-eval-", ".tdict")
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
                // The production Tatar wiring: suffix rules + the TATAR fuzzy policy.
                tatarIndex = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize, TatarSuffixRules,
                        FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                )
                vocabulary = DictionaryTestFixtures.words(raw)
                check(vocabulary.size == spec.expectedEntryCount.toInt())
            } finally {
                rawFile.delete()
            }
            substrate = vocabulary.filter { word ->
                word.codePointCount(0, word.length) >= MIN_WORD_CODE_POINTS && word in evalWords
            }
            safetyText = locate(
                "src/test/resources/tt_valid_oov_safety_set.tsv",
                "app/src/test/resources/tt_valid_oov_safety_set.tsv",
            ).readText(Charsets.UTF_8)
            safetyRows = safetyText.split("\n").filter { it.isNotEmpty() }.map { line ->
                val fields = line.split("\t")
                check(fields.size == 2) { "malformed valid-OOV safety row: $line" }
                check(fields[0] in evalWords) { "safety word is not held out: ${fields[0]}" }
                check(fields[1] in SAFETY_STRATA) { "unknown safety stratum: ${fields[1]}" }
                SafetyRow(fields[0], fields[1])
            }
            check(safetyRows.isNotEmpty()) { "the valid-OOV safety set is empty" }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")
    }
}
