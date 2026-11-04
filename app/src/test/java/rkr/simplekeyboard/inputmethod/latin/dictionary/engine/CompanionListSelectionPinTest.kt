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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator

/**
 * Pins the two shipped cold-companion lists against the shipped dictionaries: the list of each
 * language is exactly the top words of its dictionary by stored frequency (ties by dictionary
 * order), the same rule `scripts/companion_list_pack.py` applies. A rebuilt dictionary that is
 * not followed by a re-packed list fails here until the packer runs again.
 */
class CompanionListSelectionPinTest {

    private fun locate(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("cannot locate ${paths.last()}")

    private fun dictionaryIndex(spec: DictionaryArtifactSpec): TdictPrefixIndex {
        val asset = locate(
            "src/main/assets/${spec.assetPath}",
            "app/src/main/assets/${spec.assetPath}",
        )
        val rawFile = File.createTempFile("companion-selection-", ".tdict")
        try {
            rawFile.outputStream().use { output ->
                TdictValidator().inflateAsset(asset.inputStream(), output, spec)
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()), identity,
                    validated.entryCount, validated.rawSize,
                    autocorrectMinCandidateFrequency = 0L,
                ),
            )
        } finally {
            rawFile.delete()
        }
    }

    private fun topDictionaryWords(spec: DictionaryArtifactSpec, count: Int): List<String> {
        val index = dictionaryIndex(spec)
        val entries = ArrayList<Pair<String, Long>>(index.entryCount)
        // The cold walk visits every entry in dictionary order with its frequency.
        index.forEachWordCold(ColdWordVisitor { word, frequency -> entries.add(word to frequency) })
        // Ties keep dictionary order, the packer's rule. Ranking the indices keeps the
        // tie-break O(1); ranking the entries and looking each up would be quadratic.
        val ranked = entries.indices.sortedWith(
            compareByDescending<Int> { entries[it].second }.thenBy { it },
        )
        return ranked.take(count).map { entries[it].first }
    }

    private fun shippedListWords(spec: CompanionListArtifactSpec): List<String> {
        val asset = locate(
            "src/main/assets/${spec.assetPath}",
            "app/src/main/assets/${spec.assetPath}",
        )
        return asset.inputStream().use { CompanionListReader().read(it, spec) }.words
    }

    private fun dictionarySpecFor(list: CompanionListArtifactSpec): DictionaryArtifactSpec =
        requireNotNull(DictionaryArtifactSpec.forSubtype(list.languageTag))

    @Test
    fun theTatarListIsTheTopOfTheShippedTatarDictionary() {
        val list = CompanionListArtifactSpec.TATAR_COMPANION_V1
        val expected = topDictionaryWords(dictionarySpecFor(list), TOP)
        val actual = shippedListWords(list)
        assertEquals(expected.sorted(), actual)
    }

    @Test
    fun theRussianListIsTheTopOfTheShippedRussianDictionary() {
        val list = CompanionListArtifactSpec.RUSSIAN_COMPANION_V1
        val expected = topDictionaryWords(dictionarySpecFor(list), TOP)
        val actual = shippedListWords(list)
        assertEquals(expected.sorted(), actual)
    }

    private companion object {
        /** The words each list keeps; the same number `rebuild_assets.py` passes the packer. */
        const val TOP = 20_000
    }
}
