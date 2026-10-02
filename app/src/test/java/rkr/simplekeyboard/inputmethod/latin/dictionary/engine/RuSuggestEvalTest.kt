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
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.SentStartIndex
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripState
import java.io.File
import java.nio.ByteBuffer

/**
 * Runs the real shipped Russian dictionary and bigram indexes over the held-out eval set
 * `app/src/test/resources/ru_eval_sentences.txt` (built by `scripts/make_ru_eval_set.py` from
 * Tatoeba lines whose normalized form never appears in a training corpus) and prints
 * `EVAL|metric|value` lines:
 *  - prefix top-3 completion: for each unique word, prefixes of 1/2/3 code points are looked up
 *    in [TdictPrefixIndex] and the word must be in the top-3;
 *  - bigram head coverage and next-word top-1/top-3 hit rates over adjacent pairs, via
 *    [TatBigrPrefixIndex.predict];
 *  - strip-empty at sentence start and sentence-start top-3 hit rate, via the sentence-start
 *    table (`russian_sentstart_v1.txt`, parsed by [SentStartIndex]).
 *
 * The production Russian wiring carries no suffix rules and no fuzzy policy. Assets and eval set
 * are SHA-pinned, so the exact counts below are a deterministic function of committed bytes:
 * re-pin when the assets, the eval set or the ranking change.
 */
class RuSuggestEvalTest {

    @Test
    fun prefixTop3CompletionRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)

        val wordsPerLength = IntArray(4)
        val hitsPerLength = IntArray(4)
        for (codePoints in 1..3) {
            for (word in uniqueWords) {
                val prefix = codePointPrefix(word, codePoints) ?: continue
                wordsPerLength[codePoints]++
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
                )
                if (results.contains(word)) hitsPerLength[codePoints]++
            }
        }
        for (codePoints in 1..3) {
            val words = wordsPerLength[codePoints]
            val hits = hitsPerLength[codePoints]
            val rate = hits * 100.0 / words
            println("EVAL|prefix_cp${codePoints}_words|$words")
            println("EVAL|prefix_cp${codePoints}_hits|$hits")
            println("EVAL|prefix_top3_cp${codePoints}_pct|${format(rate)}")
        }
        assertEquals(PIN_CP1_WORDS, wordsPerLength[1])
        assertEquals(PIN_CP1_HITS, hitsPerLength[1])
        assertEquals(PIN_CP2_WORDS, wordsPerLength[2])
        assertEquals(PIN_CP2_HITS, hitsPerLength[2])
        assertEquals(PIN_CP3_WORDS, wordsPerLength[3])
        assertEquals(PIN_CP3_HITS, hitsPerLength[3])
    }

    @Test
    fun nextWordTop1AndTop3HitRatesOnTheEvalSet() {
        val bigrams = requireNotNull(bigramIndex)

        var pairs = 0
        var covered = 0
        var top1Hits = 0
        var top3Hits = 0
        for (line in evalLines) {
            val words = line.split(" ")
            for (position in 0 until words.size - 1) {
                pairs++
                val shown = bigrams.predict(
                    ImmutableUtf8Prefix.copyOf(words[position].toByteArray(Charsets.UTF_8))
                )
                if (shown.isEmpty()) continue
                covered++
                if (shown.first() == words[position + 1]) top1Hits++
                if (shown.contains(words[position + 1])) top3Hits++
            }
        }
        val coveredPct = covered * 100.0 / pairs
        val top1Pct = top1Hits * 100.0 / pairs
        val top3Pct = top3Hits * 100.0 / pairs
        val top3CoveredPct = if (covered > 0) top3Hits * 100.0 / covered else 0.0
        println("EVAL|nextword_pairs|$pairs")
        println("EVAL|nextword_head_covered|$covered")
        println("EVAL|nextword_top1_hits|$top1Hits")
        println("EVAL|nextword_top3_hits|$top3Hits")
        println("EVAL|bigram_head_coverage_pct|${format(coveredPct)}")
        println("EVAL|nextword_top1_hit_pct|${format(top1Pct)}")
        println("EVAL|nextword_top3_hit_pct|${format(top3Pct)}")
        println("EVAL|nextword_top3_hit_covered_pct|${format(top3CoveredPct)}")

        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_COVERED, covered)
        assertEquals(PIN_TOP1_HITS, top1Hits)
        assertEquals(PIN_TOP3_HITS, top3Hits)
        // Exact-rate pins, a deterministic consequence of the count pins; they catch
        // formatting or rounding drift in the report itself.
        assertEquals(PIN_COVERED_PCT, format(coveredPct))
        assertEquals(PIN_TOP1_PCT, format(top1Pct))
        assertEquals(PIN_TOP3_PCT, format(top3Pct))
        assertEquals(PIN_TOP3_COVERED_PCT, format(top3CoveredPct))
    }

    /**
     * At a sentence start the strip answers from the committed sentence-start table, so
     * `strip_empty_sentence_start_pct` is 0; the hit rate is the fraction of eval sentences whose
     * first word is among the top-3 suggestions. An empty typed prefix still has no completions,
     * and the bigram table is never queried without a context word.
     */
    @Test
    fun sentenceStartPredictionsOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)
        assertTrue(
            "an empty prefix must not produce prefix candidates",
            index.lookup(ImmutableUtf8Prefix.copyOf(ByteArray(0))).isEmpty(),
        )

        val sentStart = requireNotNull(sentStartIndex)
        val shown = sentStart.topWords(SuggestionStripState.CELL_COUNT)
        var empty = 0
        var hits = 0
        for (line in evalLines) {
            if (shown.isEmpty()) empty++
            if (shown.contains(line.split(" ")[0])) hits++
        }
        val emptyRate = empty * 100.0 / evalLines.size
        val hitRate = hits * 100.0 / evalLines.size
        println("EVAL|strip_empty_sentence_start_pct|${format(emptyRate)}")
        println("EVAL|sentstart_top3_hits|$hits")
        println("EVAL|sentstart_top3_hit_pct|${format(hitRate)}")
        assertEquals(0, empty)
        assertEquals("0.0000", format(emptyRate))
        assertEquals(PIN_SENTSTART_TOP3_HITS, hits)
    }

    @Test
    fun evalSetShapeMatchesThePinnedSet() {
        assertEquals(PIN_EVAL_LINES, evalLines.size)
        assertEquals(PIN_UNIQUE_WORDS, uniqueWords.size)
        for (line in evalLines) {
            val words = line.split(" ")
            assertTrue("eval lines carry 3..12 words", words.size in 3..12)
        }
    }

    private fun codePointPrefix(word: String, codePoints: Int): String? {
        val cps = word.codePoints().toArray()
        if (cps.size < codePoints) return null
        val builder = StringBuilder(codePoints)
        for (slot in 0 until codePoints) builder.appendCodePoint(cps[slot])
        return builder.toString()
    }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // Pins over the committed assets and eval set; re-pin when either changes (see class KDoc).
        private const val PIN_EVAL_LINES = 1_000
        private const val PIN_UNIQUE_WORDS = 2_218
        private const val PIN_PAIRS = 4_466
        private const val PIN_COVERED = 4_010
        private const val PIN_TOP1_HITS = 349
        private const val PIN_TOP3_HITS = 673
        private const val PIN_COVERED_PCT = "89.7895"
        private const val PIN_TOP1_PCT = "7.8146"
        private const val PIN_TOP3_PCT = "15.0694"
        private const val PIN_TOP3_COVERED_PCT = "16.7830"
        private const val PIN_CP1_WORDS = 2_218
        private const val PIN_CP2_WORDS = 2_210
        private const val PIN_CP3_WORDS = 2_179
        private const val PIN_CP1_HITS = 67
        private const val PIN_CP2_HITS = 354
        private const val PIN_CP3_HITS = 768
        private const val PIN_SENTSTART_TOP3_HITS = 31

        private lateinit var evalLines: List<String>
        private lateinit var uniqueWords: List<String>
        private var dictionaryIndex: TdictPrefixIndex? = null
        private var bigramIndex: TatBigrPrefixIndex? = null
        private var sentStartIndex: SentStartIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndEvalSet() {
            evalLines = locate(
                "src/test/resources/ru_eval_sentences.txt",
                "app/src/test/resources/ru_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            uniqueWords = evalLines.flatMap { it.split(" ") }.distinct()

            // The committed sentence-start table, parsed through the production reader.
            val sentStart = SentStartIndex.parse(
                locate(
                    "src/main/assets/dictionaries/russian_sentstart_v1.txt",
                    "app/src/main/assets/dictionaries/russian_sentstart_v1.txt",
                ).readText(Charsets.UTF_8),
            )
            check(!sentStart.isEmpty) { "the committed sentence-start table failed to parse" }
            sentStartIndex = sentStart

            // The production Russian engine is constructed with no suffix rules and no fuzzy
            // policy; the eval index is opened the same way.
            val dictSpec = DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
            val dictAsset = locate(
                "src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib",
            )
            val dictRawFile = File.createTempFile("ru-suggest-eval-dict-", ".tdict")
            val dictionary: TdictPrefixIndex
            try {
                dictRawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(dictAsset.inputStream(), output, dictSpec)
                }
                val validated = TdictValidator().validateRaw(dictRawFile, dictSpec)
                val identity = DictionaryIdentity(
                    dictSpec.generation, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                dictionary = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(dictRawFile.readBytes()), identity,
                        validated.entryCount, validated.rawSize,
                    ),
                )
            } finally {
                dictRawFile.delete()
            }
            dictionaryIndex = dictionary

            val asset = locate(
                "src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
                "app/src/main/assets/bigrams/russian_bigrams_v1.tatbigr.zlib",
            )
            val spec = BigramArtifactSpec.RUSSIAN_BIGRAMS_V1
            val rawFile = File.createTempFile("ru-suggest-eval-bigrams-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation, spec.fileLanguageTag, validated.schemaId,
                    validated.formatVersion, validated.rawSha256,
                )
                bigramIndex = TatBigrPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()), identity, dictionary,
                    validated.headCount, validated.rawSize,
                )
                check(bigramIndex != null)
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")
    }
}
