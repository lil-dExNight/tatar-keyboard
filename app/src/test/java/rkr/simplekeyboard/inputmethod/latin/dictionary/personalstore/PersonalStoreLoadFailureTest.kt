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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe

/**
 * The two ways a personal store's first read can fail, for each of the six stores. A file that
 * fails validation goes to quarantine and the store opens empty. Any other failure (an I/O error,
 * an unexpected exception) leaves the store not loaded: nothing is learned or written, the file on
 * disk keeps its bytes, and the next prime reads it again.
 */
class PersonalStoreLoadFailureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val subtype = PersonalSubtypes.TATAR_RU

    @Test
    fun aDirectorySyncErrorDuringLoadNeverLetsAWriteReplaceTheFile() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val before = contentsOf(directory)
            // A stale temp makes the open's cleanup sync the directory, and that sync throws.
            File(directory, harness.tempPrefix + "stale.tmp").writeBytes(byteArrayOf(1))
            val ops = FaultOps(failingSyncs = 1)
            val failing = store(harness, directory, ops = ops)

            harness.prime(failing)
            harness.tryToChange(failing)

            assertFalse("${harness.name}: nothing is published", harness.hasContent(failing))
            assertDirectoryUnchanged(harness.name, before, directory)

            harness.prime(failing)

            assertTrue("${harness.name}: the next prime reads the file", harness.hasContent(failing))
            assertArrayEquals(
                "${harness.name}: and the original bytes are still there",
                before.getValue(harness.fileName), File(directory, harness.fileName).readBytes(),
            )
        }
    }

    @Test
    fun anUnexpectedExceptionDuringLoadAlsoLeavesTheStoreNotLoaded() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val before = contentsOf(directory)
            var failuresLeft = 1
            val provider = PersonalDirectoryProvider {
                if (failuresLeft-- > 0) throw IllegalStateException("no directory yet")
                directory
            }
            val failing = store(harness, directory, provider = provider)

            harness.prime(failing)
            harness.tryToChange(failing)

            assertFalse("${harness.name}: nothing is published", harness.hasContent(failing))
            assertDirectoryUnchanged(harness.name, before, directory)

            harness.prime(failing)

            assertTrue("${harness.name}: the next prime reads the file", harness.hasContent(failing))
        }
    }

    @Test
    fun anUnreadableFileIsNotQuarantinedAndNotOverwritten() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val file = File(directory, harness.fileName)
            val bytes = file.readBytes()
            assertTrue(file.setReadable(false))
            // Running as root ignores the permission bits; the read error cannot be staged then.
            assumeFalse(file.canRead())
            try {
                val failing = store(harness, directory)

                harness.prime(failing)
                harness.tryToChange(failing)

                assertFalse("${harness.name}: nothing is published", harness.hasContent(failing))
                assertFalse(
                    "${harness.name}: an I/O error is not a damaged file",
                    File(directory, harness.fileName + QUARANTINE_SUFFIX).exists(),
                )
                assertTrue(file.setReadable(true))
                assertArrayEquals("${harness.name}: the file is untouched", bytes, file.readBytes())
            } finally {
                file.setReadable(true)
            }
        }
    }

    @Test
    fun aFileThatFailsValidationIsQuarantinedAndTheStoreOpensEmpty() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val file = File(directory, harness.fileName)
            val corrupt = file.readBytes()
            corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1].toInt() xor 0x7f).toByte()
            file.writeBytes(corrupt)
            val reopened = store(harness, directory)

            harness.prime(reopened)

            assertFalse("${harness.name}: the damaged file left its place", file.exists())
            assertArrayEquals(
                "${harness.name}: and sits in quarantine, byte for byte",
                corrupt, File(directory, harness.fileName + QUARANTINE_SUFFIX).readBytes(),
            )
            assertFalse("${harness.name}: the store opens empty", harness.hasContent(reopened))
            harness.seed(reopened)
            assertTrue("${harness.name}: and is loaded, so it learns again", harness.hasContent(reopened))
        }
    }

    @Test
    fun anErasureBeforeTheFirstUnlockDoesNotLetLearningReplaceTheFile() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            var unlocked = false
            val gated = store(harness, directory, unlockGate = { unlocked })

            harness.clearAll(gated)
            unlocked = true
            harness.seedOther(gated)

            assertTrue(
                "${harness.name}: the saved entry survives the refused erasure",
                harness.holdsSeed(File(directory, harness.fileName)),
            )
        }
    }

    // ---- per-store harnesses ------------------------------------------------------------------

    /** What the shared tests need from one store type. */
    private abstract inner class Harness(val name: String, val fileName: String, val tempPrefix: String) {
        abstract fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
        ): Any

        /** Saves one entry through the store's ordinary path, so a valid file exists. */
        abstract fun seed(store: Any)

        /** Saves a different entry, through learning where the store learns. */
        abstract fun seedOther(store: Any)

        /** Every write the store offers: learning, the user's own edits and the flush. */
        abstract fun tryToChange(store: Any)

        abstract fun hasContent(store: Any): Boolean

        abstract fun clearAll(store: Any)

        /** Whether [file] still holds the entry [seed] saved. */
        fun holdsSeed(file: File): Boolean {
            val probe = create({ file.parentFile!! }, FaultOps(0), { true })
            prime(probe)
            return seedPresent(probe)
        }

        abstract fun prime(store: Any)

        abstract fun seedPresent(store: Any): Boolean
    }

    private fun harnesses(): List<Harness> = listOf(words(), pairs(), emoji(), sentenceStarts(),
        refusals(), shortcuts())

    /** Runs [check] for every store and reports every store that failed, not only the first. */
    private fun forEachStore(check: (Harness) -> Unit) {
        val failures = ArrayList<String>()
        for (harness in harnesses()) {
            try {
                check(harness)
            } catch (failure: AssertionError) {
                failures.add(failure.message ?: harness.name)
            }
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
    }

    private fun words() = object : Harness("words", TpersFormat.personalFileName(subtype), ".personal-") {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            PersonalDictionaryStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
                unlockGate = unlockGate)

        private fun Any.s() = this as PersonalDictionaryStore
        override fun seed(store: Any) = store.s().addManually("абыйлар")
        override fun seedOther(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteCompletion("дөнья")
        }
        override fun tryToChange(store: Any) {
            val s = store.s()
            repeat(PendingCounters.LEARN_THRESHOLD) { s.noteCompletion("дөнья") }
            s.addManually("китап")
            s.noteAcceptedSuggestion("абыйлар")
            s.forget("абыйлар")
            s.restoreQuarantine()
            s.flush()
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = store.s().snapshot.indexOfNormalized("абыйлар") >= 0
    }

    private fun pairs() = object : Harness(
        "pairs", TpersbFormat.personalBigramsFileName(subtype), ".personal-bigrams-",
    ) {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            PersonalBigramStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
                contextMembership = { _, _ -> true }, unlockGate = unlockGate)

        private fun Any.s() = this as PersonalBigramStore
        override fun seed(store: Any) = repeat(PersonalBigramStore.LEARN_THRESHOLD) {
            store.s().notePair("китап", "укыйм")
        }
        override fun seedOther(store: Any) = repeat(PersonalBigramStore.LEARN_THRESHOLD) {
            store.s().notePair("матур", "дөнья")
        }
        override fun tryToChange(store: Any) {
            val s = store.s()
            repeat(PersonalBigramStore.LEARN_THRESHOLD) { s.notePair("матур", "дөнья") }
            s.noteAcceptedPrediction("китап", "укыйм")
            s.forget("китап", "укыйм")
            s.restoreQuarantine()
            s.flush()
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.successorRawFormAt(it) == "укыйм" }
    }

    private fun emoji() = object : Harness(
        "emoji", TpersemFormat.personalEmojiFileName(subtype), ".personal-emoji-",
    ) {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            PersonalEmojiStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
                unlockGate = unlockGate)

        private fun Any.s() = this as PersonalEmojiStore
        override fun seed(store: Any) = repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
            store.s().noteObservation("сәләм", "☀️")
        }
        override fun seedOther(store: Any) = repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
            store.s().noteObservation("бәйрәм", "🎉")
        }
        override fun tryToChange(store: Any) {
            val s = store.s()
            repeat(PersonalEmojiStore.LEARN_THRESHOLD) { s.noteObservation("бәйрәм", "🎉") }
            s.noteUse("сәләм", "☀️")
            s.forget("сәләм", "☀️")
            s.restoreQuarantine()
            s.flush()
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.emojiAt(it) == "☀️" }
    }

    private fun sentenceStarts() = object : Harness(
        "sentence starts", TsentFormat.sentStartFileName(subtype), ".personal-",
    ) {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            SentStartOverlayStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
                unlockGate = unlockGate)

        private fun Any.s() = this as SentStartOverlayStore
        override fun seed(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteSentenceStart("Сәлам")
        }
        override fun seedOther(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteSentenceStart("Бүген")
        }
        override fun tryToChange(store: Any) {
            val s = store.s()
            repeat(PendingCounters.LEARN_THRESHOLD) { s.noteSentenceStart("Бүген") }
            s.noteSentenceStart("Сәлам")
            s.flush()
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = "Сәлам" in store.s().snapshot.topWords()
    }

    private fun refusals() = object : Harness(
        "refused corrections", TrefFormat.refusedCorrectionsFileName(subtype), ".personal-",
    ) {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            RefusedCorrectionStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
                unlockGate = unlockGate)

        private fun Any.s() = this as RefusedCorrectionStore
        override fun seed(store: Any) = store.s().noteRefusal("китаб", "китап")
        override fun seedOther(store: Any) = store.s().noteRefusal("дуньа", "дөнья")
        override fun tryToChange(store: Any) {
            store.s().noteRefusal("дуньа", "дөнья")
            store.s().noteRefusal("китаб", "китап")
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = (0 until store.s().snapshot.size).any { store.s().snapshot.typedWordAt(it) == "китаб" }
    }

    private fun shortcuts() = object : Harness("text shortcuts", TcutFormat.shortcutsFileName(), ".personal-") {
        override fun create(provider: PersonalDirectoryProvider, ops: DurableFileOps, unlockGate: () -> Boolean) =
            TextShortcutStore(provider, ops, opener, roomy, { 1000L }, direct, unlockGate = unlockGate)

        private fun Any.s() = this as TextShortcutStore
        override fun seed(store: Any) = store.s().put("кт", "китап")
        override fun seedOther(store: Any) = store.s().put("дн", "дөнья")
        override fun tryToChange(store: Any) {
            store.s().put("дн", "дөнья")
            store.s().remove("кт")
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = store.s().snapshot.expansionFor("кт") == "китап"
    }

    // ---- helpers --------------------------------------------------------------------------------

    private val direct = Executor { it.run() }
    private val opener = PersonalOutputOpener { temp -> FileOutputStream(temp) }
    private val roomy = SpaceProbe { Long.MAX_VALUE }

    private fun newPersonalDir(): File =
        File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }

    private fun store(
        harness: Harness,
        directory: File,
        ops: DurableFileOps = FaultOps(0),
        provider: PersonalDirectoryProvider = PersonalDirectoryProvider { directory },
        unlockGate: () -> Boolean = { true },
    ): Any = harness.create(provider, ops, unlockGate)

    private fun contentsOf(directory: File): Map<String, ByteArray> =
        directory.listFiles()!!.associate { it.name to it.readBytes() }

    private fun assertDirectoryUnchanged(name: String, before: Map<String, ByteArray>, directory: File) {
        val after = contentsOf(directory)
        assertEquals("$name: no file is created or removed", before.keys, after.keys)
        for ((fileName, bytes) in before) {
            assertArrayEquals("$name: $fileName keeps its bytes", bytes, after.getValue(fileName))
        }
    }

    /** Performs every durable op for real; the first [failingSyncs] directory syncs throw. */
    private class FaultOps(private var failingSyncs: Int) : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }

        override fun syncDirectory(directory: File) {
            if (failingSyncs > 0) {
                failingSyncs--
                throw IOException("sync failed")
            }
        }

        override fun delete(file: File): Boolean = file.delete()
    }

    private companion object {
        const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
