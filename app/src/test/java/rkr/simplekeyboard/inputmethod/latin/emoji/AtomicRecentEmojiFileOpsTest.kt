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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recents file's read side: a legitimately tiny file is
 * read, an oversized one is refused on its LENGTH — before any byte is decoded — and the
 * absent/unreadable shapes still answer null. The write side answers whether the replacement is
 * durable (temp fsync, rename, directory fsync): a failure before the rename never costs the
 * previous medium, while a post-rename directory-sync failure reports false with the new content
 * already installed. The staged writer's faults are injected deterministically.
 */
class AtomicRecentEmojiFileOpsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aLegitimateMediumIsReadBackVerbatim() {
        val file = temporaryFolder.newFile("recent_emoji.txt")
        AtomicRecentEmojiFileOps.writeAtomic(file, "😀\n🚀")

        assertEquals("😀\n🚀", AtomicRecentEmojiFileOps.read(file))
    }

    @Test
    fun anOversizedFileIsRefusedBeforeItIsRead() {
        val file = temporaryFolder.newFile("recent_emoji.txt")
        file.writeBytes(ByteArray(4097) { 'a'.code.toByte() })

        assertNull(AtomicRecentEmojiFileOps.read(file))
    }

    @Test
    fun theCapBoundaryItselfIsStillReadable() {
        val file = temporaryFolder.newFile("recent_emoji.txt")
        file.writeBytes(ByteArray(4096) { 'b'.code.toByte() })

        assertEquals(4096, AtomicRecentEmojiFileOps.read(file)!!.length)
    }

    @Test
    fun anAbsentMediumAnswersNull() {
        assertNull(AtomicRecentEmojiFileOps.read(File(temporaryFolder.root, "never-written.txt")))
    }

    @Test
    fun onlyAnAbsentOrEmptyMediumCountsAsCleared() {
        val file = File(temporaryFolder.root, "recent_emoji.txt")
        assertTrue(AtomicRecentEmojiFileOps.isAbsentOrEmpty(file))

        AtomicRecentEmojiFileOps.writeAtomic(file, "😀")
        assertFalse(AtomicRecentEmojiFileOps.isAbsentOrEmpty(file))

        AtomicRecentEmojiFileOps.writeAtomic(file, "")
        assertTrue(AtomicRecentEmojiFileOps.isAbsentOrEmpty(file))

        // A directory in the medium's place is not an empty medium.
        assertFalse(AtomicRecentEmojiFileOps.isAbsentOrEmpty(temporaryFolder.newFolder("recent_dir")))
    }

    @Test
    fun aDurableWriteAnswersTrueAndLeavesNoTemp() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        assertTrue(AtomicRecentEmojiFileOps.writeAtomic(file, "😀"))
        assertEquals("😀", AtomicRecentEmojiFileOps.read(file))
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun theDefaultWriterPerformsARealDurableWriteIncludingTheDirectorySync() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        // No injected stages: the production sequence, including the real directory fsync, must
        // succeed on this runtime. A directory-sync approach the runtime rejects fails here even
        // though every injected-stage test would still pass.
        assertTrue(AtomicRecentEmojiWriter().write(file, "😀"))
        assertEquals("😀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())

        // Replacing an existing medium goes through the same real stages.
        assertTrue(AtomicRecentEmojiWriter().write(file, "🚀"))
        assertEquals("🚀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun anInterruptedWriteKeepsThePreviousMediumUntilTheRetryLands() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        // The replacement was written and synced, then the process died before the rename.
        val temp = File(temporaryFolder.root, "recent_emoji_v1.tmp")
        temp.writeText("🚀")

        assertEquals("😀", AtomicRecentEmojiFileOps.read(file))

        assertTrue(AtomicRecentEmojiFileOps.writeAtomic(file, "🚀"))
        assertEquals("🚀", AtomicRecentEmojiFileOps.read(file))
        assertFalse(temp.exists())
    }

    @Test
    fun aFailedWriteAnswersFalseAndPreservesThePreviousMedium() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        // A non-empty directory in the temp's place fails the write and even the cleanup delete.
        val temp = temporaryFolder.newFolder("recent_emoji_v1.tmp")
        File(temp, "stranded").writeText("x")

        assertFalse(AtomicRecentEmojiFileOps.writeAtomic(file, "🚀"))
        assertEquals("😀", AtomicRecentEmojiFileOps.read(file))
    }

    @Test
    fun stagesRunInOrderTempSyncThenRenameThenDirectorySync() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        val stages = ArrayList<String>()
        val writer = AtomicRecentEmojiWriter(
            writeAndSyncTemp = { temp, bytes ->
                stages += "write+sync"
                temp.writeBytes(bytes)
            },
            rename = { temp, destination ->
                stages += "rename"
                temp.renameTo(destination)
            },
            syncDirectory = { stages += "dirsync" },
        )

        assertTrue(writer.write(file, "😀"))
        assertEquals(listOf("write+sync", "rename", "dirsync"), stages)
        assertEquals("😀", file.readText())
    }

    @Test
    fun aWriteStageFailureKeepsThePreviousMediumAndCleansTheTemp() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        var renameCalled = false
        val writer = AtomicRecentEmojiWriter(
            writeAndSyncTemp = { temp, _ ->
                temp.writeText("partial") // the stream died mid-write
                throw IOException("write failed")
            },
            rename = { _, _ ->
                renameCalled = true
                false
            },
        )

        assertFalse(writer.write(file, "🚀"))
        assertFalse(renameCalled) // the destination is never touched before the temp is durable
        assertEquals("😀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun aTempSyncFailureKeepsThePreviousMediumAndCleansTheTemp() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        var renameCalled = false
        val writer = AtomicRecentEmojiWriter(
            writeAndSyncTemp = { temp, bytes ->
                temp.writeBytes(bytes) // all bytes written, then the fsync failed
                throw IOException("temp fsync failed")
            },
            rename = { _, _ ->
                renameCalled = true
                false
            },
        )

        assertFalse(writer.write(file, "🚀"))
        assertFalse(renameCalled)
        assertEquals("😀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun aFailedRenameKeepsThePreviousMediumCleansTheTempAndSkipsTheDirectorySync() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        var renameReached = false
        var directorySyncCalled = false
        val writer = AtomicRecentEmojiWriter(
            rename = { _, _ ->
                renameReached = true
                false
            },
            syncDirectory = { directorySyncCalled = true },
        )

        assertFalse(writer.write(file, "🚀"))
        assertTrue(renameReached) // the real temp write and fsync ran before this stage
        assertFalse(directorySyncCalled)
        // The destination was never deleted: the previous medium survives the failed rename.
        assertEquals("😀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun aDirectorySyncFailureIsNotDurableAlthoughTheNewContentIsInstalled() {
        val file = temporaryFolder.newFile("recent_emoji_v1")
        file.writeText("😀")
        var directorySyncCalled = false
        val writer = AtomicRecentEmojiWriter(
            syncDirectory = {
                directorySyncCalled = true
                throw IOException("directory fsync failed")
            },
        )

        assertFalse(writer.write(file, "🚀"))
        assertTrue(directorySyncCalled)
        // The rename already replaced the medium: only durability is unproven on this branch, so
        // the previous content is NOT promised here — the caller must keep the change dirty.
        assertEquals("🚀", file.readText())

        // The retry with a working directory sync lands the same content durably.
        assertTrue(AtomicRecentEmojiWriter().write(file, "🚀"))
        assertEquals("🚀", file.readText())
        assertFalse(File(temporaryFolder.root, "recent_emoji_v1.tmp").exists())
    }

    @Test
    fun aParentThatCannotBeCreatedAnswersFalse() {
        val blocker = temporaryFolder.newFile("not_a_directory")
        val file = File(blocker, "recent_emoji_v1")
        assertFalse(AtomicRecentEmojiFileOps.writeAtomic(file, "😀"))
        assertNull(AtomicRecentEmojiFileOps.read(file))
    }

    @Test
    fun removeTempAnswersTrueOnlyWhenNoTempRemains() {
        val file = File(temporaryFolder.root, "recent_emoji_v1")
        assertTrue(AtomicRecentEmojiFileOps.removeTemp(file)) // nothing stranded

        val temp = File(temporaryFolder.root, "recent_emoji_v1.tmp")
        temp.writeText("stranded")
        assertTrue(AtomicRecentEmojiFileOps.removeTemp(file))
        assertFalse(temp.exists())

        val tempDir = temporaryFolder.newFolder("recent_emoji_v1.tmp")
        File(tempDir, "stranded").writeText("x")
        assertFalse(AtomicRecentEmojiFileOps.removeTemp(file)) // a non-empty directory remains
    }
}
