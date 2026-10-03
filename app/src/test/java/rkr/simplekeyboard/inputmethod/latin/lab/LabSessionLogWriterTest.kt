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

package rkr.simplekeyboard.inputmethod.latin.lab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The writer's line format, rotation and clearing, on plain JVM files. */
class LabSessionLogWriterTest {

    @Test
    fun linesAreTimestampKindKeyCodeArm() {
        val dir = tempDir()
        try {
            val file = File(dir, LabSessionLogWriter.FILE_NAME)
            val writer = LabSessionLogWriter(file)
            writer.append(1759500000123L, LabSessionLogWriter.KIND_LETTER, 0x04D9, 0)
            writer.append(1759500000456L, LabSessionLogWriter.KIND_DELETE, -5, 1)
            writer.append(1759500000789L, LabSessionLogWriter.KIND_KEYBOARD_HIDDEN,
                LabSessionLogWriter.KEY_CODE_NONE, 2)
            writer.close()
            assertEquals(
                "1759500000123 1 1241 0\n1759500000456 3 -5 1\n1759500000789 5 0 2\n",
                file.readText(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSecondWriterContinuesTheSameFile() {
        val dir = tempDir()
        try {
            val file = File(dir, LabSessionLogWriter.FILE_NAME)
            val first = LabSessionLogWriter(file)
            first.append(1L, LabSessionLogWriter.KIND_KEYBOARD_SHOWN,
                LabSessionLogWriter.KEY_CODE_NONE, 0)
            first.close()
            val second = LabSessionLogWriter(file)
            second.append(2L, LabSessionLogWriter.KIND_SPACE, 32, 0)
            second.close()
            assertEquals("1 4 0 0\n2 2 32 0\n", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rotationCapsThePairAtAboutTwiceTheLimit() {
        val dir = tempDir()
        try {
            val file = File(dir, LabSessionLogWriter.FILE_NAME)
            val limit = 64L
            val writer = LabSessionLogWriter(file, maxBytes = limit)
            // Each line is well under the limit, so this many appends force several rotations.
            repeat(40) { writer.append(1000L + it, LabSessionLogWriter.KIND_LETTER, 1072, 0) }
            writer.close()
            val rotated = LabSessionLogWriter.rotatedFile(file)
            assertTrue("the rotation must exist once the cap is crossed", rotated.isFile)
            assertTrue(rotated.length() <= limit)
            assertTrue(file.length() <= limit)
            // The rotated file ends where the live one begins: no gap, no overlap, and the newer
            // events are in the live file.
            val older = rotated.readLines()
            val newer = file.readLines()
            assertTrue(older.isNotEmpty() && newer.isNotEmpty())
            assertTrue(older.last().substringBefore(' ').toLong()
                    < newer.first().substringBefore(' ').toLong())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesBothFiles() {
        val dir = tempDir()
        try {
            val file = File(dir, LabSessionLogWriter.FILE_NAME)
            val writer = LabSessionLogWriter(file, maxBytes = 32L)
            repeat(8) { writer.append(1000L + it, LabSessionLogWriter.KIND_LETTER, 1072, 0) }
            writer.clear()
            assertFalse(file.exists())
            assertFalse(LabSessionLogWriter.rotatedFile(file).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("lab-log-test").toFile()
}
