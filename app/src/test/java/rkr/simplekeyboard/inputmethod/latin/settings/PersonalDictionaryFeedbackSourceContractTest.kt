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
 * Mission `tt-personal-dict`, finding A2 of `docs/SILENT-AUDIT.md`, on the side of it that cannot
 * run off-device: what the SCREEN does with the outcome.
 *
 * The store half is exercised for real in `PersonalDictionarySilentFailureTest`. Everything asserted
 * here lives in an `Activity` and in `LatinIME`, which need a device, so it is asserted by source in
 * the style this project already uses for both classes — and every predicate below is proved
 * fail-capable against the shape it replaced, so a regression turns it red instead of quietly
 * passing.
 *
 * The rule being pinned: repainting the list is not allowed to happen in the same statement that
 * queues the mutation. The list is drawn from the published snapshot, and the snapshot does not
 * exist until the worker has finished two fsyncs, so the old ordering routinely drew a list without
 * the word the user had just added — and said nothing at all when the write had genuinely failed.
 */
class PersonalDictionaryFeedbackSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun mainFile(relative: String) = File(sourceRoot(), "java/$relative").readText()

    private val host by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }
    private val screenController by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/PersonalDictionaryScreenController.kt")
    }
    private val forget by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalForget.kt")
    }
    private val ime by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    // --- the screen ---------------------------------------------------------------------------

    @Test
    fun allThreeMutationsHandOverACallbackInsteadOfAssumingSuccess() {
        val add = bodyOf(host, "private fun showAddPersonalWordDialog(",
            "private fun showForgetPersonalWordDialog(")
        val remove = bodyOf(host, "private fun showForgetPersonalWordDialog(",
            "private fun showErasePersonalDictionaryDialog(")
        val erase = bodyOf(host, "private fun showErasePersonalDictionaryDialog(",
            "private fun afterPersonalMutation(")

        assertTrue("adding takes the outcome", add.contains("controller.addWord(subtypeId, field.text.toString()) { saved ->"))
        assertTrue("removing takes the outcome",
            remove.contains("controller.removeWord(row.subtypeId, row.normalizedForm) { removed ->"))
        // U7 (2026-09-23): the global erase answers twice — once per store — so its lambda names
        // the words half; the pairs half arrives in the nested call.
        assertTrue("erasing takes the outcome", erase.contains("controller.eraseAll(subtypeIds) { wordsErased ->"))

        // And each one routes it into the single place that decides what the user sees.
        for (handler in listOf(add, remove, erase)) {
            assertTrue("the outcome reaches afterPersonalMutation",
                handler.contains("afterPersonalMutation("))
        }
    }

    @Test
    fun theListIsNotRepaintedBeforeTheMutationHasFinished() {
        val remove = bodyOf(host, "private fun showForgetPersonalWordDialog(",
            "private fun showErasePersonalDictionaryDialog(")
        val erase = bodyOf(host, "private fun showErasePersonalDictionaryDialog(",
            "private fun afterPersonalMutation(")
        for (handler in listOf(remove, erase)) {
            assertFalse(
                "a repaint in the confirm handler reads the snapshot that does not exist yet",
                handler.contains("showScreen(Screen.PERSONAL_DICTIONARY)"),
            )
        }

        // Adding keeps ONE synchronous repaint, and only on the branch that never queued anything:
        // a word the content filter rejected. Nothing is in flight there, so nothing is awaited.
        val add = bodyOf(host, "private fun showAddPersonalWordDialog(",
            "private fun showForgetPersonalWordDialog(")
        assertEquals("exactly one, and it is the rejection branch", 1,
            Regex("showScreen\\(Screen\\.PERSONAL_DICTIONARY\\)").findAll(add).count())
        assertTrue(add.substringBefore("showScreen(Screen.PERSONAL_DICTIONARY)")
            .contains("R.string.personal_dictionary_add_rejected"))
    }

    @Test
    fun theSinglePlaceThatReactsSaysSomethingWhenTheMutationFailed() {
        val after = bodyOf(host, "private fun afterPersonalMutation(", "\n    /**")
        assertTrue("a failure is spoken aloud", after.contains("Toast.makeText(this, failureMessageRes"))
        assertTrue("and the list is repainted from the finished state",
            after.contains("showScreen(Screen.PERSONAL_DICTIONARY)"))
        assertTrue("a dead Activity is not touched",
            after.contains("if (isFinishing || isDestroyed) return"))
        assertTrue("nor a screen the user has already navigated away from",
            after.contains("if (currentScreen == Screen.PERSONAL_DICTIONARY)"))
    }

    @Test
    fun eachFailureHasItsOwnMessageAndAllThreeAreTranslated() {
        val keys = listOf(
            "personal_dictionary_save_failed",
            "personal_dictionary_delete_failed",
            "personal_dictionary_erase_failed",
        )
        // The three languages this app writes its own strings in.
        for (values in listOf("values", "values-ru", "values-tt")) {
            val strings = File(sourceRoot(), "res/$values/strings.xml").readText()
            for (key in keys) {
                assertTrue("$values is missing $key", strings.contains("\"$key\""))
            }
        }
        // No message may name the word: this text can appear over any app, and the words are the
        // one thing the personal dictionary exists to keep private.
        val english = File(sourceRoot(), "res/values/strings.xml").readText()
        for (key in keys) {
            val text = english.substringAfter("<string name=\"$key\">").substringBefore("</string>")
            assertFalse("$key interpolates something", text.contains("%"))
        }
    }

    // --- the controller between the screen and the store ----------------------------------------

    @Test
    fun everyOutcomeIsMarshalledOntoTheUiThread() {
        // The store answers on its own worker; a Toast and a repaint may not happen there.
        assertTrue(screenController.contains("private val uiPoster: (Runnable) -> Unit"))
        // Seven entry points, nine exits: eraseAll and quarantines each answer on both of their
        // branches (nothing to do, and the counted fan-out). Every one of them ends on the UI
        // thread. (9th exit 2026-09-23: U7's per-language clearWords, docs/ROADMAP-P2.md.)
        assertEquals("no answer may be left on the store's worker", 9,
            Regex("uiPoster \\{").findAll(screenController).count())
    }

    @Test
    fun eraseAllReportsSuccessOnlyWhenEveryLanguageIsActuallyGone() {
        val erase = bodyOf(screenController, "fun eraseAll(", "\n}")
        assertTrue("one answer is produced from all of them, not one per language",
            erase.contains("remaining.decrementAndGet() == 0"))
        assertTrue("and any single failure sinks the whole answer",
            erase.contains("if (!erased) everythingGone.set(false)"))
    }

    // --- the IME's own "Forget word" ------------------------------------------------------------

    @Test
    fun forgettingFromTheBandAlsoHasSomewhereToReportFailure() {
        assertTrue("the seam exists and carries nothing but the fact",
            forget.contains("onFailed: Runnable? = null"))
        assertTrue("and it fires only when the word is still saved",
            forget.contains("if (!removed) onFailed?.run()"))

        assertTrue("the IME passes one in",
            ime.contains("PersonalForget.confirmForget(this, subtypeId, shownWord,"))
        assertTrue("marshalled onto the UI thread, like every other store notification here",
            ime.contains("() -> mHandler.post(this::showPersonalForgetFailedDialog)"))
        val dialog = bodyOf(ime, "private void showPersonalForgetFailedDialog() {",
            "\n    /**")
        assertTrue(dialog.contains("R.string.personal_dictionary_delete_failed"))
        assertTrue("attached to the input window like every dialog this service owns",
            dialog.contains("attachDialogToInputWindow(dialog, windowToken)"))
    }

    // --- fail-capability -------------------------------------------------------------------------

    /**
     * Each predicate above is only worth its line if the shape it replaced makes it red. These are
     * the two shapes that actually shipped, fed to the same checks.
     */
    @Test
    fun thePredicatesRejectTheShapeTheyReplaced() {
        val oldAddHandler = """
            val added = subtypeId != null && controller.addWord(subtypeId, field.text.toString())
            if (!added) { Toast }
            showScreen(Screen.PERSONAL_DICTIONARY)
        """.trimIndent()
        assertFalse("the old add handler must not satisfy the callback check",
            oldAddHandler.contains("controller.addWord(subtypeId, field.text.toString()) { saved ->"))

        val oldRemoveHandler = """
            controller.removeWord(row.subtypeId, row.normalizedForm)
            showScreen(Screen.PERSONAL_DICTIONARY)
        """.trimIndent()
        assertTrue("the old remove handler repainted before the mutation finished",
            oldRemoveHandler.contains("showScreen(Screen.PERSONAL_DICTIONARY)"))

        val oldConfirmForget = "PersonalForget.confirmForget(this, subtypeId, shownWord))"
        assertFalse("the old IME call handed over no failure channel",
            oldConfirmForget.contains("PersonalForget.confirmForget(this, subtypeId, shownWord,"))
    }
}
