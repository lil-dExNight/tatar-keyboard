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

package rkr.simplekeyboard.inputmethod.latin.glide

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The glide decoder: a SHARK2-style statistical classifier over the dictionary.
 *
 * The algorithm follows the AnySoftKeyboard PR #1870 parameter study (Etienne Desticourt's
 * write-up) as implemented by FlorisBoard's `StatisticalGlideTypingClassifier` (Apache-2.0,
 * (C) the FlorisBoard contributors). The math is ported with attribution; the code is new:
 * fixed scratch buffers, no allocations after warmup, no candidates on degenerate input. The
 * channel sigmas and the frequency exponent are tuned on a synthetic gesture set.
 *
 * Pipeline of one [decode]:
 *  1. Extremity pruning: the keys nearest the gesture's start (up to
 *     [GlideConstants.startExtremityNeighbors]) and its end (up to
 *     [GlideConstants.endExtremityNeighbors]) select the start x end buckets of the
 *     [GlideWordIndex].
 *  2. Length pruning: a candidate stays only when its plain or looped ideal-path length lies
 *     within [GlideConstants.lengthThreshold] x key radius of the gesture's length.
 *  3. Scoring against the candidate's ideal path (the looped variant when the word has a
 *     doubled letter, the plain one otherwise; a twinless doubled word keeps the better of
 *     both, see [scoreCandidate]): shape distance (bbox-normalized pointwise L1
 *     over the resampled paths, Gaussian with [GlideConstants.shapeStd]) x location distance
 *     (pointwise x/y errors, two Gaussians with [GlideConstants.locationStdXFactor] /
 *     [GlideConstants.locationStdYFactor] sigmas x key radius)
 *     x a frequency weight. The location sigmas widen when the gesture is faster than the
 *     candidate's normative duration — its Fitts-law duration over letter transitions (the
 *     speed channel, see [scoreVariant]). A per-key dwell
 *     mask marks locally slow segments relative to the gesture's average speed; dwell at a doubled
 *     letter admits the plain ideal path and applies a likelihood ratio. The confidence competes
 *     for the top-N; cheap fail-fast checks (frequency alone, then shape alone) skip the expensive
 *     channels when a candidate cannot
 *     reach the current k-th worst score.
 *
 * Scoring is one fused loop: the ideal path's arc-equidistant points are produced on the fly
 * by the resampler's segment walk (segment lengths cached by the path writer), and both
 * channels accumulate against them. The shape channel sums L1, not L2 (no sqrt per point),
 * and both paths normalize by their raw bbox, which the loop needs before it starts.
 *
 * `decode(path, out)` is deterministic (score ties break on dictionary order, which the CSR
 * buckets preserve), allocates nothing after warmup except the result strings, and returns no
 * candidates, never an exception, on empty or fully pruned input.
 *
 * Threading: an instance is worker-confined like the dictionary index it reads. The lazily
 * built word index is immutable and published through a @Volatile reference, which is all a
 * racing first decode needs.
 */
class GlideDecoder(
    private val geometry: GlideKeyGeometry,
    private val inventory: GlideWordInventory,
    private val constants: GlideConstants = GlideConstants(),
) {
    /**
     * Tuning knobs of the classifier. [lengthThreshold], [sampleCount] and [frequencyWeight]
     * carry the AnySoftKeyboard PR #1870 study values (as ported through FlorisBoard); the
     * study's 2-neighbor extremity prune is widened to 3 here (the measured lift-off overshoot
     * costs recall at 2). The sigmas, the frequency exponent, the speed channel and the length
     * channel are tuned per language on the train split of the synthetic gesture set; the
     * constructor defaults are the Tatar set, and [RUSSIAN] carries the Russian set.
     */
    class GlideConstants(
        /** Gaussian sigma of the shape channel, in bbox-normalized units x sample count. */
        val shapeStd: Float = 11.04f,
        /**
         * Gaussian sigma of the location channel's x error, as a factor of the key radius. The
         * keyboard is wide and flat: a user's horizontal error (row drift, wide target keys)
         * spreads wider than the vertical one, so the channel splits its sigma per axis.
         */
        val locationStdXFactor: Float = 0.24f,
        /**
         * Gaussian sigma of the location channel's y error, as a factor of the key radius; see
         * [locationStdXFactor]. The pair (x, y) is tuned per language on the train split.
         */
        val locationStdYFactor: Float = 0.13f,
        /** Widening cap of the location sigma under a fast gesture; 1f disables the adaptation. */
        val speedWidenMax: Float = 1.5f,
        /** Minimum duration of a slow segment that can contribute to the per-key dwell mask. */
        val dwellMinDurationMs: Float = 30f,
        /** A segment dwells when its speed is below this fraction of the whole gesture's speed. */
        val dwellSpeedRatio: Float = 0.5f,
        /** Likelihood ratio for a doubled candidate when its doubled key carries dwell evidence. */
        val doubledDwellLikelihoodRatio: Float = 4f,
        /**
         * Hard cutoff of the location channel, in UNWIDENED sigmas: a candidate is out once its
         * mean per-point location error passes it — meanX^2/sigmaX^2 + meanY^2/sigmaY^2 > C^2,
         * each axis measured against its own sigma — whatever the speed channel did to the sigma.
         * Without it, a widened sigma could rescue a candidate whose probability underflowed to
         * zero — a gesture far off the keys must keep returning nothing.
         */
        val locationCutoffSigmas: Float = 8f,
        /**
         * Sigma of the length channel, as a factor of the key radius: the score gains the
         * unnormalized Gaussian bump exp(-(userLength - idealLength)^2 / (2 sigma^2)), which peaks
         * at 1, so the fail-fast bounds are untouched. The hard length prune
         * ([lengthThreshold]) stays. [Float.POSITIVE_INFINITY] disables the channel.
         */
        val lengthStdFactor: Float = 6f,
        /** Length-pruning threshold, as a factor of the key radius. */
        val lengthThreshold: Float = 8.42f,
        /** Resampled path resolution shared by both channels. */
        val sampleCount: Int = 200,
        /**
         * Keys considered nearest to the gesture's START in extremity pruning. The touch-down
         * offset is the smaller of the two endpoint errors, so the start keeps the study's
         * 2-neighbor set; the bucket-count budget (a hard host-decode p95) is spent where the
         * recall is: the lift-off.
         */
        val startExtremityNeighbors: Int = 2,
        /**
         * Keys considered nearest to the gesture's END (lift-off) in extremity pruning: real
         * lift-offs overshoot the last key's center by up to a radius, so 3 neighbors keep the
         * true end key in the bucket set (measured on the calibration set: the 2-neighbor set
         * loses ~6 % of plain targets before ranking).
         */
        val endExtremityNeighbors: Int = 3,
        /** Scale of the frequency weight: weight = frequencyWeight x (frequency/maxFrequency)^gamma. */
        val frequencyWeight: Float = 255f,
        /**
         * Dynamic-range compressor of the frequency weight. The reference implementations store
         * frequencies quantized to a 0..255 byte, which bounds the weight ratio of any two words
         * to 255:1; raw dictionary counts span 1..10^6 and would drown the shape and location
         * channels entirely. gamma = 1 is linear, gamma = 0 makes the channel uniform; 0.25
         * keeps frequency a mild prior (tuned on the train split).
         */
        val frequencyExponent: Float = 0.25f,
        /**
         * The bigram channel's per-rank penalty on the glide N-best: a successor of the previous
         * committed word multiplies its confidence by rankPenalty^rank, an absent candidate ranks
         * one past the last successor. 1f disables the channel (the context-free decode).
         */
        val bigramRankPenalty: Float = 3.0f,
    ) {
        companion object {
            /** The Tatar engine's constants (the constructor defaults; tuned on the Tatar train split). */
            @JvmField
            val TATAR = GlideConstants()

            /** The Russian engine's constants (tuned on the Russian train split). */
            @JvmField
            val RUSSIAN = GlideConstants(
                shapeStd = 8.28f,
                locationStdXFactor = 0.22f,
                locationStdYFactor = 0.12f,
            )
        }
    }

    @Volatile
    private var wordIndex: GlideWordIndex? = null

    /** Preloads a shared prebuilt index (the calibration grid skips a rebuild per cell). */
    internal fun preloadIndex(index: GlideWordIndex) {
        wordIndex = index
    }

    /** Builds the index without decoding a path. */
    internal fun prewarmIndex() {
        index()
    }

    private fun index(): GlideWordIndex {
        wordIndex?.let { return it }
        return synchronized(this) {
            wordIndex ?: GlideWordIndex.build(inventory, geometry).also { wordIndex = it }
        }
    }

    // Decode scratch, allocated once; decode itself allocates nothing but the result strings.
    private val userX = FloatArray(constants.sampleCount)
    private val userY = FloatArray(constants.sampleCount)
    private val userNX = FloatArray(constants.sampleCount)
    private val userNY = FloatArray(constants.sampleCount)
    private val idealX = FloatArray(GlideIdealPaths.MAX_POINTS)
    private val idealY = FloatArray(GlideIdealPaths.MAX_POINTS)
    // The ideal polyline's segment lengths and bbox, written alongside the points (fused loop).
    private val idealSegLens = FloatArray(GlideIdealPaths.MAX_POINTS)
    private val idealInvSegLens = FloatArray(GlideIdealPaths.MAX_POINTS)
    private val idealStats = FloatArray(4)
    private val startKeys: IntArray = IntArray(constants.startExtremityNeighbors)
    private val endKeys: IntArray = IntArray(constants.endExtremityNeighbors)
    private val topScores = FloatArray(TOP_N)
    private val topEntries = IntArray(TOP_N)
    // The extremity-pruning merge arms: nearest start keys x nearest end keys. The head entry
    // and frequency of each arm are cached (an arm's head changes only when the arm advances),
    // so the per-candidate selection scan compares cached values, not fresh CSR reads.
    private val mergeArmCapacity =
        constants.startExtremityNeighbors * constants.endExtremityNeighbors
    private val mergeEnd = IntArray(mergeArmCapacity)
    private val mergeCursor = IntArray(mergeArmCapacity)
    private val mergeHeadEntry = IntArray(mergeArmCapacity)
    private val mergeHeadFrequency = LongArray(mergeArmCapacity)
    private val dwellMask = LongArray((geometry.keyCount + 63) ushr 6)
    private val dwellKey = IntArray(1)
    private var dwellMaskValid = false
    private var dwellMaskAny = false

    // Test-only observability, worker-confined like the decode itself: candidates visited after
    // extremity pruning and candidates fully scored (the fail-fast checks skip the rest).
    internal var lastCandidateCount = 0
        private set
    internal var lastScoredCount = 0
        private set


    /**
     * Decodes [path] into [out] (reset on entry) and returns the candidate count, best first.
     * Fewer than two path points, an empty geometry or an all-pruned candidate space yield 0,
     * and the strip shows nothing for the gesture.
     */
    fun decode(path: GlidePath, out: GlideResult): Int {
        out.reset()
        lastCandidateCount = 0
        lastScoredCount = 0
        dwellMaskValid = false
        dwellMaskAny = false
        if (path.size < 2 || geometry.isEmpty) return 0
        val index = index()
        if (index.wordCount == 0) return 0

        val samples = constants.sampleCount
        GlideResampler.resample(path.xs, path.ys, path.size, userX, userY, samples)
        // The shape channel normalizes by the raw path's bounding box, not the resampled one:
        // the fused scoring loop needs the factors before it starts, and resampling can miss an
        // extremal vertex. The ideal side uses its raw polyline bbox the same way.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until path.size) {
            val x = path.xs[i]
            val y = path.ys[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val userWidth = maxX - minX
        val userHeight = maxY - minY
        GlideResampler.normalizeByBoxSide(
            userX, userY, samples, minX, maxX, minY, maxY, userNX, userNY,
        )
        val userLength = path.length()

        val radius = geometry.keyRadius
        val maxDistance = constants.lengthThreshold * radius
        // Anisotropic location sigmas: x and y carry their own Gaussian.
        val locationStdX = constants.locationStdXFactor * radius
        val locationStdY = constants.locationStdYFactor * radius
        // The speed channel: the gesture's wall-clock duration. A nonpositive or NaN duration
        // (a digitizer that repeats timestamps) leaves the location sigma unadapted.
        val userDurationMs = path.ts[path.size - 1] - path.ts[0]
        val lengthStd = constants.lengthStdFactor * radius
        val lengthInvTwoSigmaSq = (1.0 / (2.0 * lengthStd * lengthStd)).toFloat()
        // Gaussian factors: p = factor * exp(-distance^2 * invTwoSigmaSq); the location channel's
        // peak is the PRODUCT of the per-axis peaks, which the fail-fast checks consume.
        val shapeFactor = (1.0 / (constants.shapeStd * SQRT_2_PI)).toFloat()
        val locationXFactor = (1.0 / (locationStdX * SQRT_2_PI)).toFloat()
        val locationYFactor = (1.0 / (locationStdY * SQRT_2_PI)).toFloat()
        val shapeInvTwoSigmaSq =
            (1.0 / (2.0 * constants.shapeStd * constants.shapeStd)).toFloat()
        val locationXInvTwoSigmaSq =
            (1.0 / (2.0 * locationStdX * locationStdX)).toFloat()
        val locationYInvTwoSigmaSq =
            (1.0 / (2.0 * locationStdY * locationStdY)).toFloat()
        val maxChannelProduct = shapeFactor * locationXFactor * locationYFactor

        val startCount = geometry.findClosestKeys(path.firstX, path.firstY, startKeys)
        val endCount = geometry.findClosestKeys(path.lastX, path.lastY, endKeys)
        topScores.fill(Float.POSITIVE_INFINITY)
        var topCount = 0
        // The arm-tail floor: within one arm the entries are frequency-descending, so once an
        // arm's head frequency cannot pass fail-fast #1 even with the dwell ratio's headroom,
        // the arm's whole tail cannot either — the arm is exhausted. Verdict-exact (the floor
        // rounds down to the equality bound, so only entries the per-candidate check also
        // rejects are cut). A plain local recompute, no closure: the decode path must not
        // allocate.
        var armFrequencyFloor = 0L
        // The pair buckets hold their entries frequency-first (build-time sort), and the visit
        // merges the ≤ start x end neighbor-count arms into one global frequency-descending walk:
        // fail-fast bound tightens after the first few scored candidates, so most survivors are
        // rejected without ever paying the scoring cost. Verdict-exact (a rejected candidate
        // cannot reach the top-N even with a perfect match) and deterministic (frequency, then
        // entry index).
        var armCount = 0
        for (s in 0 until startCount) {
            for (e in 0 until endCount) {
                val rangeStart = index.pairRangeStart(startKeys[s], endKeys[e])
                val rangeEnd = index.pairRangeEnd(startKeys[s], endKeys[e])
                if (rangeStart < rangeEnd) {
                    mergeEnd[armCount] = rangeEnd
                    mergeCursor[armCount] = rangeStart
                    val headEntry = index.pairEntryAt(rangeStart)
                    mergeHeadEntry[armCount] = headEntry
                    mergeHeadFrequency[armCount] = index.frequencyAt(headEntry)
                    armCount++
                }
            }
        }
        while (true) {
            var bestArm = -1
            var bestFrequency = -1L
            var bestEntry = Int.MAX_VALUE
            for (arm in 0 until armCount) {
                if (mergeCursor[arm] >= mergeEnd[arm]) continue
                val frequency = mergeHeadFrequency[arm]
                if (frequency <= armFrequencyFloor) {
                    // The arm's tail is frequency-descending past the floor: exhaust it.
                    mergeCursor[arm] = mergeEnd[arm]
                    continue
                }
                val entry = mergeHeadEntry[arm]
                if (frequency > bestFrequency || (frequency == bestFrequency && entry < bestEntry)) {
                    bestArm = arm
                    bestFrequency = frequency
                    bestEntry = entry
                }
            }
            if (bestArm < 0) break
            val nextCursor = mergeCursor[bestArm] + 1
            mergeCursor[bestArm] = nextCursor
            if (nextCursor < mergeEnd[bestArm]) {
                val headEntry = index.pairEntryAt(nextCursor)
                mergeHeadEntry[bestArm] = headEntry
                mergeHeadFrequency[bestArm] = index.frequencyAt(headEntry)
            }
            val entry = bestEntry
            lastCandidateCount++
            val plain = index.plainLengthAt(entry)
            val looped = index.loopLengthAt(entry)
            if (abs(userLength - plain) >= maxDistance &&
                (looped < 0f || abs(userLength - looped) >= maxDistance)
            ) {
                continue
            }
            val previousWorst = if (topCount == TOP_N) topScores[TOP_N - 1] else Float.NaN
            topCount = scoreCandidate(
                path, index, entry, topCount, shapeFactor,
                locationXFactor, locationYFactor,
                shapeInvTwoSigmaSq, locationXInvTwoSigmaSq, locationYInvTwoSigmaSq,
                maxChannelProduct, userDurationMs, userLength, lengthInvTwoSigmaSq,
            )
            if (topCount == TOP_N && topScores[TOP_N - 1] != previousWorst) {
                // Reject when maxChannelProduct x frequencyWeight x dwellRatio <=
                // 1/topScores[k-1]; solve for the frequency at equality (gamma root in double
                // precision, floor-rounded so the cut stays inside the per-candidate check).
                val needed = 1.0 / (
                    topScores[TOP_N - 1].toDouble() * maxChannelProduct *
                        constants.frequencyWeight * constants.doubledDwellLikelihoodRatio
                    )
                armFrequencyFloor = if (needed >= 1.0) 0L else {
                    val root = Math.pow(needed, 1.0 / constants.frequencyExponent)
                    Math.floor(index.maxFrequency * root).toLong()
                }
            }
        }

        for (slot in 0 until topCount) {
            out.words[slot] = inventory.wordAt(topEntries[slot])
            out.scores[slot] = topScores[slot]
        }
        out.count = topCount
        return topCount
    }

    /**
     * Scores one length-surviving candidate against the resampled user gesture and inserts it
     * into the top-N when it beats the current k-th worst. Returns the new top count.
     */
    private fun scoreCandidate(
        path: GlidePath,
        index: GlideWordIndex,
        entry: Int,
        topCount: Int,
        shapeFactor: Float,
        locationXFactor: Float,
        locationYFactor: Float,
        shapeInvTwoSigmaSq: Float,
        locationXInvTwoSigmaSq: Float,
        locationYInvTwoSigmaSq: Float,
        maxChannelProduct: Float,
        userDurationMs: Float,
        userLength: Float,
        lengthInvTwoSigmaSq: Float,
    ): Int {
        val samples = constants.sampleCount
        val frequencyRatio = index.frequencyAt(entry).toDouble() / index.maxFrequency
        // The exponent is 0.25 (the tuned value): two square roots replace Math.pow, which the
        // fail-fast path would otherwise call per surviving candidate.
        var frequencyWeight =
            if (constants.frequencyExponent == 0.25f) {
                (constants.frequencyWeight * sqrt(sqrt(frequencyRatio))).toFloat()
            } else {
                (constants.frequencyWeight *
                    Math.pow(frequencyRatio, constants.frequencyExponent.toDouble())).toFloat()
            }
        // Dwell is alternative evidence to a loop: it lets the doubled candidate use its plain
        // path even when the undoubled twin exists, and supplies an independent likelihood ratio.
        val doubled = index.loopLengthAt(entry) >= 0f
        val dwellAtDouble = doubled &&
            hasDwellAtDouble(path, index, entry, userDurationMs, userLength)
        if (dwellAtDouble) frequencyWeight *= constants.doubledDwellLikelihoodRatio
        // Fail-fast #1: even a perfect shape and location cannot lift this candidate past the
        // current k-th worst when its frequency weight is too low.
        if (topCount == TOP_N &&
            maxChannelProduct * frequencyWeight <= 1f / topScores[TOP_N - 1]
        ) {
            return topCount
        }
        // Without dwell, a doubled word with an indexed twin scores only against its looped path:
        // its plain variant equals the twin's path, so frequency alone must not choose between
        // them. A twinless doubled word always keeps the better of its plain and looped paths.
        var best = scoreVariant(
            index, entry, topCount, doubled, shapeFactor,
            locationXFactor, locationYFactor,
            shapeInvTwoSigmaSq, locationXInvTwoSigmaSq, locationYInvTwoSigmaSq,
            frequencyWeight, samples, userDurationMs, userLength, lengthInvTwoSigmaSq,
        )
        if (doubled && (index.isTwinlessAt(entry) || dwellAtDouble)) {
            val plain = scoreVariant(
                index, entry, topCount, false, shapeFactor,
                locationXFactor, locationYFactor,
                shapeInvTwoSigmaSq, locationXInvTwoSigmaSq, locationYInvTwoSigmaSq,
                frequencyWeight, samples, userDurationMs, userLength, lengthInvTwoSigmaSq,
            )
            if (plain < best) best = plain
        }
        lastScoredCount++
        if (best == Float.POSITIVE_INFINITY) return topCount
        if (topCount == TOP_N && best >= topScores[TOP_N - 1]) return topCount
        var slot = topCount
        for (i in 0 until topCount) {
            // Score ascending; ties keep the dictionary order (entries arrive in it per bucket,
            // and buckets are visited in a fixed key order — the decode is deterministic).
            if (best < topScores[i] || (best == topScores[i] && entry < topEntries[i])) {
                slot = i
                break
            }
        }
        if (slot >= TOP_N) return topCount
        val newCount = minOf(TOP_N, topCount + 1)
        for (i in newCount - 1 downTo slot + 1) {
            topScores[i] = topScores[i - 1]
            topEntries[i] = topEntries[i - 1]
        }
        topScores[slot] = best
        topEntries[slot] = entry
        return newCount
    }

    /** True when a doubled letter of [entry] is on a key marked by the gesture's dwell mask. */
    private fun hasDwellAtDouble(
        path: GlidePath,
        index: GlideWordIndex,
        entry: Int,
        userDurationMs: Float,
        userLength: Float,
    ): Boolean {
        if (!dwellMaskValid) buildDwellMask(path, userDurationMs, userLength)
        if (!dwellMaskAny) return false
        for (position in index.keySeqStart(entry) + 1 until index.keySeqEnd(entry)) {
            if (!index.sameLetterAsPreviousAt(position)) continue
            val key = index.keySeqAt(position)
            if ((dwellMask[key ushr 6] ushr (key and 63)) and 1L != 0L) return true
        }
        return false
    }

    /** Marks keys at slow segments, using the gesture's own average speed as the reference. */
    private fun buildDwellMask(path: GlidePath, userDurationMs: Float, userLength: Float) {
        dwellMaskValid = true
        dwellMask.fill(0L)
        if (userDurationMs <= 0f || !userDurationMs.isFinite() ||
            userLength <= 0f || !userLength.isFinite()
        ) {
            return
        }
        val averageSpeed = userLength / userDurationMs
        if (averageSpeed <= 0f || !averageSpeed.isFinite()) return
        for (i in 0 until path.size - 1) {
            val duration = path.ts[i + 1] - path.ts[i]
            if (duration < constants.dwellMinDurationMs) continue
            val dx = path.xs[i + 1] - path.xs[i]
            val dy = path.ys[i + 1] - path.ys[i]
            val dwellDistance = averageSpeed * duration * constants.dwellSpeedRatio
            if (dx * dx + dy * dy >= dwellDistance * dwellDistance) continue
            val found = geometry.findClosestKeys(
                (path.xs[i] + path.xs[i + 1]) * 0.5f,
                (path.ys[i] + path.ys[i + 1]) * 0.5f,
                dwellKey,
            )
            if (found == 0) continue
            val key = dwellKey[0]
            dwellMask[key ushr 6] = dwellMask[key ushr 6] or (1L shl (key and 63))
            dwellMaskAny = true
        }
    }

    /**
     * Scores one candidate against one ideal path ([loopedOnly] selects the doubled-letter loop)
     * and returns its confidence, or POSITIVE_INFINITY when a fail-fast check rejects it.
     *
     * The speed channel: when the gesture's [userDurationMs] is shorter than the word's
     * normative duration ([GlideWordIndex.normativeDurationMsAt], the Fitts-law duration of its
     * letter transitions), both location sigmas widen by their ratio (capped by
     * [GlideConstants.speedWidenMax]) — a fast gesture's location evidence is trusted less.
     * Widening only lowers the location channel's peak, so the caller's fail-fast checks
     * against the un-widened factors remain exact (the widened peak can never beat them); the
     * variant-level checks below use the widened factors.
     *
     * The location channel: the x and y error of every sample are scored by their own Gaussians
     * (p = pX * pY), so the two sums accumulate independently in the fused walk.
     */
    private fun scoreVariant(
        index: GlideWordIndex,
        entry: Int,
        topCount: Int,
        loopedOnly: Boolean,
        shapeFactor: Float,
        locationXFactor: Float,
        locationYFactor: Float,
        shapeInvTwoSigmaSq: Float,
        locationXInvTwoSigmaSq: Float,
        locationYInvTwoSigmaSq: Float,
        frequencyWeight: Float,
        samples: Int,
        userDurationMs: Float,
        userLength: Float,
        lengthInvTwoSigmaSq: Float,
    ): Float {
        val points = GlideIdealPaths.write(
            index, entry, geometry, loopedOnly, idealX, idealY, idealStats, idealSegLens,
            idealInvSegLens,
        )
        if (points < 0) return Float.POSITIVE_INFINITY
        val totalLength =
            if (loopedOnly) index.loopLengthAt(entry) else index.plainLengthAt(entry)
        var variantLocationXFactor = locationXFactor
        var variantLocationYFactor = locationYFactor
        var variantLocationXInvTwoSigmaSq = locationXInvTwoSigmaSq
        var variantLocationYInvTwoSigmaSq = locationYInvTwoSigmaSq
        if (userDurationMs > 0f && userDurationMs.isFinite()) {
            // The normative duration is the word's letter route (the Fitts duration is over
            // letters, not loop embellishment), so the looped variant buys no extra location
            // slack on a fast gesture — the twin rule keeps its teeth.
            val normativeMs = index.normativeDurationMsAt(entry)
            if (normativeMs > userDurationMs) {
                val widen = minOf(normativeMs / userDurationMs, constants.speedWidenMax)
                if (widen > 1f) {
                    variantLocationXFactor /= widen
                    variantLocationYFactor /= widen
                    variantLocationXInvTwoSigmaSq /= widen * widen
                    variantLocationYInvTwoSigmaSq /= widen * widen
                }
            }
        }
        val variantLocationFactor = variantLocationXFactor * variantLocationYFactor
        // Normalization factors from the raw polyline's bbox; the fused loop below needs them
        // before it starts (the user path is normalized the same way, see decode()).
        val idealWidth = idealStats[1] - idealStats[0]
        val idealHeight = idealStats[3] - idealStats[2]
        val longestSide = maxOf(maxOf(idealWidth, idealHeight), 0.00001f)
        val invSide = 1f / longestSide
        val centroidX = (idealWidth / 2f + idealStats[0]) * invSide
        val centroidY = (idealHeight / 2f + idealStats[2]) * invSide
        // Early-bail limit for the shape channel: the partial sums below are monotone
        // nondecreasing, so once the running distance passes the distance at which the
        // Gaussian can no longer beat the k-th worst (even with a perfect location), the
        // loop's rest cannot change the verdict. Float compare against a Double-derived
        // limit is exact enough — the bail fires strictly past the true threshold.
        var shapeLimit = Float.POSITIVE_INFINITY
        if (topCount == TOP_N) {
            val needed =
                1.0 / (topScores[TOP_N - 1].toDouble() * variantLocationFactor * frequencyWeight)
            if (needed >= shapeFactor) return Float.POSITIVE_INFINITY
            shapeLimit = (constants.shapeStd *
                Math.sqrt(-2.0 * Math.log(needed / shapeFactor))).toFloat()
        }
        // The fused scoring pass: one walk producing the ideal path's arc-equidistant
        // points on the fly (the same segment walk the resampler runs, segment lengths cached
        // by the writer), with both channels accumulating against them. The shape bail ends
        // the walk early; location's own bail is subsumed (a shape-bailed candidate never
        // pays for location samples either). The location x/y sums are separate (anisotropic
        // sigmas); the post-walk cutoff gates on their means.
        var shapeDistance = 0f
        var locationXSum = 0f
        var locationYSum = 0f
        var k = 0
        if (totalLength <= 0f) {
            // A degenerate ideal path (every letter on one key): every sample is that point.
            val inx = idealX[0] * invSide - centroidX
            val iny = idealY[0] * invSide - centroidY
            while (k < samples && shapeDistance <= shapeLimit) {
                val dx = inx - userNX[k]
                val dy = iny - userNY[k]
                shapeDistance += (if (dx < 0f) -dx else dx) + (if (dy < 0f) -dy else dy)
                val lx = idealX[0] - userX[k]
                val ly = idealY[0] - userY[k]
                locationXSum += if (lx < 0f) -lx else lx
                locationYSum += if (ly < 0f) -ly else ly
                k++
            }
        } else {
            val step = totalLength / (samples - 1)
            var segment = 0
            var segmentBase = 0f
            var segmentLength = idealSegLens[0]
            while (k < samples && shapeDistance <= shapeLimit) {
                val ix: Float
                val iy: Float
                if (k == 0) {
                    ix = idealX[0]
                    iy = idealY[0]
                } else {
                    val target = step * k
                    while (segmentBase + segmentLength < target) {
                        segmentBase += segmentLength
                        segment++
                        if (segment >= points - 1) break
                        segmentLength = idealSegLens[segment]
                    }
                    if (segment >= points - 1) {
                        // Float accumulation overshoot: the remaining points sit at the end.
                        ix = idealX[points - 1]
                        iy = idealY[points - 1]
                    } else {
                        // Interpolate with a multiply (the reciprocal is precomputed);
                        // x*(1/y) can differ from x/y by 1 ulp — ranking-irrelevant.
                        val t = (target - segmentBase) * idealInvSegLens[segment]
                        ix = idealX[segment] + (idealX[segment + 1] - idealX[segment]) * t
                        iy = idealY[segment] + (idealY[segment + 1] - idealY[segment]) * t
                    }
                }
                val inx = ix * invSide - centroidX
                val iny = iy * invSide - centroidY
                // No library calls per point: manual abs (a call per point is slow on low-end
                // ART devices), and the shape channel sums pointwise L1 instead of L2.
                val dx = inx - userNX[k]
                val dy = iny - userNY[k]
                shapeDistance += (if (dx < 0f) -dx else dx) + (if (dy < 0f) -dy else dy)
                val lx = ix - userX[k]
                val ly = iy - userY[k]
                locationXSum += if (lx < 0f) -lx else lx
                locationYSum += if (ly < 0f) -ly else ly
                k++
            }
        }
        if (shapeDistance > shapeLimit) return Float.POSITIVE_INFINITY
        val shapeProbability = gaussian(shapeDistance, shapeFactor, shapeInvTwoSigmaSq)
        if (shapeProbability <= 0f) return Float.POSITIVE_INFINITY // underflow: far shapes
        // Fail-fast #2: shape already known; even a perfect location cannot qualify. The widened
        // factor is the candidate's own (a wider sigma lowers the peak).
        if (topCount == TOP_N &&
            shapeProbability * variantLocationFactor * frequencyWeight <= 1f / topScores[TOP_N - 1]
        ) {
            return Float.POSITIVE_INFINITY
        }
        if (topCount == TOP_N) {
            // Location check: the fused walk has already accumulated the full location sums,
            // so this bound is checked once here instead of inside the loop.
            val needed = 1.0 /
                (topScores[TOP_N - 1].toDouble() * shapeProbability * frequencyWeight)
            if (needed >= variantLocationFactor) return Float.POSITIVE_INFINITY
        }
        // The hard location cutoff: the candidate is out once its mean per-point location error
        // passes locationCutoffSigmas anisotropic sigmas — meanX^2/(2 sigmaX^2) + meanY^2/(2
        // sigmaY^2) > C^2/2 — checked once on the accumulated sums (the mean's 1/samples^2 is
        // folded into the weights) with the UNWIDENED sigmas, so the speed channel's widening
        // cannot rescue a gesture drawn far off the keys.
        val invSamples = 1f / samples
        val cutoffXWeight = locationXInvTwoSigmaSq * invSamples * invSamples
        val cutoffYWeight = locationYInvTwoSigmaSq * invSamples * invSamples
        val cutoffLimit = 0.5f * constants.locationCutoffSigmas * constants.locationCutoffSigmas
        if (cutoffXWeight * locationXSum * locationXSum +
            cutoffYWeight * locationYSum * locationYSum > cutoffLimit
        ) {
            return Float.POSITIVE_INFINITY
        }
        val locationXDistance = locationXSum * invSamples
        val locationYDistance = locationYSum * invSamples
        val locationProbability = gaussianXy(
            locationXDistance, locationYDistance,
            variantLocationXFactor * variantLocationYFactor,
            variantLocationXInvTwoSigmaSq, variantLocationYInvTwoSigmaSq,
        )
        if (locationProbability <= 0f) return Float.POSITIVE_INFINITY
        // The length channel: an unnormalized Gaussian bump over the path-length mismatch; it
        // peaks at 1, so the fail-fast bounds above stay exact.
        val lengthDelta = userLength - totalLength
        val lengthBoost =
            Math.exp((-lengthDelta * lengthDelta * lengthInvTwoSigmaSq).toDouble()).toFloat()
        return 1f / (shapeProbability * locationProbability * frequencyWeight * lengthBoost)
    }

    /** The one-sided Gaussian probability of [distance]; Float wrapper over Math.exp. */
    private fun gaussian(distance: Float, factor: Float, invTwoSigmaSq: Float): Float {
        val exponent = -distance * distance * invTwoSigmaSq
        return factor * Math.exp(exponent.toDouble()).toFloat()
    }

    /** The anisotropic location probability: per-axis Gaussians, p = pX * pY. */
    private fun gaussianXy(
        distanceX: Float,
        distanceY: Float,
        factorProduct: Float,
        invTwoSigmaSqX: Float,
        invTwoSigmaSqY: Float,
    ): Float {
        val exponent = -distanceX * distanceX * invTwoSigmaSqX -
            distanceY * distanceY * invTwoSigmaSqY
        return factorProduct * Math.exp(exponent.toDouble()).toFloat()
    }

    companion object {
        /** Candidates kept by a decode; the strip displays the leading four. */
        const val TOP_N = 8

        private val SQRT_2_PI = sqrt(2.0 * Math.PI)
    }
}

/**
 * The reusable decode output: up to [GlideDecoder.TOP_N] (word, score) pairs, best first.
 * [reset] releases the previous strings; the arrays themselves live as long as the decoder's
 * owner. Scores are confidences — LOWER is better.
 */
class GlideResult(val capacity: Int = GlideDecoder.TOP_N) {
    val words: Array<String?> = arrayOfNulls(capacity)
    val scores = FloatArray(capacity)
    var count = 0
        internal set

    internal fun reset() {
        for (i in 0 until count) words[i] = null
        count = 0
    }
}
