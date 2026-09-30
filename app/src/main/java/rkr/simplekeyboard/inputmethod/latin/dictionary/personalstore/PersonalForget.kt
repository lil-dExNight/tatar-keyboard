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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.content.Context

/**
 * "Forget this word": whether the word shown in a strip cell is in the personal dictionary, and
 * removing it once the user confirms.
 *
 * The lookup uses the normalized form against the snapshot's normalized forms, never the displayed
 * string: the display has been through `applyCasing`, so a search by it would miss the saved
 * «Гүзәл» when the user typed in capitals.
 */
object PersonalForget {

    /**
     * The saved spelling of [shownWord] if it is a personal entry, or null for an ordinary
     * dictionary word, where a long press does nothing.
     */
    @JvmStatic
    fun savedFormOf(context: Context, subtypeId: String, shownWord: String): String? {
        val normalized = PersonalWordFilter.normalize(shownWord)
        val snapshot = PersonalDictionaries.snapshotFor(context, subtypeId)
        val index = snapshot.indexOfNormalized(normalized)
        return if (index >= 0) snapshot.rawFormAt(index) else null
    }

    /**
     * Removes the confirmed word and clears the strip at once, without waiting for the disk write.
     * The rewrite can fail; then [onFailed] runs on the store's worker thread, because the word is
     * still saved. It is a bare [Runnable]: no word, path or reason leaves this package.
     */
    @JvmStatic
    @JvmOverloads
    fun confirmForget(
        context: Context,
        subtypeId: String,
        shownWord: String,
        onFailed: Runnable? = null,
    ) {
        val normalized = PersonalWordFilter.normalize(shownWord)
        PersonalDictionaries.storeFor(context, subtypeId).forget(normalized) { removed ->
            if (!removed) onFailed?.run()
        }
        PersonalDictionaries.notifyErased()
    }
}
