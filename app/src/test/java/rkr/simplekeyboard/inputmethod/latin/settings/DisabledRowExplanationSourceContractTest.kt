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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 of `docs/ROADMAP-P8-PLAN.md`, closing F15(a) of `docs/AUDIT-2026-09-24-FIXES.md`: a settings
 * row dimmed because the switch it depends on is off used to swallow the tap silently. It now
 * explains itself with a short Toast, while the switch inside it stays disabled so the tap can
 * never toggle anything, and TalkBack still calls the row disabled.
 *
 * `View`, `Toast` and resources do not exist on a plain JVM (no Robolectric here, by design), so
 * this is a source-level contract, the discipline of every other settings pin.
 */
class DisabledRowExplanationSourceContractTest {

    private fun source(relative: String): String {
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private fun rows() =
        source("src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsRows.kt")

    @Test
    fun aSoftDisabledRowKeepsItsTapTargetAndExplainsItself() {
        val text = rows()
        val setter = text.substringAfter("internal fun SettingsHostActivity.setRowEnabled(")
            .substringBefore("internal fun SettingsHostActivity.rowClick(")
        assertTrue(
            "a reason turns the row soft-disabled",
            setter.contains("val softDisabled = !enabled && reasonRes != 0"),
        )
        assertTrue(
            "which keeps it able to receive the tap",
            setter.contains("row.isEnabled = enabled || softDisabled"),
        )
        val click = text.substringAfter("internal fun SettingsHostActivity.rowClick(")
            .substringBefore("internal fun disabledReason(")
        assertTrue("the tap shows the reason", click.contains("Toast.makeText(this, reason"))
        assertTrue("and swallows the action", click.contains("} else {") && click.contains("action()"))
    }

    @Test
    fun theSwitchItselfStaysDisabled() {
        val setter = rows().substringAfter("internal fun SettingsHostActivity.setRowEnabled(")
            .substringBefore("internal fun SettingsHostActivity.rowClick(")
        assertTrue(
            "the switch follows `enabled`, not the soft-disabled relaxation",
            setter.contains("row.findViewById<Switch>(R.id.row_switch)?.isEnabled = enabled"),
        )
    }

    @Test
    fun talkBackStillCallsTheRowDisabled() {
        assertTrue(
            "the switch row's a11y node reports the soft-disabled state",
            rows().contains("if (rowIsSoftDisabled(host)) {"),
        )
    }

    @Test
    fun anAdminRestrictionGetsItsOwnWording() {
        assertTrue(
            rows().substringAfter("internal fun disabledReason(")
                .contains("if (restricted) R.string.row_locked_by_admin else dependencyReasonRes"),
        )
    }

    @Test
    fun everyRowTapGoesThroughTheGate() {
        val text = rows()
        // No row may install a raw click listener any more: the gate is the only way in.
        val rawListeners = Regex("row\\.setOnClickListener").findAll(text).count()
        assertTrue("found $rawListeners raw row click listeners, expected 0 outside rowClick",
            rawListeners == 1)
        assertTrue(
            "and the only one is inside rowClick itself",
            text.substringAfter("internal fun SettingsHostActivity.rowClick(")
                .contains("row.setOnClickListener"),
        )
    }

    @Test
    fun theDependentRowsOfThePreferencesScreenNameTheirSwitch() {
        val host = source(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        )
        for (row in listOf("personalRow", "incognitoSwitch", "autocorrectSwitch", "emojiSuggestSwitch")) {
            assertTrue(
                "$row must pass the suggestions reason",
                host.substringAfter("setRowEnabled($row,").substringBefore("))")
                    .contains("R.string.row_needs_suggestions"),
            )
        }
        assertTrue(
            "the IME-switch row names the globe key",
            host.substringAfter("setRowEnabled(imeRow,").substringBefore("))")
                .contains("R.string.row_needs_language_switch"),
        )
    }

    @Test
    fun theExplanationsExistInAllThreeLocales() {
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = source("src/main/res/$folder/strings.xml")
            for (name in listOf(
                "row_needs_suggestions", "row_needs_language_switch", "row_locked_by_admin",
            )) {
                assertTrue("$folder is missing $name", strings.contains("name=\"$name\""))
            }
        }
    }
}
