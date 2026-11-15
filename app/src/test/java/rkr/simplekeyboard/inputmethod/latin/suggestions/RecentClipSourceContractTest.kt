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
        assertTrue("onWindowShown posts the registration past the first frame",
            methodBody(latinIme, "public void onWindowShown()")
                .contains("mHandler.post(mRegisterRecentClipListener)"))
        assertTrue("onWindowHidden unregisters it",
            methodBody(latinIme, "public void onWindowHidden()")
                .contains("unregisterRecentClipListener()"))
        assertTrue("onDestroy unregisters it too",
            methodBody(latinIme, "public void onDestroy()")
                .contains("unregisterRecentClipListener()"))
        assertTrue("a hide before the post runs cancels the registration",
            methodBody(latinIme, "public void onWindowHidden()")
                .contains("removeCallbacks(mRegisterRecentClipListener)"))
        assertTrue("a destroy before the post runs cancels it too",
            methodBody(latinIme, "public void onDestroy()")
                .contains("removeCallbacks(mRegisterRecentClipListener)"))
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
        assertTrue(controller.contains("private var recentClip = RecentClipCell(recentClipScheduler)"))
        assertTrue(controller.contains(
            "RecentClipExpiryScheduler { delayMillis, task -> uiHandler.postDelayed(task, delayMillis) }"))
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
    fun theClipboardIsNeverReadWhileLocked() {
        // Both the show-time read and the change listener go through this method, so one gate
        // before the clipboard read covers every lock-screen path.
        val latinIme = latinImeSource()
        val capture = methodBody(latinIme, "private void capturePrimaryClipForOffer()")
        val gate = capture.indexOf("if (mSessionKeyguardLocked || noteFreshPrivacyLock()) {")
        val read = capture.indexOf("mClipboardManager.getPrimaryClip()")
        assertTrue("the fresh lock gate runs before the clipboard read", gate in 0 until read)
        assertFalse("the up-to-two-second cached gate must not decide a clipboard read",
            capture.contains("isPrivacyLocked()"))
        assertTrue("a locked read clears the held offer",
            capture.substring(gate, read).contains("controller.onPrimaryClipChanged(null, 0L);"))
        val fresh = methodBody(latinIme, "private boolean noteFreshPrivacyLock()")
        val invalidated = fresh.indexOf("mPrivacyLockValid = false;")
        val apply = fresh.indexOf("noteLiveLock();")
        assertTrue("an eligible session forces a new read and applies its result",
            invalidated in 0 until apply)
        assertTrue("the change listener reads through the gated method",
            latinIme.substringAfter("public void onPrimaryClipChanged() {")
                .substringBefore("}").contains("capturePrimaryClipForOffer();"))
    }

    @Test
    fun theClipboardIsNeverReadInAnIneligibleField() {
        // The field-eligibility gate must run before any clipboard byte is read — a password,
        // numeric, NO_PERSONALIZED_LEARNING or locked-session field never shows the cell, so its
        // clipboard must not be touched at all.
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        val eligibility = capture.indexOf("if (!isSuggestionsEligible()) {")
        val read = capture.indexOf("mClipboardManager.getPrimaryClip()")
        assertTrue("the field-eligibility gate runs before the clipboard read",
            eligibility in 0 until read)
        // It also precedes the fresh keyguard read: eligibility is the cheapest, most protective
        // gate, so an ineligible field never even pays for a lock re-read.
        val freshLock = capture.indexOf("noteFreshPrivacyLock()")
        assertTrue("eligibility precedes the fresh keyguard read", eligibility in 0 until freshLock)
        assertTrue("an ineligible field clears the held offer",
            capture.substring(eligibility, read).contains("controller.onPrimaryClipChanged(null, 0L);"))
        // Both entry points (the show-time read and the change listener) go through this one
        // gated method, so the single check covers every recent-clip clipboard read.
        val register = methodBody(latinImeSource(), "private void registerRecentClipListener()")
        assertTrue("the show-time read goes through the gated capture",
            register.contains("capturePrimaryClipForOffer()"))
        assertTrue("the change listener goes through the gated capture",
            latinImeSource().substringAfter("public void onPrimaryClipChanged() {")
                .substringBefore("}").contains("capturePrimaryClipForOffer();"))
    }

    @Test
    fun aSensitiveClipClearsTheOfferBeforeItsTextIsRead() {
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        val extras = capture.indexOf("clip.getDescription().getExtras()")
        val gate = capture.indexOf("if (!RecentClipCell.mayOfferClip(")
        val textRead = capture.indexOf("clip.getItemAt(0).getText()")
        assertTrue("the sensitive mark is read from the clip description", extras >= 0)
        assertTrue("the gate runs before the clip text is read", gate in extras until textRead)
        assertTrue("the gate reads the extra with a false default",
            capture.contains("clipExtras.getBoolean(key, false)"))
        val afterGate = capture.substring(gate, textRead)
        assertTrue("a sensitive clip clears the held offer",
            afterGate.contains("controller.onPrimaryClipChanged(null, 0L);"))
    }

    @Test
    fun oversizedClipsAreRejectedBeforeMaterializationAndBinderCommit() {
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        val captureGuard = capture.indexOf(
            "if (!RichInputConnection.shouldCommitPasteDirectly(text))")
        val materialization = capture.indexOf("text.toString()")
        assertTrue("the clipboard CharSequence is sized before it becomes a String",
            captureGuard in 0 until materialization)

        val inputLogic = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java").readText()
        val commit = methodBody(inputLogic, "public boolean commitClipText(final String text)")
        val commitGuard = commit.indexOf(
            "if (!RichInputConnection.shouldCommitPasteDirectly(text))")
        val batch = commit.indexOf("mConnection.beginBatchEdit();")
        val binderCommit = commit.indexOf("mConnection.commitText(")
        assertTrue("an oversized clip cannot open a batch or reach commitText",
            commitGuard in 0 until batch && batch < binderCommit)
    }

    @Test
    fun aStaleClipClearsTheOfferBeforeItsTextIsRead() {
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        val timestamp = capture.indexOf("clip.getDescription().getTimestamp()")
        val gate = capture.indexOf("ageMillis > RecentClipCell.TTL_MILLIS")
        val textRead = capture.indexOf("clip.getItemAt(0).getText()")
        assertTrue("the timestamp is read from the clip description", timestamp >= 0)
        assertTrue("the age gate runs after the timestamp read", gate in timestamp until textRead)
        assertTrue("a timestamp the platform cannot prove still fails to no offer",
            capture.contains("if (timestamp <= 0L)"))
        assertTrue("a stale clip clears the held offer before the text is read",
            capture.substring(gate, textRead).contains("controller.onPrimaryClipChanged(null, 0L);"))
    }

    @Test
    fun theAgeGateUsesTheCellsFreshnessWindow() {
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        // The same window the cell applies: future-stamped or older than TTL clears, and a clip
        // exactly at the TTL boundary is still fresh.
        assertTrue(capture.contains("ageMillis < 0L || ageMillis > RecentClipCell.TTL_MILLIS"))
        assertFalse("a clip exactly at the TTL boundary must stay offerable",
            capture.contains("ageMillis >= RecentClipCell.TTL_MILLIS"))
    }

    @Test
    fun platformsWithoutClipTimestampsKeepTheFreshEventMomentFallback() {
        val capture = methodBody(latinImeSource(), "private void capturePrimaryClipForOffer()")
        val apiGuard = capture.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O")
        val timestamp = capture.indexOf("clip.getDescription().getTimestamp()")
        assertTrue("the timestamp read is API 26+ only", apiGuard in 0 until timestamp)
        assertTrue("pre-26 clips keep the change event's moment as their set-time",
            capture.contains("setAtMillis = System.currentTimeMillis();"))
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
