package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictFormat

data class DictionaryIdentity(
    val generation: Int,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

internal fun interface PrefixComputer {
    fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String>
}

/**
 * P3 same-stem boost seam (docs/TT-SUGGESTIONS.md): a language-specific table of inflectional
 * suffix forms, consulted by the exact pass of [TdictPrefixIndex.lookup] when the typed prefix is
 * itself a complete dictionary word. The remainder of a candidate comes straight off the mapped
 * buffer in at most two contiguous pieces (a schema-2 word is a shared prefix of its block's first
 * word plus a suffix of its own), so the test takes them as two byte ranges and must never
 * allocate. The Tatar engine is constructed with `TatarSuffixRules`; the Russian engine passes
 * null and never applies Tatar rules.
 */
fun interface InflectedSuffixTable {
    fun isInflectedContinuation(
        bytes: ByteBuffer,
        firstStart: Int,
        firstLength: Int,
        secondStart: Int,
        secondLength: Int,
    ): Boolean
}

/**
 * Exact whole-word frequency of a dictionary: the P3 after-word forms keep only generated
 * candidates the dictionary actually carries. [TdictPrefixIndex] is the implementation; the
 * sentinel for "absent" is 0 (schema 2 stores strictly positive frequencies).
 */
fun interface WordFrequencySource {
    fun frequencyOf(word: String): Long
}

/**
 * Sink of [TdictPrefixIndex.forEachWordCold] (P7-1, docs/GLIDE-PLAN.md): one visit per entry, in
 * dictionary order, with the word and its raw frequency. A fun interface with a primitive Long
 * (not a Kotlin lambda type) so the full-dictionary walk does not box a Long per word.
 */
fun interface ColdWordVisitor {
    fun visit(word: String, frequency: Long)
}

/**
 * A [PrefixComputer] that also reports how many of the results it just returned were EXACT
 * dictionary candidates; the rest are fuzzy (E3).
 *
 * The three-class merge of E4b has to insert one personal word BETWEEN the exact and the fuzzy
 * candidates, so it must know where the boundary is — and the frozen `lookup` signature returns a
 * bare `List<String>` that cannot carry it. The count is exposed as state rather than as a richer
 * return type on purpose: `lookup` stays frozen, and no object is allocated per lookup to carry two
 * numbers. Reading it is safe under exactly the guarantee the index's scratch buffers already rely
 * on — at most one active worker, serialized by `LatestOnlyPrefixEngine`.
 */
internal interface ClassifiedPrefixComputer : PrefixComputer {
    /** Number of LEADING results of the last [lookup] that are exact candidates. */
    val lastExactCount: Int

    /**
     * The D3 autocorrect verdict of the last [lookup], or null when the typed word must not be
     * replaced. Read from the UI thread, hence the implementations publish it through a `@Volatile`
     * reference; a computer that does not run the class #1 pass simply never advises anything.
     */
    val lastAutocorrectAdvice: AutocorrectAdvice?
        get() = null
}

/**
 * Receives the current key-neighbor table for a computer that runs a fuzzy pass. Kept separate from
 * [PrefixComputer] so the frozen `lookup` signature never changes.
 */
internal interface KeyNeighborSink {
    fun updateKeyNeighbors(table: KeyNeighborTable?)
}

/** Strict scalar UTF-8 validation without a decoder or temporary objects. */
internal fun isValidUtf8Scalar(bytes: ByteArray): Boolean =
    isValidUtf8Scalar(bytes.size) { bytes[it].toInt() and 0xff }

internal fun isValidUtf8Scalar(bytes: ImmutableUtf8Prefix): Boolean =
    isValidUtf8Scalar(bytes.byteCount) { bytes.byteAt(it) }

private inline fun isValidUtf8Scalar(size: Int, byteAt: (Int) -> Int): Boolean {
    var index = 0
    while (index < size) {
        val first = byteAt(index)
        val continuationCount: Int
        val minimumCodePoint: Int
        var codePoint: Int
        when {
            first <= 0x7f -> {
                index++
                continue
            }
            first in 0xc2..0xdf -> {
                continuationCount = 1
                minimumCodePoint = 0x80
                codePoint = first and 0x1f
            }
            first in 0xe0..0xef -> {
                continuationCount = 2
                minimumCodePoint = 0x800
                codePoint = first and 0x0f
            }
            first in 0xf0..0xf4 -> {
                continuationCount = 3
                minimumCodePoint = 0x10000
                codePoint = first and 0x07
            }
            else -> return false
        }
        if (index + continuationCount >= size) return false
        for (offset in 1..continuationCount) {
            val continuation = byteAt(index + offset)
            if (continuation !in 0x80..0xbf) return false
            codePoint = (codePoint shl 6) or (continuation and 0x3f)
        }
        if (codePoint < minimumCodePoint ||
            codePoint > 0x10ffff ||
            codePoint in 0xd800..0xdfff
        ) {
            return false
        }
        index += continuationCount + 1
    }
    return true
}

/**
 * Immutable schema-2 reader. The supplied buffer must already have passed D1b validation.
 *
 * [suffixTable] is the P3 same-stem boost table (docs/TT-SUGGESTIONS.md): null for an engine that
 * never boosts (the Russian one, and every fixture that predates P3 — their behavior is
 * byte-identical to the frozen D1 pass). It is consulted by the exact pass only, and only when the
 * typed prefix is itself a complete dictionary word.
 */
internal class TdictPrefixIndex private constructor(
    private val bytes: ByteBuffer,
    val identity: DictionaryIdentity,
    override val entryCount: Int,
    private val blockCount: Int,
    private val blockIndexOffset: Int,
    private val suffixTable: InflectedSuffixTable?,
    private val fuzzyPolicy: FuzzyEditPolicy,
) : ClassifiedPrefixComputer, KeyNeighborSink, BigramDictionary, WordFrequencySource,
    TopFrequencySource {
    // Reusable per-index scratch. The index stops being fully immutable: these buffers are touched
    // ONLY inside lookup(), whose exclusivity is guaranteed by LatestOnlyPrefixEngine serialization
    // (at most one active worker). updateKeyNeighbors() only swaps a @Volatile reference.
    private val exactScratch = ByteArray(MAX_PREFIX_BYTES)
    private val variantScratch = ByteArray(MAX_PREFIX_BYTES + VARIANT_HEADROOM)
    private val codePointScratch = IntArray(MAX_PREFIX_BYTES)
    // Front-coding decode scratch: word A is the target of every single-word access; two-word
    // comparisons (ranking tie-breaks) decode the second word into word B. Neither escapes lookup().
    private val wordScratchA = ByteArray(TdictFormat.MAX_WORD_BYTES)
    private val wordScratchB = ByteArray(TdictFormat.MAX_WORD_BYTES)
    // Phase C2: the probe path's own decode scratch, deliberately separate from A/B — probes
    // interleave with survivor scans whose tie-breaks use A/B, and a shared scratch would have to
    // prove the absence of interleavings rather than just having it.
    private val probeScratch = ByteArray(TdictFormat.MAX_WORD_BYTES)
    // P6: the class-#5 driver's own chain scratch (a seed prefix lives across its stage-B
    // enumeration) and per-stage-B next-letter buffer. Constructor-allocated once; the pass
    // itself allocates nothing.
    private val edit2ChainScratch = ByteArray(MAX_PREFIX_BYTES + VARIANT_HEADROOM)
    private val edit2NextLetters = IntArray(64)
    private val edit2ByteStart = IntArray(MAX_PREFIX_BYTES)
    // Phase C2: per-position search ranges for the class #4 probes — words starting with the
    // first N code points of the typed prefix, incrementally narrowed once per lookup. A variant
    // substituted at position p shares the typed prefix's first p code points, so its survivors
    // can only live in probeRange[p] — and an empty range skips the whole position for free.
    private val probeRangeStart = IntArray(MAX_PREFIX_BYTES)
    private val probeRangeEnd = IntArray(MAX_PREFIX_BYTES)
    // The block touched by the last access, fully decoded: concatenated word bytes, per-word
    // start offsets and frequencies. Range scans (the exact pass over thousands of matches for a
    // one-letter prefix, the fuzzy variant scans) touch every entry of a block, so decoding the
    // block once — instead of re-walking it per entry — is what keeps schema 2 at schema-1
    // latency. Worker-confined exactly like the scratches above; the data is immutable, so the
    // cache is valid across lookups and never needs invalidation.
    private val blockWordBytes = ByteArray(TdictFormat.BLOCK_SIZE * TdictFormat.MAX_WORD_BYTES)
    private val blockWordStarts = IntArray(TdictFormat.BLOCK_SIZE + 1)
    private val blockFrequencies = LongArray(TdictFormat.BLOCK_SIZE)
    private var cachedBlock = -1
    private val rankedIndices = IntArray(MAX_RESULTS)
    private val rankedFrequencies = LongArray(MAX_RESULTS)
    // P3 same-stem boost (docs/TT-SUGGESTIONS.md): the two tracks of the dual-track exact pass —
    // candidates whose remainder is a known suffix, and the rest. Fixed-size scratch, allocated
    // once per index; the pass itself stays allocation-free.
    private val stemTrackIndices = IntArray(MAX_RESULTS)
    private val stemTrackFrequencies = LongArray(MAX_RESULTS)
    private val stemTrackClasses = IntArray(MAX_RESULTS)
    private val otherTrackIndices = IntArray(MAX_RESULTS)
    private val otherTrackFrequencies = LongArray(MAX_RESULTS)
    private val otherTrackClasses = IntArray(MAX_RESULTS)
    // Ranking key carried alongside every ranked slot as a plain primitive int — no boxing, no
    // collection. Exact candidates all carry EDIT_CLASS_EXACT, so the key is a no-op tie on
    // the exact level and its frozen order is unchanged; fuzzy candidates carry their packed rank
    // key (edit class x2 minus the same-length bonus — see [fuzzyRankKey]), so the fuzzy level
    // orders by class first (see [ranksBefore]).
    private val rankedClasses = IntArray(MAX_RESULTS)
    private val fuzzyIndices = IntArray(MAX_RESULTS)
    private val fuzzyFrequencies = LongArray(MAX_RESULTS)
    private val fuzzyClasses = IntArray(MAX_RESULTS)
    // Scratch of the D3 pass. Separate buffers rather than a reuse of the two above, because the
    // autocorrect pass must stay independent of whether the display fuzzy level ran at all: it is
    // decided by rules of its own (word length, word absent from the dictionary), never by how many
    // cells the exact pass happened to leave empty.
    private val autocorrectCodePointScratch = IntArray(MAX_PREFIX_BYTES)
    private val autocorrectVariantScratch = ByteArray(MAX_PREFIX_BYTES + VARIANT_HEADROOM)

    @Volatile
    private var neighborTable: KeyNeighborTable? = null

    /**
     * How many leading results of the last [lookup] are exact. Worker-confined exactly like the
     * scratch buffers above; reset at the top of every lookup so a failed or rejected one cannot
     * leave a stale boundary behind for the merge to trust.
     */
    override var lastExactCount = 0
        private set

    /**
     * The D3 verdict of the last [lookup]. Written by the serialized worker, read on the UI thread
     * when a word separator is pressed, hence `@Volatile`: the object itself is immutable, so
     * publishing the reference publishes everything the reader needs.
     *
     * Reset at the top of every lookup, exactly like [lastExactCount], so a rejected or failed
     * lookup can never leave an older word's verdict behind for the next separator to act on.
     */
    @Volatile
    override var lastAutocorrectAdvice: AutocorrectAdvice? = null
        private set

    /** Drops the current verdict; called when the engine idles or is torn down. */
    fun clearAutocorrectAdvice() {
        lastAutocorrectAdvice = null
    }

    // Test-only observability of the last lookup's fuzzy work. These are plain ints assigned on the
    // hot path (no allocation, no logging); they let the JVM harness report measured variants and
    // visited entries and prove the fail-closed budget never trips on the typo set. Phase C adds
    // the probe counter (edit class #4 probe-first).
    internal var lastFuzzyVariantCount = 0
        private set
    internal var lastFuzzyVisitedCount = 0
        private set
    internal var lastFuzzyOverBudget = false
        private set
    internal var lastFuzzyProbeCount = 0
        private set

    // Same observability for the D3 autocorrect pass (ROADMAP-P3 P7): class-#4 probes issued by
    // the last lookup's advice computation. Worker-confined exactly like the counters above.
    internal var lastAutocorrectProbeCount = 0
        private set

    // Same observability for the class-#5 pass (ROADMAP-P4 P6): total probes issued by the last
    // lookup's chained two-substitution enumeration (seed probes + chain extensions + full-variant
    // probes + survivor scans). Worker-confined exactly like the counters above.
    internal var lastEdit2ProbeCount = 0
        private set

    // Fuzzy-pass accumulator, private to a single lookup() invocation and reset on each entry.
    private var fuzzyExactCount = 0
    private var fuzzyRemaining = 0
    private var fuzzyCount = 0
    private var fuzzyVisited = 0
    private var fuzzyOverBudget = false
    private var fuzzyPrefixLength = 0
    // Variants that consumed the shared MAX_FUZZY_VARIANTS budget so far (every class #1/#2/#3
    // variant, and each class #4 SURVIVOR — its probes count against MAX_FUZZY_PROBES instead).
    private var fuzzyVariantsUsed = 0
    private var fuzzyProbesUsed = 0
    // Class-#5 (P6) accumulators, private to a single collectFuzzy invocation.
    private var edit2ProbesUsed = 0
    private var edit2ScansUsed = 0
    // The plausibility of the variant currently feeding scanVariantBlock: the number of its edits
    // (0..2) that are attested confusion pairs in the layout's long-press map. A plain int set by
    // the class-#5 driver before each scan and read by the packed rank key; classes #1-#4 leave it
    // at zero, which preserves their existing order exactly.
    private var fuzzyCurrentPlausibility = 0

    // The edit class of the variant pass currently running (EDIT_CLASS_LONG_PRESS/GEOMETRIC/
    // TRANSPOSITION/SUBSTITUTION). A plain int, set before each class's generator runs and read by
    // scanVariantBlock so every fuzzy candidate is tagged with the class that produced it.
    private var fuzzyCurrentClass = EDIT_CLASS_LONG_PRESS

    // D3 accumulator, private to a single computeAutocorrectAdvice() invocation. The pass counts
    // WHOLE-WORD matches: how many class #1/#4 variants of the typed word are themselves dictionary
    // entries, and which one. Anything but exactly one means no replacement.
    private var autocorrectMatchCount = 0
    private var autocorrectMatchIndex = NO_ENTRY
    private var autocorrectProbesUsed = 0

    // Allocated once, so neither a lambda nor any object is created per lookup or per variant.
    private val fuzzyConsumer =
        FuzzyPrefixVariants.VariantConsumer { bytes, length -> scanVariantBlock(bytes, length) }

    private val substitutionProbeConsumer =
        FuzzyPrefixVariants.PositionedVariantConsumer { position, bytes, length ->
            probeAndScanVariant(position, bytes, length)
        }

    private val autocorrectConsumer =
        FuzzyPrefixVariants.VariantConsumer { bytes, length -> matchWholeWord(bytes, length) }

    override fun updateKeyNeighbors(table: KeyNeighborTable?) {
        neighborTable = table
    }

    override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
        val prefixLength = normalizedPrefixUtf8.byteCount
        lastExactCount = 0
        lastAutocorrectAdvice = null
        lastAutocorrectProbeCount = 0
        if (prefixLength == 0 ||
            prefixLength > MAX_PREFIX_BYTES ||
            !isValidUtf8Scalar(normalizedPrefixUtf8)
        ) {
            return emptyList()
        }
        return try {
            lastFuzzyVariantCount = 0
            lastFuzzyVisitedCount = 0
            lastFuzzyProbeCount = 0
            lastEdit2ProbeCount = 0
            lastFuzzyOverBudget = false
            for (offset in 0 until prefixLength) {
                exactScratch[offset] = normalizedPrefixUtf8.byteAt(offset).toByte()
            }
            var resultCount = collectExact(prefixLength)
            // Recorded before the fuzzy pass appends to the same ranked arrays: everything after
            // this many slots is fuzzy, which is exactly what the E4b merge needs to know.
            lastExactCount = resultCount
            // The fuzzy level fills only cells left empty by D1, and only when the exact pass
            // returned fewer than three candidates: one check, no new state. Exact candidates are
            // never shifted or replaced.
            if (resultCount < MAX_RESULTS) {
                val table = neighborTable
                val codePointCount = countCodePointsByLeadBytes(exactScratch, prefixLength)
                if (table != null && !table.isEmpty &&
                    codePointCount >= MIN_FUZZY_PREFIX_CODE_POINTS
                ) {
                    resultCount = collectFuzzy(prefixLength, table, resultCount, codePointCount)
                }
            }
            // Deliberately outside the `resultCount < MAX_RESULTS` guard above: the D3 verdict is
            // about the typed word itself and must not depend on how full the band happens to be.
            computeAutocorrectAdvice(normalizedPrefixUtf8, prefixLength)
            if (resultCount == 0) return emptyList()
            ArrayList<String>(resultCount).also { result ->
                for (slot in 0 until resultCount) {
                    result += decodeWord(rankedIndices[slot])
                }
            }
        } catch (_: RuntimeException) {
            lastExactCount = 0
            lastAutocorrectAdvice = null
            emptyList()
        }
    }

    /**
     * The D3 pass: decides whether the word that was just looked up may be autocorrected, and to
     * what. Runs on the same worker, right after the display passes, and touches the same mmap'd
     * buffer they do — so no new thread, no new request and no new token exist anywhere.
     *
     * Every condition of the contract is checked here, in the contract's own order:
     *  - the word is at least [AutocorrectPolicy.MIN_WORD_CODE_POINTS] code points long;
     *  - the word is ABSENT from the dictionary (a word people write is never "corrected");
     *  - EXACTLY ONE class #1 (long-press partner) variant of it is itself a dictionary word —
     *    counted before any frequency filter, so an ambiguous typo is left alone rather than
     *    resolved by frequency;
     *  - that one candidate's frequency is at least [AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY].
     *
     * Two properties are worth naming. The match is WHOLE-WORD, not prefix-block: the contract
     * replaces a word by a word one edit away from it, and a prefix scan would offer continuations
     * instead. And the class set is the policy's OWN [FuzzyEditPolicy.autocorrectClasses], never
     * the display set: a display policy enabling classes #2/#4 (the Tatar one) must not make
     * autocorrect follow implicitly — the D3 widening is decided by its own written gates
     * (ROADMAP-P3 P7, docs/ROADMAP-P3.md). With the default policy this pass is byte-identical to
     * the frozen D3 class-#1 behavior.
     *
     * The class-#1 side costs one binary search per variant and scans no block at all. The class-#4
     * side is probe-first exactly like the display pass (the TT-TYPO-NEXT C2 engineering reused):
     * per-position narrowed no-cache probes, whole-word equality, never a range scan. The whole
     * pass runs only for words long enough to qualify, so short prefixes pay nothing.
     */
    private fun computeAutocorrectAdvice(
        normalizedPrefixUtf8: ImmutableUtf8Prefix,
        prefixLength: Int,
    ) {
        val table = neighborTable ?: return
        if (table.isEmpty) return
        val codePointCount = countCodePointsByLeadBytes(exactScratch, prefixLength)
        if (codePointCount < AutocorrectPolicy.MIN_WORD_CODE_POINTS) {
            return
        }
        val typedEntry = lowerBound(exactScratch, prefixLength, 0)
        if (typedEntry < entryCount && wordEquals(typedEntry, exactScratch, prefixLength)) return
        autocorrectMatchCount = 0
        autocorrectMatchIndex = NO_ENTRY
        autocorrectProbesUsed = 0

        if (EDIT_CLASS_LONG_PRESS in fuzzyPolicy.autocorrectClasses) {
            val emitted = FuzzyPrefixVariants.generateLongPressVariants(
                exactScratch, prefixLength, table, autocorrectCodePointScratch,
                autocorrectVariantScratch, MAX_FUZZY_VARIANTS, autocorrectConsumer,
            )
            // Fail closed on a budget overrun or malformed input, exactly like the display level: a
            // partially generated variant set could hide the second candidate that makes a typo
            // ambiguous, and acting on it would replace text on incomplete evidence.
            if (emitted < 0) return
        }

        // ROADMAP-P3 P7: the widened side — full single substitution, probe-first. Early-out once
        // the word is already ambiguous: further probes can only add candidates, never remove one,
        // and the verdict below is already NO.
        if (EDIT_CLASS_SUBSTITUTION in fuzzyPolicy.autocorrectClasses &&
            autocorrectMatchCount < 2
        ) {
            computeProbeRanges(codePointCount)
            val emitted = FuzzyPrefixVariants.generateFullSubstitutionVariants(
                exactScratch, prefixLength, table.nodes, autocorrectCodePointScratch,
                autocorrectVariantScratch, MAX_FUZZY_PROBES, autocorrectProbeConsumer,
            )
            if (emitted < 0) return
        }
        lastAutocorrectProbeCount = autocorrectProbesUsed

        if (autocorrectMatchCount != 1) return
        val candidate = autocorrectMatchIndex
        val frequency = frequencyAt(candidate)
        if (frequency < AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY) return
        lastAutocorrectAdvice = AutocorrectAdvice(
            normalizedPrefixUtf8.decodeUtf8(),
            decodeWord(candidate),
            frequency,
        )
    }

    /** Counts one class #1 variant that is itself a dictionary entry; stops caring past two. */
    private fun matchWholeWord(variantBytes: ByteArray, variantLength: Int) {
        if (autocorrectMatchCount > 1) return
        val entry = lowerBound(variantBytes, variantLength, 0)
        if (entry >= entryCount) return
        if (!wordEquals(entry, variantBytes, variantLength)) return
        // Distinct variants are distinct byte strings, so this can only fire on a defensive re-entry;
        // counting the same entry twice would turn one candidate into a false ambiguity.
        if (entry == autocorrectMatchIndex) return
        autocorrectMatchCount++
        autocorrectMatchIndex = entry
    }

    /** The probe-first whole-word consumer of the class-#4 autocorrect variants (ROADMAP-P3 P7). */
    private val autocorrectProbeConsumer =
        FuzzyPrefixVariants.PositionedVariantConsumer { position, bytes, length ->
            probeAutocorrectWholeWord(position, bytes, length)
        }

    /**
     * One narrowed no-cache probe per class-#4 variant, then whole-word equality — never a range
     * scan. Counts a match exactly like [matchWholeWord] does (dedup by entry index across
     * classes; stops caring past two).
     */
    private fun probeAutocorrectWholeWord(position: Int, variantBytes: ByteArray, variantLength: Int) {
        if (autocorrectMatchCount > 1) return
        val rangeStart = probeRangeStart[position]
        val rangeEnd = probeRangeEnd[position]
        if (rangeStart >= rangeEnd) return
        autocorrectProbesUsed++
        val probe = probeLowerBound(variantBytes, variantLength, rangeStart, rangeEnd)
        if (probe >= rangeEnd) return
        val wordLength = decodeWordInto(probe, probeScratch)
        if (wordLength != variantLength) return
        for (offset in 0 until variantLength) {
            if (unsigned(probeScratch[offset]) != (variantBytes[offset].toInt() and 0xff)) return
        }
        // A class-#1/class-#4 duplicate must not count twice, or one candidate would read as two.
        if (probe == autocorrectMatchIndex) return
        autocorrectMatchCount++
        autocorrectMatchIndex = probe
    }

    /**
     * The frozen D1 exact pass: fills [rankedIndices] with up to [MAX_RESULTS] and returns count.
     *
     * P3 (docs/TT-SUGGESTIONS.md): with a suffix table injected AND the typed prefix being itself a
     * complete dictionary word of at least [MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS] code points,
     * the pass runs dual-track — candidates whose remainder is a known suffix rank before unrelated
     * continuations, frequency order preserved within each group, the typed word itself still
     * excluded. Every other case is byte-identical to the frozen pass. The complete-word test costs
     * no search of its own: [lowerBound] has already landed on the entry when the prefix is one.
     *
     * The length gate (P3 refinement, 2026-09-20): a short complete word (су, ал, өй) is a common
     * MID-TYPING state — the user is usually on the way to a longer word, so its inflections must
     * not displace unrelated continuations; a long one is likely an intentional word end. The
     * threshold mirrors [AutocorrectPolicy.MIN_WORD_CODE_POINTS], the one other place the engine
     * treats a typed word as "settled enough to act on". It also keeps the eval proxies honest:
     * the completion metric types 1–3 code-point prefixes, which the gate exempts entirely.
     */
    private fun collectExact(prefixLength: Int): Int {
        val start = lowerBound(exactScratch, prefixLength, 0)
        val end = upperBound(exactScratch, prefixLength, start)
        if (start >= end) return 0
        val table = suffixTable
        if (table == null ||
            countCodePointsByLeadBytes(exactScratch, prefixLength) <
            MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS ||
            !wordEquals(start, exactScratch, prefixLength)
        ) {
            var resultCount = 0
            scanBlockRange(
                start, end, exactScratch, prefixLength,
                onEntry = { _, equalsQuery, _, _, _, _ ->
                    if (equalsQuery) SCAN_SKIP else SCAN_TAKE
                },
                onFrequency = { index, frequency, _ ->
                    resultCount = insertRanked(
                        rankedIndices, rankedFrequencies, rankedClasses, resultCount, MAX_RESULTS,
                        index, frequency, EDIT_CLASS_EXACT,
                    )
                },
            )
            return resultCount
        }
        var stemCount = 0
        var otherCount = 0
        scanBlockRange(
            start, end, exactScratch, prefixLength,
            onEntry = { _, equalsQuery, remFirstStart, remFirstLength, remSecondStart, remSecondLength ->
                when {
                    equalsQuery -> SCAN_SKIP
                    table.isInflectedContinuation(
                        bytes, remFirstStart, remFirstLength, remSecondStart, remSecondLength,
                    ) -> SCAN_TAKE_STEM
                    else -> SCAN_TAKE
                }
            },
            onFrequency = { index, frequency, verdict ->
                if (verdict == SCAN_TAKE_STEM) {
                    stemCount = insertRanked(
                        stemTrackIndices, stemTrackFrequencies, stemTrackClasses,
                        stemCount, MAX_RESULTS, index, frequency, EDIT_CLASS_EXACT,
                    )
                } else {
                    otherCount = insertRanked(
                        otherTrackIndices, otherTrackFrequencies, otherTrackClasses,
                        otherCount, MAX_RESULTS, index, frequency, EDIT_CLASS_EXACT,
                    )
                }
            },
        )
        // Same-stem candidates first, then the rest fill the cells they leave — all within
        // MAX_RESULTS, both tracks already in the frozen (frequency, code point) order.
        var resultCount = 0
        for (slot in 0 until stemCount) {
            rankedIndices[resultCount] = stemTrackIndices[slot]
            rankedFrequencies[resultCount] = stemTrackFrequencies[slot]
            rankedClasses[resultCount] = stemTrackClasses[slot]
            resultCount++
        }
        for (slot in 0 until otherCount) {
            if (resultCount >= MAX_RESULTS) break
            rankedIndices[resultCount] = otherTrackIndices[slot]
            rankedFrequencies[resultCount] = otherTrackFrequencies[slot]
            rankedClasses[resultCount] = otherTrackClasses[slot]
            resultCount++
        }
        return resultCount
    }

    /**
     * Fills the cells the exact pass left empty with the best fuzzy candidates. Within the fuzzy
     * level the order is edit class first (class #1 long-press partner, then #2 geometric
     * neighbour, then #3 transposition, then #4 full single substitution); inside one class the
     * TT-TYPO-NEXT Phase-B same-length bonus applies when the engine's policy enables it (a
     * candidate exactly as long as the typed prefix ranks before its own continuations), and then
     * the frozen tie-break (frequency descending, then code-point lexical ascending). Exact
     * candidates are never touched and always outrank any fuzzy candidate. Returns the total
     * candidate count.
     *
     * WHICH edit classes run is the engine's [FuzzyEditPolicy], injected per engine through
     * [open] (TT-TYPO-NEXT Phases B/C/C2, docs/TT-TYPO-NEXT.md). The Tatar engine ships
     * [FuzzyEditPolicy.TATAR] — class #1 + the gated class #4 + the same-length bonus — which
     * passed the corrected C2 gates (2026-09-20). [FuzzyEditPolicy.DEFAULT] (class #1 only, no
     * bonus) is what every other engine runs — bit-identical to the pre-Phase-B behavior (the E3b
     * verdict, PROPOSALS.md section "Контракт текста", line "Итог, 2026-07-27",
     * docs/archive/missions/DICTIONARY-E3.md). Classes #2 (geometric neighbour — rejected by
     * Phase-B G1) and #3 (transposition — never re-calibrated) stay unreachable through a shipped
     * lookup(); their generators stay in the tree as infrastructure with direct tests. There is
     * no per-request state and no user-facing toggle.
     *
     * Class #4 (Phase C, probe-first full single substitution) carries its own ACTIVATION GATE on
     * top of the policy: it runs only when the exact pass returned ZERO results and the prefix is
     * at least [MIN_SUBSTITUTION_PREFIX_CODE_POINTS] code points — the strip is empty in those
     * cases, so a correct guess is pure gain and a wrong one displaces nothing.
     *
     * The whole fuzzy level is dropped (returns [exactCount]) if variant generation or the block
     * scan trips a fixed budget: the level is discarded in full, never in part.
     */
    private fun collectFuzzy(
        prefixLength: Int,
        table: KeyNeighborTable,
        exactCount: Int,
        codePointCount: Int,
    ): Int {
        fuzzyExactCount = exactCount
        fuzzyRemaining = MAX_RESULTS - exactCount
        fuzzyCount = 0
        fuzzyVisited = 0
        fuzzyOverBudget = false
        fuzzyPrefixLength = prefixLength
        fuzzyVariantsUsed = 0
        fuzzyProbesUsed = 0
        edit2ProbesUsed = 0
        edit2ScansUsed = 0
        fuzzyCurrentPlausibility = 0
        // The enabled classes share one variant budget: the total number of variants SCANNED across
        // all of them must stay within MAX_FUZZY_VARIANTS (for class #4 only survivors consume it —
        // its probes count against MAX_FUZZY_PROBES instead). The edit class DOES affect ranking
        // (class #1 before #2 before #3 before #4 before #5, then the same-length bonus, then the
        // class-#5 plausibility, then frequency inside a class); each candidate is tagged with a
        // rank key derived from fuzzyCurrentClass, set below before its class runs. Any single
        // class returning -1 (its slice of a budget exceeded) drops the whole fuzzy level, never
        // a part of it.

        if (EDIT_CLASS_LONG_PRESS in fuzzyPolicy.editClasses) {
            fuzzyCurrentClass = EDIT_CLASS_LONG_PRESS
            val emitted = FuzzyPrefixVariants.generateLongPressVariants(
                exactScratch, prefixLength, table, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - fuzzyVariantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            fuzzyVariantsUsed += emitted
        }

        if (EDIT_CLASS_GEOMETRIC in fuzzyPolicy.editClasses) {
            fuzzyCurrentClass = EDIT_CLASS_GEOMETRIC
            val emitted = FuzzyPrefixVariants.generateGeometricVariants(
                exactScratch, prefixLength, table, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - fuzzyVariantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            fuzzyVariantsUsed += emitted
        }

        if (EDIT_CLASS_TRANSPOSITION in fuzzyPolicy.editClasses) {
            fuzzyCurrentClass = EDIT_CLASS_TRANSPOSITION
            val emitted = FuzzyPrefixVariants.generateTranspositionVariants(
                exactScratch, prefixLength, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - fuzzyVariantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            fuzzyVariantsUsed += emitted
        }

        // Class #4 (Phase C): full single substitution over the layout's alphabet, PROBE-FIRST —
        // each of the n x alphabet variants gets one existence probe (binary search, no range
        // scan), and only survivors are scanned and counted against the shared variant budget.
        // The activation gate is the point of the design: the class fires only when the exact
        // pass found NOTHING and the prefix is settled (>= 4 code points), so it can only ever
        // fill an otherwise empty strip.
        if (EDIT_CLASS_SUBSTITUTION in fuzzyPolicy.editClasses &&
            exactCount == 0 && codePointCount >= MIN_SUBSTITUTION_PREFIX_CODE_POINTS
        ) {
            fuzzyCurrentClass = EDIT_CLASS_SUBSTITUTION
            computeProbeRanges(codePointCount)
            val emitted = FuzzyPrefixVariants.generateFullSubstitutionVariants(
                exactScratch, prefixLength, table.nodes, codePointScratch, variantScratch,
                MAX_FUZZY_PROBES - fuzzyProbesUsed, substitutionProbeConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                lastFuzzyProbeCount = fuzzyProbesUsed
                return exactCount
            }
            // fuzzyProbesUsed was already incremented per issued probe by the consumer; `emitted`
            // is the generated-variant count (probes issued + skipped-by-empty-range), which must
            // NOT be added — that would double-count.
        }

        // Class #5 (ROADMAP-P4 P6, docs/ROADMAP-P4.md): TWO substitutions at distinct positions,
        // full class over the layout alphabet, enumerated by the chained probe design below. The
        // activation gate is deliberately stricter than class #4's: class #5 fires only when the
        // exact pass found NOTHING *and* every earlier class produced no candidate — the strip is
        // empty either way, so a correct guess is pure gain and a wrong one displaces nothing
        // (the G2 precision condition is structural, not measured).
        //
        // VERDICT (2026-09-23, TwoSubstitutionCalibrationTest): NO SHIP. The +10 pp G1 recovery
        // gate is unreachable even at the perfect-recall ceiling (9.79 % — 113.6 edit-2
        // competitors per empty-strip row bury the original word at median rank 28), and this
        // chained probe-per-letter enumeration trips the fail-closed probe budget on 73 % of
        // firing rows (measured recovery: 0). The class stays unwired — no policy enables it;
        // the machinery and the calibration stand as the documented evidence.
        if (EDIT_CLASS_TWO_SUBSTITUTIONS in fuzzyPolicy.editClasses &&
            exactCount == 0 && fuzzyCount == 0 &&
            codePointCount >= MIN_TWO_SUBST_PREFIX_CODE_POINTS
        ) {
            fuzzyCurrentClass = EDIT_CLASS_TWO_SUBSTITUTIONS
            collectTwoSubstitutionVariants(table, prefixLength, codePointCount)
            if (fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                lastFuzzyProbeCount = fuzzyProbesUsed
                lastEdit2ProbeCount = edit2ProbesUsed
                return exactCount
            }
            fuzzyVariantsUsed += edit2ScansUsed
        }

        lastFuzzyVariantCount = fuzzyVariantsUsed
        lastFuzzyVisitedCount = fuzzyVisited
        lastFuzzyProbeCount = fuzzyProbesUsed
        lastEdit2ProbeCount = edit2ProbesUsed
        for (slot in 0 until fuzzyCount) {
            rankedIndices[exactCount + slot] = fuzzyIndices[slot]
            rankedFrequencies[exactCount + slot] = fuzzyFrequencies[slot]
            rankedClasses[exactCount + slot] = fuzzyClasses[slot]
        }
        return exactCount + fuzzyCount
    }

    /**
     * The probe-first consumer of edit class #4: one existence probe per variant — a binary
     * search plus a starts-with check, no range scan — and only a variant that provably starts at
     * least one dictionary word (a survivor) consumes the shared variant budget and gets its block
     * scanned by [scanVariantBlock]. A survivor overflow trips the budget fail-closed, exactly
     * like a generator overflow.
     *
     * Phase C2 (docs/TT-TYPO-NEXT.md) — the probe cost engineering, in two steps:
     *
     * 1. The probe's search NEVER touches the shared decoded-block cache. The Phase-C profile
     *    showed why: hundreds of probes per lookup traverse nearly identical binary-search paths,
     *    and routing each step through the one-entry cache made every probe evict the previous
     *    probe's block — ~17 full block decodes per probe, 31.6 ms p95 on the reference device.
     *    The probe reads each compared word directly off the mapped bytes ([decodeWordInto] into
     *    a dedicated scratch): a bounded front-coded walk, no cache involvement, no allocations.
     * 2. The search is NARROWED per position: a variant substituted at position p shares the typed
     *    prefix's first p code points, so its survivors can only live in probeRange[p] — computed
     *    once per lookup by [computeProbeRanges], incrementally narrowed, and free to skip when
     *    empty (a prefix no word starts with kills every later position without a single probe).
     *
     * The result is bit-identical to the Phase-C probe — [probeLowerBound] mirrors [lowerBound]
     * comparison-for-comparison — and the pinned Phase-C recovery/precision counts prove it.
     */
    private fun probeAndScanVariant(position: Int, variantBytes: ByteArray, variantLength: Int) {
        if (fuzzyOverBudget) return
        val rangeStart = probeRangeStart[position]
        val rangeEnd = probeRangeEnd[position]
        if (rangeStart >= rangeEnd) return
        fuzzyProbesUsed++
        val probe = probeLowerBound(variantBytes, variantLength, rangeStart, rangeEnd)
        if (probe >= rangeEnd) return
        val wordLength = decodeWordInto(probe, probeScratch)
        if (wordLength < variantLength) return
        for (offset in 0 until variantLength) {
            if (unsigned(probeScratch[offset]) != (variantBytes[offset].toInt() and 0xff)) return
        }
        if (fuzzyVariantsUsed >= MAX_FUZZY_VARIANTS) {
            fuzzyOverBudget = true
            return
        }
        fuzzyVariantsUsed++
        scanVariantBlock(variantBytes, variantLength)
    }

    /**
     * Phase C2: the per-position probe ranges — probeRange[p] holds the entry range of words
     * starting with the typed prefix's first p code points (p = 0 is the whole dictionary),
     * computed once per class-#4 lookup by incremental narrowing (each range is searched within
     * its predecessor, so the whole chain costs one descent's worth of warm steps). Once a range
     * is empty every later range is empty too — the loop just propagates it.
     */
    private fun computeProbeRanges(codePointCount: Int) {
        probeRangeStart[0] = 0
        probeRangeEnd[0] = entryCount
        var byteOffset = 0
        for (position in 1 until codePointCount) {
            var start = probeRangeStart[position - 1]
            var end = probeRangeEnd[position - 1]
            if (start < end) {
                byteOffset += when (unsigned(exactScratch[byteOffset])) {
                    in 0x00..0x7f -> 1
                    in 0xc2..0xdf -> 2
                    in 0xe0..0xef -> 3
                    else -> 4
                }
                start = probeLowerBound(exactScratch, byteOffset, start, end)
                end = probeUpperBound(exactScratch, byteOffset, start, end)
            }
            probeRangeStart[position] = start
            probeRangeEnd[position] = end
        }
    }

    /**
     * [lowerBound]'s exact twin over a no-cache comparator: the first entry in [low0, high0) not
     * smaller than the query in whole-word-then-length order. Only the probe path uses it.
     */
    private fun probeLowerBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        var low = low0
        var high = high0
        while (low < high) {
            val middle = (low + high) ushr 1
            if (probeCompareWholeWordToVariant(middle, query, queryLength) < 0) low = middle + 1
            else high = middle
        }
        return low
    }

    /** [upperBound]'s exact twin over the no-cache prefix comparator. Only the probe path. */
    private fun probeUpperBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        var low = low0
        var high = high0
        while (low < high) {
            val middle = (low + high) ushr 1
            if (probeCompareWordToPrefixBlock(middle, query, queryLength) <= 0) low = middle + 1
            else high = middle
        }
        return low
    }

    /** [compareWholeWordToPrefix] computed without the block cache: decode, then compare. */
    private fun probeCompareWholeWordToVariant(index: Int, query: ByteArray, queryLength: Int): Int {
        val wordLength = decodeWordInto(index, probeScratch)
        val shared = minOf(wordLength, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(probeScratch[offset]) - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return wordLength - queryLength
    }

    /** [compareWordToPrefixBlock] computed without the block cache (0 iff the word starts with the query). */
    private fun probeCompareWordToPrefixBlock(index: Int, query: ByteArray, queryLength: Int): Int {
        val wordLength = decodeWordInto(index, probeScratch)
        val shared = minOf(wordLength, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(probeScratch[offset]) - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return if (wordLength < queryLength) -1 else 0
    }

    /**
     * The class-#5 driver (ROADMAP-P4 P6, docs/ROADMAP-P4.md): full TWO-substitution recovery,
     * enumerated by chains instead of the dead (38n)^2 naive space.
     *
     * VERDICT (2026-09-23): NO SHIP — the class is correct but unreachable by any policy: the
     * G1 recovery ceiling itself sits below the gate (see the calibration's diagnostic), and the
     * chained enumeration below trips the probe budget on 73 % of firing rows. Kept unwired as
     * documented evidence; do not enable without a fundamentally cheaper enumeration AND a
     * renegotiated gate.
     *
     * Per position i, every substitution letter x != typed[i] gets ONE seed probe — the
     * single-substitution prefix `typed[0..i) + x` inside the class-#4 narrowing range
     * (probeRange[i], so a prefix no word starts with kills the whole position for free). A
     * surviving seed is extended letter by letter (one narrowed lowerBound-startsWith probe per
     * extension): the chain dies the moment its prefix leaves the dictionary, and dead chains
     * are never re-probed. At every live through-j prefix, the SECOND substitution at j is
     * enumerated two ways by range width: a bounded walk of the seed range reads its own
     * continuation letters off the first [EDIT2_CONTINUATION_WALK_LIMIT] entries and pays at
     * most one cheap scan per letter; a range wider than that pays 37 full-variant probes. Every
     * emitted full variant is scanned by [scanVariantBlock] with block-first-word bounds, tagged
     * with the class and its plausibility (how many of its two edits are attested confusion
     * pairs in the layout's long-press map — the key that puts `сәләм` over `салым` for `сэлэм`).
     *
     * Every probe is one of the block-first-word bounds above (~14 direct reads + a bounded
     * in-block walk — the C2 decode-per-step probe would be ~4x more expensive at this scale);
     * [edit2ProbesUsed] counts them and [MAX_EDIT2_PROBES] is the fail-closed trip that drops
     * the level whole. Nothing here touches the shared block cache, and nothing allocates:
     * chains live in [edit2ChainScratch], variants in [variantScratch], next-letter sets in
     * [edit2NextLetters].
     */
    private fun collectTwoSubstitutionVariants(
        table: KeyNeighborTable,
        prefixLength: Int,
        codePointCount: Int,
    ) {
        edit2ProbesUsed = 0
        edit2ScansUsed = 0
        // Code points of the typed prefix (for the substitution enumeration and the plausibility
        // map lookups) and the byte offset of each code-point position (for scratch building).
        var byteOffset = 0
        var codePointAt = 0
        while (codePointAt < codePointCount) {
            edit2ByteStart[codePointAt] = byteOffset
            val lead = unsigned(exactScratch[byteOffset])
            val width: Int
            var codePoint: Int
            when {
                lead <= 0x7f -> {
                    width = 1
                    codePoint = lead
                }
                lead in 0xc2..0xdf -> {
                    width = 2
                    codePoint = lead and 0x1f
                }
                lead in 0xe0..0xef -> {
                    width = 3
                    codePoint = lead and 0x0f
                }
                else -> {
                    width = 4
                    codePoint = lead and 0x07
                }
            }
            for (offset in 1 until width) {
                codePoint = (codePoint shl 6) or (unsigned(exactScratch[byteOffset + offset]) and 0x3f)
            }
            codePointScratch[codePointAt] = codePoint
            byteOffset += width
            codePointAt++
        }
        computeProbeRanges(codePointCount)
        val alphabet = table.nodes

        for (position in 0 until codePointCount - 1) {
            if (fuzzyOverBudget) return
            val rangeStart = probeRangeStart[position]
            val rangeEnd = probeRangeEnd[position]
            if (rangeStart >= rangeEnd) continue
            for (letter in alphabet) {
                if (letter == codePointScratch[position]) continue
                // Seed = typed[0..position) + letter, probed inside the narrowed range.
                val seedLength = writeSeed(position, letter)
                if (!countProbeAndCheckBudget()) return
                var chainStart = edit2LowerBoundStartsWith(edit2ChainScratch, seedLength, rangeStart, rangeEnd)
                if (chainStart < 0) continue
                // The chain is alive through the seed (length position+1). Stage-B it, then
                // extend letter by letter until it dies or runs out of positions. The chain's
                // end stays the (loose) range end — every span used here is a superset-safe
                // binary-search bound, never a correctness input.
                var secondPosition = position + 1
                var chainLength = seedLength
                while (secondPosition < codePointCount) {
                    emitSecondSubstitutions(
                        table, position, letter, secondPosition, chainLength, chainStart, rangeEnd,
                        prefixLength,
                    )
                    if (fuzzyOverBudget) return
                    // Extend the chain with the typed letter for the next stage B.
                    val width = appendTyped(chainLength, secondPosition)
                    chainLength += width
                    if (!countProbeAndCheckBudget()) return
                    chainStart = edit2LowerBoundStartsWith(edit2ChainScratch, chainLength, chainStart, rangeEnd)
                    if (chainStart < 0) break
                    secondPosition++
                }
            }
        }
    }

    /**
     * The second substitution of one seed chain at one position. Walk the seed range's own
     * entries (prefix-checked piecewise, at most [EDIT2_CONTINUATION_WALK_LIMIT] of them) and
     * collect its continuation letters; when the range proves wide, fall back to 37 full-variant
     * probes. Both modes feed [emitTwoSubstitutionVariant].
     */
    private fun emitSecondSubstitutions(
        table: KeyNeighborTable,
        firstPosition: Int,
        firstLetter: Int,
        secondPosition: Int,
        chainLength: Int,
        chainStart: Int,
        chainEnd: Int,
        prefixLength: Int,
    ) {
        var nextCount = 0
        var wide = false
        var visited = 0
        walkEntries(chainStart, chainEnd) { firstStart, prefixLen, suffixStart, suffixLen ->
            if (compareEntryToQueryPrefix(
                    firstStart, prefixLen, suffixStart, suffixLen,
                    edit2ChainScratch, chainLength,
                ) != 0
            ) {
                // The seed range is over — the rest of the entries do not start with the chain.
                return@walkEntries true
            }
            fuzzyVisited++
            if (fuzzyVisited > MAX_FUZZY_VISITED) {
                fuzzyOverBudget = true
                return@walkEntries true
            }
            visited++
            if (visited > EDIT2_CONTINUATION_WALK_LIMIT) {
                wide = true
                return@walkEntries true
            }
            // The entry's remainder past the chain prefix (the entry is verified to start with
            // it) can SPAN the front-coded prefix/suffix boundary, so both pieces go in: the
            // letter at the second-substitution position, or 0 when the entry IS the chain.
            val next = if (prefixLen >= chainLength) {
                firstCodePointOfRemainder(
                    firstStart + chainLength, prefixLen - chainLength,
                    suffixStart, suffixLen,
                )
            } else {
                firstCodePointOfRemainder(
                    suffixStart + (chainLength - prefixLen),
                    suffixLen - (chainLength - prefixLen),
                    0, 0,
                )
            }
            if (next > 0 && next != codePointScratch[secondPosition]) {
                var present = false
                for (slot in 0 until nextCount) {
                    if (edit2NextLetters[slot] == next) {
                        present = true
                        break
                    }
                }
                if (!present && nextCount < edit2NextLetters.size) {
                    edit2NextLetters[nextCount++] = next
                }
            }
            false
        }
        if (fuzzyOverBudget) return
        if (wide) {
            // Wide seed range: pay one full-variant probe per second-substitution letter and scan
            // only the survivors (the probe IS the existence check, narrowed by the seed range).
            for (letter in table.nodes) {
                if (letter == codePointScratch[secondPosition]) continue
                val variantLength = writeVariant(secondPosition, letter, chainLength, prefixLength)
                if (!countProbeAndCheckBudget()) return
                val landing = edit2LowerBoundStartsWith(variantScratch, variantLength, chainStart, chainEnd)
                if (landing < 0) continue
                val end = edit2UpperBound(variantScratch, variantLength, landing, chainEnd)
                emitTwoSubstitutionVariant(
                    firstPosition, firstLetter, secondPosition, letter, variantLength, landing, end,
                )
                if (fuzzyOverBudget) return
            }
        } else {
            for (slot in 0 until nextCount) {
                val variantLength = writeVariant(secondPosition, edit2NextLetters[slot], chainLength, prefixLength)
                if (!countProbeAndCheckBudget(2)) return
                val start = edit2LowerBound(variantScratch, variantLength, 0, entryCount)
                val end = edit2UpperBound(variantScratch, variantLength, start, entryCount)
                emitTwoSubstitutionVariant(
                    firstPosition, firstLetter, secondPosition, edit2NextLetters[slot],
                    variantLength, start, end,
                )
                if (fuzzyOverBudget) return
            }
        }
    }

    /**
     * One full two-substitution variant, pre-built and pre-bounded: tag it with its plausibility
     * (attested-confusion edit count, from the layout's long-press map) and scan its block — the
     * shared variant budget is checked exactly like the other classes (fail-closed).
     */
    private fun emitTwoSubstitutionVariant(
        firstPosition: Int,
        firstLetter: Int,
        secondPosition: Int,
        secondLetter: Int,
        variantLength: Int,
        start: Int,
        end: Int = -1,
    ) {
        if (start < 0) return
        fuzzyCurrentPlausibility =
            (if (isConfusionPair(codePointScratch[firstPosition], firstLetter)) 1 else 0) +
            (if (isConfusionPair(codePointScratch[secondPosition], secondLetter)) 1 else 0)
        edit2ScansUsed++
        if (fuzzyVariantsUsed + edit2ScansUsed > MAX_FUZZY_VARIANTS) {
            fuzzyOverBudget = true
            return
        }
        scanVariantBlock(variantScratch, variantLength, start, end)
    }

    /** True when [b] is a long-press partner of [a] in the layout's symmetrized map. */
    private fun isConfusionPair(a: Int, b: Int): Boolean {
        val partners = neighborTable?.longPressPartnersOf(a) ?: return false
        return java.util.Arrays.binarySearch(partners, b) >= 0
    }

    /** The seed prefix `typed[0..position) + letter` into [edit2ChainScratch]; returns bytes. */
    private fun writeSeed(position: Int, letter: Int): Int {
        val start = edit2ByteStart[position]
        for (offset in 0 until start) edit2ChainScratch[offset] = exactScratch[offset]
        return start + writeUtf8(letter, edit2ChainScratch, start)
    }

    /** The full variant `chain + letter + typed[secondPosition+1..n)`; returns bytes. */
    private fun writeVariant(
        secondPosition: Int,
        letter: Int,
        chainLength: Int,
        prefixLength: Int,
    ): Int {
        for (offset in 0 until chainLength) variantScratch[offset] = edit2ChainScratch[offset]
        var at = chainLength + writeUtf8(letter, variantScratch, chainLength)
        val tailStart = edit2ByteStart[secondPosition] + typedCodePointByteWidth(secondPosition)
        for (offset in tailStart until prefixLength) {
            variantScratch[at++] = exactScratch[offset]
        }
        return at
    }

    /** Appends the typed letter at [position] to [edit2ChainScratch]; returns its byte width. */
    private fun appendTyped(chainLength: Int, position: Int): Int {
        val start = edit2ByteStart[position]
        val width = typedCodePointByteWidth(position)
        for (offset in start until start + width) {
            edit2ChainScratch[chainLength + offset - start] = exactScratch[offset]
        }
        return width
    }

    /** Byte width of the typed code point at [position] (1-4, from its UTF-8 lead byte). */
    private fun typedCodePointByteWidth(position: Int): Int =
        when (unsigned(exactScratch[edit2ByteStart[position]])) {
            in 0x00..0x7f -> 1
            in 0xc2..0xdf -> 2
            in 0xe0..0xef -> 3
            else -> 4
        }

    /** One UTF-8 code point into [out] at [at]; returns the bytes written. */
    private fun writeUtf8(codePoint: Int, out: ByteArray, at: Int): Int = when {
        codePoint <= 0x7f -> {
            out[at] = codePoint.toByte()
            1
        }
        codePoint <= 0x7ff -> {
            out[at] = (0xc0 or (codePoint shr 6)).toByte()
            out[at + 1] = (0x80 or (codePoint and 0x3f)).toByte()
            2
        }
        codePoint <= 0xffff -> {
            out[at] = (0xe0 or (codePoint shr 12)).toByte()
            out[at + 1] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
            out[at + 2] = (0x80 or (codePoint and 0x3f)).toByte()
            3
        }
        else -> {
            out[at] = (0xf0 or (codePoint shr 18)).toByte()
            out[at + 1] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
            out[at + 2] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
            out[at + 3] = (0x80 or (codePoint and 0x3f)).toByte()
            4
        }
    }

    /** The first code point of a front-coded entry's remainder past the chain query, or 0 if empty. */
    private fun firstCodePointOfRemainder(
        remFirstStart: Int,
        remFirstLength: Int,
        remSecondStart: Int,
        remSecondLength: Int,
    ): Int {
        val start: Int
        val length: Int
        if (remFirstLength > 0) {
            start = remFirstStart
            length = remFirstLength
        } else if (remSecondLength > 0) {
            start = remSecondStart
            length = remSecondLength
        } else {
            return 0
        }
        val lead = unsigned(bytes.get(start))
        var codePoint: Int
        val width: Int
        when {
            lead <= 0x7f -> {
                width = 1
                codePoint = lead
            }
            lead in 0xc2..0xdf -> {
                width = 2
                codePoint = lead and 0x1f
            }
            lead in 0xe0..0xef -> {
                width = 3
                codePoint = lead and 0x0f
            }
            else -> {
                width = 4
                codePoint = lead and 0x07
            }
        }
        if (width > length) return 0
        for (offset in 1 until width) {
            codePoint = (codePoint shl 6) or (unsigned(bytes.get(start + offset)) and 0x3f)
        }
        return codePoint
    }

    // --- Class-#5 block-first-word probe machinery (P6) -----------------------------------------
    //
    // The full two-substitution enumeration needs hundreds of probes per lookup, and the C2
    // no-cache probe (~17 front-coded block decodes per binary search) is too expensive at that
    // scale (the POCO pays ~29 us for it). The class-#5 probe instead searches the BLOCK INDEX by
    // first words — ~14 direct byte reads, no decode — and then walks the single landing block
    // (<= 8 entries) with a piecewise comparator. Results are entry-identical to the
    // decode-based probe (the calibration pins prove it); the per-probe cost is ~4x lower.

    /** The last block whose first word is <= [query], or -1 when the query precedes block 0. */
    private fun edit2BlockLowerBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        var low = low0 / TdictFormat.BLOCK_SIZE
        var high = minOf(blockCount, (high0 + TdictFormat.BLOCK_SIZE - 1) / TdictFormat.BLOCK_SIZE)
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareBlockFirstWordToQuery(middle, query, queryLength) <= 0) low = middle + 1
            else high = middle
        }
        return low - 1
    }

    /** The first block whose first word does NOT start with [query] (blockCount when none). */
    private fun edit2BlockUpperBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        var low = low0 / TdictFormat.BLOCK_SIZE
        var high = minOf(blockCount, (high0 + TdictFormat.BLOCK_SIZE - 1) / TdictFormat.BLOCK_SIZE)
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareBlockFirstWordToQueryPrefix(middle, query, queryLength) <= 0) low = middle + 1
            else high = middle
        }
        return low
    }

    /** A block's first word vs [query] in whole-word-then-length order, direct reads, no decode. */
    private fun compareBlockFirstWordToQuery(block: Int, query: ByteArray, queryLength: Int): Int {
        var cursor = blockOffset(block)
        val firstLength = unsigned(bytes.get(cursor))
        cursor++
        val shared = minOf(firstLength, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(bytes.get(cursor + offset)) - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return firstLength - queryLength
    }

    /** A block's first word vs [query] in prefix order (0 iff the first word starts with it). */
    private fun compareBlockFirstWordToQueryPrefix(block: Int, query: ByteArray, queryLength: Int): Int {
        var cursor = blockOffset(block)
        val firstLength = unsigned(bytes.get(cursor))
        cursor++
        val shared = minOf(firstLength, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(bytes.get(cursor + offset)) - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return if (firstLength < queryLength) -1 else 0
    }

    /**
     * Walks entries [fromIndex, toIndex) sequentially, decoding each crossed block on the fly,
     * and reports every entry's front-coded pieces to [onEntry]
     * (firstStart/prefixLength/suffixStart/suffixLength of the mapped buffer — the prefix piece
     * refers to the block's first word, the suffix piece to the entry's own). Returns the first
     * index where [onEntry] answers true, or [toIndex] when nothing stopped the walk.
     */
    private inline fun walkEntries(
        fromIndex: Int,
        toIndex: Int,
        onEntry: (Int, Int, Int, Int) -> Boolean,
    ): Int {
        // Decoding always starts at fromIndex's BLOCK start (front-coding is sequential inside a
        // block); entries before fromIndex are decoded but skipped — reporting a shifted entry
        // against a query is exactly the bug this walk exists to avoid.
        var index = (fromIndex / TdictFormat.BLOCK_SIZE) * TdictFormat.BLOCK_SIZE
        var currentBlock = -1
        var cursor = 0
        var firstStart = 0
        var prefixLength = 0
        var suffixStart = 0
        var entryLength = 0
        while (index < toIndex) {
            val block = index / TdictFormat.BLOCK_SIZE
            if (block != currentBlock) {
                currentBlock = block
                cursor = blockOffset(block)
                val firstLength = unsigned(bytes.get(cursor))
                cursor++
                firstStart = cursor
                cursor += firstLength
                prefixLength = 0
                suffixStart = firstStart
                entryLength = firstLength
            } else {
                var packed = unsigned(bytes.get(cursor))
                cursor++
                if (packed >= 0x80) {
                    decodeVarint(cursor - 1)
                    packed = varintValue
                    cursor = varintNext
                }
                prefixLength = packed
                val suffixLength = unsigned(bytes.get(cursor))
                cursor++
                suffixStart = cursor
                cursor += suffixLength
                entryLength = prefixLength + suffixLength
            }
            if (index >= fromIndex &&
                onEntry(firstStart, prefixLength, suffixStart, entryLength - prefixLength)
            ) {
                return index
            }
            index++
        }
        return toIndex
    }

    /**
     * The entry-level lowerBound of [query] within [low0, high0): the first entry not smaller
     * than [query] in whole-word-then-length order. Block search, then one linear walk.
     */
    private fun edit2LowerBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        if (low0 >= high0) return high0
        val block = edit2BlockLowerBound(query, queryLength, low0, high0)
        if (block < 0) return low0
        return walkEntries(maxOf(low0, block * TdictFormat.BLOCK_SIZE), high0) {
            firstStart, prefixLength, suffixStart, suffixLength ->
            compareEntryToQuery(
                firstStart, prefixLength, suffixStart, suffixLength, query, queryLength,
            ) >= 0
        }
    }

    /**
     * The entry-level upperBound of [query] within [low0, high0): the first entry NOT starting
     * with [query]. The boundary can live in the previous block's tail, so the walk starts there.
     */
    private fun edit2UpperBound(query: ByteArray, queryLength: Int, low0: Int, high0: Int): Int {
        if (low0 >= high0) return high0
        val block = edit2BlockUpperBound(query, queryLength, low0, high0)
        // Every block up to blockCount-1 starts with the query: the boundary still sits in the
        // LAST block's tail, so walk it instead of returning high0 blindly.
        val fromIndex = maxOf(
            low0,
            if (block >= blockCount) {
                if (blockCount == 0) return high0
                (blockCount - 1) * TdictFormat.BLOCK_SIZE
            } else if (block > 0) {
                block * TdictFormat.BLOCK_SIZE - TdictFormat.BLOCK_SIZE
            } else {
                block * TdictFormat.BLOCK_SIZE
            },
        )
        return walkEntries(fromIndex, high0) { firstStart, prefixLength, suffixStart, suffixLength ->
            compareEntryToQueryPrefix(
                firstStart, prefixLength, suffixStart, suffixLength, query, queryLength,
            ) > 0
        }
    }

    /**
     * Piecewise 3-way compare of a front-coded entry (prefix of the block's first word + suffix)
     * against [query], whole-word-then-length order, direct reads, no materialization.
     */
    private fun compareEntryToQuery(
        firstStart: Int,
        prefixLength: Int,
        suffixStart: Int,
        suffixLength: Int,
        query: ByteArray,
        queryLength: Int,
    ): Int {
        val wordLength = prefixLength + suffixLength
        val shared = minOf(wordLength, queryLength)
        for (offset in 0 until shared) {
            val value: Int
            value = if (offset < prefixLength) {
                unsigned(bytes.get(firstStart + offset))
            } else {
                unsigned(bytes.get(suffixStart + offset - prefixLength))
            }
            val difference = value - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return wordLength - queryLength
    }

    /**
     * Piecewise compare of a front-coded entry against [query] in PREFIX order: 0 when the entry
     * starts with the query, the byte difference when they diverge, -1 when the entry is shorter
     * than the query (a proper prefix of it is never a match).
     */
    private fun compareEntryToQueryPrefix(
        firstStart: Int,
        prefixLength: Int,
        suffixStart: Int,
        suffixLength: Int,
        query: ByteArray,
        queryLength: Int,
    ): Int {
        val wordLength = prefixLength + suffixLength
        val shared = minOf(wordLength, queryLength)
        for (offset in 0 until shared) {
            val value = if (offset < prefixLength) {
                unsigned(bytes.get(firstStart + offset))
            } else {
                unsigned(bytes.get(suffixStart + offset - prefixLength))
            }
            val difference = value - (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return if (wordLength < queryLength) -1 else 0
    }

    /**
     * The landing entry of [query] inside [low, high) when it starts with the query, else -1 —
     * one block-first-word lowerBound plus one piecewise compare (the class-#5 seed/extension
     * probe).
     */
    private fun edit2LowerBoundStartsWith(query: ByteArray, queryLength: Int, low: Int, high: Int): Int {
        val landing = edit2LowerBound(query, queryLength, low, high)
        if (landing >= high) return -1
        val startsWith = walkEntries(landing, landing + 1) { firstStart, prefixLength, suffixStart, suffixLength ->
            compareEntryToQueryPrefix(
                firstStart, prefixLength, suffixStart, suffixLength, query, queryLength,
            ) == 0
        } == landing
        return if (startsWith) landing else -1
    }

    /** Counts [count] edit-#5 probes; false when the fail-closed budget trips. */
    private fun countProbeAndCheckBudget(count: Int = 1): Boolean {
        if (edit2ProbesUsed + count > MAX_EDIT2_PROBES) {
            fuzzyOverBudget = true
            return false
        }
        edit2ProbesUsed += count
        return true
    }

    /** Scans one variant's dictionary block, ranking its candidates into [fuzzyIndices]. */
    private fun scanVariantBlock(
        variantBytes: ByteArray,
        variantLength: Int,
        startOverride: Int = -1,
        endOverride: Int = -1,
    ) {
        if (fuzzyOverBudget) return
        // The class-#5 driver passes the exact pre-probed prefix range as overrides (the cheap
        // block-first-word bounds); every other caller leaves them computed as before.
        val start = if (startOverride >= 0) startOverride else lowerBound(variantBytes, variantLength, 0)
        val end = if (endOverride >= 0) endOverride else upperBound(variantBytes, variantLength, start)
        // The exact-word exclusion compares against the TYPED word, not the variant that
        // selected the range — hence exactScratch/fuzzyPrefixLength here.
        scanBlockRange(
            start, end, exactScratch, fuzzyPrefixLength,
            onEntry = { index, equalsQuery, _, remFirstLength, _, remSecondLength ->
                fuzzyVisited++
                if (fuzzyVisited > MAX_FUZZY_VISITED) {
                    fuzzyOverBudget = true
                    return@scanBlockRange SCAN_ABORT
                }
                // Exact-word exclusion applies on both levels: never suggest the typed word itself.
                // A word is de-duplicated by dictionary index so it can never occupy two cells. Because
                // classes run in order (#1 … #5), the first class to reach a word keeps it,
                // which is also its best (lowest) class — consistent with the class-first ranking.
                if (equalsQuery ||
                    containsIndex(rankedIndices, fuzzyExactCount, index) ||
                    containsIndex(fuzzyIndices, fuzzyCount, index)
                ) {
                    return@scanBlockRange SCAN_SKIP
                }
                // TT-TYPO-NEXT Phase B: the same-length bonus. A candidate whose remainder past the
                // variant is empty IS the variant itself — and a substitution variant has exactly
                // the typed prefix's code-point length — so an empty remainder marks precisely the
                // candidates whose length equals the typed prefix length, with no counting on the
                // hot path. The bonus only applies when the engine's policy enables it.
                if (fuzzyPolicy.sameLengthBonus && remFirstLength == 0 && remSecondLength == 0) {
                    SCAN_TAKE_SAME_LENGTH
                } else {
                    SCAN_TAKE
                }
            },
            onFrequency = { index, frequency, verdict ->
                fuzzyCount = insertRanked(
                    fuzzyIndices, fuzzyFrequencies, fuzzyClasses, fuzzyCount, fuzzyRemaining,
                    index, frequency,
                    fuzzyRankKey(fuzzyCurrentClass, verdict == SCAN_TAKE_SAME_LENGTH),
                )
            },
        )
    }

    /**
     * The ranking key of a fuzzy candidate: edit class first (ascending), then — only under
     * [FuzzyEditPolicy.sameLengthBonus] — a same-length candidate before its own continuations,
     * then the class-#5 plausibility (attested-confusion edit count, descending — zero for every
     * other class), then the frozen (frequency descending, code-point ascending) tie-break inside
     * [insertRanked]. Packed as `class * 32 - bonus * 16 - plausibility * 2` so a single int
     * comparison implements all of them in order; with plausibility zero this is exactly the
     * pre-P6 class-then-bonus-then-frequency order (the packing is rescaled, never reordered).
     */
    private fun fuzzyRankKey(editClass: Int, sameLength: Boolean): Int =
        editClass * 32 - (if (sameLength) 16 else 0) - fuzzyCurrentPlausibility * 2

    /** Bounded insertion sort shared by both levels; returns the new count. */
    private fun insertRanked(
        indices: IntArray,
        frequencies: LongArray,
        classes: IntArray,
        count: Int,
        capacity: Int,
        candidateIndex: Int,
        candidateFrequency: Long,
        candidateClass: Int,
    ): Int {
        var insertion = count
        for (slot in 0 until count) {
            if (ranksBefore(
                    candidateClass, candidateIndex, candidateFrequency,
                    classes[slot], indices[slot], frequencies[slot],
                )
            ) {
                insertion = slot
                break
            }
        }
        if (insertion >= capacity) return count
        val newCount = minOf(capacity, count + 1)
        for (slot in newCount - 1 downTo insertion + 1) {
            indices[slot] = indices[slot - 1]
            frequencies[slot] = frequencies[slot - 1]
            classes[slot] = classes[slot - 1]
        }
        indices[insertion] = candidateIndex
        frequencies[insertion] = candidateFrequency
        classes[insertion] = candidateClass
        return newCount
    }

    private fun containsIndex(indices: IntArray, count: Int, value: Int): Boolean {
        for (slot in 0 until count) {
            if (indices[slot] == value) return true
        }
        return false
    }

    private fun lowerBound(query: ByteArray, queryLength: Int, from: Int): Int {
        var low = from
        var high = entryCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareWholeWordToPrefix(middle, query, queryLength) < 0) low = middle + 1
            else high = middle
        }
        return low
    }

    private fun upperBound(query: ByteArray, queryLength: Int, lowHint: Int): Int {
        var low = lowHint
        var high = entryCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareWordToPrefixBlock(middle, query, queryLength) <= 0) low = middle + 1
            else high = middle
        }
        return low
    }

    private fun compareWholeWordToPrefix(index: Int, query: ByteArray, queryLength: Int): Int {
        val start = cachedWordStart(index)
        val length = cachedWordEnd(index) - start
        val shared = minOf(length, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(blockWordBytes[start + offset]) -
                (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return length - queryLength
    }

    /** Words beginning with the query compare equal, which gives the exclusive range end. */
    private fun compareWordToPrefixBlock(index: Int, query: ByteArray, queryLength: Int): Int {
        val start = cachedWordStart(index)
        val length = cachedWordEnd(index) - start
        val shared = minOf(length, queryLength)
        for (offset in 0 until shared) {
            val difference = unsigned(blockWordBytes[start + offset]) -
                (query[offset].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return if (length < queryLength) -1 else 0
    }

    private fun wordEquals(index: Int, query: ByteArray, queryLength: Int): Boolean {
        val start = cachedWordStart(index)
        if (cachedWordEnd(index) - start != queryLength) return false
        for (offset in 0 until queryLength) {
            if (unsigned(blockWordBytes[start + offset]) !=
                (query[offset].toInt() and 0xff)
            ) {
                return false
            }
        }
        return true
    }

    private fun ranksBefore(
        candidateClass: Int,
        candidateIndex: Int,
        candidateFrequency: Long,
        rankedClass: Int,
        rankedIndex: Int,
        rankedFrequency: Long,
    ): Boolean = when {
        // Ranking key first (ascending). Exact candidates all share EDIT_CLASS_EXACT, so this key
        // is a tie among them and their frozen order is untouched. Fuzzy candidates carry the
        // packed key of [fuzzyRankKey]: edit class dominant (#1 … #5), with the same-length bonus
        // and the class-#5 plausibility ordering inside a class when the policy enables them.
        candidateClass != rankedClass -> candidateClass < rankedClass
        candidateFrequency != rankedFrequency -> candidateFrequency > rankedFrequency
        else -> compareWords(candidateIndex, rankedIndex) < 0
    }

    private fun compareWords(firstIndex: Int, secondIndex: Int): Int {
        val firstLength = decodeWordInto(firstIndex, wordScratchA)
        val secondLength = decodeWordInto(secondIndex, wordScratchB)
        val shared = minOf(firstLength, secondLength)
        for (offset in 0 until shared) {
            val difference = unsigned(wordScratchA[offset]) - unsigned(wordScratchB[offset])
            if (difference != 0) return difference
        }
        return firstLength - secondLength
    }

    private fun decodeWord(index: Int): String {
        val start = cachedWordStart(index)
        return String(blockWordBytes, start, cachedWordEnd(index) - start, Charsets.UTF_8)
    }

    // --- BigramDictionary (SIZE-2): the schema-3 bigram table resolves its head/success indices
    // through exactly these two reads, on the same serialized worker as lookup() — so the block
    // cache and the scratch discipline above apply unchanged.
    override val rawSha256: String
        get() = identity.rawSha256

    /** Exact-word index lookup: one [lowerBound] plus an equality check, no prefix semantics. */
    override fun indexOfWord(query: ByteArray, queryLength: Int): Int {
        val candidate = lowerBound(query, queryLength, 0)
        if (candidate >= entryCount) return -1
        return if (wordEquals(candidate, query, queryLength)) candidate else -1
    }

    /**
     * P3 (docs/TT-SUGGESTIONS.md): exact whole-word frequency of [query], or 0 when the dictionary
     * does not contain it (schema 2 stores strictly positive frequencies, so 0 is a safe absent
     * sentinel). One exact binary search plus one cached-block read — no word is materialized, so
     * this byte-level form allocates nothing and shares the lookup path's worker confinement.
     */
    fun frequencyOf(query: ByteArray, queryLength: Int): Long {
        if (queryLength == 0 || queryLength > TdictFormat.MAX_WORD_BYTES) return 0L
        val entry = indexOfWord(query, queryLength)
        return if (entry < 0) 0L else frequencyAt(entry)
    }

    /**
     * The string form of [frequencyOf] — it encodes, so it belongs to bounded off-hot-path callers
     * (the P3 after-word forms), never to the per-keystroke scan.
     */
    override fun frequencyOf(word: String): Long {
        val bytes = word.toByteArray(Charsets.UTF_8)
        return frequencyOf(bytes, bytes.size)
    }

    /**
     * P1 of Phase 2 (docs/ROADMAP-P2.md): exact whole-word membership of [normalizedWord],
     * answered WITHOUT the block cache and without ANY shared scratch — every byte comes straight
     * from the read-only mapping into local state. That is what makes this the one read that is
     * safe to call from a thread that is not the lookup worker (the personal-bigram store's
     * worker, at pair-graduation time): the mapping is read-only, and plain [ByteBuffer.get] reads
     * need no happens-before of their own beyond the one the caller's `@Volatile` handoff already
     * provides.
     *
     * The cost is a cold binary search — about log2([entryCount]) front-coded decodes, each
     * walking at most one block — bounded and paid only at graduation, never per keystroke. The
     * comparator is the same unsigned-byte order [lowerBound] relies on, so the answer agrees
     * with the lookup path on every word.
     */
    fun containsWordCold(normalizedWord: String): Boolean {
        val query = normalizedWord.toByteArray(Charsets.UTF_8)
        if (query.isEmpty() || query.size > TdictFormat.MAX_WORD_BYTES) return false
        val scratch = ByteArray(TdictFormat.MAX_WORD_BYTES)
        var low = 0
        var high = entryCount
        while (low < high) {
            val mid = (low + high) ushr 1
            val length = decodeWordCold(mid, scratch)
            var order = 0
            val shared = minOf(length, query.size)
            for (offset in 0 until shared) {
                val difference = (scratch[offset].toInt() and 0xff) - (query[offset].toInt() and 0xff)
                if (difference != 0) {
                    order = difference
                    break
                }
            }
            if (order == 0) order = length - query.size
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid
                else -> return true
            }
        }
        return false
    }

    /**
     * P7-1 (docs/GLIDE-PLAN.md): cold enumeration of every entry with its frequency, for the
     * glide decoder's one-time word-index build. The walk is a single sequential pass over the
     * read-only mapping with LOCAL varint state — like [containsWordCold] it never touches the
     * worker-confined block cache or the shared [varintValue]/[varintNext], so it is safe to
     * call from the thread that builds the glide index (the engine worker at first glide), and
     * it never enters the per-keystroke lookup path or its budgets. One String per word is
     * materialized — build-time cost, paid once per dictionary.
     */
    internal fun forEachWordCold(visitor: ColdWordVisitor) {
        val wordBytes = ByteArray(TdictFormat.BLOCK_SIZE * TdictFormat.MAX_WORD_BYTES)
        val wordStarts = IntArray(TdictFormat.BLOCK_SIZE + 1)
        for (block in 0 until blockCount) {
            var cursor = blockOffset(block)
            val inBlock = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
            val firstLength = unsigned(bytes.get(cursor))
            cursor++
            val firstStart = cursor
            cursor += firstLength
            wordStarts[0] = 0
            for (offset in 0 until firstLength) wordBytes[offset] = bytes.get(firstStart + offset)
            wordStarts[1] = firstLength
            var writeAt = firstLength
            for (entry in 1 until inBlock) {
                var prefixLength = 0
                var shift = 0
                while (true) {
                    val byte = unsigned(bytes.get(cursor))
                    cursor++
                    prefixLength = prefixLength or ((byte and 0x7f) shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                }
                val suffixLength = unsigned(bytes.get(cursor))
                cursor++
                for (offset in 0 until prefixLength) {
                    wordBytes[writeAt + offset] = bytes.get(firstStart + offset)
                }
                for (offset in 0 until suffixLength) {
                    wordBytes[writeAt + prefixLength + offset] = bytes.get(cursor + offset)
                }
                cursor += suffixLength
                writeAt += prefixLength + suffixLength
                wordStarts[entry + 1] = writeAt
            }
            for (entry in 0 until inBlock) {
                var frequency = 0
                var shift = 0
                while (true) {
                    val byte = unsigned(bytes.get(cursor))
                    cursor++
                    frequency = frequency or ((byte and 0x7f) shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                }
                val start = wordStarts[entry]
                visitor.visit(
                    String(wordBytes, start, wordStarts[entry + 1] - start, Charsets.UTF_8),
                    frequency.toLong() and MAX_U32,
                )
            }
        }
    }

    /**
     * The cold-read twin of [decodeWordInto]: same front-coded walk, but the varint decode is
     * LOCAL — it never touches [varintValue]/[varintNext] — so concurrent lookup workers are not
     * raced. Used only by [containsWordCold].
     */
    private fun decodeWordCold(index: Int, scratch: ByteArray): Int {
        val block = index / TdictFormat.BLOCK_SIZE
        val position = index % TdictFormat.BLOCK_SIZE
        var cursor = blockOffset(block)
        val firstLength = unsigned(bytes.get(cursor))
        cursor++
        val firstStart = cursor
        if (position == 0) {
            for (offset in 0 until firstLength) scratch[offset] = bytes.get(firstStart + offset)
            return firstLength
        }
        cursor += firstLength
        var prefixLength = 0
        var suffixLength = 0
        for (entry in 1..position) {
            var value = 0
            var shift = 0
            while (true) {
                val byte = unsigned(bytes.get(cursor))
                cursor++
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            prefixLength = value
            suffixLength = unsigned(bytes.get(cursor))
            cursor++
            if (entry == position) {
                for (offset in 0 until prefixLength) {
                    scratch[offset] = bytes.get(firstStart + offset)
                }
                for (offset in 0 until suffixLength) {
                    scratch[prefixLength + offset] = bytes.get(cursor + offset)
                }
            }
            cursor += suffixLength
        }
        return prefixLength + suffixLength
    }

    /**
     * TT-NEXTWORD-FILL (docs/TT-NEXTWORD-FILL.md): the [count] most frequent words of the
     * dictionary, in the frozen ranking order (frequency descending, then code-point ascending —
     * UTF-8 byte order is code-point order, so the same comparator as the lookup path applies).
     *
     * ONE linear scan of the dictionary: blocks decode sequentially through the shared block cache
     * (each block decoded exactly once), the top-N selection state is two fixed primitive arrays,
     * and the tie-break compare reads both words through the no-cache [decodeWordInto] — so the
     * scan allocates nothing per entry and never touches the per-keystroke structures differently
     * than any other read. It runs at engine START (the fallback factory builds the pool there),
     * at most once per engine; it is never on the lookup path.
     */
    override fun topFrequentWords(count: Int): List<String> {
        require(count >= 0)
        if (count == 0) return emptyList()
        val topIndices = IntArray(count)
        val topFrequencies = LongArray(count)
        var size = 0
        for (index in 0 until entryCount) {
            val frequency = frequencyAt(index)
            var insertion = size
            for (slot in 0 until size) {
                if (frequency > topFrequencies[slot] ||
                    (frequency == topFrequencies[slot] && compareWords(index, topIndices[slot]) < 0)
                ) {
                    insertion = slot
                    break
                }
            }
            if (insertion >= count) continue
            val newSize = minOf(count, size + 1)
            for (slot in newSize - 1 downTo insertion + 1) {
                topIndices[slot] = topIndices[slot - 1]
                topFrequencies[slot] = topFrequencies[slot - 1]
            }
            topIndices[insertion] = index
            topFrequencies[insertion] = frequency
            size = newSize
        }
        return (0 until size).map { decodeWord(topIndices[it]) }
    }

    override fun wordAt(index: Int): String = decodeWord(index)

    /** Start of word [index] inside [blockWordBytes]; decodes its block if it is not cached. */
    private fun cachedWordStart(index: Int): Int {
        ensureBlock(index / TdictFormat.BLOCK_SIZE)
        return blockWordStarts[index % TdictFormat.BLOCK_SIZE]
    }

    /** End of word [index] inside [blockWordBytes]; the block is cached by [cachedWordStart]. */
    private fun cachedWordEnd(index: Int): Int = blockWordStarts[index % TdictFormat.BLOCK_SIZE + 1]

    /** Decodes block [block] — words and frequencies — into the per-lookup block cache. */
    private fun ensureBlock(block: Int) {
        if (block == cachedBlock) return
        val count = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
        var cursor = blockOffset(block)
        val firstLength = unsigned(bytes.get(cursor))
        cursor++
        val firstStart = cursor
        cursor += firstLength
        blockWordStarts[0] = 0
        var writeAt = firstLength
        for (offset in 0 until firstLength) blockWordBytes[offset] = bytes.get(firstStart + offset)
        blockWordStarts[1] = writeAt
        for (entry in 1 until count) {
            // Inline varint fast path: a prefix length virtually always fits one byte.
            var prefixLength = unsigned(bytes.get(cursor))
            cursor++
            if (prefixLength >= 0x80) {
                decodeVarint(cursor - 1)
                prefixLength = varintValue
                cursor = varintNext
            }
            val suffixLength = unsigned(bytes.get(cursor))
            cursor++
            // The prefix refers to the block's FIRST word, which lies contiguously in the
            // mapped buffer — copying from there (not from the partially overwritten cache)
            // is what makes every entry of the block independently decodable.
            for (offset in 0 until prefixLength) {
                blockWordBytes[writeAt + offset] = bytes.get(firstStart + offset)
            }
            for (offset in 0 until suffixLength) {
                blockWordBytes[writeAt + prefixLength + offset] = bytes.get(cursor + offset)
            }
            cursor += suffixLength
            writeAt += prefixLength + suffixLength
            blockWordStarts[entry + 1] = writeAt
        }
        for (entry in 0 until count) {
            // Inline varint fast path; a varint holds a u32, interpreted unsigned (schema 1 parity).
            var frequency = unsigned(bytes.get(cursor))
            cursor++
            if (frequency >= 0x80) {
                decodeVarint(cursor - 1)
                frequency = varintValue
                cursor = varintNext
            }
            blockFrequencies[entry] = frequency.toLong() and MAX_U32
        }
        cachedBlock = block
    }

    /**
     * Streams the entry range [start, end) without materializing words: per in-range entry it
     * reports [onEntry] — (index, equalsQuery), computed piecewise against the mapped bytes, plus
     * the candidate's remainder past the query as two contiguous byte ranges into the mapped
     * buffer (P3's suffix-membership test consumes them without a copy) — and only for entries
     * where [onEntry] answered [SCAN_TAKE] or [SCAN_TAKE_STEM] it then reports [onFrequency] with
     * that verdict, still inside the same block visit. The function is `inline`, so neither
     * callback allocates, and the word bytes are never copied: a one-letter prefix scan pays a
     * couple of varint reads per entry instead of a full block decode.
     *
     * [onEntry] verdicts: [SCAN_SKIP] — not a candidate; [SCAN_TAKE] — candidate, report its
     * frequency via [onFrequency]; [SCAN_TAKE_STEM] — same, flagged as a same-stem candidate of
     * the P3 dual-track pass; [SCAN_TAKE_SAME_LENGTH] — same, flagged as a candidate whose length
     * equals the typed prefix length (the TT-TYPO-NEXT Phase-B same-length bonus; only the fuzzy
     * pass ever returns it); [SCAN_ABORT] — stop the whole scan immediately (the fuzzy budget
     * trip, after which the level is dropped in full and pending frequencies are never needed, so
     * [onFrequency] may then be skipped).
     *
     * The [insertRanked] call sequence is unchanged versus the schema-1 loop: [onFrequency] fires
     * in ascending index order within a block and blocks are visited in ascending order.
     */
    private inline fun scanBlockRange(
        start: Int,
        end: Int,
        query: ByteArray,
        queryLength: Int,
        onEntry: (Int, Boolean, Int, Int, Int, Int) -> Int,
        onFrequency: (Int, Long, Int) -> Unit,
    ) {
        var index = start
        while (index < end) {
            val block = index / TdictFormat.BLOCK_SIZE
            val inBlock = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
            val lastPosition = minOf(inBlock, end - block * TdictFormat.BLOCK_SIZE)
            var cursor = blockOffset(block)
            val firstLength = unsigned(bytes.get(cursor))
            cursor++
            val firstStart = cursor
            cursor += firstLength
            var takeMask = 0
            var stemMask = 0
            var sameLengthMask = 0
            var position = 0
            // Entry 0 is the block's first word; treating it as "prefix 0 + whole-word suffix"
            // keeps the loop body uniform.
            var prefixLength = 0
            var suffixStart = firstStart
            var wordLength = firstLength
            while (position < lastPosition) {
                var suffixLength = 0
                if (position > 0) {
                    prefixLength = unsigned(bytes.get(cursor))
                    cursor++
                    if (prefixLength >= 0x80) {
                        decodeVarint(cursor - 1)
                        prefixLength = varintValue
                        cursor = varintNext
                    }
                    suffixLength = unsigned(bytes.get(cursor))
                    cursor++
                    suffixStart = cursor
                    cursor += suffixLength
                    wordLength = prefixLength + suffixLength
                }
                val entryIndex = block * TdictFormat.BLOCK_SIZE + position
                if (entryIndex >= index) {
                    val equalsQuery = wordLength == queryLength &&
                        wordBytesEqual(
                            firstStart, prefixLength, suffixStart,
                            query, queryLength,
                        )
                    // The remainder past the query, piecewise against the mapped bytes (P3): the
                    // shared prefix may reach past the query's end, never the reverse — every word
                    // in [start, end) begins with the query.
                    val remFirstLength: Int
                    val remSecondStart: Int
                    if (prefixLength >= queryLength) {
                        remFirstLength = prefixLength - queryLength
                        remSecondStart = suffixStart
                    } else {
                        remFirstLength = 0
                        remSecondStart = suffixStart + (queryLength - prefixLength)
                    }
                    when (
                        onEntry(
                            entryIndex, equalsQuery,
                            firstStart + queryLength, remFirstLength,
                            remSecondStart, wordLength - queryLength - remFirstLength,
                        )
                    ) {
                        SCAN_TAKE -> takeMask = takeMask or (1 shl position)
                        SCAN_TAKE_STEM -> stemMask = stemMask or (1 shl position)
                        SCAN_TAKE_SAME_LENGTH -> sameLengthMask = sameLengthMask or (1 shl position)
                        SCAN_ABORT -> return
                    }
                }
                position++
            }
            val verdictMask = takeMask or stemMask or sameLengthMask
            if (verdictMask != 0) {
                // Finish walking the word section to reach the block's frequencies.
                while (position < inBlock) {
                    decodeVarint(cursor)
                    cursor = varintNext
                    cursor += 1 + unsigned(bytes.get(cursor))
                    position++
                }
                var frequencyPosition = 0
                while (frequencyPosition < inBlock) {
                    decodeVarint(cursor)
                    val frequency = varintValue.toLong() and MAX_U32
                    cursor = varintNext
                    val frequencyBit = 1 shl frequencyPosition
                    val verdict = when {
                        takeMask and frequencyBit != 0 -> SCAN_TAKE
                        stemMask and frequencyBit != 0 -> SCAN_TAKE_STEM
                        sameLengthMask and frequencyBit != 0 -> SCAN_TAKE_SAME_LENGTH
                        else -> SCAN_SKIP
                    }
                    if (verdict != SCAN_SKIP) {
                        onFrequency(
                            block * TdictFormat.BLOCK_SIZE + frequencyPosition,
                            frequency,
                            verdict,
                        )
                    }
                    frequencyPosition++
                }
            }
            index = block * TdictFormat.BLOCK_SIZE + lastPosition
        }
    }

    /**
     * Piecewise equality of a front-coded word (prefix of the block's first word + suffix at
     * [suffixStart]) against [query]. Caller guarantees prefix + suffix lengths equal
     * [queryLength] (checked before the call), so [prefixLength] never exceeds it.
     */
    private fun wordBytesEqual(
        firstStart: Int,
        prefixLength: Int,
        suffixStart: Int,
        query: ByteArray,
        queryLength: Int,
    ): Boolean {
        for (offset in 0 until prefixLength) {
            if (unsigned(bytes.get(firstStart + offset)) !=
                (query[offset].toInt() and 0xff)
            ) {
                return false
            }
        }
        for (offset in 0 until queryLength - prefixLength) {
            if (unsigned(bytes.get(suffixStart + offset)) !=
                (query[prefixLength + offset].toInt() and 0xff)
            ) {
                return false
            }
        }
        return true
    }

    /**
     * The varint frequency of word [index], served from the decoded-block cache.
     */
    private fun frequencyAt(index: Int): Long {
        ensureBlock(index / TdictFormat.BLOCK_SIZE)
        return blockFrequencies[index % TdictFormat.BLOCK_SIZE]
    }

    /**
     * Decodes word [index] of the front-coded block structure into [scratch] and returns its
     * byte length. Used only by [compareWords], whose two words may live in different blocks and
     * therefore cannot share the single-block cache; the binary-search and scan paths above all
     * go through the cache instead. A block stores its first word in full and every following
     * word as a varint shared-prefix length against that FIRST word plus a u8-length suffix, so
     * decoding word p walks p entries of the block (≤ [TdictFormat.BLOCK_SIZE] - 1) — and only
     * reads varints and skips suffix bytes on the way, copying nothing until the target word.
     * The prefix bytes are copied from the mapped buffer (where the block's first word always
     * lies contiguously), never from [scratch]: consecutive decodes into one scratch would
     * otherwise corrupt the prefix the next word refers to.
     */
    private fun decodeWordInto(index: Int, scratch: ByteArray): Int {
        val block = index / TdictFormat.BLOCK_SIZE
        val position = index % TdictFormat.BLOCK_SIZE
        var cursor = blockOffset(block)
        val firstLength = unsigned(bytes.get(cursor))
        cursor++
        val firstStart = cursor
        if (position == 0) {
            for (offset in 0 until firstLength) scratch[offset] = bytes.get(firstStart + offset)
            return firstLength
        }
        cursor += firstLength
        var prefixLength = 0
        var suffixLength = 0
        for (entry in 1..position) {
            decodeVarint(cursor)
            prefixLength = varintValue
            cursor = varintNext
            suffixLength = unsigned(bytes.get(cursor))
            cursor++
            if (entry == position) {
                for (offset in 0 until prefixLength) {
                    scratch[offset] = bytes.get(firstStart + offset)
                }
                for (offset in 0 until suffixLength) {
                    scratch[prefixLength + offset] = bytes.get(cursor + offset)
                }
            }
            cursor += suffixLength
        }
        return prefixLength + suffixLength
    }

    // Shared varint decode result, worker-confined exactly like the word scratches above.
    private var varintValue = 0
    private var varintNext = 0

    /** Decodes the base-128 varint at [offset] into [varintValue]/[varintNext]. */
    private fun decodeVarint(offset: Int) {
        var value = 0
        var shift = 0
        var cursor = offset
        while (true) {
            val byte = unsigned(bytes.get(cursor))
            cursor++
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        varintValue = value
        varintNext = cursor
    }

    private fun blockOffset(block: Int): Int = bytes.getInt(blockIndexOffset + block * U32_BYTES)

    companion object {
        private const val HEADER_SIZE = 72
        private const val CHECKSUM_ALGORITHM_SHA256 = 1
        private const val U32_BYTES = 4
        private const val MAX_RESULTS = 3
        internal const val MAX_PREFIX_BYTES = 128
        private const val MAX_U32 = 0xffff_ffffL

        /** "No dictionary entry"; entry indices are non-negative. */
        private const val NO_ENTRY = -1

        // Edit-class ranking keys, carried as plain ints. Exact candidates sort as EDIT_CLASS_EXACT
        // (a tie on the exact level, whose order is unchanged); within the fuzzy level the ascending
        // order is #1 long-press partner < #2 geometric neighbour < #3 transposition < #4 full
        // single substitution < #5 two substitutions, applied ahead of frequency by [ranksBefore] —
        // for fuzzy candidates through the packed rank key (see [fuzzyRankKey]), which keeps the
        // class dominant. The exact level always outranks the fuzzy level regardless of these
        // values, because exact and fuzzy candidates live in separate arrays and the exact ones
        // are merged first.
        private const val EDIT_CLASS_EXACT = 0
        internal const val EDIT_CLASS_LONG_PRESS = 1
        internal const val EDIT_CLASS_GEOMETRIC = 2
        internal const val EDIT_CLASS_TRANSPOSITION = 3
        internal const val EDIT_CLASS_SUBSTITUTION = 4

        // ROADMAP-P4 P6 (docs/ROADMAP-P4.md): two substitutions at two distinct positions.
        internal const val EDIT_CLASS_TWO_SUBSTITUTIONS = 5

        // P6: the class-#5 activation floor (mirrors MIN_SUBSTITUTION_PREFIX_CODE_POINTS), the
        // fail-closed probe budget for the chained enumeration (seed probes + chain extensions +
        // second-substitution probes + survivor scans — a trip drops the level whole), and the
        // continuation-walk limit: second-substitution letters of a seed range up to this many
        // entries are read off the range itself (a bounded walk) instead of probed one by one.
        private const val MIN_TWO_SUBST_PREFIX_CODE_POINTS = 4
        internal const val MAX_EDIT2_PROBES = 512
        private const val EDIT2_CONTINUATION_WALK_LIMIT = 64

        // Which edit classes reach an engine's fuzzy pass is no longer a global constant: since
        // TT-TYPO-NEXT Phase B (docs/TT-TYPO-NEXT.md) it is the per-engine [FuzzyEditPolicy]
        // injected through [open] — [FuzzyEditPolicy.DEFAULT] (class #1 only, no bonus) reproduces
        // exactly what the E3b verdict shipped (PROPOSALS.md, section "Контракт текста", line
        // "Итог, 2026-07-27", docs/archive/missions/DICTIONARY-E3.md), and [FuzzyEditPolicy.TATAR]
        // is the Tatar configuration shipped since Phase C2 (2026-09-20): class #1 plus the gated,
        // probe-first class #4 with the same-length bonus. The #2/#3 generators, the geometry map
        // and the instrumentation harness stay in the tree as infrastructure regardless of policy
        // and keep their direct tests.

        // Fuzzy pass. Extra headroom on the variant buffer covers a re-encode that is a few bytes
        // longer than the prefix; edit classes #1/#2 (single-letter substitution) and #3
        // (transposition) keep the code-point length identical in practice.
        private const val VARIANT_HEADROOM = 8

        // The fuzzy pass (all three E3b classes) needs at least three code points; the count is
        // taken off the UTF-8 lead bytes so a two-letter Cyrillic prefix (four bytes) is rejected.
        private const val MIN_FUZZY_PREFIX_CODE_POINTS = 3

        // Phase C (docs/TT-TYPO-NEXT.md): edit class #4 (full single substitution) additionally
        // requires the exact pass to have returned ZERO results (see collectFuzzy) and a settled
        // prefix of at least four code points — the boundary the D3 contract already uses for
        // "settled word" (AutocorrectPolicy.MIN_WORD_CODE_POINTS).
        private const val MIN_SUBSTITUTION_PREFIX_CODE_POINTS = 4

        // P3 same-stem boost: the typed prefix must be a complete word AND at least this many code
        // points (counted off the UTF-8 lead bytes, same as MIN_FUZZY_PREFIX_CODE_POINTS). Mirrors
        // the AutocorrectPolicy.MIN_WORD_CODE_POINTS convention of treating four letters as the
        // "settled word" boundary.
        private const val MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS = 4

        // Fixed budgets. Exceeding either drops the whole fuzzy level, never a part of it. The
        // variant budget bounds classes #1+#2+#3 combined; it sits above the E3b offline reference
        // (p95 33 variants, max 39) with headroom, so a correct implementation never trips it on the
        // typo set — a fact the recovery test asserts. The visited budget sits far above the E3b
        // reference (p95 133 entries, max 522).
        private const val MAX_FUZZY_VARIANTS = 64
        private const val MAX_FUZZY_VISITED = 8192

        // Phase C: the class #4 PROBE budget. Probe-first means MAX_FUZZY_VARIANTS caps only
        // survivors (variants that start at least one dictionary word — measured: typically 0-3);
        // the probes themselves are bounded separately. The count is prefix code points x
        // (alphabet size - 1), at most MAX_PREFIX_BYTES x 38 ≈ 4 864 for the Tatar alphabet, so
        // 8 192 can never trip on a real layout — it exists so a pathological future alphabet
        // fails closed instead of scanning unbounded.
        private const val MAX_FUZZY_PROBES = 8192

        // scanBlockRange entry verdicts: not a candidate / candidate, report its frequency /
        // abort the whole scan (the fuzzy budget trip). SCAN_TAKE_STEM is the P3 dual-track
        // variant of SCAN_TAKE: candidate whose remainder is a known suffix of the injected table.
        private const val SCAN_SKIP = 0
        private const val SCAN_TAKE = 1
        private const val SCAN_ABORT = 2
        private const val SCAN_TAKE_STEM = 3
        // TT-TYPO-NEXT Phase-B variant of SCAN_TAKE: a fuzzy candidate whose length equals the
        // typed prefix length (only scanVariantBlock returns it, under the policy's bonus flag).
        private const val SCAN_TAKE_SAME_LENGTH = 4
        private val MAGIC = "TATDICT\u0000".toByteArray(Charsets.US_ASCII)

        fun open(
            source: ByteBuffer,
            identity: DictionaryIdentity,
            expectedEntryCount: Long,
            expectedRawSize: Long,
            // P3: the same-stem boost table; null keeps the frozen D1 behavior byte-identical.
            suffixTable: InflectedSuffixTable? = null,
            // TT-TYPO-NEXT Phase B: the per-engine fuzzy policy; null is [FuzzyEditPolicy.DEFAULT],
            // bit-identical to the pre-Phase-B shipped behavior (class #1 only, no bonus).
            fuzzyEditPolicy: FuzzyEditPolicy? = null,
        ): TdictPrefixIndex? = try {
            require(identity.generation > 0)
            require(identity.schemaId == TdictFormat.SCHEMA_ID)
            require(identity.formatVersion == TdictFormat.FORMAT_VERSION)
            require(expectedEntryCount in 1..Int.MAX_VALUE.toLong())
            require(expectedRawSize in HEADER_SIZE.toLong()..Int.MAX_VALUE.toLong())
            require(source.limit().toLong() == expectedRawSize)

            val duplicate = source.asReadOnlyBuffer()
            duplicate.position(0)
            val buffer = duplicate.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= HEADER_SIZE)
            for (index in MAGIC.indices) require(buffer.get(index) == MAGIC[index])
            require(u16(buffer, 8) == identity.schemaId)
            require(u16(buffer, 10) == identity.formatVersion)
            require(u16(buffer, 12) == HEADER_SIZE)
            require(u16(buffer, 14) == CHECKSUM_ALGORITHM_SHA256)

            val count = u32(buffer, 16)
            require(count == expectedEntryCount)
            val blocks = u32(buffer, 20)
            val blockIndex = u32(buffer, 24)
            val blocksOffset = u32(buffer, 28)
            val blocksSize = u32(buffer, 32)
            val fileSize = u32(buffer, 36)
            val entryCount = count.toInt()
            val expectedBlockCount =
                (entryCount + TdictFormat.BLOCK_SIZE - 1) / TdictFormat.BLOCK_SIZE
            val expectedBlocksOffset = HEADER_SIZE.toLong() + U32_BYTES * blocks
            val expectedFileSize = expectedBlocksOffset + blocksSize
            require(blocks == expectedBlockCount.toLong())
            require(blockIndex == HEADER_SIZE.toLong())
            require(blocksOffset == expectedBlocksOffset)
            require(fileSize == expectedFileSize && fileSize == expectedRawSize)
            require(expectedBlocksOffset <= Int.MAX_VALUE && expectedFileSize <= Int.MAX_VALUE)

            val blockCount = blocks.toInt()
            val blockIndexOffset = blockIndex.toInt()
            val index = TdictPrefixIndex(
                buffer,
                identity,
                entryCount,
                blockCount,
                blockIndexOffset,
                suffixTable,
                fuzzyEditPolicy ?: FuzzyEditPolicy.DEFAULT,
            )
            // Full structural pass: the block table is canonical and every block decodes to
            // exactly its entry count, strictly increasing words and positive frequencies.
            require(index.blockOffset(0) == blocksOffset.toInt())
            var previousBlockOffset = index.blockOffset(0)
            for (block in 1 until blockCount) {
                val offset = index.blockOffset(block)
                require(offset > previousBlockOffset)
                require(offset.toLong() < fileSize)
                previousBlockOffset = offset
            }
            val previous = ByteArray(TdictFormat.MAX_WORD_BYTES)
            var previousLength = -1
            val current = ByteArray(TdictFormat.MAX_WORD_BYTES)
            for (block in 0 until blockCount) {
                val blockStart = index.blockOffset(block)
                val blockEnd =
                    if (block + 1 < blockCount) index.blockOffset(block + 1) else fileSize.toInt()
                val inBlock = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
                var cursor = blockStart
                val firstLength = unsigned(buffer.get(cursor))
                cursor++
                require(firstLength >= 1 && firstLength <= TdictFormat.MAX_WORD_BYTES)
                require(cursor + firstLength <= blockEnd)
                val firstStart = cursor
                cursor += firstLength
                for (entry in 0 until inBlock) {
                    val length: Int
                    if (entry == 0) {
                        for (offset in 0 until firstLength) current[offset] = buffer.get(firstStart + offset)
                        length = firstLength
                    } else {
                        index.decodeVarint(cursor)
                        val prefixLength = index.varintValue
                        cursor = index.varintNext
                        require(prefixLength <= firstLength)
                        require(cursor < blockEnd)
                        val suffixLength = unsigned(buffer.get(cursor))
                        cursor++
                        require(suffixLength >= 1)
                        require(prefixLength + suffixLength <= TdictFormat.MAX_WORD_BYTES)
                        require(cursor + suffixLength <= blockEnd)
                        for (offset in 0 until prefixLength) current[offset] = buffer.get(firstStart + offset)
                        for (offset in 0 until suffixLength) current[prefixLength + offset] = buffer.get(cursor + offset)
                        cursor += suffixLength
                        length = prefixLength + suffixLength
                    }
                    if (previousLength >= 0) {
                        val shared = minOf(previousLength, length)
                        var difference = 0
                        for (offset in 0 until shared) {
                            difference = unsigned(previous[offset]) - unsigned(current[offset])
                            if (difference != 0) break
                        }
                        if (difference == 0) difference = previousLength - length
                        require(difference < 0)
                    }
                    System.arraycopy(current, 0, previous, 0, length)
                    previousLength = length
                }
                for (entry in 0 until inBlock) {
                    index.decodeVarint(cursor)
                    require(index.varintValue != 0)
                    cursor = index.varintNext
                }
                require(cursor == blockEnd)
            }
            index
        } catch (_: RuntimeException) {
            null
        }

        private fun u16(buffer: ByteBuffer, offset: Int): Int =
            buffer.getShort(offset).toInt() and 0xffff

        private fun u32(buffer: ByteBuffer, offset: Int): Long =
            buffer.getInt(offset).toLong() and MAX_U32

        private fun unsigned(byte: Byte): Int = byte.toInt() and 0xff
    }
}
