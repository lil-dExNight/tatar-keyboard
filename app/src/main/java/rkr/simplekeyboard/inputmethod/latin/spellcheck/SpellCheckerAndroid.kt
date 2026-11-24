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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.AndroidPersonalDictionaryStorage
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDictionaryStorageFactory
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
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

/** Uses only the catalog's read-only activation path; the spell checker never prepares storage. */
internal class AndroidSpellCheckerDictionaryLeaseSource(
    context: Context,
) : SpellCheckerDictionaryLeaseSource {
    private val appContext = context.applicationContext

    override fun acquire(spec: DictionaryArtifactSpec): DictionaryFileLease? = try {
        AndroidDictionaryStorageFactory.create(appContext, DIRECT_EXECUTOR, spec)
            .acquireLatestForActivation()
    } catch (_: Exception) {
        null
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}

/** The process-wide spell-checker engine; its layout and source loads run on one worker. */
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
    private val loadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spell-checker").apply { isDaemon = true }
    }

    /** Queues layout construction and source preparation so binder callbacks never block. */
    fun prepare(context: Context) {
        val personalContext = context.applicationContext
        try {
            loadExecutor.execute {
                if (neighbors == null) {
                    val built = try {
                        KeyNeighborTableBuilder.fromTatarLayout(personalContext)
                    } catch (_: Exception) {
                        null
                    }
                    if (built != null) {
                        synchronized(lock) {
                            if (neighbors == null) neighbors = built
                        }
                    }
                }
                get(personalContext).prepare()
            }
        } catch (_: Exception) {
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
                loadExecutor = loadExecutor,
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

    private fun load(deviceContext: Context, personalContext: Context): SpellCheckerWordSource? =
        try {
            val opener = SpellCheckerAssetOpener { path ->
                deviceContext.assets.open(path, AssetManager.ACCESS_STREAMING)
            }
            val loaded = SpellCheckerDictionaryLoader(
                opener,
                { SharedTatarMorphology.getIfLoaded() },
                AndroidPersonalDictionarySource.create(personalContext),
                neighbors ?: emptyNeighbors,
                AndroidSpellCheckerDictionaryLeaseSource(deviceContext),
            ).load()
            // Warming stays behind the dictionaries: the acceptor asset is read on this worker,
            // never inside a binder call.
            if (loaded != null) SharedTatarMorphology.get(personalContext)
            loaded
        } catch (_: Exception) {
            null
        }
}
