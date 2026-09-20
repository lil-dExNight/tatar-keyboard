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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * TT-NEXTWORD-FILL task B/C (docs/TT-NEXTWORD-FILL.md): the NEXT_WORD strip after a committed
 * word, end to end on the real shipped assets with the production wiring (P3 suffix rules + TATAR
 * fuzzy policy + after-word forms + the global top-frequency fallback).
 *
 * The operator's scenario (2.0.0): after accepting `сәләм` the strip offered only `сәләмә`; after
 * accepting `сәләмә` it went empty. Now: `сәләм` is no bigram head and has exactly one attested
 * form, so the strip is `[сәләмә, һәм, белән]` — the fallback fills the two cells the bigrams and
 * forms leave free; after `сәләмә` (no successors, no forms) it is `[һәм, белән, да]` — the
 * global top words. A bigram head (сәлам) still shows only its successors. The top-8 list is
 * derived from the real asset through the new [TdictPrefixIndex.topFrequentWords] API and pinned
 * as literals.
 */
class TtNextWordFillE2ETest {

    @Test
    fun theTatarTop8DrivesTheFallbackCells() {
        // Derived through the new API and pinned as literals: the order the fallback offers.
        assertEquals(
            listOf("һәм", "белән", "да", "бу", "дә", "дип", "ул", "өчен"),
            requireNotNull(tatarIndex).topFrequentWords(8),
        )
    }

    @Test
    fun committingSyalamOffersItsFormThenTheTopWords() {
        // сәләм: no bigram successors (not a head), one attested form (сәләмә) — then the fill.
        assertEquals(
            listOf("сәләмә", "һәм", "белән"),
            tatarPredict("сәләм"),
        )
    }

    @Test
    fun committingSyalamaFillsAllThreeCellsFromTheTopWords() {
        // сәләмә: no successors and no attested forms — the whole strip is the fill.
        assertEquals(
            listOf("һәм", "белән", "да"),
            tatarPredict("сәләмә"),
        )
    }

    @Test
    fun aBigramHeadStillShowsOnlyItsSuccessors() {
        // сәлам is a head with 4 stored successors; the engine's top-3 fill the strip — no form,
        // no fallback cell. The successor белән is ALSO a top-8 word: the dedup rule is exercised
        // here structurally (it must not appear twice, and the fallback never runs at all).
        val result = tatarPredict("сәлам")
        assertEquals(listOf("биреп", "белән", "бирү"), result)
        assertEquals(result.distinct(), result)
        assertFalse(result.contains("да"))
    }

    @Test
    fun theCommittedWordIsNeverReOffered() {
        // һәм IS the global top word: committing it must not put it back on the strip. It is also
        // a bigram head — its successors fill two cells and the fallback takes the third, minus
        // the committed word itself.
        val result = tatarPredict("һәм")
        assertFalse(result.contains("һәм"))
        assertEquals(3, result.size)
    }

    @Test
    fun aNonHeadRussianWordFillsWithRussianTopWords() {
        // тюлень: in the Russian dictionary, not a bigram head → all three cells are the Russian
        // global top words (the ru engine ships no word-form rules — the pure fill).
        assertEquals(
            listOf("я", "не", "в"),
            russianPredict("тюлень"),
        )
    }

    companion object {
        private var tatarIndex: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null
        private var tatarComputer: CompositePrefixComputer? = null
        private var russianComputer: CompositePrefixComputer? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            tatarIndex = openDictionary(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            russianIndex = openDictionary(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            // The production NEXT_WORD shapes: Tatar — suffix rules + after-word forms + fallback;
            // Russian — no word-form rules, the fallback alone.
            tatarComputer = CompositePrefixComputer(
                requireNotNull(tatarIndex), PersonalCandidateSource.EMPTY,
                TatarSuffixRules.createAfterWordForms(requireNotNull(tatarIndex)),
                GlobalTopFrequencyFallbackFactory.createFallbackWords(requireNotNull(tatarIndex)),
            ).also { it.attachBigramSource(openBigrams(BigramArtifactSpec.TATAR_BIGRAMS_V1, requireNotNull(tatarIndex))) }
            russianComputer = CompositePrefixComputer(
                requireNotNull(russianIndex), PersonalCandidateSource.EMPTY,
                null,
                GlobalTopFrequencyFallbackFactory.createFallbackWords(requireNotNull(russianIndex)),
            ).also { it.attachBigramSource(openBigrams(BigramArtifactSpec.RUSSIAN_BIGRAMS_V1, requireNotNull(russianIndex))) }
        }

        private fun tatarPredict(word: String): List<String> =
            requireNotNull(tatarComputer).predict(prefixOf(word))

        private fun russianPredict(word: String): List<String> =
            requireNotNull(russianComputer).predict(prefixOf(word))

        private fun prefixOf(word: String) =
            ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8))

        private fun openDictionary(spec: DictionaryArtifactSpec): TdictPrefixIndex {
            val raw = inflate(spec)
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                ),
            )
        }

        private fun openBigrams(spec: BigramArtifactSpec, dictionary: TdictPrefixIndex): TatBigrPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-fill-bigr-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                        validated.headCount, validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun inflate(spec: DictionaryArtifactSpec): ByteArray {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-fill-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                return rawFile.readBytes()
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
