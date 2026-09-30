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
 * Source contract (like EmojiPanelSourceContractTest): greps the source rather than exercising
 * Android, guarding the fling and recent-emoji code — a single reusable OverScroller, a
 * VelocityTracker obtained once per gesture and recycled on UP/CANCEL, allocation-free hot bodies,
 * the fling physics taken from EmojiFling, the credential-protected recents file, the
 * device-protected context confined to its known seams, and a settings erase path that never reads
 * the recents content.
 */
class EmojiRecentAndFlingSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val panel by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
    }
    private val controller by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelController.kt")
    }
    private val settings by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

    private fun onDrawBody() =
        panel.substringAfter("override fun onDraw").substringBefore("@Suppress(\"ClickableViewAccessibility\")")

    private fun onTouchBody() =
        panel.substringAfter("override fun onTouchEvent").substringBefore("override fun onVisibilityChanged")

    // --- Fling: one reusable OverScroller, one VelocityTracker capture, recycle on UP/CANCEL ----

    @Test
    fun exactlyOneOverScrollerIsConstructedForTheWholeView() {
        assertEquals(1, countOccurrences(panel, "OverScroller("))
    }

    @Test
    fun theVelocityTrackerIsObtainedAtMostOncePerGesture() {
        // A single obtain() call site, guarded so a gesture never grabs a second tracker.
        assertEquals(1, countOccurrences(panel, "VelocityTracker.obtain("))
        assertTrue(panel.contains("if (velocityTracker == null)"))
    }

    @Test
    fun theVelocityTrackerIsRecycledOnActionUpAndActionCancel() {
        val touchBody = onTouchBody()
        val upBlock = touchBody.substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_CANCEL ->")
        val cancelBlock = touchBody.substringAfter("MotionEvent.ACTION_CANCEL ->")
        assertTrue("ACTION_UP must recycle the tracker", upBlock.contains("recycleVelocityTracker()"))
        assertTrue("ACTION_CANCEL must recycle the tracker", cancelBlock.contains("recycleVelocityTracker()"))
        assertTrue(panel.contains("velocityTracker?.recycle()"))
    }

    @Test
    fun flingVelocityThresholdsComeFromViewConfiguration() {
        assertTrue(panel.contains("scaledMinimumFlingVelocity"))
        assertTrue(panel.contains("scaledMaximumFlingVelocity"))
    }

    @Test
    fun theFlingPhysicsAreTakenFromEmojiFling() {
        assertTrue(panel.contains("EmojiFling.shouldFling("))
        assertTrue(panel.contains("EmojiFling.clampScroll("))
    }

    // --- Allocation-free hot bodies; only the visible rows are drawn ---------------------------

    @Test
    fun onDrawAndTouchBodiesContainNoKnownAllocationSites() {
        // Kept in sync with SuggestionStripSourceContractTest / EmojiPanelSourceContractTest.
        val forbidden = listOf(
            "= Rect(",
            "= Paint(",
            "MotionEvent.obtain",
            ".toString()",
            "TextUtils.",
            "arrayOf(",
            "listOf(",
        )
        listOf(onDrawBody(), onTouchBody()).forEach { body ->
            forbidden.forEach { token ->
                assertFalse("hot path contains $token", body.contains(token))
            }
        }
    }

    @Test
    fun onDrawPaintsOnlyTheVisibleGridRows() {
        val drawBody = onDrawBody()
        // The content is walked section by section, and inside a section row by row, both bounded
        // by the visible range rather than by the whole entry count.
        assertTrue(drawBody.contains("state.firstVisibleSection()"))
        assertTrue(drawBody.contains("state.lastVisibleSection()"))
        assertTrue(drawBody.contains("state.firstVisibleRowOf(section)"))
        assertTrue(drawBody.contains("state.lastVisibleRowOf(section)"))
        assertTrue(drawBody.contains("section in firstSection..lastSection"))
        assertTrue(drawBody.contains("while (row <= lastRow)"))
    }

    // --- The recents medium is credential-protected, never device-protected --------------------

    @Test
    fun theRecentsMediumLivesInCredentialProtectedNoBackupFilesDir() {
        assertTrue(controller.contains("noBackupFilesDir"))
        assertFalse(controller.contains("createDeviceProtectedStorageContext"))
    }

    @Test
    fun deviceProtectedStorageContextIsCreatedInExactlyThreeSeamsAndNoneInTheEmojiPackage() {
        val javaRoot = File(sourceRoot(), "java")
        val seams = javaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("createDeviceProtectedStorageContext(") }
            .map { it.name }
            .toList()
        // AndroidBigramStorageFactory.kt is the same production-wiring pattern as
        // AndroidDictionaryStorageFactory.kt, pointed at its own device-protected subdirectory.
        assertEquals("device-protected context must live in exactly three seams: $seams", 3, seams.size)
        assertTrue(seams.contains("PreferenceManagerCompat.java"))
        assertTrue(seams.contains("AndroidDictionaryStorageFactory.kt"))
        assertTrue(seams.contains("AndroidBigramStorageFactory.kt"))

        val emojiDir = File(javaRoot, "rkr/simplekeyboard/inputmethod/latin/emoji")
        emojiDir.listFiles { file -> file.name.endsWith(".kt") }?.forEach { file ->
            assertFalse(
                "${file.name} must not create a device-protected context",
                file.readText().contains("createDeviceProtectedStorageContext"),
            )
        }
    }

    // --- The settings erase path clears but never reads the recents content --------------------

    @Test
    fun settingsHostActivityClearsRecentsThroughTheSingleStorageMethod() {
        assertTrue(settings.contains("EmojiPanelController.clearRecents("))
    }

    @Test
    fun settingsHostActivityNeverReadsTheRecentsContent() {
        for (forbidden in listOf(
            "currentRecents",
            "RecentEmojiStore",
            "RecentEmojiFileOps",
            "RecentEmojiList",
            "AtomicRecentEmojiFileOps",
            "noBackupFilesDir",
            "RECENT_EMOJI_FILE_NAME",
            "deserialize",
        )) {
            assertFalse("SettingsHostActivity reads recents via $forbidden", settings.contains(forbidden))
        }
    }

    @Test
    fun clearRecentsNamesTheDestructiveActionAndCancelsWithThePlatformString() {
        // The affirmative button names the action, because the action is destructive and
        // irreversible and "OK" would not say what is about to happen; only the dismissive button
        // is the platform string. android.R.string.ok is allowed only in one-button informational
        // dialogs.
        assertTrue(settings.contains("R.string.clear_recent_emoji"))
        assertTrue(settings.contains("R.string.clear_recent_emoji_confirm"))
        assertTrue(settings.contains("setPositiveButton(R.string.clear_recent_emoji_action)"))
        assertFalse(settings.contains("setPositiveButton(android.R.string.ok)"))
        assertTrue(settings.contains("setNegativeButton(android.R.string.cancel"))
    }

    @Test
    fun clearRecentsDialogGoesThroughCurrentDialogAndIsDismissedInOnDestroy() {
        val method = settings.substringAfter("private fun showClearRecentEmojiDialog() {")
            .substringBefore("\n    }")
        assertTrue(method.contains("currentDialog?.dismiss()"))
        assertTrue(method.contains("currentDialog = dialog"))
        val onDestroy = settings.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onSaveInstanceState")
        assertTrue(onDestroy.contains("currentDialog?.dismiss()"))
    }

    // --- The emoji package writes no log, stdout or network ------------------------------------

    @Test
    fun theEmojiPackageContainsNoLoggingOrStdoutOrNetwork() {
        val emojiDir = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/emoji")
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
