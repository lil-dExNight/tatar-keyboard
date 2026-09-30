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

    private val ime by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }

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
        assertTrue(body("private boolean isSuggestionsEligible(final boolean")
            .contains("!isKeyguardLocked()"))
        assertTrue(body("private boolean isGlideEligible()").contains("!isKeyguardLocked()"))
        // Learning goes through the strip's eligibility, so it inherits the check.
        assertTrue(body("private boolean mayLearnPersonalWords()").contains("isSuggestionsEligible()"))
    }

    @Test
    fun theRecentEmojiGateReadsTheKeyguardAndPauseLearning() {
        val gate = ime.substringAfter("final RecentEmojiGate recentGate = () -> {")
            .substringBefore("};")
        assertTrue(gate.contains("isKeyguardLocked()"))
        assertTrue(gate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))
    }
}
