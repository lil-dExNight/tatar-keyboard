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
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator

/**
 * Pins the per-language autocorrect floors ([AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT],
 * [AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU]) against the shipped dictionaries: each constant
 * must equal the frequency of the rank-[RANK] word of its build, so a rebuilt dictionary that
 * moves that frequency fails here instead of silently mistuning autocorrect. The constant is then
 * re-derived by the same rule.
 */
class AutocorrectFloorPinTest {

    /**
     * The frequency of the rank-[rank] word of the shipped dictionary [spec]: every entry's
     * frequency, sorted descending. A value, not a word, so ties around the rank cannot move it.
     */
    private fun rankFrequency(spec: DictionaryArtifactSpec, rank: Int): Long {
        val asset = listOf(
            File("src/main/assets", spec.assetPath),
            File("app/src/main/assets", spec.assetPath),
        ).firstOrNull { it.isFile } ?: error("cannot locate ${spec.assetPath}")
        val rawFile = File.createTempFile("autocorrect-floor-", ".tdict")
        val index: TdictPrefixIndex
        try {
            rawFile.outputStream().use { output ->
                TdictValidator().inflateAsset(asset.inputStream(), output, spec)
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            index = requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()), identity,
                    validated.entryCount, validated.rawSize,
                    autocorrectMinCandidateFrequency = if (spec == DictionaryArtifactSpec.TATAR_TOP100K_V1)
                        AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT
                    else AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
                ),
            )
        } finally {
            rawFile.delete()
        }
        val frequencies = LongArray(index.entryCount) { index.frequencyOf(index.wordAt(it)) }
        frequencies.sortDescending()
        return frequencies[rank - 1]
    }

    @Test
    fun theTatarFloorIsTheRank10000FrequencyOfTheShippedTatarDictionary() {
        assertEquals(
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            rankFrequency(DictionaryArtifactSpec.TATAR_TOP100K_V1, RANK),
        )
    }

    @Test
    fun theRussianFloorIsTheRank10000FrequencyOfTheShippedRussianDictionary() {
        assertEquals(
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
            rankFrequency(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1, RANK),
        )
    }

    @Test
    fun everyShippedDictionaryHasItsFloorConstant() {
        // A new shipped language fails here until its rank-10 000 floor is derived and added.
        val floors = mapOf(
            PersonalSubtypes.TATAR_RU to AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            PersonalSubtypes.RUSSIAN to AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
        )
        assertEquals(
            DictionaryArtifactSpec.ALL.map { it.languageTag }.sorted(),
            floors.keys.sorted(),
        )
    }

    private companion object {
        /** The rank whose frequency is the floor: autocorrect offers only the words people write. */
        const val RANK = 10_000
    }
}
