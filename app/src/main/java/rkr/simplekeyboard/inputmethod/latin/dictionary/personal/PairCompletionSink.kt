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
 * Where a CLEAN completion of a word PAIR is reported (P1 of Phase 2, docs/ROADMAP-P2.md) — the
 * pair analogue of [WordCompletionSink].
 *
 * "Clean" is defined by the producer, `SuggestionsController`, with exactly the clean-run rules of
 * E4c: the completed word grew one character at a time and ended by the trailing word becoming
 * empty, with no backspace, no shortening, no selection change, no cursor gesture, no field or
 * subtype change and no accepted suggestion in between. Unlike the words path there is NO
 * "the dictionary does not know this word" filter: a bigram whose second half is an ordinary
 * dictionary word is the common case, not a reason to skip it.
 *
 * [contextWord] is the committed word immediately before the completed one, as read from the live
 * editor cache at the completion moment — typed or tapped, the producer does not care; whether it
 * is a word worth learning against is decided on the other side, inside the package that owns the
 * file. [completedWord] is the word whose run just ended. Both travel RAW (as they stand in the
 * editor); normalization is the store's business.
 *
 * The seam exists so the controller — the class that sees every keystroke — never references the
 * store package at all. [NONE] is the default, and with it typing writes nothing.
 */
fun interface PairCompletionSink {
    fun onCleanPairCompletion(contextWord: String, completedWord: String)

    /**
     * A NEXT_WORD band cell was committed by tap. The other side bumps the pair's usage counter
     * when (and only when) the cell is backed by a learned pair — a static successor, a word form
     * or a fallback word changes nothing. Never rewrites the file by itself.
     */
    fun onAcceptedPrediction(contextWord: String, word: String) {}

    /**
     * The editor session ended. This is the ONE boundary where the other side may put what it has
     * accumulated on disk — never per keystroke and never per completed pair.
     */
    fun onInputFinished() {}

    companion object {
        @JvmField
        val NONE: PairCompletionSink = PairCompletionSink { _, _ -> }
    }
}
