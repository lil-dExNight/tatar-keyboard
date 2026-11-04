package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [GlideBigramRerank]: the rank penalty, the absent rule, stability, the no-ops. */
class GlideBigramRerankTest {

    private fun resultOf(vararg pairs: Pair<String, Float>): GlideResult {
        val result = GlideResult()
        result.count = pairs.size
        for ((index, pair) in pairs.withIndex()) {
            result.words[index] = pair.first
            result.scores[index] = pair.second
        }
        return result
    }

    private fun wordsOf(result: GlideResult): List<String> =
        (0 until result.count).map { result.words[it]!! }

    @Test
    fun aSuccessorBeatsAnAbsentLeader() {
        val result = resultOf("китап" to 1000f, "сәләм" to 1200f)
        GlideBigramRerank.rerank(result, listOf("сәләм"), 2f, FloatArray(GlideDecoder.TOP_N))
        // The leader's confidence grows by the absent penalty (one past the last rank): 1000 x 8.
        assertEquals(listOf("сәләм", "китап"), wordsOf(result))
    }

    @Test
    fun rankOrderOfTheSuccessorsDecides() {
        // Scores closer than one penalty step apart: the better-ranked successor wins.
        val result = resultOf("китап" to 1000f, "сәләм" to 1500f, "алма" to 1900f)
        GlideBigramRerank.rerank(result, listOf("алма", "сәләм"), 2f, FloatArray(GlideDecoder.TOP_N))
        // adjusted: китап 8000, сәләм 3000, алма 1900
        assertEquals(listOf("алма", "сәләм", "китап"), wordsOf(result))
    }

    @Test
    fun aBigEnoughScoreGapSurvivesTheChannel() {
        val result = resultOf("китап" to 1000f, "сәләм" to 9000f)
        GlideBigramRerank.rerank(result, listOf("сәләм"), 2f, FloatArray(GlideDecoder.TOP_N))
        assertEquals(listOf("китап", "сәләм"), wordsOf(result))
    }

    @Test
    fun noContextNoPenaltyNoSecondCandidateNoop() {
        val result = resultOf("китап" to 1000f, "сәләм" to 1200f)
        GlideBigramRerank.rerank(result, emptyList(), 2f, FloatArray(GlideDecoder.TOP_N))
        assertEquals(listOf("китап", "сәләм"), wordsOf(result))
        GlideBigramRerank.rerank(result, listOf("сәләм"), 1f, FloatArray(GlideDecoder.TOP_N))
        assertEquals(listOf("китап", "сәләм"), wordsOf(result))
        val single = resultOf("китап" to 1000f)
        GlideBigramRerank.rerank(single, listOf("китап"), 2f, FloatArray(GlideDecoder.TOP_N))
        assertEquals(listOf("китап"), wordsOf(single))
    }

    @Test
    fun tiesKeepThePreChannelOrder() {
        val result = resultOf("китап" to 1000f, "сәләм" to 1000f)
        GlideBigramRerank.rerank(result, emptyList(), 2f, FloatArray(GlideDecoder.TOP_N))
        assertEquals(listOf("китап", "сәләм"), wordsOf(result))
    }

    /**
     * A context word with a thousand learned successors: only the first
     * [GlideBigramRerank.MAX_RANKED_SUCCESSORS] rank, so the penalty stays finite and a much
     * better shape still beats the top successor once the gap exceeds the largest penalty.
     */
    @Test
    fun aLongSuccessorListKeepsThePenaltyBounded() {
        val successors = ArrayList<String>()
        successors.add("сәләм")
        for (index in 1 until 1000) successors.add("сүз$index")
        successors.add("алма")
        val penalty = 3f
        val maxPenalty = Math.pow(penalty.toDouble(), GlideBigramRerank.MAX_RANKED_SUCCESSORS.toDouble())
        // китап is absent; сәләм is the first successor, scored worse than the largest penalty
        // allows; алма sits far past the ranked successors and counts as absent.
        val result = resultOf(
            "китап" to 1000f,
            "сәләм" to (1000.0 * maxPenalty * 2).toFloat(),
            "алма" to 1100f,
        )
        val adjusted = FloatArray(GlideDecoder.TOP_N)
        GlideBigramRerank.rerank(result, successors, penalty, adjusted)

        for (slot in 0 until result.count) {
            assertTrue("adjusted score $slot is ${adjusted[slot]}", adjusted[slot].isFinite())
        }
        assertEquals(listOf("китап", "алма", "сәләм"), wordsOf(result))
    }

    @Test
    fun aShortListRanksExactlyAsBefore() {
        // Up to the cap the absent rank is the list size, as for the bundled table's answer.
        val result = resultOf("китап" to 1000f, "сәләм" to 1500f, "алма" to 1900f)
        GlideBigramRerank.rerank(
            result, listOf("алма", "сәләм", "a", "b", "c", "d", "e", "f"), 2f,
            FloatArray(GlideDecoder.TOP_N),
        )
        // adjusted: китап 1000 x 2^8, сәләм 1500 x 2, алма 1900
        assertEquals(listOf("алма", "сәләм", "китап"), wordsOf(result))
    }

    // --- the lookup-form comparison of the rank lookup -----------------------------------------

    @Test
    fun aCasedSuccessorStillTakesItsRank() {
        // A personal word's saved casing ("Миша", the raw form the decoder's wordAt returns)
        // must match its normalized successor spelling ("миша"): it takes the rank-0 boost
        // and overtakes the absent leader, as in aSuccessorBeatsAnAbsentLeader.
        val result = resultOf("китап" to 1000f, "Миша" to 1200f)
        GlideBigramRerank.rerank(result, listOf("миша"), 2f, FloatArray(GlideDecoder.TOP_N))
        // adjusted: Миша 1200, китап 1000 x 2
        assertEquals(listOf("Миша", "китап"), wordsOf(result))
    }

    @Test
    fun anUnrelatedCasedWordStillTakesThePenalty() {
        // The fold changes only the spelling comparison: a cased word that is no successor
        // keeps the absent penalty (one past the last rank), so the channel still flips the
        // order, as in aSuccessorBeatsAnAbsentLeader.
        val result = resultOf("Миша" to 1000f, "сәләм" to 1200f)
        GlideBigramRerank.rerank(result, listOf("сәләм"), 2f, FloatArray(GlideDecoder.TOP_N))
        // adjusted: Миша 1000 x 2, сәләм 1200
        assertEquals(listOf("сәләм", "Миша"), wordsOf(result))
    }

    @Test
    fun aCasedSuccessorListEntryStillMatchesTheLowercaseNBest() {
        // The mirror case: the fold is symmetric, so a successor spelled with casing matches
        // the decode's lowercase N-best. The production successor list is normalized (the
        // bundled table stores and the learned pairs hand the lookup form), so this pins the
        // seam's contract rather than a wired path.
        val result = resultOf("китап" to 1000f, "сәләм" to 1500f, "алма" to 1900f)
        GlideBigramRerank.rerank(
            result, listOf("Алма", "Сәләм"), 2f, FloatArray(GlideDecoder.TOP_N),
        )
        // adjusted: алма 1900, сәләм 1500 x 2, китап 1000 x 4
        assertEquals(listOf("алма", "сәләм", "китап"), wordsOf(result))
    }

    @Test
    fun casedSuccessorsKeepTheRankArithmetic() {
        // Successors with distinct casings among a full ranked list: the rank comes from the
        // list position, and the fold decides only whether a candidate matches at all, so the
        // penalty arithmetic is the one the rank-order test pins with lowercase spellings.
        val result = resultOf("китап" to 1000f, "сәләм" to 1500f, "алма" to 1900f)
        val successors = listOf("a", "b", "АЛМА", "сәләм", "e", "f", "g", "h")
        GlideBigramRerank.rerank(result, successors, 2f, FloatArray(GlideDecoder.TOP_N))
        // adjusted: алма 1900 x 4 (rank 2), сәләм 1500 x 8 (rank 3), китап 1000 x 256 (absent).
        assertEquals(listOf("алма", "сәләм", "китап"), wordsOf(result))
    }
}
