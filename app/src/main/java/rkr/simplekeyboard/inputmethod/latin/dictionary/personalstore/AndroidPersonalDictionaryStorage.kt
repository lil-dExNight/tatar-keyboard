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
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * Wires a [PersonalDictionaryStore] to the base (credential-protected) `noBackupFilesDir`.
 *
 * The personal dictionary holds what the user typed, so it lives in storage decrypted only after
 * the PIN or password, not in device-protected storage; this factory never opens a device-protected
 * context. The store is not used before the first unlock, so direct boot gains nothing from it.
 *
 * `personal/` is outside `files/`, so the backup whitelist excludes it. The unlock gate is
 * mandatory: before the first unlock the path does not exist, and the store treats that as empty.
 */
internal object AndroidPersonalDictionaryStorage {
    /** The single directory this seam owns, inside the base context's `noBackupFilesDir`. */
    const val PERSONAL_DIRECTORY_NAME = "personal"

    fun create(
        context: Context,
        subtypeId: String,
        executor: Executor,
        quarantineNotice: PersonalQuarantineNotice? = null,
    ): PersonalDictionaryStore {
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        return PersonalDictionaryStore(
            subtypeId = subtypeId,
            directoryProvider = { File(appContext.noBackupFilesDir, PERSONAL_DIRECTORY_NAME) },
            fileOps = AndroidDurableFileOps,
            outputOpener = { temp -> FileOutputStream(temp) },
            spaceProbe = { directory -> directory.usableSpace },
            clock = { System.currentTimeMillis() },
            executor = executor,
            unlockGate = { userManager?.isUserUnlocked ?: false },
            quarantineNotice = quarantineNotice,
        )
    }

    /**
     * The learned-pairs counterpart of [create]: the same directory, durable ops and unlock gate,
     * plus the [contextMembership] gate.
     */
    fun createBigrams(
        context: Context,
        subtypeId: String,
        executor: Executor,
        contextMembership: PersonalBigramContextMembership,
        quarantineNotice: PersonalQuarantineNotice? = null,
    ): PersonalBigramStore {
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        return PersonalBigramStore(
            subtypeId = subtypeId,
            directoryProvider = { File(appContext.noBackupFilesDir, PERSONAL_DIRECTORY_NAME) },
            fileOps = AndroidDurableFileOps,
            outputOpener = { temp -> FileOutputStream(temp) },
            spaceProbe = { directory -> directory.usableSpace },
            clock = { System.currentTimeMillis() },
            executor = executor,
            contextMembership = contextMembership,
            unlockGate = { userManager?.isUserUnlocked ?: false },
            quarantineNotice = quarantineNotice,
        )
    }
}
