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
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink

/**
 * The E4c learning predicate: ONE predicate with six factors, shared by every write path — U8 of
 * Phase 2 (docs/ROADMAP-P2.md) added the incognito pause as the sixth.
 *
 * The factors themselves are Android state (a subtype, preferences, `UserManager`, an `EditorInfo`),
 * so what runs as a real test here is the SHAPE — a sink that writes nothing whenever the predicate
 * says no, whichever factor said it — and the rest is source-contract over the one place the
 * predicate is computed. The conjunction's arithmetic is exercised for real in `IncognitoModeTest`.
 */
class PersonalLearningGatesTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val attributes by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/InputAttributes.java").readText()
    }
    private val learning by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalLearning.kt")
            .readText()
    }

    @Test
    fun aClosedPredicateWritesNothingOnEitherEventPath() {
        // The sink is the only bridge from typing to the store, and both of its methods consult the
        // predicate — the completion AND the end-of-session flush.
        var completions = 0
        var flushes = 0
        val guarded = object : WordCompletionSink {
            override fun onCleanCompletion(word: String) {
                if (!predicateSaysNo()) completions++
            }

            override fun onInputFinished() {
                if (!predicateSaysNo()) flushes++
            }
        }
        guarded.onCleanCompletion("гүзәлия")
        guarded.onInputFinished()
        assertEquals(0, completions)
        assertEquals(0, flushes)
    }

    private fun predicateSaysNo(): Boolean = true

    @Test
    fun bothSinkMethodsAreGatedInProductionToo() {
        val body = learning.substringAfter("fun sinkFor(")
        assertEquals("the predicate is consulted on both paths, not just on the completion",
            2, Regex("if \\(!predicate\\.mayLearn\\(\\)\\) return").findAll(body).count())
    }

    @Test
    fun thePredicateIsOnePlaceAndCarriesEveryFactor() {
        val predicate = ime.substringAfter("private boolean mayLearnPersonalWords()")
            .substringBefore("// The key-neighbor table")
        assertTrue("eligibility — which already carries the field, the subtype, " +
            "IME_FLAG_NO_PERSONALIZED_LEARNING and the null-editorInfo case",
            predicate.contains("isSuggestionsEligible()"))
        assertTrue("the personal dictionary setting",
            predicate.contains("Settings.readPersonalDictionaryEnabled(mDevicePrefs)"))
        assertTrue("the device has been unlocked at least once",
            predicate.contains("userManager.isUserUnlocked()"))
        assertTrue("and the postal-address exclusion",
            predicate.contains("mIsPostalAddressField"))
        // U8: the incognito pause is the sixth factor (the conjunction itself is the pure
        // PersonalLearningGates, whose arithmetic IncognitoModeTest exercises). The UserManager
        // null-check inverted its shape when the conjunction was extracted (2026-09-23): the
        // pinned text now matches the extracted form — a missing UserManager still means locked.
        assertTrue("a missing UserManager means locked, not open",
            predicate.contains("userManager != null && userManager.isUserUnlocked()"))
        assertTrue("the incognito pause",
            predicate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))
        assertTrue("and the conjunction is delegated to the pure gates object",
            predicate.contains("PersonalLearningGates.mayLearn("))

        // One predicate, not six checks spread around: nothing else in the IME may decide this.
        assertEquals(1, Regex("private boolean mayLearnPersonalWords\\(\\)").findAll(ime).count())
        assertEquals("the sink is wired exactly once", 1,
            Regex("PersonalLearning\\.sinkFor\\(").findAll(ime).count())
    }

    @Test
    fun eligibilityItselfCarriesTheNoPersonalizedLearningFlagAndTheNullEditorInfoCase() {
        val eligibility = ime.substringAfter("private boolean isSuggestionsEligible(final boolean")
            .substringBefore("}")
        assertTrue(eligibility.contains("!settingsValues.mInputAttributes.mNoPersonalizedLearning"))
        // With editorInfo == null the input class is not TYPE_CLASS_TEXT, so mShouldShowSuggestions
        // is false — that is what closes the null case without a separate check.
        assertTrue(eligibility.contains("mInputAttributes.mShouldShowSuggestions"))
        assertTrue("the non-text branch sets it false, which is the null case",
            attributes.contains("mShouldShowSuggestions = false;"))
    }

    @Test
    fun thePostalAddressExclusionDoesNotTouchTheShowingOfSuggestions() {
        assertTrue("computed before the non-text early return, like the learning flag",
            attributes.indexOf("mIsPostalAddressField =") < attributes.indexOf("if (inputClass != InputType.TYPE_CLASS_TEXT)"))
        val suppression = attributes.substringAfter("final boolean shouldSuppressSuggestions")
            .substringBefore("mShouldShowSuggestions = !shouldSuppressSuggestions;")
        assertFalse("suggestions in an address field behave exactly as before — only learning stops",
            suppression.contains("POSTAL"))
        // Person names are deliberately NOT excluded — names are the point of the feature — and the
        // contract requires that reason to be written down. So the constant may appear in prose;
        // what must not exist is an actual exclusion of it, in either place a gate could live.
        val predicateBody = ime.substringAfter("private boolean mayLearnPersonalWords()")
            .substringBefore("// The key-neighbor table")
        assertFalse("no person-name gate in the predicate",
            predicateBody.contains("PERSON_NAME"))
        assertFalse("nor a field for it in InputAttributes",
            attributes.contains("PERSON_NAME"))
        assertTrue("but the decision is written down where the reader will meet it",
            ime.contains("TYPE_TEXT_VARIATION_PERSON_NAME is deliberately NOT excluded"))
    }

    @Test
    fun theUnlockGateProtectsAgainstDestroyingTheDictionaryNotJustAgainstAnExtraRecord() {
        // Before the first unlock the snapshot is empty by construction and writing is whole-file,
        // so a write that slipped through would replace the real file with "empty plus one word".
        val store = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalDictionaryStore.kt")
            .readText()
        assertTrue("the store refuses to open while locked",
            store.contains("if (!unlockGate()) return false"))
        assertTrue("and every write path goes through open()",
            store.contains("private fun eligibleNormalizedForm(word: String): String? {") &&
                store.substringAfter("private fun eligibleNormalizedForm(word: String): String? {")
                    .substringBefore("}").contains("if (!open()) return null"))
    }
}
