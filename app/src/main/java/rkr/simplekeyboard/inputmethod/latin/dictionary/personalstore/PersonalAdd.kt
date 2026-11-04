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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes

/**
 * "Add «X» to the personal dictionary": the strip long-press offer for a word that is not saved
 * yet. The forget side of the same gesture is [PersonalForget].
 *
 * Eligibility is the manual-add path's own filter ([PersonalWordFilter]) against the active
 * language's alphabet, so a word accepted here round-trips through the store exactly like a word
 * typed on the settings screen.
 */
object PersonalAdd {

    /** What a long press on a strip word cell offers; LatinIME walks the gates around it. */
    enum class Action { FORGET, ADD, NOT_ADDABLE }

    /**
     * The routing decision of a strip long press: [savedForm] non-null means the word is already
     * saved (offer the forget), else [addableNormalizedForm] non-null means the manual-add filter
     * accepts it (offer the add), else the word cannot enter the dictionary (explain instead).
     */
    @JvmStatic
    fun actionFor(savedForm: String?, addableNormalizedForm: String?): Action = when {
        savedForm != null -> Action.FORGET
        addableNormalizedForm != null -> Action.ADD
        else -> Action.NOT_ADDABLE
    }

    /**
     * The normalized form [shownWord] would be saved under for [subtypeId], or null when the word
     * cannot enter that language's personal dictionary: the language declares no alphabet, or the
     * shown form fails the manual-add filter (casing, length, characters). Pure; runs on the UI
     * thread in the long-press timer body.
     */
    @JvmStatic
    fun addableNormalizedForm(subtypeId: String, shownWord: String): String? {
        val alphabet = PersonalSubtypes.alphabetFor(subtypeId) ?: return null
        return PersonalWordFilter.acceptedNormalizedForm(shownWord.trim(), alphabet)
    }

    /**
     * Writes the confirmed word through the store's manual-add path. The word is saved as shown
     * (trimmed): it entered the strip through the typed prefix's casing, and the dialog's title
     * showed exactly what is saved. [onFailed] runs on the store's worker thread when the word
     * did not reach the disk; it is a bare [Runnable]: no word, path or reason leaves this
     * package.
     */
    @JvmStatic
    @JvmOverloads
    fun confirmAdd(context: Context, subtypeId: String, shownWord: String, onFailed: Runnable? = null) {
        confirmAdd(PersonalDictionaries.storeFor(context, subtypeId), shownWord, onFailed)
    }

    /** The store half of [confirmAdd], split from the Context lookup so JVM tests drive it. */
    internal fun confirmAdd(store: PersonalDictionaryStore, shownWord: String, onFailed: Runnable?) {
        store.addManually(shownWord.trim()) { saved ->
            if (!saved) onFailed?.run()
        }
    }
}
