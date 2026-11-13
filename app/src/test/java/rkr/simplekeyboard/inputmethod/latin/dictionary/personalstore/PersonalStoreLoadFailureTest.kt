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
 * A read error leaves the store not loaded: nothing is learned or written, the file on disk keeps
 * its bytes, and the next prime reads it again. A file that fails validation goes to quarantine and
 * the store opens empty only after the move itself succeeds.
 *
 * The same harness also pins the two ways an atomic replace can be followed by a failure: a later
 * write must start from the bytes actually installed, and a restore must reopen and honestly report
 * that installed state rather than retaining its pre-import snapshot.
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

    @Test
    fun aFailedQuarantineMoveBlocksWritesUntilPrimeRetriesIt() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val file = File(directory, harness.fileName)
            val damaged = file.readBytes()
            damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0x7f).toByte()
            file.writeBytes(damaged)
            val quarantine = File(directory, harness.fileName + QUARANTINE_SUFFIX)
            var notices = 0
            val ops = FaultOps(0, failingQuarantineMoves = 1)
            val failing = store(
                harness,
                directory,
                ops,
                onQuarantined = PersonalQuarantineNotice { notices++ },
            )

            harness.prime(failing)
            harness.tryToChange(failing)

            assertEquals(
                "${harness.name}: a failed move still raises its notice",
                if (harness.supportsQuarantineNotice) 1 else 0,
                notices,
            )
            assertArrayEquals("${harness.name}: no later write replaces the damaged original",
                damaged, file.readBytes())
            assertFalse("${harness.name}: no copy was made", quarantine.exists())
            assertFalse("${harness.name}: the damaged state is not published", harness.hasContent(failing))

            harness.prime(failing)
            assertFalse("${harness.name}: the retry moves the original", file.exists())
            assertArrayEquals("${harness.name}: the retry preserves every byte",
                damaged, quarantine.readBytes())

            harness.seedOther(failing)
            assertTrue("${harness.name}: learning resumes after the retry", harness.hasContent(failing))
        }
    }

    @Test
    fun aQuarantineDirectorySyncFailureKeepsTheMovedOriginalRecoverable() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val file = File(directory, harness.fileName)
            val damaged = file.readBytes()
            damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0x7f).toByte()
            file.writeBytes(damaged)
            val quarantine = File(directory, harness.fileName + QUARANTINE_SUFFIX)
            val ops = FaultOps(0)
            val store = store(harness, directory, ops)
            ops.failSyncAfterTheNextReplace(quarantine.name)

            harness.prime(store)

            assertFalse("${harness.name}: the rename landed", file.exists())
            assertArrayEquals("${harness.name}: the original remains recoverable",
                damaged, quarantine.readBytes())
            assertFalse("${harness.name}: the empty state opens safely", harness.hasContent(store))

            harness.seedOther(store)
            assertTrue("${harness.name}: new learning cannot overwrite the quarantined original",
                harness.hasContent(store))
            assertArrayEquals("${harness.name}: the quarantined bytes are still intact",
                damaged, quarantine.readBytes())
        }
    }

    @Test
    fun aDirectorySyncFailureAfterReplacePublishesInstalledStateAndKeepsItOnTheNextWrite() {
        forEachStore { harness ->
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val ops = FaultOps(0)
            val failing = store(harness, directory, ops)
            harness.prime(failing)
            ops.failSyncAfterTheNextReplace(harness.fileName)

            harness.seedOther(failing)

            assertTrue("${harness.name}: the installed set is published", harness.otherPresent(failing))
            assertTrue("${harness.name}: the old set is still published", harness.seedPresent(failing))
            assertTrue("${harness.name}: the installed set is on disk",
                harness.holdsOther(File(directory, harness.fileName)))

            // The flush boundary lifts learning's write suppression, so the third learning event
            // performs a write in every store rather than only accumulating a pending counter.
            harness.flush(failing)
            if (harness.tracksPendingLearning) {
                assertEquals(
                    "${harness.name}: an installed graduation retires its pending key",
                    0,
                    harness.pendingCountForOther(directory),
                )
            }
            harness.seedThird(failing)

            assertTrue("${harness.name}: the next write publishes its own change",
                harness.thirdPresent(failing))
            assertTrue("${harness.name}: the next write keeps the installed set in memory",
                harness.otherPresent(failing))
            val file = File(directory, harness.fileName)
            assertTrue("${harness.name}: the next write keeps the old set on disk", harness.holdsSeed(file))
            assertTrue("${harness.name}: the next write keeps the installed set on disk",
                harness.holdsOther(file))
            assertTrue("${harness.name}: the next write installs the third set", harness.holdsThird(file))
        }
    }

    @Test
    fun lastEntryDeleteDirectorySyncFailureKeepsTheEmptyStateAndPreventsResurrection() {
        forEachStore { harness ->
            if (!harness.supportsRemove) return@forEachStore
            val directory = newPersonalDir()
            harness.seed(store(harness, directory))
            val file = File(directory, harness.fileName)
            val ops = FaultOps(0)
            val store = store(harness, directory, ops)
            harness.prime(store)
            ops.failSyncAfterTheNextDelete(harness.fileName)
            var outcome: Boolean? = null

            harness.removeSeed(store) { outcome = it }

            assertFalse("${harness.name}: the directory sync failure is reported", outcome!!)
            assertFalse("${harness.name}: the installed empty state stays published",
                harness.hasContent(store))
            assertFalse("${harness.name}: the last entry's file was deleted", file.exists())

            harness.seedOther(store)

            assertTrue("${harness.name}: the next write installs the new entry",
                harness.otherPresent(store))
            assertFalse("${harness.name}: the deleted entry does not return to memory",
                harness.seedPresent(store))
            assertFalse("${harness.name}: the next write does not resurrect the deleted entry",
                harness.holdsSeed(file))
            assertTrue("${harness.name}: the next write keeps only the new entry",
                harness.holdsOther(file))
        }
    }

    @Test
    fun replaceWithDirectorySyncFailureReportsFailureButReopensTheImportedState() {
        forEachStore { harness ->
            if (!harness.supportsReplace) return@forEachStore
            val sourceDirectory = newPersonalDir()
            harness.seed(store(harness, sourceDirectory))
            val imported = File(sourceDirectory, harness.fileName).readBytes()
            val directory = newPersonalDir()
            val ops = FaultOps(0)
            val store = store(harness, directory, ops)
            ops.failSyncAfterTheNextReplace(harness.fileName)
            var outcome: Boolean? = null

            harness.replaceAll(store, imported) { outcome = it }

            assertFalse("${harness.name}: the directory sync failure is reported", outcome!!)
            assertTrue("${harness.name}: the imported state is still reopened", harness.seedPresent(store))
            assertArrayEquals("${harness.name}: the imported bytes remain installed",
                imported, File(directory, harness.fileName).readBytes())

            harness.seedThird(store)
            val file = File(directory, harness.fileName)
            assertTrue("${harness.name}: a later write keeps imported data", harness.holdsSeed(file))
            assertTrue("${harness.name}: a later write adds its own data", harness.holdsThird(file))
        }
    }

    @Test
    fun replaceReportsAFailedReopenInsteadOfSuccess() {
        forEachStore { harness ->
            if (!harness.supportsReplace) return@forEachStore
            val sourceDirectory = newPersonalDir()
            harness.seed(store(harness, sourceDirectory))
            val imported = File(sourceDirectory, harness.fileName).readBytes()
            val directory = newPersonalDir()
            var providerCalls = 0
            val provider = PersonalDirectoryProvider {
                if (++providerCalls == 2) throw IOException("reopen failed")
                directory
            }
            val store = store(harness, directory, provider = provider)
            var outcome: Boolean? = null

            harness.replaceAll(store, imported) { outcome = it }

            assertFalse("${harness.name}: a failed re-read is not success", outcome!!)
            assertFalse("${harness.name}: failed re-read publishes nothing", harness.hasContent(store))
            assertArrayEquals("${harness.name}: the installed import is preserved",
                imported, File(directory, harness.fileName).readBytes())

            harness.prime(store)
            assertTrue("${harness.name}: a later prime reads the import", harness.seedPresent(store))
        }
    }

    @Test
    fun invalidReplaceKeepsLiveStateAndAnAcceptedNullReplaceErasesIt() {
        forEachStore { harness ->
            if (!harness.supportsReplace) return@forEachStore
            val directory = newPersonalDir()
            val store = store(harness, directory)
            harness.seed(store)
            val file = File(directory, harness.fileName)
            val before = file.readBytes()
            var outcome: Boolean? = null

            harness.replaceAll(store, ByteArray(96) { 0x55 }) { outcome = it }

            assertFalse("${harness.name}: invalid bytes are rejected", outcome!!)
            assertArrayEquals("${harness.name}: invalid bytes never replace the file",
                before, file.readBytes())
            assertTrue("${harness.name}: invalid bytes never replace live state", harness.seedPresent(store))

            harness.replaceAll(store, null) { outcome = it }

            assertTrue("${harness.name}: the accepted null restore erases", outcome!!)
            assertFalse("${harness.name}: the erased file is gone", file.exists())
            assertFalse("${harness.name}: the erased live state is empty", harness.hasContent(store))
        }
    }

    // ---- per-store harnesses ------------------------------------------------------------------

    /** What the shared tests need from one store type. */
    private abstract inner class Harness(val name: String, val fileName: String, val tempPrefix: String) {
        abstract fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice? = null,
        ): Any

        open val supportsReplace = true
        open val supportsQuarantineNotice = true
        open val supportsRemove = false
        open val tracksPendingLearning = false

        /** Saves one entry through the store's ordinary path, so a valid file exists. */
        abstract fun seed(store: Any)

        /** Saves a different entry, through learning where the store learns. */
        abstract fun seedOther(store: Any)

        /** Saves a third entry, used to prove a later write starts from installed state. */
        abstract fun seedThird(store: Any)

        open fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) {
            throw UnsupportedOperationException("$name has no restore API")
        }

        open fun removeSeed(store: Any, outcome: (Boolean) -> Unit) {
            throw UnsupportedOperationException("$name has no individual removal API")
        }

        open fun pendingCountForOther(directory: File): Int = 0

        /** Every write the store offers: learning, the user's own edits and the flush. */
        abstract fun tryToChange(store: Any)

        abstract fun hasContent(store: Any): Boolean

        open fun flush(store: Any) = Unit

        abstract fun clearAll(store: Any)

        /** Whether [file] still holds the entry [seed] saved. */
        fun holdsSeed(file: File): Boolean = holds(file) { seedPresent(it) }

        fun holdsOther(file: File): Boolean = holds(file) { otherPresent(it) }

        fun holdsThird(file: File): Boolean = holds(file) { thirdPresent(it) }

        private fun holds(file: File, present: (Any) -> Boolean): Boolean {
            val probe = create({ file.parentFile!! }, FaultOps(0), { true })
            prime(probe)
            return present(probe)
        }

        abstract fun prime(store: Any)

        abstract fun seedPresent(store: Any): Boolean
        abstract fun otherPresent(store: Any): Boolean
        abstract fun thirdPresent(store: Any): Boolean
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
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = PersonalDictionaryStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
            unlockGate = unlockGate, quarantineNotice = quarantineNotice)

        override val supportsRemove = true
        override val tracksPendingLearning = true

        private fun Any.s() = this as PersonalDictionaryStore
        override fun seed(store: Any) = store.s().addManually("абыйлар")
        override fun seedOther(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteCompletion("дөнья")
        }
        override fun seedThird(store: Any) = store.s().addManually("китап")
        override fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) =
            store.s().replaceAll(bytes) { outcome(it) }
        override fun removeSeed(store: Any, outcome: (Boolean) -> Unit) =
            store.s().forget("абыйлар") { outcome(it) }
        override fun pendingCountForOther(directory: File): Int {
            val salt = File(directory, "salt-$subtype-s1-f1.bin").readBytes()
            val counters = PendingCounters.parse(
                File(directory, "pending-$subtype-s1-f1.bin").readBytes())
            return counters.countOf(PendingCounters.keyOf(salt, "дөнья"))
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
        override fun flush(store: Any) = store.s().flush()
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = store.s().snapshot.indexOfNormalized("абыйлар") >= 0
        override fun otherPresent(store: Any) = store.s().snapshot.indexOfNormalized("дөнья") >= 0
        override fun thirdPresent(store: Any) = store.s().snapshot.indexOfNormalized("китап") >= 0
    }

    private fun pairs() = object : Harness(
        "pairs", TpersbFormat.personalBigramsFileName(subtype), ".personal-bigrams-",
    ) {
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = PersonalBigramStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
            contextMembership = { _, _ -> true }, unlockGate = unlockGate,
            quarantineNotice = quarantineNotice)

        override val supportsRemove = true
        override val tracksPendingLearning = true

        private fun Any.s() = this as PersonalBigramStore
        override fun seed(store: Any) = repeat(PersonalBigramStore.LEARN_THRESHOLD) {
            store.s().notePair("китап", "укыйм")
        }
        override fun seedOther(store: Any) = repeat(PersonalBigramStore.LEARN_THRESHOLD) {
            store.s().notePair("матур", "дөнья")
        }
        override fun seedThird(store: Any) = repeat(PersonalBigramStore.LEARN_THRESHOLD) {
            store.s().notePair("яратам", "сүз")
        }
        override fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) =
            store.s().replaceAll(bytes) { outcome(it) }
        override fun removeSeed(store: Any, outcome: (Boolean) -> Unit) =
            store.s().forget("китап", "укыйм") { outcome(it) }
        override fun pendingCountForOther(directory: File): Int {
            val salt = File(directory, "salt-bigrams-$subtype-s1-f1.bin").readBytes()
            val counters = PendingCounters.parse(
                File(directory, "pending-bigrams-$subtype-s1-f1.bin").readBytes())
            return counters.countOf(PendingCounters.keyOfPair(salt, "матур", "дөнья"))
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
        override fun flush(store: Any) = store.s().flush()
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.successorRawFormAt(it) == "укыйм" }
        override fun otherPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.successorRawFormAt(it) == "дөнья" }
        override fun thirdPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.successorRawFormAt(it) == "сүз" }
    }

    private fun emoji() = object : Harness(
        "emoji", TpersemFormat.personalEmojiFileName(subtype), ".personal-emoji-",
    ) {
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = PersonalEmojiStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
            unlockGate = unlockGate, quarantineNotice = quarantineNotice)

        override val supportsRemove = true
        override val tracksPendingLearning = true

        private fun Any.s() = this as PersonalEmojiStore
        override fun seed(store: Any) = repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
            store.s().noteObservation("сәләм", "☀️")
        }
        override fun seedOther(store: Any) = repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
            store.s().noteObservation("бәйрәм", "🎉")
        }
        override fun seedThird(store: Any) = repeat(PersonalEmojiStore.LEARN_THRESHOLD) {
            store.s().noteObservation("күл", "🌙")
        }
        override fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) =
            store.s().replaceAll(bytes) { outcome(it) }
        override fun removeSeed(store: Any, outcome: (Boolean) -> Unit) =
            store.s().forget("сәләм", "☀️") { outcome(it) }
        override fun pendingCountForOther(directory: File): Int {
            val salt = File(directory, "salt-emoji-$subtype-s1-f1.bin").readBytes()
            val counters = PendingCounters.parse(
                File(directory, "pending-emoji-$subtype-s1-f1.bin").readBytes())
            return counters.countOf(PendingCounters.keyOfPair(salt, "бәйрәм", "🎉"))
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
        override fun flush(store: Any) = store.s().flush()
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.emojiAt(it) == "☀️" }
        override fun otherPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.emojiAt(it) == "🎉" }
        override fun thirdPresent(store: Any) =
            (0 until store.s().snapshot.size).any { store.s().snapshot.emojiAt(it) == "🌙" }
    }

    private fun sentenceStarts() = object : Harness(
        "sentence starts", TsentFormat.sentStartFileName(subtype), ".personal-",
    ) {
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = SentStartOverlayStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
            unlockGate = unlockGate, quarantineNotice = quarantineNotice)

        override val supportsReplace = false
        override val tracksPendingLearning = true

        private fun Any.s() = this as SentStartOverlayStore
        override fun seed(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteSentenceStart("Сәлам")
        }
        override fun seedOther(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteSentenceStart("Бүген")
        }
        override fun seedThird(store: Any) = repeat(PendingCounters.LEARN_THRESHOLD) {
            store.s().noteSentenceStart("Китап")
        }
        override fun pendingCountForOther(directory: File): Int {
            val salt = File(directory, "salt-sentstart-$subtype-s1-f1.bin").readBytes()
            val counters = PendingCounters.parse(
                File(directory, "pending-sentstart-$subtype-s1-f1.bin").readBytes())
            return counters.countOf(PendingCounters.keyOf(salt, "бүген"))
        }
        override fun tryToChange(store: Any) {
            val s = store.s()
            repeat(PendingCounters.LEARN_THRESHOLD) { s.noteSentenceStart("Бүген") }
            s.noteSentenceStart("Сәлам")
            s.flush()
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun flush(store: Any) = store.s().flush()
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = "Сәлам" in store.s().snapshot.topWords()
        override fun otherPresent(store: Any) = "Бүген" in store.s().snapshot.topWords()
        override fun thirdPresent(store: Any) = "Китап" in store.s().snapshot.topWords()
    }

    private fun refusals() = object : Harness(
        "refused corrections", TrefFormat.refusedCorrectionsFileName(subtype), ".personal-",
    ) {
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = RefusedCorrectionStore(subtype, provider, ops, opener, roomy, { 1000L }, direct,
            unlockGate = unlockGate)

        override val supportsQuarantineNotice = false

        private fun Any.s() = this as RefusedCorrectionStore
        override fun seed(store: Any) = store.s().noteRefusal("китаб", "китап")
        override fun seedOther(store: Any) = store.s().noteRefusal("дуньа", "дөнья")
        override fun seedThird(store: Any) = store.s().noteRefusal("бала", "бәлә")
        override fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) =
            store.s().replaceAll(bytes) { outcome(it) }
        override fun tryToChange(store: Any) {
            store.s().noteRefusal("дуньа", "дөнья")
            store.s().noteRefusal("китаб", "китап")
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any): Boolean {
            val snapshot = store.s().snapshot
            return (0 until snapshot.size).any {
                snapshot.typedWordAt(it) == "китаб" && snapshot.replacementAt(it) == "китап"
            }
        }
        override fun otherPresent(store: Any): Boolean {
            val snapshot = store.s().snapshot
            return (0 until snapshot.size).any {
                snapshot.typedWordAt(it) == "дуньа" && snapshot.replacementAt(it) == "дөнья"
            }
        }
        override fun thirdPresent(store: Any): Boolean {
            val snapshot = store.s().snapshot
            return (0 until snapshot.size).any {
                snapshot.typedWordAt(it) == "бала" && snapshot.replacementAt(it) == "бәлә"
            }
        }
    }

    private fun shortcuts() = object : Harness("text shortcuts", TcutFormat.shortcutsFileName(), ".personal-") {
        override fun create(
            provider: PersonalDirectoryProvider,
            ops: DurableFileOps,
            unlockGate: () -> Boolean,
            quarantineNotice: PersonalQuarantineNotice?,
        ) = TextShortcutStore(provider, ops, opener, roomy, { 1000L }, direct,
            unlockGate = unlockGate)

        override val supportsQuarantineNotice = false
        override val supportsRemove = true

        private fun Any.s() = this as TextShortcutStore
        override fun seed(store: Any) = store.s().put("кт", "китап")
        override fun seedOther(store: Any) = store.s().put("дн", "дөнья")
        override fun seedThird(store: Any) = store.s().put("өч", "өч")
        override fun replaceAll(store: Any, bytes: ByteArray?, outcome: (Boolean) -> Unit) =
            store.s().replaceAll(bytes) { outcome(it) }
        override fun removeSeed(store: Any, outcome: (Boolean) -> Unit) =
            store.s().remove("кт") { outcome(it) }
        override fun tryToChange(store: Any) {
            store.s().put("дн", "дөнья")
            store.s().remove("кт")
        }
        override fun hasContent(store: Any) = !store.s().snapshot.isEmpty
        override fun clearAll(store: Any) = store.s().clearAll()
        override fun prime(store: Any) = store.s().prime()
        override fun seedPresent(store: Any) = store.s().snapshot.expansionFor("кт") == "китап"
        override fun otherPresent(store: Any) = store.s().snapshot.expansionFor("дн") == "дөнья"
        override fun thirdPresent(store: Any) = store.s().snapshot.expansionFor("өч") == "өч"
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
        onQuarantined: PersonalQuarantineNotice? = null,
    ): Any = harness.create(provider, ops, unlockGate, onQuarantined)

    private fun contentsOf(directory: File): Map<String, ByteArray> =
        directory.listFiles()!!.associate { it.name to it.readBytes() }

    private fun assertDirectoryUnchanged(name: String, before: Map<String, ByteArray>, directory: File) {
        val after = contentsOf(directory)
        assertEquals("$name: no file is created or removed", before.keys, after.keys)
        for ((fileName, bytes) in before) {
            assertArrayEquals("$name: $fileName keeps its bytes", bytes, after.getValue(fileName))
        }
    }

    /** Performs every durable op for real; the injected faults throw at their step. */
    private class FaultOps(
        private var failingSyncs: Int,
        private var failingQuarantineMoves: Int = 0,
    ) : DurableFileOps {
        private var syncAfterReplaceFailsFor: String? = null
        private var syncAfterDeleteFailsFor: String? = null
        private var replaceLanded = false
        private var deleteLanded = false

        fun failSyncAfterTheNextReplace(destinationName: String) {
            syncAfterReplaceFailsFor = destinationName
        }

        fun failSyncAfterTheNextDelete(destinationName: String) {
            syncAfterDeleteFailsFor = destinationName
        }

        override fun createNewFile(file: File): Boolean = file.createNewFile()

        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()

        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }

        override fun atomicReplace(source: File, destination: File) {
            if (failingQuarantineMoves > 0 && destination.name.endsWith(QUARANTINE_SUFFIX)) {
                failingQuarantineMoves--
                throw IOException("quarantine move failed")
            }
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
            if (destination.name == syncAfterReplaceFailsFor) replaceLanded = true
        }

        override fun syncDirectory(directory: File) {
            if (replaceLanded) {
                replaceLanded = false
                syncAfterReplaceFailsFor = null
                throw IOException("sync after replace failed")
            }
            if (deleteLanded) {
                deleteLanded = false
                syncAfterDeleteFailsFor = null
                throw IOException("sync after delete failed")
            }
            if (failingSyncs > 0) {
                failingSyncs--
                throw IOException("sync failed")
            }
        }

        override fun delete(file: File): Boolean {
            val deleted = file.delete()
            if (deleted && file.name == syncAfterDeleteFailsFor) deleteLanded = true
            return deleted
        }
    }

    private companion object {
        const val QUARANTINE_SUFFIX = ".quarantine"
    }
}
