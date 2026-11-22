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

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentStartOverlayTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentStartOverlayTestFixtures.Entry
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat

/** Sentence-start overlays have a local-only recovery contract and are not backup entries. */
class SentenceStartBackupExclusionTest {
    @Test
    fun aValidTsentFileIsRejectedFromAnOtherwiseWellFormedArchive() {
        val subtype = PersonalSubtypes.TATAR_RU
        val settings = BackupPreferencesXml.serialize(mapOf(
            "auto_cap" to true,
            "pref_enabled_subtypes" to "tt_RU;ru",
        ))
        val overlay = SentStartOverlayTestFixtures.build(listOf(Entry("Сәләм")))
        val overlayPath = "personal/${TsentFormat.sentStartFileName(subtype)}"
        val contents = listOf(
            "settings/preferences.xml" to settings,
            overlayPath to overlay,
        )
        val manifest = BackupManifest(
            1,
            BackupFormat.ARCHIVE_PACKAGE,
            45,
            contents.map { (path, bytes) ->
                BackupManifest.Entry(path, bytes.size.toLong(), sha256(bytes))
            },
        ).serialize()

        val archive = zipOf(
            listOf("manifest.json" to manifest) + contents,
        )

        assertThrows(BackupValidationException::class.java) {
            BackupArchive.readAndVerify(archive)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
