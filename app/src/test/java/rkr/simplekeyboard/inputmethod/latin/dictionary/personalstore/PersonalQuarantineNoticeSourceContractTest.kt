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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission `tt-version-1.8.2`, findings B2 and B3 of `docs/SILENT-AUDIT.md`, on the side of them that
 * cannot run off-device: the path from the store's worker to the sentence the user reads.
 *
 * The store half is exercised for real in [PersonalDictionarySilentFailureTest] — the file is really
 * set aside, the notice really fires, the failed removal really answers. What is left is the wiring
 * through `PersonalDictionaries` and `LatinIME`, which needs a live `InputMethodService`, so it is
 * asserted by source in the style this project already uses for both classes. Every predicate here is
 * proved fail-capable against the shape it replaced, in the last test.
 */
class PersonalQuarantineNoticeSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private fun mainFile(relative: String) = File(sourceRoot(), relative).readText()

    private val store by lazy {
        mainFile("java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalDictionaryStore.kt")
    }
    private val owner by lazy {
        mainFile("java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/PersonalDictionaries.kt")
    }
    private val factory by lazy {
        mainFile("java/rkr/simplekeyboard/inputmethod/latin/dictionary/personalstore/AndroidPersonalDictionaryStorage.kt")
    }
    private val ime by lazy { mainFile("java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java") }

    private fun bodyOf(source: String, from: String, to: String) =
        source.substringAfter(from).substringBefore(to)

    // --- B2, the store side ----------------------------------------------------------------------

    @Test
    fun theUnreadableFileIsSetAsideAtTheOneSiteThatUsedToDeleteIt() {
        // `open()` split into the gate and the body when the notice became durable (mission
        // `tt-quarantine`, B5); the validation-failure branch is in the body.
        val load = bodyOf(store, "private fun load() {", "\n    /**")
        assertTrue("the validation-failure branch moves the file", load.contains("quarantine(directory, file)"))
        assertFalse(
            "and the delete that shipped is gone from that branch",
            load.contains("runCatching { deleteFile(directory, file) }"),
        )

        val quarantine = bodyOf(store, "private fun quarantine(directory: File, file: File) {", "\n    private fun quarantineFileName")
        assertTrue("the move replaces whatever occupied the one slot",
            quarantine.contains("fileOps.atomicReplace(file, File(directory, quarantineFileName()))"))
        assertTrue("and is durable, like every other mutation of this directory",
            quarantine.contains("fileOps.syncDirectory(directory)"))
        assertTrue("a move that cannot happen still clears the path the reader looks at",
            quarantine.contains("if (!moved) runCatching { deleteFile(directory, file) }"))
        assertTrue("and the user is marked as owed a notice either way",
            quarantine.contains("writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))"))
    }

    /**
     * B5. The seam is called from ONE place, and that place is every open — not only the open that
     * quarantined something. That is what carries an unspoken notice across a process death.
     */
    @Test
    fun theNoticeIsRaisedFromEveryOpenThatFindsTheMark() {
        val open = bodyOf(store, "private fun open(): Boolean {", "\n    /** The body of [open]")
        assertTrue("this session's loss and a previous session's both raise it",
            open.contains("if (justQuarantined || quarantineNoticeIsMarked()) {"))
        assertTrue(open.contains("quarantineNotice?.onQuarantined()"))
        assertEquals(
            "and nowhere else calls the seam",
            1,
            Regex(Regex.escape("quarantineNotice?.onQuarantined()")).findAll(store).count(),
        )
        assertTrue("the mark is a flag, not text: one byte whose existence is the message",
            store.contains("quarantine-notice-\$subtypeId-s1-f1.flag"))
        assertTrue("and it is spent only once the notice has really been shown",
            bodyOf(store, "fun noticeDelivered() = onWorker {", "\n    /**")
                .contains("deleted { deleteFile(directory, File(directory, quarantineNoticeFileName())) }"))
    }

    @Test
    fun thePendingFileIsReadOncePerOpen() {
        // 2026-09-24 audit, finding 14: the pending file used to be read twice per load — once
        // before the dictionary and once after. readPending REPLACES state, so the second read
        // was pure duplicate I/O; one read, on every path through load, is the contract.
        val load = bodyOf(store, "private fun load() {", "\n    /**")
        assertEquals(1, Regex(Regex.escape("readPending(directory)")).findAll(load).count())
    }

    @Test
    fun theQuarantineSlotIsOnePerLanguageAndCannotBeMistakenForAnythingElse() {
        assertTrue("named from the ordinary name, so there is exactly one per language",
            store.contains("TpersFormat.personalFileName(subtypeId) + QUARANTINE_SUFFIX"))
        val suffix = Regex("""QUARANTINE_SUFFIX = "([^"]+)"""").find(store)?.groupValues?.get(1)
        assertEquals(".quarantine", suffix)
        // Not a name the reader looks for, and not a temp: nothing validates it, nothing sweeps it.
        assertFalse("must not read as a .tpers", suffix!!.endsWith(".tpers"))
        assertFalse("must not read as a temp", suffix.endsWith(".tmp"))
    }

    @Test
    fun erasingEverythingCoversTheQuarantineCopy() {
        val clearAll = bodyOf(store, "fun clearAll(outcome: PersonalMutationOutcome? = null) = onWorker {", "\n    /**")
        assertTrue("its own independent step, like the other three",
            clearAll.contains("deleted { deleteFile(directory, File(directory, quarantineFileName())) }"))
        assertTrue("and a copy left behind sinks the answer",
            clearAll.contains("report(outcome, dictionaryGone && countersGone && saltGone && quarantineGone)"))
        // B5. The mark is not one of the user's words: a mark that would not delete must not turn
        // "your words are gone" into "the erasure failed".
        assertFalse("the mark is not part of the answer",
            clearAll.contains("&& quarantineNoticeGone"))
        assertTrue("but it does go", clearAll.contains("quarantineNoticeFileName()"))
    }

    // --- B3, the store side ----------------------------------------------------------------------

    @Test
    fun theRemovalCannotThrowOutOfTheWorker() {
        val forget = bodyOf(store, "fun forget(word: String, outcome: PersonalMutationOutcome? = null) = onWorker {",
            "/** The body of [forget]")
        assertTrue("the whole body runs inside a try", forget.contains("val removed = try {"))
        assertTrue(forget.contains("removeOnWorker(word)"))
        assertTrue("and one answer leaves it either way", forget.contains("report(outcome, removed)"))

        val body = bodyOf(store, "private fun removeOnWorker(word: String): Boolean {", "\n    /**")
        assertTrue("the delete itself becomes false rather than an exception",
            body.contains("val removed = try {\n            if (candidate.isEmpty) {"))
        assertTrue(body.contains("\n                deleteFile()\n                true\n            } else {\n                writeWhole(candidate)"))
        assertTrue("so the snapshot restore still runs: the word IS still saved",
            body.contains("snapshot = previousSnapshot"))
    }

    // --- the process-wide owner ------------------------------------------------------------------

    @Test
    fun theNoticeWaitsWhenNobodyIsListeningYet() {
        assertTrue("the store is built with the notice wired in",
            owner.contains("AndroidPersonalDictionaryStorage.create(context, subtypeId, executorLocked()) {"))
        assertTrue(owner.contains("notifyQuarantined(subtypeId)"))
        // 2026-09-24 audit, finding 13: one pending notice PER LANGUAGE, taken one at a time —
        // a single shared flag spent every open store's durable mark on the one dialog that was
        // shown, and the other language's loss went unmentioned.
        assertTrue("the notice is remembered, not merely broadcast",
            owner.contains("pendingQuarantineNotices.add(subtypeId)"))
        assertTrue("taken one language at a time, fail-closed when empty",
            owner.contains("pendingQuarantineNotices.firstOrNull() ?: return false"))
        assertTrue("and only THAT language's store clears its durable mark",
            owner.contains("synchronized(lock) { stores[subtypeId] }?.noticeDelivered()"))
        val reset = bodyOf(owner, "internal fun resetForTest() {", "\n}")
        assertTrue("an isolated test starts from nothing", reset.contains("quarantineListener = null"))
        assertTrue(reset.contains("pendingQuarantineNotices.clear()"))
        assertTrue("the factory takes the seam and defaults it, so no other caller changes",
            factory.contains("quarantineNotice: PersonalQuarantineNotice? = null"))
    }

    // --- the IME --------------------------------------------------------------------------------

    @Test
    fun theImeShowsTheNoticeAndConsumesItOnlyWhenItReallyShows() {
        assertTrue("registered beside the erasure listener, and marshalled the same way",
            ime.contains("PersonalDictionaries.setQuarantineListener(\n" +
                "                () -> mHandler.post(this::showPersonalDictionaryUnreadableDialog));"))
        assertTrue("and dropped in onDestroy, because the listener holds this service",
            bodyOf(ime, "public void onDestroy() {", "mSuggestionsController.onDestroy();")
                .contains("PersonalDictionaries.setQuarantineListener(null);"))

        val dialog = bodyOf(ime, "private void showPersonalDictionaryUnreadableDialog() {", "\n    /**")
        val consume = dialog.indexOf("PersonalDictionaries.consumeQuarantineNotice()")
        assertTrue("the notice is consumed", consume >= 0)
        assertTrue("but only after the window checks: otherwise it is spent on nobody",
            dialog.indexOf("windowToken == null") in 0 until consume)
        assertTrue(dialog.contains("R.string.personal_dictionary_unreadable"))
        assertTrue("attached to the input window like every dialog this service owns",
            dialog.contains("attachDialogToInputWindow(dialog, windowToken)"))
    }

    @Test
    fun aNoticeRaisedWithNoWindowUpGetsAnotherChance() {
        val started = bodyOf(ime, "void onStartInputViewInternal(", "if (TRACE) Debug.startMethodTracing")
        assertTrue("the boundary that already exists for the deferred suggestions message",
            started.contains("if (PersonalDictionaries.hasPendingQuarantineNotice()) {"))
        assertTrue(started.contains("mHandler.post(this::showPersonalDictionaryUnreadableDialog);"))
    }

    // --- what the user reads --------------------------------------------------------------------

    @Test
    fun theNoticeIsTranslatedAndNamesNoFilePathOrCause() {
        val english = stringValue("values", "personal_dictionary_unreadable")
        assertTrue("Russian", stringValue("values-ru", "personal_dictionary_unreadable").isNotEmpty())
        assertTrue("Tatar", stringValue("values-tt", "personal_dictionary_unreadable").isNotEmpty())

        // The same rule as the other three failure messages: it may be shown over any app.
        assertFalse("no format argument, so nothing can be interpolated into it", english.contains("%"))
        for (forbidden in listOf("file", "path", "error", "tpers", "/")) {
            assertFalse("the notice must not name '$forbidden'", english.lowercase().contains(forbidden))
        }
        assertTrue("it says the list is empty", english.contains("empty"))
    }

    private fun stringValue(qualifier: String, name: String): String {
        val xml = File(sourceRoot(), "res/$qualifier/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("$name missing from $qualifier")
    }

    // --- fail-capability ------------------------------------------------------------------------

    /**
     * Every predicate above is worth its line only if the shape it replaced makes it red. These are
     * the shapes that actually shipped in 1.8.1, fed to the same checks.
     */
    @Test
    fun thePredicatesRejectTheShapesTheyReplaced() {
        val shippedOpen = "if (validated == null) {\n    runCatching { deleteFile(directory, file) }\n"
        assertFalse("the silent delete must not satisfy the quarantine check",
            shippedOpen.contains("quarantine(directory, file)"))

        val shippedForget = "val removed = if (candidate.isEmpty) deleteFile() else writeWhole(candidate)"
        assertFalse("the unguarded delete must not satisfy the try check",
            shippedForget.contains("val removed = try {"))

        val shippedClearAll = "outcome?.onFinished(dictionaryGone && countersGone && saltGone)"
        assertFalse("three booleans must not satisfy the four-boolean check",
            shippedClearAll.contains("&& quarantineGone"))

        // Mission `tt-quarantine`, B3 and B5: the shapes 1.8.2 shipped, fed to the checks above.
        val unguardedAnswer = "outcome?.onFinished(removed)"
        assertFalse("the callback outside the try must not satisfy the guarded-answer check",
            unguardedAnswer.contains("report(outcome, removed)"))

        val deadBranch = "val removed = try {\n            if (candidate.isEmpty) deleteFile() else writeWhole(candidate)"
        assertFalse("the branch on a value that could only be true must not satisfy the honest shape",
            deadBranch.contains("val removed = try {\n            if (candidate.isEmpty) {"))

        val processLocalMark = "quarantineNotice?.onQuarantined()"
        assertFalse("a notice raised straight from the quarantine must not satisfy the durable-mark check",
            processLocalMark.contains("writeBytesDurably(directory, File(directory, quarantineNoticeFileName()), ByteArray(1))"))

        val volatileFlagOnly = "quarantinePending = false\n        return true"
        assertFalse("clearing the in-memory flag alone must not satisfy the durable-clear check",
            volatileFlagOnly.contains("store.noticeDelivered()"))

        val shippedIme = "PersonalDictionaries.setErasureListener(null);"
        assertFalse("the erasure listener alone must not satisfy the quarantine-listener check",
            shippedIme.contains("PersonalDictionaries.setQuarantineListener(null);"))

        val consumeBeforeTheChecks =
            "private void showPersonalDictionaryUnreadableDialog() {\n" +
                "    if (!PersonalDictionaries.consumeQuarantineNotice()) return;\n" +
                "    if (windowToken == null) return;\n"
        val consume = consumeBeforeTheChecks.indexOf("PersonalDictionaries.consumeQuarantineNotice()")
        assertFalse("consuming before the window checks must not satisfy the ordering check",
            consumeBeforeTheChecks.indexOf("windowToken == null") in 0 until consume)
    }
}
