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
) : ClassifiedPrefixComputer, KeyNeighborSink, BigramDictionary, WordFrequencySource {
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
    // Edit class carried alongside every ranked slot as a plain primitive int — no boxing, no
    // collection. Exact candidates all carry EDIT_CLASS_EXACT, so the class key is a no-op tie on
    // the exact level and its frozen order is unchanged; fuzzy candidates carry their generating
    // class (#1/#2/#3) so the fuzzy level orders by class first (see [ranksBefore]).
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
    // visited entries and prove the fail-closed budget never trips on the typo set.
    internal var lastFuzzyVariantCount = 0
        private set
    internal var lastFuzzyVisitedCount = 0
        private set
    internal var lastFuzzyOverBudget = false
        private set

    // Fuzzy-pass accumulator, private to a single lookup() invocation and reset on each entry.
    private var fuzzyExactCount = 0
    private var fuzzyRemaining = 0
    private var fuzzyCount = 0
    private var fuzzyVisited = 0
    private var fuzzyOverBudget = false
    private var fuzzyPrefixLength = 0

    // The edit class of the variant pass currently running (EDIT_CLASS_LONG_PRESS/GEOMETRIC/
    // TRANSPOSITION). A plain int, set before each class's generator runs and read by
    // scanVariantBlock so every fuzzy candidate is tagged with the class that produced it.
    private var fuzzyCurrentClass = EDIT_CLASS_LONG_PRESS

    // D3 accumulator, private to a single computeAutocorrectAdvice() invocation. The pass counts
    // WHOLE-WORD matches: how many class #1 variants of the typed word are themselves dictionary
    // entries, and which one. Anything but exactly one means no replacement.
    private var autocorrectMatchCount = 0
    private var autocorrectMatchIndex = NO_ENTRY

    // Allocated once, so neither a lambda nor any object is created per lookup or per variant.
    private val fuzzyConsumer =
        FuzzyPrefixVariants.VariantConsumer { bytes, length -> scanVariantBlock(bytes, length) }

    private val autocorrectConsumer =
        FuzzyPrefixVariants.VariantConsumer { bytes, length -> matchWholeWord(bytes, length) }

    override fun updateKeyNeighbors(table: KeyNeighborTable?) {
        neighborTable = table
    }

    override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
        val prefixLength = normalizedPrefixUtf8.byteCount
        lastExactCount = 0
        lastAutocorrectAdvice = null
        if (prefixLength == 0 ||
            prefixLength > MAX_PREFIX_BYTES ||
            !isValidUtf8Scalar(normalizedPrefixUtf8)
        ) {
            return emptyList()
        }
        return try {
            lastFuzzyVariantCount = 0
            lastFuzzyVisitedCount = 0
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
                if (table != null && !table.isEmpty &&
                    countCodePointsByLeadBytes(exactScratch, prefixLength) >=
                    MIN_FUZZY_PREFIX_CODE_POINTS
                ) {
                    resultCount = collectFuzzy(prefixLength, table, resultCount)
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
     * instead. And the class is pinned to #1 directly rather than through
     * [SHIPPED_FUZZY_EDIT_CLASSES]: D3 excludes classes #2/#3 by its own contract, so re-enabling
     * them for the band must not make autocorrect follow.
     *
     * The pass costs one binary search per variant and scans no block at all; it runs only for words
     * long enough to qualify, so short prefixes — the bulk of the keystrokes — pay nothing.
     */
    private fun computeAutocorrectAdvice(
        normalizedPrefixUtf8: ImmutableUtf8Prefix,
        prefixLength: Int,
    ) {
        val table = neighborTable ?: return
        if (table.isEmpty) return
        if (countCodePointsByLeadBytes(exactScratch, prefixLength) <
            AutocorrectPolicy.MIN_WORD_CODE_POINTS
        ) {
            return
        }
        val typedEntry = lowerBound(exactScratch, prefixLength, 0)
        if (typedEntry < entryCount && wordEquals(typedEntry, exactScratch, prefixLength)) return
        autocorrectMatchCount = 0
        autocorrectMatchIndex = NO_ENTRY
        val emitted = FuzzyPrefixVariants.generateLongPressVariants(
            exactScratch, prefixLength, table, autocorrectCodePointScratch,
            autocorrectVariantScratch, MAX_FUZZY_VARIANTS, autocorrectConsumer,
        )
        // Fail closed on a budget overrun or malformed input, exactly like the display level: a
        // partially generated variant set could hide the second candidate that makes a typo
        // ambiguous, and acting on it would replace text on incomplete evidence.
        if (emitted < 0) return
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
     * neighbour, then #3 transposition), and only inside one class the frozen tie-break
     * (frequency descending, then code-point lexical ascending). Exact candidates are never
     * touched and always outrank any fuzzy candidate. Returns the total candidate count.
     *
     * WHICH edit classes run on the shipped live path is decided in EXACTLY ONE named place —
     * [SHIPPED_FUZZY_EDIT_CLASSES]. E3b measured both acceptance conditions unmet (PROPOSALS.md,
     * section "Контракт текста", line "Итог, 2026-07-27"; docs/DICTIONARY-E3.md), so only class #1
     * (long-press partner) ships. Classes #2 (geometric neighbour) and #3 (transposition) are
     * excluded from this live path and are therefore unreachable through lookup(); their generators
     * below stay in the tree as infrastructure and remain covered by direct-generator tests. A
     * class runs here only if its EDIT_CLASS_* value is in the shipped set — there is no per-request
     * state and no user-facing toggle.
     *
     * The whole fuzzy level is dropped (returns [exactCount]) if variant generation or the block
     * scan trips a fixed budget: the level is discarded in full, never in part.
     */
    private fun collectFuzzy(prefixLength: Int, table: KeyNeighborTable, exactCount: Int): Int {
        fuzzyExactCount = exactCount
        fuzzyRemaining = MAX_RESULTS - exactCount
        fuzzyCount = 0
        fuzzyVisited = 0
        fuzzyOverBudget = false
        fuzzyPrefixLength = prefixLength
        // The enabled classes share one variant budget: the total number of variants generated
        // across all of them must stay within MAX_FUZZY_VARIANTS. The edit class DOES affect ranking
        // (class #1 before #2 before #3, then frequency inside a class); each candidate is tagged
        // with fuzzyCurrentClass, set below before its class runs. Any single class returning -1
        // (its slice of the budget exceeded) drops the whole fuzzy level, never a part of it.
        var variantsUsed = 0

        if (EDIT_CLASS_LONG_PRESS in SHIPPED_FUZZY_EDIT_CLASSES) {
            fuzzyCurrentClass = EDIT_CLASS_LONG_PRESS
            val emitted = FuzzyPrefixVariants.generateLongPressVariants(
                exactScratch, prefixLength, table, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - variantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            variantsUsed += emitted
        }

        if (EDIT_CLASS_GEOMETRIC in SHIPPED_FUZZY_EDIT_CLASSES) {
            fuzzyCurrentClass = EDIT_CLASS_GEOMETRIC
            val emitted = FuzzyPrefixVariants.generateGeometricVariants(
                exactScratch, prefixLength, table, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - variantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            variantsUsed += emitted
        }

        if (EDIT_CLASS_TRANSPOSITION in SHIPPED_FUZZY_EDIT_CLASSES) {
            fuzzyCurrentClass = EDIT_CLASS_TRANSPOSITION
            val emitted = FuzzyPrefixVariants.generateTranspositionVariants(
                exactScratch, prefixLength, codePointScratch, variantScratch,
                MAX_FUZZY_VARIANTS - variantsUsed, fuzzyConsumer,
            )
            if (emitted < 0 || fuzzyOverBudget) {
                lastFuzzyOverBudget = true
                lastFuzzyVisitedCount = fuzzyVisited
                return exactCount
            }
            variantsUsed += emitted
        }

        lastFuzzyVariantCount = variantsUsed
        lastFuzzyVisitedCount = fuzzyVisited
        for (slot in 0 until fuzzyCount) {
            rankedIndices[exactCount + slot] = fuzzyIndices[slot]
            rankedFrequencies[exactCount + slot] = fuzzyFrequencies[slot]
            rankedClasses[exactCount + slot] = fuzzyClasses[slot]
        }
        return exactCount + fuzzyCount
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
            onEntry = { index, equalsQuery, _, _, _, _ ->
                fuzzyVisited++
                if (fuzzyVisited > MAX_FUZZY_VISITED) {
                    fuzzyOverBudget = true
                    return@scanBlockRange SCAN_ABORT
                }
                // Exact-word exclusion applies on both levels: never suggest the typed word itself.
                // A word is de-duplicated by dictionary index so it can never occupy two cells. Because
                // classes run in order (#1, then #2, then #3), the first class to reach a word keeps it,
                // which is also its best (lowest) class — consistent with the class-first ranking.
                if (!equalsQuery &&
                    !containsIndex(rankedIndices, fuzzyExactCount, index) &&
                    !containsIndex(fuzzyIndices, fuzzyCount, index)
                ) {
                    SCAN_TAKE
                } else {
                    SCAN_SKIP
                }
            },
            onFrequency = { index, frequency, _ ->
                fuzzyCount = insertRanked(
                    fuzzyIndices, fuzzyFrequencies, fuzzyClasses, fuzzyCount, fuzzyRemaining,
                    index, frequency, fuzzyCurrentClass,
                )
            },
        )
    }

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
        // Edit class first (ascending): #1 long-press < #2 geometric < #3 transposition. Exact
        // candidates all share EDIT_CLASS_EXACT, so this key is a tie among them and their frozen
        // order is untouched; only the fuzzy level, whose candidates carry distinct class values,
        // is reordered by it.
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
     * the P3 dual-track pass; [SCAN_ABORT] — stop the whole scan immediately (the fuzzy budget
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
                        SCAN_ABORT -> return
                    }
                }
                position++
            }
            val verdictMask = takeMask or stemMask
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
        // order is #1 long-press partner < #2 geometric neighbour < #3 transposition, applied ahead
        // of frequency by [ranksBefore]. The exact level always outranks the fuzzy level regardless
        // of these values, because exact and fuzzy candidates live in separate arrays and the exact
        // ones are merged first.
        private const val EDIT_CLASS_EXACT = 0
        internal const val EDIT_CLASS_LONG_PRESS = 1
        internal const val EDIT_CLASS_GEOMETRIC = 2
        internal const val EDIT_CLASS_TRANSPOSITION = 3

        // THE single switch that decides which edit classes reach the shipped fuzzy pass. E3b
        // measured both of its acceptance conditions unmet — the combined recovery@3 of classes
        // #1–#3 fell far below the 2.4x threshold — so classes #2 (geometric neighbour) and #3
        // (transposition) are excluded from the shipped live path and only class #1 (long-press
        // partner) ships. See PROPOSALS.md, section "Контракт текста", line "Итог, 2026-07-27", and
        // docs/DICTIONARY-E3.md. The #2/#3 generators, geometry map and instrumentation harness stay
        // in the tree as infrastructure and keep their direct tests; making them reachable again is
        // a one-line change to this set (add EDIT_CLASS_GEOMETRIC / EDIT_CLASS_TRANSPOSITION) — there
        // is deliberately no runtime state and no user-facing toggle. The ranking-by-edit-class order
        // in [ranksBefore] is retained unchanged; with a single shipped class it is a constant tie,
        // which is exactly why class #1 recovery is invariant to whether #2/#3 are present.
        internal val SHIPPED_FUZZY_EDIT_CLASSES = intArrayOf(EDIT_CLASS_LONG_PRESS)

        // Fuzzy pass. Extra headroom on the variant buffer covers a re-encode that is a few bytes
        // longer than the prefix; edit classes #1/#2 (single-letter substitution) and #3
        // (transposition) keep the code-point length identical in practice.
        private const val VARIANT_HEADROOM = 8

        // The fuzzy pass (all three E3b classes) needs at least three code points; the count is
        // taken off the UTF-8 lead bytes so a two-letter Cyrillic prefix (four bytes) is rejected.
        private const val MIN_FUZZY_PREFIX_CODE_POINTS = 3

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

        // scanBlockRange entry verdicts: not a candidate / candidate, report its frequency /
        // abort the whole scan (the fuzzy budget trip). SCAN_TAKE_STEM is the P3 dual-track
        // variant of SCAN_TAKE: candidate whose remainder is a known suffix of the injected table.
        private const val SCAN_SKIP = 0
        private const val SCAN_TAKE = 1
        private const val SCAN_ABORT = 2
        private const val SCAN_TAKE_STEM = 3
        private val MAGIC = "TATDICT\u0000".toByteArray(Charsets.US_ASCII)

        fun open(
            source: ByteBuffer,
            identity: DictionaryIdentity,
            expectedEntryCount: Long,
            expectedRawSize: Long,
            // P3: the same-stem boost table; null keeps the frozen D1 behavior byte-identical.
            suffixTable: InflectedSuffixTable? = null,
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
