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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

/**
 * Where a CLEAN completion of a word is reported (E4c).
 *
 * "Clean" is defined by the producer, `SuggestionsController`: the word grew one character at a
 * time and ended by the trailing word becoming empty, with no backspace, no shortening, no selection
 * change, no cursor gesture, no field or subtype change and no accepted suggestion in between.
 *
 * The seam exists so the controller — the class that sees every keystroke — never references the
 * store package at all: it announces an event, and whether anything is written, and under which
 * gates, is decided on the other side. [NONE] is the default, and with it typing writes nothing.
 */
fun interface WordCompletionSink {
    fun onCleanCompletion(word: String)

    /**
     * The user accepted a suggestion cell showing [word] (2026-09-24 audit, finding 2). Whether
     * [word] is a saved personal word — and therefore whether any usage counter moves — is decided
     * on the other side, like everything else behind this seam. Default no-op, so a sink written
     * before the event existed keeps compiling and simply never counts acceptances.
     */
    fun onAcceptedSuggestion(word: String) {}

    /**
     * The editor session ended. This is the ONE boundary where the other side may put what it has
     * accumulated on disk — never per keystroke and never per completed word.
     */
    fun onInputFinished() {}

    companion object {
        @JvmField
        val NONE: WordCompletionSink = WordCompletionSink { }
    }
}
