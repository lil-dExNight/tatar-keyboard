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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatarLayoutTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AssetInputProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AtomicDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DeviceProtectedDirectoryProvider
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StorageClock
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TestDurableFileOps

class SpellCheckerDictionaryLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publishedFilesServeBothLanguagesWithoutOpeningBundledDictionaries() {
        val stores = stores(publish = true)
        var dictionaryAssetOpens = 0

        val dictionaries = requireNotNull(load(stores) { dictionaryAssetOpens++ })

        assertTrue(dictionaries.tatarBaseWord("бүген"))
        assertTrue(dictionaries.russianBaseWord("и"))
        assertTrue(dictionaries.typoCandidates("сэлам").words.contains("сәлам"))
        assertEquals(0, dictionaryAssetOpens)
        for (harness in stores.values) {
            assertTrue(finalFile(harness.spec).isFile)
            assertTrue(harness.operations.deleted.isEmpty())
            assertEquals(1, harness.store.fullValidationCount)
        }
    }

    @Test
    fun missingPublishedFilesFallBackToBundledAssets() {
        val stores = stores(publish = false)
        var dictionaryAssetOpens = 0

        val dictionaries = requireNotNull(load(stores) { dictionaryAssetOpens++ })

        assertTrue(dictionaries.tatarBaseWord("бүген"))
        assertTrue(dictionaries.russianBaseWord("и"))
        assertEquals(2, dictionaryAssetOpens)
        for (harness in stores.values) {
            assertFalse(finalFile(harness.spec).exists())
            assertTrue(harness.operations.deleted.isEmpty())
        }
    }

    @Test
    fun corruptPublishedFilesFallBackWithoutBeingDeleted() {
        val stores = stores(publish = true)
        val tatar = stores.getValue(DictionaryArtifactSpec.TATAR_TOP100K_V1)
        val corrupt = finalFile(tatar.spec)
        val changed = corrupt.readBytes().also {
            it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte()
        }
        corrupt.writeBytes(changed)
        assertTrue(corrupt.setLastModified(corrupt.lastModified() + 2_000))
        var dictionaryAssetOpens = 0

        val dictionaries = requireNotNull(load(stores) { dictionaryAssetOpens++ })

        assertTrue(dictionaries.tatarBaseWord("бүген"))
        assertTrue(dictionaries.russianBaseWord("и"))
        assertEquals(1, dictionaryAssetOpens)
        assertTrue(changed.contentEquals(corrupt.readBytes()))
        for (harness in stores.values) assertTrue(harness.operations.deleted.isEmpty())
    }

    private fun stores(publish: Boolean): Map<DictionaryArtifactSpec, StoreHarness> =
        DictionaryArtifactSpec.ALL.associateWith { spec ->
            val directory = temporaryFolder.newFolder(spec.family)
            val operations = TestDurableFileOps()
            val store = AtomicDictionaryStore(
                directoryProvider = DeviceProtectedDirectoryProvider { directory },
                assetInputProvider = AssetInputProvider { artifact ->
                    locateAsset(artifact.assetPath).inputStream()
                },
                clock = StorageClock { 1234L },
                spaceProbe = SpaceProbe { Long.MAX_VALUE },
                fileOps = operations,
                supportedArtifacts = listOf(spec),
            )
            if (publish) {
                assertTrue(store.ensurePublished(spec) is PreparationResult.Published)
            }
            StoreHarness(spec, store, operations)
        }

    private fun load(
        stores: Map<DictionaryArtifactSpec, StoreHarness>,
        onDictionaryAssetOpen: () -> Unit,
    ): SpellCheckerDictionaries? = SpellCheckerDictionaryLoader(
        opener = SpellCheckerAssetOpener { path ->
            if (path.endsWith(".tdict.zlib")) onDictionaryAssetOpen()
            locateAsset(path).inputStream()
        },
        acceptor = { null },
        personal = PersonalDictionarySource { PersonalDictionary.EMPTY },
        neighbors = TatarLayoutTestFixtures.tatarNeighborTable(),
        leaseSource = SpellCheckerDictionaryLeaseSource { spec ->
            stores.getValue(spec).store.acquireLatestForActivation()
        },
    ).load()

    private fun finalFile(spec: DictionaryArtifactSpec): File {
        val directory = temporaryFolder.root.resolve(spec.family)
        return File(directory, spec.finalFileName)
    }

    private fun locateAsset(path: String): File = listOf(
        File("src/main/assets", path),
        File("app/src/main/assets", path),
    ).firstOrNull(File::isFile) ?: error("missing bundled asset")

    private data class StoreHarness(
        val spec: DictionaryArtifactSpec,
        val store: AtomicDictionaryStore,
        val operations: TestDurableFileOps,
    )
}
