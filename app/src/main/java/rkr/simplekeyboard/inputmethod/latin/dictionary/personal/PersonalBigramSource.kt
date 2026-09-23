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
 * The personal-bigram side of the NEXT_WORD merge (P1 of Phase 2, docs/ROADMAP-P2.md), as seen by
 * the engine. Kept as a seam so the composite computer is testable without a file, a snapshot or
 * Android — the exact shape [PersonalCandidateSource] has on the prefix path, for the other kind
 * of query.
 */
fun interface PersonalBigramSource {
    /**
     * The successors learned for [normalizedContextWord], in the pinned personal order (usage
     * descending, frequency descending, normalized ascending). [PersonalCandidate.rawForm] is what
     * the cell shows; [PersonalCandidate.normalizedForm] is what the duplicate rule against the
     * static successors compares by.
     */
    fun successorsFor(normalizedContextWord: String): List<PersonalCandidate>

    /**
     * True when this source cannot produce anything at all (feature off, locked device, empty
     * store). The merge checks it FIRST so a disabled personal-bigram store costs the prediction
     * path nothing — not even decoding the context bytes into a String.
     */
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

/**
 * The production source: reads whatever immutable snapshot [snapshot] currently returns. The
 * snapshot itself is published by the personal-bigram store on its own worker, so this only ever
 * reads a `@Volatile` reference — no I/O, no lock, no checksum on the engine thread.
 */
class SnapshotPersonalBigramSource(
    private val snapshot: () -> PersonalBigramDictionary,
) : PersonalBigramSource {
    override fun successorsFor(normalizedContextWord: String): List<PersonalCandidate> =
        snapshot().successorsFor(normalizedContextWord)

    override fun isEmpty(): Boolean = snapshot().isEmpty
}
