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
 * The glide decoder: a SHARK2-style two-channel statistical classifier over the dictionary.
 *
 * The algorithm follows the AnySoftKeyboard PR #1870 parameter study (Etienne Desticourt's
 * write-up) as implemented by FlorisBoard's `StatisticalGlideTypingClassifier` (Apache-2.0,
 * (C) the FlorisBoard contributors). The math is ported with attribution; the code is new:
 * fixed scratch buffers, no allocations after warmup, no candidates on degenerate input. The
 * channel sigmas and the frequency exponent are tuned on a synthetic gesture set.
 *
 * Pipeline of one [decode]:
 *  1. Extremity pruning: the two keys nearest the gesture's start x the two nearest its end
 *     select up to four buckets of the [GlideWordIndex].
 *  2. Length pruning: a candidate stays only when its plain or looped ideal-path length lies
 *     within [GlideConstants.lengthThreshold] x key radius of the gesture's length.
 *  3. Scoring against the candidate's ideal path (the looped variant when the word has a
 *     doubled letter, the plain one otherwise; a twinless doubled word keeps the better of
 *     both, see [scoreCandidate]): shape distance (bbox-normalized pointwise L1
 *     over the resampled paths, Gaussian with [GlideConstants.shapeStd]) x location distance
 *     (absolute pointwise L1/2, Gaussian with [GlideConstants.locationStdFactor] x key radius)
 *     x a frequency weight. The confidence competes for the top-N; cheap fail-fast checks
 *     (frequency alone, then shape alone) skip the expensive channels when a candidate cannot
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
     * Tuning knobs of the classifier. [lengthThreshold], [sampleCount], [extremityNeighbors] and
     * [frequencyWeight] carry the AnySoftKeyboard PR #1870 study values (as ported through
     * FlorisBoard). [shapeStd], [locationStdFactor] and [frequencyExponent] are tuned on the
     * train split of a synthetic gesture set against the Tatar dictionary; the study's sigma
     * values (22.08 / 0.5109) lie on the same plateau.
     */
    class GlideConstants(
        /** Gaussian sigma of the shape channel, in bbox-normalized units x sample count. */
        val shapeStd: Float = 11.04f,
        /** Gaussian sigma of the location channel, as a factor of the key radius. */
        val locationStdFactor: Float = 0.18f,
        /** Length-pruning threshold, as a factor of the key radius. */
        val lengthThreshold: Float = 8.42f,
        /** Resampled path resolution shared by both channels. */
        val sampleCount: Int = 200,
        /** Keys considered nearest to the gesture's start (and end) in extremity pruning. */
        val extremityNeighbors: Int = 2,
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
    )

    @Volatile
    private var wordIndex: GlideWordIndex? = null

    /** Preloads a shared prebuilt index (the calibration grid skips a rebuild per cell). */
    internal fun preloadIndex(index: GlideWordIndex) {
        wordIndex = index
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
    private val startKeys: IntArray = IntArray(constants.extremityNeighbors)
    private val endKeys: IntArray = IntArray(constants.extremityNeighbors)
    private val topScores = FloatArray(TOP_N)
    private val topEntries = IntArray(TOP_N)
    // The extremity-pruning merge arms: 2 nearest start keys x 2 nearest end keys.
    private val mergeEnd = IntArray(constants.extremityNeighbors * constants.extremityNeighbors)
    private val mergeCursor = IntArray(constants.extremityNeighbors * constants.extremityNeighbors)

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
        val locationStd = constants.locationStdFactor * radius
        // Gaussian factors: p = factor * exp(-distance^2 * invTwoSigmaSq); their product at
        // distance 0 is the best either channel can do, which the fail-fast checks consume.
        val shapeFactor = (1.0 / (constants.shapeStd * SQRT_2_PI)).toFloat()
        val locationFactor = (1.0 / (locationStd * SQRT_2_PI)).toFloat()
        val shapeInvTwoSigmaSq =
            (1.0 / (2.0 * constants.shapeStd * constants.shapeStd)).toFloat()
        val locationInvTwoSigmaSq = (1.0 / (2.0 * locationStd * locationStd)).toFloat()
        val maxChannelProduct = shapeFactor * locationFactor

        val startCount = geometry.findClosestKeys(path.firstX, path.firstY, startKeys)
        val endCount = geometry.findClosestKeys(path.lastX, path.lastY, endKeys)
        topScores.fill(Float.POSITIVE_INFINITY)
        var topCount = 0

        // The pair buckets hold their entries frequency-first (build-time sort), and the visit
        // merges the ≤ 4 arms into one global frequency-descending walk: the fail-fast bound
        // tightens after the first few scored candidates, so most survivors are rejected without
        // ever paying the scoring cost. Verdict-exact (a rejected candidate cannot reach the
        // top-N even with a perfect match) and deterministic (frequency, then entry index).
        var armCount = 0
        for (s in 0 until startCount) {
            for (e in 0 until endCount) {
                val rangeStart = index.pairRangeStart(startKeys[s], endKeys[e])
                val rangeEnd = index.pairRangeEnd(startKeys[s], endKeys[e])
                if (rangeStart < rangeEnd) {
                    mergeEnd[armCount] = rangeEnd
                    mergeCursor[armCount] = rangeStart
                    armCount++
                }
            }
        }
        while (true) {
            var bestArm = -1
            var bestFrequency = -1L
            var bestEntry = Int.MAX_VALUE
            for (arm in 0 until armCount) {
                val cursor = mergeCursor[arm]
                if (cursor >= mergeEnd[arm]) continue
                val entry = index.pairEntryAt(cursor)
                val frequency = index.frequencyAt(entry)
                if (frequency > bestFrequency || (frequency == bestFrequency && entry < bestEntry)) {
                    bestArm = arm
                    bestFrequency = frequency
                    bestEntry = entry
                }
            }
            if (bestArm < 0) break
            mergeCursor[bestArm]++
            val entry = bestEntry
            lastCandidateCount++
            val plain = index.plainLengthAt(entry)
            val looped = index.loopLengthAt(entry)
            if (abs(userLength - plain) >= maxDistance &&
                (looped < 0f || abs(userLength - looped) >= maxDistance)
            ) {
                continue
            }
            topCount = scoreCandidate(
                index, entry, topCount, shapeFactor, locationFactor,
                shapeInvTwoSigmaSq, locationInvTwoSigmaSq, maxChannelProduct,
            )
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
        index: GlideWordIndex,
        entry: Int,
        topCount: Int,
        shapeFactor: Float,
        locationFactor: Float,
        shapeInvTwoSigmaSq: Float,
        locationInvTwoSigmaSq: Float,
        maxChannelProduct: Float,
    ): Int {
        val samples = constants.sampleCount
        val frequencyRatio = index.frequencyAt(entry).toDouble() / index.maxFrequency
        val frequencyWeight =
            (constants.frequencyWeight * Math.pow(frequencyRatio, constants.frequencyExponent.toDouble())).toFloat()
        // Fail-fast #1: even a perfect shape and location cannot lift this candidate past the
        // current k-th worst when its frequency weight is too low.
        if (topCount == TOP_N &&
            maxChannelProduct * frequencyWeight <= 1f / topScores[TOP_N - 1]
        ) {
            return topCount
        }
        // A word with a doubled letter scores against its looped ideal path, so the doubled letter
        // needs a loop or dwell in the user path: its plain variant equals the undoubled twin's
        // path, and frequency alone would pick between the two words. When no indexed word owns
        // that plain path (the twin bit), the word also scores against it and keeps the better.
        val doubled = index.loopLengthAt(entry) >= 0f
        var best = scoreVariant(
            index, entry, topCount, doubled, shapeFactor, locationFactor,
            shapeInvTwoSigmaSq, locationInvTwoSigmaSq, frequencyWeight, samples,
        )
        if (doubled && index.isTwinlessAt(entry)) {
            val plain = scoreVariant(
                index, entry, topCount, false, shapeFactor, locationFactor,
                shapeInvTwoSigmaSq, locationInvTwoSigmaSq, frequencyWeight, samples,
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

    /**
     * Scores one candidate against one ideal path ([loopedOnly] selects the doubled-letter loop)
     * and returns its confidence, or POSITIVE_INFINITY when a fail-fast check rejects it.
     */
    private fun scoreVariant(
        index: GlideWordIndex,
        entry: Int,
        topCount: Int,
        loopedOnly: Boolean,
        shapeFactor: Float,
        locationFactor: Float,
        shapeInvTwoSigmaSq: Float,
        locationInvTwoSigmaSq: Float,
        frequencyWeight: Float,
        samples: Int,
    ): Float {
        val points = GlideIdealPaths.write(
            index, entry, geometry, loopedOnly, idealX, idealY, idealStats, idealSegLens,
            idealInvSegLens,
        )
        if (points < 0) return Float.POSITIVE_INFINITY
        val totalLength =
            if (loopedOnly) index.loopLengthAt(entry) else index.plainLengthAt(entry)
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
            val needed = 1.0 / (topScores[TOP_N - 1].toDouble() * locationFactor * frequencyWeight)
            if (needed >= shapeFactor) return Float.POSITIVE_INFINITY
            shapeLimit = (constants.shapeStd *
                Math.sqrt(-2.0 * Math.log(needed / shapeFactor))).toFloat()
        }
        // The fused scoring pass: one walk producing the ideal path's arc-equidistant
        // points on the fly (the same segment walk the resampler runs, segment lengths cached
        // by the writer), with both channels accumulating against them. The shape bail ends
        // the walk early; location's own bail is subsumed (a shape-bailed candidate never
        // pays for location samples either).
        var shapeDistance = 0f
        var locationSum = 0f
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
                locationSum += (if (lx < 0f) -lx else lx) + (if (ly < 0f) -ly else ly)
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
                locationSum += (if (lx < 0f) -lx else lx) + (if (ly < 0f) -ly else ly)
                k++
            }
        }
        if (shapeDistance > shapeLimit) return Float.POSITIVE_INFINITY
        val shapeProbability = gaussian(shapeDistance, shapeFactor, shapeInvTwoSigmaSq)
        if (shapeProbability <= 0f) return Float.POSITIVE_INFINITY // underflow: far shapes
        // Fail-fast #2: shape already known; even a perfect location cannot qualify.
        if (topCount == TOP_N &&
            shapeProbability * locationFactor * frequencyWeight <= 1f / topScores[TOP_N - 1]
        ) {
            return Float.POSITIVE_INFINITY
        }
        if (topCount == TOP_N) {
            // Location check: the fused walk has already accumulated the full location sum,
            // so this bound is checked once here instead of inside the loop.
            val needed = 1.0 /
                (topScores[TOP_N - 1].toDouble() * shapeProbability * frequencyWeight)
            if (needed >= locationFactor) return Float.POSITIVE_INFINITY
        }
        val locationDistance = locationSum / (2f * samples)
        val locationProbability = gaussian(locationDistance, locationFactor, locationInvTwoSigmaSq)
        if (locationProbability <= 0f) return Float.POSITIVE_INFINITY
        return 1f / (shapeProbability * locationProbability * frequencyWeight)
    }

    /** The one-sided Gaussian probability of [distance]; Float wrapper over Math.exp. */
    private fun gaussian(distance: Float, factor: Float, invTwoSigmaSq: Float): Float {
        val exponent = -distance * distance * invTwoSigmaSq
        return factor * Math.exp(exponent.toDouble()).toFloat()
    }

    companion object {
        /** Candidates kept by a decode; the strip displays the leading three. */
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
