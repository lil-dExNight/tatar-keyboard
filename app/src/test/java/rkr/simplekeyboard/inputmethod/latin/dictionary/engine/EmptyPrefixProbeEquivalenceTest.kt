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

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer

/** Exact-range probe decisions against the original lookup probe on both shipped dictionaries. */
class EmptyPrefixProbeEquivalenceTest {

    @Test
    fun tatarDictionaryOovAndTypoDecisionsAreIdentical() {
        assertEquivalent(
            requireNotNull(tatarIndex),
            listOf("китап" to true, "арфам" to false, "селям" to false),
        )
    }

    @Test
    fun russianDictionaryOovAndTypoDecisionsAreIdentical() {
        assertEquivalent(
            requireNotNull(russianIndex),
            listOf("книга" to true, "боге" to false, "кнега" to false),
        )
    }

    private fun assertEquivalent(index: TdictPrefixIndex, cases: List<Pair<String, Boolean>>) {
        val computer = CompositePrefixComputer(index, PersonalCandidateSource.EMPTY)
        for ((word, known) in cases) {
            assertEquals("case premise: $word", known, index.frequencyOf(word) > 0L)
            val expected = lookupProbe(index, word)
            val exactCount = index.lastExactCount
            val fuzzyProbes = index.lastFuzzyProbeCount
            val autocorrectProbes = index.lastAutocorrectProbeCount

            val actual = computer.shortestEmptyProperPrefixLength(word)

            assertEquals(word, expected, actual)
            if (known) assertEquals(word, EmptyPrefixProber.NO_EMPTY_PREFIX, actual)
            assertEquals("exact verdict changed for $word", exactCount, index.lastExactCount)
            assertEquals("typo probes changed for $word", fuzzyProbes, index.lastFuzzyProbeCount)
            assertEquals(
                "autocorrect probes changed for $word",
                autocorrectProbes,
                index.lastAutocorrectProbeCount,
            )
        }
    }

    /** The original probe: exact-pass emptiness observed through complete dictionary lookups. */
    private fun lookupProbe(index: TdictPrefixIndex, word: String): Int {
        if (index.frequencyOf(word) > 0L) return EmptyPrefixProber.NO_EMPTY_PREFIX
        var low = 1
        var high = word.length - 1
        var found = EmptyPrefixProber.NO_EMPTY_PREFIX
        while (low <= high) {
            val middle = (low + high) ushr 1
            val bytes = word.substring(0, middle).toByteArray(Charsets.UTF_8)
            index.lookup(ImmutableUtf8Prefix.copyOf(bytes))
            if (index.lastExactCount == 0) {
                found = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return found
    }

    companion object {
        private var tatarIndex: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedDictionaries() {
            tatarIndex = open(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            russianIndex = open(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
        }

        private fun open(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("empty-prefix-probe-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val tatar = spec == DictionaryArtifactSpec.TATAR_TOP100K_V1
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                val index = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity,
                        validated.entryCount, validated.rawSize,
                        if (tatar) TatarSuffixRules else null,
                        if (tatar) FuzzyEditPolicy.TATAR else FuzzyEditPolicy.RUSSIAN,
                        if (tatar) AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT
                        else AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                    ),
                )
                index.updateKeyNeighbors(
                    if (tatar) E3bTestFixtures.tatarNeighborTable() else russianNeighborTable(),
                )
                return index
            } finally {
                rawFile.delete()
            }
        }

        private fun russianNeighborTable(): KeyNeighborTable = KeyNeighborTable.build(
            "ru",
            true,
            "йцукенгшщзхъфывапролджэячсмитьбюё".map {
                KeyNeighborTable.RawKey(it.code, intArrayOf())
            },
        )

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed dictionary test resource")
    }
}
