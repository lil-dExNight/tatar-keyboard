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

package rkr.simplekeyboard.inputmethod.latin.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineReport
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalWordFilter
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.SentStartOverlays

/**
 * Everything the "Personal dictionary" screen does to the saved words, away from the view code:
 * read snapshots, add, remove, clear and erase words, and inspect/restore/discard quarantined copies.
 *
 * Every mutation goes to the process-wide [PersonalDictionaries] owner, which runs it on the single
 * personal-store worker; the screen does no file I/O and holds no second writer. Erasure also
 * notifies the IME, so a removed word stops being tappable in the open suggestion strip. Every
 * mutation takes a completion callback delivered on the UI thread through [uiPoster], and the
 * screen repaints only then: right after queueing, the published snapshot is not updated yet.
 */
internal class PersonalDictionaryScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val learningPaused: () -> Boolean = {
        Settings.readIncognitoModeEnabled(
            PreferenceManagerCompat.getDeviceSharedPreferences(context))
    },
) {

    /**
     * The languages shown, in the order given, each with its current snapshot. Only subtypes that
     * can have a personal dictionary at all are listed: the feature is keyed by subtype, and a
     * language the dictionary does not support has nothing to show.
     */
    fun sections(subtypeIds: List<String>): List<Pair<String, PersonalDictionary>> =
        subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
            .map { it to PersonalDictionaries.snapshotFor(context, it) }

    /**
     * Adds one word typed on the screen. Returns false at once while learning is paused or when
     * the word is not eligible under the same content filter learning uses. `true` means "worth
     * saving", not "saved": whether the word reached the disk arrives later through [onSaved] on
     * the UI thread.
     */
    fun addWord(subtypeId: String, word: String, onSaved: (Boolean) -> Unit): Boolean {
        // The preference is read for every confirmation: the dialog may predate a pause change.
        if (learningPaused()) return false
        val alphabet = PersonalSubtypes.alphabetFor(subtypeId) ?: return false
        val trimmed = word.trim()
        if (PersonalWordFilter.acceptedNormalizedForm(trimmed, alphabet) == null) return false
        PersonalDictionaries.storeFor(context, subtypeId)
            .addManually(trimmed) { saved -> uiPoster { onSaved(saved) } }
        return true
    }

    /**
     * Removes one word. The suggestion strip unbinds whatever it shows immediately, without waiting
     * for the disk. [onRemoved] arrives on the UI thread once both the dictionary and the sentence
     * overlay (including its quarantined copy) know whether the word is really gone.
     */
    fun removeWord(subtypeId: String, word: String, onRemoved: (Boolean) -> Unit) {
        val removal = PersonalWordRemovalJoin { removed -> uiPoster { onRemoved(removed) } }
        PersonalDictionaries.storeFor(context, subtypeId)
            .forget(word) { removed -> removal.dictionaryFinished(removed) }
        // The sentence overlay owns its own file and quarantined copy. Purge both before answering
        // so the recovery card can never bring a deliberately forgotten word back.
        SentStartOverlays.storeFor(context, subtypeId)
            .forget(word) { removed -> removal.sentenceFinished(removed) }
        PersonalDictionaries.notifyErased()
    }

    /**
     * Asks every language whether it has a quarantined copy and answers once, on the UI thread, with
     * the languages that do. Reading a copy is file work on the store's worker, so the screen paints
     * without the card and repaints when the answer arrives. A language present with a count of zero
     * has a copy that yielded nothing; it still gets a card so the user can remove it.
     */
    fun quarantines(
        subtypeIds: List<String>,
        onReady: (Map<String, PersonalQuarantineReport>) -> Unit,
    ) {
        val targets = subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
        if (targets.isEmpty()) {
            uiPoster { onReady(emptyMap()) }
            return
        }
        val found = ConcurrentHashMap<String, PersonalQuarantineReport>()
        val remaining = AtomicInteger(targets.size)
        for (subtypeId in targets) {
            PersonalDictionaries.storeFor(context, subtypeId).inspectQuarantine { report ->
                if (report != null) found[subtypeId] = report
                if (remaining.decrementAndGet() == 0) {
                    uiPoster { onReady(found.toMap()) }
                }
            }
        }
    }

    /**
     * Puts the readable words of one language's copy back into its dictionary, at the user's
     * request. The copy is left where it is: what could not be read this time is still there for a
     * later reader, and removing it is the user's own separate decision ([discardQuarantine]).
     */
    fun restoreQuarantine(subtypeId: String, onRestored: (Boolean) -> Unit) {
        PersonalDictionaries.storeFor(context, subtypeId)
            .restoreQuarantine { restored -> uiPoster { onRestored(restored) } }
    }

    /** Removes one language's copy and nothing else. */
    fun discardQuarantine(subtypeId: String, onDiscarded: (Boolean) -> Unit) {
        PersonalDictionaries.storeFor(context, subtypeId)
            .discardQuarantine { discarded -> uiPoster { onDiscarded(discarded) } }
    }

    /**
     * Erases the personal dictionary of one language (the section-level "Clear all words").
     * [onCleared] arrives on the UI thread with whether the files are really gone; the suggestion
     * strip is unbound immediately, like after a single removal.
     */
    fun clearWords(subtypeId: String, onCleared: (Boolean) -> Unit) {
        PersonalDictionaries.storeFor(context, subtypeId)
            .clearAll { cleared -> uiPoster { onCleared(cleared) } }
        PersonalDictionaries.notifyErased()
    }
}

/**
 * Joins the dictionary and sentence-overlay answers without assuming worker order. A missing answer
 * cannot turn into success; a duplicate answer fails the operation and is reported only once.
 */
internal class PersonalWordRemovalJoin(private val onRemoved: (Boolean) -> Unit) {
    private var completedMask = 0
    private var removedMask = 0
    private var reported = false

    fun dictionaryFinished(removed: Boolean) = finished(DICTIONARY, removed)

    fun sentenceFinished(removed: Boolean) = finished(SENTENCE_OVERLAY, removed)

    private fun finished(store: Int, removed: Boolean) {
        val result = synchronized(this) {
            if (reported) return
            if (completedMask and store != 0) {
                reported = true
                false
            } else {
                completedMask = completedMask or store
                if (removed) removedMask = removedMask or store
                if (completedMask == BOTH_STORES) {
                    reported = true
                    removedMask == BOTH_STORES
                } else {
                    null
                }
            }
        }
        result?.let(onRemoved)
    }

    private companion object {
        const val DICTIONARY = 1
        const val SENTENCE_OVERLAY = 1 shl 1
        const val BOTH_STORES = DICTIONARY or SENTENCE_OVERLAY
    }
}
