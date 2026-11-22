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

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * The device fixture for BackupExportDocumentDeviceTest: a real provider declared in the debug
 * app manifest and backed by the debug app's cache directory. It lives in the debug app rather
 * than the instrumentation APK so the test and the provider share one UID: same-UID resolver
 * calls bypass the provider's permission check. The manifest carries the flags DocumentsProvider
 * mandates in attachInfo (exported, grantUriPermissions, MANAGE_DOCUMENTS as read/write
 * permission); no other app can hold that signature-level permission, so only this app's own
 * UID can use it. Absent from the release APK.
 */
class BackupExportTestDocumentsProvider : DocumentsProvider() {
    private lateinit var directory: File

    override fun onCreate(): Boolean {
        directory = File(context!!.cacheDir, ROOT_DIRECTORY)
        return directory.isDirectory || directory.mkdirs()
    }

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, "backup-export-test")
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(Root.COLUMN_TITLE, "Backup export test")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
                add(Root.COLUMN_MIME_TYPES, "application/zip")
            }
        }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        if (parentDocumentId != ROOT_DOCUMENT_ID) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            directory.listFiles()?.sortedBy { it.name }?.forEach { file -> addDocument(file.name) }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != ROOT_DOCUMENT_ID) throw FileNotFoundException(parentDocumentId)
        require(displayName.isNotEmpty() && displayName == File(displayName).name)
        val documentId = "${System.nanoTime()}-$displayName"
        val file = File(directory, documentId)
        if (!file.createNewFile()) throw FileAlreadyExistsException(file)
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_DOCUMENT_ID) throw FileNotFoundException("cannot delete the root")
        if (!documentFile(documentId).delete()) throw FileNotFoundException(documentId)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val flags = if (mode == "r") {
            ParcelFileDescriptor.MODE_READ_ONLY
        } else {
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_TRUNCATE
        }
        return ParcelFileDescriptor.open(documentFile(documentId), flags)
    }

    private fun MatrixCursor.addDocument(documentId: String) {
        val file = documentFile(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val isRoot = documentId == ROOT_DOCUMENT_ID
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "Backup export test" else file.name)
            add(Document.COLUMN_MIME_TYPE, if (isRoot) Document.MIME_TYPE_DIR else "application/zip")
            add(Document.COLUMN_FLAGS, if (isRoot) Document.FLAG_DIR_SUPPORTS_CREATE
            else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
            add(Document.COLUMN_SIZE, if (isRoot) 0L else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun documentFile(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) return directory
        require(documentId == File(documentId).name) { "non-canonical document id" }
        return File(directory, documentId)
    }

    companion object {
        const val AUTHORITY = "rkr.simplekeyboard.inputmethod.test.backup-export"
        const val ROOT_DOCUMENT_ID = "backup-export-root"
        const val ROOT_DIRECTORY = "backup-export-documents"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
