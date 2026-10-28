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
 * The immutable read model of one language's sentence-initial overlay: the few words the user
 * actually starts sentences with, in strip order (usage descending, then last-use serial
 * descending). Published whole by the store and safe to read on the UI thread; a plain class
 * without a generated `toString`, because it carries the user's words.
 */
class SentStartOverlay private constructor(
    private val wordsByUsage: List<String>,
) {
    val isEmpty: Boolean
        get() = wordsByUsage.isEmpty()

    /**
     * The overlay's words in strip order, already deduplicated by normalized form. The returned
     * list is the immutable snapshot itself: callers must not mutate it and never receive a
     * mutable view. Called at a sentence start, never in a lookup loop.
     */
    fun topWords(): List<String> = wordsByUsage

    companion object {
        @JvmField
        val EMPTY: SentStartOverlay = SentStartOverlay(emptyList())

        internal fun of(wordsByUsage: List<String>): SentStartOverlay =
            if (wordsByUsage.isEmpty()) EMPTY else SentStartOverlay(wordsByUsage)
    }
}
