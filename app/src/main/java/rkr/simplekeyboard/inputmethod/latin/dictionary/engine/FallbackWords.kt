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

import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/**
 * The N most frequent words of an engine's own dictionary, in ranking order (frequency
 * descending, then code point ascending). [TdictPrefixIndex] is the implementation.
 */
fun interface TopFrequencySource {
    fun topFrequentWords(count: Int): List<String>
}

/** A top-frequency source backed by the list precomputed with its dictionary. */
internal class PrecomputedTopFrequencySource(
    private val words: List<String>,
) : TopFrequencySource {
    override fun topFrequentWords(count: Int): List<String> {
        require(count >= 0)
        return if (count >= words.size) words else words.subList(0, count)
    }
}

/**
 * Top-frequency words of the engine's language that fill the strip cells a committed word's
 * NEXT_WORD answer leaves empty, after the bigram successors, learned pairs and the emoji.
 * Built per engine by a [FallbackWordsFactory] against that engine's dictionary, so each language
 * fills with its own words without a language check at the call site.
 *
 * Implementations must exclude the committed word and everything in [alreadyShown], return at
 * most [maxOut] words in frequency-descending order, and never displace anything.
 */
fun interface FallbackWords {
    fun fallbackWords(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String>
}

/**
 * Builds the [FallbackWords] of one engine from that engine's SHA-bound, precomputed dictionary
 * list. The fallback rule itself is language-level and stateless.
 */
fun interface FallbackWordsFactory {
    fun createFallbackWords(dictionary: TopFrequencySource): FallbackWords
}

/**
 * The production [FallbackWords]: a fixed pool of the dictionary's top-frequency words, computed
 * once at engine start, served by exclusion and truncation only. [harmonyTwins] names the
 * front/back word pairs of the language's own fallback pool; when the pool carries one, the
 * variant that does not match the committed word's vowel harmony is skipped and the next pool
 * word takes its slot. Pools without a pair behave exactly as before, so no language is
 * hardcoded in the engine.
 */
internal class GlobalTopFrequencyFallback(
    private val topWords: List<String>,
    private val harmonyTwins: List<Pair<String, String>> = emptyList(),
) : FallbackWords {
    override fun fallbackWords(
        contextWord: ImmutableUtf8Prefix,
        alreadyShown: List<String>,
        maxOut: Int,
    ): List<String> {
        if (maxOut <= 0) return emptyList()
        val committed = contextWord.decodeUtf8()
        val committedFront = if (harmonyTwins.isEmpty()) {
            null
        } else {
            TatarWordUtils.lastVowelIsFront(committed)
        }
        val out = ArrayList<String>(maxOut)
        for (word in topWords) {
            if (out.size >= maxOut) break
            // Never offer the committed word itself or a word already on the strip.
            if (word == committed || alreadyShown.contains(word)) continue
            // A harmony twin is only offered in the shape that matches the committed word's
            // vowel harmony; the other one drops and the pool's next word takes the freed
            // slot. A committed word with no deciding vowel keeps the pool order; the
            // committed twin itself is excluded by the equality check above.
            if (committedFront != null && isMisMatchingTwin(word, committedFront)) continue
            out.add(word)
        }
        return out
    }

    private fun isMisMatchingTwin(word: String, committedFront: Boolean): Boolean {
        for ((front, back) in harmonyTwins) {
            if (word == front && !committedFront) return true
            if (word == back && committedFront) return true
        }
        return false
    }
}

/**
 * The production [FallbackWordsFactory]. It builds the pool from the engine's own dictionary, so
 * each engine's fallback stays in its own language. The pool is 8: four cells to fill plus room
 * for the excluded committed word and the already-shown candidates. [harmonyTwins] carries the
 * language's front/back word pairs from its dictionary spec; [INSTANCE] builds the plain
 * no-pairs pool.
 */
class GlobalTopFrequencyFallbackFactory(
    private val harmonyTwins: List<Pair<String, String>> = emptyList(),
) : FallbackWordsFactory {
    override fun createFallbackWords(dictionary: TopFrequencySource): FallbackWords =
        GlobalTopFrequencyFallback(
            dictionary.topFrequentWords(TOP_WORD_POOL),
            harmonyTwins,
        )

    companion object {
        /** No pairs: the plain exclusion-and-truncation pool. */
        @JvmField
        val INSTANCE = GlobalTopFrequencyFallbackFactory()

        internal const val TOP_WORD_POOL = 8
    }
}
