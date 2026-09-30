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
 * The learned-pairs side of the NEXT_WORD merge, as seen by the engine. A seam so the composite
 * computer is testable without a file, a snapshot or Android; see [PersonalCandidateSource].
 */
fun interface PersonalBigramSource {
    /**
     * The successors learned for [normalizedContextWord], in the pinned personal order (usage
     * descending, frequency descending, normalized ascending). [PersonalCandidate.rawForm] is what
     * the cell shows; [PersonalCandidate.normalizedForm] is what the duplicate rule against the
     * static successors compares by.
     */
    fun successorsFor(normalizedContextWord: String): List<PersonalCandidate>

    /** True when this source cannot produce anything. See [PersonalCandidateSource.isEmpty]. */
    fun isEmpty(): Boolean = false

    companion object {
        /** The source used whenever personal bigrams are off or unavailable. */
        @JvmField
        val EMPTY: PersonalBigramSource = object : PersonalBigramSource {
            override fun successorsFor(normalizedContextWord: String): List<PersonalCandidate> =
                emptyList()

            override fun isEmpty(): Boolean = true
        }
    }
}

/** The production source. See [SnapshotPersonalCandidateSource]. */
class SnapshotPersonalBigramSource(
    private val snapshot: () -> PersonalBigramDictionary,
) : PersonalBigramSource {
    override fun successorsFor(normalizedContextWord: String): List<PersonalCandidate> =
        snapshot().successorsFor(normalizedContextWord)

    override fun isEmpty(): Boolean = snapshot().isEmpty
}
