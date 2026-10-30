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
        assertTrue(learning.contains("!isKeyguardLocked()"))
        assertTrue(learning.contains("isUserUnlocked()"))
    }

    @Test
    fun theRecentEmojiGateReadsTheKeyguardAndPauseLearning() {
        val gate = ime.substringAfter("final RecentEmojiGate recentGate = () -> {")
            .substringBefore("};")
        assertTrue(gate.contains("isKeyguardLocked()"))
        assertTrue(gate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))
    }

    @Test
    fun theControllerGetsALivePrivacyGate() {
        // The controller re-reads keyguard and Direct Boot state at transform and write boundaries.
        assertTrue(ime.contains("mSuggestionsController.setKeyguardGate(this::isPrivacyLocked);"))
        assertTrue(body("private boolean isPrivacyLocked()")
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
        assertTrue(
            body("private boolean isPrivacyLocked()")
                .contains("!mPrivacyLockValid || now - mPrivacyLockCheckedAt >= PRIVACY_LOCK_CACHE_MS"),
        )
        // Deep sleep counts toward the reuse window, and session starts read live.
        assertTrue(body("private boolean isPrivacyLocked()").contains("SystemClock.elapsedRealtime()"))
        assertTrue(
            ime.contains("mPrivacyLockValid = false;\n        mSessionKeyguardLocked = isPrivacyLocked();"),
        )
        assertTrue(
            body("public void onWindowShown()")
                .contains("mPrivacyLockValid = false;\n        final boolean locked = isPrivacyLocked();"),
        )
    }
}
