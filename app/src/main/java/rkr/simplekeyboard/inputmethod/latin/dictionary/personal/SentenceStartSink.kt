/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
 * Where a sentence-initial word commit is reported: a word the user typed out and finished with a
 * separator while its start position was a sentence start, or a cell committed from the
 * sentence-start strip or from a prefix strip whose word began at a sentence start.
 *
 * The seam keeps the controller, which sees every keystroke, from referencing the store package:
 * it announces an event, and the other side decides whether and under which gates anything is
 * written (the shared learning predicate first, then the store's own content filters and its
 * pending-counter threshold). [NONE] is the default, and with it typing writes nothing.
 */
fun interface SentenceStartSink {
    /** A word was committed where a sentence begins. Raw form, as committed. */
    fun onSentenceStartWord(word: String)

    /**
     * The editor session ended: the only point where the other side may write what it has
     * accumulated to disk; never per commit.
     */
    fun onInputFinished() {}

    companion object {
        @JvmField
        val NONE: SentenceStartSink = SentenceStartSink { }
    }
}
