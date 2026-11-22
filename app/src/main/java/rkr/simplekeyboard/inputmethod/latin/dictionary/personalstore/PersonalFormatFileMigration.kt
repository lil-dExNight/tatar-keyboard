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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatMigrationPlan
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatMigrationRegistry
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatVersion
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormats
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalStoreFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PERSONAL_F1_FORMAT_VERSION
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PERSONAL_S1_SCHEMA_ID
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import java.io.File
import java.io.IOException

/**
 * Durable file half of a registered personal migration. The s1-f1 source is read-only and is never
 * deleted: an interrupted or failed migration therefore leaves the original representation intact.
 * The destination follows temp -> fsync -> target validation -> atomic replace -> directory fsync.
 * A successful run also leaves the source for explicit old/new reader fallback; erase-all's existing
 * whole-directory sweep removes both representations and their auxiliary files.
 */
internal class PersonalFormatFileMigration(
    private val registry: PersonalFormatMigrationRegistry,
    private val fileOps: DurableFileOps,
    private val outputOpener: PersonalOutputOpener,
    private val clock: StorageClock,
) {
    /**
     * Store-open gate. Current s1-f1 does no I/O. A future writer must have a registered path;
     * an existing destination is left for the store's normal validator, and a device with neither
     * representation has nothing to migrate. Otherwise s1-f1 is migrated before the new file opens.
     */
    fun migrateIfNeeded(
        kind: PersonalStoreFormat,
        schemaId: Int,
        formatVersion: Int,
        subtypeId: String?,
        directory: File,
    ): Boolean {
        if (schemaId == PERSONAL_S1_SCHEMA_ID && formatVersion == PERSONAL_F1_FORMAT_VERSION) {
            return true
        }
        val plan = registry.planFor(kind, PersonalFormatVersion(schemaId, formatVersion)) ?: return false
        if (File(directory, plan.targetFileName(subtypeId)).isFile) return true
        if (!File(directory, PersonalFormats.s1f1FileName(kind, subtypeId)).isFile) return true
        return migrate(plan, subtypeId, directory)
    }

    fun migrate(
        plan: PersonalFormatMigrationPlan,
        subtypeId: String?,
        directory: File,
    ): Boolean {
        val source = File(directory, PersonalFormats.s1f1FileName(plan.kind, subtypeId))
        val destination = File(directory, plan.targetFileName(subtypeId))
        val sourceBytes = try {
            PersonalFileReads.readCapped(source, PersonalFormats.s1f1MaxBytes(plan.kind))
        } catch (_: Exception) {
            null
        } ?: return false

        val migrated = try {
            registry.migrate(plan, sourceBytes, subtypeId)
        } catch (_: Exception) {
            return false
        }
        // A no-op s1-f1 plan is the fixture/backup round-trip path, not a file replacement.
        if (destination == source) return true

        var temporary: File? = null
        return try {
            temporary = createExclusiveTemp(directory, plan)
            outputOpener.open(temporary!!).use { output ->
                output.write(migrated)
                output.flush()
                fileOps.syncFile(output.fd)
            }
            val readBack = PersonalFileReads.readCapped(temporary!!, migrated.size.toLong())
                ?: throw IOException("migration read-back failed")
            registry.validateTarget(plan, readBack, subtypeId)
            fileOps.atomicReplace(temporary!!, destination)
            fileOps.syncDirectory(directory)
            true
        } catch (_: Exception) {
            temporary?.let { temp ->
                if (temp.exists()) runCatching { fileOps.delete(temp) }
            }
            false
        }
    }

    private fun createExclusiveTemp(directory: File, plan: PersonalFormatMigrationPlan): File {
        val timestamp = clock.nowMillis()
        for (counter in 0 until MAX_TEMP_ATTEMPTS) {
            val file = File(
                directory,
                ".personal-migration-${plan.kind.name}.$timestamp.$counter.tmp",
            )
            if (fileOps.createNewFile(file)) return file
        }
        throw IOException("cannot create exclusive migration temp")
    }

    private companion object {
        const val MAX_TEMP_ATTEMPTS = 100
    }
}
