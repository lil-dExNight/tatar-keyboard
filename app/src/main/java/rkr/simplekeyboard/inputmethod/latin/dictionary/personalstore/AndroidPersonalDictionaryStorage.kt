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
 * The personal dictionary is the list of what the user typed, so it lives in the storage that is
 * decrypted only after the PIN/password — never in the device-protected storage the OS opens at
 * boot. This factory therefore never opens a device-protected storage context: the base
 * application context is already credential-protected. The claim "a device-protected context is
 * created in exactly two seams (AndroidDictionaryStorageFactory and PreferenceManagerCompat)" stays
 * true. `directBootAware` startup is the IME's own concern, served by the packaged asset and
 * device-protected settings; the personal dictionary by its own gate is neither read nor written
 * before the first unlock, so device-protected placement would only lose protection on a lost
 * locked device while gaining nothing.
 *
 * The directory `personal/` is not a subdirectory of `files/`, so it is excluded from every backup
 * domain by construction — no rule enumerates it (the whitelist from E2b-3 already closes backup by
 * default). The `UserManager.isUserUnlocked()` gate is mandatory: before the first unlock the path
 * does not exist, and the store survives that as "empty", never as an exception.
 *
 * Dormant in E4a-2: nothing in the live IME constructs this. Learning, the merge and the settings
 * toggle that will call it are E4b/E4c.
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
     * The personal-bigram sibling of [create] (P1 of Phase 2, docs/ROADMAP-P2.md): the SAME
     * directory, the same durable ops, the same unlock gate — only the store, its format and its
     * files differ, plus the [contextMembership] gate the words store does not have.
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
