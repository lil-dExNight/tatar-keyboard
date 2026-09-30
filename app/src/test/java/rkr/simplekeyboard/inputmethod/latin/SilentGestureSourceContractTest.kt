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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One class of defect: the user makes a gesture, nothing happens, and nothing says why.
 *
 * The three cases here live in an `Activity` or in `LatinIME`, which need a device, so they are
 * pinned by source (as in `PersonalDictionaryFeedbackSourceContractTest`). The emoji search case
 * is a behavioral test in `EmojiSearchUnavailableTest`.
 */
class SilentGestureSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun mainFile(relative: String) = File(sourceRoot(), "java/$relative").readText()

    private val ime by lazy { mainFile("rkr/simplekeyboard/inputmethod/latin/LatinIME.java") }
    private val settings by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    // --- 1. Long press on a suggestion that is not a saved word ---------------------------------

    /**
     * Long-pressing a suggestion cell offers "Forget «X»?" for a word the personal dictionary
     * holds. For an ordinary dictionary word the lookup finds nothing; without feedback the same
     * gesture would show a dialog on one word and nothing on the next.
     */
    @Test
    fun aLongPressOnAnOrdinaryWordIsAnsweredRatherThanIgnored() {
        val body = bodyOf(ime, "private void showForgetPersonalWordDialog(",
            "private void showSuggestionsUnavailableDialog(")
        val branch = bodyOf(body, "if (savedForm == null) {", "}")
        assertTrue(
            "the not-a-saved-word branch must answer the gesture, not just return: <$branch>",
            branch.contains("showNotASavedWordDialog()"),
        )
        // The default state of the keyboard: the personal dictionary ships off, so this gate is
        // the one almost every long press actually reaches.
        val offGate = bodyOf(body, "if (!Settings.readPersonalDictionaryEnabled(mDevicePrefs)) {", "}")
        assertTrue(
            "the saved-words-are-off gate must answer the gesture too: <$offGate>",
            offGate.contains("showPersonalDictionaryOffDialog()"),
        )
        val noSubtype = bodyOf(body, "if (subtypeId == null) {", "}")
        assertTrue(
            "a layout with no personal dictionary must answer it too: <$noSubtype>",
            noSubtype.contains("showNotASavedWordDialog()"),
        )
        assertTrue("the off-dialog it names must exist",
            ime.contains("private void showPersonalDictionaryOffDialog()"))
        assertTrue("and the dialog it names must exist",
            ime.contains("private void showNotASavedWordDialog()"))
        assertTrue("it must carry a message string",
            bodyOf(ime, "private void showNotASavedWordDialog()", "\n    }")
                .contains("R.string.personal_dictionary_not_saved"))
    }

    // --- 2. The emoji key when the panel will never show ----------------------------------------

    /**
     * `onEmojiKeyPressed()` returns false when the panel will not show in this process — the
     * snapshot could not be built, so the key is dead for good. The caller must act on that
     * answer, or a visible key becomes a permanent no-op.
     */
    @Test
    fun aDeadEmojiKeyIsAnsweredRatherThanIgnored() {
        val body = bodyOf(ime, "public void showEmojiPanel()", "\n    }")
        assertFalse(
            "the answer must not be discarded: <$body>",
            body.contains("mEmojiPanelController.onEmojiKeyPressed();"),
        )
        assertTrue("the answer must be acted on",
            body.contains("if (!mEmojiPanelController.onEmojiKeyPressed())"))
        assertTrue("and it must reach the user",
            body.contains("showEmojiUnavailableDialog()"))
        assertTrue("the dialog it names must exist",
            ime.contains("private void showEmojiUnavailableDialog()"))
    }

    // --- 3. The links on the About card ---------------------------------------------------------

    /**
     * "Privacy Policy" and "License" open a browser. With no app able to handle the intent the row
     * must tell the user, not just write a log line they cannot see.
     */
    @Test
    fun aLinkWithNothingToOpenItIsAnsweredRatherThanLogged() {
        val body = bodyOf(settings, "private fun openUrl(uri: String)", "\n    }")
        assertTrue(
            "the failure must reach the screen, not only the log: <$body>",
            body.contains("Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_LONG).show()"),
        )
    }

    // --- the strings the three of them need ------------------------------------------------------

    @Test
    fun everyNewMessageExistsInAllThreeLanguages() {
        val keys = listOf(
            "personal_dictionary_not_saved",
            "personal_dictionary_off_nothing_to_forget",
            "emoji_unavailable",
            "no_app_for_link",
            "personal_dictionary_empty_ready",
        )
        val res = File(sourceRoot(), "res")
        for (dir in listOf("values", "values-ru", "values-tt")) {
            val text = File(res, "$dir/strings.xml").readText()
            for (key in keys) {
                assertTrue("$dir/strings.xml is missing $key",
                    text.contains("<string name=\"$key\">"))
            }
        }
    }

    /** The approved shape: no file, no path, no error code, no cause. */
    @Test
    fun noNewMessageNamesAFileAPathOrACode() {
        val res = File(sourceRoot(), "res")
        val banned = Regex("""\.tpers|\.tdict|/data/|files/|errno|SIGSEGV|IOException|0x[0-9a-fA-F]+""")
        for (dir in listOf("values", "values-ru", "values-tt")) {
            val text = File(res, "$dir/strings.xml").readText()
            for (key in listOf("personal_dictionary_not_saved",
                "personal_dictionary_off_nothing_to_forget", "emoji_unavailable",
                "no_app_for_link", "personal_dictionary_empty_ready")) {
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1) ?: continue
                assertFalse("$dir/$key names something the user cannot act on: $value",
                    banned.containsMatchIn(value))
            }
        }
    }
}
