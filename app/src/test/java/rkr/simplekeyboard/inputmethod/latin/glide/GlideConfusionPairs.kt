package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * Confusion-pair mining for the pair-conditional bigram-channel measurement: the offline half of
 * the experiment, shared by the calibration suite (the Tatar dictionary, the pinned synthetic
 * set) and the real-gesture diagnostic (the lexicon of the eval file).
 *
 * A confusion pair is an unordered pair of distinct words {a, b} with b in the top-8 of a's
 * ideal-path decode or vice versa. The mining input is the lexicon and the geometry alone:
 * every indexed word's ideal path (the plain one, plus the looped one when the word has a
 * doubled letter) is drawn at the normative speed — the speed channel stays neutral — and
 * decoded with the production decoder. No gesture data is involved.
 */
internal object GlideConfusionPairs {

    /** The mined sets as sorted key arrays, for allocation-free binary-search probes. */
    class Mined(
        /** Pairs where at least one direction holds. */
        val union: LongArray,
        /** Pairs where both directions hold. */
        val mutual: LongArray,
    )

    /** FNV-1a over the code points of [a], a separator, then [b] — order-sensitive. */
    fun pairHash(a: String, b: String): Long {
        var hash = -3750763034362895579L // 0xCBF29CE484222325
        var offset = 0
        while (offset < a.length) {
            val codePoint = a.codePointAt(offset)
            hash = (hash xor codePoint.toLong()) * 0x100000001B3L
            offset += Character.charCount(codePoint)
        }
        hash = (hash xor 0x1fL) * 0x100000001B3L
        offset = 0
        while (offset < b.length) {
            val codePoint = b.codePointAt(offset)
            hash = (hash xor codePoint.toLong()) * 0x100000001B3L
            offset += Character.charCount(codePoint)
        }
        return hash
    }

    /** The order-free key of a pair: both probe orders hash alike. */
    fun pairKey(a: String, b: String): Long = if (a < b) pairHash(a, b) else pairHash(b, a)

    /** Mines the pairs over the whole inventory; see the class doc for the protocol. */
    fun mine(
        inventory: GlideWordInventory,
        geometry: GlideKeyGeometry,
        constants: GlideDecoder.GlideConstants = GlideDecoder.GlideConstants(),
    ): Mined {
        val index = GlideWordIndex.build(inventory, geometry)
        val decoder = GlideDecoder(geometry, inventory, constants)
        decoder.preloadIndex(index)
        val speed = constants.normativeSpeedRadiiPerMs * geometry.keyRadius
        val idealX = FloatArray(GlideIdealPaths.MAX_POINTS)
        val idealY = FloatArray(GlideIdealPaths.MAX_POINTS)
        val segLens = FloatArray(GlideIdealPaths.MAX_POINTS)
        val path = GlidePath(GlideIdealPaths.MAX_POINTS + 8)
        val result = GlideResult()
        val directed = HashSet<Long>()
        val union = HashSet<Long>()
        val mutual = HashSet<Long>()
        for (entry in 0 until inventory.entryCount) {
            if (index.keySeqEnd(entry) <= index.keySeqStart(entry)) continue
            val word = inventory.wordAt(entry)
            for (looped in booleanArrayOf(false, true)) {
                if (looped && index.loopLengthAt(entry) < 0f) continue
                val points = GlideIdealPaths.write(
                    index, entry, geometry, looped, idealX, idealY, null, segLens, null,
                )
                if (points < 2) continue
                path.clear()
                path.addPoint(idealX[0], idealY[0], 0f)
                var t = 0f
                for (p in 1 until points) {
                    t += segLens[p - 1] / speed
                    path.addPoint(idealX[p], idealY[p], t)
                }
                val count = decoder.decode(path, result)
                for (slot in 0 until count) {
                    val other = result.words[slot]!!
                    if (other == word) continue
                    union.add(pairKey(word, other))
                    directed.add(pairHash(word, other))
                    // The reverse edge from an earlier word closes a mutual pair.
                    if (directed.contains(pairHash(other, word))) {
                        mutual.add(pairKey(word, other))
                    }
                }
            }
        }
        return Mined(union.toLongArray().sortedArray(), mutual.toLongArray().sortedArray())
    }

    /**
     * True when the top-[count] words hold at least one pair whose key is in [keys]; with
     * [rankOneOnly] only pairs involving the rank-1 word count. Probes are binary searches;
     * nothing allocates.
     */
    fun holdsMinedPair(
        keys: LongArray,
        words: Array<String?>,
        count: Int,
        rankOneOnly: Boolean,
    ): Boolean {
        val first = if (rankOneOnly) 1 else count
        for (i in 0 until first) {
            val a = words[i] ?: continue
            for (j in i + 1 until count) {
                val b = words[j] ?: continue
                if (java.util.Arrays.binarySearch(keys, pairKey(a, b)) >= 0) return true
            }
        }
        return false
    }

    /**
     * Paired bootstrap of the top-1 gain ([variant] minus [baseline]), in percentage points: the
     * rows are resampled with replacement from a deterministic SplitMix64 stream; the interval is
     * the 2.5/97.5 percentiles of the resampled gain.
     */
    fun pairedGainCi(
        variant: BooleanArray,
        baseline: BooleanArray,
        resamples: Int = 10_000,
    ): DoubleArray {
        val n = variant.size
        val gains = DoubleArray(resamples)
        var stream = splitmix64(0xB00757A7L)
        for (r in 0 until resamples) {
            var variantHits = 0
            var baselineHits = 0
            for (i in 0 until n) {
                stream = splitmix64(stream)
                val pick = java.lang.Long.remainderUnsigned(stream, n.toLong()).toInt()
                if (variant[pick]) variantHits++
                if (baseline[pick]) baselineHits++
            }
            gains[r] = (variantHits - baselineHits) * 100.0 / n
        }
        gains.sort()
        return doubleArrayOf(
            gains[(resamples * 0.025).toInt()],
            gains[(resamples * 0.975).toInt()],
        )
    }

    private fun splitmix64(seed: Long): Long {
        var z = seed + -7046029254386353131L // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
}
