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
import android.provider.DocumentsContract
import android.test.InstrumentationTestCase
import java.io.File

/** A real [BackupExportTestDocumentsProvider] round trip through the platform resolver, on device only. */
class BackupExportDocumentDeviceTest : InstrumentationTestCase() {

    private lateinit var context: Context

    override fun setUp() {
        super.setUp()
        // The provider is declared in the debug app, so the documents live in the target app's
        // cache directory and the target context owns both the resolver calls and the cleanup.
        context = instrumentation.targetContext
    }

    override fun tearDown() {
        File(context.cacheDir, BackupExportTestDocumentsProvider.ROOT_DIRECTORY)
            .deleteRecursively()
        super.tearDown()
    }

    fun testExportRoundTripThroughRealDocumentProvider() {
        val archive = BackupArchive.create(
            versionCode = 1,
            preferences = mapOf("pref_space_swipe" to true),
            personalFiles = emptyList(),
        )
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUri(
            BackupExportTestDocumentsProvider.AUTHORITY,
            BackupExportTestDocumentsProvider.ROOT_DOCUMENT_ID,
        )
        val document = DocumentsContract.createDocument(
            resolver, parent, "application/zip", "roundtrip.zip",
        ) ?: throw AssertionError("the test document provider could not create a file")

        val result = BackupDocumentWrite.writeDocument(
            archive,
            openOutputStream = { resolver.openOutputStream(document, "w") },
            readBack = { resolver.openInputStream(document)?.use { it.readBytes() } },
            verifyArchive = { BackupDocumentWrite.verifyExportedArchive(it, null) },
            delete = { DocumentsContract.deleteDocument(resolver, document) },
        )

        assertEquals(BackupExportResult.SAVED, result)
        requireNotNull(resolver.openInputStream(document)) {
            "the completed backup disappeared from the document provider"
        }.use { input ->
            assertTrue(archive.contentEquals(input.readBytes()))
        }
        assertTrue(DocumentsContract.deleteDocument(resolver, document))
    }
}
