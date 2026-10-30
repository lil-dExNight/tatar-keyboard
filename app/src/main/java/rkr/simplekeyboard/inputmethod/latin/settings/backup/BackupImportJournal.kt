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

import java.nio.ByteBuffer

/** A verified interrupted import: the complete state before it and the requested state after it. */
internal class VerifiedImportJournal(
    val before: VerifiedBackup,
    val after: VerifiedBackup,
)

/**
 * Private on-disk journal for one import. Both states use the verified archive representation;
 * the outer header only frames them. This is not a user-carried archive and has its own magic.
 */
internal object BackupImportJournal {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'K'.code.toByte(), 'B'.code.toByte(),
        'J'.code.toByte(), 1)
    private const val HEADER_BYTES = 9
    const val MAX_SERIALIZED_BYTES = 2 * BackupFormat.MAX_ARCHIVE_BYTES + HEADER_BYTES

    /** Validates the rollback snapshot and frames it with the verified requested archive. */
    fun create(versionCode: Int, before: VerifiedBackup, afterArchive: ByteArray): ByteArray {
        val beforeArchive = BackupArchive.create(versionCode, before.preferences, before.personalFiles)
        require(beforeArchive.size.toLong() <= BackupFormat.MAX_ARCHIVE_BYTES)
        BackupArchive.readAndVerifySnapshot(beforeArchive)
        require(afterArchive.size.toLong() <= BackupFormat.MAX_ARCHIVE_BYTES)
        val output = ByteBuffer.allocate(HEADER_BYTES + beforeArchive.size + afterArchive.size)
        output.put(MAGIC)
        output.putInt(beforeArchive.size)
        output.put(beforeArchive)
        output.put(afterArchive)
        return output.array()
    }

    /** Verifies both complete states before recovery is allowed to mutate anything. */
    fun read(bytes: ByteArray): VerifiedImportJournal {
        if (bytes.size < HEADER_BYTES || bytes.size.toLong() > MAX_SERIALIZED_BYTES) {
            fail("journal size is invalid")
        }
        val input = ByteBuffer.wrap(bytes)
        for (expected in MAGIC) {
            if (input.get() != expected) fail("journal identity is invalid")
        }
        val beforeSize = input.int
        if (beforeSize <= 0 || beforeSize.toLong() > BackupFormat.MAX_ARCHIVE_BYTES ||
                HEADER_BYTES.toLong() + beforeSize >= bytes.size.toLong()) {
            fail("journal frame is invalid")
        }
        val afterSize = bytes.size - HEADER_BYTES - beforeSize
        if (afterSize.toLong() > BackupFormat.MAX_ARCHIVE_BYTES) fail("journal frame is invalid")
        val beforeBytes = ByteArray(beforeSize)
        val afterBytes = ByteArray(afterSize)
        input.get(beforeBytes)
        input.get(afterBytes)
        return VerifiedImportJournal(
            BackupArchive.readAndVerifySnapshot(beforeBytes),
            BackupArchive.readAndVerify(afterBytes),
        )
    }

    private fun fail(message: String): Nothing = throw BackupValidationException(message)
}
