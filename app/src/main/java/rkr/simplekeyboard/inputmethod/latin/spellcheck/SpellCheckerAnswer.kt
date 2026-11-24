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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import android.view.textservice.SuggestionsInfo

/**
 * One word's platform-independent verdict: the flags a [SuggestionsInfo] carries and the words a
 * suggestion menu offers. The text the verdict was computed from is not retained.
 */
internal class SpellCheckerAnswer(
    val attributes: Int,
    val suggestions: List<String>,
) {
    val hasRecommendedSuggestions: Boolean
        get() = attributes and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0

    companion object {
        val ACCEPTED = SpellCheckerAnswer(
            SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
            emptyList(),
        )

        fun typo(suggestions: List<String>): SpellCheckerAnswer =
            SpellCheckerAnswer(
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                    (if (suggestions.isEmpty()) 0
                    else SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS),
                suggestions,
            )
    }
}

/** The answers of one submitted sentence, in the same order as [spans]. */
internal class SpellCheckerSentence(
    val spans: List<WordSpan>,
    val answers: List<SpellCheckerAnswer>,
)

/** One platform text submitted for checking, with the cookie and sequence to echo back. */
internal class SpellCheckerRequest(
    val text: String,
    val cookie: Int,
    val sequence: Int,
)

/** One word answer with the cookie and sequence of the [SpellCheckerRequest] it belongs to. */
internal class SpellCheckerWordResponse(
    val span: WordSpan,
    val answer: SpellCheckerAnswer,
    val cookie: Int,
    val sequence: Int,
)

/** All word answers of one submitted text. */
internal class SpellCheckerSentenceResponse(
    val words: List<SpellCheckerWordResponse>,
)
