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

/**
 * TT-NEXTWORD-FILL (docs/TT-NEXTWORD-FILL.md): the read side of the global top-frequency
 * fallback — the N most frequent words of an engine's own dictionary, in the frozen ranking order
 * (frequency descending, then code-point ascending). [TdictPrefixIndex] is the implementation.
 */
fun interface TopFrequencySource {
    fun topFrequentWords(count: Int): List<String>
}

/**
 * TT-NEXTWORD-FILL (docs/TT-NEXTWORD-FILL.md): the global top-frequency words of the engine's
 * language that fill the strip cells a committed word's NEXT_WORD answer leaves empty — after the
 * bigram successors, the after-word forms and the emoji tail took theirs. The seam mirrors the P3
 * after-word forms: a per-engine [FallbackWords] built by a [FallbackWordsFactory] against that
 * engine's own dictionary (the index exists only inside engine startup), so the Tatar engine fills
 * with Tatar top words and the Russian one with Russian top words without a call-site language
 * check.
 *
 * Implementations must exclude the committed word itself and everything in [alreadyShown], return
 * at most [maxOut] words in their own (frequency-descending) order, and never displace anything.
 */
fun interface FallbackWords {
    fun fallbackWords(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String>
}

/**
 * Builds the [FallbackWords] of one engine against that engine's own dictionary. A factory because
 * the dictionary index exists only inside engine startup — the fallback rule itself is
 * language-level and stateless.
 */
fun interface FallbackWordsFactory {
    fun createFallbackWords(dictionary: TopFrequencySource): FallbackWords
}

/**
 * The production [FallbackWords]: a fixed pool of the dictionary's top-frequency words, computed
 * ONCE at engine start (off the per-keystroke path), serving every later NEXT_WORD answer by
 * exclusion and truncation only.
 */
internal class GlobalTopFrequencyFallback(
    private val topWords: List<String>,
) : FallbackWords {
    override fun fallbackWords(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String> {
        if (maxOut <= 0) return emptyList()
        val committed = contextWord.decodeUtf8()
        val out = ArrayList<String>(maxOut)
        for (word in topWords) {
            if (out.size >= maxOut) break
            // Never offer the committed word itself or a word already on the band.
            if (word == committed || alreadyShown.contains(word)) continue
            out.add(word)
        }
        return out
    }
}

/**
 * The production [FallbackWordsFactory], language-agnostic by construction: it builds the pool
 * from the engine's OWN dictionary, so wiring it for every shipped language keeps each engine's
 * fallback in its own language. The pool is 8: three cells to fill plus slack for the excluded
 * committed word and the already-shown candidates (at most two of those when a cell is free —
 * 8 − 3 ≥ 3 always).
 */
object GlobalTopFrequencyFallbackFactory : FallbackWordsFactory {
    private const val TOP_WORD_POOL = 8

    override fun createFallbackWords(dictionary: TopFrequencySource): FallbackWords =
        GlobalTopFrequencyFallback(dictionary.topFrequentWords(TOP_WORD_POOL))
}
