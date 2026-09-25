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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalDictionary

/**
 * E4d: "Forget «X»?" — who owns a shown word, and what a long press may and may not do.
 *
 * The lookup itself is exercised for real (it is pure Kotlin over the published snapshot); the touch
 * timer and the dialog are source-contract, since neither a View nor an InputMethodService runs off
 * device.
 */
class PersonalForgetTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val strip by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt").readText()
    }
    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }

    private fun snapshotOf(vararg raw: String): PersonalDictionary {
        val sorted = raw.sortedBy { it.lowercase() }
        return PersonalDictionary.of(
            ValidatedPersonalDictionary(
                rawForms = sorted,
                normalizedForms = sorted.map { it.lowercase() },
                usageCounts = IntArray(sorted.size) { 1 },
                lastUseSerials = LongArray(sorted.size) { (it + 1).toLong() },
                subtypeTag = "tt_RU",
            ),
        )
    }

    @Test
    fun ownershipIsFoundByTheNormalizedFormAtEveryCasingTheStripCanShow() {
        val snapshot = snapshotOf("Гүзәл")
        // The three forms the strip can display for the saved entry «Гүзәл», depending on how the
        // user typed the prefix: lower, Initial Caps and ALL CAPS. All three must find it.
        for (shown in listOf("гүзәл", "Гүзәл", "ГҮЗӘЛ")) {
            val index = snapshot.indexOfNormalized(PersonalWordFilter.normalize(shown))
            assertTrue("the saved word must be found for a shown '$shown'", index >= 0)
            assertEquals("Гүзәл", snapshot.rawFormAt(index))
        }
    }

    @Test
    fun anOrdinaryDictionaryWordIsNotOwnedAndTheGestureDoesNothing() {
        val snapshot = snapshotOf("Гүзәл")
        assertEquals(-1, snapshot.indexOfNormalized(PersonalWordFilter.normalize("китап")))
        assertTrue("the production path returns null for a word it does not own, and the caller " +
            "returns without showing anything",
            ime.contains("if (savedForm == null) {"))
    }

    @Test
    fun theSearchIsBinaryAndRunsInTheTimerBodyNotInOnTouchEvent() {
        val dictionary = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personal/PersonalDictionary.kt")
            .readText()
        assertTrue("binary search over the normalized forms",
            dictionary.contains("fun indexOfNormalized(normalized: String): Int") &&
                dictionary.substringAfter("fun indexOfNormalized(normalized: String): Int")
                    .substringBefore("}").contains("lowerBound(normalized)"))

        val touch = strip.substringAfter("override fun onTouchEvent(").substringBefore("override fun dispatchHoverEvent")
        assertFalse("no lookup on the touch path — zero allocations in hot touch code",
            touch.contains("suggestionAt("))
        val timer = strip.substringAfter("private fun fireLongPress()").substringBefore("private fun scheduleLongPress()")
        assertTrue("the cell is read in the deferred runnable instead", timer.contains("suggestionAt("))
    }

    @Test
    fun theTimerIsOnePreAllocatedRunnableAndTheLongPressCancelsTheTap() {
        assertTrue("allocated once, as a field",
            strip.contains("private val longPressRunnable = Runnable { fireLongPress() }"))
        assertTrue(strip.contains("postDelayed(longPressRunnable"))
        assertTrue(strip.contains("removeCallbacks(longPressRunnable)"))
        assertTrue("a fired long press cancels the tap of this touch sequence",
            strip.contains("if (!wasLongPress && cell != SuggestionStripState.NO_CELL) activateCell(cell)"))
    }

    @Test
    fun theLongClickActionIsExposedOnEveryFilledCellNotOnlyOnPersonalOnes() {
        val node = strip.substringAfter("val actionable = isVirtualCellActionable(virtualViewId)")
            .substringBefore("override fun onPerformActionForVirtualView")
        assertTrue("every actionable cell gets it",
            node.contains("node.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)"))
        // Checked on CODE only: the reason this action is on every cell is explained in a comment
        // right there, and prose mentioning "personal" must not fail its own rule.
        val code = node.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse("nothing may condition the action on the word being personal",
            code.contains("Personal") || code.contains("personal"))
    }

    @Test
    fun forgettingAWordAlsoDropsItsPendingProgress() {
        // Otherwise three more completions would silently bring back a word the user just removed.
        val store = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalDictionaryStore.kt")
            .readText()
        val forget = store.substringAfter("fun forget(word: String) = onWorker {")
            .substringBefore("fun clearAll()")
        assertTrue(forget.contains("pending = pending.without(key)"))
        assertTrue(forget.contains("pendingDirty = true"))
    }

    @Test
    fun theDialogNeverCommitsTextAndIsGatedBySetting() {
        val dialog = ime.substringAfter("private void showForgetPersonalWordDialog(")
            .substringBefore("/**\n     * Shows the one-shot message")
        assertTrue("gated by the personal-dictionary setting",
            dialog.contains("Settings.readPersonalDictionaryEnabled(mDevicePrefs)"))
        assertTrue("attached to the IME window like the subtype picker",
            dialog.contains("attachDialogToInputWindow(dialog, windowToken)"))
        assertFalse("a long press must never insert text",
            dialog.contains("commitText") || dialog.contains("commitSuggestion"))
        assertTrue("and erasure unbinds what the band shows",
            File(sourceRoot(),
                "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalForget.kt")
                .readText().contains("PersonalDictionaries.notifyErased()"))
    }

    @Test
    fun theSavedSpellingIsWhatTheDialogTitleShows() {
        assertTrue("the title takes the SAVED form, not the string on screen",
            ime.contains("getString(R.string.personal_dictionary_forget_title, savedForm)"))
        assertNull("and a word that is not owned produces no dialog at all",
            snapshotOf("Гүзәл").let {
                val index = it.indexOfNormalized("китап")
                if (index >= 0) it.rawFormAt(index) else null
            })
    }
}
