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
 * The system spell-checker entry point. Its verdicts are computed by [SpellCheckerEngine]; the
 * Russian and English subtypes exist for routing only and never decide the text's language.
 * Dictionary and personal data are read-only, no input is logged or stored, and no keyboard view
 * or learning machinery is started.
 */
class TatarSpellCheckerService : SpellCheckerService() {

    override fun onCreate() {
        super.onCreate()
        SharedSpellCheckerEngine.prepare(applicationContext)
    }

    override fun createSession(): Session = CheckingSession()

    /** One framework session; the process-wide engine is safe for concurrent binder calls. */
    private inner class CheckingSession : Session() {
        private val core = SpellCheckerSession(
            SharedSpellCheckerEngine.get(this@TatarSpellCheckerService),
        )

        override fun onCreate() {}

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo =
            core.suggestions(textInfo.request(), suggestionsLimit).platformInfo()

        /**
         * One answer per word with its own offset and length: a single answer spanning the whole
         * sentence is not rendered as underlines on newer releases.
         */
        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<TextInfo>,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo> = Array(textInfos.size) { index ->
            val response = core.sentence(textInfos[index].request(), suggestionsLimit)
            SentenceSuggestionsInfo(
                Array(response.words.size) { response.words[it].platformInfo() },
                IntArray(response.words.size) { response.words[it].span.start },
                IntArray(response.words.size) { response.words[it].span.length },
            )
        }

        private fun TextInfo.request(): SpellCheckerRequest =
            SpellCheckerRequest(text ?: "", cookie, sequence)

        private fun SpellCheckerWordResponse.platformInfo(): SuggestionsInfo =
            SuggestionsInfo(answer.attributes, answer.suggestions.toTypedArray()).also { info ->
                info.setCookieAndSequence(cookie, sequence)
            }
    }
}
