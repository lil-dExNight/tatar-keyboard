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
 * Same-stem boost: a language-specific table of inflectional suffixes, consulted by the exact pass
 * of [TdictPrefixIndex.lookup] when the typed prefix is itself a complete dictionary word.
 *
 * A candidate's remainder arrives in at most two contiguous byte ranges (a schema-2 word is a
 * shared prefix of its block's first word plus its own suffix), and the test must not allocate.
 * The buffer is a heap copy of the mapped block (see [TdictPrefixIndex]). The Tatar engine uses
 * `TatarSuffixRules`; the Russian engine passes null.
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
 * Exact whole-word frequency in a dictionary; after-word forms keep only generated candidates the
 * dictionary contains. [TdictPrefixIndex] is the implementation; 0 means absent (schema 2 stores
 * strictly positive frequencies).
 */
fun interface WordFrequencySource {
    fun frequencyOf(word: String): Long
}

/**
 * Sink of [TdictPrefixIndex.forEachWordCold]: one visit per entry, in dictionary order, with the
 * word and its raw frequency. A fun interface with a primitive Long (not a Kotlin function type),
 * so the full-dictionary walk does not box a Long per word.
 */
fun interface ColdWordVisitor {
    fun visit(word: String, frequency: Long)
}

/**
 * A [PrefixComputer] that also reports how many of the results it just returned were exact
 * dictionary candidates; the rest come from typo recovery.
 *
 * The personal merge inserts one personal word between the exact and the typo-recovery candidates,
 * so it needs the boundary, and `lookup` returns a bare `List<String>`. The count is exposed as
 * state so no object is allocated per lookup. Reading it relies on the same guarantee as the
 * index's scratch buffers: at most one active worker, serialized by `LatestOnlyPrefixEngine`.
 */
internal interface ClassifiedPrefixComputer : PrefixComputer {
    /** Number of leading results of the last [lookup] that are exact candidates. */
    val lastExactCount: Int

    /**
     * Autocorrect verdict of the last [lookup], or null when the typed word must not be replaced.
     * Read from the UI thread, so implementations publish it through a `@Volatile` reference.
     */
    val lastAutocorrectAdvice: AutocorrectAdvice?
        get() = null
}

/**
 * Receives the current key-neighbor table for a computer that runs a typo-recovery pass. Separate
 * from [PrefixComputer] so the `lookup` signature stays unchanged.
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
 * Schema-2 dictionary reader. The supplied buffer must already have passed storage validation;
 * [open] re-checks the invariants lookups depend on.
 *
 * [suffixTable] is the same-stem boost table, null for an engine that never boosts (the Russian
 * one). It is consulted by the exact pass only, when the typed prefix is itself a complete word.
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
    // Reusable per-index scratch, touched only inside lookup(), whose exclusivity is guaranteed by
    // LatestOnlyPrefixEngine serialization (at most one active worker). updateKeyNeighbors() only
    // swaps a @Volatile reference.
    private val exactScratch = ByteArray(MAX_PREFIX_BYTES)
    private val variantScratch = ByteArray(MAX_PREFIX_BYTES + VARIANT_HEADROOM)
    private val codePointScratch = IntArray(MAX_PREFIX_BYTES)
    // Front-coding decode scratch: word A is the target of every single-word access; two-word
    // comparisons (ranking tie-breaks) decode the second word into word B. Neither escapes lookup().
    private val wordScratchA = ByteArray(TdictFormat.MAX_WORD_BYTES)
    private val wordScratchB = ByteArray(TdictFormat.MAX_WORD_BYTES)
    // Decode scratch of the probe path, separate from A/B: probes interleave with survivor scans
    // whose tie-breaks use A/B.
    private val probeScratch = ByteArray(TdictFormat.MAX_WORD_BYTES)
    // Per-position search ranges for the class #4 probes: words starting with the first N code
    // points of the typed prefix, narrowed once per lookup. A variant substituted at position p
    // shares the typed prefix's first p code points, so its survivors can only be in
    // probeRange[p]; an empty range skips the whole position.
    private val probeRangeStart = IntArray(MAX_PREFIX_BYTES)
    private val probeRangeEnd = IntArray(MAX_PREFIX_BYTES)
    // The block touched by the last access, fully decoded: concatenated word bytes, per-word start
    // offsets and frequencies. Range scans touch every entry of a block, so the block is decoded
    // once instead of re-walked per entry. Worker-confined like the scratches above; the data is
    // immutable, so the cache never needs invalidation.
    private val blockWordBytes = ByteArray(TdictFormat.BLOCK_SIZE * TdictFormat.MAX_WORD_BYTES)
    private val blockWordStarts = IntArray(TdictFormat.BLOCK_SIZE + 1)
    private val blockFrequencies = LongArray(TdictFormat.BLOCK_SIZE)
    private var cachedBlock = -1
    // Raw-block fetch scratches. Per-byte MappedByteBuffer.get is slow, so the active block is
    // copied into a heap array with one bulk get and parsed from there. The mapping stays the
    // source of truth (evictable, file-backed); each scratch holds at most one block. Two
    // scratches, because decodeWordInto (probe path, ranking tie-breaks) runs inside
    // scanBlockRange's callbacks. Worker-confined; the data is immutable, so the block tags never
    // need invalidation.
    private val rangeScanBytes = ByteArray(MAX_BLOCK_RAW_BYTES)
    private val probeBlockBytes = ByteArray(MAX_BLOCK_RAW_BYTES)
    private var rangeScanBlockNumber = -1
    private var probeBlockNumber = -1
    // A duplicate of the mapped buffer, so bulk fetches can set its position without touching the
    // supplied buffer's position/limit. rangeScanView is a heap view of the current scan block,
    // handed to the suffix test; scanBlockRange's remainder positions are relative to it.
    private val blockFetchView: ByteBuffer = bytes.duplicate()
    private val rangeScanView: ByteBuffer = ByteBuffer.wrap(rangeScanBytes)
    private val rankedIndices = IntArray(MAX_RESULTS)
    private val rankedFrequencies = LongArray(MAX_RESULTS)
    // Same-stem boost: the two tracks of the exact pass, candidates whose remainder is a known
    // suffix and the rest. Fixed-size scratch allocated once per index.
    private val stemTrackIndices = IntArray(MAX_RESULTS)
    private val stemTrackFrequencies = LongArray(MAX_RESULTS)
    private val stemTrackClasses = IntArray(MAX_RESULTS)
    private val otherTrackIndices = IntArray(MAX_RESULTS)
    private val otherTrackFrequencies = LongArray(MAX_RESULTS)
    private val otherTrackClasses = IntArray(MAX_RESULTS)
    // Ranking key carried with every ranked slot as a primitive int. Exact candidates all carry
    // EDIT_CLASS_EXACT, so the key never changes the exact order; typo-recovery candidates carry
    // their packed rank key (see [fuzzyRankKey]), so that level orders by class first (see
    // [ranksBefore]).
    private val rankedClasses = IntArray(MAX_RESULTS)
    private val fuzzyIndices = IntArray(MAX_RESULTS)
    private val fuzzyFrequencies = LongArray(MAX_RESULTS)
    private val fuzzyClasses = IntArray(MAX_RESULTS)
    // Scratch of the autocorrect pass, separate from the display pass: autocorrect has its own
    // rules (word length, word absent from the dictionary) and runs whether or not the display
    // typo-recovery level ran.
    private val autocorrectCodePointScratch = IntArray(MAX_PREFIX_BYTES)
    private val autocorrectVariantScratch = ByteArray(MAX_PREFIX_BYTES + VARIANT_HEADROOM)

    @Volatile
    private var neighborTable: KeyNeighborTable? = null

    /**
     * How many leading results of the last [lookup] are exact. Worker-confined; reset at the start
     * of every lookup, so a failed or rejected one leaves no stale boundary.
     */
    override var lastExactCount = 0
        private set

    /**
     * Autocorrect verdict of the last [lookup]. Written by the worker, read on the UI thread when a
     * word separator is pressed; the object is immutable, so `@Volatile` publication is enough.
     * Reset at the start of every lookup, like [lastExactCount].
     */
    @Volatile
    override var lastAutocorrectAdvice: AutocorrectAdvice? = null
        private set

    /** Drops the current verdict; called when the engine idles or is torn down. */
    fun clearAutocorrectAdvice() {
        lastAutocorrectAdvice = null
    }

    // Test-only counters of the last lookup's typo-recovery work: plain ints assigned on the hot
    // path (no allocation, no logging), so JVM tests can check variants, visited entries, probes
    // and whether the budget tripped.
    internal var lastFuzzyVariantCount = 0
        private set
    internal var lastFuzzyVisitedCount = 0
        private set
    internal var lastFuzzyOverBudget = false
        private set
    internal var lastFuzzyProbeCount = 0
        private set

    // Same for the autocorrect pass: class #4 probes issued by the last advice computation.
    internal var lastAutocorrectProbeCount = 0
        private set

    // Typo-recovery accumulator, private to one lookup() call and reset on each entry.
    private var fuzzyExactCount = 0
    private var fuzzyRemaining = 0
    private var fuzzyCount = 0
    private var fuzzyVisited = 0
    private var fuzzyOverBudget = false
    private var fuzzyPrefixLength = 0
    // Variants that consumed the shared MAX_FUZZY_VARIANTS budget so far (every class #1/#2/#3
    // variant and each class #4 survivor; class #4 probes count against MAX_FUZZY_PROBES).
    private var fuzzyVariantsUsed = 0
    private var fuzzyProbesUsed = 0

    // Edit class of the variant pass currently running, set before each generator runs and read by
    // scanVariantBlock, so every candidate is tagged with the class that produced it.
    private var fuzzyCurrentClass = EDIT_CLASS_LONG_PRESS

    // Autocorrect accumulator, private to one computeAutocorrectAdvice() call. It counts whole-word
    // matches: how many variants of the typed word are dictionary entries, and which one. Anything
    // but exactly one means no replacement.
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
            lastFuzzyOverBudget = false
            for (offset in 0 until prefixLength) {
                exactScratch[offset] = normalizedPrefixUtf8.byteAt(offset).toByte()
            }
            // One binary search serves both the exact pass and the autocorrect pass: the
            // typo-recovery level between them only reads exactScratch, so the bound stays valid.
            val exactLowerBound = lowerBound(exactScratch, prefixLength, 0)
            var resultCount = collectExact(prefixLength, exactLowerBound)
            // Recorded before typo recovery appends to the same ranked arrays: the personal merge
            // needs this boundary.
            lastExactCount = resultCount
            // Typo recovery fills only cells the exact pass left empty; exact candidates are
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
            // Outside the `resultCount < MAX_RESULTS` guard: the autocorrect verdict is about the
            // typed word itself and must not depend on how full the strip is.
            computeAutocorrectAdvice(normalizedPrefixUtf8, prefixLength, exactLowerBound)
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
     * Decides whether the word just looked up may be autocorrected, and to what. Runs on the same
     * worker right after the display passes, on the same mapped buffer. Conditions, in order:
     *  - the word is at least [AutocorrectPolicy.MIN_WORD_CODE_POINTS] code points long;
     *  - the word is absent from the dictionary;
     *  - exactly one variant of it is a dictionary word, counted before any frequency filter, so
     *    an ambiguous typo is left alone rather than resolved by frequency;
     *  - that candidate's frequency is at least [AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY].
     *
     * The match is whole-word, not prefix: a word is replaced by a word one edit away, not by a
     * continuation. The edit classes are [FuzzyEditPolicy.autocorrectClasses], never the display
     * set, so enabling display classes does not change autocorrect.
     *
     * Class #1 costs one binary search per variant and scans no block. Class #4 is probe-first like
     * the display pass: per-position narrowed no-cache probes and whole-word equality, never a
     * range scan. Short words skip the pass entirely.
     */
    private fun computeAutocorrectAdvice(
        normalizedPrefixUtf8: ImmutableUtf8Prefix,
        prefixLength: Int,
        typedEntry: Int,
    ) {
        val table = neighborTable ?: return
        if (table.isEmpty) return
        val codePointCount = countCodePointsByLeadBytes(exactScratch, prefixLength)
        if (codePointCount < AutocorrectPolicy.MIN_WORD_CODE_POINTS) {
            return
        }
        // The typed word's lower bound comes from lookup(); typo recovery never writes
        // exactScratch, so it is still valid.
        if (typedEntry < entryCount && wordEquals(typedEntry, exactScratch, prefixLength)) return
        autocorrectMatchCount = 0
        autocorrectMatchIndex = NO_ENTRY
        autocorrectProbesUsed = 0

        if (EDIT_CLASS_LONG_PRESS in fuzzyPolicy.autocorrectClasses) {
            val emitted = FuzzyPrefixVariants.generateLongPressVariants(
                exactScratch, prefixLength, table, autocorrectCodePointScratch,
                autocorrectVariantScratch, MAX_FUZZY_VARIANTS, autocorrectConsumer,
            )
            // On a budget overrun or malformed input give no advice: a partial variant set could
            // hide the second candidate that makes the typo ambiguous.
            if (emitted < 0) return
        }

        // Full single substitution, probe-first. Skipped once the word is already ambiguous:
        // further probes can only add candidates, and the verdict is already no.
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
        // Distinct variants are distinct byte strings, so this only guards against re-entry;
        // counting the same entry twice would turn one candidate into a false ambiguity.
        if (entry == autocorrectMatchIndex) return
        autocorrectMatchCount++
        autocorrectMatchIndex = entry
    }

    /** Probe-first whole-word consumer of the class #4 autocorrect variants. */
    private val autocorrectProbeConsumer =
        FuzzyPrefixVariants.PositionedVariantConsumer { position, bytes, length ->
            probeAutocorrectWholeWord(position, bytes, length)
        }

    /**
     * One narrowed no-cache probe per class #4 variant, then whole-word equality, never a range
     * scan. Counts a match like [matchWholeWord] (dedup by entry index; stops past two).
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
        // A class #1 / class #4 duplicate must not count twice, or one candidate would read as two.
        if (probe == autocorrectMatchIndex) return
        autocorrectMatchCount++
        autocorrectMatchIndex = probe
    }

    /**
     * The exact pass: fills [rankedIndices] with up to [MAX_RESULTS] and returns the count.
     *
     * With a suffix table and a typed prefix that is itself a complete dictionary word of at least
     * [MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS] code points, the pass runs in two tracks: candidates
     * whose remainder is a known suffix rank before other continuations, frequency order kept
     * within each track, the typed word excluded. The complete-word test needs no extra search:
     * [lowerBound] has already landed on the entry.
     *
     * The length threshold exists because a short complete word is usually a mid-typing state on
     * the way to a longer word, so its word forms must not displace other continuations. It
     * matches [AutocorrectPolicy.MIN_WORD_CODE_POINTS].
     */
    private fun collectExact(prefixLength: Int, start: Int): Int {
        // [start] is the prefix's lower bound, computed once in lookup() and shared with the
        // autocorrect pass.
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
                        rangeScanView, remFirstStart, remFirstLength, remSecondStart, remSecondLength,
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
        // Same-stem candidates first, then the rest fill the remaining cells; both tracks are
        // already in (frequency, code point) order.
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
     * Fills the cells the exact pass left empty with the best typo-recovery candidates. Order:
     * edit class first (#1 long-press partner, #2 geometric neighbor, #3 transposition, #4 full
     * single substitution); within a class, the same-length bonus if the policy enables it; then
     * frequency descending, then code point ascending. Exact candidates always rank first and are
     * never touched. Returns the total candidate count.
     *
     * The classes that run come from the engine's [FuzzyEditPolicy], set through [open]. No
     * shipped policy enables classes #2 and #3; their generators have direct tests.
     *
     * Class #4 has an extra condition: it runs only when the exact pass returned nothing and the
     * prefix has at least [MIN_SUBSTITUTION_PREFIX_CODE_POINTS] code points. The strip is empty
     * then, so a wrong guess displaces nothing.
     *
     * If variant generation or the block scan exceeds a fixed budget, the whole level is dropped
     * (returns [exactCount]), never kept in part.
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
        // The enabled classes share one budget: variants scanned across all of them stay within
        // MAX_FUZZY_VARIANTS (for class #4 only survivors count; its probes count against
        // MAX_FUZZY_PROBES). Each candidate is tagged with a rank key derived from
        // fuzzyCurrentClass, set before its class runs. Any class returning -1 drops the whole
        // level.

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

        // Class #4: full single substitution over the layout's alphabet, probe-first. Each of the
        // n x alphabet variants gets one existence probe (binary search, no range scan); only
        // survivors are scanned and counted against the shared variant budget. It runs only when
        // the exact pass found nothing and the prefix has >= 4 code points, so it can only fill
        // an otherwise empty strip.
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
            // The consumer already counted each issued probe in fuzzyProbesUsed; `emitted` also
            // includes variants skipped by an empty range, so adding it would double-count.
        }

        lastFuzzyVariantCount = fuzzyVariantsUsed
        lastFuzzyVisitedCount = fuzzyVisited
        lastFuzzyProbeCount = fuzzyProbesUsed
        for (slot in 0 until fuzzyCount) {
            rankedIndices[exactCount + slot] = fuzzyIndices[slot]
            rankedFrequencies[exactCount + slot] = fuzzyFrequencies[slot]
            rankedClasses[exactCount + slot] = fuzzyClasses[slot]
        }
        return exactCount + fuzzyCount
    }

    /**
     * Probe-first consumer of edit class #4: one existence probe per variant (binary search plus a
     * starts-with check, no range scan). Only a variant that starts at least one dictionary word
     * (a survivor) consumes the shared variant budget and gets its block scanned by
     * [scanVariantBlock]; too many survivors trip the budget like a generator overflow.
     *
     * Two things keep probes cheap:
     * 1. The probe search never uses the shared decoded-block cache. Hundreds of probes per lookup
     *    follow nearly identical binary-search paths, and a one-entry cache would make every probe
     *    evict the previous probe's block. Each compared word is decoded directly into a dedicated
     *    scratch ([decodeWordInto]): a bounded front-coded walk, no allocation.
     * 2. The search is narrowed per position: a variant substituted at position p shares the typed
     *    prefix's first p code points, so its survivors can only be in probeRange[p] (see
     *    [computeProbeRanges]); an empty range skips the position without a probe.
     *
     * [probeLowerBound] mirrors [lowerBound] comparison for comparison, so results are the same as
     * a cached search.
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
     * Per-position probe ranges: probeRange[p] is the entry range of words starting with the typed
     * prefix's first p code points (p = 0 is the whole dictionary). Each range is searched within
     * its predecessor; once a range is empty, every later one is empty too.
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
     * [lowerBound] over a no-cache comparator: the first entry in [low0, high0) not smaller than
     * the query in whole-word-then-length order. Probe path only.
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

    /** [upperBound] over the no-cache prefix comparator. Probe path only. */
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

    /** Scans one variant's dictionary block, ranking its candidates into [fuzzyIndices]. */
    private fun scanVariantBlock(variantBytes: ByteArray, variantLength: Int) {
        if (fuzzyOverBudget) return
        val start = lowerBound(variantBytes, variantLength, 0)
        val end = upperBound(variantBytes, variantLength, start)
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
                // Never suggest the typed word itself, and never put one word in two cells
                // (de-duplicated by dictionary index). Classes run in order (#1 to #4), so the
                // first class to reach a word is also its best one.
                if (equalsQuery ||
                    containsIndex(rankedIndices, fuzzyExactCount, index) ||
                    containsIndex(fuzzyIndices, fuzzyCount, index)
                ) {
                    return@scanBlockRange SCAN_SKIP
                }
                // Same-length bonus. A candidate with an empty remainder past the variant is the
                // variant itself, and a substitution variant has the typed prefix's length, so an
                // empty remainder marks exactly the same-length candidates without counting.
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
     * Rank key of a typo-recovery candidate: edit class ascending, then (under
     * [FuzzyEditPolicy.sameLengthBonus]) a same-length candidate before its continuations; ties go
     * to [insertRanked] (frequency descending, code point ascending). Packed as `class * 2 - bonus`,
     * so one int comparison covers both keys.
     */
    private fun fuzzyRankKey(editClass: Int, sameLength: Boolean): Int =
        editClass * 2 - if (sameLength) 1 else 0

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
        // Rank key first (ascending). Exact candidates all share EDIT_CLASS_EXACT, so it never
        // reorders them; typo-recovery candidates carry the packed key of [fuzzyRankKey].
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

    // --- BigramDictionary: the schema-3 bigram table resolves its head/successor indices through
    // these two reads, on the same serialized worker as lookup(), so the block cache and scratch
    // rules above apply.
    override val rawSha256: String
        get() = identity.rawSha256

    /** Exact-word index lookup: one [lowerBound] plus an equality check, no prefix semantics. */
    override fun indexOfWord(query: ByteArray, queryLength: Int): Int {
        val candidate = lowerBound(query, queryLength, 0)
        if (candidate >= entryCount) return -1
        return if (wordEquals(candidate, query, queryLength)) candidate else -1
    }

    /**
     * Exact whole-word frequency of [query], or 0 when absent (schema 2 stores strictly positive
     * frequencies). One binary search plus a cached-block read; allocates nothing and is
     * worker-confined like the lookup path.
     */
    fun frequencyOf(query: ByteArray, queryLength: Int): Long {
        if (queryLength == 0 || queryLength > TdictFormat.MAX_WORD_BYTES) return 0L
        val entry = indexOfWord(query, queryLength)
        return if (entry < 0) 0L else frequencyAt(entry)
    }

    /**
     * String form of [frequencyOf]. It encodes, so it is for bounded callers off the hot path (the
     * after-word forms), never the per-keystroke scan.
     */
    override fun frequencyOf(word: String): Long {
        val bytes = word.toByteArray(Charsets.UTF_8)
        return frequencyOf(bytes, bytes.size)
    }

    /**
     * Exact whole-word membership of [normalizedWord], without the block cache or any shared
     * scratch: every byte goes from the read-only mapping into local state. This makes it safe to
     * call from a thread other than the lookup worker (the learned-pairs store's worker); plain
     * [ByteBuffer.get] reads need no ordering beyond the caller's `@Volatile` publication.
     *
     * Cost: a cold binary search, about log2([entryCount]) front-coded decodes of at most one block
     * each, paid only when a pair is learned. Same unsigned-byte order as [lowerBound].
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
     * Cold enumeration of every entry with its frequency, for the glide decoder's one-time
     * word-index build. One sequential pass over the mapping with local varint state; like
     * [containsWordCold] it never touches the block cache or the shared [varintValue]/[varintNext].
     * Allocates one String per word, once per dictionary.
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
     * Cold-read version of [decodeWordInto]: same front-coded walk, but with local varint state
     * instead of [varintValue]/[varintNext], so it cannot race the lookup worker. Used only by
     * [containsWordCold].
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
     * The [count] most frequent words of the dictionary, frequency descending, then code point
     * ascending (UTF-8 byte order is code-point order, so the lookup comparator applies).
     *
     * One linear scan: blocks decode sequentially through the block cache, top-N state is two
     * primitive arrays, and tie-breaks read words through the no-cache [decodeWordInto], so nothing
     * is allocated per entry. Runs once per engine at start, never on the lookup path.
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
        ensureRangeScanBlock(block)
        val raw = rangeScanBytes
        var cursor = 0
        val firstLength = unsigned(raw[cursor])
        cursor++
        val firstStart = cursor
        cursor += firstLength
        blockWordStarts[0] = 0
        var writeAt = firstLength
        System.arraycopy(raw, firstStart, blockWordBytes, 0, firstLength)
        blockWordStarts[1] = writeAt
        for (entry in 1 until count) {
            // Inline varint fast path: a prefix length virtually always fits one byte.
            var prefixLength = unsigned(raw[cursor])
            cursor++
            if (prefixLength >= 0x80) {
                decodeVarint(raw, cursor - 1)
                prefixLength = varintValue
                cursor = varintNext
            }
            val suffixLength = unsigned(raw[cursor])
            cursor++
            // The prefix refers to the block's first word, which lies contiguously in the fetched
            // block; copying from there (not from the partly overwritten cache) keeps every entry
            // independently decodable.
            System.arraycopy(raw, firstStart, blockWordBytes, writeAt, prefixLength)
            System.arraycopy(raw, cursor, blockWordBytes, writeAt + prefixLength, suffixLength)
            cursor += suffixLength
            writeAt += prefixLength + suffixLength
            blockWordStarts[entry + 1] = writeAt
        }
        for (entry in 0 until count) {
            // Inline varint fast path; a varint holds a u32, interpreted unsigned.
            var frequency = unsigned(raw[cursor])
            cursor++
            if (frequency >= 0x80) {
                decodeVarint(raw, cursor - 1)
                frequency = varintValue
                cursor = varintNext
            }
            blockFrequencies[entry] = frequency.toLong() and MAX_U32
        }
        cachedBlock = block
    }

    /**
     * Streams the entry range [start, end) without materializing words. For each entry it calls
     * [onEntry] with (index, equalsQuery) and the candidate's remainder past the query as two byte
     * ranges relative to the fetched block, which the suffix test reads through [rangeScanView]
     * without a copy (valid only until the scan moves to the next block). For entries [onEntry]
     * accepted, it then calls [onFrequency] with the verdict, in the same block visit. `inline`,
     * so no callback allocates; each block is bulk-fetched once per visit ([ensureRangeScanBlock]).
     *
     * [onEntry] verdicts: [SCAN_SKIP], not a candidate; [SCAN_TAKE], a candidate;
     * [SCAN_TAKE_STEM], a same-stem candidate of the two-track exact pass;
     * [SCAN_TAKE_SAME_LENGTH], a candidate as long as the typed prefix (typo recovery only);
     * [SCAN_ABORT], stop at once (budget trip; the level is dropped, so [onFrequency] may be
     * skipped).
     *
     * [onFrequency] fires in ascending index order within a block, and blocks are visited in
     * ascending order.
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
            ensureRangeScanBlock(block)
            val raw = rangeScanBytes
            var cursor = 0
            val firstLength = unsigned(raw[cursor])
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
                    prefixLength = unsigned(raw[cursor])
                    cursor++
                    if (prefixLength >= 0x80) {
                        decodeVarint(raw, cursor - 1)
                        prefixLength = varintValue
                        cursor = varintNext
                    }
                    suffixLength = unsigned(raw[cursor])
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
                    // The remainder past the query, piecewise against the fetched block. The
                    // shared prefix may reach past the query's end, never the reverse: every word
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
                    decodeVarint(raw, cursor)
                    cursor = varintNext
                    cursor += 1 + unsigned(raw[cursor])
                    position++
                }
                var frequencyPosition = 0
                while (frequencyPosition < inBlock) {
                    decodeVarint(raw, cursor)
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
     * [suffixStart]) against [query], read from the current scan block's scratch. Called only by
     * [scanBlockRange] while its fetch is current; the caller has checked that the word length
     * equals [queryLength].
     */
    private fun wordBytesEqual(
        firstStart: Int,
        prefixLength: Int,
        suffixStart: Int,
        query: ByteArray,
        queryLength: Int,
    ): Boolean {
        val raw = rangeScanBytes
        for (offset in 0 until prefixLength) {
            if (unsigned(raw[firstStart + offset]) !=
                (query[offset].toInt() and 0xff)
            ) {
                return false
            }
        }
        for (offset in 0 until queryLength - prefixLength) {
            if (unsigned(raw[suffixStart + offset]) !=
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
     * Decodes word [index] into [scratch] and returns its byte length. Used by the no-cache probe
     * path and by [compareWords], whose two words may be in different blocks and so cannot share
     * the single-block cache; binary search and scans go through the cache.
     *
     * A block stores its first word in full and every following word as a varint shared-prefix
     * length against that first word plus a u8-length suffix, so decoding word p walks p entries
     * (≤ [TdictFormat.BLOCK_SIZE] - 1), copying nothing until the target. The raw block is
     * bulk-fetched into [probeBlockBytes] once per block switch ([ensureProbeBlock]); "no-cache"
     * means no decoded-block cache. Prefix bytes are copied from the fetched block, never from
     * [scratch], so consecutive decodes into one scratch cannot corrupt the prefix.
     */
    private fun decodeWordInto(index: Int, scratch: ByteArray): Int {
        val block = index / TdictFormat.BLOCK_SIZE
        val position = index % TdictFormat.BLOCK_SIZE
        ensureProbeBlock(block)
        val raw = probeBlockBytes
        var cursor = 0
        val firstLength = unsigned(raw[cursor])
        cursor++
        val firstStart = cursor
        if (position == 0) {
            System.arraycopy(raw, firstStart, scratch, 0, firstLength)
            return firstLength
        }
        cursor += firstLength
        var prefixLength = 0
        var suffixLength = 0
        for (entry in 1..position) {
            decodeVarint(raw, cursor)
            prefixLength = varintValue
            cursor = varintNext
            suffixLength = unsigned(raw[cursor])
            cursor++
            if (entry == position) {
                System.arraycopy(raw, firstStart, scratch, 0, prefixLength)
                System.arraycopy(raw, cursor, scratch, prefixLength, suffixLength)
            }
            cursor += suffixLength
        }
        return prefixLength + suffixLength
    }

    // Shared varint decode result, worker-confined like the word scratches above.
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

    /** [decodeVarint] over a fetched block scratch; same result fields, array reads. */
    private fun decodeVarint(raw: ByteArray, offset: Int) {
        var value = 0
        var shift = 0
        var cursor = offset
        while (true) {
            val byte = unsigned(raw[cursor])
            cursor++
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        varintValue = value
        varintNext = cursor
    }

    /**
     * Bulk-fetches block [block]'s raw bytes into [scratch] with one relative get off
     * [blockFetchView]. The extent comes from the block index, the last block ending at the buffer
     * limit ([open] validated both). A corrupt extent throws, and lookup()'s catch returns an
     * empty result, as for any malformed read.
     */
    private fun fetchBlock(block: Int, scratch: ByteArray) {
        val start = blockOffset(block)
        val end = if (block + 1 < blockCount) blockOffset(block + 1) else blockFetchView.limit()
        blockFetchView.position(start)
        blockFetchView.get(scratch, 0, end - start)
    }

    /** Fetches block [block] into [rangeScanBytes] unless it is already there. */
    private fun ensureRangeScanBlock(block: Int) {
        if (block == rangeScanBlockNumber) return
        fetchBlock(block, rangeScanBytes)
        rangeScanBlockNumber = block
    }

    /** Fetches block [block] into [probeBlockBytes] unless it is already there. */
    private fun ensureProbeBlock(block: Int) {
        if (block == probeBlockNumber) return
        fetchBlock(block, probeBlockBytes)
        probeBlockNumber = block
    }

    private fun blockOffset(block: Int): Int = bytes.getInt(blockIndexOffset + block * U32_BYTES)

    companion object {
        private const val HEADER_SIZE = 72
        private const val CHECKSUM_ALGORITHM_SHA256 = 1
        private const val U32_BYTES = 4
        private const val MAX_RESULTS = 3
        internal const val MAX_PREFIX_BYTES = 128
        private const val MAX_U32 = 0xffff_ffffL
        // Upper bound on one front-coded block's raw byte size in the canonical encoding: u8
        // length + first word, then per following entry a <=5-byte u32 varint prefix length, a u8
        // suffix length and the suffix, then one <=5-byte frequency varint per entry. A file with
        // over-long varints could exceed it; fetchBlock then throws inside lookup()'s catch
        // instead of reading past the scratch.
        private const val MAX_U32_VARINT_BYTES = 5
        private const val MAX_BLOCK_RAW_BYTES =
            1 + TdictFormat.MAX_WORD_BYTES +
                (TdictFormat.BLOCK_SIZE - 1) *
                (MAX_U32_VARINT_BYTES + 1 + TdictFormat.MAX_WORD_BYTES) +
                TdictFormat.BLOCK_SIZE * MAX_U32_VARINT_BYTES

        /** "No dictionary entry"; entry indices are non-negative. */
        private const val NO_ENTRY = -1

        // Edit-class ranking keys. Exact candidates all sort as EDIT_CLASS_EXACT. For typo
        // recovery the ascending order is #1 long-press partner < #2 geometric neighbor < #3
        // transposition < #4 full single substitution, applied ahead of frequency by [ranksBefore]
        // through the packed key of [fuzzyRankKey]. Exact candidates always rank first because
        // they live in separate arrays and are merged first.
        private const val EDIT_CLASS_EXACT = 0
        internal const val EDIT_CLASS_LONG_PRESS = 1
        internal const val EDIT_CLASS_GEOMETRIC = 2
        internal const val EDIT_CLASS_TRANSPOSITION = 3
        internal const val EDIT_CLASS_SUBSTITUTION = 4

        // Which edit classes run is the per-engine [FuzzyEditPolicy] passed to [open].

        // Extra headroom on the variant buffer covers a re-encode a few bytes longer than the
        // prefix; all edit classes keep the code-point length.
        private const val VARIANT_HEADROOM = 8

        // Typo recovery needs at least three code points; the count is taken off the UTF-8 lead
        // bytes, so a two-letter Cyrillic prefix (four bytes) is rejected.
        private const val MIN_FUZZY_PREFIX_CODE_POINTS = 3

        // Edit class #4 also requires an empty exact pass (see collectFuzzy) and a prefix of at
        // least four code points, the same bound as AutocorrectPolicy.MIN_WORD_CODE_POINTS.
        private const val MIN_SUBSTITUTION_PREFIX_CODE_POINTS = 4

        // Same-stem boost: the typed prefix must be a complete word of at least this many code
        // points (counted off the UTF-8 lead bytes), as in AutocorrectPolicy.MIN_WORD_CODE_POINTS.
        private const val MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS = 4

        // Fixed budgets. Exceeding either drops the whole typo-recovery level. The variant budget
        // covers classes #1 to #3 combined plus class #4 survivors; both budgets sit well above
        // what the recovery tests observe, so they only stop pathological input.
        private const val MAX_FUZZY_VARIANTS = 64
        private const val MAX_FUZZY_VISITED = 8192

        // Class #4 probe budget. MAX_FUZZY_VARIANTS caps only survivors (variants that start at
        // least one dictionary word); probes are bounded here. The probe count is prefix code
        // points x (alphabet size - 1), below this bound for the shipped layouts; it stops an
        // unexpectedly large alphabet from scanning without limit.
        private const val MAX_FUZZY_PROBES = 8192

        // scanBlockRange entry verdicts: not a candidate / candidate / abort the whole scan (budget
        // trip). SCAN_TAKE_STEM: a candidate whose remainder is a known suffix of the table.
        private const val SCAN_SKIP = 0
        private const val SCAN_TAKE = 1
        private const val SCAN_ABORT = 2
        private const val SCAN_TAKE_STEM = 3
        // A typo-recovery candidate as long as the typed prefix (only scanVariantBlock returns it,
        // under the policy's bonus flag).
        private const val SCAN_TAKE_SAME_LENGTH = 4
        private val MAGIC = "TATDICT\u0000".toByteArray(Charsets.US_ASCII)

        fun open(
            source: ByteBuffer,
            identity: DictionaryIdentity,
            expectedEntryCount: Long,
            expectedRawSize: Long,
            // The same-stem boost table; null disables the boost.
            suffixTable: InflectedSuffixTable? = null,
            // The per-engine typo-recovery policy; null is [FuzzyEditPolicy.DEFAULT].
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
