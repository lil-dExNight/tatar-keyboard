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

package rkr.simplekeyboard.inputmethod.latin.emoji

/**
 * Builds the snapshot the panel actually draws: the immutable asset snapshot, optionally with the
 * "recent" category prepended as category 0. It uses only [EmojiSetSnapshot]'s public read surface
 * and its module-internal constructor, so [EmojiSet] itself needs no recents awareness.
 *
 * The Recent tab appears first and only when the list is non-empty: [withRecents] returns the base
 * snapshot unchanged for an empty list, so "shown only when non-empty" is a consequence of the
 * empty list rather than a separate code branch. In direct boot the list is always empty (the path
 * is inaccessible before the device is unlocked), so the tab simply does not exist there.
 */
internal object EmojiDisplaySnapshots {

    /** Internal category name for the recents tab (its tab label is drawn from its first entry). */
    const val RECENT_CATEGORY_NAME = "recent"

    /** Every sequence the base snapshot can draw; used to drop recents absent from the set. */
    fun availableSequences(base: EmojiSetSnapshot): Set<String> {
        val set = HashSet<String>()
        for (category in 0 until base.categoryCount) {
            set.addAll(base.entriesOf(category))
        }
        return set
    }

    /**
     * Returns [base] unchanged when [recents] is empty, or a new snapshot with the recents as the
     * first category otherwise.
     */
    fun withRecents(base: EmojiSetSnapshot, recents: List<String>): EmojiSetSnapshot {
        if (recents.isEmpty()) return base
        val names = ArrayList<String>(base.categoryCount + 1)
        val entries = ArrayList<List<String>>(base.categoryCount + 1)
        names.add(RECENT_CATEGORY_NAME)
        entries.add(ArrayList(recents))
        for (category in 0 until base.categoryCount) {
            names.add(base.categoryName(category))
            entries.add(base.entriesOf(category))
        }
        return EmojiSetSnapshot(names, entries)
    }
}
