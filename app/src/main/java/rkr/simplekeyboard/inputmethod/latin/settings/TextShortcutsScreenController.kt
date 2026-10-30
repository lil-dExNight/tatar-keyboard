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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcuts
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.TextShortcutFilter
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.TextShortcutStores

internal enum class TextShortcutSaveResult { INVALID, CONFIRM_REPLACEMENT, NO_CHANGE, WRITE_STARTED }

/** Decides whether a validated pair is new, unchanged, or replaces an existing expansion. */
internal fun textShortcutSaveDecision(
    pairs: TextShortcuts,
    shortcut: String,
    expansion: String,
    replaceExisting: Boolean,
): TextShortcutSaveResult {
    val existing = pairs.expansionFor(shortcut) ?: return TextShortcutSaveResult.WRITE_STARTED
    if (existing == expansion) return TextShortcutSaveResult.NO_CHANGE
    return if (replaceExisting) TextShortcutSaveResult.WRITE_STARTED
    else TextShortcutSaveResult.CONFIRM_REPLACEMENT
}

/**
 * Everything the "Text shortcuts" screen does to the saved pairs, away from the view code: read
 * the snapshot, save and remove pairs.
 *
 * Every mutation goes to the process-wide [TextShortcutStores] owner, which runs it on the shared
 * personal-store worker; the screen does no file I/O and holds no second writer. A removal also
 * notifies the IME, so a removed shortcut stops being tappable in the open suggestion strip. Every
 * mutation takes a completion callback delivered on the UI thread through [uiPoster], and the
 * screen repaints only then: right after queueing, the published snapshot is not updated yet.
 */
internal class TextShortcutsScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {

    /** The current pairs, ordered by shortcut. */
    fun pairs(): TextShortcuts = TextShortcutStores.snapshotFor(context)

    /**
     * Validates and saves one pair. A different existing expansion requires [replaceExisting];
     * an unchanged pair performs no write. Disk completion arrives through [onSaved] on the UI
     * thread only for [TextShortcutSaveResult.WRITE_STARTED].
     */
    fun addPair(
        shortcut: String,
        expansion: String,
        replaceExisting: Boolean = false,
        onSaved: (Boolean) -> Unit,
    ): TextShortcutSaveResult {
        val acceptedShortcut = TextShortcutFilter.acceptedShortcut(shortcut)
            ?: return TextShortcutSaveResult.INVALID
        val acceptedExpansion = TextShortcutFilter.acceptedExpansion(expansion)
            ?: return TextShortcutSaveResult.INVALID
        val decision = textShortcutSaveDecision(
            pairs(), acceptedShortcut, acceptedExpansion, replaceExisting)
        if (decision == TextShortcutSaveResult.WRITE_STARTED) {
            TextShortcutStores.storeFor(context)
                .put(acceptedShortcut, acceptedExpansion) { saved -> uiPoster { onSaved(saved) } }
        }
        return decision
    }

    /**
     * Removes one pair. The suggestion strip unbinds whatever it shows immediately, without waiting
     * for the disk. [onRemoved] arrives on the UI thread once the store knows whether the shortcut
     * is really gone.
     */
    fun removePair(shortcut: String, onRemoved: (Boolean) -> Unit) {
        TextShortcutStores.storeFor(context)
            .remove(shortcut) { removed -> uiPoster { onRemoved(removed) } }
        TextShortcutStores.notifyErased()
    }
}
