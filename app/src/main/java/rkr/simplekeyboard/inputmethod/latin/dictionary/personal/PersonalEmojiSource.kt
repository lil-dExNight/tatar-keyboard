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
 * The learned-emoji side of the strip's word lookup, as seen by the engine. A seam so the strip
 * logic is testable without a file, a snapshot or Android; see [PersonalCandidateSource].
 */
fun interface PersonalEmojiSource {
    /**
     * The top emoji learned for [normalizedWord], in the pinned personal order (usage descending,
     * frequency descending, key ascending), or null when the word has none. The caller normalizes;
     * a miss is silent by design.
     */
    fun emojiFor(normalizedWord: String): String?

    /** True when this source cannot produce anything. See [PersonalCandidateSource.isEmpty]. */
    fun isEmpty(): Boolean = false

    companion object {
        /** The source used whenever personal emoji are off or unavailable. */
        @JvmField
        val EMPTY: PersonalEmojiSource = object : PersonalEmojiSource {
            override fun emojiFor(normalizedWord: String): String? = null

            override fun isEmpty(): Boolean = true
        }
    }
}

/** The production source. See [SnapshotPersonalCandidateSource]. */
class SnapshotPersonalEmojiSource(
    private val snapshot: () -> PersonalEmojiDictionary,
) : PersonalEmojiSource {
    override fun emojiFor(normalizedWord: String): String? = snapshot().emojiFor(normalizedWord)

    override fun isEmpty(): Boolean = snapshot().isEmpty
}
