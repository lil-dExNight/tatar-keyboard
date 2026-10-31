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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import java.io.File

/**
 * Wires [PersonalEraseAll] to the process-wide stores: for every supported language the words,
 * word pairs, emoji, refused corrections and sentence starts, plus the one text-shortcut list, all
 * on [PersonalDictionaries.sharedStoreExecutor] over the credential-protected personal directory.
 */
internal object PersonalErasure {

    /**
     * Erases everything the stores hold for [subtypeIds] and sweeps the directory; [onFinished]
     * runs on the store worker. The open strip is told at once, so no erased entry stays tappable.
     */
    fun eraseEverything(context: Context, subtypeIds: List<String>, onFinished: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val stores = ArrayList<PersonalErasable>()
        for (subtypeId in subtypeIds.filter { PersonalSubtypes.isSupported(it) }.distinct()) {
            stores.add(PersonalErasable { PersonalDictionaries.storeFor(appContext, subtypeId).clearAll(it) })
            stores.add(PersonalErasable {
                PersonalBigramDictionaries.storeFor(appContext, subtypeId).clearAll(it)
            })
            stores.add(PersonalErasable {
                PersonalEmojiDictionaries.storeFor(appContext, subtypeId).clearAll(it)
            })
            stores.add(PersonalErasable {
                RefusedCorrectionStores.storeFor(appContext, subtypeId).clearAll(it)
            })
            stores.add(PersonalErasable { SentStartOverlays.storeFor(appContext, subtypeId).clearAll(it) })
        }
        stores.add(PersonalErasable { TextShortcutStores.storeFor(appContext).clearAll(it) })
        eraser(appContext, stores).run(onFinished)
        PersonalDictionaries.notifyErased()
        PersonalBigramDictionaries.notifyErased()
        PersonalEmojiDictionaries.notifyErased()
        TextShortcutStores.notifyErased()
    }

    /** Whether any file is in the personal directory; [onReady] runs on the store worker. */
    fun hasSavedFiles(context: Context, onReady: (Boolean) -> Unit) {
        eraser(context.applicationContext, emptyList()).hasSavedFiles(onReady)
    }

    private fun eraser(appContext: Context, stores: List<PersonalErasable>): PersonalEraseAll {
        val userManager = appContext.getSystemService(UserManager::class.java)
        return PersonalEraseAll(
            stores = stores,
            directoryProvider = {
                File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
            },
            fileOps = AndroidDurableFileOps,
            executor = PersonalDictionaries.sharedStoreExecutor(),
            unlockGate = { userManager?.isUserUnlocked ?: false },
        )
    }
}
