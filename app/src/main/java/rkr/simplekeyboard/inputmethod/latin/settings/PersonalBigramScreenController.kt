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
 * The pair half of what the "Personal dictionary" screen does to the data (U7 of Phase 2,
 * docs/ROADMAP-P2.md) — the deliberate mirror of [PersonalDictionaryScreenController] for the
 * learned word pairs the P1 store owns: read the snapshots, remove one pair, erase one language
 * or all of them, and inspect/restore/discard the quarantine copy of an unreadable pairs file.
 *
 * Every mutation goes to the process-wide [PersonalBigramDictionaries] owner, which turns it into
 * an event on the single personal-store worker — the same worker the words stores are serialized
 * on, so a screen mutation can never race an in-flight write of the other feature in the shared
 * directory. The settings screen performs no file I/O itself and holds no second writer.
 *
 * Every mutation here takes a completion callback and delivers it on the UI thread through
 * [uiPoster], for the reason the words controller's class doc writes down: queueing an event and
 * repainting in the next statement made the screen report an outcome it could not know yet.
 */
internal class PersonalBigramScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {

    /**
     * The languages shown, in the order given, each with its current pairs snapshot. Only subtypes
     * that can have a personal store at all are listed — the same filter the words half applies.
     */
    fun sections(subtypeIds: List<String>): List<Pair<String, PersonalBigramDictionary>> =
        subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
            .map { it to PersonalBigramDictionaries.snapshotFor(context, it) }

    /**
     * Removes one pair. Erasure semantics: the band unbinds whatever it is showing, immediately —
     * that part cannot wait for the disk. [onRemoved] arrives on the UI thread once the store knows
     * whether the pair is really gone; the quarantine copy is purged with it, so a later restore
     * cannot resurrect what was forgotten (the P1 no-resurrection rule).
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
     * Erases the learned pairs of ONE language — the section-level "Clear all word pairs".
     * [onCleared] arrives on the UI thread with whether the files are really gone.
     */
    fun clearPairs(subtypeId: String, onCleared: (Boolean) -> Unit) {
        PersonalBigramDictionaries.storeFor(context, subtypeId)
            .clearAll { cleared -> uiPoster { onCleared(cleared) } }
        PersonalBigramDictionaries.notifyErased()
    }

    /**
     * Erases the learned pairs of ALL languages — the pairs half of the screen's global "erase
     * all", which the Activity composes with the words half. [onErased] gets `true` only when
     * EVERY language's files went away, exactly like the words erasure.
     */
    fun eraseAll(subtypeIds: List<String>, onErased: (Boolean) -> Unit) {
        val targets = subtypeIds.filter { PersonalSubtypes.alphabetFor(it) != null }
        if (targets.isEmpty()) {
            PersonalBigramDictionaries.notifyErased()
            uiPoster { onErased(true) }
            return
        }
        // All outcomes arrive on the one store worker, in sequence; the counter is atomic anyway so
        // the invariant does not depend on that staying true.
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
     * Asks every language whether it has a pairs quarantine copy, and answers ONCE, on the UI
     * thread, with the languages that do. The read is file work and belongs on the store's worker,
     * like every other read in this subsystem — the screen paints without the card and repaints
     * when the answers arrive, the same shape the words half uses, for the same reason.
     *
     * A language present with a count of zero HAS a copy that yielded nothing, and still deserves
     * its card: those bytes are on the device and the user is the only one who can decide to
     * remove them.
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
     * Puts the readable pairs of one language's copy back into its store, at the user's request.
     * The copy is left where it is: what could not be read this time is still there for a later
     * reader, and removing it is the user's own separate decision ([discardQuarantine]).
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
