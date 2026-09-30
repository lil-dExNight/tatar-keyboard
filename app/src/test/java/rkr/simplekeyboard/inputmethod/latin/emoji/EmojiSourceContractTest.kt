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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract (like SuggestionStripSourceContractTest): greps the source rather than
 * exercising Android, guarding how emoji input is wired into the IME.
 */
class EmojiSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun backspaceBody(): String {
        val inputLogic = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        ).readText()
        return inputLogic.substringAfter("private void handleBackspaceEvent")
            .substringBefore("private void handleLanguageSwitchKey")
    }

    @Test
    fun selectionBranchIsUnchanged() {
        val body = backspaceBody()
        assertTrue(body.contains("if (mConnection.hasSelection()) {"))
        assertTrue(body.contains("mConnection.deleteSelectedText();"))
    }

    @Test
    fun doubleSpaceToPeriodRevertBranchIsUnchanged() {
        val body = backspaceBody()
        assertTrue(
            body.contains("mJustDoubleSpaced")
                && body.contains("== Constants.CODE_SPACE")
                && body.contains("== Constants.CODE_PERIOD"),
        )
        assertTrue(body.contains("mConnection.deleteTextBeforeCursor(2);"))
        assertTrue(body.contains("mConnection.commitText(\"  \", 1);"))
        // The revert still returns immediately, before the emoji path can run.
        assertTrue(
            body.indexOf("mConnection.commitText(\"  \", 1);")
                in 0 until body.indexOf("EmojiTextUtils.trailingEmojiClusterLength"),
        )
    }

    @Test
    fun emojiDeletionReadsOnlyTheCachedBeforeCursorText() {
        val body = backspaceBody()
        assertTrue(body.contains("EmojiTextUtils.trailingEmojiClusterLength("))
        assertTrue(body.contains("mConnection.getCachedTextBeforeCursor()"))
    }

    @Test
    fun emojiDeletionPathMakesExactlyOneDeleteOfTheClusterLength() {
        val body = backspaceBody()
        val thenBlock = body.substringAfter("if (emojiClusterLength > 0) {")
            .substringBefore("} else {")
        assertTrue(thenBlock.contains("mConnection.deleteTextBeforeCursor(emojiClusterLength);"))
        assertEquals(
            1,
            "deleteTextBeforeCursor\\(".toRegex().findAll(thenBlock).count(),
        )
    }

    @Test
    fun codePointFallbackPathIsPreserved() {
        val body = backspaceBody()
        // The NOT_A_CODE / hardware-key path and the supplementary-aware code-point delete stay.
        assertTrue(body.contains("codePointBeforeCursor == Constants.NOT_A_CODE"))
        assertTrue(body.contains("sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)"))
        assertTrue(body.contains("Character.isSupplementaryCodePoint(codePointBeforeCursor)"))
    }

    @Test
    fun emojiPackageContainsNoLoggingOrStdoutOrNetwork() {
        val emojiDir = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/emoji",
        )
        val kotlinFiles = emojiDir.listFiles { file -> file.name.endsWith(".kt") }
            ?: error("no emoji package sources found at $emojiDir")
        assertTrue(kotlinFiles.isNotEmpty())
        for (file in kotlinFiles) {
            val source = file.readText()
            for (forbidden in listOf("Log.", "println", "System.out", "java.net.")) {
                assertFalse("${file.name} contains $forbidden", source.contains(forbidden))
            }
        }
    }
}
