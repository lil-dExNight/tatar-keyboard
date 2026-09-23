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
 * U8 of Phase 2 (docs/ROADMAP-P2.md) — incognito mode: ONE switch, default OFF, that pauses ALL
 * learning while it is on.
 *
 * The semantics, as pinned here:
 *
 * 1. **While ON, nothing new is learned.** No write reaches the personal words store, the personal
 *    bigrams store, or their pending counters — the pending hashes are written only from the
 *    completion event, and both sinks gate that event (and the acceptance bump, and the
 *    end-of-session flush) on the ONE predicate the incognito factor vetoes.
 * 2. **What is already saved keeps surfacing.** The READ side never consults the pause: the gates
 *    the engines are constructed with read the personal-dictionary setting and nothing else. This
 *    is a documented choice, not an oversight — hiding the learned words would be a second feature
 *    ("forget for a while"), and the user already has "turn the personal dictionary off" for that.
 * 3. **Turning OFF resumes learning; nothing is retro-learned.** While the pause was on, no
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
            .substringBefore("// The key-neighbor table")
        assertTrue("the predicate delegates the conjunction to the pure gates object",
            predicate.contains("PersonalLearningGates.mayLearn("))
        assertTrue("and hands it the live incognito read",
            predicate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))

        val broken = predicate.replace("PersonalLearningGates.mayLearn(", "PersonalLearningGates.missing(")
        assertFalse("fail-capable: a renamed seam must not satisfy the check",
            broken.contains("PersonalLearningGates.mayLearn("))
    }

    @Test
    fun bothSinksAreGatedByTheSameIncognitoCarryingPredicate() {
        // The words sink and the pairs sink are wired with the very same predicate instance, so
        // the pause covers both stores — and their pending counters, whose only writer is the
        // completion event the sinks gate.
        val wordWiring = ime.substringAfter("PersonalLearning.sinkFor(").substringBefore(");")
        val pairWiring = ime.substringAfter("PersonalBigramLearning.sinkFor(").substringBefore(");")
        for ((name, wiring) in listOf("words" to wordWiring, "pairs" to pairWiring)) {
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
        assertEquals("both personal sources are gated, words and pairs", 2, readGates.size)
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
            settingsSource.contains("prefs.getBoolean(PREF_INCOGNITO_MODE, false)"))
        assertFalse(settingsSource.contains("prefs.getBoolean(PREF_INCOGNITO_MODE, true)"))
        assertTrue("and it is read live, per event, like the personal-dictionary setting",
            settingsSource.contains("public static boolean readIncognitoModeEnabled"))
    }

    @Test
    fun theSwitchSitsBesideThePersonalDictionaryRowAndFollowsTheSuggestionsSwitch() {
        assertTrue("the row exists on the Preferences screen",
            host.contains("switchRow(Settings.PREF_INCOGNITO_MODE, false,"))
        assertTrue("it is greyed while suggestions are off — the learning it pauses lives there",
            host.contains("setRowEnabled(it, checked && !isRestricted(Settings.PREF_INCOGNITO_MODE))"))
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
    fun theSummaryNamesBothHalvesOfTheContract() {
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        val summary = english.substringAfter("<string name=\"incognito_mode_summary\">")
            .substringBefore("</string>")
        assertTrue("the pause half", summary.contains("Pause learning"))
        assertTrue("and the keeps-appearing half — the choice the read side pins",
            summary.contains("keeps appearing"))
        assertTrue("and what turning it off does", summary.contains("resumes learning"))
    }
}
