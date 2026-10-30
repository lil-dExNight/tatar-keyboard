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
 * The autocorrect verdict for one typed word, produced by the same lookup that fed the suggestion
 * strip. The only source of replacements is the typo-recovery variant generator (edit class #1 by
 * default, see [FuzzyEditPolicy.autocorrectClasses]); there is no second request.
 *
 * Immutable and published through a `@Volatile` reference: computed on the engine worker, read on
 * the UI thread when a word separator is pressed. It carries the word it was computed for; the
 * reader compares [typedWord] with the live normalized trailing word and refuses on any mismatch.
 *
 * Not a Kotlin data class, and [toString] prints no user text.
 */
class AutocorrectAdvice(
    /** The typed word, in the NFC lowercase form the lookup was made with. */
    val typedWord: String,
    /** The single dictionary word allowed by the enabled edit classes (NFC lowercase). */
    val replacement: String,
    /** Frequency of [replacement] in the bundled dictionary, for the [minCandidateFrequency] check. */
    val frequency: Long,
    /** The floor [frequency] was checked against: the per-language value of the engine that produced this advice. */
    val minCandidateFrequency: Long,
) {
    /** Deliberately says nothing: the user's word must never reach a log or exception message. */
    override fun toString(): String = "AutocorrectAdvice"
}

/**
 * The autocorrect thresholds, in one place, read by both sides of the decision: the index
 * (which prunes work with them) and the controller (which re-checks them before it edits text).
 * The word length is shared; the candidate frequency floor is per language, derived from each
 * shipped dictionary and pinned by `AutocorrectFloorPinTest`.
 */
object AutocorrectPolicy {
    /**
     * Minimum length of the typed word, in code points of its normalized form. At three letters one
     * edit changes the word too often, and Tatar has many short words.
     */
    const val MIN_WORD_CODE_POINTS: Int = 4

    /**
     * Minimum candidate frequency of the Tatar engine: the frequency of the rank-10 000 word of
     * the shipped Tatar dictionary, so autocorrect only offers words people actually write.
     * `AutocorrectFloorPinTest` pins it against the shipped asset; a rebuilt dictionary that moves
     * the rank-10 000 frequency fails that test until this constant is re-derived the same way.
     */
    const val MIN_CANDIDATE_FREQUENCY_TT: Long = 3_283L

    /**
     * Minimum candidate frequency of the Russian engine: the frequency of the rank-10 000 word of
     * the shipped Russian dictionary. See [MIN_CANDIDATE_FREQUENCY_TT] for the rule.
     */
    const val MIN_CANDIDATE_FREQUENCY_RU: Long = 2_734L
}
