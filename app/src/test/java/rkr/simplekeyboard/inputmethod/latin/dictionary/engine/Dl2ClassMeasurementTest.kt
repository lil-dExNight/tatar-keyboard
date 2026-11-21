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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File

/**
 * The DL-2 measurement bridge's class semantics on synthetic dictionaries: variant enumeration,
 * the engine's own ranking rules applied to class candidates, the visible-top-3 hit predicate and
 * every `dl2-advice-v1` guard. No committed asset and no reviewed worksheet is read.
 */
class Dl2ClassMeasurementTest {
    private val ae = listOf('ә' to 'е', 'е' to 'ә')
    private val yeYo = listOf('е' to 'ё')

    @Test
    fun classVariantsEnumerateOneVariantPerRuleAndPosition() {
        assertEquals(listOf("китәм"), Dl2ClassMeasurement.Dl2ClassSemantics.classVariants("китем", ae))
        assertEquals(
            listOf("кәте", "кетә"),
            Dl2ClassMeasurement.Dl2ClassSemantics.classVariants("кете", ae),
        )
        // е→ё is one-directional but positional: every е position yields a variant. The reverse
        // fixture must contain ё and no е at all, so nothing can be substituted back.
        assertEquals(
            listOf("ёще", "ещё"),
            Dl2ClassMeasurement.Dl2ClassSemantics.classVariants("еще", yeYo),
        )
        assertEquals(emptyList<String>(), Dl2ClassMeasurement.Dl2ClassSemantics.classVariants("трёх", yeYo))
    }

    @Test
    fun visibleHitUsesOnlyTheFirstThreeCandidates() {
        val candidates = listOf("а", "б", "в", "г")
        assertEquals(0, Dl2ClassMeasurement.Dl2ClassSemantics.visibleHit(candidates, "г", 3))
        assertEquals(1, Dl2ClassMeasurement.Dl2ClassSemantics.visibleHit(candidates, "в", 3))
        assertEquals(0, Dl2ClassMeasurement.Dl2ClassSemantics.visibleHit(candidates, "", 3))
    }

    @Test
    fun mergedCandidatesFillOnlyCellsLeftFreeByExactCandidates() {
        // Entries must be strictly increasing in unsigned UTF-8 order: е (0x0435) sorts
        // before ә (0x04D9), so китемгә precedes китәм.
        val index = EngineTestFixtures.index(
            listOf("китем" to 40L, "китемгә" to 50L, "китәм" to 30L),
        )
        val baseline = index.lookup(utf8("китем"))
        assertEquals(listOf("китемгә"), baseline)
        val variants = Dl2ClassMeasurement.Dl2ClassSemantics.classVariants("китем", ae)
        val (hits, probes) = Dl2ClassMeasurement.Dl2ClassSemantics.dictionaryVariants(index, variants)
        assertEquals(1, probes)
        assertEquals(listOf("китәм" to 30L), hits.map { it.word to it.frequency })
        val ranked = Dl2ClassMeasurement.Dl2ClassSemantics.rankVariants(hits)
        val merged = Dl2ClassMeasurement.Dl2ClassSemantics.mergedCandidates(ranked, baseline)
        // The exact continuation keeps its cell; the class variant fills the next free one.
        assertEquals(listOf("китемгә", "китәм"), merged)
        assertEquals(1, Dl2ClassMeasurement.Dl2ClassSemantics.visibleHit(merged, "китәм", 3))
    }

    @Test
    fun mergedCandidatesNeverDisplaceAFullExactBaseline() {
        // A typed word with enough exact continuations leaves no free cell, so a class variant
        // must not fabricate a visible hit.
        val baseline = listOf("күрсәтебез", "күрсәтем", "күрсәтегез", "күрсәтелгән")
        val merged = Dl2ClassMeasurement.Dl2ClassSemantics.mergedCandidates(listOf("күрсәтә"), baseline)
        assertEquals(baseline, merged)
        assertEquals(0, Dl2ClassMeasurement.Dl2ClassSemantics.visibleHit(merged, "күрсәтә", 3))
    }

    @Test
    fun mergedCandidatesCapAtTheEngineResultSizeAndNeverDuplicate() {
        val merged = Dl2ClassMeasurement.Dl2ClassSemantics.mergedCandidates(
            listOf("а", "а", "б", "в", "г"),
            listOf("в", "д", "е"),
        )
        assertEquals(listOf("в", "д", "е", "а"), merged)
    }

    @Test
    fun rankVariantsUseFrequencyDescendingThenCodePointOrder() {
        assertEquals(
            listOf("в", "а", "б"),
            Dl2ClassMeasurement.Dl2ClassSemantics.rankVariants(
                listOf(
                    Dl2ClassMeasurement.VariantHit("а", 5),
                    Dl2ClassMeasurement.VariantHit("б", 5),
                    Dl2ClassMeasurement.VariantHit("в", 9),
                ),
            ),
        )
    }

    @Test
    fun classAdviceRequiresOneUnambiguousDictionaryVariantWithinEveryGuard() {
        val semantics = Dl2ClassMeasurement.Dl2ClassSemantics
        val variant = listOf(Dl2ClassMeasurement.VariantHit("китәм", 30))
        assertEquals(
            "китәм",
            semantics.classAdvice(
                "китем", variant, companionContainsWord = false, typedWordInDictionary = false,
                minCandidateFrequency = 10L, armed = false, isArmedSuccessor = { true },
            ),
        )
        // A typed word present in the dictionary is never replaced, as in the production advice.
        assertNull(
            semantics.classAdvice(
                "дигән", listOf(Dl2ClassMeasurement.VariantHit("диген", 100)),
                companionContainsWord = false, typedWordInDictionary = true,
                minCandidateFrequency = 10L, armed = false, isArmedSuccessor = { true },
            ),
        )
        // Word too short, companion word, ambiguity, frequency floor, armed guard.
        assertNull(semantics.classAdvice("кит", variant, false, false, 10L, false) { true })
        assertNull(semantics.classAdvice("китем", variant, true, false, 10L, false) { true })
        assertNull(
            semantics.classAdvice(
                "китем",
                listOf(
                    Dl2ClassMeasurement.VariantHit("китәм", 30),
                    Dl2ClassMeasurement.VariantHit("китемм", 20),
                ),
                false, false, 10L, false,
            ) { true },
        )
        assertNull(
            semantics.classAdvice(
                "китем", variant, false, false, minCandidateFrequency = 31L, armed = false,
            ) { true },
        )
        assertNull(semantics.classAdvice("китем", variant, false, false, 10L, armed = true) { false })
        assertEquals(
            "китәм",
            semantics.classAdvice(
                "китем", variant, false, false, minCandidateFrequency = 10L, armed = true,
            ) { it == "китәм" },
        )
    }

    @Test
    fun dictionaryVariantsCountOneProbePerCandidateVariant() {
        val index = EngineTestFixtures.index(listOf("китәм" to 30L))
        val (hits, probes) = Dl2ClassMeasurement.Dl2ClassSemantics.dictionaryVariants(
            index, listOf("китәм", "кетемә", "китемә"),
        )
        assertEquals(3, probes)
        assertEquals(listOf("китәм"), hits.map { it.word })
    }

    @Test
    fun theBatchHeaderMatchesTheBridgeContract() {
        assertEquals(
            listOf("record_index", "id", "kind", "language", "word", "correction", "predecessor", "class"),
            Dl2ClassMeasurement.BATCH_HEADER,
        )
        assertEquals(
            Dl2ClassMeasurement.RESULT_HEADER.size,
            Dl2ClassMeasurement.RESULT_HEADER.toSet().size,
        )
    }

    @Test
    fun readBatchAcceptsTheNormalFormAndRejectsAnythingElse() {
        val batch = File.createTempFile("dl2-batch-", ".tsv")
        batch.deleteOnExit()
        val header = Dl2ClassMeasurement.BATCH_HEADER.joinToString("\t")
        batch.writeText(
            "$header\n0\tC0000003\trecovery\ttat\tкитем\tкитәм\t\tae\n",
            Charsets.UTF_8,
        )
        val rows = Dl2ClassMeasurement.readBatch(batch)
        assertEquals(1, rows.size)
        assertEquals("китем", rows[0].word)
        assertEquals("китәм", rows[0].correction)

        batch.writeText(
            "$header\n0\tC0000003\trecovery\ttat\tКитЕм\tкитәм\t\tae\n",
            Charsets.UTF_8,
        )
        try {
            Dl2ClassMeasurement.readBatch(batch)
            throw AssertionError("a batch word outside the normalized lookup form must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theClassCatalogCoversSixClassesWithOneLanguageEach() {
        val catalog = Dl2ClassMeasurement.Dl2ClassRules.BY_SLUG
        assertEquals(6, catalog.size)
        assertEquals(setOf("ye-yo"), catalog.values.filter { it.language == "rus" }.map { it.slug }.toSet())
        assertEquals(
            setOf("ae", "oe", "ue", "ng", "hkh"),
            catalog.values.filter { it.language == "tat" }.map { it.slug }.toSet(),
        )
        assertEquals(1, catalog.getValue("ye-yo").substitutions.size)
    }

    private fun utf8(word: String) = ImmutableUtf8Prefix.copyOf(TatarWordUtils.toLookupBytes(word))
}
