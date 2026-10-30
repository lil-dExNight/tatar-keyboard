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

import rkr.simplekeyboard.inputmethod.latin.settings.Settings

/**
 * The two archive directions, export and import, over a [BackupTarget] — the tiny seam between
 * the pure archive logic and the app-private storage. Production wires it to the device's stores
 * (`BackupCoordinator`); tests drive it against a temp directory.
 */
internal interface BackupTarget {
    /** The writing app's versionCode, recorded in the manifest. */
    val versionCode: Int

    /** The current preference map. Keys in [BackupFormat.INTERNAL_PREFERENCE_KEYS] may be present. */
    fun readPreferences(): Map<String, Any?>

    /** The file names currently in the personal directory; unclassifiable ones are ignored. */
    fun personalFileNames(): List<String>

    /** The bytes of one personal file, or null when it cannot be read. */
    fun readPersonalFile(fileName: String): ByteArray?

    /**
     * Replaces one personal store file ([bytes] null deletes it) and refreshes its live store.
     * Returns whether the disk now matches the request.
     */
    fun replacePersonalFile(fileName: String, bytes: ByteArray?): Boolean

    /**
     * Replaces the preference map whole (keys absent from [values] revert to defaults) and
     * re-applies device policy. Returns whether the write went through.
     */
    fun replacePreferences(values: Map<String, Any?>): Boolean

    /** The durable interrupted-import journal, or null when none exists. */
    fun readImportJournal(): ByteArray?

    /** Durably replaces the journal before the first import mutation. */
    fun writeImportJournal(bytes: ByteArray): Boolean

    /** Durably removes the journal after a complete apply or rollback. */
    fun clearImportJournal(): Boolean
}

/** The import verdict. The settings screen turns each into one toast; no detail leaves here. */
internal enum class BackupImportResult { IMPORTED, IMPORTED_PRIVACY_CHANGED, INVALID_FILE, WRITE_FAILED }

internal object BackupTransfer {

    /**
     * Builds the archive for [target], or returns null when recovery fails or any recognized
     * personal file cannot be read and validated. The caller must not write an archive then.
     * Unclassified files, recent emoji, pending counters and salts are outside the backup format.
     */
    @Synchronized
    fun export(target: BackupTarget): ByteArray? {
        if (!recoverPending(target)) return null
        val preferences = target.readPreferences()
            .filterKeys { it !in BackupFormat.INTERNAL_PREFERENCE_KEYS }
        val personal = ArrayList<VerifiedPersonalFile>()
        for (name in target.personalFileNames().sorted()) {
            val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(name) ?: continue
            val bytes = try {
                target.readPersonalFile(name)
            } catch (_: Exception) {
                null
            } ?: return null
            if (!BackupFormat.contentPasses(kind, subtypeId, bytes)) return null
            personal.add(VerifiedPersonalFile(kind, subtypeId, bytes))
        }
        return BackupArchive.create(target.versionCode, preferences, personal)
    }

    /**
     * Restores [archiveBytes] as one transaction. The journal holds both complete states before
     * the first mutation; a failed apply rolls back, and deleting the journal commits the import.
     */
    @Synchronized
    fun import(target: BackupTarget, archiveBytes: ByteArray): BackupImportResult {
        if (!recoverPending(target)) return BackupImportResult.WRITE_FAILED
        val verified = try {
            BackupArchive.readAndVerify(archiveBytes)
        } catch (e: BackupValidationException) {
            return BackupImportResult.INVALID_FILE
        }
        val before = try {
            readCurrentState(target)
        } catch (_: Exception) {
            return BackupImportResult.WRITE_FAILED
        } ?: return BackupImportResult.WRITE_FAILED
        val names = transactionFileNames(before, verified)
        val journal = try {
            BackupImportJournal.create(target.versionCode, before, archiveBytes)
        } catch (_: Exception) {
            return BackupImportResult.WRITE_FAILED
        }
        val journaled = try {
            target.writeImportJournal(journal)
        } catch (_: Exception) {
            false
        }
        if (!journaled) return BackupImportResult.WRITE_FAILED

        val applied = try {
            applyState(target, names, verified)
        } catch (_: Exception) {
            false
        }
        if (!applied) {
            rollback(target, names, before)
            return BackupImportResult.WRITE_FAILED
        }
        val committed = try {
            target.clearImportJournal()
        } catch (_: Exception) {
            false
        }
        if (!committed) return BackupImportResult.WRITE_FAILED

        val currentPreferences = try {
            target.readPreferences()
        } catch (_: Exception) {
            return BackupImportResult.WRITE_FAILED
        }
        return if (privacySwitchesChanged(before.preferences, currentPreferences)) {
            BackupImportResult.IMPORTED_PRIVACY_CHANGED
        } else {
            BackupImportResult.IMPORTED
        }
    }

    /**
     * Rolls back an interrupted import. A read or apply failure returns false and keeps the
     * journal for another attempt; a journal that fails validation can never be applied, so it
     * is deleted instead of blocking every later export and import.
     */
    @Synchronized
    fun recoverPending(target: BackupTarget): Boolean {
        val bytes = try {
            target.readImportJournal()
        } catch (_: Exception) {
            return false
        } ?: return true
        val journal = try {
            BackupImportJournal.read(bytes)
        } catch (_: BackupValidationException) {
            return discardPending(target)
        } catch (_: Exception) {
            return false
        }
        val restored = try {
            applyState(target, transactionFileNames(journal.before, journal.after), journal.before)
        } catch (_: Exception) {
            false
        }
        if (!restored) return false
        return try {
            target.clearImportJournal()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes the interrupted-import journal without applying it. "Erase everything saved" calls
     * this, so a rollback that runs after the erase cannot bring the erased data back.
     */
    @Synchronized
    fun discardPending(target: BackupTarget): Boolean = try {
        target.clearImportJournal()
    } catch (_: Exception) {
        false
    }

    /** Reads and validates every recognized current file so rollback cannot discard its bytes. */
    private fun readCurrentState(target: BackupTarget): VerifiedBackup? {
        val preferences = LinkedHashMap<String, Any>()
        for ((key, value) in target.readPreferences()) {
            if (value != null) preferences[key] = value
        }
        val personal = ArrayList<VerifiedPersonalFile>()
        val names = target.personalFileNames()
            .filter { BackupFormat.classifyPersonalFileName(it) != null }.sorted()
        for (name in names) {
            val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(name)!!
            val bytes = try {
                target.readPersonalFile(name)
            } catch (_: Exception) {
                null
            } ?: return null
            if (!BackupFormat.contentPasses(kind, subtypeId, bytes)) return null
            personal.add(VerifiedPersonalFile(kind, subtypeId, bytes))
        }
        return VerifiedBackup(preferences, personal)
    }

    private fun transactionFileNames(before: VerifiedBackup, after: VerifiedBackup): List<String> =
        (before.personalFiles.map { BackupFormat.personalFileName(it.kind, it.subtypeId) } +
            after.personalFiles.map { BackupFormat.personalFileName(it.kind, it.subtypeId) })
            .toSortedSet().toList()

    /** Applies a complete state to exactly the names captured in the transaction journal. */
    private fun applyState(
        target: BackupTarget,
        names: List<String>,
        state: VerifiedBackup,
    ): Boolean {
        val desired = state.personalFiles.associate { file ->
            BackupFormat.personalFileName(file.kind, file.subtypeId) to file.bytes
        }
        for (name in names) {
            if (!target.replacePersonalFile(name, desired[name])) return false
        }
        return target.replacePreferences(state.preferences)
    }

    private fun rollback(target: BackupTarget, names: List<String>, before: VerifiedBackup) {
        val restored = try {
            applyState(target, names, before)
        } catch (_: Exception) {
            false
        }
        if (restored) {
            try {
                target.clearImportJournal()
            } catch (_: Exception) {
                // The next recovery repeats the complete rollback.
            }
        }
    }

    private fun privacySwitchesChanged(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
    ): Boolean = PRIVACY_SWITCHES.any { key ->
        (before[key] as? Boolean ?: false) != (after[key] as? Boolean ?: false)
    }

    private val PRIVACY_SWITCHES = listOf(
        Settings.PREF_PERSONAL_DICTIONARY,
        Settings.PREF_INCOGNITO_MODE,
    )
}
