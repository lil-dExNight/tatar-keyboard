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

import java.io.OutputStream

/** The export verdict. A touched failure may leave a document behind (never atomic). */
internal enum class BackupExportResult {
    SAVED,
    FAILED,
    FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT,
}

/**
 * The document half of the export: write, close, re-read and verify, then clean up.
 *
 * Document providers are not an atomic file replacement: a failed write or a failed close can
 * leave a complete or truncated document. Therefore success requires a byte-equal read-back and
 * the caller's full archive verification. A touched failure gets one best-effort delete; when the
 * provider refuses it (or lies about success), the answer says a partial document may remain.
 */
internal object BackupDocumentWrite {
    /** Decrypts when wrapped, then applies every structural, checksum and content archive check. */
    fun verifyExportedArchive(bytes: ByteArray, password: CharArray?) {
        val plain = if (BackupEncryption.isEncrypted(bytes)) {
            val supplied = password
                ?: throw BackupValidationException("the exported password is missing")
            BackupEncryption.decrypt(bytes, supplied)
        } else {
            bytes
        }
        BackupArchive.readAndVerify(plain)
    }

    /**
     * Writes [archive] through the supplied provider operations and reports the verdict.
     *
     * [openOutputStream] supplies a fresh truncating stream, [readBack] re-reads the completed
     * document, [verifyArchive] runs the complete archive gate on those bytes, and [delete]
     * performs the best-effort cleanup. The returned verdict carries no archive content.
     */
    fun writeDocument(
        archive: ByteArray,
        openOutputStream: () -> OutputStream?,
        readBack: () -> ByteArray?,
        verifyArchive: (ByteArray) -> Unit,
        delete: () -> Boolean,
    ): BackupExportResult {
        try {
            // Opening can create or truncate server-side before it fails locally. Treat every
            // attempted open as a touched document so cleanup and the partial warning are not lost.
            val output = openOutputStream() ?: return cleanup(delete)
            // `use` treats close exactly like the rest of the write: a failing close is a failed
            // export, not a background detail.
            output.use { stream ->
                stream.write(archive)
                stream.flush()
            }
            val completed = readBack()
            if (completed == null || !completed.contentEquals(archive)) {
                return cleanup(delete)
            }
            try {
                verifyArchive(completed)
            } catch (_: Exception) {
                return cleanup(delete)
            }
            return BackupExportResult.SAVED
        } catch (_: Exception) {
            return cleanup(delete)
        }
    }

    private fun cleanup(delete: () -> Boolean): BackupExportResult {
        try {
            delete()
        } catch (_: Exception) {
            // The cleanup is deliberately best effort. Even a provider's successful answer cannot
            // turn an arbitrary document provider into an atomic replacement, so keep the warning.
        }
        return BackupExportResult.FAILED_WITH_POSSIBLE_PARTIAL_DOCUMENT
    }
}
