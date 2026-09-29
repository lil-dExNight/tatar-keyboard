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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramPreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageFailure
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The next-word table follows the layout exactly as the dictionary does, and follows it through
 * the SAME resolver: [DictionaryArtifactSpec.forSubtype] answers "which language is this" once,
 * for both artifact kinds. These tests are the counterpart of
 * [SuggestionsControllerLanguageSwitchTest] for the second artifact, and the first two of them
 * exist to make a second, independently-drifting subtype-to-table rule fail loudly if anyone ever
 * adds one.
 */
class SuggestionsControllerBigramLanguageSwitchTest {

    private val tatar = PersonalSubtypes.TATAR_RU
    private val russian = PersonalSubtypes.RUSSIAN

    // --- Fakes ---------------------------------------------------------------------------------

    private class FakeStrip : StripSurface {
        var reserveCount = 0
        var hideCount = 0
        override fun showSuggestions(first: String, second: String?, third: String?) = Unit
        override fun reserve() { reserveCount++ }
        override fun hideSuggestions() { hideCount++ }
        override fun setTapListener(listener: SuggestionTapListener) = Unit
    }

    private class FakeEditor : EditorSurface {
        var word: String = ""
        override fun cachedWordBeforeCursor(): String = word
        override fun commitSuggestion(expectedPrefix: String, suggestion: String) = true
        override fun hasKnownCursor(): Boolean = true
        override fun hasLetterAfterCursor(): Boolean = false
    }

    /** One engine per language; records which bigram catalog was attached to it, if any. */
    private class FakeEngine(val subtypeId: String) : EngineHandle {
        var attachedCatalog: PublishedBigramTableCatalog? = null
        var attachCount = 0

        override fun request(editorSessionId: Long, subtypeId: String, prefixUtf8: ByteArray): Any? =
            TOKEN

        override fun requestNextWord(
            editorSessionId: Long,
            subtypeId: String,
            contextWordUtf8: ByteArray,
        ): Any? = null

        override fun attachBigramSource(catalog: PublishedBigramTableCatalog): Boolean {
            attachCount++
            attachedCatalog = catalog
            return true
        }

        override fun isCurrent(token: Any): Boolean = token === TOKEN
        override fun finishInput() = Unit
        override fun destroy(timeoutMs: Long): Boolean = true

        companion object { val TOKEN = Any() }
    }

    private class FakeDictionaryCatalog : PublishedDictionaryCatalog {
        override fun acquireLatestForActivation(): DictionaryFileLease? = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakeBigramCatalog(val subtypeId: String) : PublishedBigramTableCatalog {
        override fun acquireLatestForActivation() = null
        override fun cleanupReleasedVersions() = Unit
    }

    private class FakeDictionaryPreparation : DictionaryPreparation {
        private val catalog = FakeDictionaryCatalog()
        override fun prepare(onResult: (PreparationResult) -> Unit) = onResult(
            PreparationResult.Published(
                PublishedDictionary(
                    generation = 1,
                    file = File("/dev/null"),
                    rawSize = 72,
                    entryCount = 1,
                    schemaId = 1,
                    formatVersion = 1,
                    rawSha256 = "0".repeat(64),
                ),
                alreadyPresent = true,
            ),
        )

        override fun catalog(): PublishedDictionaryCatalog = catalog
    }

    /** Publishes on demand so a test can hold one language's table unpublished, or fail it. */
    private class FakeBigramPreparation(
        val subtypeId: String,
        val publishNow: Boolean = true,
        val failWith: StorageFailure? = null,
    ) : BigramPreparation {
        var prepareCalls = 0
        private var pending: ((BigramPreparationResult) -> Unit)? = null
        val catalog = FakeBigramCatalog(subtypeId)

        override fun prepare(onResult: (BigramPreparationResult) -> Unit) {
            prepareCalls++
            when {
                failWith != null -> onResult(BigramPreparationResult.Unavailable(failWith))
                publishNow -> onResult(published())
                else -> pending = onResult
            }
        }

        fun publishPending() {
            val callback = pending ?: return
            pending = null
            callback(published())
        }

        override fun catalog(): PublishedBigramTableCatalog = catalog

        private fun published() = BigramPreparationResult.Published(
            PublishedBigramTable(
                generation = 1,
                fileLanguageTag = if (subtypeId == PersonalSubtypes.TATAR_RU) "tt" else subtypeId,
                file = File("/dev/null"),
                rawSize = 96,
                headCount = 1,
                pairCount = 1,
                successVocabularyCount = 1,
                schemaId = 2,
                formatVersion = 1,
                rawSha256 = "0".repeat(64),
            ),
            alreadyPresent = true,
        )
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    /**
     * [languagesWithTable] models the production registry: a subtype absent from it gets no
     * bigram seam at all, exactly like [DictionaryArtifactSpec.bigramsForSubtype] returning null.
     */
    private class Harness(
        val languagesWithTable: Set<String> = setOf(
            PersonalSubtypes.TATAR_RU,
            PersonalSubtypes.RUSSIAN,
        ),
        val russianTablePublishesNow: Boolean = true,
        val russianTableFailsWith: StorageFailure? = null,
    ) {
        val strip = FakeStrip()
        val editor = FakeEditor()
        val executor = DirectExecutorService()
        val engines = LinkedHashMap<String, FakeEngine>()
        val bigramPreparations = LinkedHashMap<String, FakeBigramPreparation>()
        val dictionarySeamCalls = mutableListOf<String>()
        val bigramSeamCalls = mutableListOf<String>()

        val controller = SuggestionsController(
            strip,
            editor,
            UiPoster { it.run() },
            { subtypeId, _ -> engines.getOrPut(subtypeId) { FakeEngine(subtypeId) } },
            { executor },
            { _: ExecutorService, subtypeId: String ->
                dictionarySeamCalls.add(subtypeId)
                FakeDictionaryPreparation()
            },
            false,
            { _: ExecutorService, subtypeId: String ->
                bigramSeamCalls.add(subtypeId)
                if (subtypeId !in languagesWithTable) {
                    null
                } else {
                    bigramPreparations.getOrPut(subtypeId) {
                        FakeBigramPreparation(
                            subtypeId,
                            publishNow = subtypeId != PersonalSubtypes.RUSSIAN ||
                                russianTablePublishesNow,
                            failWith = if (subtypeId == PersonalSubtypes.RUSSIAN) {
                                russianTableFailsWith
                            } else {
                                null
                            },
                        )
                    }
                }
            },
        )
    }

    // --- The registry --------------------------------------------------------------------------

    @Test
    fun everyShippedLanguageResolvesItsOwnTableThroughTheOneRegistry() {
        assertSame(
            BigramArtifactSpec.TATAR_BIGRAMS_V1,
            DictionaryArtifactSpec.bigramsForSubtype(tatar),
        )
        assertSame(
            BigramArtifactSpec.RUSSIAN_BIGRAMS_V1,
            DictionaryArtifactSpec.bigramsForSubtype(russian),
        )
        // A subtype with no dictionary has no table either — one lookup answers both.
        assertNull(DictionaryArtifactSpec.forSubtype("en_US"))
        assertNull(DictionaryArtifactSpec.bigramsForSubtype("en_US"))
    }

    @Test
    fun aTableCanNeverBelongToADifferentLanguageThanItsDictionary() {
        // The invariant that makes one resolver enough: the spec pair cannot disagree, because
        // DictionaryArtifactSpec's init requires the tags to match. Asserted over the whole
        // registry so a third language is covered the day it is added.
        for (spec in DictionaryArtifactSpec.ALL) {
            val table = spec.bigrams ?: continue
            assertEquals(spec.languageTag, table.subtypeId)
            assertSame(table, DictionaryArtifactSpec.bigramsForSubtype(spec.languageTag))
        }
        assertTrue(DictionaryArtifactSpec.ALL.any { it.bigrams != null })
    }

    @Test
    fun eachTableOwnsItsOwnFamilyDirectoryAndFileName() {
        val tables = DictionaryArtifactSpec.ALL.mapNotNull { it.bigrams }
        assertEquals(tables.size, tables.map { it.family }.distinct().size)
        assertEquals(tables.size, tables.map { it.storageDirectoryName }.distinct().size)
        assertEquals(tables.size, tables.map { it.assetPath }.distinct().size)

        // FROZEN: family, tag, generation and directory of the file 1.6.0 inflated on devices.
        // The schema segment is deliberately NOT frozen: s2 → s3 (SIZE-2, 2026-09-01) is exactly
        // what makes a device re-inflate the cross-referenced table next to the old one instead
        // of opening a format it does not understand.
        val tt = BigramArtifactSpec.TATAR_BIGRAMS_V1
        assertEquals("bigrams", tt.storageDirectoryName)
        assertEquals(
            "tatar_bigrams-tt-v000001-s3-f1-${tt.expectedRawSha256}.tatbigr",
            tt.finalFileName,
        )
        // Neither family's pattern may ever match the other family's file.
        val ru = BigramArtifactSpec.RUSSIAN_BIGRAMS_V1
        assertTrue(tt.finalFilePattern.matches(tt.finalFileName))
        assertTrue(ru.finalFilePattern.matches(ru.finalFileName))
        assertTrue(!tt.finalFilePattern.matches(ru.finalFileName))
        assertTrue(!ru.finalFilePattern.matches(tt.finalFileName))
        assertTrue(!tt.temporaryFilePrefix.startsWith(ru.temporaryFilePrefix))
        assertTrue(!ru.temporaryFilePrefix.startsWith(tt.temporaryFilePrefix))
    }

    // --- The controller ------------------------------------------------------------------------

    @Test
    fun switchingLayoutSwitchesWhichTableIsAttached() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)

        assertSame(
            h.bigramPreparations.getValue(tatar).catalog,
            h.engines.getValue(tatar).attachedCatalog,
        )

        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        assertSame(
            h.bigramPreparations.getValue(russian).catalog,
            h.engines.getValue(russian).attachedCatalog,
        )
        // The Tatar engine kept its own table and was never handed the Russian one.
        assertSame(
            h.bigramPreparations.getValue(tatar).catalog,
            h.engines.getValue(tatar).attachedCatalog,
        )
    }

    @Test
    fun theTableSeamIsAlwaysAskedForTheSameSubtypeAsTheDictionarySeam() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)

        assertEquals(h.dictionarySeamCalls, h.bigramSeamCalls)
        assertEquals(listOf(tatar, russian), h.bigramSeamCalls)
    }

    @Test
    fun switchingBackReusesTheWarmTableAndPreparesNothingTwice() {
        val h = Harness()
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)
        h.editor.word = "сүзл"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = tatar)

        assertEquals(1, h.bigramPreparations.getValue(tatar).prepareCalls)
        assertEquals(1, h.bigramPreparations.getValue(russian).prepareCalls)
        assertEquals(1, h.engines.getValue(tatar).attachCount)
        assertEquals(1, h.engines.getValue(russian).attachCount)
    }

    @Test
    fun aLanguageWithNoTableStaysSilentAndNeverBorrowsTheOtherLanguagesTable() {
        // Fail-closed, and closed on the RIGHT language: the shape a language that ships a
        // dictionary but no next-word table must have.
        val h = Harness(languagesWithTable = setOf(PersonalSubtypes.TATAR_RU))
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        assertNull(h.engines.getValue(russian).attachedCatalog)
        assertEquals(0, h.engines.getValue(russian).attachCount)
        // Prefix suggestions for that language are untouched: the band is still open.
        assertTrue(h.strip.reserveCount >= 2)
        // And the language that does have a table still has exactly its own.
        assertSame(
            h.bigramPreparations.getValue(tatar).catalog,
            h.engines.getValue(tatar).attachedCatalog,
        )
    }

    @Test
    fun oneLanguagesTableFailingLeavesTheOthersAttached() {
        val h = Harness(russianTableFailsWith = StorageFailure.INVALID_ASSET)
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        assertNull(h.engines.getValue(russian).attachedCatalog)
        assertNotNull(h.engines.getValue(tatar).attachedCatalog)
        assertTrue(h.strip.reserveCount >= 2)
    }

    @Test
    fun aTableThatPublishesLateAttachesToItsOwnEngineWhenItArrives() {
        val h = Harness(russianTablePublishesNow = false)
        h.editor.word = "сүз"
        h.controller.onStartInput(eligible = true, subtypeId = tatar)
        h.editor.word = "сло"
        h.controller.onSubtypeChanged(eligible = true, subtypeId = russian)

        // Prefix suggestions did not wait for the table.
        assertNotNull(h.engines[russian])
        assertNull(h.engines.getValue(russian).attachedCatalog)

        h.bigramPreparations.getValue(russian).publishPending()

        assertSame(
            h.bigramPreparations.getValue(russian).catalog,
            h.engines.getValue(russian).attachedCatalog,
        )
        assertSame(
            h.bigramPreparations.getValue(tatar).catalog,
            h.engines.getValue(tatar).attachedCatalog,
        )
    }
}
