/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearningGates

/**
 * Pause learning (incognito): ONE switch, default OFF, that pauses ALL
 * learning while it is on.
 *
 * The semantics, as pinned here:
 *
 * 1. While ON, nothing new is learned. No write reaches the personal words store, the personal
 *    bigrams store, the refused-corrections store, or the pending counters — the pending hashes are
 *    written only from the completion event, and every sink gates its event (and the acceptance
 *    bump, and the end-of-session flush) on the ONE predicate the incognito factor vetoes.
 * 2. What is already saved keeps surfacing. The READ side never consults the pause: the gates
 *    the engines are constructed with read the personal-dictionary setting and nothing else. Hiding
 *    learned words would be a different feature; turning the personal dictionary off does that.
 * 3. Turning OFF resumes learning; nothing is retro-learned. While the pause was on, no
 *    observation reached any counter, so there is nothing to make up afterwards.
 *
 * The conjunction itself is the pure [PersonalLearningGates.mayLearn], exercised for real below;
 * the wiring (the key, the predicate, both sinks, the read side, the row, the note) is
 * source-contract in the established style, because `LatinIME` and the Activity cannot run
 * off-device. Every source predicate is proved fail-capable against a broken shape.
 */
class IncognitoModeTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val settingsSource by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java").readText()
    }
    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }
    private val host by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }
    private val screenController by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/PersonalDictionaryScreenController.kt")
            .readText()
    }

    // --- The pure gate: real tests --------------------------------------------------------------

    @Test
    fun incognitoVetoesLearningWhateverTheOtherFactorsSay() {
        // The full truth table, so the veto is proven rather than sampled: with the pause ON no
        // combination of the other five inputs permits a write; with it OFF the decision is exactly
        // the conjunction of the rest.
        for (eligible in listOf(false, true)) {
            for (personalOn in listOf(false, true)) {
                for (unlocked in listOf(false, true)) {
                    for (postal in listOf(false, true)) {
                        val withoutPause = eligible && personalOn && unlocked && !postal
                        assertEquals(withoutPause,
                            PersonalLearningGates.mayLearn(eligible, personalOn, unlocked, postal, false))
                        assertFalse("incognito must veto ($eligible, $personalOn, $unlocked, $postal)",
                            PersonalLearningGates.mayLearn(eligible, personalOn, unlocked, postal, true))
                    }
                }
            }
        }
    }

    @Test
    fun turningThePauseOffResumesLearningWithNothingToMakeUp() {
        val learnable = { incognito: Boolean ->
            PersonalLearningGates.mayLearn(
                suggestionsEligible = true,
                personalDictionaryOn = true,
                userUnlocked = true,
                postalAddressField = false,
                incognito = incognito,
            )
        }
        assertFalse("paused", learnable(true))
        assertTrue("""resumed — and the paused period left no trace anywhere, because no
            |observation reached a counter while the veto held""".trimMargin(), learnable(false))
    }

    @Test
    fun theIncognitoFactorSitsInsideTheOnePredicate() {
        // There is ONE place that computes "may we learn", and the pause is computed there — not
        // sprinkled over the sinks, the stores or the controller, where a second check could drift.
        val predicate = ime.substringAfter("private boolean mayLearnPersonalWords()")
            .substringBefore("private void updateKeyNeighbors()")
        assertTrue("the predicate delegates the conjunction to the pure gates object",
            predicate.contains("PersonalLearningGates.mayLearn("))
        assertTrue("and hands it the live incognito read",
            predicate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))

        val broken = predicate.replace("PersonalLearningGates.mayLearn(", "PersonalLearningGates.missing(")
        assertFalse("fail-capable: a renamed seam must not satisfy the check",
            broken.contains("PersonalLearningGates.mayLearn("))
    }

    @Test
    fun everyLearningSinkIsGatedByTheSameIncognitoCarryingPredicate() {
        // The words sink, the pairs sink, the refused-corrections sink and the sentence-initial
        // sink are wired with the very same predicate instance, so the pause covers their stores —
        // and the pending counters, whose only writer is the completion event the sinks gate.
        val wordWiring = ime.substringAfter("PersonalLearning.sinkFor(").substringBefore(");")
        val pairWiring = ime.substringAfter("PersonalBigramLearning.sinkFor(").substringBefore(");")
        val refusalWiring = ime.substringAfter("RefusedCorrectionLearning.sinkFor(").substringBefore(");")
        val sentenceStartWiring =
            ime.substringAfter("SentStartOverlayLearning.sinkFor(").substringBefore(");")
        for ((name, wiring) in listOf("words" to wordWiring, "pairs" to pairWiring,
                "refusals" to refusalWiring, "sentence starts" to sentenceStartWiring)) {
            assertTrue("the $name sink consults the ONE predicate",
                wiring.contains("this::mayLearnPersonalWords"))
        }
    }

    // --- The read side never consults the pause --------------------------------------------------

    @Test
    fun whatIsAlreadySavedKeepsSurfacingWhileThePauseIsOn() {
        // The gates the engines are constructed with read the personal-dictionary setting and
        // nothing else: incognito is a pause on WRITES, never a hide on reads.
        val readGates = Regex("\\(\\) -> (Settings\\.\\w+\\(mDevicePrefs\\))")
            .findAll(ime).map { it.groupValues[1] }.toList()
        assertEquals("all five personal sources are gated: words, pairs, emoji, refused corrections and the sentence-initial overlay",
            5, readGates.size)
        for (gate in readGates) {
            assertEquals("the read gate is the personal-dictionary setting",
                "Settings.readPersonalDictionaryEnabled(mDevicePrefs)", gate)
        }
        assertFalse("the read side must not consult the pause anywhere",
            readGates.any { it.contains("Incognito") })

        val hiddenReads = readGates.map { it + " && !Settings.readIncognitoModeEnabled(mDevicePrefs)" }
        assertTrue("fail-capable: a hiding read gate must be caught",
            hiddenReads.all { it.contains("readIncognitoModeEnabled") })
    }

    // --- The setting itself ------------------------------------------------------------------------

    @Test
    fun thereIsOneIncognitoKeyAndItDefaultsToOff() {
        assertTrue(settingsSource.contains("PREF_INCOGNITO_MODE = \"pref_incognito_mode\""))
        assertTrue("default OFF — the pause is the user's act, never the default",
            settingsSource.contains("readBooleanTolerant(prefs, PREF_INCOGNITO_MODE, false)"))
        assertFalse(settingsSource.contains("readBooleanTolerant(prefs, PREF_INCOGNITO_MODE, true)"))
        assertTrue("and it is read live, per event, like the personal-dictionary setting",
            settingsSource.contains("public static boolean readIncognitoModeEnabled"))
    }

    @Test
    fun theSwitchSitsBesideThePersonalDictionaryRowAndFollowsTheSuggestionsSwitch() {
        assertTrue("the row exists on the Preferences screen",
            host.contains("switchRow(Settings.PREF_INCOGNITO_MODE, false,"))
        val callback = host.substringAfter("incognitoRow?.let {").substringBefore("autocorrectRow?.let {")
        assertTrue("it is greyed while suggestions are off — the learning it pauses lives there",
            callback.contains("checked && !isRestricted(Settings.PREF_INCOGNITO_MODE)"))
        assertTrue("and its live disabled state keeps the explanation",
            callback.contains("disabledReason(isRestricted(Settings.PREF_INCOGNITO_MODE)") &&
                callback.contains("R.string.row_needs_suggestions"))
        val prefs = host.substringAfter("private fun buildPreferencesScreen()")
            .substringBefore("private fun buildPersonalDictionaryScreen()")
        val personalIndex = prefs.indexOf("Settings.PREF_PERSONAL_DICTIONARY, false,")
        val incognitoIndex = prefs.indexOf("Settings.PREF_INCOGNITO_MODE, false,")
        assertTrue("incognito comes right after the personal dictionary row",
            personalIndex in 1 until incognitoIndex)
    }

    @Test
    fun thePersonalDictionaryScreenSaysLearningIsPaused() {
        val screen = host.substringAfter("private fun buildPersonalDictionaryScreen()")
            .substringBefore("private fun usageRow(")
        assertTrue("the note is shown exactly when the pause is on",
            screen.contains("if (Settings.readIncognitoModeEnabled(prefs)) {"))
        assertTrue(screen.contains("R.string.personal_dictionary_learning_paused"))
    }

    @Test
    fun savedWordsManualAddIsDisabledAndItsDialogCannotOpenWhilePaused() {
        val screen = host.substringAfter("private fun buildPersonalDictionaryScreen()")
            .substringBefore("private fun usageRow(")
        val enabled = screen.substringAfter("setRowEnabled(addRow,").substringBefore("))")
        assertTrue("the add row follows the live pause as well as the dictionary switch",
            enabled.contains("&& !Settings.readIncognitoModeEnabled(prefs)"))

        val dialog = host.substringAfter("private fun showAddPersonalWordDialog(")
            .substringBefore("private fun showForgetPersonalWordDialog(")
        val openingGate = dialog.indexOf("if (Settings.readIncognitoModeEnabled(prefs))")
        val editor = dialog.indexOf("layoutInflater.inflate")
        val explanation = dialog.indexOf("R.string.personal_dictionary_learning_paused")
        assertTrue("a stale click is refused before an editor is created",
            openingGate in 0 until editor)
        assertTrue("the refusal explains the pause", explanation in openingGate until editor)
    }

    @Test
    fun savedWordsConfirmationRechecksPauseBeforeTouchingTheStore() {
        assertTrue("the production controller reads the device preference for each add",
            screenController.contains("Settings.readIncognitoModeEnabled(") &&
                screenController.contains(
                    "PreferenceManagerCompat.getDeviceSharedPreferences(context)"))
        val add = screenController.substringAfter("fun addWord(").substringBefore("\n    /**")
        val freshPause = add.indexOf("if (learningPaused()) return false")
        val store = add.indexOf("PersonalDictionaries.storeFor")
        assertTrue("a dialog that predates the pause cannot queue a personal-store write",
            freshPause in 0 until store)

        val rejection = host.substringAfter("private fun showAddPersonalWordDialog(")
            .substringAfter("if (!accepted) {")
            .substringBefore("showScreen(Screen.PERSONAL_DICTIONARY)")
        assertTrue("the confirmation refusal is not misreported as an alphabet rejection",
            rejection.contains("R.string.personal_dictionary_learning_paused"))
        // Unpaused, eligible input still takes the existing filtered store path and callback.
        assertTrue(add.contains("PersonalWordFilter.acceptedNormalizedForm(trimmed, alphabet)"))
        assertTrue(add.contains(".addManually(trimmed) { saved -> uiPoster { onSaved(saved) } }"))
    }

    @Test
    fun theSummaryNamesBothHalvesOfTheContract() {
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        val summary = english.substringAfter("<string name=\"incognito_mode_summary\">")
            .substringBefore("</string>")
        assertTrue("the pause half", summary.contains("Nothing new is remembered"))
        val title = english.substringAfter("<string name=\"incognito_mode\">")
            .substringBefore("</string>")
        assertEquals("the row is named by what it does", "Pause learning", title)
    }
}
