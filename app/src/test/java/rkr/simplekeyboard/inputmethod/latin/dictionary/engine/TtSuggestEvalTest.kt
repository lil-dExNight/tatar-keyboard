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
 *  - prefix top-3 completion: for each unique word, prefixes of 1/2/3 code points are looked up
 *    in [TdictPrefixIndex] (fuzzy pass off) and the word must be in the top-3;
 *  - next-word top-1 and top-3 hit rates of the plain bigram path ([TatBigrPrefixIndex.predict])
 *    and of the full production NEXT_WORD chain (bigram successors, after-word forms,
 *    top-frequency fallback; learned word pairs empty), the chain top-3 with a sentence-level
 *    bootstrap CI95 (SplitMix64 resample stream, the seed shared with the python harness, which
 *    additionally prints the minimum-detectable-effect bound);
 *  - lemma strata (seen-form / new-form-of-seen-stem / unseen-stem): unique-word counts,
 *    per-stratum cp3 completion top-3 rates and per-stratum chain top-3 next-word rates;
 *  - keystroke savings: strip taps at cost 1, completion assist at the first prefix that shows
 *    the word, plus the vocabulary-oracle bound over minimal distinguishing prefixes;
 *  - strip-empty at sentence start and sentence-start top-3 hit rate, via the sentence-start
 *    table (`tatar_sentstart_v1.txt`, parsed by [SentStartIndex]).
 *
 * Metrics count the three strip cells; the Tatar table stores K = 4 successors but
 * `TatBigrPrefixIndex.MAX_RESULTS` = 3, so the results match `scripts/suggest_eval.py`.
 * Assets and eval set are SHA-pinned, so the exact counts below are a deterministic function of
 * committed bytes: re-pin when the assets, the eval set or the ranking change. Zero-allocation and
 * p95 contracts live in [RealDictionaryPrefixIndexTest] and [RealBigramPrefixIndexTest].
 */
class TtSuggestEvalTest {

    @Test
    fun prefixTop3CompletionRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        // Fuzzy pass off: everything the lookup returns is an exact dictionary candidate. The
        // index carries the production Tatar wiring, including the same-stem boost table.
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
        var hitsBoosted = 0
        var hitsControl = 0
        for (word in uniqueWords) {
            val stem = longestStemWithSuffixRemainder(word, boosted) ?: continue
            words++
            val query = ImmutableUtf8Prefix.copyOf(stem.toByteArray(Charsets.UTF_8))
            if (boosted.lookup(query).contains(word)) hitsBoosted++
            if (control.lookup(query).contains(word)) hitsControl++
        }
        println("EVAL|samestem_words|$words")
        println("EVAL|samestem_hits|$hitsBoosted")
        println("EVAL|samestem_hits_boost_off|$hitsControl")
        println("EVAL|samestem_top3_pct|${format(hitsBoosted * 100.0 / words)}")
        println("EVAL|samestem_top3_boost_off_pct|${format(hitsControl * 100.0 / words)}")

        assertEquals(PIN_SAMESTEM_WORDS, words)
        assertEquals(PIN_SAMESTEM_HITS, hitsBoosted)
        assertEquals(PIN_SAMESTEM_HITS_BOOST_OFF, hitsControl)
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
        var hits = 0
        for (line in evalLines) {
            val words = line.split(" ")
            for (position in 0 until words.size - 1) {
                pairs++
                val shown = bigrams.predict(
                    ImmutableUtf8Prefix.copyOf(words[position].toByteArray(Charsets.UTF_8))
                )
                if (shown.isEmpty()) continue
                covered++
                if (shown[0] == words[position + 1]) top1Hits++
                if (shown.contains(words[position + 1])) hits++
            }
        }
        val top1Pct = top1Hits * 100.0 / pairs
        val hitPct = hits * 100.0 / pairs
        val coveredPct = covered * 100.0 / pairs
        val hitCoveredPct = if (covered > 0) hits * 100.0 / covered else 0.0
        println("EVAL|nextword_pairs|$pairs")
        println("EVAL|nextword_head_covered|$covered")
        println("EVAL|nextword_top1_hits|$top1Hits")
        println("EVAL|nextword_top1_hit_pct|${format(top1Pct)}")
        println("EVAL|nextword_top3_hits|$hits")
        println("EVAL|bigram_head_coverage_pct|${format(coveredPct)}")
        println("EVAL|nextword_top3_hit_pct|${format(hitPct)}")
        println("EVAL|nextword_top3_hit_covered_pct|${format(hitCoveredPct)}")

        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_COVERED, covered)
        assertEquals(PIN_TOP1_HITS, top1Hits)
        assertEquals(PIN_TOP3_HITS, hits)
        // Cross-implementation pin: scripts/suggest_eval.py must print the same values.
        assertEquals("7.0358", format(top1Pct))
        assertEquals("85.0750", format(coveredPct))
        assertEquals("12.2722", format(hitPct))
        assertEquals("14.4252", format(hitCoveredPct))
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
     * The strip-empty-after-word rate: for each unique eval word as the committed context,
     * whether the NEXT_WORD answer (bigram successors + after-word forms) is empty, measured
     * without and with the top-frequency fallback. With the fallback it is 0: a committed word's
     * strip is never empty while suggestions are on.
     */
    @Test
    fun stripEmptyAfterWordRateOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        val bigrams = requireNotNull(bigramIndex)
        val forms = TatarSuffixRules.createAfterWordForms(index)
        val beforeComputer = CompositePrefixComputer(index, PersonalCandidateSource.EMPTY, forms)
            .also { it.attachBigramSource(bigrams) }
        val afterComputer = CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY, forms,
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
     * The full production NEXT_WORD chain (bigram successors, after-word forms, top-frequency
     * fallback) against the plain bigram path: top-1 and top-3 hit rates over all eval pairs.
     * Cross-pinned with `scripts/suggest_eval.py`, which runs the mirror of the same chain.
     */
    @Test
    fun nextWordChainHitRatesOnTheEvalSet() {
        val stats = chainSentenceStats()
        var pairs = 0
        var top1Hits = 0
        var top3Hits = 0
        for (index in stats.top1Hits.indices) {
            pairs += stats.pairs[index]
            top1Hits += stats.top1Hits[index]
            top3Hits += stats.top3Hits[index]
        }
        val top1Pct = top1Hits * 100.0 / pairs
        val top3Pct = top3Hits * 100.0 / pairs
        println("EVAL|nextword_chain_pairs|$pairs")
        println("EVAL|nextword_chain_top1_hits|$top1Hits")
        println("EVAL|nextword_chain_top1_pct|${format(top1Pct)}")
        println("EVAL|nextword_chain_top3_hits|$top3Hits")
        println("EVAL|nextword_chain_top3_pct|${format(top3Pct)}")
        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_CHAIN_TOP1_HITS, top1Hits)
        assertEquals(PIN_CHAIN_TOP3_HITS, top3Hits)
        // Cross-implementation pin: scripts/suggest_eval.py must print the same values.
        assertEquals("7.1972", format(top1Pct))
        assertEquals("12.7336", format(top3Pct))
    }

    /**
     * CI95 of the chain top-3 rate: sentences resampled with replacement (a resampled sentence
     * brings all its pairs), the round indices drawn from one SplitMix64 stream (see
     * [splitmix64]) shared with the python harness. Nearest-rank percentiles of the sorted
     * per-round rates. A future two-arm comparison must consume the same stream for both arms.
     */
    @Test
    fun nextWordChainTop3BootstrapCi95OnTheEvalSet() {
        val stats = chainSentenceStats()
        val rates = DoubleArray(BOOTSTRAP_ROUNDS)
        var state = splitmix64(BOOTSTRAP_SEED)
        for (round in 0 until BOOTSTRAP_ROUNDS) {
            var hits = 0
            var pairs = 0
            repeat(evalLines.size) {
                val drawn = java.lang.Long.remainderUnsigned(state, evalLines.size.toLong()).toInt()
                state = splitmix64(state)
                hits += stats.top3Hits[drawn]
                pairs += stats.pairs[drawn]
            }
            rates[round] = hits * 100.0 / pairs
        }
        rates.sort()
        val lo = rates[nearestRankIndex(25, BOOTSTRAP_ROUNDS)]
        val hi = rates[nearestRankIndex(975, BOOTSTRAP_ROUNDS)]
        println("EVAL|nextword_chain_top3_ci95_lo|${format(lo)}")
        println("EVAL|nextword_chain_top3_ci95_hi|${format(hi)}")
        // Cross-implementation pin: scripts/suggest_eval.py must print the same values.
        assertEquals("11.7195", format(lo))
        assertEquals("13.7219", format(hi))
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
            var cp3Hits = 0
            for (word in words) {
                val prefix = codePointPrefix(word, SHOWN_CELLS) ?: continue
                cp3Words++
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
                )
                if (results.contains(word)) cp3Hits++
            }
            var pairs = 0
            var hits = 0
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
                    if (shown.contains(lineWords[position + 1])) hits++
                }
            }
            val name = names[stratum]
            println("EVAL|stratum_${name}_words|${words.size}")
            println("EVAL|stratum_${name}_cp3_words|$cp3Words")
            println("EVAL|stratum_${name}_cp3_hits|$cp3Hits")
            println("EVAL|stratum_${name}_cp3_pct|${format(ratePct(cp3Hits, cp3Words))}")
            println("EVAL|stratum_${name}_pairs|$pairs")
            println("EVAL|stratum_${name}_top3_hits|$hits")
            println("EVAL|stratum_${name}_top3_pct|${format(ratePct(hits, pairs))}")
        }
        assertEquals(PIN_STRATUM_SEEN_FORM_WORDS, stratumCount(stratumByWord, STRATUM_SEEN_FORM))
        assertEquals(PIN_STRATUM_NEW_FORM_WORDS, stratumCount(stratumByWord, STRATUM_NEW_FORM))
        assertEquals(
            PIN_STRATUM_UNSEEN_STEM_WORDS, stratumCount(stratumByWord, STRATUM_UNSEEN_STEM)
        )
    }

    /**
     * Keystroke savings: replay the eval sentences with strip taps at cost 1. The baseline types
     * every code point plus one space between words. The simulation types the first word with
     * completion assist; a later word is a tap when the chain shows it for the previous word,
     * else completion assist. Completion assist types code points until the word enters the
     * prefix top-3 (cost k+1 with the tap) or, when it never does, the full length. The oracle
     * types the first word's minimal distinguishing prefix over the eval vocabulary (never more
     * than the word) and taps every later word.
     */
    @Test
    fun keystrokeSavingsOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        index.updateKeyNeighbors(null)
        val chain = chainComputer()
        val completionCosts = HashMap<String, Int>(uniqueWords.size)
        val predictCache = HashMap<String, List<String>>()

        fun completionCostOf(word: String): Int {
            completionCosts[word]?.let { return it }
            var cost = word.length
            for (codePoints in 1 until word.length) {
                val results = index.lookup(
                    ImmutableUtf8Prefix.copyOf(
                        word.substring(0, codePoints).toByteArray(Charsets.UTF_8)
                    )
                )
                if (results.contains(word)) {
                    cost = codePoints + 1
                    break
                }
            }
            completionCosts[word] = cost
            return cost
        }

        val distinguishing = minimalDistinguishingPrefixLengths(uniqueWords)
        var baseline = 0
        var simulated = 0
        var oracle = 0
        for (line in evalLines) {
            val words = line.split(" ")
            baseline += words.sumOf { it.length } + words.size - 1
            simulated += completionCostOf(words[0])
            for (position in 1 until words.size) {
                val shown = predictCache.getOrPut(words[position - 1]) {
                    chain.predict(
                        ImmutableUtf8Prefix.copyOf(
                            words[position - 1].toByteArray(Charsets.UTF_8)
                        )
                    )
                }
                simulated += if (shown.contains(words[position])) 1 else completionCostOf(words[position])
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
        val oraclePct = (baseline - oracle) * 100.0 / baseline
        println("EVAL|ks_baseline_keys|$baseline")
        println("EVAL|ks_simulated_keys|$simulated")
        println("EVAL|ks_pct|${format(savedPct)}")
        println("EVAL|ks_oracle_keys|$oracle")
        println("EVAL|ks_oracle_pct|${format(oraclePct)}")
        assertEquals(PIN_KS_BASELINE_KEYS, baseline)
        assertEquals(PIN_KS_SIMULATED_KEYS, simulated)
        assertEquals(PIN_KS_ORACLE_KEYS, oracle)
        // Cross-implementation pin: scripts/suggest_eval.py must print the same values.
        assertEquals("34.2584", format(savedPct))
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
     * The production NEXT_WORD wiring: bundled bigrams, empty personal sources, after-word
     * forms, the top-frequency fallback. The same wiring as the with-fallback computer of
     * [stripEmptyAfterWordRateOnTheEvalSet].
     */
    private fun chainComputer(): CompositePrefixComputer {
        val index = requireNotNull(dictionaryIndex)
        val forms = TatarSuffixRules.createAfterWordForms(index)
        return CompositePrefixComputer(
            index, PersonalCandidateSource.EMPTY, forms,
            GlobalTopFrequencyFallbackFactory.createFallbackWords(index),
        ).also { it.attachBigramSource(requireNotNull(bigramIndex)) }
    }

    /** Per-sentence chain hit counts; the bootstrap resamples whole sentences. */
    private class ChainSentenceStats(
        val top1Hits: IntArray,
        val top3Hits: IntArray,
        val pairs: IntArray,
    )

    private fun chainSentenceStats(): ChainSentenceStats {
        val chain = chainComputer()
        val predictCache = HashMap<String, List<String>>()
        val top1Hits = IntArray(evalLines.size)
        val top3Hits = IntArray(evalLines.size)
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
                if (shown[0] == words[position + 1]) top1Hits[lineIndex]++
                if (shown.contains(words[position + 1])) top3Hits[lineIndex]++
            }
        }
        return ChainSentenceStats(top1Hits, top3Hits, pairs)
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

    companion object {
        // Pins over the committed assets and eval set; re-pin when either changes (see class KDoc).
        private const val PIN_EVAL_LINES = 1_000
        private const val PIN_UNIQUE_WORDS = 2_658
        private const val PIN_PAIRS = 4_335
        private const val PIN_COVERED = 3_688
        private const val PIN_TOP3_HITS = 532
        private const val PIN_CP1_WORDS = 2_658
        private const val PIN_CP2_WORDS = 2_656
        private const val PIN_CP3_WORDS = 2_614
        // The same-stem boost only engages at >= 4 code points, so these 1-3 code-point prefix
        // counters are unaffected by it.
        private const val PIN_CP1_HITS = 70
        private const val PIN_CP2_HITS = 320
        private const val PIN_CP3_HITS = 765
        private const val PIN_SAMESTEM_WORDS = 1_750
        private const val PIN_SAMESTEM_HITS = 1_173
        private const val PIN_SAMESTEM_HITS_BOOST_OFF = 1_090
        private const val PIN_SENTSTART_TOP3_HITS = 124
        // Unique eval words whose committed-word strip is empty WITHOUT the top-frequency
        // fallback; with the fallback the count is asserted to be 0.
        private const val PIN_NEXTWORD_EMPTY_BEFORE = 642
        private const val PIN_TOP1_HITS = 305
        private const val PIN_CHAIN_TOP1_HITS = 312
        private const val PIN_CHAIN_TOP3_HITS = 552
        private const val PIN_STRATUM_SEEN_FORM_WORDS = 2_413
        private const val PIN_STRATUM_NEW_FORM_WORDS = 91
        private const val PIN_STRATUM_UNSEEN_STEM_WORDS = 154
        private const val PIN_KS_BASELINE_KEYS = 33_332
        private const val PIN_KS_SIMULATED_KEYS = 21_913
        private const val PIN_KS_ORACLE_KEYS = 8_311

        // Lemma-stratum ids and the strip cell count the cp3 stratum metric uses.
        private const val STRATUM_SEEN_FORM = 0
        private const val STRATUM_NEW_FORM = 1
        private const val STRATUM_UNSEEN_STEM = 2
        private const val SHOWN_CELLS = 3

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
                        FuzzyEditPolicy.TATAR,
                    ),
                )
                controlDictionary = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize,
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
