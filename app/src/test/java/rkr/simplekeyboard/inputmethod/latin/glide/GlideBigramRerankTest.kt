package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
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
}
