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

/**
 * Everything the "Text shortcuts" screen does to the saved pairs, away from the view code: read
 * the snapshot, add and remove pairs.
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
     * Saves one pair typed on the screen (an existing shortcut's expansion is replaced). Returns
     * false at once when either side is not eligible under the same content filter the store
     * applies. `true` means "worth saving", not "saved": whether the pair reached the disk arrives
     * later through [onSaved] on the UI thread.
     */
    fun addPair(shortcut: String, expansion: String, onSaved: (Boolean) -> Unit): Boolean {
        val acceptedShortcut = TextShortcutFilter.acceptedShortcut(shortcut) ?: return false
        val acceptedExpansion = TextShortcutFilter.acceptedExpansion(expansion) ?: return false
        TextShortcutStores.storeFor(context)
            .put(acceptedShortcut, acceptedExpansion) { saved -> uiPoster { onSaved(saved) } }
        return true
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
