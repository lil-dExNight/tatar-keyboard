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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import android.content.Context
import android.content.res.AssetManager
import android.os.UserManager
import java.io.File
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.AndroidPersonalDictionaryStorage
import rkr.simplekeyboard.inputmethod.latin.morphology.SharedTatarMorphology
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.suggestions.KeyNeighborTableBuilder

/** Adapts the credential-protected personal files to a read-only snapshot source. */
internal object AndroidPersonalDictionarySource {
    fun create(context: Context): PersonalDictionarySource {
        val appContext = context.applicationContext
        val userManager = try {
            appContext.getSystemService(UserManager::class.java)
        } catch (_: Exception) {
            null
        }
        return FilePersonalDictionarySource(
            unlocked = { userManager?.isUserUnlocked ?: false },
            directory = {
                File(
                    appContext.noBackupFilesDir,
                    AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME,
                )
            },
        )
    }
}

/** The process-wide spell-checker engine and its lazily loaded language data. */
internal object SharedSpellCheckerEngine {
    @Volatile
    private var engine: SpellCheckerEngine? = null

    @Volatile
    private var neighbors: KeyNeighborTable? = null

    private val lock = Any()
    private val emptyNeighbors = KeyNeighborTable.build(
        PersonalSubtypes.TATAR_RU,
        false,
        emptyList(),
    )

    /** Prepares the layout model on the service's main-thread creation callback. */
    fun prepare(context: Context) {
        if (neighbors != null) return
        val built = try {
            KeyNeighborTableBuilder.fromTatarLayout(context.applicationContext)
        } catch (_: Exception) {
            null
        }
        if (built != null) {
            synchronized(lock) {
                if (neighbors == null) neighbors = built
            }
        }
    }

    fun get(context: Context): SpellCheckerEngine {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val personalContext = context.applicationContext
            val deviceContext = PreferenceManagerCompat.getDeviceContext(personalContext)
            return SpellCheckerEngine(
                enabled = { readEnabled(deviceContext) },
                loadSource = { load(deviceContext, personalContext) },
            ).also { engine = it }
        }
    }

    private fun readEnabled(context: Context): Boolean = try {
        PreferenceManagerCompat.getDeviceSharedPreferences(context).getBoolean(
            Settings.PREF_TATAR_SPELL_CHECKER,
            true,
        )
    } catch (_: Exception) {
        true
    }

    private fun load(deviceContext: Context, personalContext: Context): SpellCheckerWordSource? = try {
        val opener = SpellCheckerAssetOpener { path ->
            deviceContext.assets.open(path, AssetManager.ACCESS_STREAMING)
        }
        SpellCheckerDictionaryLoader(
            opener,
            { SharedTatarMorphology.get(personalContext) },
            AndroidPersonalDictionarySource.create(personalContext),
            neighbors ?: emptyNeighbors,
        ).load()
    } catch (_: Exception) {
        null
    }
}
