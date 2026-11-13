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
 * While the keyguard is shown (a quick reply or a PIN field on the lock screen, after the first
 * unlock) the keyboard shows nothing learned and learns nothing: no suggestion strip, no glide
 * typing, no recent emoji, no writes to the personal stores. An unknown keyguard state counts as
 * locked.
 *
 * Asserted by source: the checks live in [LatinIME], which the JVM tests cannot instantiate.
 */
class LockScreenPrivacySourceContractTest {

    private val mainRoot by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }
    private val ime by lazy {
        File(mainRoot, "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val manifest by lazy { File(mainRoot, "AndroidManifest.xml").readText() }

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(signature: String): String {
        val start = ime.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val open = ime.indexOf('{', start)
        var depth = 0
        for (index in open until ime.length) {
            when (ime[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return ime.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun anUnknownKeyguardStateCountsAsLocked() {
        val check = body("private boolean isKeyguardLocked()")
        assertTrue(check.contains("getSystemService(KeyguardManager.class)"))
        assertTrue(check.contains("keyguardManager == null || keyguardManager.isKeyguardLocked()"))
    }

    @Test
    fun theStripAndGlideAreOffWhileTheKeyguardIsLocked() {
        // The predicates read the session's snapshot, refreshed at field start and window show;
        // the snapshot starts locked, so an unevaluated session stays closed.
        assertTrue(body("private boolean isSuggestionsEligible(final boolean")
            .contains("!mSessionKeyguardLocked"))
        assertTrue(body("private boolean isGlideEligible()").contains("!mSessionKeyguardLocked"))
        assertTrue(ime.contains("private boolean mSessionKeyguardLocked = true;"))
        assertTrue(body("void onStartInputViewInternal(final EditorInfo")
            .contains("mSessionKeyguardLocked = isPrivacyLocked()"))
        assertTrue(body("public void onWindowShown()")
            .contains("mSessionKeyguardLocked = locked"))
        // Learning combines the field predicate with live keyguard and Direct Boot checks.
        val learning = body("private boolean mayLearnPersonalWords()")
        assertTrue(learning.contains("isSuggestionsEligible()"))
        assertTrue(learning.contains("!mLearningKeyguardScope.isLocked(mLiveKeyguardRead)"))
        assertTrue(learning.contains("isUserUnlocked()"))
        // The keyguard read behind it is the live system call, never the cached privacy gate.
        assertTrue(ime.contains("private final BooleanSupplier mLiveKeyguardRead = this::isKeyguardLocked;"))
    }

    @Test
    fun learningKeepsOneKeyguardReadOnlyWithinOneInputEvent() {
        // Each dispatch that can reach a learning sink opens the scope and closes it in finally,
        // so a kept answer never outlives the event that read it.
        for (signature in listOf(
            "public void onEvent(final Event event)",
            "public void onTextInput(final String rawText)",
        )) {
            val dispatch = body(signature)
            val open = dispatch.indexOf("mLearningKeyguardScope.open();\n        try {")
            val close = dispatch.indexOf("} finally {\n            mLearningKeyguardScope.close();")
            assertTrue(signature, open >= 0 && close > open)
        }
        val tap = ime.substringAfter("strip.setOnSuggestionClickListener((cellId, suggestion) -> {")
            .substringBefore("});")
        val tapOpen = tap.indexOf("mLearningKeyguardScope.open();")
        val tapCall = tap.indexOf("listener.onTap(suggestion);")
        val tapClose = tap.indexOf("mLearningKeyguardScope.close();")
        assertTrue(tapOpen in 0 until tapCall && tapCall < tapClose && tap.contains("finally"))
        // Nothing else opens a scope: engine callbacks, the session-end flush and emoji panel
        // picks read live.
        assertEquals(3, ime.split("mLearningKeyguardScope.open();").size - 1)
    }

    @Test
    fun theRecentEmojiGateReadsTheKeyguardAndPauseLearning() {
        val gate = ime.substringAfter("final RecentEmojiGate recentGate = () -> {")
            .substringBefore("};")
        assertTrue(gate.contains("isKeyguardLocked()"))
        assertTrue(gate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))
    }

    @Test
    fun theSuggestionsOfferIsClosedOnTheLockScreen() {
        // The offer's "Enable" writes the suggestions setting, which the lock screen must not do.
        val offerGate = body("public boolean isKeyguardLocked()")
        assertTrue(offerGate.contains("return mSessionKeyguardLocked || isPrivacyLocked();"))
    }

    @Test
    fun manualAddFromTheStripChecksFreshLockStateAtOpenAndConfirmation() {
        val policy = body("private int manualPersonalWordAddBlockMessage()")
        val freshCheck = policy.indexOf("if (noteFreshPrivacyLock()) {")
        val fieldRead = policy.indexOf("mSettings.getCurrent()")
        assertTrue("the fresh lock veto runs before field and pause checks",
            freshCheck in 0 until fieldRead)
        assertTrue(policy.substring(freshCheck).startsWith(
            "if (noteFreshPrivacyLock()) {\n" +
                "            return R.string.personal_dictionary_add_device_locked;"))

        val router = body("private void onStripWordLongPress(final String shownWord)")
        val addRoute = router.substringAfter("case ADD:").substringBefore("default:")
        val openingCheck = addRoute.indexOf("manualPersonalWordAddBlockMessage()")
        val opening = addRoute.indexOf("showAddPersonalWordDialog(")
        assertTrue("the dialog cannot open before its fresh check",
            openingCheck in 0 until opening)

        val dialog = body("private void showAddPersonalWordDialog(final String subtypeId")
        val confirmation = dialog.substringAfter(
            ".setPositiveButton(R.string.personal_dictionary_add_action")
        val confirmationCheck = confirmation.indexOf("manualPersonalWordAddBlockMessage()")
        val storeWrite = confirmation.indexOf("PersonalAdd.confirmAdd(")
        assertTrue("confirmation re-reads the lock before the personal-store write",
            confirmationCheck in 0 until storeWrite)
        val guardedWrite = confirmation.substring(confirmationCheck, storeWrite)
        assertTrue("a locked confirmation leaves before the write",
            guardedWrite.contains("if (blockedMessage != 0)") &&
                guardedWrite.contains("showPersonalWordAddBlockedDialog(blockedMessage)") &&
                guardedWrite.contains("return;"))

        for (values in listOf("values", "values-ru", "values-tt")) {
            val strings = File(mainRoot, "res/$values/strings.xml").readText()
            assertTrue("$values lacks the locked-add explanation",
                strings.contains("name=\"personal_dictionary_add_device_locked\""))
        }
    }

    @Test
    fun theFreshLockBoundarySkipsTheCacheAndAppliesALockedSession() {
        val fresh = body("private boolean noteFreshPrivacyLock()")
        assertTrue(fresh.contains(
            "mPrivacyLockValid = false;\n        return noteLiveLock();"))
        // Invalidation selects the synchronous read in isPrivacyLocked(), not its cached answer.
        assertTrue(body("private boolean isPrivacyLocked()").contains(
            "if (!mPrivacyLockValid || age >= 2 * PRIVACY_LOCK_CACHE_MS)"))
        assertTrue(body("private boolean readPrivacyLock()")
            .contains("isKeyguardLocked() || !isUserUnlocked()"))
        val applied = body("private boolean noteLiveLock()")
        assertTrue(applied.contains("mSessionKeyguardLocked = true;"))
        assertTrue(applied.contains("clearInlineAutofill();"))
        assertTrue(applied.contains("onSessionLockStateChanged();"))
    }

    @Test
    fun thePasteKeyReadsNoClipboardOnTheLockScreen() {
        val before = body("public boolean onBeforeClipboardPaste()")
        assertTrue(before.trimStart('{').trimStart()
            .startsWith("if (noteFreshPrivacyLock()) {\n            return false;"))
        assertTrue("the fresh gate runs before the clip offer is cleared",
            before.indexOf("noteFreshPrivacyLock()") <
                before.indexOf("mSuggestionsController.onClipboardPaste()"))
        val inputLogic = File(mainRoot,
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java").readText()
        assertTrue(inputLogic.contains(
            "if (mLatinIME.onBeforeClipboardPaste()) {\n                    mConnection.pasteClipboard();"))
        assertTrue(inputLogic.contains(
            "if (mLatinIME.onBeforeClipboardPaste()) {\n" +
                "                    mConnection.performContextMenuAction(android.R.id.paste);"))
        // Every context-menu paste sits behind the gate.
        assertEquals(1, inputLogic.split("performContextMenuAction(android.R.id.paste)").size - 1)
        assertFalse(inputLogic.contains("mLatinIME.onBeforeClipboardPaste();"))
    }

    @Test
    fun theControllerGetsALivePrivacyGate() {
        // The controller re-reads keyguard and Direct Boot state at transform and write boundaries.
        assertTrue(ime.contains("mSuggestionsController.setKeyguardGate(this::isPrivacyLocked);"))
        assertTrue(body("private boolean readPrivacyLock()")
            .contains("isKeyguardLocked() || !isUserUnlocked()"))
        assertTrue(body("private boolean isUserUnlocked()")
            .contains("userManager != null && userManager.isUserUnlocked()"))
    }

    @Test
    fun lockTransitionsAreBroadcastDriven() {
        // Screen and unlock broadcasts reach only a receiver registered at runtime.
        val create = body("public void onCreate()")
        for (action in listOf("SCREEN_OFF", "SCREEN_ON", "USER_PRESENT", "USER_UNLOCKED")) {
            assertTrue(action, create.contains("lockFilter.addAction(Intent.ACTION_$action);"))
            assertFalse(action, manifest.contains("android.intent.action.$action"))
        }
        assertTrue(create.contains("registerReceiver(mLockStateReceiver, lockFilter);"))
        assertTrue(
            ime.substringAfter("mLockStateReceiver = new BroadcastReceiver() {")
                .substringBefore("};").contains("onSystemLockEvent();"),
        )
        assertTrue(
            body("public void onDestroy()").contains("unregisterReceiver(mLockStateReceiver);"),
        )
        // A broadcast drops the reused answer before it re-applies the lock.
        val event = body("private void onSystemLockEvent()")
        val invalidated = event.indexOf("mPrivacyLockValid = false;")
        assertTrue(invalidated in 0 until event.indexOf("noteLiveLock();"))
        // An invalidated answer is read on the calling thread; an old one is refreshed on a worker
        // whose result is dropped after a newer forced read. Deep sleep counts toward the age.
        val gate = body("private boolean isPrivacyLocked()")
        assertTrue(
            gate.contains(
                "if (!mPrivacyLockValid || age >= 2 * PRIVACY_LOCK_CACHE_MS) {\n" +
                    "            mPrivacyLocked = readPrivacyLock();",
            ),
        )
        assertTrue(
            gate.contains(
                "age >= PRIVACY_LOCK_CACHE_MS && !mPrivacyLockRefreshing && !mPrivacyLockClosed",
            ),
        )
        assertTrue(gate.contains("SystemClock.elapsedRealtime()"))
        val refresh = body("private void refreshPrivacyLockOffThread()")
        assertTrue(
            refresh.contains("if (mPrivacyLockClosed || generation != mPrivacyLockGeneration) {"),
        )
        assertTrue(refresh.indexOf("if (locked) {") > refresh.indexOf("mPrivacyLocked = locked;"))
        assertTrue(refresh.contains("noteLiveLock();"))
        // After onDestroy no worker read starts: the executor is shut down there.
        val destroy = body("public void onDestroy()")
        assertTrue(destroy.trimStart('{').trimStart().startsWith("mPrivacyLockClosed = true;"))
        assertTrue(destroy.contains("mLockReadExecutor.shutdownNow();"))
        assertTrue(
            ime.contains("mPrivacyLockValid = false;\n        mSessionKeyguardLocked = isPrivacyLocked();"),
        )
        assertTrue(
            body("public void onWindowShown()")
                .contains("mPrivacyLockValid = false;\n        final boolean locked = isPrivacyLocked();"),
        )
    }
}
