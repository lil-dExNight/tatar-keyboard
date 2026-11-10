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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.settings.backup.finishPreferenceReplacement
import rkr.simplekeyboard.inputmethod.latin.settings.backup.runOnMainAndWait

/**
 * Restored subtype settings against the real preferences and the live subtype manager. The
 * journal and the simulated managed policy live in a test directory; no personal store is opened,
 * so the user's learned data stays untouched.
 */
class BackupRestoreDeviceTest : InstrumentationTestCase() {

    private class SimulatedProcessDeath : Error()

    private inner class Target : BackupTarget {
        var failAfterImportedPreferences = false
        var crashAfterImportedPreferences = false
        var managedEnabled: String? = null
        var managedPersonalDictionary: Boolean? = null
        private val journal = File(testDirectory, "backup-import-journal.bin")

        override val versionCode: Int
            get() = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                info.versionCode
            } catch (_: Exception) {
                0
            }

        override fun readPreferences(): Map<String, Any?> = LinkedHashMap(preferences.all)

        override fun personalFileNames(): List<String> = emptyList()

        override fun readPersonalFile(fileName: String): ByteArray? = null

        override fun openPersonalStore(fileName: String): Boolean = false

        override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean =
            throw AssertionError("this fixture carries no personal files")

        override fun replacePreferences(values: Map<String, Any?>): Boolean {
            val editor = preferences.edit()
            editor.clear()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
            val replaced = finishPreferenceReplacement(
                committed = editor.commit(),
                applyRestrictions = {
                    // The device holds no managed profile; these two writes stand in for the exact
                    // keys and precedence Settings.loadRestrictions applies from a real manager.
                    managedEnabled?.let { preferences.edit()
                        .putString(Settings.PREF_ENABLED_SUBTYPES, it).commit() }
                    managedPersonalDictionary?.let { preferences.edit()
                        .putBoolean(Settings.PREF_PERSONAL_DICTIONARY, it).commit() }
                    val active = buildSet {
                        if (managedEnabled != null) add(Settings.PREF_ENABLED_SUBTYPES)
                        if (managedPersonalDictionary != null) add(Settings.PREF_PERSONAL_DICTIONARY)
                    }
                    val editor2 = preferences.edit()
                    if (active.isEmpty()) editor2.remove(Settings.ACTIVE_RESTRICTIONS)
                    else editor2.putStringSet(Settings.ACTIVE_RESTRICTIONS, active)
                    editor2.commit()
                },
                reloadSubtypes = {
                    runOnMainAndWait(
                        isMainThread = Looper.myLooper() == Looper.getMainLooper(),
                        poster = { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
                    ) {
                        RichInputMethodManager.reloadSubtypesAndNotifyIfInitialized()
                    }
                },
            )
            if (replaced && values[Settings.PREF_CURRENT_SUBTYPE] == NEW_CURRENT &&
                values[Settings.PREF_ENABLED_SUBTYPES] == NEW_ENABLED
            ) {
                if (crashAfterImportedPreferences) throw SimulatedProcessDeath()
                if (failAfterImportedPreferences) return false
            }
            return replaced
        }

        override fun readImportJournal(): ByteArray? =
            if (journal.isFile) journal.readBytes() else null

        override fun writeImportJournal(bytes: ByteArray): Boolean = try {
            FileOutputStream(journal).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            true
        } catch (_: Exception) {
            false
        }

        override fun clearImportJournal(): Boolean =
            !journal.exists() || journal.delete()
    }

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var savedPreferences: LinkedHashMap<String, Any?>
    private lateinit var testDirectory: File

    override fun setUp() {
        super.setUp()
        context = instrumentation.targetContext.applicationContext
        preferences = PreferenceManagerCompat.getDeviceSharedPreferences(context)
        savedPreferences = LinkedHashMap(preferences.all)
        RichInputMethodManager.init(context)
        testDirectory = File(
            context.noBackupFilesDir,
            "backup-restore-device-test-${System.nanoTime()}",
        )
        assertTrue(testDirectory.mkdirs())
        setRawPreferences(mapOf(
            Settings.PREF_ENABLED_SUBTYPES to OLD_ENABLED,
            Settings.PREF_CURRENT_SUBTYPE to OLD_CURRENT,
        ))
    }

    override fun tearDown() {
        restoreOriginalState()
        testDirectory.deleteRecursively()
        super.tearDown()
    }

    fun testImportAppliesEnabledAndCurrentSubtypesWithoutAStaleCycleWrite() {
        val target = Target()
        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(target, newArchive()))
        assertNull(target.readImportJournal())
        assertLiveState(NEW_ENABLED, NEW_CURRENT)

        RichInputMethodManager.getInstance().resetSubtypeCycleOrder()
        assertLiveState(NEW_ENABLED, NEW_CURRENT)
        assertEquals("the cycle reset wrote a stale enabled list",
            NEW_ENABLED.split(";").toSet(),
            preferences.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!
                .split(";").toSet())
    }

    fun testAmbiguousPreferenceFailureRollsBackTheLiveSubtypes() {
        val target = Target().apply { failAfterImportedPreferences = true }

        assertEquals(BackupImportResult.WRITE_FAILED, BackupTransfer.import(target, newArchive()))
        assertLiveState(OLD_ENABLED, OLD_CURRENT)
        assertNull("the rolled-back import left its journal", target.readImportJournal())
    }

    fun testProcessDeathAfterPreferenceApplyRecoversTheLiveSubtypes() {
        val target = Target().apply { crashAfterImportedPreferences = true }
        try {
            BackupTransfer.import(target, newArchive())
            fail("the simulated process death did not happen")
        } catch (expected: SimulatedProcessDeath) {
        }
        assertLiveState(NEW_ENABLED, NEW_CURRENT)
        assertNotNull("the interrupted import lost its journal", target.readImportJournal())

        assertTrue(BackupTransfer.recoverPending(Target()))
        assertLiveState(OLD_ENABLED, OLD_CURRENT)
        assertNull("a complete recovery left its journal", Target().readImportJournal())
    }

    fun testManagedRestrictionWinsAndNoImportedRestrictionCacheStays() {
        val archive = BackupArchive.create(
            Target().versionCode,
            mapOf(
                Settings.PREF_ENABLED_SUBTYPES to NEW_ENABLED,
                Settings.PREF_CURRENT_SUBTYPE to NEW_CURRENT,
                Settings.ACTIVE_RESTRICTIONS to setOf(Settings.PREF_PERSONAL_DICTIONARY),
                Settings.PREF_PERSONAL_DICTIONARY to true,
            ),
            emptyList(),
        )
        val target = Target().apply {
            managedEnabled = TATAR
            managedPersonalDictionary = false
        }

        assertEquals(BackupImportResult.IMPORTED, BackupTransfer.import(target, archive))
        assertLiveState(TATAR, TATAR, checkCurrentPreference = false)
        assertEquals("the imported restriction cache was restored",
            setOf(Settings.PREF_ENABLED_SUBTYPES, Settings.PREF_PERSONAL_DICTIONARY),
            preferences.getStringSet(Settings.ACTIVE_RESTRICTIONS, emptySet()),
        )
        assertFalse("the imported privacy value won over managed policy",
            preferences.getBoolean(Settings.PREF_PERSONAL_DICTIONARY, true))
    }

    private fun newArchive(): ByteArray = BackupArchive.create(
        Target().versionCode,
        mapOf(
            Settings.PREF_ENABLED_SUBTYPES to NEW_ENABLED,
            Settings.PREF_CURRENT_SUBTYPE to NEW_CURRENT,
        ),
        emptyList(),
    )

    private fun assertLiveState(
        expectedEnabled: String,
        expectedCurrent: String,
        checkCurrentPreference: Boolean = true,
    ) {
        instrumentation.runOnMainSync {
            val manager = RichInputMethodManager.getInstance()
            val enabled = manager.getEnabledSubtypes(false).map { subtype ->
                "${subtype.locale}:${subtype.keyboardLayoutSet}"
            }.toSet()
            val current = manager.currentSubtype.let {
                "${it.locale}:${it.keyboardLayoutSet}"
            }
            assertEquals(expectedEnabled.split(";").toSet(), enabled)
            assertEquals(expectedCurrent, current)
            if (checkCurrentPreference) {
                assertEquals(expectedCurrent,
                    preferences.getString(Settings.PREF_CURRENT_SUBTYPE, ""))
            }
        }
    }

    private fun setRawPreferences(values: Map<String, Any?>) {
        val editor = preferences.edit()
        editor.clear()
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            }
        }
        assertTrue(editor.commit())
        instrumentation.runOnMainSync {
            RichInputMethodManager.reloadSubtypesAndNotifyIfInitialized()
        }
    }

    private fun restoreOriginalState() {
        val editor = preferences.edit()
        editor.clear()
        for ((key, value) in savedPreferences) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            }
        }
        editor.commit()
        instrumentation.runOnMainSync {
            RichInputMethodManager.reloadSubtypesAndNotifyIfInitialized()
        }
    }

    private companion object {
        const val TATAR = "tt_RU:tatar"
        const val RUSSIAN = "ru:russian"
        const val ENGLISH = "en_US:qwerty"
        const val OLD_ENABLED = "$TATAR;$RUSSIAN;$ENGLISH"
        const val OLD_CURRENT = ENGLISH
        const val NEW_ENABLED = "$TATAR;$RUSSIAN"
        const val NEW_CURRENT = RUSSIAN
    }
}
