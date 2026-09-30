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
 * The learned-emoji part of the "Personal dictionary" screen —
 * the learned word → emoji entries listed per language ("word → emoji", usage count), per-entry
 * delete, per-language "Clear all", the global erase covering all THREE stores, and the quarantine
 * card for an unreadable emoji file.
 *
 * The store half of every mutation named here is exercised for real in the personalstore tests.
 * What is left is the controller wiring and the Activity, which cannot run off-device, so they are
 * pinned by source; every predicate is also checked against a broken input.
 *
 * The rules being pinned:
 *
 * 1. Every emoji mutation goes through the process-wide owner and the store's `forget`/`clearAll`
 *    APIs — the screen never touches a file, and "erased" covers the quarantine copy too.
 * 2. Every outcome is marshalled onto the UI thread; the screen repaints only from a FINISHED
 *    mutation.
 * 3. Nothing user-visible names a file, a path, a cause or a code.
 */
class PersonalEmojiScreenSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainFile(relative: String) = File(sourceRoot(), "java/$relative").readText()

    private val host by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
    }
    private val emojiController by lazy {
        mainFile("rkr/simplekeyboard/inputmethod/latin/settings/PersonalEmojiScreenController.kt")
    }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    private val screen by lazy {
        bodyOf(host, "private fun buildPersonalDictionaryScreen() {", "\n    /**")
    }

    // --- The screen shows the learned emoji and lets the user act on them ------------------------

    @Test
    fun theScreenReadsTheEmojiStoreThroughTheSameOwnerTheEngineUses() {
        assertTrue("the emoji content comes from the emoji controller",
            screen.contains("emojiController.sections(subtypeIds)"))
        val sections = bodyOf(emojiController, "fun sections(", "\n    /**")
        assertTrue("and that reads the process-wide owner, never the file system",
            sections.contains("PersonalEmojiDictionaries.snapshotFor(context, it)"))
        assertFalse("the screen performs no file I/O of its own",
            emojiController.contains("java.io.File") || emojiController.contains("FileInputStream"))
    }

    @Test
    fun theEmojiCardSitsInTheSectionAfterThePairsCard() {
        assertTrue("the card starts from the saved-emoji count",
            screen.contains("R.plurals.personal_emoji_count")
                && screen.contains("section.emojiCount"))
        assertTrue("the row reads \"word → emoji\", the shape its forget dialog repeats",
            screen.contains("row.word + \" → \" + row.emoji"))
        assertTrue("each store of the language gets its own clear action",
            screen.contains("R.string.personal_dictionary_clear_words")
                && screen.contains("R.string.personal_dictionary_clear_pairs")
                && screen.contains("R.string.personal_emoji_clear"))
    }

    @Test
    fun deletingOneEntryGoesThroughTheStoreForgetSoTheCopyIsPurgedToo() {
        assertTrue("the row's tap opens a confirmation",
            screen.contains("showForgetPersonalEmojiDialog(emojiController, row)"))
        val dialog = bodyOf(host, "private fun showForgetPersonalEmojiDialog(", "\n    /**")
        assertTrue("the dialog shows the entry the way the row does — \"word → emoji\"",
            dialog.contains("R.string.personal_emoji_forget_title"))
        assertTrue("and only the positive button acts",
            dialog.contains("controller.removeEntry(row.subtypeId, row.word, row.emoji)"))
        assertTrue("the dialog names the entry, so its window is secured",
            dialog.contains("DialogUtils.securePersonalContent(dialog)")
                && dialog.contains("DialogUtils.filterObscuredTouches(dialog)"))
        val removal = bodyOf(emojiController, "fun removeEntry(", "\n    /**")
        assertTrue("the removal is the store's forget — the quarantine purge comes with it",
            removal.contains("PersonalEmojiDictionaries.storeFor(context, subtypeId)")
                && removal.contains(".forget(word, emoji)"))
        assertTrue("and the band is unbound at once",
            removal.contains("PersonalEmojiDictionaries.notifyErased()"))
    }

    @Test
    fun everyLevelOfErasureIsCoveredAndConfirmed() {
        // Per language, per store: the third clear row inside the sections.
        assertTrue(screen.contains("showClearPersonalEmojiDialog(emojiController, section.subtypeId)"))
        val clearEmoji = bodyOf(host, "private fun showClearPersonalEmojiDialog(", "\n    private fun")
        assertTrue("the destructive action asks first",
            clearEmoji.contains("R.string.personal_emoji_clear_confirm"))
        assertTrue("and reaches the store's clearAll through the controller",
            clearEmoji.contains("controller.clearEmoji(subtypeId)"))
        val clearing = bodyOf(emojiController, "fun clearEmoji(", "\n    /**")
        assertTrue("the controller calls the store's clearAll",
            clearing.contains("PersonalEmojiDictionaries.storeFor(context, subtypeId)")
                && clearing.contains(".clearAll"))
        assertTrue("and the band is unbound at once",
            clearing.contains("PersonalEmojiDictionaries.notifyErased()"))
        // The global erase covers ALL THREE stores — an "erase everything" that left the learned
        // emoji behind would be contradicted by the suggestions.
        val erase = bodyOf(host, "private fun showErasePersonalDictionaryDialog(", "\n    /**")
        assertTrue("the words half", erase.contains("controller.eraseAll(subtypeIds)"))
        assertTrue("the pairs half", erase.contains("pairController.eraseAll(subtypeIds)"))
        assertTrue("and the emoji third", erase.contains("emojiController.eraseAll(subtypeIds)"))
        assertTrue("reported as one honest answer",
            erase.contains("afterPersonalMutation(wordsErased && pairsErased && emojiErased,"))
        assertTrue("with all three quarantine cards re-read",
            erase.contains("personalQuarantines = null")
                && erase.contains("personalPairQuarantines = null")
                && erase.contains("personalEmojiQuarantines = null"))
    }

    @Test
    fun everyEmojiOutcomeIsMarshalledOntoTheUiThread() {
        assertTrue(emojiController.contains("private val uiPoster: (Runnable) -> Unit"))
        // Six entry points, eight exits: removeEntry, clearEmoji, restoreQuarantine and
        // discardQuarantine answer once each; eraseAll and quarantines answer on both of their
        // branches (nothing to do, and the counted fan-out). Every one ends on the UI thread.
        assertEquals("no answer may be left on the store's worker", 8,
            Regex("uiPoster \\{").findAll(emojiController).count())
    }

    // --- The emoji quarantine card ---------------------------------------------------------------

    @Test
    fun anUnreadableEmojiFileGetsTheSameCardTheWordsGet() {
        assertTrue("the card is built on the personal screen",
            screen.contains("addPersonalEmojiQuarantineCards(emojiController, subtypeIds)"))
        val card = bodyOf(host, "private fun addPersonalEmojiQuarantineCards(", "\n    private fun")
        assertTrue("the count and the damage are chosen together, like the words card",
            card.contains("val summary = when {")
                && card.contains("R.string.personal_emoji_quarantine_none")
                && card.contains("R.plurals.personal_emoji_quarantine_whole")
                && card.contains("R.plurals.personal_emoji_quarantine_partial"))
        assertTrue("restoring is offered only when there is something to restore",
            card.contains("if (report.wordCount > 0) {")
                && card.contains("R.string.personal_emoji_quarantine_restore"))
        assertTrue("discarding asks first",
            card.contains("showDiscardPersonalEmojiQuarantineDialog(controller, subtypeId)"))
        assertTrue("and the read itself is async, on the store's worker",
            card.contains("if (reports == null) {") && card.contains("controller.quarantines(subtypeIds) { found ->"))
        val discard = bodyOf(host, "private fun showDiscardPersonalEmojiQuarantineDialog(", "\n    /**")
        assertTrue("the discard confirmation filters obscured touches",
            discard.contains("DialogUtils.filterObscuredTouches(dialog)"))
    }

    @Test
    fun everySentenceIsTranslatedAndNamesNoFilePathOrCause() {
        val keys = listOf(
            "personal_emoji_forget_title",
            "personal_emoji_delete_failed",
            "personal_emoji_clear",
            "personal_emoji_clear_confirm",
            "personal_emoji_quarantine_title",
            "personal_emoji_quarantine_none",
            "personal_emoji_quarantine_restore",
            "personal_emoji_quarantine_discard",
            "personal_emoji_quarantine_discard_confirm",
            "personal_emoji_quarantine_restore_failed",
            "personal_emoji_quarantine_discard_failed",
            "personal_dictionary_erase_confirm",
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
        assertTrue("the global erase now says all three kinds of content",
            stringValue("values", "personal_dictionary_erase_confirm").let {
                it.contains("word") && it.contains("pair") && it.contains("emoji")
            })
        for (name in listOf("personal_emoji_count",
                "personal_emoji_quarantine_partial", "personal_emoji_quarantine_whole")) {
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
        val eraseWithoutTheEmoji = """
            controller.eraseAll(subtypeIds) { wordsErased ->
                pairController.eraseAll(subtypeIds) { pairsErased ->
                    afterPersonalMutation(wordsErased && pairsErased,
                            R.string.personal_dictionary_erase_failed)
                }
            }
        """.trimIndent()
        assertFalse("a global erase that forgets the emoji must not satisfy the check",
            eraseWithoutTheEmoji.contains("emojiController.eraseAll(subtypeIds)"))

        val deleteWithoutTheStore = "controller.removeEntry(row.subtypeId) { }  // deletes a file"
        assertFalse("a removal that bypasses the store must not satisfy the check",
            deleteWithoutTheStore.contains(".forget(word, emoji)"))

        val namesTheFile = "The file personal-emoji-tt_RU-s1-f1.tpersem could not be read."
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
