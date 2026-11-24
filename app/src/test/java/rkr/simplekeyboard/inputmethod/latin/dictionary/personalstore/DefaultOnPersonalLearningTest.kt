/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionaryReader
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils

/**
 * Default-on learning through the production gate and store: a clean word graduates exactly at
 * [PendingCounters.LEARN_THRESHOLD], while every field, lock and pause veto leaves no trace.
 * [Settings.PERSONAL_DICTIONARY_DEFAULT] supplies the absent-key default; its reader wiring is
 * pinned by `PersonalDictionarySettingsTest`. The two sink steps are explicit because
 * [PersonalLearning] requires a `Context`; `PersonalLearningGatesTest` pins the production sink.
 */
class DefaultOnPersonalLearningTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val word = "гүзәлия"
    private val subtype = PersonalSubtypes.TATAR_RU

    /** EditorInfo signals with every other field factor permissive. */
    private enum class Field(val inputType: Int, val imeOptions: Int) {
        ORDINARY(InputType.TYPE_CLASS_TEXT, 0),
        PASSWORD(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0),
        NO_PERSONALIZED_LEARNING(
            InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        ),
        POSTAL_ADDRESS(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            0,
        );

        /** `InputAttributes.mShouldShowSuggestions`: text class and not password-classified. */
        val allowsSuggestions: Boolean
            get() = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                !InputTypeUtils.isPasswordInputType(inputType) &&
                !InputTypeUtils.isVisiblePasswordInputType(inputType)

        /** `InputAttributes.readNoPersonalizedLearning`. */
        val noPersonalizedLearning: Boolean
            get() = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        /** `InputAttributes.mIsPostalAddressField`. */
        val postalAddress: Boolean
            get() = (inputType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS

        /** The field half of `LatinIME.isSuggestionsEligible`, other factors permissive. */
        val suggestionsEligible: Boolean
            get() = allowsSuggestions && !noPersonalizedLearning
    }

    /** One field/session combination and its expected learning outcome. */
    private enum class Variant(
        val field: Field,
        val userUnlocked: Boolean = true,
        val incognito: Boolean = false,
        val personalDictionaryOff: Boolean = false,
        val learnable: Boolean,
    ) {
        ORDINARY(Field.ORDINARY, learnable = true),
        PASSWORD(Field.PASSWORD, learnable = false),
        NO_PERSONALIZED_LEARNING(Field.NO_PERSONALIZED_LEARNING, learnable = false),
        POSTAL_ADDRESS(Field.POSTAL_ADDRESS, learnable = false),
        LOCKED(Field.ORDINARY, userUnlocked = false, learnable = false),
        PAUSED(Field.ORDINARY, incognito = true, learnable = false),
        PERSONAL_DICTIONARY_OFF(Field.ORDINARY, personalDictionaryOff = true, learnable = false),
    }

    /**
     * One field session over a real store: the production conjunction over the row's factors, then
     * the store call the production sink makes when the predicate holds.
     */
    private inner class Session(var variant: Variant) {
        val directory: File = newPersonalDir()
        val store: PersonalDictionaryStore = newStore(directory)

        val predicate = PersonalLearningPredicate {
            PersonalLearningGates.mayLearn(
                suggestionsEligible = variant.field.suggestionsEligible,
                personalDictionaryOn = !variant.personalDictionaryOff &&
                    Settings.PERSONAL_DICTIONARY_DEFAULT,
                userUnlocked = variant.userUnlocked,
                postalAddressField = variant.field.postalAddress,
                incognito = variant.incognito,
            )
        }

        /** `PersonalLearning`'s completion path: the predicate first, then the store. */
        fun complete(times: Int = 1) {
            repeat(times) {
                if (predicate.mayLearn()) store.noteCompletion(word)
            }
        }

        /** `PersonalLearning`'s flush path, gated like the completion. */
        fun finish() {
            if (predicate.mayLearn()) store.flush()
        }

        /** The same store after the user leaves the closed state. */
        fun reopen() {
            variant = Variant.ORDINARY
        }
    }

    @Test
    fun anOrdinaryFieldWithTheDefaultSettingLearnsOnlyAtTheLearnThreshold() {
        val session = Session(Variant.ORDINARY)
        assertTrue("the ordinary row must be open or the case proves nothing",
            session.predicate.mayLearn())

        // The exact threshold value is pinned by PendingCountersTest; this case pins the path.
        session.complete(PendingCounters.LEARN_THRESHOLD - 1)
        assertTrue("no word below the threshold", session.store.snapshot.isEmpty)
        assertEquals("no dictionary write below the threshold", 0, session.store.writeCount)
        assertTrue("no plaintext on disk below the threshold",
            savedWords(session.directory).isEmpty())

        session.complete()
        assertEquals("the threshold saves the word", 1, session.store.snapshot.size)
        assertEquals("as typed", word, session.store.snapshot.rawFormAt(0))
        assertEquals("the saved word reached the disk", listOf(word), savedWords(session.directory))
        assertEquals(1, session.store.writeCount)
    }

    @Test
    fun everyClosedGateLeavesNoLearnedWordAfterTheThresholdObservations() {
        for (variant in Variant.values().filterNot { it.learnable }) {
            val session = Session(variant)
            assertFalse("${variant.name}: the predicate must be closed", session.predicate.mayLearn())

            session.complete(PendingCounters.LEARN_THRESHOLD)
            session.finish()

            assertTrue("${variant.name}: no learned word", session.store.snapshot.isEmpty)
            assertEquals("${variant.name}: no dictionary write", 0, session.store.writeCount)
            assertTrue("${variant.name}: nothing reached the disk",
                savedWords(session.directory).isEmpty())
            assertEquals("${variant.name}: no salt or pending file either",
                emptyList<String>(), directoryEntries(session.directory))

            // Non-vacuous: the same store takes the same word through the manual path, so the
            // emptiness above is the gate's work, not a word the store would reject.
            session.store.addManually(word)
            assertEquals("${variant.name}: the store accepts the word when reached",
                1, session.store.snapshot.size)
        }
    }

    @Test
    fun aClosedPeriodIsNotLearnedWhenTheGateReopens() {
        for (variant in Variant.values().filterNot { it.learnable }) {
            val session = Session(variant)
            session.complete(PendingCounters.LEARN_THRESHOLD * 2)
            session.finish()
            assertTrue("${variant.name}: the closed period learned nothing",
                session.store.snapshot.isEmpty)

            session.reopen()
            assertTrue("${variant.name}: the reopened row is open", session.predicate.mayLearn())

            session.complete(PendingCounters.LEARN_THRESHOLD - 1)
            assertTrue("${variant.name}: the closed period left no counter to make up",
                session.store.snapshot.isEmpty)

            session.complete()
            assertEquals("${variant.name}: the threshold counts from the reopen",
                1, session.store.snapshot.size)
        }
    }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun newStore(directory: File): PersonalDictionaryStore = PersonalDictionaryStore(
        subtypeId = subtype,
        directoryProvider = { directory },
        fileOps = TestFileOps,
        outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
        spaceProbe = SpaceProbe { Long.MAX_VALUE },
        clock = { 1000L },
        executor = Executor { it.run() },
        // The store's own unlock gate stays open: the locked row must be closed by the learning
        // predicate, or a dropped predicate factor would hide behind this second gate.
        unlockGate = { true },
    )

    private fun savedWords(directory: File): List<String> {
        val file = File(directory, TpersFormat.personalFileName(subtype))
        if (!file.isFile) return emptyList()
        val dictionary = PersonalDictionaryReader().read(file, subtype)
        return (0 until dictionary.size).map { dictionary.rawFormAt(it) }
    }

    private fun directoryEntries(directory: File): List<String> =
        directory.list()?.sorted() ?: emptyList()

    /** Real file operations without the Android `Os` calls the production ops make. */
    private object TestFileOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) {
                throw IOException("rename failed")
            }
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) = Unit

        override fun delete(file: File): Boolean = file.delete()
    }
}
