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

import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * The system spell-checker entry point. Every word is reported as correct: the Tatar verdict and
 * its corrections arrive with the engine integration. The Russian and English subtypes exist for
 * routing only, so their sessions never evaluate a word.
 *
 * Stateless by design: it loads no dictionary, reads no personal data, writes and logs nothing
 * and starts none of the keyboard's machinery, so any binder thread may call it.
 */
class TatarSpellCheckerService : SpellCheckerService() {

    override fun createSession(): Session = AcceptingSession()

    /** One instance per framework session, created and called on binder threads. */
    private inner class AcceptingSession : Session() {

        override fun onCreate() {}

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo =
                accepted(textInfo.text ?: "", textInfo)

        /**
         * One answer per word with its own offset and length: a single answer spanning the whole
         * sentence is not rendered as underlines on newer releases.
         */
        override fun onGetSentenceSuggestionsMultiple(textInfos: Array<TextInfo>,
                suggestionsLimit: Int): Array<SentenceSuggestionsInfo> {
            return Array(textInfos.size) { index ->
                val textInfo = textInfos[index]
                val text = textInfo.text ?: ""
                val spans = SpellCheckerWords.wordSpans(text)
                SentenceSuggestionsInfo(
                        Array(spans.size) { spanIndex ->
                            val span = spans[spanIndex]
                            accepted(text.substring(span.start, span.start + span.length),
                                    textInfo)
                        },
                        IntArray(spans.size) { spans[it].start },
                        IntArray(spans.size) { spans[it].length })
            }
        }

        private fun accepted(word: String, textInfo: TextInfo): SuggestionsInfo {
            val info = SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                    NO_SUGGESTIONS)
            info.setCookieAndSequence(textInfo.cookie, textInfo.sequence)
            return info
        }
    }

    private companion object {
        /** Nothing is a typo yet, so no answer carries a replacement. */
        val NO_SUGGESTIONS = arrayOf<String>()
    }
}
