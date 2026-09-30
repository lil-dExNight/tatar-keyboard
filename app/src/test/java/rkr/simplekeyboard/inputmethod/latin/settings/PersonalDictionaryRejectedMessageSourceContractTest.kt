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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-word rejection toast names the alphabet of the section the word was headed for. The
 * store a hand-added
 * word goes into is decided by the live subtype (`SettingsHostActivity.targetSubtypeForAddedWord`),
 * so the message must follow the same decision.
 *
 * The choice lives in an `Activity` and cannot run off-device, so it is pinned by source in the
 * style of `PersonalDictionaryFeedbackSourceContractTest`; the string resources themselves are
 * asserted for real in all three locales the app ships.
 */
class PersonalDictionaryRejectedMessageSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val host by lazy {
        File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt").readText()
    }

    private fun rejectionBranch(): String {
        val dialog = host.substringAfter("private fun showAddPersonalWordDialog(")
            .substringBefore("private fun showForgetPersonalWordDialog(")
        return dialog.substringAfter("if (!accepted) {")
            .substringBefore("showScreen(Screen.PERSONAL_DICTIONARY)")
    }

    @Test
    fun theRejectionMessageFollowsTheSameSubtypeTheWordWasMeantFor() {
        val branch = rejectionBranch()
        assertTrue("the Russian store gets the Russian message",
            branch.contains("PersonalSubtypes.RUSSIAN"))
        assertTrue(branch.contains("R.string.personal_dictionary_add_rejected_ru"))
        assertTrue("every other target keeps the Tatar message",
            branch.contains("R.string.personal_dictionary_add_rejected"))
    }

    @Test
    fun bothVariantsExistInAllThreeLocales() {
        for (values in listOf("values", "values-ru", "values-tt")) {
            val strings = File(sourceRoot(), "res/$values/strings.xml").readText()
            for (key in listOf("personal_dictionary_add_rejected",
                    "personal_dictionary_add_rejected_ru")) {
                assertTrue("$values is missing $key", strings.contains("\"$key\""))
            }
        }
    }

    /** Each variant names its own alphabet in the language of the locale itself. */
    @Test
    fun eachVariantNamesTheAlphabetItEnforces() {
        val expectations = mapOf(
            "values" to ("Tatar letters" to "Russian letters"),
            "values-ru" to ("татарских букв" to "русских букв"),
            "values-tt" to ("татар хәрефе" to "рус хәрефе"),
        )
        for ((values, pair) in expectations) {
            val strings = File(sourceRoot(), "res/$values/strings.xml").readText()
            val tatar = strings.substringAfter(
                "<string name=\"personal_dictionary_add_rejected\">").substringBefore("</string>")
            val russian = strings.substringAfter(
                "<string name=\"personal_dictionary_add_rejected_ru\">").substringBefore("</string>")
            assertTrue("$values tatar variant: $tatar", tatar.contains(pair.first))
            assertFalse("$values tatar variant must not name Russian: $tatar",
                tatar.contains(pair.second))
            assertTrue("$values russian variant: $russian", russian.contains(pair.second))
            assertFalse("$values russian variant must not name Tatar: $russian",
                russian.contains(pair.first))
        }
    }

    /** A single hardcoded Tatar message must fail the branch check. */
    @Test
    fun thePredicatesRejectTheShapeTheyReplaced() {
        val oldBranch = """
            Toast.makeText(this, R.string.personal_dictionary_add_rejected,
                    Toast.LENGTH_SHORT).show()
        """.trimIndent()
        assertFalse(oldBranch.contains("R.string.personal_dictionary_add_rejected_ru"))
        assertFalse(oldBranch.contains("PersonalSubtypes.RUSSIAN"))
    }
}
