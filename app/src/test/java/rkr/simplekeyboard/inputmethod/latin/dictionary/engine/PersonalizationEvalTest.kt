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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer

/**
 * The JVM mirror of the learn-then-type personalization harness
 * (`scripts/personalization_eval.py`), scoped to the graduation counters: the splits, the
 * clean-run priming simulator with its three observation arms (oracle / realistic /
 * fixed-deferred), and the two learning stores (a word graduates after 3 clean completions with
 * an empty-exact proof, a pair after 2 clean observations with a known context; pending
 * tables cap at 256 with lowest-serial eviction, entries at 2000/1000).
 *
 * The exact-pass emptiness signal comes from the real shipped index: a lookup whose
 * [TdictPrefixIndex.lastExactCount] is zero, exactly what `observeEmptyResult` consumes
 * (typo recovery off, as in the eval configuration). The pinned counters below are the
 * Python harness's printed values; both sides pin the same numbers, which is the parity
 * channel. Re-pin both together when the assets, the eval set or the harness constants
 * change.
 */
class PersonalizationEvalTest {

    /** PendingCounters keyed by the word itself; serials and the cap mirror the production. */
    private class PendingModel<K>(private val cap: Int) {
        private val counts = HashMap<K, Int>()
        private val serials = HashMap<K, Long>()
        private var nextSerial = 1L
        var evictions = 0
            private set

        fun note(key: K): Int {
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            serials[key] = nextSerial
            nextSerial++
            if (counts.size > cap) {
                val victim = lowestSerialKey()
                counts.remove(victim)
                serials.remove(victim)
                evictions++
            }
            return count
        }

        fun drop(key: K) {
            counts.remove(key)
            serials.remove(key)
        }

        /** Serials are unique per key, so the strict-less-than scan has one answer. */
        private fun lowestSerialKey(): K {
            var victim: K? = null
            var lowest = Long.MAX_VALUE
            for ((key, serial) in serials) {
                if (serial < lowest) {
                    lowest = serial
                    victim = key
                }
            }
            return victim!!
        }
    }

    /** Graduated personal words: usage counters and LRU serials (PersonalEntries). */
    private class WordEntries(private val cap: Int) {
        val usage = HashMap<String, Int>()
        private val serials = HashMap<String, Long>()
        private var nextSerial = 1L

        operator fun contains(word: String): Boolean = usage.containsKey(word)

        /** upsert: a new entry starts at usage 1; evicts the lowest serial past the cap. */
        fun add(word: String) {
            usage[word] = (usage[word] ?: 0) + 1
            serials[word] = nextSerial
            nextSerial++
            while (usage.size > cap) {
                var victim: String? = null
                var lowest = Long.MAX_VALUE
                for ((key, serial) in serials) {
                    if (serial < lowest) {
                        lowest = serial
                        victim = key
                    }
                }
                usage.remove(victim)
                serials.remove(victim)
            }
        }

        fun size(): Int = usage.size
    }

    /** Graduated pairs: usage and frequency counters plus LRU serials (PersonalBigramEntries). */
    private class PairEntries(private val cap: Int) {
        /** (usage, frequency) per successor, keyed by context. */
        val byContext = HashMap<String, HashMap<String, IntArray>>()
        private val serials = HashMap<Pair<String, String>, Long>()
        private var nextSerial = 1L

        operator fun contains(pair: Pair<String, String>): Boolean =
            byContext[pair.first]?.containsKey(pair.second) == true

        /** A new pair starts at usage 0 with the given frequency; bumps frequency if present. */
        fun upsert(context: String, word: String, frequencyDelta: Int) {
            val words = byContext.getOrPut(context) { HashMap() }
            val counters = words[word]
            if (counters == null) {
                words[word] = intArrayOf(0, frequencyDelta)
            } else {
                counters[1] += frequencyDelta
            }
            serials[context to word] = nextSerial
            nextSerial++
            while (serials.size > cap) {
                var victim: Pair<String, String>? = null
                var lowest = Long.MAX_VALUE
                for ((key, serial) in serials) {
                    if (serial < lowest) {
                        lowest = serial
                        victim = key
                    }
                }
                serials.remove(victim)
                val contextWords = byContext.getValue(victim!!.first)
                contextWords.remove(victim.second)
                if (contextWords.isEmpty()) byContext.remove(victim.first)
            }
        }

        /** One more clean typed observation of a graduated pair: frequency += 1. */
        fun noteObservation(context: String, word: String) {
            val counters = byContext[context]?.get(word) ?: return
            counters[1] += 1
            serials[context to word] = nextSerial
            nextSerial++
        }

        fun size(): Int = serials.size
    }

    /** The two learning stores over one priming pass; [knownWord] is the bundled dictionary. */
    private class PersonalModel(private val knownWord: (String) -> Boolean) {
        val words = WordEntries(WORD_CAP)
        val pairs = PairEntries(PAIR_CAP)
        private val wordPending = PendingModel<String>(PENDING_CAP)
        private val pairPending = PendingModel<Pair<String, String>>(PENDING_CAP)
        var wordCompletionsReported = 0L
            private set
        var wordCompletionsEligible = 0L
            private set
        var pairObservations = 0L
            private set
        var pairsDroppedUnknownContext = 0L
            private set

        val wordPendingEvictions: Int
            get() = wordPending.evictions
        val pairPendingEvictions: Int
            get() = pairPending.evictions

        fun noteCompletion(word: String) {
            wordCompletionsReported++
            if (!eligible(word, WORD_MIN_CODE_POINTS, WORD_MAX_CODE_POINTS)) return
            wordCompletionsEligible++
            if (word in words) return
            if (wordPending.note(word) >= WORD_LEARN_THRESHOLD) {
                words.add(word)
                wordPending.drop(word)
            }
        }

        fun notePair(context: String, word: String) {
            pairObservations++
            if (!eligible(context, PAIR_WORD_MIN_CODE_POINTS, PAIR_WORD_MAX_CODE_POINTS)) return
            if (!eligible(word, PAIR_WORD_MIN_CODE_POINTS, PAIR_WORD_MAX_CODE_POINTS)) return
            val pair = context to word
            if (pair in pairs) {
                pairs.noteObservation(context, word)
                return
            }
            if (pairPending.note(pair) >= PAIR_LEARN_THRESHOLD) {
                if (!knownWord(context) && context !in words) {
                    // The keyboard does not know the context word: the progress is dropped.
                    pairPending.drop(pair)
                    pairsDroppedUnknownContext++
                    return
                }
                pairs.upsert(context, word, PAIR_LEARN_THRESHOLD)
                pairPending.drop(pair)
            }
        }

        private fun eligible(word: String, minCodePoints: Int, maxCodePoints: Int): Boolean {
            // The eval set is BMP-only, so the UTF-16 length is the code-point count.
            if (word.length < minCodePoints || word.length > maxCodePoints) return false
            var offset = 0
            while (offset < word.length) {
                val codePoint = word.codePointAt(offset)
                if (codePoint !in ALPHABET) return false
                offset += Character.charCount(codePoint)
            }
            return true
        }
    }

    /**
     * Latest-only single-worker request queue with a fixed lookup latency in integer ms;
     * see the Python harness for the full contract. An answer counts only when it lands
     * strictly before the next request or text change; a CUTOFF event is a text change that
     * issues no request (the sentence-final separator).
     */
    private class CoalescingWorker(private val latencyMs: Long) {
        private class Request(
            var completeAt: Long,
            val kind: Int,
            val payload: String?,
            var alive: Boolean,
        )

        private var busy: Request? = null
        private var pending: Request? = null
        var issuedRequests = 0L
            private set
        var observedLookups = 0L
            private set
        var droppedLookups = 0L
            private set
        var staledLookups = 0L
            private set

        /** Advance to an event; returns the prefixes whose answers landed since the last one. */
        fun arrive(timeMs: Long, kind: Int, payload: String?): List<String> {
            val observed = ArrayList<String>()
            while (busy != null && busy!!.completeAt < timeMs) {
                val done = busy!!
                if (done.alive && done.kind == KIND_PREFIX) observed.add(done.payload!!)
                busy = null
                val next = pending
                if (next != null) {
                    pending = null
                    busy = Request(done.completeAt + latencyMs, next.kind, next.payload, next.alive)
                }
            }
            if (kind == KIND_CUTOFF) {
                busy?.let {
                    if (it.alive) staledLookups++
                    it.alive = false
                }
                pending?.let {
                    if (it.alive) staledLookups++
                    it.alive = false
                }
            } else {
                issuedRequests++
                val replaced = pending
                if (replaced != null) {
                    if (replaced.alive) droppedLookups++
                    pending = null
                }
                val inFlight = busy
                if (inFlight == null) {
                    busy = Request(timeMs + latencyMs, kind, payload, true)
                } else {
                    if (inFlight.alive) staledLookups++
                    inFlight.alive = false
                    pending = Request(-1L, kind, payload, true)
                }
            }
            observedLookups += observed.size
            return observed
        }
    }

    /** Keystroke gaps: a base interval plus a SplitMix64 jitter in [0, KEY_INTERVAL_JITTER_MS]. */
    private class IntervalStream {
        private var state = splitmix64(INTERVAL_SEED)

        fun next(): Long {
            val value = KEY_INTERVAL_BASE_MS +
                java.lang.Long.remainderUnsigned(state, KEY_INTERVAL_JITTER_MS + 1L)
            state = splitmix64(state)
            return value
        }
    }

    /** The counters of one priming pass, for the pin assertions and the PERS| lines. */
    private class PrimeOutcome {
        var observedLookups = 0L
        var issuedRequests = 0L
        var droppedLookups = 0L
        var staledLookups = 0L
        var emptyObservations = 0L
        var fixProbes = 0L
        var fixRecoveredWords = 0L
        var gradWords = 0
        var gradPairs = 0
        var wordCompletionsReported = 0L
        var wordCompletionsEligible = 0L
        var pairObservations = 0L
        var pairsDroppedUnknownContext = 0L
        var wordPendingEvictions = 0
        var pairPendingEvictions = 0
    }

    private enum class Arm { ORACLE, REALISTIC, FIXED }

    /** One finished priming pass: the learned state plus the counters. */
    private class PrimeRun(val model: PersonalModel, val outcome: PrimeOutcome)

    private fun armTag(arm: Arm): String = when (arm) {
        Arm.ORACLE -> "oracle"
        Arm.REALISTIC -> "realistic"
        Arm.FIXED -> "fixed"
    }

    /** True when no dictionary word other than [prefix] itself starts with [prefix]. */
    private fun exactEmpty(prefix: String): Boolean {
        val index = requireNotNull(dictionaryIndex)
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))
        return index.lastExactCount == 0
    }

    /** The deferred completion-time probe: binary-search the shortest proper prefix of [word]
     * whose exact pass is empty (emptiness is monotone in prefix length). */
    private fun shortestEmptyPrefixProbe(word: String): Pair<Int, Int> {
        var low = 1
        var high = word.length - 1
        var found = -1
        var probes = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            probes++
            if (exactEmpty(word.substring(0, middle))) {
                found = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return found to probes
    }

    /** Replay the prime stream through the clean-run model under one observation arm. */
    private fun runPrimePass(lines: List<String>, arm: Arm, latencyMs: Long): PrimeRun {
        val index = requireNotNull(dictionaryIndex)
        val model = PersonalModel { word -> index.frequencyOf(word) > 0L }
        val outcome = PrimeOutcome()
        val worker = if (arm == Arm.ORACLE) null else CoalescingWorker(latencyMs)
        val intervals = if (worker == null) null else IntervalStream()
        var clock = 0L
        for (line in lines) {
            val words = line.split(" ")
            for (position in words.indices) {
                val word = words[position]
                val length = word.length
                var shortestEmpty = -1
                if (worker == null) {
                    for (k in 1..length) {
                        outcome.observedLookups++
                        if (exactEmpty(word.substring(0, k))) {
                            outcome.emptyObservations++
                            if (shortestEmpty < 0 || k < shortestEmpty) shortestEmpty = k
                        }
                    }
                } else {
                    for (k in 1..length) {
                        clock += intervals!!.next()
                        for (prefix in worker.arrive(clock, KIND_PREFIX, word.substring(0, k))) {
                            if (exactEmpty(prefix)) {
                                outcome.emptyObservations++
                                if (shortestEmpty < 0 || prefix.length < shortestEmpty) {
                                    shortestEmpty = prefix.length
                                }
                            }
                        }
                    }
                    clock += intervals!!.next()
                    val kind = if (position + 1 < words.size) KIND_NEXT_WORD else KIND_CUTOFF
                    for (prefix in worker.arrive(clock, kind, null)) {
                        if (exactEmpty(prefix)) {
                            outcome.emptyObservations++
                            if (shortestEmpty < 0 || prefix.length < shortestEmpty) {
                                shortestEmpty = prefix.length
                            }
                        }
                    }
                }
                var qualified = shortestEmpty in 1 until length
                if (!qualified && arm == Arm.FIXED && index.frequencyOf(word) == 0L) {
                    // The deferred fix fires on unknown words only: a dictionary word's proper
                    // prefixes are all continued by the word itself.
                    val (found, probes) = shortestEmptyPrefixProbe(word)
                    outcome.fixProbes += probes
                    if (found >= 1) {
                        qualified = true
                        outcome.fixRecoveredWords++
                    }
                }
                if (qualified) model.noteCompletion(word)
                if (position > 0) model.notePair(words[position - 1], word)
            }
        }
        if (worker != null) {
            outcome.observedLookups = worker.observedLookups
            outcome.issuedRequests = worker.issuedRequests
            outcome.droppedLookups = worker.droppedLookups
            outcome.staledLookups = worker.staledLookups
        } else {
            outcome.issuedRequests = outcome.observedLookups
        }
        outcome.gradWords = model.words.size()
        outcome.gradPairs = model.pairs.size()
        outcome.wordCompletionsReported = model.wordCompletionsReported
        outcome.wordCompletionsEligible = model.wordCompletionsEligible
        outcome.pairObservations = model.pairObservations
        outcome.pairsDroppedUnknownContext = model.pairsDroppedUnknownContext
        outcome.wordPendingEvictions = model.wordPendingEvictions
        outcome.pairPendingEvictions = model.pairPendingEvictions
        return PrimeRun(model, outcome)
    }

    /** SplitMix64 sentence-hash halves: `splitmix64(SPLIT_SEED ^ fnv1a64(line)) % 2`. */
    private fun hashHalves(lines: List<String>): Pair<List<String>, List<String>> =
        lines.partition { line ->
            java.lang.Long.remainderUnsigned(
                splitmix64(SPLIT_SEED xor fnv1a64(line.toByteArray(Charsets.UTF_8))), 2L,
            ) == 0L
        }

    private fun splitStreams(split: String, lines: List<String>): Pair<List<String>, List<String>> =
        when (split) {
            "hash" -> hashHalves(lines)
            "chrono" ->
                lines.subList(0, lines.size / 2) to lines.subList(lines.size / 2, lines.size)
            "rep" -> buildList { repeat(REP_PRIME_PASSES) { addAll(lines) } } to lines
            else -> error("unknown split: $split")
        }

    /** The longest proper split into a dictionary stem plus a runtime-table suffix, or null. */
    private fun longestStemWithSuffixRemainder(index: TdictPrefixIndex, word: String): String? {
        for (cut in word.length - 1 downTo 1) {
            val remainder = word.substring(cut)
            if (!TatarSuffixRules.isInflectedContinuation(remainder)) continue
            val stem = word.substring(0, cut)
            if (index.frequencyOf(stem) > 0L) return stem
        }
        return null
    }

    private fun emit(name: String, value: Any) = println("PERS|$name|$value")

    @Test
    fun graduationCountersMatchThePythonHarness() {
        for (split in SPLITS) {
            for (arm in ARM_ORDER) {
                val prefix = "$split.${armTag(arm)}"
                val outcome = PRIME_RUNS.getValue(split to arm).outcome
                emit("$prefix.observed_lookups", outcome.observedLookups)
                emit("$prefix.empty_observations", outcome.emptyObservations)
                emit("$prefix.word_completions_reported", outcome.wordCompletionsReported)
                emit("$prefix.word_completions_eligible", outcome.wordCompletionsEligible)
                emit("$prefix.pair_observations", outcome.pairObservations)
                emit(
                    "$prefix.pairs_dropped_unknown_context",
                    outcome.pairsDroppedUnknownContext,
                )
                emit("$prefix.word_pending_evictions", outcome.wordPendingEvictions)
                emit("$prefix.pair_pending_evictions", outcome.pairPendingEvictions)
                emit("$prefix.grad_words", outcome.gradWords)
                emit("$prefix.grad_pairs", outcome.gradPairs)
                if (arm == Arm.FIXED) emit("$prefix.fix_probes", outcome.fixProbes)
                assertEquals(prefix, PIN_GRAD_WORDS.getValue(prefix), outcome.gradWords)
                assertEquals(prefix, PIN_GRAD_PAIRS.getValue(prefix), outcome.gradPairs)
                assertEquals(prefix, PIN_OBSERVED_LOOKUPS.getValue(prefix), outcome.observedLookups)
                assertEquals(
                    prefix, PIN_EMPTY_OBSERVATIONS.getValue(prefix), outcome.emptyObservations
                )
                assertEquals(
                    prefix, PIN_WORD_COMPLETIONS.getValue(split), outcome.wordCompletionsReported
                )
                assertEquals(
                    prefix, PIN_PAIR_OBSERVATIONS.getValue(split), outcome.pairObservations
                )
                assertEquals(
                    prefix,
                    PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT.getValue(split),
                    outcome.pairsDroppedUnknownContext,
                )
                assertEquals(
                    prefix, PIN_WORD_PENDING_EVICTIONS.getValue(split),
                    outcome.wordPendingEvictions.toLong(),
                )
                assertEquals(
                    prefix, PIN_PAIR_PENDING_EVICTIONS.getValue(split),
                    outcome.pairPendingEvictions.toLong(),
                )
                if (arm == Arm.FIXED) {
                    assertEquals(prefix, PIN_FIX_PROBES.getValue(split), outcome.fixProbes)
                }
            }
        }
    }

    @Test
    fun armOrderingGates() {
        for (split in SPLITS) {
            val oracle = PRIME_RUNS.getValue(split to Arm.ORACLE).outcome.gradWords
            val realistic = PRIME_RUNS.getValue(split to Arm.REALISTIC).outcome.gradWords
            val fixed = PRIME_RUNS.getValue(split to Arm.FIXED).outcome.gradWords
            assertTrue("$split: oracle $oracle >= realistic $realistic", oracle >= realistic)
            // On clean streams the completion-time probe sees the same monotone predicate,
            // so the fixed arm matches the oracle arm exactly.
            assertEquals("$split: fixed == oracle", oracle, fixed)
        }
    }

    @Test
    fun a7CoverageGateOnUnseenStemTokens() {
        val index = requireNotNull(dictionaryIndex)
        for (split in SPLITS) {
            val (prime, _) = splitStreams(split, evalLines)
            val unseen = HashSet<String>()
            var unseenTokens = 0L
            for (line in prime) {
                for (word in line.split(" ")) {
                    if (index.frequencyOf(word) == 0L &&
                        longestStemWithSuffixRemainder(index, word) == null
                    ) {
                        unseen.add(word)
                    }
                }
            }
            for (line in prime) {
                for (word in line.split(" ")) {
                    if (word in unseen) unseenTokens++
                }
            }
            val coveredByArm = HashMap<Arm, Long>()
            for (arm in ARM_ORDER) {
                val model = PRIME_RUNS.getValue(split to arm).model
                var covered = 0L
                for (line in prime) {
                    for (word in line.split(" ")) {
                        if (word in unseen && word in model.words) covered++
                    }
                }
                coveredByArm[arm] = covered
            }
            val oracle = coveredByArm.getValue(Arm.ORACLE)
            val realistic = coveredByArm.getValue(Arm.REALISTIC)
            val fixed = coveredByArm.getValue(Arm.FIXED)
            emit("$split.a7.oracle_unseen_tokens", oracle)
            emit("$split.a7.realistic_unseen_tokens", realistic)
            emit("$split.a7.fixed_unseen_tokens", fixed)
            emit("$split.prime_unseen_tokens", unseenTokens)
            val rhsX1000 = realistic * 1000 + 900 * (oracle - realistic)
            emit("$split.a7.rhs_x1000", rhsX1000)
            emit("$split.a7.gate_pass", if (fixed * 1000 >= rhsX1000) 1 else 0)
            assertEquals(split, PIN_PRIME_UNSEEN_TOKENS.getValue(split), unseenTokens)
            assertEquals(split, PIN_A7_UNSEEN.getValue(split), listOf(oracle, realistic, fixed))
            assertTrue("$split: deferred-observation gate", fixed * 1000 >= rhsX1000)
        }
    }

    @Test
    fun realisticSweepAtExtremeLatenciesMatchesThePythonHarness() {
        val (prime, _) = splitStreams("rep", evalLines)
        val slowCell = runPrimePass(prime, Arm.REALISTIC, 100L).outcome
        emit("rep.sweep_l100.grad_words", slowCell.gradWords)
        emit("rep.sweep_l100.observed_lookups", slowCell.observedLookups)
        emit("rep.sweep_l100.dropped_lookups", slowCell.droppedLookups)
        emit("rep.sweep_l100.staled_lookups", slowCell.staledLookups)
        assertEquals(PIN_REP_SWEEP_L100_GRAD_WORDS, slowCell.gradWords)
        assertEquals(PIN_REP_SWEEP_L100_OBSERVED, slowCell.observedLookups)
        assertEquals(PIN_REP_SWEEP_L100_DROPPED, slowCell.droppedLookups)
        assertEquals(PIN_REP_SWEEP_L100_STALED, slowCell.staledLookups)
        val deadCell = runPrimePass(prime, Arm.REALISTIC, 200L).outcome
        emit("rep.sweep_l200.grad_words", deadCell.gradWords)
        emit("rep.sweep_l200.observed_lookups", deadCell.observedLookups)
        emit("rep.sweep_l200.dropped_lookups", deadCell.droppedLookups)
        emit("rep.sweep_l200.staled_lookups", deadCell.staledLookups)
        assertEquals(PIN_REP_SWEEP_L200_GRAD_WORDS, deadCell.gradWords)
        assertEquals(PIN_REP_SWEEP_L200_OBSERVED, deadCell.observedLookups)
        assertEquals(PIN_REP_SWEEP_L200_DROPPED, deadCell.droppedLookups)
        assertEquals(PIN_REP_SWEEP_L200_STALED, deadCell.staledLookups)
    }

    companion object {
        // Harness constants, shared with scripts/personalization_eval.py; changing them
        // re-pins every counter on both sides.
        private const val SPLIT_SEED = 20261028L
        private const val INTERVAL_SEED = 20261029L
        private const val KEY_INTERVAL_BASE_MS = 80L
        private const val KEY_INTERVAL_JITTER_MS = 120L
        private const val LOOKUP_LATENCY_MS = 16L
        private const val REP_PRIME_PASSES = 3

        // Production learning knobs (PendingCounters, PersonalBigramStore, Tpers/TpersbFormat).
        private const val WORD_LEARN_THRESHOLD = 3
        private const val PAIR_LEARN_THRESHOLD = 2
        private const val PENDING_CAP = 256
        private const val WORD_CAP = 2_000
        private const val PAIR_CAP = 1_000
        private const val WORD_MIN_CODE_POINTS = 3
        private const val WORD_MAX_CODE_POINTS = 24
        private const val PAIR_WORD_MIN_CODE_POINTS = 1
        private const val PAIR_WORD_MAX_CODE_POINTS = 24

        private const val KIND_PREFIX = 0
        private const val KIND_NEXT_WORD = 1
        private const val KIND_CUTOFF = 2

        private val SPLITS = listOf("hash", "chrono", "rep")
        private val ARM_ORDER = listOf(Arm.ORACLE, Arm.REALISTIC, Arm.FIXED)
        private val ALPHABET: Set<Int> = PersonalSubtypes.TATAR_RU_ALPHABET

        // Graduation-counter pins, shared with tests/personalization_eval. The hash/chrono
        // halves carry no repeated word, so no word reaches the learn threshold of 3 there.
        private val PIN_GRAD_WORDS = mapOf(
            "hash.oracle" to 0, "hash.realistic" to 0, "hash.fixed" to 0,
            "chrono.oracle" to 0, "chrono.realistic" to 0, "chrono.fixed" to 0,
            "rep.oracle" to 197, "rep.realistic" to 197, "rep.fixed" to 197,
        )
        private val PIN_GRAD_PAIRS = mapOf(
            "hash.oracle" to 41, "hash.realistic" to 41, "hash.fixed" to 41,
            "chrono.oracle" to 42, "chrono.realistic" to 42, "chrono.fixed" to 42,
            "rep.oracle" to 82, "rep.realistic" to 82, "rep.fixed" to 82,
        )
        private val PIN_OBSERVED_LOOKUPS = mapOf(
            "hash.oracle" to 14_783L, "hash.realistic" to 14_783L, "hash.fixed" to 14_783L,
            "chrono.oracle" to 14_541L, "chrono.realistic" to 14_541L, "chrono.fixed" to 14_541L,
            "rep.oracle" to 86_991L, "rep.realistic" to 86_991L, "rep.fixed" to 86_991L,
        )
        private val PIN_EMPTY_OBSERVATIONS = mapOf(
            "hash.oracle" to 875L, "hash.realistic" to 875L, "hash.fixed" to 875L,
            "chrono.oracle" to 830L, "chrono.realistic" to 830L, "chrono.fixed" to 830L,
            "rep.oracle" to 4_953L, "rep.realistic" to 4_953L, "rep.fixed" to 4_953L,
        )
        private val PIN_WORD_COMPLETIONS = mapOf(
            "hash" to 115L, "chrono" to 95L, "rep" to 603L,
        )
        private val PIN_PAIR_OBSERVATIONS = mapOf(
            "hash" to 2_209L, "chrono" to 2_173L, "rep" to 13_005L,
        )
        private val PIN_PAIRS_DROPPED_UNKNOWN_CONTEXT = mapOf(
            "hash" to 0L, "chrono" to 0L, "rep" to 1L,
        )
        private val PIN_WORD_PENDING_EVICTIONS = mapOf(
            "hash" to 0L, "chrono" to 0L, "rep" to 0L,
        )
        private val PIN_PAIR_PENDING_EVICTIONS = mapOf(
            "hash" to 1_852L, "chrono" to 1_800L, "rep" to 11_997L,
        )
        private val PIN_FIX_PROBES = mapOf(
            "hash" to 52L, "chrono" to 40L, "rep" to 342L,
        )
        private val PIN_PRIME_UNSEEN_TOKENS = mapOf(
            "hash" to 83L, "chrono" to 72L, "rep" to 447L,
        )
        private val PIN_A7_UNSEEN = mapOf(
            "hash" to listOf(0L, 0L, 0L),
            "chrono" to listOf(0L, 0L, 0L),
            "rep" to listOf(405L, 405L, 405L),
        )

        private const val PIN_REP_SWEEP_L100_GRAD_WORDS = 152
        private const val PIN_REP_SWEEP_L100_OBSERVED = 70_377L
        private const val PIN_REP_SWEEP_L100_DROPPED = 0L
        private const val PIN_REP_SWEEP_L100_STALED = 19_105L
        private const val PIN_REP_SWEEP_L200_GRAD_WORDS = 0
        private const val PIN_REP_SWEEP_L200_OBSERVED = 0L
        private const val PIN_REP_SWEEP_L200_DROPPED = 28_247L
        private const val PIN_REP_SWEEP_L200_STALED = 71_749L

        private lateinit var evalLines: List<String>
        private var dictionaryIndex: TdictPrefixIndex? = null
        private lateinit var PRIME_RUNS: Map<Pair<String, Arm>, PrimeRun>

        /** One SplitMix64 output for [state] (matches scripts/typo_pack.py bit for bit). */
        private fun splitmix64(state: Long): Long {
            var z = state + 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        private fun fnv1a64(data: ByteArray): Long {
            var hash = 0xCBF29CE484222325uL.toLong()
            for (byte in data) {
                hash = hash xor (byte.toLong() and 0xffL)
                hash *= 0x100000001B3L
            }
            return hash
        }

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndEvalSet() {
            evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val dictSpec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val dictAsset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val dictRawFile = File.createTempFile("tt-personalization-eval-dict-", ".tdict")
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
                dictionaryIndex = requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(raw), identity,
                        validated.entryCount, validated.rawSize, TatarSuffixRules,
                        FuzzyEditPolicy.TATAR, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                )
                // The eval configuration: typo recovery off.
                dictionaryIndex?.updateKeyNeighbors(null)
            } finally {
                dictRawFile.delete()
            }

            // The nine priming passes are independent of each other and of test order.
            val runs = LinkedHashMap<Pair<String, Arm>, PrimeRun>()
            val harness = PersonalizationEvalTest()
            for (split in SPLITS) {
                val (prime, _) = harness.splitStreams(split, evalLines)
                for (arm in ARM_ORDER) {
                    runs[split to arm] = harness.runPrimePass(prime, arm, LOOKUP_LATENCY_MS)
                }
            }
            PRIME_RUNS = runs
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")
    }
}
