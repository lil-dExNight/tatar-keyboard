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

import android.test.InstrumentationTestCase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Real Android storage, production fsync stages, and deterministic write failures. */
class RecentEmojiWriteFailureDeviceTest : InstrumentationTestCase() {

    private lateinit var directory: File
    private lateinit var file: File

    override fun setUp() {
        super.setUp()
        directory = File(
            instrumentation.targetContext.noBackupFilesDir,
            "recent-emoji-device-test-${System.nanoTime()}",
        )
        file = File(directory, "recent_emoji_v1")
    }

    override fun tearDown() {
        directory.deleteRecursively()
        super.tearDown()
    }

    fun testDefaultWriterFsyncsFileAndDirectoryAndReplacesMedium() {
        assertTrue(AtomicRecentEmojiWriter().write(file, "😀"))
        assertEquals("😀", file.readText())
        assertFalse(temp().exists())

        assertTrue(AtomicRecentEmojiFileOps.writeAtomic(file, "🚀"))
        assertEquals("🚀", AtomicRecentEmojiFileOps.read(file))
        assertFalse(temp().exists())
    }

    fun testWriteAndRenameFailuresPreservePreviousMediumAndRetry() {
        assertTrue(AtomicRecentEmojiWriter().write(file, "😀"))
        var failWrite = true
        var failRename = false
        val fileOps = object : RecentEmojiFileOps by AtomicRecentEmojiFileOps {
            override fun writeAtomic(destination: File, content: String): Boolean =
                AtomicRecentEmojiWriter(
                    writeAndSyncTemp = { temp, bytes ->
                        if (failWrite) {
                            temp.writeBytes(bytes.copyOf(bytes.size / 2))
                            throw IOException("injected write failure")
                        }
                        FileOutputStream(temp).use { output ->
                            output.write(bytes)
                            output.flush()
                            output.fd.sync()
                        }
                    },
                    rename = { temp, target ->
                        if (failRename) false else temp.renameTo(target)
                    },
                ).write(destination, content)
        }
        val store = RecentEmojiStore(RecentEmojiFileProvider { file }, fileOps, openGate())
        store.recordUse("🚀", setOf("😀", "🚀"))

        store.flushOnHide()
        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)
        assertEquals("😀", file.readText())
        assertFalse(temp().exists())

        failWrite = false
        failRename = true
        store.flushOnHide()
        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)
        assertEquals("😀", file.readText())
        assertFalse(temp().exists())

        failRename = false
        store.flushOnHide()
        assertFalse(store.isDirty())
        assertEquals(1, store.saveCount)
        assertEquals("🚀${RecentEmojiList.SEPARATOR}😀", file.readText())
        assertFalse(temp().exists())
    }

    fun testDirectorySyncFailureStaysDirtyAndSuccessfulRetryIsIdempotent() {
        assertTrue(AtomicRecentEmojiWriter().write(file, "😀"))
        var failDirectorySync = true
        var directorySyncCalls = 0
        val fileOps = object : RecentEmojiFileOps by AtomicRecentEmojiFileOps {
            override fun writeAtomic(destination: File, content: String): Boolean =
                AtomicRecentEmojiWriter(syncDirectory = {
                    directorySyncCalls++
                    if (failDirectorySync) throw IOException("injected directory fsync failure")
                }).write(destination, content)
        }
        val store = RecentEmojiStore(RecentEmojiFileProvider { file }, fileOps, openGate())
        store.recordUse("🚀", setOf("😀", "🚀"))

        store.flushOnHide()
        assertEquals(1, directorySyncCalls)
        assertTrue(store.isDirty())
        assertEquals(0, store.saveCount)
        assertEquals("🚀${RecentEmojiList.SEPARATOR}😀", file.readText())

        failDirectorySync = false
        store.flushOnHide()
        assertEquals(2, directorySyncCalls)
        assertFalse(store.isDirty())
        assertEquals(1, store.saveCount)
        assertEquals("🚀${RecentEmojiList.SEPARATOR}😀", file.readText())
        assertFalse(temp().exists())
    }

    private fun openGate() = RecentEmojiGate {
        RecentEmojiGateState(
            shouldShowSuggestions = true,
            userUnlocked = true,
            noPersonalizedLearning = false,
            keyguardLocked = false,
            incognito = false,
        )
    }

    private fun temp() = File(directory, file.name + ".tmp")
}
