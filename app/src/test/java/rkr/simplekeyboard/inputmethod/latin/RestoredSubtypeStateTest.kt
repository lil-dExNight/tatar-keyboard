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

package rkr.simplekeyboard.inputmethod.latin

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.settings.backup.BackupArchive
import rkr.simplekeyboard.inputmethod.latin.settings.backup.BackupImportResult
import rkr.simplekeyboard.inputmethod.latin.settings.backup.BackupTarget
import rkr.simplekeyboard.inputmethod.latin.settings.backup.BackupTransfer
import rkr.simplekeyboard.inputmethod.latin.settings.backup.finishPreferenceReplacement
import rkr.simplekeyboard.inputmethod.latin.settings.backup.runOnMainAndWait

/** Restored preferences, live state and listener publication agree on the main thread. */
class RestoredSubtypeStateTest {

    @Test
    fun importReplacesEnabledAndCurrentSubtypesBeforeALaterCycleReset() {
        val fixture = SubtypeFixture()
        fixture.use {
            assertEquals(BackupImportResult.IMPORTED,
                BackupTransfer.import(fixture.Target(), archive(NEW_PREFERENCES)))
            assertLiveState(NEW_ENABLED, NEW_CURRENT, fixture)
            assertNotifications(fixture, NEW_ENABLED to NEW_CURRENT)

            // The old current subtype was English at index two. A reset using that stale list would
            // write English back into the restored preference; a reset of the restored list cannot.
            fixture.main.call { fixture.live.resetSubtypeCycleOrder() }
            assertEquals("$RUSSIAN;$TATAR", fixture.host.readSubtypes())
            assertEquals(NEW_CURRENT, preferenceString(fixture.live.currentSubtype))
            assertNull(fixture.journal)
        }
    }

    @Test
    fun rollbackRestoresTheLiveListAfterAnAmbiguousPreferenceFailure() {
        val fixture = SubtypeFixture()
        fixture.use {
            val target = fixture.Target()
            // Model a write whose result is unknown: its values and reload happened, but the caller
            // hears failure and must roll the complete state back.
            target.failAfterImportedPreferences = true

            assertEquals(BackupImportResult.WRITE_FAILED,
                BackupTransfer.import(target, archive(NEW_PREFERENCES)))
            assertLiveState(OLD_ENABLED, OLD_CURRENT, fixture)
            assertNotifications(fixture, NEW_ENABLED to NEW_CURRENT, OLD_ENABLED to OLD_CURRENT)

            fixture.main.call { fixture.live.resetSubtypeCycleOrder() }
            assertEquals("$ENGLISH;$TATAR;$RUSSIAN", fixture.host.readSubtypes())
            assertEquals(OLD_CURRENT, preferenceString(fixture.live.currentSubtype))
            assertNull(fixture.journal)
        }
    }

    @Test
    fun journalRecoveryRestoresTheLiveListAfterDeathFollowingThePreferenceApply() {
        val fixture = SubtypeFixture()
        fixture.use {
            val target = fixture.Target()
            target.crashAfterImportedPreferences = true

            try {
                BackupTransfer.import(target, archive(NEW_PREFERENCES))
                fail("the injected process death must escape the transfer")
            } catch (expected: SimulatedProcessDeath) {
            }
            assertNotNull(fixture.journal)
            assertLiveState(NEW_ENABLED, NEW_CURRENT, fixture)

            assertTrue(BackupTransfer.recoverPending(fixture.Target()))
            assertLiveState(OLD_ENABLED, OLD_CURRENT, fixture)
            assertNotifications(fixture, NEW_ENABLED to NEW_CURRENT, OLD_ENABLED to OLD_CURRENT)

            fixture.main.call { fixture.live.resetSubtypeCycleOrder() }
            assertEquals("$ENGLISH;$TATAR;$RUSSIAN", fixture.host.readSubtypes())
            assertEquals(OLD_CURRENT, preferenceString(fixture.live.currentSubtype))
            assertNull(fixture.journal)
        }
    }

    @Test
    fun managedSubtypesAreReloadedAfterTheImportedValues() {
        val fixture = SubtypeFixture()
        fixture.use {
            fixture.managedSubtypes = TATAR

            assertEquals(BackupImportResult.IMPORTED,
                BackupTransfer.import(fixture.Target(), archive(NEW_PREFERENCES)))

            // The archive selects Russian. Policy leaves only Tatar, so both the enabled set and
            // the live current selection must come from the restricted state.
            assertLiveState(TATAR, TATAR, fixture)
            assertNotifications(fixture, TATAR to TATAR)
            assertEquals(TATAR, fixture.host.readSubtypes())
            fixture.main.call { fixture.live.resetSubtypeCycleOrder() }
            assertEquals(TATAR, fixture.host.readSubtypes())
        }
    }

    @Test
    fun restoreAfterResourceRecreationUsesTheCurrentApplicationResources() {
        val fixture = SubtypeFixture()
        fixture.use {
            fixture.host.resourceRevision = 2

            assertEquals(BackupImportResult.IMPORTED,
                BackupTransfer.import(fixture.Target(), archive(NEW_PREFERENCES)))

            assertEquals("russian#2", fixture.main.call {
                fixture.live.currentSubtype.layoutDisplayName
            })
            assertNotifications(fixture, NEW_ENABLED to NEW_CURRENT)
        }
    }

    @Test
    fun reloadAlreadyOnMainDoesNotWaitForItself() {
        val fixture = SubtypeFixture()
        fixture.use {
            fixture.main.call {
                runOnMainAndWait(fixture.main.isCurrent, fixture.main::post) {
                    fixture.live.reloadAndNotify()
                }
            }

            assertLiveState(OLD_ENABLED, OLD_CURRENT, fixture)
            assertNotifications(fixture, OLD_ENABLED to OLD_CURRENT)
        }
    }

    private fun assertLiveState(
        expectedEnabled: String,
        expectedCurrent: String,
        fixture: SubtypeFixture,
    ) {
        val state = fixture.main.call {
            val enabled = fixture.live.getAll(false).map(::preferenceString).toSet()
            enabled to preferenceString(fixture.live.currentSubtype)
        }
        assertEquals(expectedEnabled.split(";").toSet(), state.first)
        assertEquals(expectedCurrent, state.second)
    }

    private fun assertNotifications(
        fixture: SubtypeFixture,
        vararg expected: Pair<String, String>,
    ) {
        assertEquals(expected.size, fixture.changes.size)
        for ((index, change) in fixture.changes.withIndex()) {
            val (enabled, current) = expected[index]
            assertFalse("restores and policy reloads are silent", change.userInitiated)
            assertSame("the listener must run on main", fixture.main.thread, change.thread)
            assertEquals(enabled.split(";").toSet(), change.enabled)
            assertEquals("the listener must see the complete reloaded state", current, change.current)
        }
    }

    private fun archive(preferences: Map<String, Any?>): ByteArray =
        BackupArchive.create(VERSION_CODE, preferences, emptyList())

    private class SimulatedProcessDeath : Error()

    private class SubtypeChange(
        val userInitiated: Boolean,
        val thread: Thread,
        val enabled: Set<String>,
        val current: String,
    )

    private inner class SubtypeFixture : Closeable {
        val main = TestMainThread()
        val host = TestSubtypeHost(OLD_PREFERENCES)
        val live = RichInputMethodManager.SubtypeList(host)
        val changes = mutableListOf<SubtypeChange>()
        var journal: ByteArray? = null
        var managedSubtypes: String? = null

        init {
            live.setSubtypeChangeHandler { userInitiated ->
                changes += SubtypeChange(
                    userInitiated,
                    Thread.currentThread(),
                    live.getAll(false).map(::preferenceString).toSet(),
                    preferenceString(live.currentSubtype),
                )
            }
        }

        override fun close() = main.close()

        inner class Target : BackupTarget {
            override val versionCode: Int = VERSION_CODE
            var failAfterImportedPreferences = false
            var crashAfterImportedPreferences = false

            override fun readPreferences(): Map<String, Any?> = HashMap(host.values)

            override fun personalFileNames(): List<String> = emptyList()

            override fun readPersonalFile(fileName: String): ByteArray? = null

            override fun openPersonalStore(fileName: String): Boolean = false

            override fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean =
                throw AssertionError("this fixture has no personal files")

            override fun replacePreferences(values: Map<String, Any?>): Boolean {
                host.values.clear()
                host.values.putAll(values)
                val replaced = finishPreferenceReplacement(
                    committed = true,
                    applyRestrictions = {
                        managedSubtypes?.let { managed -> host.writeSubtypes(managed) }
                    },
                    reloadSubtypes = {
                        runOnMainAndWait(main.isCurrent, main::post) { live.reloadAndNotify() }
                    },
                )
                if (replaced && values == NEW_PREFERENCES) {
                    if (crashAfterImportedPreferences) {
                        crashAfterImportedPreferences = false
                        throw SimulatedProcessDeath()
                    }
                    if (failAfterImportedPreferences) {
                        failAfterImportedPreferences = false
                        return false
                    }
                }
                return replaced
            }

            override fun readImportJournal(): ByteArray? = journal

            override fun writeImportJournal(bytes: ByteArray): Boolean {
                journal = bytes
                return true
            }

            override fun clearImportJournal(): Boolean {
                journal = null
                return true
            }
        }
    }

    private class TestMainThread : Closeable {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "subtype-test-main").apply { isDaemon = true }
        }
        val thread: Thread = executor.submit<Thread> { Thread.currentThread() }.get()
        val isCurrent: Boolean
            get() = Thread.currentThread() == thread

        fun post(runnable: Runnable) = executor.execute(runnable)

        fun <T> call(action: () -> T): T =
            executor.submit<T> { action() }.get(5, TimeUnit.SECONDS)

        override fun close() {
            executor.shutdownNow()
        }
    }

    private class TestSubtypeHost(initial: Map<String, Any?>) :
        RichInputMethodManager.SubtypeList.Host {
        val values = LinkedHashMap(initial)
        var resourceRevision: Int? = null

        override fun readSubtypes(): String =
            values[Settings.PREF_ENABLED_SUBTYPES] as? String ?: ""

        override fun writeSubtypes(subtypes: String) {
            values[Settings.PREF_ENABLED_SUBTYPES] = subtypes
        }

        override fun readCurrentSubtype(): String =
            values[Settings.PREF_CURRENT_SUBTYPE] as? String ?: ""

        override fun writeCurrentSubtype(subtype: String) {
            values[Settings.PREF_CURRENT_SUBTYPE] = subtype
        }

        override fun loadSubtypes(preference: String): MutableList<Subtype> {
            if (preference.isEmpty()) return mutableListOf()
            return preference.split(";").mapTo(mutableListOf<Subtype>()) {
                subtype(it, resourceRevision)
            }
        }

        override fun defaultSubtypes(): MutableList<Subtype> =
            mutableListOf(subtype(TATAR), subtype(RUSSIAN), subtype(ENGLISH))
    }

    companion object {
        private const val VERSION_CODE = 55
        private const val TATAR = "tt_RU:tatar"
        private const val RUSSIAN = "ru:russian"
        private const val ENGLISH = "en_US:qwerty"
        private const val OLD_ENABLED = "$TATAR;$RUSSIAN;$ENGLISH"
        private const val OLD_CURRENT = ENGLISH
        private const val NEW_ENABLED = "$TATAR;$RUSSIAN"
        private const val NEW_CURRENT = RUSSIAN
        private val OLD_PREFERENCES = mapOf<String, Any?>(
            Settings.PREF_ENABLED_SUBTYPES to OLD_ENABLED,
            Settings.PREF_CURRENT_SUBTYPE to OLD_CURRENT,
        )
        private val NEW_PREFERENCES = mapOf<String, Any?>(
            Settings.PREF_ENABLED_SUBTYPES to NEW_ENABLED,
            Settings.PREF_CURRENT_SUBTYPE to NEW_CURRENT,
        )

        private fun subtype(preference: String, resourceRevision: Int? = null): Subtype {
            val (locale, layout) = preference.split(":")
            val displayName = resourceRevision?.let { "$layout#$it" } ?: layout
            return Subtype(locale, layout, displayName, false, null)
        }

        private fun preferenceString(subtype: Subtype): String =
            "${subtype.locale}:${subtype.keyboardLayoutSet}"
    }
}
