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
 * Where a clean completion of a word is reported.
 *
 * "Clean" is defined by the producer, `SuggestionsController`: the word grew one character at a
 * time and ended by the trailing word becoming empty, with no backspace, no shortening, no selection
 * change, no cursor gesture, no field or subtype change and no accepted suggestion in between.
 *
 * The seam keeps the controller, which sees every keystroke, from referencing the store package: it
 * announces an event, and the other side decides whether and under which gates anything is written.
 * [NONE] is the default, and with it typing writes nothing.
 */
fun interface WordCompletionSink {
    fun onCleanCompletion(word: String)

    /**
     * The user accepted a suggestion cell showing [word]. The other side decides whether [word] is a
     * saved personal word and a usage counter moves. Default no-op.
     */
    fun onAcceptedSuggestion(word: String) {}

    /**
     * The editor session ended. This is the only point where the other side may write what it has
     * accumulated to disk; never per keystroke or per completed word.
     */
    fun onInputFinished() {}

    companion object {
        @JvmField
        val NONE: WordCompletionSink = WordCompletionSink { }
    }
}
