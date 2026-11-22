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

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatMigration
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatMigrationException
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatMigrationRegistry
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatReader
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormatVersion
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalFormats
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalStoreFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PERSONAL_S1_F1
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps

/** File atomicity of the future migration scaffolding, using unchanged s1-f1 bytes as test data. */
class PersonalFormatFileMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU
    private val sourceBytes = PersonalDictionaryTestFixtures.build(
        entries = listOf(
            PersonalDictionaryTestFixtures.Entry("абыйлар", count = 2, serial = 3),
        ),
        schemaId = 1,
        formatVersion = 1,
    )

    @Test
    fun aFutureVersionWithNoRegisteredMigrationFailsStoreOpenClosed() {
        val directory = sourceDirectory()
        val registry = PersonalFormatMigrationRegistry()

        val migrated = coordinator(registry).migrateIfNeeded(
            PersonalStoreFormat.WORDS,
            TARGET.schemaId,
            TARGET.formatVersion,
            subtype,
            directory,
        )

        assertFalse(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertFalse(targetFile(directory).exists())
    }

    @Test
    fun anAlreadyMigratedFileIsNotOverwrittenByTheLegacySource() {
        val directory = sourceDirectory()
        targetFile(directory).writeBytes(sourceBytes.copyOf().also { it[0] = 2 })

        val migrated = coordinator(identityRegistry()).migrateIfNeeded(
            PersonalStoreFormat.WORDS,
            TARGET.schemaId,
            TARGET.formatVersion,
            subtype,
            directory,
        )

        assertTrue(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertEquals(2, targetFile(directory).readBytes()[0].toInt())
    }

    @Test
    fun aDeviceWithNeitherLegacyNorMigratedDataHasNothingToMigrate() {
        val empty = temporaryFolder.newFolder()

        val migrated = coordinator(identityRegistry()).migrateIfNeeded(
            PersonalStoreFormat.WORDS,
            TARGET.schemaId,
            TARGET.formatVersion,
            subtype,
            empty,
        )

        assertTrue(migrated)
        assertTrue(empty.list()!!.isEmpty())
    }

    @Test
    fun aSuccessfulMigrationKeepsTheOriginalAndCompatibleAuxiliaryFiles() {
        val directory = sourceDirectory()
        val salt = File(directory, "salt-$subtype-s1-f1.bin").also { it.writeBytes(byteArrayOf(7, 8)) }
        val registry = identityRegistry()
        val plan = registry.planFor(PersonalStoreFormat.WORDS, TARGET)!!

        val migrated = coordinator(registry).migrate(plan, subtype, directory)

        assertTrue(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertArrayEquals(sourceBytes, targetFile(directory).readBytes())
        assertArrayEquals(byteArrayOf(7, 8), salt.readBytes())
    }

    @Test
    fun anInterruptedTemporaryWriteLeavesTheOriginalIntactAndInstallsNothing() {
        val directory = sourceDirectory()
        val registry = identityRegistry()
        val plan = registry.planFor(PersonalStoreFormat.WORDS, TARGET)!!
        val failing = object : RealOps() {
            override fun syncFile(fileDescriptor: FileDescriptor) = throw IOException("interrupted")
        }

        val migrated = coordinator(registry, failing).migrate(plan, subtype, directory)

        assertFalse(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertFalse(targetFile(directory).exists())
        assertTrue("the failed temp is cleaned", directory.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun aFailureAfterTargetReplacementStillNeverRemovesTheOriginal() {
        val directory = sourceDirectory()
        val registry = identityRegistry()
        val plan = registry.planFor(PersonalStoreFormat.WORDS, TARGET)!!
        val failing = object : RealOps() {
            override fun syncDirectory(directory: File) = throw IOException("sync interrupted")
        }

        val migrated = coordinator(registry, failing).migrate(plan, subtype, directory)

        assertFalse(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertArrayEquals(sourceBytes, targetFile(directory).readBytes())
    }

    @Test
    fun aRejectedMigrationWritesNeitherTargetNorTemp() {
        val directory = sourceDirectory()
        val registry = PersonalFormatMigrationRegistry()
        registry.register(PersonalFormatMigration(
            kind = PersonalStoreFormat.WORDS,
            source = PERSONAL_S1_F1,
            target = TARGET,
            sourceReader = registry.currentReader(PersonalStoreFormat.WORDS),
            targetReader = PersonalFormatReader { _, _ ->
                throw PersonalFormatMigrationException("rejected")
            },
            targetFileName = { "personal-$it-s1-f2.tpers" },
            migrate = { bytes, _ -> bytes.copyOf() },
        ))
        val plan = registry.planFor(PersonalStoreFormat.WORDS, TARGET)!!

        val migrated = coordinator(registry).migrate(plan, subtype, directory)

        assertFalse(migrated)
        assertArrayEquals(sourceBytes, sourceFile(directory).readBytes())
        assertFalse(targetFile(directory).exists())
        assertEquals(listOf(sourceFile(directory).name), directory.list()!!.toList())
    }

    private fun identityRegistry(): PersonalFormatMigrationRegistry =
        PersonalFormatMigrationRegistry().also { registry ->
            registry.register(PersonalFormatMigration(
                kind = PersonalStoreFormat.WORDS,
                source = PERSONAL_S1_F1,
                target = TARGET,
                sourceReader = registry.currentReader(PersonalStoreFormat.WORDS),
                targetReader = registry.currentReader(PersonalStoreFormat.WORDS),
                targetFileName = { "personal-$it-s1-f2.tpers" },
                // No target schema is invented: this identity step exercises only file mechanics.
                migrate = { bytes, _ -> bytes.copyOf() },
            ))
        }

    private fun coordinator(
        registry: PersonalFormatMigrationRegistry,
        ops: DurableFileOps = RealOps(),
    ) = PersonalFormatFileMigration(
        registry = registry,
        fileOps = ops,
        outputOpener = PersonalOutputOpener { FileOutputStream(it) },
        clock = { 1000L },
    )

    private fun sourceDirectory(): File = temporaryFolder.newFolder().also {
        sourceFile(it).writeBytes(sourceBytes)
    }

    private fun sourceFile(directory: File) =
        File(directory, PersonalFormats.s1f1FileName(PersonalStoreFormat.WORDS, subtype))

    private fun targetFile(directory: File) = File(directory, "personal-$subtype-s1-f2.tpers")

    private open class RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }

    private companion object {
        val TARGET = PersonalFormatVersion(1, 2)
    }
}
