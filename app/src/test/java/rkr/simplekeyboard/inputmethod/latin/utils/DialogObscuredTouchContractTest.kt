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

package rkr.simplekeyboard.inputmethod.latin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-02, C5: every dialog the app shows must drop touches delivered while another
 * window obscures it (`filterTouchesWhenObscured`). The dialogs are built from framework layouts
 * whose button panel cannot carry the XML flag, so [DialogUtils.filterObscuredTouches] sets it
 * programmatically on the decor view — the one ViewGroup every touch into the dialog passes
 * through.
 *
 * Dialogs cannot be instantiated without Android, so this pins the WIRING by source: the one
 * helper exists and does the right call, and every AlertDialog creation site in the app passes
 * through it — a new dialog that forgets it fails here.
 *
 * A sibling rule lives here too (audit 2026-09-25): the activity-wide FLAG_SECURE does not extend
 * to dialog windows, so the settings dialogs that render personal content set it on their own
 * window through [DialogUtils.securePersonalContent].
 */
class DialogObscuredTouchContractTest {

    @Test
    fun theHelperSetsTheFlagOnTheDecorView() {
        val body = javaBody(read(UTILS), "public static void filterObscuredTouches")
        assertTrue(
            "the obscured-touch filter belongs on the decor view",
            body.contains("getDecorView().setFilterTouchesWhenObscured(true)"),
        )
    }

    @Test
    fun everyImeAttachedDialogPassesThroughTheFilteringAttach() {
        val ime = read(LATIN_IME)
        assertTrue(
            "attachDialogToInputWindow must install the filter",
            javaBody(ime, "private void attachDialogToInputWindow")
                .contains("DialogUtils.filterObscuredTouches(dialog)"),
        )
        // Every dialog LatinIME shows is attached through it: 9 call sites plus the definition.
        // (9th site 2026-09-23: the P1 personal-bigrams unreadable dialog, docs/ROADMAP-P2.md.)
        assertEquals(10, ime.occurrencesOf("attachDialogToInputWindow("))
    }

    @Test
    fun theSubtypePickerFiltersObscuredTouches() {
        val imm = read(RICH_IMM)
        assertTrue(
            "showSubtypePicker must install the filter",
            javaBody(imm, "public AlertDialog showSubtypePicker")
                .contains("DialogUtils.filterObscuredTouches(dialog)"),
        )
    }

    @Test
    fun everySettingsDialogInstallsTheFilter() {
        val activity = read(SETTINGS_ACTIVITY)
        val dialogs = activity.occurrencesOf("AlertDialog.Builder(")
        val filters = activity.occurrencesOf("DialogUtils.filterObscuredTouches(")
        assertTrue("the settings screen builds dialogs", dialogs > 0)
        assertEquals(
            "every AlertDialog in SettingsHostActivity must install the obscured-touch filter",
            dialogs, filters,
        )
        assertEquals(1, read(SEEK_BAR_HELPER).occurrencesOf("DialogUtils.filterObscuredTouches("))
        // 2026-09-24 audit, finding 9: the languages screen builds its own dialog (the add-language
        // picker) — it is covered by the same rule, counted the same way.
        val languages = read(SETTINGS_LANGUAGES_SCREENS)
        assertEquals(
            "every AlertDialog in SettingsLanguagesScreens must install the obscured-touch filter",
            languages.occurrencesOf("AlertDialog.Builder("),
            languages.occurrencesOf("DialogUtils.filterObscuredTouches("),
        )
    }

    @Test
    fun personalContentDialogsAreSecureFromCapture() {
        // 2026-09-25 audit: FLAG_SECURE on the activity window does NOT cover dialog windows —
        // each dialog is a window of its own. The three dialogs that render personal content
        // (the word the forget-word dialog names, the pair the forget-pair dialog names, the
        // field the add-word dialog takes) secure their own window through the helper; the
        // generic confirmations (clear-all, erase, discard) show no personal content and stay
        // shareable.
        val helper = javaBody(read(UTILS), "public static void securePersonalContent")
        assertTrue(
            "the helper sets FLAG_SECURE on the dialog window",
            helper.contains("window.setFlags(WindowManager.LayoutParams.FLAG_SECURE"),
        )
        assertTrue(
            "the helper must not need show() to have happened — it composes with the show-listener"
                + " filter on the same dialog",
            helper.contains("dialog.getWindow()"),
        )
        val activity = read(SETTINGS_ACTIVITY)
        for (signature in listOf(
            "private fun showAddPersonalWordDialog(",
            "private fun showForgetPersonalWordDialog(",
            "private fun showForgetPersonalPairDialog(",
        )) {
            assertTrue(
                "$signature must secure its dialog's window",
                javaBody(activity, signature).contains("DialogUtils.securePersonalContent(dialog)"),
            )
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /** The body of the Java method whose signature starts with [signatureStart]. */
    private fun javaBody(source: String, signatureStart: String): String {
        val start = source.indexOf(signatureStart)
        assertTrue("method not found: $signatureStart", start >= 0)
        val openBrace = source.indexOf('{', start)
        var depth = 0
        var index = openBrace
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("unbalanced braces after $signatureStart")
    }

    private fun read(path: String): String {
        val candidates = listOf(path, "app/$path")
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        error("source not found: $path")
    }

    private companion object {
        const val UTILS = "src/main/java/rkr/simplekeyboard/inputmethod/latin/utils/DialogUtils.java"
        const val LATIN_IME = "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"
        const val RICH_IMM = "src/main/java/rkr/simplekeyboard/inputmethod/latin/RichInputMethodManager.java"
        const val SETTINGS_ACTIVITY =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt"
        const val SETTINGS_LANGUAGES_SCREENS =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsLanguagesScreens.kt"
        const val SEEK_BAR_HELPER =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SeekBarDialogHelper.kt"
    }
}
