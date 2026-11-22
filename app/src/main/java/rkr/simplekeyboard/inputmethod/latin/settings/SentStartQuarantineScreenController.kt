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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineReport
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.SentStartOverlays

/**
 * The screen side of a quarantined sentence-start overlay: count-only inspection, restore and
 * discard. File work goes through the store's process-wide owner on the shared personal-store
 * worker; every answer is marshalled onto the UI thread, like the other recovery controllers.
 */
internal class SentStartQuarantineScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    /**
     * Asks every language whether it has a quarantined sentence-start copy and answers once, on
     * the UI thread. A report carries counts and damage only, never a saved word.
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
            SentStartOverlays.storeFor(context, subtypeId).inspectQuarantine { report ->
                if (report != null) found[subtypeId] = report
                if (remaining.decrementAndGet() == 0) {
                    uiPoster { onReady(found.toMap()) }
                }
            }
        }
    }

    /**
     * Puts the readable sentence-start words back into the overlay. The quarantined copy stays
     * where it is until the user asks to delete it.
     */
    fun restoreQuarantine(subtypeId: String, onRestored: (Boolean) -> Unit) {
        SentStartOverlays.storeFor(context, subtypeId)
            .restoreQuarantine { restored -> uiPoster { onRestored(restored) } }
    }

    /** Removes one language's quarantined copy and nothing else. */
    fun discardQuarantine(subtypeId: String, onDiscarded: (Boolean) -> Unit) {
        SentStartOverlays.storeFor(context, subtypeId)
            .discardQuarantine { discarded -> uiPoster { onDiscarded(discarded) } }
    }
}
