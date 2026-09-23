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

/**
 * U7 of Phase 2 (docs/ROADMAP-P2.md): the pairs half of the "Personal dictionary" screen —
 * the learned word pairs listed per language ("A → B", usage count), per-pair delete, per-language
 * "Clear all", the global erase covering both stores, and the quarantine card for an unreadable
 * pairs file.
 *
 * The store half of every mutation named here is exercised for real in `PersonalBigramStoreWriteTest`
 * and `PersonalQuarantineRecoveryTest`. What is left is the controller wiring and the Activity,
 * which cannot run off-device, so they are pinned by source in the established style — every
 * predicate fail-capable against the shape it replaced.
 *
 * The rules being pinned:
 *
 * 1. Every pair mutation goes through the process-wide owner and the store's `forget`/`clearAll`
 *    APIs — the screen never touches a file, and "erased" covers the quarantine copy too.
 * 2. Every outcome is marshalled onto the UI thread; the screen repaints only from a FINISHED
 *    mutation.
 * 3. Nothing user-visible names a file, a path, a cause or a code.
 */
class PersonalBigramScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainFile(relative: String) = File(sourceRoot(), "java/$relative").readText()

    private val host by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }
    private val pairController by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/PersonalBigramScreenController.kt")
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    private val screen by lazy {
        bodyOf(host, "private fun buildPersonalDictionaryScreen() {", "\n    /**")
    }

    // --- The screen shows the pairs and lets the user act on them --------------------------------

    @Test
    fun theScreenReadsBothStoresThroughTheSameOwnerTheEngineUses() {
        assertTrue("the pairs content comes from the pairs controller",
            screen.contains("pairController.sections(subtypeIds)"))
        val sections = bodyOf(pairController, "fun sections(", "\n    /**")
        assertTrue("and that reads the process-wide owner, never the file system",
            sections.contains("PersonalBigramDictionaries.snapshotFor(context, it)"))
        assertFalse("the screen performs no file I/O of its own",
            pairController.contains("java.io.File") || pairController.contains("FileInputStream"))
    }

    @Test
    fun deletingOnePairGoesThroughTheStoreForgetSoTheCopyIsPurgedToo() {
        assertTrue("the row's tap opens a confirmation",
            screen.contains("showForgetPersonalPairDialog(pairController, row)"))
        val dialog = bodyOf(host, "private fun showForgetPersonalPairDialog(", "\n    /**")
        assertTrue("the dialog shows the pair the way the row does — \"A → B\"",
            dialog.contains("R.string.personal_dictionary_pair_forget_title"))
        assertTrue("and only the positive button acts",
            dialog.contains("controller.removePair(row.subtypeId, row.contextForm,"))
        val removal = bodyOf(pairController, "fun removePair(", "\n    /**")
        assertTrue("the removal is the store's forget — the quarantine purge comes with it",
            removal.contains("PersonalBigramDictionaries.storeFor(context, subtypeId)")
                && removal.contains(".forget(contextForm, successorForm)"))
        assertTrue("and the band is unbound at once",
            removal.contains("PersonalBigramDictionaries.notifyErased()"))
    }

    @Test
    fun everyLevelOfErasureIsCoveredAndConfirmed() {
        // Per language, per store: two clear rows inside the sections.
        assertTrue(screen.contains("showClearPersonalWordsDialog(controller, section.subtypeId)"))
        assertTrue(screen.contains("showClearPersonalPairsDialog(pairController, section.subtypeId)"))
        val clearPairs = bodyOf(host, "private fun showClearPersonalPairsDialog(", "\n    /**")
        assertTrue("the destructive action asks first",
            clearPairs.contains("R.string.personal_dictionary_clear_pairs_confirm"))
        // And the global erase now covers BOTH stores — an "erase everything" that left the pairs
        // behind would read as a lie the predictions keep contradicting.
        val erase = bodyOf(host, "private fun showErasePersonalDictionaryDialog(", "\n    /**")
        assertTrue("the words half", erase.contains("controller.eraseAll(subtypeIds)"))
        assertTrue("and the pairs half", erase.contains("pairController.eraseAll(subtypeIds)"))
        assertTrue("reported as one honest answer",
            erase.contains("afterPersonalMutation(wordsErased && pairsErased,"))
        assertTrue("with both quarantine cards re-read",
            erase.contains("personalQuarantines = null") && erase.contains("personalPairQuarantines = null"))
    }

    @Test
    fun everyPairOutcomeIsMarshalledOntoTheUiThread() {
        assertTrue(pairController.contains("private val uiPoster: (Runnable) -> Unit"))
        // Six entry points, eight exits: removePair, clearPairs, restoreQuarantine and
        // discardQuarantine answer once each; eraseAll and quarantines answer on both of their
        // branches (nothing to do, and the counted fan-out). Every one ends on the UI thread.
        assertEquals("no answer may be left on the store's worker", 8,
            Regex("uiPoster \\{").findAll(pairController).count())
    }

    // --- The pairs quarantine card ---------------------------------------------------------------

    @Test
    fun anUnreadablePairsFileGetsTheSameCardTheWordsGet() {
        assertTrue("the card is built on the personal screen",
            screen.contains("addPersonalPairQuarantineCards(pairController, subtypeIds)"))
        val card = bodyOf(host, "private fun addPersonalPairQuarantineCards(", "\n    /**")
        assertTrue("the count and the damage are chosen together, like the words card",
            card.contains("val summary = when {")
                && card.contains("R.string.personal_bigrams_quarantine_none")
                && card.contains("R.plurals.personal_bigrams_quarantine_whole")
                && card.contains("R.plurals.personal_bigrams_quarantine_partial"))
        assertTrue("restoring is offered only when there is something to restore",
            card.contains("if (report.wordCount > 0) {")
                && card.contains("R.string.personal_bigrams_quarantine_restore"))
        assertTrue("discarding asks first",
            card.contains("showDiscardPersonalPairQuarantineDialog(controller, subtypeId)"))
        assertTrue("and the read itself is async, on the store's worker",
            card.contains("if (reports == null) {") && card.contains("controller.quarantines(subtypeIds) { found ->"))
    }

    @Test
    fun everySentenceIsTranslatedAndNamesNoFilePathOrCause() {
        val keys = listOf(
            "personal_dictionary_pair_forget_title",
            "personal_dictionary_pair_delete_failed",
            "personal_dictionary_clear_words",
            "personal_dictionary_clear_words_confirm",
            "personal_dictionary_clear_pairs",
            "personal_dictionary_clear_pairs_confirm",
            "personal_bigrams_quarantine_title",
            "personal_bigrams_quarantine_none",
            "personal_bigrams_quarantine_restore",
            "personal_bigrams_quarantine_discard",
            "personal_bigrams_quarantine_discard_confirm",
            "personal_bigrams_quarantine_restore_failed",
            "personal_bigrams_quarantine_discard_failed",
            "personal_dictionary_learning_paused",
            "incognito_mode",
            "incognito_mode_summary",
        )
        val forbidden = listOf("file", "path", "error", "tpers", "quarantine", "/")
        for (name in keys) {
            val english = stringValue("values", name)
            assertTrue("$name: English", english.isNotEmpty())
            assertTrue("$name: Russian", stringValue("values-ru", name).isNotEmpty())
            assertTrue("$name: Tatar", stringValue("values-tt", name).isNotEmpty())
            for (word in forbidden) {
                assertFalse("$name must not name '$word'", english.lowercase().contains(word))
            }
        }
        for (name in listOf("personal_bigrams_quarantine_partial", "personal_bigrams_quarantine_whole",
                "personal_dictionary_words_count", "personal_dictionary_pairs_count",
                "personal_dictionary_usage_count")) {
            for (qualifier in listOf("values", "values-ru", "values-tt")) {
                assertTrue("$name: $qualifier", pluralForms(qualifier, name).isNotEmpty())
            }
            for ((quantity, text) in pluralForms("values", name)) {
                assertTrue("$name/$quantity carries the count", text.contains("%1\$d"))
            }
        }
    }

    // --- fail-capability -------------------------------------------------------------------------

    @Test
    fun thePredicatesRejectTheShapesTheyReplaced() {
        val eraseWithoutThePairs = """
            controller.eraseAll(subtypeIds) { erased ->
                afterPersonalMutation(erased, R.string.personal_dictionary_erase_failed)
            }
        """.trimIndent()
        assertFalse("a global erase that forgets the pairs must not satisfy the check",
            eraseWithoutThePairs.contains("pairController.eraseAll(subtypeIds)"))

        val deleteWithoutTheStore = "controller.removePair(row.subtypeId) { }  // deletes a file"
        assertFalse("a removal that bypasses the store must not satisfy the check",
            deleteWithoutTheStore.contains(".forget(contextForm, successorForm)"))

        val namesTheFile = "The file personal-bigrams-tt_RU-s1-f1.tpersb could not be read."
        assertTrue("the privacy check must reject a sentence that names the file",
            namesTheFile.lowercase().contains("file"))
    }

    /** The `<item quantity="…">` forms of one `<plurals>`, by quantity. */
    private fun pluralForms(qualifier: String, name: String): Map<String, String> {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun stringValue(qualifier: String, name: String): String {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: ""
    }
}
