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
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer

/**
 * TT-SUGGESTIONS phase P0 (docs/TT-SUGGESTIONS-PLAN.md): drives the REAL shipped Tatar
 * dictionary and bigram indexes over the pinned held-out eval set
 * `app/src/test/resources/tt_eval_sentences.txt` (built by `scripts/make_eval_set.py`,
 * Tatoeba lines that never entered the `tt_conv_train90` training mix) and prints the
 * baseline suggestion metrics as `EVAL|metric|value` lines.
 *
 * Four metrics:
 *  - prefix top-3 completion: for each unique eval word, the prefixes of 1/2/3 code
 *    points (where the word is long enough) are looked up in [TdictPrefixIndex] and the
 *    word must appear among the top-3 exact results (key-neighbor fuzzy pass disabled,
 *    so everything returned is exact);
 *  - next-word top-3 hit rate: for each adjacent word pair, the successor must appear
 *    among the (at most three) results [TatBigrPrefixIndex.predict] shows for the head;
 *  - strip-empty at sentence start: with no typed prefix and no previous-word context
 *    the strip shows nothing -- the P0 baseline pinned 100 %, and phase P4 re-pinned it
 *    to 0 %: the sentence-start table (`tatar_sentstart_v1.txt`, parsed through the
 *    production [SentStartIndex] reader) now answers every sentence start;
 *  - sentence-start top-3 hit rate (P4): the fraction of eval sentences whose first word
 *    is among the top-3 sentence-start suggestions.
 *
 * Usage is strictly read-only; the zero-allocation and p95 lookup contracts keep their
 * own dedicated tests ([RealDictionaryPrefixIndexTest], [RealBigramPrefixIndexTest]).
 *
 * The pinned counts below are exact on purpose: both assets and the eval set are
 * SHA-pinned, so every number here is a deterministic function of committed bytes. Any
 * change -- asset rebuild, eval-set regeneration, ranking change -- trips an equality
 * and forces a conscious re-pin, which is exactly what the mission's before/after
 * discipline needs. The shared metrics are also pinned to the values the python harness
 * (`scripts/suggest_eval.py`) prints, cross-checking the two implementations.
 */
class TtSuggestEvalTest {

    @Test
    fun prefixTop3CompletionRatesOnTheEvalSet() {
        val index = requireNotNull(dictionaryIndex)
        // Fuzzy pass off: everything the lookup returns is an exact dictionary candidate. The
        // index carries the production Tatar wiring — the P3 same-stem boost table — so these
        // counters are the before/after evidence for the boost; the P2 baseline values are quoted
        // in docs/TT-SUGGESTIONS.md next to the re-pinned ones.
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
     * The P3 same-stem boost metric: for every unique eval word that decomposes as stem+suffix —
     * the remainder a form in the runtime suffix table, the stem a dictionary word — is the word
     * in the top-3 when the user has typed exactly the stem? Decomposition is deterministic: the
     * LONGEST qualifying stem wins. The same measurement against an index without the table is the
     * control (what the frozen D1 ranking achieved); both counters are pinned exactly.
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
        assertEquals("75.3623", format(coveredPct))
        assertEquals("9.5468", format(hitPct))
        assertEquals("12.6679", format(hitCoveredPct))
    }

    /**
     * P4 (docs/TT-SUGGESTIONS.md): at a sentence start the strip now answers from the committed
     * sentence-start table. The P0 baseline pinned `strip_empty_sentence_start_pct` at 100.0000
     * (no prefix, no context word, nothing to show); it is re-pinned at 0.0000 — the table always
     * answers — and the new hit-rate metric is the honest quality measure of a static table on a
     * held-out conversational set: the fraction of eval sentences whose first word is among the
     * top-3 suggestions. The prefix side of the contract is unchanged and stays pinned: an empty
     * typed prefix still has no completions, and the bigram table is still never queried without
     * a context word.
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
        // Pins measured on 2026-09-19 against the committed assets and the committed eval
        // set; re-pin consciously when either changes (see class KDoc).
        private const val PIN_EVAL_LINES = 1_000
        private const val PIN_UNIQUE_WORDS = 2_670
        private const val PIN_PAIRS = 4_347
        private const val PIN_COVERED = 3_276
        private const val PIN_TOP3_HITS = 415
        private const val PIN_CP1_WORDS = 2_670
        private const val PIN_CP2_WORDS = 2_668
        private const val PIN_CP3_WORDS = 2_626
        // P3 with the 2026-09-20 refinement (docs/TT-SUGGESTIONS.md): the same-stem boost is gated
        // at >= 4 code-point prefixes, and this metric types 1-3 code-point prefixes — so the
        // counters are exactly the P2 baseline again. The measured excursion without the gate was
        // cp1 64 -> 29, cp2 301 -> 235, cp3 741 -> 685; the gate exempts those prefixes entirely.
        private const val PIN_CP1_HITS = 64
        private const val PIN_CP2_HITS = 301
        private const val PIN_CP3_HITS = 741
        // P3 same-stem metric, measured 2026-09-20 on the committed assets, boost gated at >= 4
        // code points: 988 (boost off) -> 1 078; the ungated variant measured 1 201.
        private const val PIN_SAMESTEM_WORDS = 1_716
        private const val PIN_SAMESTEM_HITS = 1_078
        private const val PIN_SAMESTEM_HITS_BOOST_OFF = 988
        // P4 sentence-start metric, measured 2026-09-20 on the committed table against the pinned
        // eval set: 123 of 1 000 first words are in the top-3 (бу, ул, ә).
        private const val PIN_SENTSTART_TOP3_HITS = 123

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

            // P4: the committed sentence-start table, parsed through the production reader.
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
                // The production Tatar engine is constructed with the P3 suffix rules and —
                // since TT-TYPO-NEXT Phase C2 — the TATAR fuzzy policy (class #1 + the gated
                // class #4 + the same-length bonus): the eval index carries both, and a second
                // rules-free instance is the boost control. The policy is provably inert on these
                // metrics (class #4 fires only on an empty exact pass at >= 4 code points, and
                // fuzzy candidates only ever fill cells the exact pass left free), so the pinned
                // counters do not move.
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
