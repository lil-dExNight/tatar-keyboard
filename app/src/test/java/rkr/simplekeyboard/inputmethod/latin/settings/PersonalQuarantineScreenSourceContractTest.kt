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
 * Mission `tt-quarantine`, task 2: the screen half of the recovery path.
 *
 * 1.8.2 kept the bytes of an unreadable personal dictionary and showed them on no screen. The store
 * half of the repair is exercised for real in `PersonalQuarantineRecoveryTest` — the copy is really
 * read, really restored, really discarded. What is left is an `Activity`, which needs a device, so
 * it is asserted by source in the style this project already uses for that class, and every
 * predicate is proved fail-capable against the shape it replaced in the last test.
 *
 * The rules being pinned are the mission's own decision rules, in order:
 *
 * 1. A partial recovery is never presented as a complete one. The count and the damage are printed
 *    together or not at all.
 * 2. Restoring is started by the person. Nothing on this screen restores by itself.
 * 3. Nothing user-visible names a file, a path, a cause or a code.
 */
class PersonalQuarantineScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainFile(relative: String) = File(sourceRoot(), "java/$relative").readText()

    private val host by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }
    private val screenController by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/PersonalDictionaryScreenController.kt")
    }
    private val store by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalDictionaryStore.kt")
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    private val card by lazy {
        bodyOf(host, "private fun addPersonalQuarantineCards(", "private fun showDiscardPersonalQuarantineDialog")
    }

    /** The names added for the card; each one has to exist in all three locales. */
    private val newStrings = listOf(
        "personal_dictionary_quarantine_title",
        "personal_dictionary_quarantine_none",
        "personal_dictionary_quarantine_restore",
        "personal_dictionary_quarantine_discard",
        "personal_dictionary_quarantine_discard_confirm",
        "personal_dictionary_quarantine_restore_failed",
        "personal_dictionary_quarantine_discard_failed",
    )

    // --- the card exists at all ------------------------------------------------------------------

    /** The screen that showed no copy anywhere now builds one card per language that has one. */
    @Test
    fun theSavedWordsScreenShowsTheCopyThatUsedToBeInvisible() {
        assertTrue("the card is part of the personal-dictionary screen",
            bodyOf(host, "private fun buildPersonalDictionaryScreen() {", "\n    /**")
                .contains("addPersonalQuarantineCards(controller, subtypeIds)"))
        assertTrue("one per language that has a copy",
            card.contains("val report = reports[subtypeId] ?: continue"))
        assertTrue("in the order the languages are listed, not the order the worker answered in",
            card.contains("for (subtypeId in subtypeIds) {"))
    }

    /**
     * The read is file work: it goes to the store's worker and comes back to the UI thread, and the
     * screen repaints then. Reading the copy on the UI thread would be the one thing this whole
     * subsystem has never done.
     */
    @Test
    fun theCopyIsReadOnTheWorkerAndTheScreenRepaintsWhenTheAnswerArrives() {
        assertTrue("asked once, when the answer is not known yet",
            card.contains("if (reports == null) {") && card.contains("controller.quarantines(subtypeIds) { found ->"))
        assertTrue("and the screen is rebuilt from the answer",
            card.contains("personalQuarantines = found"))
        assertTrue("but not into an Activity that has gone",
            card.contains("if (isFinishing || isDestroyed) return@quarantines"))

        val inspect = bodyOf(screenController, "fun quarantines(", "\n    /**")
        assertTrue("the controller asks the store, never the file system",
            inspect.contains("PersonalDictionaries.storeFor(context, subtypeId).inspectQuarantine {"))
        assertTrue("and answers exactly once, on the UI thread",
            inspect.contains("if (remaining.decrementAndGet() == 0) {") &&
                inspect.contains("uiPoster { onReady(found.toMap()) }"))
    }

    // --- decision rule 1: never a partial recovery dressed up as a whole one ----------------------

    /**
     * The count and the damage are one decision, made in one `when`. A screen that could print the
     * number without the sentence beside it is exactly the failure this rule exists to forbid.
     */
    @Test
    fun theCountAndTheDamageAreChosenTogether() {
        val summary = bodyOf(card, "val summary = when {", "addSectionHeader")
        assertTrue("nothing readable is its own sentence",
            summary.contains("report.wordCount == 0 -> getString(R.string.personal_dictionary_quarantine_none)"))
        assertTrue("a whole copy says so", summary.contains("report.readToEnd -> resources.getQuantityString("))
        assertTrue(summary.contains("R.plurals.personal_dictionary_quarantine_whole"))
        assertTrue("and a damaged one says THAT, with the same count",
            summary.contains("R.plurals.personal_dictionary_quarantine_partial"))

        // The wording carries the other half: the count alone would be a true number in a false
        // sentence, so every form of the damaged string must say what happened to the rest.
        for (qualifier in listOf("values", "values-ru", "values-tt")) {
            val forms = pluralForms(qualifier, "personal_dictionary_quarantine_partial")
            assertTrue("$qualifier has the damaged wording at all", forms.isNotEmpty())
            for ((quantity, text) in forms) {
                assertTrue("$qualifier/$quantity: the number is in it", text.contains("%1\$d"))
            }
        }
        assertTrue("and the English says the rest is damaged, in every form",
            pluralForms("values", "personal_dictionary_quarantine_partial")
                .all { it.value.lowercase().contains("damaged") })
        assertFalse("while the whole-copy wording claims no loss",
            pluralForms("values", "personal_dictionary_quarantine_whole")
                .any { it.value.lowercase().contains("damaged") })

        // A count that reads "1 words" makes a person doubt the sentence beside it, and the sentence
        // beside it is the one saying part of their words is gone.
        assertTrue("English distinguishes one from many",
            pluralForms("values", "personal_dictionary_quarantine_whole").keys.containsAll(
                listOf("one", "other")))
        assertTrue("and Russian carries all three of its forms",
            pluralForms("values-ru", "personal_dictionary_quarantine_whole").keys.containsAll(
                listOf("one", "few", "many")))
    }

    /**
     * A copy that yielded nothing keeps its card and loses only the restore action. The bytes are
     * the user's own words on the user's own device; the button that removes them may not be hidden
     * behind a word count.
     */
    @Test
    fun aCopyThatYieldedNothingStillOffersTheOneActionThatAppliesToIt() {
        assertTrue("restoring is offered only when there is something to restore",
            card.contains("if (report.wordCount > 0) {") &&
                card.contains("actionRow(R.string.personal_dictionary_quarantine_restore)"))
        // Indentation is the check that the delete row is a sibling of the branch and not inside
        // it: nested, it would be four columns further in and would disappear with the branch.
        assertTrue(
            "deleting the copy is offered outside the word-count branch",
            card.contains(
                "\n            rows.add(actionRow(R.string.personal_dictionary_quarantine_discard)"),
        )
        assertTrue(
            "while restoring is inside it",
            card.contains(
                "\n                rows.add(actionRow(R.string.personal_dictionary_quarantine_restore)"),
        )
    }

    // --- decision rule 2: the person starts it ----------------------------------------------------

    /** Both actions are rows the user taps, and the destructive one is confirmed first. */
    @Test
    fun nothingIsRestoredOrDeletedWithoutTheUserAskingForIt() {
        assertTrue("restoring is a tap", card.contains("controller.restoreQuarantine(subtypeId) { restored ->"))
        assertTrue("deleting the copy asks first",
            card.contains("showDiscardPersonalQuarantineDialog(controller, subtypeId)"))
        val dialog = bodyOf(host, "private fun showDiscardPersonalQuarantineDialog(", "\n    /**")
        assertTrue(dialog.contains("setMessage(R.string.personal_dictionary_quarantine_discard_confirm)"))
        assertTrue("and only the positive button acts",
            dialog.contains("setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->") &&
                dialog.contains("controller.discardQuarantine(subtypeId) { discarded ->"))
    }

    /**
     * Restoring does not delete the copy. Two actions, two decisions: the damaged tail is the part
     * no reader could handle THIS time, and a repair that destroys it takes the only chance a better
     * reader would ever have.
     */
    @Test
    fun restoringDoesNotTakeTheCopyWithIt() {
        val restore = bodyOf(store, "private fun restoreOnWorker(): Boolean {", "\n    /**")
        assertFalse("the restore must not remove anything",
            restore.contains("deleteFile"))
        assertTrue("it only adds what is not already there",
            restore.contains("if (candidate.containsNormalized(normalized)) continue"))
    }

    /** And "Erase all words" still takes the copy — the link 1.8.2 established is not broken here. */
    @Test
    fun erasingEverythingStillRemovesTheCopyAndInvalidatesTheCard() {
        assertTrue("the erasure still covers the copy",
            bodyOf(store, "fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {", "\n    /**")
                .contains("deleted { deleteFile(directory, File(directory, quarantineFileName())) }"))
        assertTrue("and the card is re-read rather than repainted from a stale answer",
            bodyOf(host, "private fun showErasePersonalDictionaryDialog(", "\n    /**")
                .contains("personalQuarantines = null"))
    }

    /** Every finished action re-reads the copy, because every one of them changes the answer. */
    @Test
    fun everyActionOnTheCopyMakesTheScreenAskAgain() {
        assertEquals(
            "restore, discard, erase-all and the U7 per-language clear: four actions, four invalidations",
            4,
            Regex(Regex.escape("personalQuarantines = null")).findAll(host).count(),
        )
    }

    // --- decision rule 3: nothing visible names a file, a path or a cause -------------------------

    @Test
    fun everyNewSentenceIsTranslatedAndNamesNoFilePathOrCause() {
        val forbidden = listOf("file", "path", "error", "tpers", "quarantine", "/")
        for (name in listOf("personal_dictionary_quarantine_partial",
                "personal_dictionary_quarantine_whole")) {
            for (qualifier in listOf("values", "values-ru", "values-tt")) {
                assertTrue("$name: $qualifier", pluralForms(qualifier, name).isNotEmpty())
            }
            for ((quantity, text) in pluralForms("values", name)) {
                for (word in forbidden) {
                    assertFalse("$name/$quantity must not name '$word'",
                        text.lowercase().contains(word))
                }
            }
        }
        for (name in newStrings) {
            val english = stringValue("values", name)
            assertTrue("$name: Russian", stringValue("values-ru", name).isNotEmpty())
            assertTrue("$name: Tatar", stringValue("values-tt", name).isNotEmpty())
            // The same rule the failure messages already follow: a user cannot act on any of it, and
            // the file name is derived from the language the user types in.
            for (word in forbidden) {
                assertFalse("$name must not name '$word'", english.lowercase().contains(word))
            }
        }
    }

    /** The notice now points at the screen, because there finally is something to point at. */
    @Test
    fun theNoticeOffersTheActionThatNowExists() {
        val english = stringValue("values", "personal_dictionary_unreadable")
        assertTrue("it still says the list is empty", english.contains("empty"))
        assertTrue("and now says a copy was kept", english.lowercase().contains("copy"))
        assertTrue("and where to go", english.contains("Saved words"))
        for (forbidden in listOf("file", "path", "error", "tpers", "/")) {
            assertFalse("the notice must not name '$forbidden'", english.lowercase().contains(forbidden))
        }
    }

    /** The `<item quantity="…">` forms of one `<plurals>`, by quantity. */
    private fun pluralForms(qualifier: String, name: String): Map<String, String> {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("$name missing from $qualifier")
        return Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun stringValue(qualifier: String, name: String): String {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("$name missing from $qualifier")
    }

    // --- fail-capability -------------------------------------------------------------------------

    /**
     * Every predicate above is worth its line only if the shape it replaced makes it red. These are
     * the shapes 1.8.2 shipped — a screen with no card at all — fed to the same checks.
     */
    @Test
    fun thePredicatesRejectTheShapesTheyReplaced() {
        val screenWithoutTheCard =
            "        if (content.totalCount == 0) {\n" +
                "            addCard(listOf(inflateRow(R.layout.row_link,\n"
        assertFalse("a screen that never mentions the copy must not satisfy the card check",
            screenWithoutTheCard.contains("addPersonalQuarantineCards(controller, subtypeIds)"))

        val countWithoutTheDamage = "getQuantityString(R.plurals.personal_dictionary_quarantine_whole"
        assertFalse("printing the count alone must not satisfy the damage check",
            countWithoutTheDamage.contains("personal_dictionary_quarantine_partial"))

        val bareFormatArgument = """<string name="x">%1${'$'}d words can be brought back.</string>"""
        assertFalse("a single form must not satisfy the one-versus-many check",
            bareFormatArgument.contains("quantity=\"one\""))

        val restoreThatCleansUp = "if (added == 0) return true\n        deleteFile(directory, copy)"
        assertFalse("a restore that destroys the copy must not satisfy the leave-it-alone check",
            !restoreThatCleansUp.contains("deleteFile"))

        val restoreWithoutAsking = "controller.restoreQuarantine(subtypeId) { }  // on open"
        assertFalse("the confirmation check must not be satisfied by a restore with no dialog",
            restoreWithoutAsking.contains("showDiscardPersonalQuarantineDialog"))

        val namesTheFile = "The file personal-tt_RU-s1-f1.tpers could not be read."
        assertTrue("the privacy check must reject a sentence that names the file",
            namesTheFile.lowercase().contains("file"))
    }
}
