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
 *  - next-word top-3 hit rate: for each adjacent pair, the successor must be among the results
 *    [TatBigrPrefixIndex.predict] shows for the head;
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
                if (shown.contains(words[position + 1])) hits++
            }
        }
        val hitPct = hits * 100.0 / pairs
        val coveredPct = covered * 100.0 / pairs
        val hitCoveredPct = if (covered > 0) hits * 100.0 / covered else 0.0
        println("EVAL|nextword_pairs|$pairs")
        println("EVAL|nextword_head_covered|$covered")
        println("EVAL|nextword_top3_hits|$hits")
        println("EVAL|bigram_head_coverage_pct|${format(coveredPct)}")
        println("EVAL|nextword_top3_hit_pct|${format(hitPct)}")
        println("EVAL|nextword_top3_hit_covered_pct|${format(hitCoveredPct)}")

        assertEquals(PIN_PAIRS, pairs)
        assertEquals(PIN_COVERED, covered)
        assertEquals(PIN_TOP3_HITS, hits)
        // Cross-implementation pin: scripts/suggest_eval.py must print the same values.
        assertEquals("84.3137", format(coveredPct))
        assertEquals("10.8881", format(hitPct))
        assertEquals("12.9138", format(hitCoveredPct))
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
        private const val PIN_UNIQUE_WORDS = 2_658
        private const val PIN_PAIRS = 4_335
        private const val PIN_COVERED = 3_655
        private const val PIN_TOP3_HITS = 472
        private const val PIN_CP1_WORDS = 2_658
        private const val PIN_CP2_WORDS = 2_656
        private const val PIN_CP3_WORDS = 2_614
        // The same-stem boost only engages at >= 4 code points, so these 1-3 code-point prefix
        // counters are unaffected by it.
        private const val PIN_CP1_HITS = 64
        private const val PIN_CP2_HITS = 301
        private const val PIN_CP3_HITS = 736
        private const val PIN_SAMESTEM_WORDS = 1_708
        private const val PIN_SAMESTEM_HITS = 1_073
        private const val PIN_SAMESTEM_HITS_BOOST_OFF = 983
        private const val PIN_SENTSTART_TOP3_HITS = 124
        // Unique eval words whose committed-word strip is empty WITHOUT the top-frequency
        // fallback; with the fallback the count is asserted to be 0.
        private const val PIN_NEXTWORD_EMPTY_BEFORE = 681

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
