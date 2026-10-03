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
}

/** The import verdict. The settings screen turns each into one toast; no detail leaves here. */
internal enum class BackupImportResult { IMPORTED, INVALID_FILE, WRITE_FAILED }

internal object BackupTransfer {

    /**
     * Builds the archive for [target]. A personal file that fails its store's own validator is
     * skipped: the store will quarantine it on its next open anyway, and an archive that could not
     * be imported back is worse than one without a corrupt file. The recent-emoji list, the pending
     * counters and the salts are not part of a backup at all (see [BackupFormat]).
     */
    fun export(target: BackupTarget): ByteArray {
        val preferences = target.readPreferences()
            .filterKeys { it !in BackupFormat.INTERNAL_PREFERENCE_KEYS }
        val personal = ArrayList<VerifiedPersonalFile>()
        for (name in target.personalFileNames().sorted()) {
            val (kind, subtypeId) = BackupFormat.classifyPersonalFileName(name) ?: continue
            val bytes = runCatching { target.readPersonalFile(name) }.getOrNull() ?: continue
            if (!BackupFormat.contentPasses(kind, subtypeId, bytes)) continue
            personal.add(VerifiedPersonalFile(kind, subtypeId, bytes))
        }
        return BackupArchive.create(target.versionCode, preferences, personal)
    }

    /**
     * Restores [archiveBytes] into [target]. Everything is validated first
     * ([BackupArchive.readAndVerify]); only a fully valid archive writes anything. A restore
     * replaces: a personal file absent from the archive is deleted locally, so the learned state
     * afterwards is exactly the archive's. Write failures are reported as
     * [BackupImportResult.WRITE_FAILED]; they can leave a partial restore (a full disk is the
     * realistic cause), never an unnoticed one.
     */
    fun import(target: BackupTarget, archiveBytes: ByteArray): BackupImportResult {
        val verified = try {
            BackupArchive.readAndVerify(archiveBytes)
        } catch (e: BackupValidationException) {
            return BackupImportResult.INVALID_FILE
        }
        val desired = verified.personalFiles.associate { file ->
            BackupFormat.personalFileName(file.kind, file.subtypeId) to file.bytes
        }
        // Both directions of the diff classify first, so a foreign file (another build's schema,
        // another app's leftover) is neither read nor touched.
        val localNames = target.personalFileNames()
            .filter { BackupFormat.classifyPersonalFileName(it) != null }
        var written = true
        for (name in (desired.keys + localNames).toSortedSet()) {
            if (!target.replacePersonalFile(name, desired[name])) written = false
        }
        val preferences = verified.preferences
            .filterKeys { it !in BackupFormat.INTERNAL_PREFERENCE_KEYS }
        if (!target.replacePreferences(preferences)) written = false
        return if (written) BackupImportResult.IMPORTED else BackupImportResult.WRITE_FAILED
    }
}
