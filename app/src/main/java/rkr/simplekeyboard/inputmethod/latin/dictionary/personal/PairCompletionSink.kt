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
 * Where a clean completion of a word pair is reported. See [WordCompletionSink] for the clean-run
 * rules and the reason for the seam. Unlike the word path there is no "unknown to the dictionary"
 * filter: a pair of ordinary dictionary words is the common case.
 *
 * [contextWord] is the committed word before the completed one, read from the editor cache;
 * [completedWord] is the word whose run just ended. Both are raw; the store normalizes them.
 * [NONE] is the default, and with it typing writes nothing.
 */
fun interface PairCompletionSink {
    fun onCleanPairCompletion(contextWord: String, completedWord: String)

    /**
     * A NEXT_WORD cell was committed by tap. The other side bumps the pair's usage counter only when
     * the cell is backed by a learned pair. Never rewrites the file by itself.
     */
    fun onAcceptedPrediction(contextWord: String, word: String) {}

    /** The editor session ended. See [WordCompletionSink.onInputFinished]. */
    fun onInputFinished() {}

    companion object {
        @JvmField
        val NONE: PairCompletionSink = PairCompletionSink { _, _ -> }
    }
}
