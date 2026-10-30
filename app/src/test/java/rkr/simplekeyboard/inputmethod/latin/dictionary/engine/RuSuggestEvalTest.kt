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
 *  - prefix completion and next-word prediction report top-1, visible top-3 and engine top-4
 *    separately, for both the plain bigram path and the production NEXT_WORD chain;
 *  - bigram head coverage over adjacent pairs, via [TatBigrPrefixIndex.predict];
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
        val visibleTop3HitsPerLength = IntArray(4)
        val engineTop4HitsPerLength = IntArray(4)
        val top1HitsPerLength = IntArray(4)
        for (codePoints in 1..3) {
            for (word in uniqueWords) {
                val prefix = codePointPrefix(word, codePoints) ?: continue
                wordsPerLength[codePoints]++
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
                )
                if (results.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                    visibleTop3HitsPerLength[codePoints]++
                }
                if (results.take(SuggestionStripState.CELL_COUNT).contains(word)) {
                    engineTop4HitsPerLength[codePoints]++
                }
                if (results.firstOrNull() == word) top1HitsPerLength[codePoints]++
            }
        }
        for (codePoints in 1..3) {
            val words = wordsPerLength[codePoints]
            val visibleHits = visibleTop3HitsPerLength[codePoints]
            val engineHits = engineTop4HitsPerLength[codePoints]
            val top1Hits = top1HitsPerLength[codePoints]
            println("EVAL|prefix_cp${codePoints}_words|$words")
            println("EVAL|prefix_cp${codePoints}_top1_hits|$top1Hits")
            println("EVAL|prefix_top1_cp${codePoints}_pct|${format(ratePct(top1Hits, words))}")
            println("EVAL|prefix_cp${codePoints}_visible_top3_hits|$visibleHits")
            println("EVAL|prefix_visible_top3_cp${codePoints}_pct|${format(ratePct(visibleHits, words))}")
            println("EVAL|prefix_cp${codePoints}_engine_top4_hits|$engineHits")
            println("EVAL|prefix_engine_top4_cp${codePoints}_pct|${format(ratePct(engineHits, words))}")
        }
        assertEquals(PIN_CP1_WORDS, wordsPerLength[1])
        assertEquals(PIN_CP1_VISIBLE_TOP3_HITS, visibleTop3HitsPerLength[1])
        assertEquals(PIN_CP1_ENGINE_TOP4_HITS, engineTop4HitsPerLength[1])
        assertEquals(PIN_CP2_WORDS, wordsPerLength[2])
        assertEquals(PIN_CP2_VISIBLE_TOP3_HITS, visibleTop3HitsPerLength[2])
        assertEquals(PIN_CP2_ENGINE_TOP4_HITS, engineTop4HitsPerLength[2])
        assertEquals(PIN_CP3_WORDS, wordsPerLength[3])
        assertEquals(PIN_CP3_VISIBLE_TOP3_HITS, visibleTop3HitsPerLength[3])
        assertEquals(PIN_CP3_ENGINE_TOP4_HITS, engineTop4HitsPerLength[3])
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
        var visibleHits = 0
        var engineHits = 0
        for (line in evalLines) {
            val words = line.split(" ")
            for (position in 0 until words.size - 1) {
                pairs++
                val shown = bigrams.predict(
                    ImmutableUtf8Prefix.copyOf(words[position].toByteArray(Charsets.UTF_8))
                )
                if (shown.isEmpty()) continue
                covered++
                val successor = words[position + 1]
                if (shown.first() == successor) top1Hits++
                if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(successor)) {
                    visibleHits++
                }
                if (shown.take(SuggestionStripState.CELL_COUNT).contains(successor)) engineHits++
            }
        }
        println("EVAL|nextword_pairs|$pairs")
        println("EVAL|nextword_head_covered|$covered")
        println("EVAL|nextword_top1_hits|$top1Hits")
        println("EVAL|nextword_top1_hit_pct|${format(ratePct(top1Hits, pairs))}")
        println("EVAL|nextword_visible_top3_hits|$visibleHits")
        println("EVAL|nextword_visible_top3_hit_pct|${format(ratePct(visibleHits, pairs))}")
        println("EVAL|nextword_visible_top3_hit_covered_pct|${format(ratePct(visibleHits, covered))}")
        println("EVAL|nextword_engine_top4_hits|$engineHits")
        println("EVAL|nextword_engine_top4_hit_pct|${format(ratePct(engineHits, pairs))}")
        println("EVAL|nextword_engine_top4_hit_covered_pct|${format(ratePct(engineHits, covered))}")
        println("EVAL|bigram_head_coverage_pct|${format(ratePct(covered, pairs))}")

        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_COVERED, covered)
        assertEquals(PIN_TOP1_HITS, top1Hits)
        assertEquals(PIN_BIGRAM_VISIBLE_TOP3_HITS, visibleHits)
        assertEquals(PIN_BIGRAM_ENGINE_TOP4_HITS, engineHits)
        // Exact-rate pins, a deterministic consequence of the count pins; they catch
        // formatting or rounding drift in the report itself.
        assertEquals(PIN_COVERED_PCT, format(ratePct(covered, pairs)))
        assertEquals(PIN_TOP1_PCT, format(ratePct(top1Hits, pairs)))
        assertEquals(PIN_VISIBLE_TOP3_PCT, format(ratePct(visibleHits, pairs)))
        assertEquals(PIN_VISIBLE_TOP3_COVERED_PCT, format(ratePct(visibleHits, covered)))
        assertEquals(PIN_ENGINE_TOP4_PCT, format(ratePct(engineHits, pairs)))
        assertEquals(PIN_ENGINE_TOP4_COVERED_PCT, format(ratePct(engineHits, covered)))
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
        var top1Hits = 0
        var visibleHits = 0
        var engineHits = 0
        for (line in evalLines) {
            if (shown.isEmpty()) empty++
            val firstWord = line.split(" ")[0]
            if (shown.firstOrNull() == firstWord) top1Hits++
            if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(firstWord)) {
                visibleHits++
            }
            if (shown.take(SuggestionStripState.CELL_COUNT).contains(firstWord)) engineHits++
        }
        println("EVAL|strip_empty_sentence_start_pct|${format(ratePct(empty, evalLines.size))}")
        println("EVAL|sentstart_top1_hits|$top1Hits")
        println("EVAL|sentstart_top1_hit_pct|${format(ratePct(top1Hits, evalLines.size))}")
        println("EVAL|sentstart_visible_top3_hits|$visibleHits")
        println("EVAL|sentstart_visible_top3_hit_pct|${format(ratePct(visibleHits, evalLines.size))}")
        println("EVAL|sentstart_engine_top4_hits|$engineHits")
        println("EVAL|sentstart_engine_top4_hit_pct|${format(ratePct(engineHits, evalLines.size))}")
        assertEquals(0, empty)
        assertEquals(PIN_SENTSTART_TOP1_HITS, top1Hits)
        assertEquals(PIN_SENTSTART_VISIBLE_TOP3_HITS, visibleHits)
        assertEquals(PIN_SENTSTART_ENGINE_TOP4_HITS, engineHits)
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
        val baseVisibleTop3 = IntArray(evalLines.size)
        val boostVisibleTop3 = IntArray(evalLines.size)
        val baseEngineTop4 = IntArray(evalLines.size)
        val boostEngineTop4 = IntArray(evalLines.size)
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
                    if (base.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                        baseVisibleTop3[lineIndex]++
                    }
                    if (base.take(SuggestionStripState.CELL_COUNT).contains(word)) {
                        baseEngineTop4[lineIndex]++
                    }
                    val boost = index.lookup(query, ids, contextCount)
                    if (boost.firstOrNull() == word) boostTop1[lineIndex]++
                    if (boost.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                        boostVisibleTop3[lineIndex]++
                    }
                    if (boost.take(SuggestionStripState.CELL_COUNT).contains(word)) {
                        boostEngineTop4[lineIndex]++
                    }
                }
            }
        }
        val totalMoments = moments.sum()
        val base1 = baseTop1.sum()
        val boost1 = boostTop1.sum()
        val baseVisible = baseVisibleTop3.sum()
        val boostVisible = boostVisibleTop3.sum()
        val baseEngine = baseEngineTop4.sum()
        val boostEngine = boostEngineTop4.sum()
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
        println("EVAL|ctxboost_cp2plus_visible_top3_base_hits|$baseVisible")
        println("EVAL|ctxboost_cp2plus_visible_top3_base_pct|${format(ratePct(baseVisible, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_visible_top3_boost_hits|$boostVisible")
        println("EVAL|ctxboost_cp2plus_visible_top3_boost_pct|${format(ratePct(boostVisible, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_visible_top3_delta_pp|${formatSigned(ratePct(boostVisible - baseVisible, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_engine_top4_base_hits|$baseEngine")
        println("EVAL|ctxboost_cp2plus_engine_top4_base_pct|${format(ratePct(baseEngine, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_engine_top4_boost_hits|$boostEngine")
        println("EVAL|ctxboost_cp2plus_engine_top4_boost_pct|${format(ratePct(boostEngine, totalMoments))}")
        println("EVAL|ctxboost_cp2plus_engine_top4_delta_pp|${formatSigned(ratePct(boostEngine - baseEngine, totalMoments))}")

        assertEquals(PIN_CTXBOOST_CP2PLUS_MOMENTS, totalMoments)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BASE_HITS, base1)
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_HITS, boost1)
        assertEquals(PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_HITS, baseVisible)
        assertEquals(PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_HITS, boostVisible)
        assertEquals(PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_HITS, baseEngine)
        assertEquals(PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_HITS, boostEngine)
        // Cross-implementation pins: scripts/suggest_eval.py --language rus must print the same.
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BASE_PCT, format(ratePct(base1, totalMoments)))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_PCT, format(ratePct(boost1, totalMoments)))
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_TOP1_DELTA, formatSigned(ratePct(boost1 - base1, totalMoments)),
        )
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_CI95_LO, formatSigned(ciLo))
        assertEquals(PIN_CTXBOOST_CP2PLUS_TOP1_CI95_HI, formatSigned(ciHi))
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_PCT,
            format(ratePct(baseVisible, totalMoments)),
        )
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_PCT,
            format(ratePct(boostVisible, totalMoments)),
        )
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_DELTA,
            formatSigned(ratePct(boostVisible - baseVisible, totalMoments)),
        )
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_PCT, format(ratePct(baseEngine, totalMoments)),
        )
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_PCT, format(ratePct(boostEngine, totalMoments)),
        )
        assertEquals(
            PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_DELTA,
            formatSigned(ratePct(boostEngine - baseEngine, totalMoments)),
        )
    }

    /** Measures the production NEXT_WORD chain at all three reporting widths. */
    @Test
    fun nextWordChainHitRatesOnTheEvalSet() {
        val chain = chainComputer()
        val predictCache = HashMap<String, List<String>>()
        var pairs = 0
        var top1Hits = 0
        var visibleHits = 0
        var engineHits = 0
        for (line in evalLines) {
            val words = line.split(" ")
            for (position in 0 until words.size - 1) {
                pairs++
                val shown = predictCache.getOrPut(words[position]) {
                    chain.predict(
                        ImmutableUtf8Prefix.copyOf(words[position].toByteArray(Charsets.UTF_8))
                    )
                }
                val successor = words[position + 1]
                if (shown.firstOrNull() == successor) top1Hits++
                if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(successor)) {
                    visibleHits++
                }
                if (shown.take(SuggestionStripState.CELL_COUNT).contains(successor)) engineHits++
            }
        }
        println("EVAL|nextword_chain_pairs|$pairs")
        println("EVAL|nextword_chain_top1_hits|$top1Hits")
        println("EVAL|nextword_chain_top1_pct|${format(ratePct(top1Hits, pairs))}")
        println("EVAL|nextword_chain_visible_top3_hits|$visibleHits")
        println("EVAL|nextword_chain_visible_top3_pct|${format(ratePct(visibleHits, pairs))}")
        println("EVAL|nextword_chain_engine_top4_hits|$engineHits")
        println("EVAL|nextword_chain_engine_top4_pct|${format(ratePct(engineHits, pairs))}")
        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_CHAIN_TOP1_HITS, top1Hits)
        assertEquals(PIN_CHAIN_VISIBLE_TOP3_HITS, visibleHits)
        assertEquals(PIN_CHAIN_ENGINE_TOP4_HITS, engineHits)
        assertEquals("7.8594", format(ratePct(top1Hits, pairs)))
        assertEquals("15.7412", format(ratePct(visibleHits, pairs)))
        assertEquals("18.6520", format(ratePct(engineHits, pairs)))
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

    /** The production Russian NEXT_WORD wiring: bundled bigrams and the top-frequency fallback. */
    private fun chainComputer(): CompositePrefixComputer {
        val index = requireNotNull(dictionaryIndex)
        return CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY,
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
        private const val PIN_BIGRAM_VISIBLE_TOP3_HITS = 673
        private const val PIN_BIGRAM_ENGINE_TOP4_HITS = 794
        private const val PIN_COVERED_PCT = "89.7895"
        private const val PIN_TOP1_PCT = "7.8146"
        private const val PIN_VISIBLE_TOP3_PCT = "15.0694"
        private const val PIN_VISIBLE_TOP3_COVERED_PCT = "16.7830"
        private const val PIN_ENGINE_TOP4_PCT = "17.7788"
        private const val PIN_ENGINE_TOP4_COVERED_PCT = "19.8005"
        private const val PIN_CHAIN_TOP1_HITS = 351
        private const val PIN_CHAIN_VISIBLE_TOP3_HITS = 703
        private const val PIN_CHAIN_ENGINE_TOP4_HITS = 833
        private const val PIN_CP1_WORDS = 2_218
        private const val PIN_CP2_WORDS = 2_210
        private const val PIN_CP3_WORDS = 2_179
        private const val PIN_CP1_VISIBLE_TOP3_HITS = 67
        private const val PIN_CP2_VISIBLE_TOP3_HITS = 354
        private const val PIN_CP3_VISIBLE_TOP3_HITS = 768
        private const val PIN_CP1_ENGINE_TOP4_HITS = 90
        private const val PIN_CP2_ENGINE_TOP4_HITS = 425
        private const val PIN_CP3_ENGINE_TOP4_HITS = 880
        private const val PIN_CP1_TOP1_HITS = 23
        private const val PIN_CP2_TOP1_HITS = 145
        private const val PIN_CP3_TOP1_HITS = 391
        private const val PIN_CP1_TOP1_PCT = "1.0370"
        private const val PIN_CP2_TOP1_PCT = "6.5611"
        private const val PIN_CP3_TOP1_PCT = "17.9440"
        private const val PIN_STRIP_EMPTY_PREFIX_MOMENTS = 20_556
        private const val PIN_STRIP_EMPTY_PREFIX_EMPTY = 283
        private const val PIN_STRIP_EMPTY_PREFIX_PCT = "1.3767"
        private const val PIN_SENTSTART_TOP1_HITS = 22
        private const val PIN_SENTSTART_VISIBLE_TOP3_HITS = 187
        private const val PIN_SENTSTART_ENGINE_TOP4_HITS = 194
        // The context boost over the cp>=2 prefix moments, cross-pinned with the ctxboost_* rows
        // of scripts/suggest_eval.py --language rus; the CI95 is the paired sentence-level
        // bootstrap (the seed and rounds are shared with scripts/suggest_chain.py).
        private const val PIN_CTXBOOST_CP2PLUS_MOMENTS = 15_636
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_HITS = 5_968
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_HITS = 6_270
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_HITS = 9_096
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_HITS = 9_224
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_HITS = 9_800
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_HITS = 9_906
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_PCT = "38.1683"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_PCT = "40.0998"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_DELTA = "+1.9314"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_LO = "+1.5925"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_HI = "+2.2991"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_PCT = "58.1734"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_PCT = "58.9921"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_DELTA = "+0.8186"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_PCT = "62.6759"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_PCT = "63.3538"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_DELTA = "+0.6779"

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
