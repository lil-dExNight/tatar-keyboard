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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.RefusedCorrectionSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.RefusedCorrections
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import java.io.File
import java.io.FileOutputStream

/**
 * The process-wide owner of the refused-corrections stores, one per subtype, on
 * [PersonalDictionaries.sharedStoreExecutor] so their temp files never race the other personal
 * stores' in the shared `personal/` directory. See [PersonalDictionaries] for the design.
 *
 * The read side ([sourceFor]) is the published in-memory snapshot: lookups never touch the disk,
 * and the store opens lazily on the worker. There is no erasure listener: a refused pair is never
 * shown in a strip cell, so an erasure has nothing displayed to unbind — it only lets a suppressed
 * correction fire again, which the very next keystroke picks up from the empty snapshot.
 */
object RefusedCorrectionStores {

    private val lock = Any()
    private val stores = HashMap<String, RefusedCorrectionStore>()

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): RefusedCorrectionStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) { createStore(context, subtypeId) }
        }

    /**
     * The autocorrect paths' read side for [subtypeId]. Behaves like an empty list while the
     * personal-dictionary setting is off, like the other personal sources: the switch hides every
     * kind of learned data, this one included. Performs no I/O (the store opens lazily on its
     * worker), so it may be called for every separator and every strip derivation.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): RefusedCorrectionSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        return RefusedCorrectionSource { typedWord, replacement ->
            gate.isOn() && store.snapshot.isRefused(typedWord, replacement)
        }
    }

    /** The backup restore for one language. See [PersonalDictionaries.replaceAll]. */
    internal fun replaceAll(
        context: Context,
        subtypeId: String,
        bytes: ByteArray?,
        outcome: PersonalMutationOutcome,
    ) = storeFor(context, subtypeId).replaceAll(bytes, outcome)

    /**
     * The factory wiring: what [AndroidPersonalDictionaryStorage.create] assembles for the words
     * store — the same credential-protected `noBackupFilesDir`, durable ops and unlock gate.
     */
    private fun createStore(context: Context, subtypeId: String): RefusedCorrectionStore {
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        return RefusedCorrectionStore(
            subtypeId = subtypeId,
            directoryProvider = {
                File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
            },
            fileOps = AndroidDurableFileOps,
            outputOpener = { temp -> FileOutputStream(temp) },
            spaceProbe = { directory -> directory.usableSpace },
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
