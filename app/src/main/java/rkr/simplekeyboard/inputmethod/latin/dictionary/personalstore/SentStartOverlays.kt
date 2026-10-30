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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.content.Context
import android.os.UserManager
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidSpaceProbe
import java.io.File
import java.io.FileOutputStream

/**
 * The process-wide owner of the sentence-initial overlay stores, one per subtype, on
 * [PersonalDictionaries.sharedStoreExecutor] so their temp files never race the other personal
 * stores' in the shared `personal/` directory. See [PersonalDictionaries] for the design.
 *
 * The read side ([topWordsFor]) is the published in-memory snapshot: a lookup never touches the
 * disk, and the store opens lazily on the worker. Reads are gated live on the personal-dictionary
 * setting, like the other personal sources: the switch hides every kind of learned data, this one
 * included. Pausing learning gates writes only, never reads.
 *
 * There is no erasure listener of its own: the settings screen's erase-all also clears the words
 * store, whose notification already makes the controller unbind and repaint the strip.
 */
object SentStartOverlays {

    private val lock = Any()
    private val stores = HashMap<String, SentStartOverlayStore>()

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): SentStartOverlayStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) { createStore(context, subtypeId) }
        }

    /**
     * The strip's read side for [subtypeId]: the saved sentence-initial words in usage order, or
     * an empty list while the personal-dictionary setting is off. Performs no I/O on the caller
     * (the store opens lazily on its worker), so it may be called at every sentence start.
     */
    @JvmStatic
    fun topWordsFor(context: Context, subtypeId: String, gate: PersonalDictionaryGate): List<String> {
        if (!gate.isOn()) return emptyList()
        val store = storeFor(context, subtypeId)
        store.prime()
        return store.snapshot.topWords()
    }

    /**
     * The factory wiring: what [AndroidPersonalDictionaryStorage.create] assembles for the words
     * store — the same credential-protected `noBackupFilesDir`, durable ops and unlock gate.
     */
    private fun createStore(context: Context, subtypeId: String): SentStartOverlayStore {
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        return SentStartOverlayStore(
            subtypeId = subtypeId,
            directoryProvider = {
                File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
            },
            fileOps = AndroidDurableFileOps,
            outputOpener = { temp -> FileOutputStream(temp) },
            spaceProbe = AndroidSpaceProbe,
            clock = { System.currentTimeMillis() },
            executor = PersonalDictionaries.sharedStoreExecutor(),
            unlockGate = { userManager?.isUserUnlocked ?: false },
        )
    }

    /** Test hook: drops every cached store so an isolated test starts from nothing. */
    internal fun resetForTest() {
        synchronized(lock) { stores.clear() }
    }
}
