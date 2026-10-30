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
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer

/**
 * Runs the real shipped Tatar dictionary and bigram indexes over the held-out eval set
 * `app/src/test/resources/tt_eval_sentences.txt` (built by `scripts/make_eval_set.py` from
 * Tatoeba lines that never entered the training mix) and prints `EVAL|metric|value` lines:
 *  - prefix completion and next-word prediction report top-1, visible top-3 and engine top-4
 *    separately, for both the plain bigram path and the full production NEXT_WORD chain;
 *  - visible and engine chain rates have sentence-level bootstrap CI95 bounds (SplitMix64,
 *    shared with the python harness, which additionally prints the MDE bound);
 *  - strip-empty rates over typing moments: the share of prefix moments (one keystroke into a
 *    token, up to the completing one) whose completion answer is empty, and the share of
 *    next-word moments (adjacent pairs) whose chain answer is empty;
 *  - lemma strata (seen-form / new-form-of-seen-stem / unseen-stem): unique-word counts and
 *    per-stratum visible and engine completion/next-word rates;
 *  - keystroke savings: strip taps at cost 1, completion assist at the first prefix that shows
 *    the word with the previous committed word as context, plus the vocabulary-oracle bound.
 *    Two arms: the shipped three-cell default and the fourth-cell setting;
 *  - strip-empty and top-1/visible-top-3/engine-top-4 rates at sentence start, via the
 *    sentence-start table (`tatar_sentstart_v1.txt`, parsed by [SentStartIndex]).
 *
 * The engine-side metrics count the four composed candidates; the Tatar table stores K = 4
 * successors and `TatBigrPrefixIndex.MAX_RESULTS` reads them all. The keystroke arms differ only
 * in how many of the four a tap can reach. The results match `scripts/suggest_eval.py`.
 * Assets and eval set are SHA-pinned, so the exact counts below are a deterministic function of
 * committed bytes: re-pin when the assets, the eval set or the ranking change. Zero-allocation and
 * p95 contracts live in [RealDictionaryPrefixIndexTest] and [RealBigramPrefixIndexTest].
 */
class TtSuggestEvalTest {

    @Test
    fun prefixCompletionRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        // Fuzzy pass off: everything the lookup returns is an exact dictionary candidate. The
        // index carries the production Tatar wiring, including the same-stem boost table.
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
        assertEquals("0.9782", format(top1HitsPerLength[1] * 100.0 / wordsPerLength[1]))
        assertEquals("5.1581", format(top1HitsPerLength[2] * 100.0 / wordsPerLength[2]))
        assertEquals("15.1875", format(top1HitsPerLength[3] * 100.0 / wordsPerLength[3]))
    }

    /**
     * The same-stem boost metric: for every unique eval word that splits into a dictionary stem
     * plus a suffix from the runtime table, is the word in the top-3 when the user has typed
     * exactly the stem? The longest qualifying stem wins. The same measurement on an index without
     * the table is the control; both counters are pinned.
     */
    @Test
    fun sameStemBoostCompletionAtStemLengthPrefix() {
        val boosted = requireNotNull(dictionaryIndex)
        val control = requireNotNull(dictionaryIndexWithoutRules)
        boosted.updateKeyNeighbors(null)
        control.updateKeyNeighbors(null)

        var words = 0
        var top1Boosted = 0
        var visibleBoosted = 0
        var engineBoosted = 0
        var visibleControl = 0
        var engineControl = 0
        for (word in uniqueWords) {
            val stem = longestStemWithSuffixRemainder(word, boosted) ?: continue
            words++
            val query = ImmutableUtf8Prefix.copyOf(stem.toByteArray(Charsets.UTF_8))
            val boostedResults = boosted.lookup(query)
            val controlResults = control.lookup(query)
            if (boostedResults.firstOrNull() == word) top1Boosted++
            if (boostedResults.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                visibleBoosted++
            }
            if (boostedResults.take(SuggestionStripState.CELL_COUNT).contains(word)) {
                engineBoosted++
            }
            if (controlResults.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                visibleControl++
            }
            if (controlResults.take(SuggestionStripState.CELL_COUNT).contains(word)) {
                engineControl++
            }
        }
        println("EVAL|samestem_words|$words")
        println("EVAL|samestem_top1_hits|$top1Boosted")
        println("EVAL|samestem_top1_pct|${format(ratePct(top1Boosted, words))}")
        println("EVAL|samestem_visible_top3_hits|$visibleBoosted")
        println("EVAL|samestem_visible_top3_pct|${format(ratePct(visibleBoosted, words))}")
        println("EVAL|samestem_engine_top4_hits|$engineBoosted")
        println("EVAL|samestem_engine_top4_pct|${format(ratePct(engineBoosted, words))}")
        println("EVAL|samestem_visible_top3_hits_boost_off|$visibleControl")
        println("EVAL|samestem_engine_top4_hits_boost_off|$engineControl")

        assertEquals(PIN_SAMESTEM_WORDS, words)
        assertEquals(PIN_SAMESTEM_TOP1_HITS, top1Boosted)
        assertEquals(PIN_SAMESTEM_VISIBLE_TOP3_HITS, visibleBoosted)
        assertEquals(PIN_SAMESTEM_ENGINE_TOP4_HITS, engineBoosted)
        assertEquals(PIN_SAMESTEM_VISIBLE_TOP3_HITS_BOOST_OFF, visibleControl)
        assertEquals(PIN_SAMESTEM_ENGINE_TOP4_HITS_BOOST_OFF, engineControl)
    }

    /**
     * The context boost on the prefix path: exact-pass candidates among the previous committed
     * word's packed bigram successors lead the strip. Measured over the token-occurrence typing
     * moments at prefix length >= 2 code points; the control is the same moments unarmed. The
     * top-1 delta and its sentence-level paired-bootstrap CI95 (the SplitMix64 stream, seed and
     * rounds shared with the python harness) are cross-pinned with scripts/suggest_eval.py (the
     * ctxboost_* rows); the top-3 delta is the non-regression reading.
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
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
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

    /**
     * The longest proper split of [word] into stem + remainder where the remainder is a suffix
     * form of the runtime table and the stem is a dictionary word (nonzero frequency), or null.
     * The eval set is normalized Tatar — BMP only — so char splits are code-point splits here.
     */
    private fun longestStemWithSuffixRemainder(word: String, index: TdictPrefixIndex): String? {
        for (cut in word.length - 1 downTo 1) {
            val remainder = word.substring(cut)
            if (!TatarSuffixRules.isInflectedContinuation(remainder)) continue
            val stem = word.substring(0, cut)
            if (index.frequencyOf(stem) > 0L) return stem
        }
        return null
    }

    @Test
    fun nextWordTop3HitRateOnTheEvalSet() {
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
                if (shown[0] == successor) top1Hits++
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
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("8.2122", format(ratePct(top1Hits, pairs)))
        assertEquals("85.0750", format(ratePct(covered, pairs)))
        assertEquals("14.0715", format(ratePct(visibleHits, pairs)))
        assertEquals("16.0092", format(ratePct(engineHits, pairs)))
        assertEquals("18.8178", format(ratePct(engineHits, covered)))
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
     * The strip-empty-after-word rate: for each unique eval word as the committed context,
     * whether the NEXT_WORD answer is empty without and with the top-frequency fallback. With
     * the fallback it is 0: a committed word's strip is never empty while suggestions are on.
     */
    @Test
    fun stripEmptyAfterWordRateOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        val bigrams = requireNotNull(bigramIndex)
        val beforeComputer = CompositePrefixComputer(index, PersonalCandidateSource.EMPTY)
            .also { it.attachBigramSource(bigrams) }
        val afterComputer = CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY,
            GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
        ).also { it.attachBigramSource(bigrams) }
        var emptyBefore = 0
        var emptyAfter = 0
        for (word in uniqueWords) {
            val query = ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8))
            if (beforeComputer.predict(query).isEmpty()) emptyBefore++
            if (afterComputer.predict(query).isEmpty()) emptyAfter++
        }
        println("EVAL|nextword_empty_words_before|$emptyBefore")
        println("EVAL|nextword_empty_words_after|$emptyAfter")
        println("EVAL|nextword_empty_before_pct|${format(emptyBefore * 100.0 / uniqueWords.size)}")
        println("EVAL|nextword_empty_after_pct|${format(emptyAfter * 100.0 / uniqueWords.size)}")
        assertEquals(PIN_NEXTWORD_EMPTY_BEFORE, emptyBefore)
        assertEquals(0, emptyAfter)
    }

    /**
     * The strip-empty share over typing moments, per answer kind. A prefix moment is one
     * keystroke into a token, up to the completing one; the answer is the exact completion pass
     * of the typed prefix. A next-word moment is one committed-word context (an adjacent pair,
     * the [PIN_PAIRS] denominator); the answer is the full NEXT_WORD chain. The chain ends in
     * the top-frequency fallback, so the next-word side asserts the fallback invariant (never
     * empty) rather than a measured rate. Token-occurrence weighted, not per unique word.
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
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("2.0370", format(prefixEmpty * 100.0 / prefixMoments))
        assertEquals("0.0000", format(nextwordEmpty * 100.0 / nextwordMoments))
    }

    /**
     * The full production NEXT_WORD chain (bigram successors, top-frequency fallback) against
     * the plain bigram path: top-1 and top-3 hit rates over all eval pairs.
     * Cross-pinned with `scripts/suggest_eval.py`, which runs the mirror of the same chain.
     */
    @Test
    fun nextWordChainHitRatesOnTheEvalSet() {
        val stats = chainSentenceStats()
        var pairs = 0
        var top1Hits = 0
        var visibleHits = 0
        var engineHits = 0
        for (index in stats.top1Hits.indices) {
            pairs += stats.pairs[index]
            top1Hits += stats.top1Hits[index]
            visibleHits += stats.visibleTop3Hits[index]
            engineHits += stats.engineTop4Hits[index]
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
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("8.4198", format(ratePct(top1Hits, pairs)))
        assertEquals("14.6482", format(ratePct(visibleHits, pairs)))
        assertEquals("16.7705", format(ratePct(engineHits, pairs)))
    }

    /** Sentence-level bootstrap CI95 for the visible top-3 and engine top-4 chain rates. */
    @Test
    fun nextWordChainBootstrapCi95OnTheEvalSet() {
        val stats = chainSentenceStats()

        fun ci95(hitsPerSentence: IntArray): Pair<Double, Double> {
            val rates = DoubleArray(BOOTSTRAP_ROUNDS)
            var state = splitmix64(BOOTSTRAP_SEED)
            for (round in 0 until BOOTSTRAP_ROUNDS) {
                var hits = 0
                var pairs = 0
                repeat(evalLines.size) {
                    val drawn = java.lang.Long.remainderUnsigned(
                        state, evalLines.size.toLong(),
                    ).toInt()
                    state = splitmix64(state)
                    hits += hitsPerSentence[drawn]
                    pairs += stats.pairs[drawn]
                }
                rates[round] = hits * 100.0 / pairs
            }
            rates.sort()
            return rates[nearestRankIndex(25, BOOTSTRAP_ROUNDS)] to
                rates[nearestRankIndex(975, BOOTSTRAP_ROUNDS)]
        }

        val visible = ci95(stats.visibleTop3Hits)
        val engine = ci95(stats.engineTop4Hits)
        println("EVAL|nextword_chain_visible_top3_ci95_lo|${format(visible.first)}")
        println("EVAL|nextword_chain_visible_top3_ci95_hi|${format(visible.second)}")
        println("EVAL|nextword_chain_engine_top4_ci95_lo|${format(engine.first)}")
        println("EVAL|nextword_chain_engine_top4_ci95_hi|${format(engine.second)}")
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("13.6086", format(visible.first))
        assertEquals("15.6977", format(visible.second))
        assertEquals("15.6571", format(engine.first))
        assertEquals("17.8758", format(engine.second))
    }

    /**
     * Lemma strata of the unique eval words: seen-form (a dictionary word), new-form-of-seen-stem
     * (splits into a dictionary stem plus a runtime-table suffix), unseen-stem (the rest). Per
     * stratum: the cp3 prefix completion top-3 rate and the chain top-3 next-word rate of the
     * pairs whose head is in the stratum. Words outside the dictionary never complete, so their
     * cp3 rates are zero by construction.
     */
    @Test
    fun lemmaStratificationOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)
        val chain = chainComputer()
        val predictCache = HashMap<String, List<String>>()
        val strata = intArrayOf(STRATUM_SEEN_FORM, STRATUM_NEW_FORM, STRATUM_UNSEEN_STEM)
        val names = arrayOf("seen_form", "new_form_of_seen_stem", "unseen_stem")
        val visiblePctByStratum = arrayOfNulls<String>(strata.size)
        val enginePctByStratum = arrayOfNulls<String>(strata.size)
        val stratumByWord = HashMap<String, Int>(uniqueWords.size)
        for (word in uniqueWords) {
            stratumByWord[word] = when {
                index.frequencyOf(word) > 0L -> STRATUM_SEEN_FORM
                longestStemWithSuffixRemainder(word, index) != null -> STRATUM_NEW_FORM
                else -> STRATUM_UNSEEN_STEM
            }
        }
        for (stratum in strata) {
            val words = uniqueWords.filter { stratumByWord[it] == stratum }
            var cp3Words = 0
            var cp3VisibleHits = 0
            var cp3EngineHits = 0
            for (word in words) {
                val prefix = codePointPrefix(word, STRATUM_PREFIX_CODE_POINTS) ?: continue
                cp3Words++
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
                )
                if (results.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(word)) {
                    cp3VisibleHits++
                }
                if (results.take(SuggestionStripState.CELL_COUNT).contains(word)) cp3EngineHits++
            }
            var pairs = 0
            var visibleHits = 0
            var engineHits = 0
            for (line in evalLines) {
                val lineWords = line.split(" ")
                for (position in 0 until lineWords.size - 1) {
                    if (stratumByWord[lineWords[position]] != stratum) continue
                    pairs++
                    val shown = predictCache.getOrPut(lineWords[position]) {
                        chain.predict(
                            ImmutableUtf8Prefix.copyOf(
                                lineWords[position].toByteArray(Charsets.UTF_8)
                            )
                        )
                    }
                    val successor = lineWords[position + 1]
                    if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT)
                        .contains(successor)
                    ) {
                        visibleHits++
                    }
                    if (shown.take(SuggestionStripState.CELL_COUNT).contains(successor)) {
                        engineHits++
                    }
                }
            }
            val name = names[stratum]
            visiblePctByStratum[stratum] = format(ratePct(visibleHits, pairs))
            enginePctByStratum[stratum] = format(ratePct(engineHits, pairs))
            println("EVAL|stratum_${name}_words|${words.size}")
            println("EVAL|stratum_${name}_cp3_words|$cp3Words")
            println("EVAL|stratum_${name}_cp3_visible_top3_hits|$cp3VisibleHits")
            println("EVAL|stratum_${name}_cp3_visible_top3_pct|${format(ratePct(cp3VisibleHits, cp3Words))}")
            println("EVAL|stratum_${name}_cp3_engine_top4_hits|$cp3EngineHits")
            println("EVAL|stratum_${name}_cp3_engine_top4_pct|${format(ratePct(cp3EngineHits, cp3Words))}")
            println("EVAL|stratum_${name}_pairs|$pairs")
            println("EVAL|stratum_${name}_visible_top3_hits|$visibleHits")
            println("EVAL|stratum_${name}_visible_top3_pct|${visiblePctByStratum[stratum]}")
            println("EVAL|stratum_${name}_engine_top4_hits|$engineHits")
            println("EVAL|stratum_${name}_engine_top4_pct|${enginePctByStratum[stratum]}")
        }
        assertEquals(PIN_STRATUM_SEEN_FORM_WORDS, stratumCount(stratumByWord, STRATUM_SEEN_FORM))
        assertEquals(PIN_STRATUM_NEW_FORM_WORDS, stratumCount(stratumByWord, STRATUM_NEW_FORM))
        assertEquals(
            PIN_STRATUM_UNSEEN_STEM_WORDS, stratumCount(stratumByWord, STRATUM_UNSEEN_STEM)
        )
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("15.1347", visiblePctByStratum[STRATUM_SEEN_FORM])
        assertEquals("2.5316", visiblePctByStratum[STRATUM_NEW_FORM])
        assertEquals("4.0000", visiblePctByStratum[STRATUM_UNSEEN_STEM])
        assertEquals("17.3484", enginePctByStratum[STRATUM_SEEN_FORM])
        assertEquals("2.5316", enginePctByStratum[STRATUM_NEW_FORM])
        assertEquals("4.0000", enginePctByStratum[STRATUM_UNSEEN_STEM])
    }

    /**
     * Keystroke savings: replay the eval sentences with strip taps at cost 1. The baseline types
     * every code point plus one space between words. The simulation types the first word with
     * completion assist; a later word is a tap when the chain shows it for the previous word,
     * else completion assist. Completion assist types code points until the word enters the
     * prefix answer's shown cells (cost k+1 with the tap) or, when it never does, the full
     * length. The oracle types the first word's minimal distinguishing prefix over the eval
     * vocabulary (never more than the word) and taps every later word.
     *
     * Two arms over the same engine answers: the shipped default strip (three cells) and the
     * fourth-cell setting (all four). The engine composes four candidates either way; the arm
     * decides how many are tappable.
     */
    @Test
    fun keystrokeSavingsOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)
        val chain = chainComputer()
        val completionCosts = HashMap<Pair<String, String?>, Int>(uniqueWords.size)
        val completionCostsCells4 = HashMap<Pair<String, String?>, Int>(uniqueWords.size)
        val predictCache = HashMap<String, List<String>>()

        fun completionCostOf(
            word: String,
            context: String?,
            cells: Int,
            cache: HashMap<Pair<String, String?>, Int>,
        ): Int {
            val key = word to context
            cache[key]?.let { return it }
            val contextPrefix = context?.let {
                ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8))
            }
            var cost = word.length
            for (codePoints in 1 until word.length) {
                val query = ImmutableUtf8Prefix.copyOf(
                    word.substring(0, codePoints).toByteArray(Charsets.UTF_8)
                )
                val results = if (contextPrefix == null) {
                    chain.lookup(query)
                } else {
                    chain.lookup(query, contextPrefix)
                }
                if (results.take(cells).contains(word)) {
                    cost = codePoints + 1
                    break
                }
            }
            cache[key] = cost
            return cost
        }

        val distinguishing = minimalDistinguishingPrefixLengths(uniqueWords)
        var baseline = 0
        var simulated = 0
        var simulatedCells4 = 0
        var oracle = 0
        for (line in evalLines) {
            val words = line.split(" ")
            baseline += words.sumOf { it.length } + words.size - 1
            simulated += completionCostOf(
                words[0], null, SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT, completionCosts)
            simulatedCells4 += completionCostOf(
                words[0], null, SuggestionStripState.CELL_COUNT, completionCostsCells4)
            for (position in 1 until words.size) {
                val shown = predictCache.getOrPut(words[position - 1]) {
                    chain.predict(
                        ImmutableUtf8Prefix.copyOf(
                            words[position - 1].toByteArray(Charsets.UTF_8)
                        )
                    )
                }
                val target = words[position]
                simulated +=
                    if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT).contains(target)) {
                        1
                    } else {
                        completionCostOf(
                            target, words[position - 1],
                            SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT, completionCosts,
                        )
                    }
                simulatedCells4 +=
                    if (shown.take(SuggestionStripState.CELL_COUNT).contains(target)) {
                        1
                    } else {
                        completionCostOf(
                            target, words[position - 1],
                            SuggestionStripState.CELL_COUNT, completionCostsCells4,
                        )
                    }
            }
            val distinguishingLength = distinguishing[words[0]]
            oracle += if (distinguishingLength != null) {
                minOf(words[0].length, distinguishingLength + 1)
            } else {
                words[0].length
            }
            oracle += words.size - 1
        }
        val savedPct = (baseline - simulated) * 100.0 / baseline
        val savedPctCells4 = (baseline - simulatedCells4) * 100.0 / baseline
        val oraclePct = (baseline - oracle) * 100.0 / baseline
        println("EVAL|ks_baseline_keys|$baseline")
        println("EVAL|ks_simulated_keys|$simulated")
        println("EVAL|ks_pct|${format(savedPct)}")
        println("EVAL|ks_cells4_simulated_keys|$simulatedCells4")
        println("EVAL|ks_cells4_pct|${format(savedPctCells4)}")
        println("EVAL|ks_oracle_keys|$oracle")
        println("EVAL|ks_oracle_pct|${format(oraclePct)}")
        assertEquals(PIN_KS_BASELINE_KEYS, baseline)
        assertEquals(PIN_KS_SIMULATED_KEYS, simulated)
        assertEquals(PIN_KS_SIMULATED_KEYS_CELLS4, simulatedCells4)
        assertEquals(PIN_KS_ORACLE_KEYS, oracle)
        // Cross-implementation pins: scripts/suggest_eval.py must print the same values.
        assertEquals("35.0204", format(savedPct))
        assertEquals("37.2495", format(savedPctCells4))
        assertEquals("75.0660", format(oraclePct))
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

    /**
     * The production NEXT_WORD wiring: bundled bigrams, empty personal sources and the
     * top-frequency fallback. The same wiring as the with-fallback computer of
     * [stripEmptyAfterWordRateOnTheEvalSet].
     */
    private fun chainComputer(): CompositePrefixComputer {
        val index = requireNotNull(dictionaryIndex)
        return CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY,
            GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
        ).also { it.attachBigramSource(requireNotNull(bigramIndex)) }
    }

    /** Per-sentence chain hit counts; the bootstrap resamples whole sentences. */
    private class ChainSentenceStats(
        val top1Hits: IntArray,
        val visibleTop3Hits: IntArray,
        val engineTop4Hits: IntArray,
        val pairs: IntArray,
    )

    private fun chainSentenceStats(): ChainSentenceStats {
        val chain = chainComputer()
        val predictCache = HashMap<String, List<String>>()
        val top1Hits = IntArray(evalLines.size)
        val visibleTop3Hits = IntArray(evalLines.size)
        val engineTop4Hits = IntArray(evalLines.size)
        val pairs = IntArray(evalLines.size)
        for (lineIndex in evalLines.indices) {
            val words = evalLines[lineIndex].split(" ")
            for (position in 0 until words.size - 1) {
                pairs[lineIndex]++
                val shown = predictCache.getOrPut(words[position]) {
                    chain.predict(
                        ImmutableUtf8Prefix.copyOf(words[position].toByteArray(Charsets.UTF_8))
                    )
                }
                if (shown.isEmpty()) continue
                val successor = words[position + 1]
                if (shown[0] == successor) top1Hits[lineIndex]++
                if (shown.take(SuggestionStripState.DEFAULT_VISIBLE_CELL_COUNT)
                    .contains(successor)
                ) {
                    visibleTop3Hits[lineIndex]++
                }
                if (shown.take(SuggestionStripState.CELL_COUNT).contains(successor)) {
                    engineTop4Hits[lineIndex]++
                }
            }
        }
        return ChainSentenceStats(top1Hits, visibleTop3Hits, engineTop4Hits, pairs)
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

    private fun stratumCount(stratumByWord: Map<String, Int>, stratum: Int): Int =
        stratumByWord.values.count { it == stratum }

    /**
     * For each word, the length of the shortest prefix no other word of the set shares; a word
     * that is a proper prefix of another word has none and is left out. In a sorted set only the
     * immediate neighbors can share the longest prefix. The eval set is BMP-only, so char length
     * is the code-point length.
     */
    private fun minimalDistinguishingPrefixLengths(words: List<String>): Map<String, Int> {
        val ordered = words.sorted()
        val lengths = HashMap<String, Int>(ordered.size)
        for (index in ordered.indices) {
            val word = ordered[index]
            var need = 1
            if (index > 0) need = maxOf(need, commonPrefixLength(ordered[index - 1], word) + 1)
            if (index + 1 < ordered.size) {
                need = maxOf(need, commonPrefixLength(word, ordered[index + 1]) + 1)
            }
            if (need <= word.length) lengths[word] = need
        }
        return lengths
    }

    private fun commonPrefixLength(first: String, second: String): Int {
        val limit = minOf(first.length, second.length)
        var at = 0
        while (at < limit && first[at] == second[at]) at++
        return at
    }

    private fun format(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    private fun formatSigned(value: Double): String = "%+.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // Pins over the committed assets and eval set; re-pin when either changes (see class KDoc).
        private const val PIN_EVAL_LINES = 1_000
        private const val PIN_UNIQUE_WORDS = 2_658
        private const val PIN_PAIRS = 4_335
        private const val PIN_COVERED = 3_688
        private const val PIN_BIGRAM_VISIBLE_TOP3_HITS = 610
        private const val PIN_BIGRAM_ENGINE_TOP4_HITS = 694
        private const val PIN_CP1_WORDS = 2_658
        private const val PIN_CP2_WORDS = 2_656
        private const val PIN_CP3_WORDS = 2_614
        // The same-stem boost only engages at >= 4 code points, so these 1-3 code-point prefix
        // counters are unaffected by it.
        private const val PIN_CP1_VISIBLE_TOP3_HITS = 70
        private const val PIN_CP2_VISIBLE_TOP3_HITS = 320
        private const val PIN_CP3_VISIBLE_TOP3_HITS = 765
        private const val PIN_CP1_ENGINE_TOP4_HITS = 87
        private const val PIN_CP2_ENGINE_TOP4_HITS = 381
        private const val PIN_CP3_ENGINE_TOP4_HITS = 887
        private const val PIN_CP1_TOP1_HITS = 26
        private const val PIN_CP2_TOP1_HITS = 137
        private const val PIN_CP3_TOP1_HITS = 397
        private const val PIN_STRIP_EMPTY_PREFIX_MOMENTS = 23_662
        private const val PIN_STRIP_EMPTY_PREFIX_EMPTY = 482
        private const val PIN_SAMESTEM_WORDS = 1_753
        private const val PIN_SAMESTEM_TOP1_HITS = 734
        private const val PIN_SAMESTEM_VISIBLE_TOP3_HITS = 1_181
        private const val PIN_SAMESTEM_ENGINE_TOP4_HITS = 1_281
        private const val PIN_SAMESTEM_VISIBLE_TOP3_HITS_BOOST_OFF = 1_096
        private const val PIN_SAMESTEM_ENGINE_TOP4_HITS_BOOST_OFF = 1_185
        private const val PIN_SENTSTART_TOP1_HITS = 49
        private const val PIN_SENTSTART_VISIBLE_TOP3_HITS = 239
        private const val PIN_SENTSTART_ENGINE_TOP4_HITS = 275
        // Unique eval words whose committed-word strip is empty WITHOUT the top-frequency
        // fallback; with the fallback the count is asserted to be 0.
        private const val PIN_NEXTWORD_EMPTY_BEFORE = 818
        private const val PIN_TOP1_HITS = 356
        private const val PIN_CHAIN_TOP1_HITS = 365
        private const val PIN_CHAIN_VISIBLE_TOP3_HITS = 635
        private const val PIN_CHAIN_ENGINE_TOP4_HITS = 727
        private const val PIN_STRATUM_SEEN_FORM_WORDS = 2_425
        private const val PIN_STRATUM_NEW_FORM_WORDS = 84
        private const val PIN_STRATUM_UNSEEN_STEM_WORDS = 149
        private const val PIN_KS_BASELINE_KEYS = 33_332
        private const val PIN_KS_SIMULATED_KEYS = 21_659
        private const val PIN_KS_SIMULATED_KEYS_CELLS4 = 20_916
        private const val PIN_KS_ORACLE_KEYS = 8_311
        // The context boost over the cp>=2 prefix moments, cross-pinned with the ctxboost_* rows
        // of scripts/suggest_eval.py; the CI95 is the paired sentence-level bootstrap.
        private const val PIN_CTXBOOST_CP2PLUS_MOMENTS = 18_338
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_HITS = 6_292
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_HITS = 6_664
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_HITS = 9_319
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_HITS = 9_609
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_HITS = 10_022
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_HITS = 10_270
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BASE_PCT = "34.3113"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_BOOST_PCT = "36.3398"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_DELTA = "+2.0286"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_LO = "+1.6772"
        private const val PIN_CTXBOOST_CP2PLUS_TOP1_CI95_HI = "+2.4015"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BASE_PCT = "50.8180"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_BOOST_PCT = "52.3994"
        private const val PIN_CTXBOOST_CP2PLUS_VISIBLE_TOP3_DELTA = "+1.5814"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BASE_PCT = "54.6515"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_BOOST_PCT = "56.0039"
        private const val PIN_CTXBOOST_CP2PLUS_ENGINE_TOP4_DELTA = "+1.3524"

        // Lemma-stratum ids, and the stratum completion metric's prefix length ("cp3"): fixed
        // at three code points by the metric's definition, independent of the strip width.
        private const val STRATUM_SEEN_FORM = 0
        private const val STRATUM_NEW_FORM = 1
        private const val STRATUM_UNSEEN_STEM = 2
        private const val STRATUM_PREFIX_CODE_POINTS = 3

        // Bootstrap knobs, shared with scripts/suggest_chain.py; changing them re-pins the CI.
        private const val BOOTSTRAP_SEED = 20261001L
        private const val BOOTSTRAP_ROUNDS = 2_000

        private lateinit var evalLines: List<String>
        private lateinit var uniqueWords: List<String>
        private var dictionaryIndex: TdictPrefixIndex? = null
        private var dictionaryIndexWithoutRules: TdictPrefixIndex? = null
        private var bigramIndex: TatBigrPrefixIndex? = null
        private var sentStartIndex: SentStartIndex? = null

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndEvalSet() {
            evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            uniqueWords = evalLines.flatMap { it.split(" ") }.distinct()

            // The committed sentence-start table, parsed through the production reader.
            val sentStart = SentStartIndex.parse(
                locate(
                    "src/main/assets/dictionaries/tatar_sentstart_v1.txt",
                    "app/src/main/assets/dictionaries/tatar_sentstart_v1.txt",
                ).readText(Charsets.UTF_8),
            )
            check(!sentStart.isEmpty) { "the committed sentence-start table failed to parse" }
            sentStartIndex = sentStart

            // Schema 3 resolves words through the linked dictionary: open the committed
            // Tatar dictionary first, exactly like RealBigramPrefixIndexTest does.
            val dictSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val dictAsset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val dictRawFile = File.createTempFile("tt-suggest-eval-dict-", ".tdict")
            val dictionary: TdictPrefixIndex
            val controlDictionary: TdictPrefixIndex
            try {
                dictRawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(dictAsset.inputStream(), output, dictSpec)
                }
                val validated = TdictValidator().validateRaw(dictRawFile, dictSpec)
                val identity = DictionaryIdentity(
                    dictSpec.generation, validated.schemaId, validated.formatVersion,
                    validated.rawSha256,
                )
                val raw = dictRawFile.readBytes()
                // The production Tatar engine is constructed with the suffix rules and the TATAR
                // fuzzy policy (class #1, the gated class #4, the same-length bonus): the eval
                // index carries both, and a second rules-free instance is the boost control. The
                // policy does not affect these metrics (class #4 fires only on an empty exact
                // pass at >= 4 code points, and fuzzy candidates only fill cells the exact pass
                // left free).
                dictionary = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize, TatarSuffixRules,
                        FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                )
                controlDictionary = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize,
                        autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                )
            } finally {
                dictRawFile.delete()
            }
            dictionaryIndex = dictionary
            dictionaryIndexWithoutRules = controlDictionary

            val asset = locate(
                "src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
                "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
            )
            val spec = BigramArtifactSpec.TATAR_BIGRAMS_V1
            val rawFile = File.createTempFile("tt-suggest-eval-bigrams-", ".tatbigr")
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
