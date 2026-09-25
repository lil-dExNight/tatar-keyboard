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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural half of the 2026-09-25 input-robustness fix wave
 * (docs/SECURITY-AUDIT-2026-09-25-FIXES.md) for [RichInputConnection]: the paste fallback
 * wiring (F1), the dead-editor guards (F6), the batch pairing of deleteSelectedText (F5), and
 * the reload's threading contract (F8 — the apply runs on the UI thread against a re-verified
 * selection; F10 — coalescing, and the staleness check before the IPC).
 *
 * Asserted by source for the same reason as [CommitPathConnectionContractTest]: these paths
 * need a live `LatinIME`, a `ClipboardManager` and a real `Handler`, none of which exist in a
 * plain JVM test.
 */
class RichInputConnectionRobustnessContractTest {

    private val source by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java").readText()
    }

    private fun bodyOf(from: String, to: String) = source.substringAfter(from).substringBefore(to)

    private val pasteBody by lazy { bodyOf("public void pasteClipboard()", "public void sendKeyEvent(") }

    private val deleteSelectedBody by lazy {
        bodyOf("public void deleteSelectedText()", "public void performEditorAction(")
    }

    private val replaceTextBody by lazy {
        bodyOf("public void replaceText(", "public void deleteTextBeforeCursor(")
    }

    private val reloadBody by lazy {
        bodyOf("public void reloadTextCache() {", "private void finishReloadTextCache()")
    }

    // --- F1 ---------------------------------------------------------------------------------

    @Test
    fun thePasteThresholdIsPinnedAndGuardsTheDirectCommit() {
        assertTrue(
            "the threshold is 64 Ki chars (an order of magnitude under the binder's ~1 MB cap)",
            source.contains("MAX_DIRECT_PASTE_CHARS = 64 * 1024"),
        )
        val guard = pasteBody.indexOf("if (shouldCommitPasteDirectly(pasteData))")
        val directCommit = pasteBody.indexOf("mLatinIME.onTextInput(pasteData.toString());")
        val editorPaste = pasteBody.indexOf("mIC.performContextMenuAction(android.R.id.paste);")
        assertTrue("the threshold gates the direct commit", guard in 0 until directCommit)
        assertTrue(
            "at/above the threshold the editor pastes itself (no parcel copy)",
            directCommit in 0 until editorPaste,
        )
    }

    // --- F6 ---------------------------------------------------------------------------------

    @Test
    fun theDeadEditorGuardsSitBeforeAnyMutation() {
        // deleteSelectedText: the guard is the first thing inside the batch.
        val begin = deleteSelectedBody.indexOf("beginBatchEdit();")
        val guard = deleteSelectedBody.indexOf("if (!isConnected()) {")
        val mutation = deleteSelectedBody.indexOf("mTextSelection = \"\";")
        assertTrue("deleteSelectedText opens its batch", begin >= 0)
        assertTrue("deleteSelectedText checks the refreshed connection first", begin < guard)
        assertTrue("and mutates nothing before it", guard in 0 until mutation)

        // replaceText: the connection is refreshed BEFORE the cache is rewritten.
        val refresh = replaceTextBody.indexOf("mIC = mLatinIME.getCurrentInputConnection();")
        val replaceGuard = replaceTextBody.indexOf("if (!isConnected()) {")
        val cacheWrite = replaceTextBody.indexOf("mTextAfterCursor = text +")
        assertTrue("replaceText refreshes the connection", refresh >= 0)
        assertTrue("and checks it", refresh < replaceGuard)
        assertTrue("before the cache mutation", replaceGuard in 0 until cacheWrite)

        // pasteClipboard: the context-menu fallback checks the refreshed connection.
        val pasteRefresh = pasteBody.indexOf("mIC = mLatinIME.getCurrentInputConnection();")
        val pasteGuard = pasteBody.indexOf("if (!isConnected()) {")
        val contextMenu = pasteBody.indexOf("mIC.performContextMenuAction(android.R.id.paste);")
        assertTrue("pasteClipboard refreshes the connection", pasteRefresh >= 0)
        assertTrue("and guards the context-menu action", pasteRefresh < pasteGuard && pasteGuard < contextMenu)
    }

    // --- F5 ---------------------------------------------------------------------------------

    @Test
    fun deleteSelectedTextClosesItsBatchInFinally() {
        val begin = deleteSelectedBody.indexOf("beginBatchEdit();")
        val tryOpen = deleteSelectedBody.indexOf("try {")
        val finallyOpen = deleteSelectedBody.indexOf("} finally {")
        val end = deleteSelectedBody.indexOf("endBatchEdit();")
        assertTrue("the batch body is a try", begin in 0 until tryOpen)
        assertTrue("and closes in finally", tryOpen < finallyOpen && finallyOpen < end)
    }

    // --- F10 --------------------------------------------------------------------------------

    @Test
    fun theReloadCoalescesAndChecksStalenessBeforeTheIpc() {
        assertTrue(
            "a second request folds into the pending flag",
            source.contains("if (mReloadInFlight) {") &&
                source.contains("mReloadRequestedWhileInFlight = true;"),
        )
        val background = reloadBody.indexOf("mBackgroundThread.execute(")
        val preIpcCheck = reloadBody.indexOf("Selection range modified before the reload reached the editor.")
        val surroundingIpc = reloadBody.indexOf("mIC.getSurroundingText(")
        val beforeIpc = reloadBody.indexOf("mIC.getTextBeforeCursor(")
        assertTrue("the background task exists", background >= 0)
        assertTrue("the staleness check precedes the S+ IPC", background < preIpcCheck && preIpcCheck < surroundingIpc)
        assertTrue("and the pre-S IPC", preIpcCheck < beforeIpc)

        val finishBody = bodyOf("private void finishReloadTextCache()", "\n    public ")
        assertTrue("the completion clears the flag", finishBody.contains("mReloadInFlight = false;"))
        assertTrue(
            "and the folded request produces exactly one follow-up",
            finishBody.contains("reloadTextCache();"),
        )
    }

    // --- F8 ---------------------------------------------------------------------------------

    @Test
    fun theBackgroundSectionNeverWritesTheCache() {
        val writes = listOf(
            "onBeforeCursorCacheReloaded(",
            "setTextAroundCursor(",
            "mTextAfterCursor =",
            "mTextSelection =",
        )
        // Region one: from the background task's start to the first posted apply (the S+ branch).
        val background = reloadBody.substringAfter("mBackgroundThread.execute(() -> {")
        val firstPost = background.indexOf("mLatinIME.mHandler.post")
        val sPlusSection = background.substring(0, firstPost)
        for (write in writes) {
            assertFalse("the S+ background section must not write the cache: $write",
                sPlusSection.contains(write))
        }
        // Region two: the pre-S reader, from the S+ apply's close to the next posted apply.
        val sPlusClose = background.indexOf("});", firstPost)
        val preSApply = background.indexOf("mLatinIME.mHandler.post(() -> {", sPlusClose)
        val preSSection = background.substring(sPlusClose, preSApply)
        for (write in writes) {
            assertFalse("the pre-S reader must not write the cache: $write",
                preSSection.contains(write))
        }
    }

    @Test
    fun theApplyRunsOnTheUiThreadAgainstAReVerifiedSelection() {
        // Every apply block: a posted runnable whose FIRST act is the staleness re-check, with
        // the cache writes behind it and the completion at the end.
        val applies = Regex("mLatinIME\\.mHandler\\.post\\(\\(\\) -> \\{")
            .findAll(reloadBody).toList()
        assertEquals("the reload posts applies (S+, pre-S null, pre-S full)", 3, applies.size)
        for (apply in applies) {
            val block = reloadBody.substring(apply.range.first)
            val recheck = block.indexOf(
                "if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd)"
            )
            assertTrue("each apply re-verifies the selection on the UI thread", recheck > 0)
            assertTrue(
                "and every apply ends the reload (flag bookkeeping)",
                block.substring(0, block.indexOf("});")).contains("finishReloadTextCache();"),
            )
        }
        // The S+ apply: the re-check sits before the window write.
        val sPlusApply = reloadBody.substringAfter("mIC.getSurroundingText(")
        val recheck = sPlusApply.indexOf(
            "if (expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd)"
        )
        val write = sPlusApply.indexOf("setTextAroundCursor(textAroundCursor);")
        assertTrue("the S+ apply re-checks before writing", recheck in 0 until write)
    }

    @Test
    fun aReloadThatAppliesNothingStillClearsTheInFlightFlag() {
        assertTrue(
            "the finally posts the bare completion (stale/disconnected/dying-editor paths)",
            reloadBody.contains("mLatinIME.mHandler.post(this::finishReloadTextCache);"),
        )
        assertTrue(
            "and no exception is swallowed to get there",
            !Regex("\\bcatch\\s*\\(").containsMatchIn(reloadBody),
        )
    }
}
