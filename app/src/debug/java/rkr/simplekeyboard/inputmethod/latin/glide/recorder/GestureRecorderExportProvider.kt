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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves the ring buffer's JSONL file to the share sheet. Read-only, non-exported, with
 * per-intent URI grants; the only servable path is the fixed `/records` segment, resolved to
 * the recorder's own file, so no caller-controlled path ever reaches the disk. Debug builds
 * only — the provider is declared in the debug manifest and absent from the release APK.
 */
class GestureRecorderExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: android.net.Uri): String = "text/plain"

    override fun openFile(uri: android.net.Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("the export is read-only")
        if (uri.pathSegments != listOf(RECORDS_SEGMENT)) {
            throw FileNotFoundException("unknown path")
        }
        val context = context ?: throw FileNotFoundException("no context")
        val file = java.io.File(
            java.io.File(context.noBackupFilesDir, GestureRecorderController.DIRECTORY_NAME),
            GestureRingBuffer.FILE_NAME,
        )
        if (!file.isFile) throw FileNotFoundException("no records")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: android.net.Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: android.net.Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val RECORDS_SEGMENT = "records"
        private const val AUTHORITY_SUFFIX = ".gesture_recorder_export"

        /** The content URI of the ring buffer file, granted read-only to the share target. */
        fun recordsUri(context: Context): Uri =
            Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/$RECORDS_SEGMENT")
    }
}
