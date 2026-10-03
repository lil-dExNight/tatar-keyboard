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

package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * The bigram channel on a decode's N-best (the SHARK2 language-model channel): each candidate's
 * confidence is multiplied by the Gaussian-transformed bigram probability of the previous
 * committed word. The bundled schema-3 table stores successors in count order without counts, so
 * the probability reads off the rank: each rank halves it, and the Gaussian bump over that
 * log-spaced ladder is an exponential penalty per rank — score x [rankPenalty]^rank, with an
 * absent candidate one rank past the last successor.
 *
 * The channel touches only the current gesture's N-best and the previous committed word; it never
 * revises committed text.
 */
object GlideBigramRerank {

    /**
     * Reorders [result]'s slots in place by the adjusted scores, stable on ties. [adjusted] is
     * caller scratch of at least [GlideResult.count] elements. A no-op for fewer than two
     * candidates, no successors, or a penalty below 1 (the channel off).
     */
    fun rerank(result: GlideResult, successors: List<String>, rankPenalty: Float, adjusted: FloatArray) {
        val count = result.count
        if (count <= 1 || rankPenalty <= 1f || successors.isEmpty()) return
        for (slot in 0 until count) {
            val rank = successors.indexOf(result.words[slot]).let { if (it < 0) successors.size else it }
            var penalty = 1f
            repeat(rank) { penalty *= rankPenalty }
            adjusted[slot] = result.scores[slot] * penalty
        }
        // Stable insertion sort over the slots (count <= TOP_N): ties keep the pre-channel order.
        var i = 1
        while (i < count) {
            val adjustedI = adjusted[i]
            val word = result.words[i]
            val score = result.scores[i]
            var j = i - 1
            while (j >= 0 && adjusted[j] > adjustedI) {
                adjusted[j + 1] = adjusted[j]
                result.words[j + 1] = result.words[j]
                result.scores[j + 1] = result.scores[j]
                j--
            }
            adjusted[j + 1] = adjustedI
            result.words[j + 1] = word
            result.scores[j + 1] = score
            i++
        }
    }
}
