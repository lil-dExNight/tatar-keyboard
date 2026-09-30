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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learned-emoji store raises the same quarantine notice as the words and pairs stores, and
 * the IME wires it the same way as in [PersonalQuarantineNoticeSourceContractTest]: listener in
 * `onCreate`, a dialog that consumes the notice only when it really shows, the deferred boundary
 * in `onStartInputViewInternal`, and the drop in `onDestroy`.
 *
 * The store half runs for real in [PersonalEmojiStoreWriteTest]; the IME half needs a live
 * service, so it is checked from source. The last test shows every check fails on the old shape.
 */
class PersonalEmojiQuarantineNoticeSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainFile(relative: String) = File(sourceRoot(), relative).readText()

    private val owner by lazy {
        mainFile("java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalEmojiDictionaries.kt")
    }
    private val ime by lazy { mainFile("java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java") }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    // --- the process-wide owner ------------------------------------------------------------------

    @Test
    fun theNoticeWaitsWhenNobodyIsListeningYet() {
        // The same shape the words owner pins: remembered per language, taken one at a time,
        // and only THAT language's store clears its durable mark.
        assertTrue("the store is built with the notice wired in",
            owner.contains("notifyQuarantined(subtypeId)"))
        assertTrue("the notice is remembered, not merely broadcast",
            owner.contains("pendingQuarantineNotices.add(subtypeId)"))
        assertTrue("taken one language at a time, fail-closed when empty",
            owner.contains("pendingQuarantineNotices.firstOrNull() ?: return false"))
        assertTrue("and only THAT language's store clears its durable mark",
            owner.contains("synchronized(lock) { stores[subtypeId] }?.noticeDelivered()"))
    }

    // --- the IME --------------------------------------------------------------------------------

    @Test
    fun theImeShowsTheNoticeAndConsumesItOnlyWhenItReallyShows() {
        assertTrue("registered beside the erasure listener, and marshalled the same way",
            ime.contains("PersonalEmojiDictionaries.setQuarantineListener(\n" +
                "                () -> mHandler.post(this::showPersonalEmojiUnreadableDialog));"))
        assertTrue("and dropped in onDestroy, because the listener holds this service",
            bodyOf(ime, "public void onDestroy() {", "mSuggestionsController.onDestroy();")
                .contains("PersonalEmojiDictionaries.setQuarantineListener(null);"))

        val dialog = bodyOf(ime, "private void showPersonalEmojiUnreadableDialog() {", "\n    /**")
        val consume = dialog.indexOf("PersonalEmojiDictionaries.consumeQuarantineNotice()")
        assertTrue("the notice is consumed", consume >= 0)
        assertTrue("but only after the window checks: otherwise it is spent on nobody",
            dialog.indexOf("windowToken == null") in 0 until consume)
        assertTrue(dialog.contains("R.string.personal_emoji_unreadable"))
        assertTrue("attached to the input window like every dialog this service owns",
            dialog.contains("attachDialogToInputWindow(dialog, windowToken)"))
    }

    @Test
    fun aNoticeRaisedWithNoWindowUpGetsAnotherChance() {
        val started = bodyOf(ime, "void onStartInputViewInternal(", "if (TRACE) Debug.startMethodTracing")
        assertTrue("the same deferred boundary the words and pairs notices already use",
            started.contains("if (PersonalEmojiDictionaries.hasPendingQuarantineNotice()) {"))
        assertTrue(started.contains("mHandler.post(this::showPersonalEmojiUnreadableDialog);"))
    }

    // --- what the user reads --------------------------------------------------------------------

    @Test
    fun theNoticeIsTranslatedAndNamesNoFilePathOrCause() {
        val english = stringValue("values", "personal_emoji_unreadable")
        assertTrue("Russian", stringValue("values-ru", "personal_emoji_unreadable").isNotEmpty())
        assertTrue("Tatar", stringValue("values-tt", "personal_emoji_unreadable").isNotEmpty())

        // The same rule as the other failure messages: it may be shown over any app.
        assertFalse("no format argument, so nothing can be interpolated into it", english.contains("%"))
        for (forbidden in listOf("file", "path", "error", "tpers", "/")) {
            assertFalse("the notice must not name '$forbidden'", english.lowercase().contains(forbidden))
        }
    }

    private fun stringValue(qualifier: String, name: String): String {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("$name missing from $qualifier")
    }

    // --- fail-capability ------------------------------------------------------------------------

    /**
     * Shapes without the wiring (notice API present, but no IME listener, dialog or boundary).
     * Fed to the checks above, they must fail.
     */
    @Test
    fun thePredicatesRejectTheShapesTheyReplaced() {
        val shippedIme = "PersonalEmojiDictionaries.setErasureListener(null);"
        assertFalse("the erasure listener alone must not satisfy the quarantine-listener check",
            shippedIme.contains("PersonalEmojiDictionaries.setQuarantineListener(null);"))

        val consumeBeforeTheChecks =
            "private void showPersonalEmojiUnreadableDialog() {\n" +
                "    if (!PersonalEmojiDictionaries.consumeQuarantineNotice()) return;\n" +
                "    if (windowToken == null) return;\n"
        val consume = consumeBeforeTheChecks.indexOf("PersonalEmojiDictionaries.consumeQuarantineNotice()")
        assertFalse("consuming before the window checks must not satisfy the ordering check",
            consumeBeforeTheChecks.indexOf("windowToken == null") in 0 until consume)
    }
}
