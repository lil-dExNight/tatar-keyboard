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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [GestureRingBuffer]: append order, the oldest-first drop at the cap, reload from disk, and
 * truncation on clear.
 */
class GestureRingBufferTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appendsKeepOrderAndSurviveAReload() {
        val dir = temporaryFolder.newFolder()
        val buffer = GestureRingBuffer(dir)
        assertTrue(buffer.append("""{"n":1}"""))
        assertTrue(buffer.append("""{"n":2}"""))
        assertEquals(2, buffer.size)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), buffer.snapshot())

        val reloaded = GestureRingBuffer(dir)
        assertEquals(2, reloaded.size)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), reloaded.snapshot())
    }

    @Test
    fun theCapDropsTheOldestRecord() {
        val dir = temporaryFolder.newFolder()
        val buffer = GestureRingBuffer(dir, maxRecords = 3)
        for (i in 1..5) assertTrue(buffer.append("r$i"))
        assertEquals(3, buffer.size)
        assertEquals(listOf("r3", "r4", "r5"), buffer.snapshot())

        // The file itself holds exactly the newest window: a reload sees the same three.
        val file = File(dir, GestureRingBuffer.FILE_NAME)
        assertEquals(listOf("r3", "r4", "r5"), file.readLines())
        assertEquals(listOf("r3", "r4", "r5"), GestureRingBuffer(dir).snapshot())
    }

    @Test
    fun aFilePastTheCapIsCompactedOnLoad() {
        val dir = temporaryFolder.newFolder()
        File(dir, GestureRingBuffer.FILE_NAME).writeText((1..7).joinToString("\n") { "r$it" } + "\n")
        val buffer = GestureRingBuffer(dir, maxRecords = 3)
        assertEquals(listOf("r5", "r6", "r7"), buffer.snapshot())
        assertEquals(listOf("r5", "r6", "r7"),
            File(dir, GestureRingBuffer.FILE_NAME).readLines())
    }

    @Test
    fun clearTruncatesTheFile() {
        val dir = temporaryFolder.newFolder()
        val buffer = GestureRingBuffer(dir)
        buffer.append("r1")
        buffer.append("r2")
        assertTrue(buffer.clear())
        assertEquals(0, buffer.size)
        assertEquals(0, GestureRingBuffer(dir).size)
    }

    @Test
    fun theDefaultCapIsTheProtocolCap() {
        assertEquals(2000, GestureRingBuffer.MAX_RECORDS)
    }

    @Test
    fun aMissingDirectoryIsCreated() {
        val dir = File(temporaryFolder.newFolder(), "nested/deeper")
        val buffer = GestureRingBuffer(dir)
        assertTrue(buffer.append("r1"))
        assertTrue(File(dir, GestureRingBuffer.FILE_NAME).isFile)
    }
}
