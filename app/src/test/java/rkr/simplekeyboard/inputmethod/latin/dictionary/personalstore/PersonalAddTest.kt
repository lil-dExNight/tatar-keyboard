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
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryReader
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * "Add «X» to the personal dictionary" from the strip's long press: the routing decision (forget
 * vs add vs explain), the eligibility check against the manual-add filter, and the store write
 * path with a fault injected. The dialog itself is source-contract, like in [PersonalForgetTest].
 */
class PersonalAddTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    // --- the routing decision -------------------------------------------------------------------

    @Test
    fun aSavedWordRoutesToForgetEvenWhenItWouldAlsoBeAddable() {
        // Saved wins over addable: the user means "this word is mine", and the forget dialog is
        // the one that can act on it.
        assertEquals(PersonalAdd.Action.FORGET, PersonalAdd.actionFor("гүзәл", null))
        assertEquals(PersonalAdd.Action.FORGET, PersonalAdd.actionFor("гүзәл", "гүзәл"))
    }

    @Test
    fun anUnsavedAddableWordRoutesToAdd() {
        assertEquals(PersonalAdd.Action.ADD, PersonalAdd.actionFor(null, "китап"))
    }

    @Test
    fun anUnsavedUnaddableWordRoutesToTheExplanation() {
        assertEquals(PersonalAdd.Action.NOT_ADDABLE, PersonalAdd.actionFor(null, null))
    }

    // --- the eligibility check, against the real per-language alphabets --------------------------

    @Test
    fun theStripWordIsJudgedByTheManualAddFilter() {
        // Lower, Initial Caps and ALL CAPS normalize to the same saved lookup key; the raw form
        // keeps its casing.
        assertEquals("гүзәл", PersonalAdd.addableNormalizedForm(subtype, "гүзәл"))
        assertEquals("гүзәл", PersonalAdd.addableNormalizedForm(subtype, "Гүзәл"))
        assertEquals("гүзәл", PersonalAdd.addableNormalizedForm(subtype, "ГҮЗӘЛ"))
        // The strip never shows surrounding whitespace, but the check trims like the screen does.
        assertEquals("китап", PersonalAdd.addableNormalizedForm(subtype, "  китап  "))
        // Too short, mixed casing, characters outside the language's alphabet: all rejected,
        // exactly as the settings screen's "Add word" rejects them.
        assertNull(PersonalAdd.addableNormalizedForm(subtype, "су"))
        assertNull(PersonalAdd.addableNormalizedForm(subtype, "ГүЗәл"))
        assertNull(PersonalAdd.addableNormalizedForm(subtype, "су2"))
        // A Tatar word is not addable to the Russian dictionary: ү and ә are outside its
        // alphabet. (The Tatar alphabet is a superset of the Russian one, so the reverse passes.)
        assertNull(PersonalAdd.addableNormalizedForm(PersonalSubtypes.RUSSIAN, "гүзәл"))
        assertEquals("машина", PersonalAdd.addableNormalizedForm(PersonalSubtypes.RUSSIAN, "машина"))
    }

    @Test
    fun aLanguageWithoutAnAlphabetAcceptsNothing() {
        assertNull(PersonalAdd.addableNormalizedForm("en", "word"))
    }

    // --- the store write path ---------------------------------------------------------------------

    @Test
    fun aConfirmedAddLandsInTheSnapshotAndOnDisk() {
        val directory = newPersonalDir()
        val store = store(directory, RealOps())
        var failed = false

        PersonalAdd.confirmAdd(store, "Гүзәл") { failed = true }

        assertFalse(failed)
        val snapshot = store.snapshot
        val index = snapshot.indexOfNormalized("гүзәл")
        assertTrue("the added word is published at once", index >= 0)
        assertEquals("the raw form is saved as shown", "Гүзәл", snapshot.rawFormAt(index))
        // And it survived the whole-file write: a fresh read of the file sees it.
        val onDisk = PersonalDictionaryReader().read(
            File(directory, TpersFormat.personalFileName(subtype)), subtype)
        assertTrue(onDisk.indexOfNormalized("гүзәл") >= 0)
    }

    @Test
    fun aRejectedWordAnswersFalseAndWritesNothing() {
        val directory = newPersonalDir()
        val store = store(directory, RealOps())
        var failed = false

        PersonalAdd.confirmAdd(store, "!!") { failed = true }

        assertTrue("the filter's no reaches the caller", failed)
        assertTrue(store.snapshot.isEmpty)
        assertFalse(File(directory, TpersFormat.personalFileName(subtype)).exists())
    }

    @Test
    fun aWriteFaultAnswersFalseAndKeepsThePriorFile() {
        val directory = newPersonalDir()
        store(directory, RealOps()).addManually("абыйлар")
        val faulting = store(directory, object : RealOps() {
            override fun atomicReplace(source: File, destination: File) =
                throw IOException("replace failed")
        })
        var failed = false

        PersonalAdd.confirmAdd(faulting, "сүзлек") { failed = true }

        assertTrue(failed)
        // The word that was there survives; the one that failed is not half-saved.
        val snapshot = faulting.snapshot
        assertTrue(snapshot.indexOfNormalized("абыйлар") >= 0)
        assertEquals(-1, snapshot.indexOfNormalized("сүзлек"))
        val onDisk = PersonalDictionaryReader().read(
            File(directory, TpersFormat.personalFileName(subtype)), subtype)
        assertEquals(1, onDisk.size)
        assertTrue(onDisk.indexOfNormalized("абыйлар") >= 0)
    }

    // --- the dialog side, pinned by source like the forget one -------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val ime by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()
    }

    @Test
    fun theAddDialogNamesTheWordAndReportsFailureLikeTheForgetOne() {
        val dialog = ime.substringAfter("private void showAddPersonalWordDialog(")
            .substringBefore("private void showPersonalWordNotAddableDialog(")
        assertTrue("the title takes the word as it will be saved",
            dialog.contains("getString(R.string.personal_dictionary_add_title, trimmed)"))
        assertTrue(dialog.contains("R.string.personal_dictionary_add_action"))
        assertTrue(dialog.contains("PersonalAdd.confirmAdd(this, subtypeId, trimmed,"))
        assertTrue("the failure is marshalled onto the UI thread",
            dialog.contains("() -> mHandler.post(this::showPersonalAddFailedDialog)"))
        assertTrue("the title names a personal word, so the window is secured",
            dialog.contains("DialogUtils.securePersonalContent(dialog)"))
        assertTrue(dialog.contains("attachDialogToInputWindow(dialog, windowToken)"))
        assertFalse("a long press must never insert text",
            dialog.contains("commitText") || dialog.contains("commitSuggestion"))
    }

    @Test
    fun theEmojiAndClipCellsNeverReachTheRouter() {
        val listener = ime.substringAfter("strip.setOnSuggestionLongPressListener(")
        val routerCall = listener.indexOf("onStripWordLongPress(suggestion)")
        assertTrue(routerCall > 0)
        assertTrue("the emoji check runs first",
            listener.indexOf("containsAnyLetter(suggestion)") in 0 until routerCall)
        assertTrue("and the clip cell is excluded before the router too",
            listener.indexOf("isRecentClipCell(suggestion)") in 0 until routerCall)
    }

    @Test
    fun aWordThatCannotBeSavedGetsAnAnswerNamingTheLanguageAlphabet() {
        val dialog = ime.substringAfter("private void showPersonalWordNotAddableDialog(")
            .substringBefore("private void showPersonalDictionaryNoLanguageDialog(")
        assertTrue(dialog.contains("R.string.personal_dictionary_add_rejected_ru"))
        assertTrue(dialog.contains("R.string.personal_dictionary_add_rejected"))
        assertTrue("the Russian message goes to the Russian store's language",
            dialog.contains("PersonalSubtypes.RUSSIAN.equals(subtypeId)"))
    }

    // --- helpers ------------------------------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        directory: File,
        ops: DurableFileOps,
    ): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = ops,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = SpaceProbe { Long.MAX_VALUE },
        clock = { 1000L },
        executor = directExecutor,
    )

    /** The real file operations with directory fsync skipped, overridable one fault at a time. */
    private open inner class RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) {
            fileDescriptor.sync()
        }

        override fun atomicRename(source: File, destination: File) {
            if (!source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }
}
