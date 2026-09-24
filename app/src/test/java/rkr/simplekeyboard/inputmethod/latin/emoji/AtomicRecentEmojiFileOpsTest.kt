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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recents medium's read side (2026-09-24 audit, finding 11): a legitimately tiny file is
 * read, an oversized one is refused on its LENGTH — before any byte is decoded — and the
 * absent/unreadable shapes still answer null. The write side is untouched by the cap.
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
}
