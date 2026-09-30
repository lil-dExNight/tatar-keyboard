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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal dictionary setting: ONE toggle, default OFF, and an enterprise restriction that
 * applies in the RESTRICTIVE direction only.
 *
 * The two direction tests are real JVM tests, not greps: the decision they cover lives in
 * [PersonalDictionaryRestriction] precisely so it can be exercised off-device
 * (`Settings.loadRestrictions` needs a `RestrictionsManager` and real `SharedPreferences`). The
 * remaining assertions read the source, and each predicate is also checked against a
 * deliberately broken input.
 */
class PersonalDictionarySettingsTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val settingsSource by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java").readText()
    }
    private val hostSource by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }
    private val restrictionsXml by lazy {
        File(sourceRoot(), "res/xml/app_restrictions.xml").readText()
    }

    // --- The restriction, one test per direction -------------------------------------------

    @Test
    fun aRestrictivePolicyWritesFalseAndSilencesTheRow() {
        assertTrue("policy false must reach preferences",
            PersonalDictionaryRestriction.writesPreference(false))
        assertFalse("the written value is always false",
            PersonalDictionaryRestriction.valueToWrite())

        val keys = setOf(Settings.PREF_PERSONAL_DICTIONARY, Settings.PREF_AUTO_CAP)
        val active = PersonalDictionaryRestriction.effectiveRestrictionKeys(keys, false)
        assertTrue("a restrictive policy keeps the key active, so the row is greyed out",
            active.contains(Settings.PREF_PERSONAL_DICTIONARY))
        assertEquals("no other key is affected", keys, active)
    }

    @Test
    fun aPermissivePolicyWritesNothingAndLeavesTheRowToTheUser() {
        assertFalse("policy true must NOT be written: an administrator cannot force saving on",
            PersonalDictionaryRestriction.writesPreference(true))

        val keys = setOf(Settings.PREF_PERSONAL_DICTIONARY, Settings.PREF_AUTO_CAP)
        val active = PersonalDictionaryRestriction.effectiveRestrictionKeys(keys, true)
        assertFalse("a permissive policy must not grey the row out",
            active.contains(Settings.PREF_PERSONAL_DICTIONARY))
        assertTrue("every other restriction is untouched", active.contains(Settings.PREF_AUTO_CAP))
        assertEquals(1, active.size)
    }

    @Test
    fun anAbsentPolicyIsNotReadAsFalse() {
        val keys = setOf(Settings.PREF_AUTO_CAP)
        assertEquals("no personal-dictionary policy in the bundle changes nothing",
            keys, PersonalDictionaryRestriction.effectiveRestrictionKeys(keys, null))
    }

    @Test
    fun theRestrictionIsHandledByItsOwnBranchNotTheGenericBooleanList() {
        // The generic branch writes the policy value in EITHER direction. If the key were added to
        // that case list, a policy could force saving typed words on — the whole point of the
        // separate branch. Guard: the key must not appear in the run of fall-through `case` labels
        // that share the generic putBoolean.
        val genericBlock = settingsSource.substringAfter("case PREF_AUTO_CAP:")
            .substringBefore("prefsEditor.putBoolean(key, appRestrictions.getBoolean(key));")
        assertFalse("PREF_PERSONAL_DICTIONARY must not share the two-directional boolean branch",
            genericBlock.contains("case PREF_PERSONAL_DICTIONARY:"))
        assertTrue("it must have a branch of its own",
            settingsSource.contains("case PREF_PERSONAL_DICTIONARY:"))
        assertTrue("and that branch must go through the one-directional decision",
            settingsSource.contains("PersonalDictionaryRestriction.writesPreference("))

        // Fail-capable: the same predicate on a source where the key IS in the generic run.
        val broken = genericBlock + "case PREF_PERSONAL_DICTIONARY:"
        assertTrue(broken.contains("case PREF_PERSONAL_DICTIONARY:"))
    }

    @Test
    fun theKeyIsDeclaredAsARestrictionOrItWouldBeDeadInXml() {
        // Without the XML entry the policy key cannot be set at all; without the switch branch the
        // XML key is dead (default: "Unhandled restriction"). Both halves are required.
        assertTrue(restrictionsXml.contains("android:key=\"pref_personal_dictionary\""))
        assertFalse("fail-capable", restrictionsXml.contains("android:key=\"pref_no_such_key\""))
    }

    // --- One toggle, default OFF, and off does not erase ------------------------------------

    @Test
    fun thereIsExactlyOneToggleAndItDefaultsToOff() {
        assertTrue("the single key",
            settingsSource.contains("PREF_PERSONAL_DICTIONARY = \"pref_personal_dictionary\""))
        assertTrue("default OFF, like Tatar suggestions",
            settingsSource.contains("prefs.getBoolean(PREF_PERSONAL_DICTIONARY, false)"))
        assertFalse("default must not be true", settingsSource
            .contains("prefs.getBoolean(PREF_PERSONAL_DICTIONARY, true)"))

        // No second "remember typed words" switch anywhere: one toggle governs read AND write.
        val forbidden = listOf("pref_remember_typed_words", "pref_learn_words",
            "PREF_REMEMBER_WORDS", "PREF_PERSONAL_LEARNING")
        forbidden.forEach { key ->
            assertFalse("a second toggle would create a fourth, meaningless state: $key",
                settingsSource.contains(key) || hostSource.contains(key))
        }
    }

    @Test
    fun theSettingsRowFollowsTheSuggestionsSwitchAndIsGreyedOnlyByARestriction() {
        assertTrue("the row exists on the Preferences screen",
            hostSource.contains("switchRow(Settings.PREF_PERSONAL_DICTIONARY, false,"))
        assertTrue("it is disabled while Tatar suggestions are off", hostSource.contains(
            "Settings.readTatarSuggestionsEnabled(prefs)\n" +
                "                        && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY)"))
        assertTrue("and it follows the switch live", hostSource.contains(
            "setRowEnabled(it, checked && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY))"))
    }

    @Test
    fun turningTheToggleOffErasesNothingAndTheTextSaysSo() {
        // The contract: switching off keeps what is already saved; erasing lives on the screen.
        // Nothing in the settings code may call an erase path from the toggle.
        val toggleNeighbourhood = hostSource
            .substringAfter("switchRow(Settings.PREF_PERSONAL_DICTIONARY, false,")
            .take(400)
        listOf("clearAll", "erase", "delete").forEach { verb ->
            assertFalse("the toggle must not erase anything: $verb",
                toggleNeighbourhood.contains(verb, ignoreCase = true))
        }
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        val summary = english.substringAfter("<string name=\"personal_dictionary_summary\">")
            .substringBefore("</string>")
        assertTrue("the summary must say the words stay on this device",
            summary.contains("this device"))
        assertTrue("and that turning it off keeps what is already saved",
            summary.contains("keeps what is already saved"))
    }
}
