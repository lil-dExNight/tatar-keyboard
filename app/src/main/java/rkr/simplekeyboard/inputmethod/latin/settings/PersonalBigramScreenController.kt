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

package rkr.simplekeyboard.inputmethod.latin.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramDictionaries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineReport

/**
 * The learned-pairs counterpart of [PersonalDictionaryScreenController]: read snapshots, remove one
 * pair, clear one language or all, and inspect/restore/discard the quarantined copy of an
 * unreadable pairs file. Mutations go to the process-wide [PersonalBigramDictionaries] owner on the
 * same personal-store worker as the word stores, so they never race a write of another store in
 * the shared directory. Callbacks arrive on the UI thread, as in the words controller.
 */
internal class PersonalBigramScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {

    /**
     * The languages shown, in the order given, each with its current pairs snapshot. Only subtypes
     * that can have a personal store at all are listed, with the same filter as for words.
     */
    fun sections(subtypeIds: List<String>): List<Pair<String, PersonalBigramDictionary>> =
        subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
            .map { it to PersonalBigramDictionaries.snapshotFor(context, it) }

    /**
     * Removes one pair. The suggestion strip unbinds whatever it shows immediately. [onRemoved]
     * arrives on the UI thread once the store knows whether the pair is really gone; the
     * quarantined copy is purged with it, so a later restore cannot bring the pair back.
     */
    fun removePair(
        subtypeId: String,
        contextForm: String,
        successorForm: String,
        onRemoved: (Boolean) -> Unit,
    ) {
        PersonalBigramDictionaries.storeFor(context, subtypeId)
            .forget(contextForm, successorForm) { removed -> uiPoster { onRemoved(removed) } }
        PersonalBigramDictionaries.notifyErased()
    }

    /**
     * Erases the learned pairs of one language (the section-level "Clear all word pairs").
     * [onCleared] arrives on the UI thread with whether the files are really gone.
     */
    fun clearPairs(subtypeId: String, onCleared: (Boolean) -> Unit) {
        PersonalBigramDictionaries.storeFor(context, subtypeId)
            .clearAll { cleared -> uiPoster { onCleared(cleared) } }
        PersonalBigramDictionaries.notifyErased()
    }

    /**
     * Erases the learned pairs of all languages, as part of the screen's global "Erase all".
     * [onErased] gets `true` only when every language's files went away, like the words erasure.
     */
    fun eraseAll(subtypeIds: List<String>, onErased: (Boolean) -> Unit) {
        val targets = subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
        if (targets.isEmpty()) {
            PersonalBigramDictionaries.notifyErased()
            uiPoster { onErased(true) }
            return
        }
        // Atomic counter, as in PersonalDictionaryScreenController.eraseAll.
        val remaining = AtomicInteger(targets.size)
        val everythingGone = AtomicBoolean(true)
        for (subtypeId in targets) {
            PersonalBigramDictionaries.storeFor(context, subtypeId).clearAll { erased ->
                if (!erased) everythingGone.set(false)
                if (remaining.decrementAndGet() == 0) {
                    uiPoster { onErased(everythingGone.get()) }
                }
            }
        }
        PersonalBigramDictionaries.notifyErased()
    }

    /**
     * Asks every language whether it has a quarantined pairs copy and answers once, on the UI
     * thread; see [PersonalDictionaryScreenController.quarantines].
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
            PersonalBigramDictionaries.storeFor(context, subtypeId).inspectQuarantine { report ->
                if (report != null) found[subtypeId] = report
                if (remaining.decrementAndGet() == 0) {
                    uiPoster { onReady(found.toMap()) }
                }
            }
        }
    }

    /**
     * Puts the readable pairs of one language's copy back into its store. The copy stays until the
     * user removes it ([discardQuarantine]).
     */
    fun restoreQuarantine(subtypeId: String, onRestored: (Boolean) -> Unit) {
        PersonalBigramDictionaries.storeFor(context, subtypeId)
            .restoreQuarantine { restored -> uiPoster { onRestored(restored) } }
    }

    /** Removes one language's copy and nothing else. */
    fun discardQuarantine(subtypeId: String, onDiscarded: (Boolean) -> Unit) {
        PersonalBigramDictionaries.storeFor(context, subtypeId)
            .discardQuarantine { discarded -> uiPoster { onDiscarded(discarded) } }
    }
}
