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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineReport
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalWordFilter

/**
 * Everything the "Personal dictionary" screen does to the data, in one place away from the view
 * code: read the snapshots, add a word, remove a word, erase everything.
 *
 * Every mutation goes to the process-wide [PersonalDictionaries] owner, which turns it into an event
 * on the single personal-store worker — the settings screen performs no file I/O itself and holds no
 * second writer. Erasure additionally notifies the IME so a word that is gone stops being tappable
 * in the band that is open right now.
 *
 * Every mutation here takes a completion callback and delivers it on the UI thread through
 * [uiPoster]. That is not a convenience: queueing an event and repainting the screen in the next
 * statement made the screen report an outcome it could not know yet. It read the published snapshot
 * before the worker had finished two fsyncs, so a word the user had just added was routinely missing
 * from the list, and a write that genuinely failed said nothing at all. The screen now repaints when
 * the mutation is over, and says so when it did not happen.
 */
internal class PersonalDictionaryScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
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
     * Adds one word typed by the user on the screen. Returns false when the word is not eligible —
     * the SAME content filter learning will use, so what the screen accepts and what typing would
     * save can never drift apart. That answer is immediate and is about the word itself.
     *
     * Whether the accepted word actually reached the disk is a different and later answer, and it
     * arrives through [onSaved] on the UI thread. `true` from this method therefore means "worth
     * saving", never "saved".
     */
    fun addWord(subtypeId: String, word: String, onSaved: (Boolean) -> Unit): Boolean {
        val alphabet = PersonalSubtypes.alphabetFor(subtypeId) ?: return false
        val trimmed = word.trim()
        if (PersonalWordFilter.acceptedNormalizedForm(trimmed, alphabet) == null) return false
        PersonalDictionaries.storeFor(context, subtypeId)
            .addManually(trimmed) { saved -> uiPoster { onSaved(saved) } }
        return true
    }

    /**
     * Removes one word. Erasure semantics: the band unbinds whatever it is showing, immediately —
     * that part cannot wait for the disk. [onRemoved] arrives on the UI thread once the store knows
     * whether the word is really gone.
     */
    fun removeWord(subtypeId: String, word: String, onRemoved: (Boolean) -> Unit) {
        PersonalDictionaries.storeFor(context, subtypeId)
            .forget(word) { removed -> uiPoster { onRemoved(removed) } }
        PersonalDictionaries.notifyErased()
    }

    /**
     * Asks every language whether it has a quarantine copy, and answers ONCE, on the UI thread, with
     * the languages that do.
     *
     * The screen cannot ask this synchronously: reading the copy is file work and belongs on the
     * store's worker, like every other read in this subsystem. So the screen paints without the card
     * and repaints when the answers arrive — the same shape the mutations already use, for the same
     * reason.
     *
     * A language absent from the map has no copy. A language present with a count of zero HAS one
     * that yielded nothing, and still deserves its card: those bytes are on the device and the user
     * is the only one who can decide to remove them.
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
     * Erases the personal dictionary of ONE language — the section-level "Clear all words" (U7 of
     * Phase 2, docs/ROADMAP-P2.md). [onCleared] arrives on the UI thread with whether the files
     * are really gone; the band is unbound immediately, exactly like a single removal.
     */
    fun clearWords(subtypeId: String, onCleared: (Boolean) -> Unit) {
        PersonalDictionaries.storeFor(context, subtypeId)
            .clearAll { cleared -> uiPoster { onCleared(cleared) } }
        PersonalDictionaries.notifyErased()
    }

    /**
     * Erases the personal dictionaries of ALL languages, not only the one in view. [onErased] gets
     * `true` only when EVERY language's files went away: a partial erasure that reported success
     * would be the worst of the three, because the screen shows an empty list while the words come
     * back at the next process start.
     */
    fun eraseAll(subtypeIds: List<String>, onErased: (Boolean) -> Unit) {
        val targets = subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
        if (targets.isEmpty()) {
            PersonalDictionaries.notifyErased()
            uiPoster { onErased(true) }
            return
        }
        // All outcomes arrive on the one store worker, in sequence; the counter is atomic anyway so
        // the invariant does not depend on that staying true.
        val remaining = AtomicInteger(targets.size)
        val everythingGone = AtomicBoolean(true)
        for (subtypeId in targets) {
            PersonalDictionaries.storeFor(context, subtypeId).clearAll { erased ->
                if (!erased) everythingGone.set(false)
                if (remaining.decrementAndGet() == 0) {
                    uiPoster { onErased(everythingGone.get()) }
                }
            }
        }
        PersonalDictionaries.notifyErased()
    }
}
