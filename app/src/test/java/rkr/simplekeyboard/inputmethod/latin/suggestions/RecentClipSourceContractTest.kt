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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The recent-clip cell's privacy contracts, in code shape: the clipboard listener is registered
 * only while the input view is shown, the clip text is RAM-only (never a file, never preferences,
 * never logged), and the offer is gated on the strip's own eligibility (which already excludes
 * password, numeric, incognito and lock-screen fields) plus a live keyguard re-read — the session
 * snapshot can predate the screen locking.
 */
class RecentClipSourceContractTest {

    @Test
    fun theListenerIsRegisteredOnlyWhileTheInputViewIsShown() {
        val latinIme = latinImeSource()
        assertTrue("onWindowShown registers the listener",
            methodBody(latinIme, "public void onWindowShown()")
                .contains("registerRecentClipListener()"))
        assertTrue("onWindowHidden unregisters it",
            methodBody(latinIme, "public void onWindowHidden()")
                .contains("unregisterRecentClipListener()"))
        assertTrue("onDestroy unregisters it too",
            methodBody(latinIme, "public void onDestroy()")
                .contains("unregisterRecentClipListener()"))
        assertTrue(methodBody(latinIme, "private void registerRecentClipListener()")
            .contains("addPrimaryClipChangedListener"))
        val unregister = methodBody(latinIme, "private void unregisterRecentClipListener()")
        assertTrue(unregister.contains("removePrimaryClipChangedListener"))
        assertTrue("hiding the window also drops the held clip",
            unregister.contains("onInputViewHidden()"))
    }

    @Test
    fun theClipPathNeverLogsAndNeverCoercesContent() {
        val latinIme = latinImeSource()
        // The listener plus every method it can reach: none may log (the clip is the user's text)
        // or coerce a clip item into text (a URI would resolve into content the user never copied).
        val region = latinIme.substringAfter("private void registerRecentClipListener()")
            .substringBefore("public void onWindowShown()")
        assertFalse(region.contains("Log."))
        assertFalse(region.contains("coerceToText"))
        // The read is the plain-text mime gate plus the first item's own text.
        assertTrue(region.contains("MIMETYPE_TEXT_PLAIN"))
    }

    @Test
    fun theClipCellIsRamOnly() {
        val cell = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/RecentClipCell.kt").readText()
        for (forbidden in listOf(
            "java.io", "File(", "FileOutput", "SharedPreferences", "openFileOutput",
            "writeBytes", "readBytes", "OutputStream", "android.util.Log", "println(",
        )) {
            assertFalse("RecentClipCell must not touch '$forbidden'", cell.contains(forbidden))
        }
        // The controller's clip half rides the same in-memory holder: no second clip store may
        // appear. The one field is the cell.
        val controller = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt").readText()
        assertTrue(controller.contains("private var recentClip = RecentClipCell()"))
        assertFalse(controller.contains("clipDirectory"))
    }

    @Test
    fun theOfferIsGatedOnStripEligibilityAndTheTapRechecksFreshness() {
        val controller = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt").readText()
        val offer = controller.substringAfter("private fun maybeShowRecentClip(): Boolean {")
            .substringBefore("    }")
        // The strip's eligibility is a session-start snapshot that can predate the screen locking,
        // so the offer re-reads the keyguard state live, and the tap re-reads it before committing.
        assertTrue(offer.contains("if (destroyed || !eligible || keyguardGate.isLocked()) return false"))
        val tap = controller.substringAfter("private fun onTap(suggestion: String) {")
        assertTrue(tap.contains("keyguardGate.isLocked()"))
        // The tap re-checks the clip's freshness before committing: a stale cell is a dead cell.
        assertTrue(tap.contains("recentClip.fullTextForCommit()"))
    }

    @Test
    fun theStripEligibilityAlreadyExcludesTheKeyguardAndIncognitoFields() {
        // The clip offer hangs off the strip's eligibility, so these checks are the matrix's
        // keyguard and incognito legs: pinned here so the chain offer -> eligible -> field gates
        // cannot silently break on either side.
        val latinIme = latinImeSource()
        val eligibility = latinIme.substringAfter("private boolean isSuggestionsEligible(final boolean")
            .substringBefore("private boolean isGlideEligible()")
        assertTrue(eligibility.contains("mSessionKeyguardLocked"))
        assertTrue(eligibility.contains("mNoPersonalizedLearning"))
        assertTrue(eligibility.contains("mShouldShowSuggestions"))
    }

    private fun latinImeSource(): String = File(sourceRoot(),
        "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    /** The body of the method whose declaration starts with [signature], braces balanced. */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("method not found: $signature", start >= 0)
        var index = source.indexOf('{', start)
        assertTrue("method body not found: $signature", index >= 0)
        var depth = 0
        val open = index
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces after $signature")
    }
}
