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
    /** The single class #1 dictionary word [typedWord] may be replaced with (NFC lowercase). */
    val replacement: String,
    /** Frequency of [replacement] in the bundled dictionary, for the [AutocorrectPolicy] threshold. */
    val frequency: Long,
) {
    /** Deliberately says nothing: the user's word must never reach a log or exception message. */
    override fun toString(): String = "AutocorrectAdvice"
}

/**
 * The two autocorrect thresholds, in one place, read by both sides of the decision: the index
 * (which prunes work with them) and the controller (which re-checks them before it edits text).
 * They were fixed before any quality measurement and are not tuned to results.
 */
object AutocorrectPolicy {
    /**
     * Minimum length of the typed word, in code points of its normalized form. At three letters one
     * edit changes the word too often, and Tatar has many short words.
     */
    const val MIN_WORD_CODE_POINTS: Int = 4

    /**
     * Minimum frequency of the candidate: the frequency of the word at rank 10 000 in the bundled
     * Tatar dictionary, so autocorrect only offers words people actually write. Re-measure it the
     * same way whenever the dictionary is rebuilt.
     */
    const val MIN_CANDIDATE_FREQUENCY: Long = 411L
}
