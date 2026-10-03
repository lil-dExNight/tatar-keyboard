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
import android.os.UserManager
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcutSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TextShortcuts
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-wide owner of the one text-shortcut store, serialized on the personal stores' shared
 * worker so its temp files never race theirs in the shared `personal/` directory.
 *
 * `SettingsHostActivity` runs in the IME process, so the settings screen edits pairs through the
 * same serialized owner the strip reads from, with no IPC and no second writer. The strip's read
 * side ([sourceFor]) is the published in-memory snapshot: lookups never touch the disk, and the
 * first read primes the store once (the file opens on the worker, off the UI thread).
 */
object TextShortcutStores {

    private val lock = Any()
    private var store: TextShortcutStore? = null
    private val primeRequested = AtomicBoolean(false)

    /**
     * Notified after a pair is removed on the settings screen, so the IME unbinds whatever the
     * strip still shows: a removed shortcut must stop being tappable in the open strip.
     */
    @Volatile
    private var erasureListener: Runnable? = null

    /** The store, created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context): TextShortcutStore =
        synchronized(lock) {
            store ?: run {
                val appContext = context.applicationContext
                val userManager = appContext.getSystemService(UserManager::class.java)
                TextShortcutStore(
                    directoryProvider = {
                        File(appContext.noBackupFilesDir,
                            AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
                    },
                    fileOps = AndroidDurableFileOps,
                    outputOpener = { temp -> FileOutputStream(temp) },
                    spaceProbe = { directory -> directory.usableSpace },
                    clock = { System.currentTimeMillis() },
                    executor = PersonalDictionaries.sharedStoreExecutor(),
                    unlockGate = { userManager?.isUserUnlocked ?: false },
                ).also { store = it }
            }
        }

    /**
     * The strip's read side: the live snapshot lookup. The first call primes the store once (an
     * enqueue, not a read), so the file opens on the worker and the very first keystrokes of a
     * process may see the empty snapshot — the offer appears as soon as the read lands.
     */
    @JvmStatic
    fun sourceFor(context: Context): TextShortcutSource {
        val store = storeFor(context)
        return TextShortcutSource { typedWord ->
            if (primeRequested.compareAndSet(false, true)) store.prime()
            store.snapshot.expansionFor(typedWord)
        }
    }

    /** The current snapshot, for the "Text shortcuts" screen. */
    internal fun snapshotFor(context: Context): TextShortcuts =
        storeFor(context).also { it.prime() }.snapshot

    @JvmStatic
    fun setErasureListener(listener: Runnable?) {
        erasureListener = listener
    }

    /** Called by the screen once a removal event has been queued on the store's worker. */
    internal fun notifyErased() {
        erasureListener?.run()
    }

    /** Test hook: drops the cached store so an isolated test starts from nothing. */
    internal fun resetForTest() {
        synchronized(lock) { store = null }
        primeRequested.set(false)
        erasureListener = null
    }
}
