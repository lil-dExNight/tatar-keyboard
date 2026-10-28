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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
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
 *  - prefix top-3 and top-1 completion: for each unique word, prefixes of 1/2/3 code points
 *    are looked up in [TdictPrefixIndex] and the word must be in the top-3, respectively lead it;
 *  - bigram head coverage and next-word top-1/top-3 hit rates over adjacent pairs, via
 *    [TatBigrPrefixIndex.predict];
 *  - strip-empty rates: at sentence start and over typing moments (prefix moments while a word
 *    is incomplete, next-word moments over the adjacent pairs), plus the sentence-start top-3
 *    hit rate, via the sentence-start table (`russian_sentstart_v1.txt`, parsed by
 *    [SentStartIndex]) and the production NEXT_WORD chain.
 *
 * The production Russian wiring carries no suffix rules and the RUSSIAN fuzzy policy; the
 * neighbor table is nulled in every measurement, so typo recovery never engages. Assets and eval
 * set are SHA-pinned, so the exact counts below are a deterministic function of committed bytes:
 * re-pin when the assets, the eval set or the ranking change.
 */
class RuSuggestEvalTest {

    @Test
    fun prefixTop3CompletionRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)

        val wordsPerLength = IntArray(4)
        val hitsPerLength = IntArray(4)
        val top1HitsPerLength = IntArray(4)
        for (codePoints in 1..3) {
            for (word in uniqueWords) {
                val prefix = codePointPrefix(word, codePoints) ?: continue
                wordsPerLength[codePoints]++
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
                )
                if (results.contains(word)) hitsPerLength[codePoints]++
                if (results.firstOrNull() == word) top1HitsPerLength[codePoints]++
            }
        }
        for (codePoints in 1..3) {
            val words = wordsPerLength[codePoints]
            val hits = hitsPerLength[codePoints]
            val top1Hits = top1HitsPerLength[codePoints]
            val rate = hits * 100.0 / words
            val top1Rate = top1Hits * 100.0 / words
            println("EVAL|prefix_cp${codePoints}_words|$words")
            println("EVAL|prefix_cp${codePoints}_hits|$hits")
            println("EVAL|prefix_top3_cp${codePoints}_pct|${format(rate)}")
            println("EVAL|prefix_cp${codePoints}_top1_hits|$top1Hits")
            println("EVAL|prefix_top1_cp${codePoints}_pct|${format(top1Rate)}")
        }
        assertEquals(PIN_CP1_WORDS, wordsPerLength[1])
        assertEquals(PIN_CP1_HITS, hitsPerLength[1])
        assertEquals(PIN_CP2_WORDS, wordsPerLength[2])
        assertEquals(PIN_CP2_HITS, hitsPerLength[2])
        assertEquals(PIN_CP3_WORDS, wordsPerLength[3])
        assertEquals(PIN_CP3_HITS, hitsPerLength[3])
        assertEquals(PIN_CP1_TOP1_HITS, top1HitsPerLength[1])
        assertEquals(PIN_CP2_TOP1_HITS, top1HitsPerLength[2])
        assertEquals(PIN_CP3_TOP1_HITS, top1HitsPerLength[3])
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals(PIN_CP1_TOP1_PCT, format(top1HitsPerLength[1] * 100.0 / wordsPerLength[1]))
        assertEquals(PIN_CP2_TOP1_PCT, format(top1HitsPerLength[2] * 100.0 / wordsPerLength[2]))
        assertEquals(PIN_CP3_TOP1_PCT, format(top1HitsPerLength[3] * 100.0 / wordsPerLength[3]))
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

    /**
     * The strip-empty share over typing moments, per answer kind. A prefix moment is one
     * keystroke into a token, up to the completing one; the answer is the exact completion pass
     * of the typed prefix. A next-word moment is one committed-word context (an adjacent pair,
     * the [PIN_PAIRS] denominator); the answer is the full NEXT_WORD chain (bigram successors,
     * top-frequency fallback; the Russian engine has no word-form rules). The chain ends in the
     * fallback, so the next-word side asserts the fallback invariant (never empty) rather than
     * a measured rate. Token-occurrence weighted, not per unique word.
     */
    @Test
    fun stripEmptyMomentRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)
        val chain = chainComputer()
        val predictCache = HashMap<String, List<String>>()
        var prefixMoments = 0
        var prefixEmpty = 0
        var nextwordMoments = 0
        var nextwordEmpty = 0
        for (line in evalLines) {
            val words = line.split(" ")
            for (word in words) {
                // The eval set is BMP-only, so char splits are code-point splits here.
                for (codePoints in 1 until word.length) {
                    prefixMoments++
                    val shown = index.lookup(
                        ImmutableUtf8Prefix.copyOf(
                            word.substring(0, codePoints).toByteArray(Charsets.UTF_8)
                        )
                    )
                    if (shown.isEmpty()) prefixEmpty++
                }
            }
            for (position in 0 until words.size - 1) {
                nextwordMoments++
                val shown = predictCache.getOrPut(words[position]) {
                    chain.predict(
                        ImmutableUtf8Prefix.copyOf(
                            words[position].toByteArray(Charsets.UTF_8)
                        )
                    )
                }
                if (shown.isEmpty()) nextwordEmpty++
            }
        }
        println("EVAL|strip_empty_prefix_moments|$prefixMoments")
        println("EVAL|strip_empty_prefix_empty|$prefixEmpty")
        println("EVAL|strip_empty_prefix_pct|${format(prefixEmpty * 100.0 / prefixMoments)}")
        println("EVAL|strip_empty_nextword_moments|$nextwordMoments")
        println("EVAL|strip_empty_nextword_empty|$nextwordEmpty")
        println("EVAL|strip_empty_nextword_pct|${format(nextwordEmpty * 100.0 / nextwordMoments)}")
        assertEquals(PIN_STRIP_EMPTY_PREFIX_MOMENTS, prefixMoments)
        assertEquals(PIN_STRIP_EMPTY_PREFIX_EMPTY, prefixEmpty)
        assertEquals(PIN_PAIRS, nextwordMoments)
        assertEquals(0, nextwordEmpty)
        // Cross-implementation pins: scripts/suggest_eval.py --language rus must print the same.
        assertEquals(PIN_STRIP_EMPTY_PREFIX_PCT, format(prefixEmpty * 100.0 / prefixMoments))
        assertEquals("0.0000", format(nextwordEmpty * 100.0 / nextwordMoments))
    }

    /**
     * The context boost on the prefix path: exact-pass candidates among the previous committed
     * word's packed bigram successors lead the strip. Measured over the token-occurrence typing
     * moments at prefix length >= 2 code points; the control is the same moments unarmed. The
     * top-1 delta and its sentence-level paired-bootstrap CI95 (the SplitMix64 stream, seed and
     * rounds shared with the python harness) are cross-pinned with
     * scripts/suggest_eval.py --language rus (the ctxboost_* rows); the top-3 delta is the
     * non-regression reading. The Russian engine has no same-stem boost, so the context track is
     * the only reordering.
     */
    @Test
    fun contextBoostedCompletionDeltaOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        val bigrams = requireNotNull(bigramIndex)
        index.updateKeyNeighbors(null)
        val ids = IntArray(TatBigrPrefixIndex.MAX_RESULTS)
        val moments = IntArray(evalLines.size)
        val baseTop1 = IntArray(evalLines.size)
        val boostTop1 = IntArray(evalLines.size)
        val baseTop3 = IntArray(evalLines.size)
        val boostTop3 = IntArray(evalLines.size)
        for (lineIndex in evalLines.indices) {
            val words = evalLines[lineIndex].split(" ")
            for (position in words.indices) {
                val contextCount = if (position > 0) {
                    bigrams.collectSuccessorIds(
                        ImmutableUtf8Prefix.copyOf(words[position - 1].toByteArray(Charsets.UTF_8)),
                        ids,
                    )
                } else {
                    0
                }
                val word = words[position]
                // The eval set is BMP-only, so char splits are code-point splits here.
                for (codePoints in 2 until word.length) {
                    val query = ImmutableUtf8Prefix.copyOf(
                        word.substring(0, codePoints).toByteArray(Charsets.UTF_8)
                    )
                    moments[lineIndex]++
                    val base = index.lookup(query)
                    if (base.firstOrNull() == word) baseTop1[lineIndex]++
                    if (base.contains(word)) baseTop3[lineIndex]++
                    val boost = index.lookup(query, ids, contextCount)
                    if (boost.firstOrNull() == word) boostTop1[lineIndex]++
                    if (boost.contains(word)) boostTop3[lineIndex]++
                }
            }
        }
        val totalMoments = moments.sum()
        val base1 = baseTop1.sum()
        val boost1 = boostTop1.sum()
        val base3 = baseTop3.sum()
        val boost3 = boostTop3.sum()
        // The paired CI95: one SplitMix64 stream, both arms scored on the same drawn sentence
        // indices, nearest-rank 25/975 per-mille of the per-round deltas.
        val deltas = DoubleArray(BOOTSTRAP_ROUNDS)
        var state = splitmix64(BOOTSTRAP_SEED)
        for (round in 0 until BOOTSTRAP_ROUNDS) {
            var base = 0
            var boost = 0
            var weight = 0
            repeat(evalLines.size) {
                val drawn = java.lang.Long.remainderUnsigned(state, evalLines.size.toLong()).toInt()
                state = splitmix64(state)
                base += baseTop1[drawn]
                boost += boostTop1[drawn]
                weight += moments[drawn]
            }
            deltas[round] = (boost - base) * 100.0 / weight
        }
        deltas.sort()
        val ciLo = deltas[nearestRankIndex(25, BOOTSTRAP_ROUNDS)]
        val ciHi = deltas[nearestRankIndex(975, BOOTSTRAP_ROUNDS)]
        println("EVAL|ctxboost_cp2plus_moments|$totalMoments")
        println("EVAL|ctxboost_cp2plus_top1_base_hits|$base1")
        println("EVAL|ctxboost_cp2plus_top1_base_pct|${format(ratePct(base1, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_top1_boost_hits|$boost1")
        println("EVAL|ctxboost_cp2plus_top1_boost_pct|${format(ratePct(boost1, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_top1_delta_pp|${formatSigned(ratePct(boost1 - base1, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_top1_delta_ci95_lo|${formatSigned(ciLo)}")
        println("EVAL|ctxboost_cp2plus_top1_delta_ci95_hi|${formatSigned(ciHi)}")
        println("EVAL|ctxboost_cp2plus_top3_base_pct|${format(ratePct(base3, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_top3_boost_pct|${format(ratePct(boost3, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_top3_delta_pp|${formatSigned(ratePct(boost3 - base3, totalMoments))}")

        assertEquals(PIN_CTXBOOST_CP2PLUS_MOMENTS, totalMoments)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BASE_HITS, base1)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_HITS, boost1)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP3_BASE_HITS, base3)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP3_BOOST_HITS, boost3)
        // Cross-implementation pins: scripts/suggest_eval.py --language rus must print the same.
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BASE_PCT, format(ratePct(base1, totalMoments)))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_PCT, format(ratePct(boost1, totalMoments)))
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_TOP1_DELTA, formatSigned(ratePct(boost1 - base1, totalMoments)),
        )
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_CI95_LO, formatSigned(ciLo))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_CI95_HI, formatSigned(ciHi))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP3_BASE_PCT, format(ratePct(base3, totalMoments)))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP3_BOOST_PCT, format(ratePct(boost3, totalMoments)))
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_TOP3_DELTA, formatSigned(ratePct(boost3 - base3, totalMoments)),
        )
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

    /**
     * The production Russian NEXT_WORD wiring: bundled bigrams, empty personal sources and the
     * top-frequency fallback. The Russian engine has no word-form rules, so the after-word
     * forms slot stays null.
     */
    private fun chainComputer(): CompositePrefixComputer {
        val index = requireNotNull(dictionaryIndex)
        return CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY, null,
            GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
        ).also { it.attachBigramSource(requireNotNull(bigramIndex)) }
    }

    private fun codePointPrefix(word: String, codePoints: Int): String? {
        val cps = word.codePoints().toArray()
        if (cps.size < codePoints) return null
        val builder = StringBuilder(codePoints)
        for (slot in 0 until codePoints) builder.appendCodePoint(cps[slot])
        return builder.toString()
    }

    /** One SplitMix64 output for [state] (matches scripts/typo_pack.py bit for bit). */
    private fun splitmix64(state: Long): Long {
        var z = state + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Zero-based index of the [perMille]/1000 percentile, nearest-rank: ceil(p*N/1000)-1. */
    private fun nearestRankIndex(perMille: Int, size: Int): Int = (perMille * size + 999) / 1000 - 1

    private fun ratePct(part: Int, whole: Int): Double = if (whole > 0) part * 100.0 / whole else 0.0

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun formatSigned(value: Double): String = "%+.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // Pins over the committed assets and eval set; re-pin when either changes (see class KDoc).
        private const val PIN_EVAL_LINES = 1_000
        private const val PIN_UNIQUE_WORDS = 2_218
        private const val PIN_PAIRS = 4_466
        private const val PIN_COVERED = 4_010
        private const val PIN_TOP1_HITS = 349
        private const val PIN_TOP3_HITS = 794
        private const val PIN_COVERED_PCT = "89.7895"
        private const val PIN_TOP1_PCT = "7.8146"
        private const val PIN_TOP3_PCT = "17.7788"
        private const val PIN_TOP3_COVERED_PCT = "19.8005"
        private const val PIN_CP1_WORDS = 2_218
        private const val PIN_CP2_WORDS = 2_210
        private const val PIN_CP3_WORDS = 2_179
        private const val PIN_CP1_HITS = 90
        private const val PIN_CP2_HITS = 425
        private const val PIN_CP3_HITS = 880
        private const val PIN_CP1_TOP1_HITS = 23
        private const val PIN_CP2_TOP1_HITS = 145
        private const val PIN_CP3_TOP1_HITS = 391
        private const val PIN_CP1_TOP1_PCT = "1.0370"
        private const val PIN_CP2_TOP1_PCT = "6.5611"
        private const val PIN_CP3_TOP1_PCT = "17.9440"
        private const val PIN_STRIP_EMPTY_PREFIX_MOMENTS = 20_556
        private const val PIN_STRIP_EMPTY_PREFIX_EMPTY = 283
        private const val PIN_STRIP_EMPTY_PREFIX_PCT = "1.3767"
        private const val PIN_SENTSTART_TOP3_HITS = 194
        // The context boost over the cp>=2 prefix moments, cross-pinned with the ctxboost_* rows
        // of scripts/suggest_eval.py --language rus; the CI95 is the paired sentence-level
        // bootstrap (the seed and rounds are shared with scripts/suggest_chain.py).
        private const val PIN_CTXBOOST_CP2PLUS_MOMENTS = 15_636
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_HITS = 5_968
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_HITS = 6_270
        private const val PIN_CTXBOOST_CP2PLUS_TOP3_BASE_HITS = 9_800
        private const val PIN_CTXBOOST_CP2PLUS_TOP3_BOOST_HITS = 9_906
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_PCT = "38.1683"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_PCT = "40.0998"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_DELTA = "+1.9314"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_LO = "+1.5925"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_HI = "+2.2991"
        private const val PIN_CTXBOOST_CP2PLUS_TOP3_BASE_PCT = "62.6759"
        private const val PIN_CTXBOOST_CP2PLUS_TOP3_BOOST_PCT = "63.3538"
        private const val PIN_CTXBOOST_CP2PLUS_TOP3_DELTA = "+0.6779"

        // Bootstrap knobs, shared with scripts/suggest_chain.py; changing them re-pins the CI.
        private const val BOOTSTRAP_SEED = 20261001L
        private const val BOOTSTRAP_ROUNDS = 2_000

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

            // The production Russian engine is constructed with no suffix rules and the RUSSIAN
            // fuzzy policy; the eval index is opened the same way. The neighbor table stays null
            // in every measurement below, so the typo-recovery pass never engages.
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
                        fuzzyEditPolicy = FuzzyEditPolicy.RUSSIAN,
                        autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
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
