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
     * Opens the live store that owns [fileName] and waits for it, so the store's own open moves a
     * damaged file to quarantine. Returns whether the store is loaded.
     */
    fun openPersonalStore(fileName: String): Boolean

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

    /**
     * Durably commits the completed import. Once this returns true the journal no longer rolls
     * anything back and its cleanup may finish later. A false answer leaves the exact pending
     * journal in place, so a reported failure never stands on a swept rollback.
     */
    fun commitImportJournal(): Boolean = clearImportJournal()

    /** Durably removes the journal after a complete rollback or an explicit erase. */
    fun clearImportJournal(): Boolean
}

/** The import verdict. A password request opens a prompt; every other verdict maps to one toast. */
internal enum class BackupImportResult {
    IMPORTED,
    IMPORTED_PRIVACY_CHANGED,
    PASSWORD_REQUIRED,
    INVALID_FILE,
    WRITE_FAILED,
}

internal object BackupTransfer {

    /**
     * Builds the archive for [target], or null when recovery fails, a store stays not loaded or a
     * recognized personal file fails to read or validate; the caller must not write an archive then.
     * Unclassified files, recent emoji, pending counters and salts are outside the backup format.
     * A non-null [password] selects the encrypted wrapper and is cleared before this returns.
     */
    @Synchronized
    fun export(target: BackupTarget, password: CharArray? = null): ByteArray? {
        try {
            if (!recoverPending(target)) return null
            if (!openPersonalStores(target)) return null
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
            val plain = BackupArchive.create(target.versionCode, preferences, personal)
            return if (password == null) plain else BackupEncryption.encrypt(plain, password)
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Restores [archiveBytes] as one transaction. Encryption format detection is automatic: an
     * encrypted archive without [password] returns PASSWORD_REQUIRED; a wrong password, modified
     * header, ciphertext or tag is rejected before recovery or any store access. A supplied
     * password is cleared before this returns. The journal holds two complete plaintext states;
     * a failed apply rolls back, and a durable commit record commits the import.
     */
    @Synchronized
    fun import(
        target: BackupTarget,
        archiveBytes: ByteArray,
        password: CharArray? = null,
    ): BackupImportResult {
        try {
            val plainArchive = if (BackupEncryption.isEncrypted(archiveBytes)) {
                val supplied = password ?: return BackupImportResult.PASSWORD_REQUIRED
                try {
                    BackupEncryption.decrypt(archiveBytes, supplied)
                } catch (e: BackupValidationException) {
                    return BackupImportResult.INVALID_FILE
                }
            } else {
                // A password is a promise that the restored bytes are the encrypted container
                // that prompted for it. A provider can return different bytes on this second
                // read, so never let a plaintext archive use the encrypted-import path.
                if (password != null) return BackupImportResult.INVALID_FILE
                archiveBytes
            }
            val verified = try {
                BackupArchive.readAndVerify(plainArchive)
            } catch (e: BackupValidationException) {
                return BackupImportResult.INVALID_FILE
            }
            if (!recoverPending(target)) return BackupImportResult.WRITE_FAILED
            if (!openPersonalStores(target)) return BackupImportResult.WRITE_FAILED
            val before = try {
                readCurrentState(target)
            } catch (_: Exception) {
                return BackupImportResult.WRITE_FAILED
            } ?: return BackupImportResult.WRITE_FAILED
            val names = transactionFileNames(before, verified)
            val journal = try {
                BackupImportJournal.create(target.versionCode, before, plainArchive)
            } catch (_: Exception) {
                return BackupImportResult.WRITE_FAILED
            }
            val journaled = try {
                target.writeImportJournal(journal)
            } catch (_: Exception) {
                false
            }
            if (!journaled) return BackupImportResult.WRITE_FAILED

            // Privacy switches are combined, never copied: a restore must not silently enable
            // learning or un-pause it. Personal dictionary = current AND imported (learning only
            // if it was already on); pause learning (incognito) = current OR imported (paused if
            // either side paused). Every other preference is taken from the archive verbatim.
            val effective = VerifiedBackup(
                combinePrivacySwitches(before.preferences, verified.preferences),
                verified.personalFiles,
            )
            val applied = try {
                applyState(target, names, effective)
            } catch (_: Exception) {
                false
            }
            if (!applied) {
                rollback(target, names, before)
                return BackupImportResult.WRITE_FAILED
            }
            val committed = try {
                target.commitImportJournal()
            } catch (_: Exception) {
                false
            }
            if (!committed) {
                // The commit never became durable: the journal still names the exact previous
                // state, and a reported failure cannot leave the imported state standing.
                rollback(target, names, before)
                return BackupImportResult.WRITE_FAILED
            }

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
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * Rolls back an interrupted import. A read, validation or apply failure returns false and
     * keeps the journal untouched: it is the exact rollback evidence of an import that may be
     * half applied, so recovery fails closed instead of discarding it and silently accepting a
     * mixed state. Only an explicit erase everything reaches [discardPending].
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

    /**
     * Opens the store of every recognized personal file, so a damaged one is quarantined before
     * anything reads it. False when a store stays not loaded: its file was never judged.
     */
    private fun openPersonalStores(target: BackupTarget): Boolean = try {
        target.personalFileNames()
            .filter { BackupFormat.classifyPersonalFileName(it) != null }
            .sorted()
            .all { target.openPersonalStore(it) }
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

    /**
     * Folds the two privacy switches of [imported] over [current], leaving every other key as the
     * archive carries it. Both switches fail toward privacy:
     * - personal dictionary (learning) = current && imported, so a restore cannot switch learning
     *   on for a user who had it off;
     * - pause learning (incognito) = current || imported, so a restore cannot resume learning for
     *   a user who had it paused.
     * A key is forced into the result only when at least one side carries it: when neither side
     * sets it the archive's omission stands (absent means the default), so a plain restore does
     * not plant explicit values. A side that omits a key counts as that key's default.
     */
    private fun combinePrivacySwitches(
        current: Map<String, Any>,
        imported: Map<String, Any>,
    ): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(imported.size + 2)
        out.putAll(imported)
        // Personal dictionary: learning only when it was already on AND the archive keeps it on.
        combineSwitch(out, current, imported, Settings.PREF_PERSONAL_DICTIONARY) { a, b -> a && b }
        // Pause learning: paused when either side paused.
        combineSwitch(out, current, imported, Settings.PREF_INCOGNITO_MODE) { a, b -> a || b }
        return out
    }

    private fun combineSwitch(
        out: LinkedHashMap<String, Any>,
        current: Map<String, Any>,
        imported: Map<String, Any>,
        key: String,
        fold: (Boolean, Boolean) -> Boolean,
    ) {
        val currentValue = current[key] as? Boolean
        val importedValue = imported[key] as? Boolean
        if (currentValue == null && importedValue == null) return
        val default = PRIVACY_SWITCH_DEFAULTS.getValue(key)
        out[key] = fold(currentValue ?: default, importedValue ?: default)
    }

    private fun privacySwitchesChanged(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
    ): Boolean = PRIVACY_SWITCH_DEFAULTS.any { (key, default) ->
        (before[key] as? Boolean ?: default) != (after[key] as? Boolean ?: default)
    }

    private val PRIVACY_SWITCH_DEFAULTS = mapOf(
        Settings.PREF_PERSONAL_DICTIONARY to Settings.PERSONAL_DICTIONARY_DEFAULT,
        Settings.PREF_INCOGNITO_MODE to false,
    )
}
