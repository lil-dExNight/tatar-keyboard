package rkr.simplekeyboard.inputmethod.latin.glide

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * P7-1 calibration (docs/GLIDE-PLAN.md, docs/ROADMAP-P7.md): the glide decoder against the REAL
 * shipped Tatar dictionary on the synthetic gesture set of `scripts/glide_pack.py`.
 *
 * The set is REGENERATED here bit-for-bit: the word selection and the integer noise model below
 * mirror the python generator draw-for-draw (the same FNV-1a + SplitMix64 primitive the typo
 * packs use, all coordinate arithmetic integer-exact, the one square root an IEEE
 * correctly-rounded double on both sides), and the byte-identity of the render is pinned by
 * SHA-256 — the same pin `tests/glide_pack/` asserts python-side.
 *
 * Gates (written in the plan before any code):
 *  - G1: top-3 recovery >= 60 %, top-1 >= 35 % on the HELD-OUT split;
 *  - G2: host decode p95 <= 2 ms at the survivor counts the pruner produces;
 *  - G3: zero allocations after warmup in the decode path (the result strings are the one
 *    documented allocation, bounded per candidate).
 *
 * The train/held-out split is deterministic (a per-word SplitMix64 bit): constants are tuned
 * against the train split only, and the held-out split carries the reported numbers. The tuning
 * surface is a diagnostic printout ([tuningSurfaceOnTheTrainSplit]), never an assert.
 */
class GlideRecoveryCalibrationTest {

    // ---- Portable deterministic primitives (bit-identical to scripts/glide_pack.py). ----

    private fun fnv1a64(data: ByteArray): Long {
        var hash = 0xCBF29CE484222325uL.toLong()
        for (byte in data) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 0x100000001B3L
        }
        return hash
    }

    private fun splitmix64(seed: Long): Long {
        var z = seed + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    // ---- Word selection, mirroring select_words() of glide_pack.py. ----

    private fun lettersMappable(word: String, letters: Set<Int>): Boolean {
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.toLowerCase(codePoint) !in letters) return false
        }
        return true
    }

    private fun selectWords(): List<String> {
        val letters = HashSet<Int>()
        for (key in GlideTestFixtures.tatarRawKeys()) letters.add(key.codePoint)
        val dictionary = HashSet(vocabulary)
        val selected = sortedSetOf<String>()
        for (word in vocabulary) {
            if (word.codePointCount(0, word.length) < MIN_WORD_CODE_POINTS) continue
            if (!lettersMappable(word, letters)) continue
            val draw = java.lang.Long.remainderUnsigned(
                splitmix64(GLIDE_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))),
                DICT_MODULUS.toLong(),
            )
            if (draw == 0L) selected.add(word)
        }
        for (line in evalLines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            for (token in line.split(" ")) {
                if (token.codePointCount(0, token.length) < MIN_WORD_CODE_POINTS) continue
                if (token !in dictionary) continue
                if (lettersMappable(token, letters)) selected.add(token)
            }
        }
        return selected.toList()
    }

    // ---- The gesture generator, mirroring generate_gesture() of glide_pack.py. ----

    private class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private class GeneratedPath(val xs: IntArray, val ys: IntArray, val ts: IntArray)

    private fun generateGesture(
        word: String,
        jitterPercent: Int = JITTER_PERCENT,
        cutModulus: Long = CUT_MODULUS,
        wanderDivisor: Int = WANDER_DIVISOR,
    ): GeneratedPath {
        val byLetter = HashMap<Int, Rect>()
        for (key in GlideTestFixtures.tatarRawKeys()) {
            byLetter[key.codePoint] = Rect(key.left, key.top, key.right, key.bottom)
        }
        val minWidth = GlideTestFixtures.tatarRawKeys().minOf { it.right - it.left }
        val radius = GlideTestFixtures.tatarKeyRadius()

        val codePoints = word.codePoints().toArray()
        var hasDouble = false
        for (i in 1 until codePoints.size) {
            if (codePoints[i] == codePoints[i - 1]) hasDouble = true
        }
        var stream = splitmix64(GLIDE_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8)))
        var drawLoop = false
        if (hasDouble) {
            drawLoop = java.lang.Long.remainderUnsigned(stream, LOOP_MODULUS) == 0L
            stream = splitmix64(stream)
        }
        val stepMin = minWidth / STEP_DIVISOR
        val step = stepMin + java.lang.Long.remainderUnsigned(stream, stepMin.toLong()).toInt()
        stream = splitmix64(stream)
        val tstep = TSTEP_MIN + java.lang.Long.remainderUnsigned(stream, TSTEP_VAR).toInt()
        stream = splitmix64(stream)

        // Ideal vertices (loop corners replace the doubled letter's center when drawn).
        val vertexX = IntArray(5 * codePoints.size)
        val vertexY = IntArray(5 * codePoints.size)
        val vertexIsLoop = BooleanArray(5 * codePoints.size)
        var vertexCount = 0
        var previous = -1
        for (codePoint in codePoints) {
            val rect = byLetter.getValue(Character.toLowerCase(codePoint))
            if (drawLoop && codePoint == previous) {
                val dx = (rect.right - rect.left) / 4
                val dy = (rect.bottom - rect.top) / 4
                vertexX[vertexCount] = rect.centerX + dx
                vertexY[vertexCount] = rect.centerY + dy
                vertexX[vertexCount + 1] = rect.centerX + dx
                vertexY[vertexCount + 1] = rect.centerY - dy
                vertexX[vertexCount + 2] = rect.centerX - dx
                vertexY[vertexCount + 2] = rect.centerY - dy
                vertexX[vertexCount + 3] = rect.centerX - dx
                vertexY[vertexCount + 3] = rect.centerY + dy
                for (k in 0 until 4) vertexIsLoop[vertexCount + k] = true
                vertexCount += 4
            } else {
                vertexX[vertexCount] = rect.centerX
                vertexY[vertexCount] = rect.centerY
                vertexCount++
            }
            previous = codePoint
        }

        // Corner cutting: decided on the original vertices, applied to copies.
        val cut = BooleanArray(vertexCount)
        for (v in 1 until vertexCount - 1) {
            if (vertexIsLoop[v]) continue
            cut[v] = java.lang.Long.remainderUnsigned(stream, cutModulus) == 0L
            stream = splitmix64(stream)
        }
        val cutX = vertexX.copyOf(vertexCount)
        val cutY = vertexY.copyOf(vertexCount)
        for (v in 1 until vertexCount - 1) {
            if (!cut[v]) continue
            cutX[v] = (vertexX[v - 1] + 2 * vertexX[v] + vertexX[v + 1]) / 4
            cutY[v] = (vertexY[v - 1] + 2 * vertexY[v] + vertexY[v + 1]) / 4
        }

        // The integer segment walk at the word's sampling step.
        val segmentLengths = IntArray(vertexCount - 1)
        var total = 0
        for (i in 0 until vertexCount - 1) {
            val dx = (cutX[i + 1] - cutX[i]).toDouble()
            val dy = (cutY[i + 1] - cutY[i]).toDouble()
            val length = Math.floor(sqrt(dx * dx + dy * dy) + 0.5).toInt()
            segmentLengths[i] = length
            total += length
        }
        val pointX = ArrayList<Int>(total / step + 2)
        val pointY = ArrayList<Int>(total / step + 2)
        pointX.add(cutX[0])
        pointY.add(cutY[0])
        if (total > 0) {
            var i = 0
            var base = 0
            var target = step
            while (target < total) {
                while (base + segmentLengths[i] < target) {
                    base += segmentLengths[i]
                    i++
                }
                val segment = segmentLengths[i]
                val num = target - base
                val x1 = cutX[i].toLong()
                val y1 = cutY[i].toLong()
                val dx = (cutX[i + 1] - cutX[i]).toLong()
                val dy = (cutY[i + 1] - cutY[i]).toLong()
                pointX.add(Math.floorDiv(x1 * segment + dx * num, segment.toLong()).toInt())
                pointY.add(Math.floorDiv(y1 * segment + dy * num, segment.toLong()).toInt())
                target += step
            }
            pointX.add(cutX[vertexCount - 1])
            pointY.add(cutY[vertexCount - 1])
        }

        // The smooth wander (clamped random walk of the per-point offset).
        val jitter = radius * jitterPercent / 100
        val wander = jitter / wanderDivisor
        val span = 2L * jitter + 1
        val stepSpan = 2L * wander + 1
        var offsetX = java.lang.Long.remainderUnsigned(stream, span).toInt() - jitter
        stream = splitmix64(stream)
        var offsetY = java.lang.Long.remainderUnsigned(stream, span).toInt() - jitter
        stream = splitmix64(stream)
        val count = pointX.size
        val xs = IntArray(count)
        val ys = IntArray(count)
        val ts = IntArray(count)
        for (index in 0 until count) {
            if (index > 0) {
                offsetX += java.lang.Long.remainderUnsigned(stream, stepSpan).toInt() - wander
                offsetX = offsetX.coerceIn(-jitter, jitter)
                stream = splitmix64(stream)
                offsetY += java.lang.Long.remainderUnsigned(stream, stepSpan).toInt() - wander
                offsetY = offsetY.coerceIn(-jitter, jitter)
                stream = splitmix64(stream)
            }
            xs[index] = pointX[index] + offsetX
            ys[index] = pointY[index] + offsetY
            ts[index] = index * tstep
        }
        return GeneratedPath(xs, ys, ts)
    }

    private fun renderSet(
        words: List<String>,
        jitterPercent: Int = JITTER_PERCENT,
        cutModulus: Long = CUT_MODULUS,
        wanderDivisor: Int = WANDER_DIVISOR,
    ): Pair<StringBuilder, List<GeneratedPath>> {
        val rendered = StringBuilder()
        val paths = ArrayList<GeneratedPath>(words.size)
        for (word in words) {
            val path = generateGesture(word, jitterPercent, cutModulus, wanderDivisor)
            paths.add(path)
            rendered.append(word).append('\t')
            for (i in path.xs.indices) {
                if (i > 0) rendered.append(';')
                rendered.append(path.xs[i]).append(',').append(path.ys[i]).append(',')
                    .append(path.ts[i])
            }
            rendered.append('\n')
        }
        return rendered to paths
    }

    private fun decodePath(path: GeneratedPath, decoder: GlideDecoder, reusable: GlidePath): Int {
        reusable.clear()
        for (i in path.xs.indices) {
            reusable.addPoint(
                path.xs[i].toFloat(), path.ys[i].toFloat(), path.ts[i].toFloat(),
            )
        }
        return decoder.decode(reusable, decodeResult)
    }

    private val decodeResult = GlideResult()

    // ---- Tests. ----

    @Test
    fun theSyntheticSetIsByteIdenticalToTheGeneratorRun() {
        val words = selectWords()
        assertEquals(SET_SIZE, words.size)
        val (rendered, _) = renderSet(words)
        val bytes = rendered.toString().toByteArray(Charsets.UTF_8)
        assertEquals(SET_BYTES, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(SET_SHA256, sha)
    }

    @Test
    fun indexBuildStatsAreMeasuredAndDocumented() {
        val inventory = TdictGlideInventory(realIndex!!)
        val startNanos = System.nanoTime()
        val index = GlideWordIndex.build(inventory, geometry)
        val buildMs = (System.nanoTime() - startNanos) / 1_000_000.0
        var maxBucket = 0
        var usedBuckets = 0
        val keyCount = geometry.keyCount
        for (start in 0 until keyCount) {
            for (end in 0 until keyCount) {
                val size = index.pairRangeEnd(start, end) - index.pairRangeStart(start, end)
                if (size > 0) usedBuckets++
                if (size > maxBucket) maxBucket = size
            }
        }
        println(
            "Glide P7-1 index: words=${index.wordCount} skipped=${index.skippedWordCount} " +
                "maxFrequency=${index.maxFrequency} retainedBytes≈${index.retainedByteEstimate} " +
                "usedBuckets=$usedBuckets/${keyCount * keyCount} maxBucket=$maxBucket " +
                "buildMs=${"%.1f".format(java.util.Locale.ROOT, buildMs)}",
        )
        assertTrue(index.wordCount > 100_000)
        assertTrue("skipped words are the more-key-only-letter minority", index.skippedWordCount < 5_000)
        assertTrue(index.maxFrequency > 0)
    }

    @Test
    fun gatesG1AndG2OnTheRealDictionary() {
        val decoder = sharedDecoder()
        val words = selectWords()
        val (_, paths) = renderSet(words)
        val reusablePath = GlidePath(1024)

        val trainTop = IntArray(2)
        val heldTop = IntArray(2)
        var trainCount = 0
        var heldCount = 0
        val timings = ArrayList<Long>()
        val candidates = ArrayList<Int>()
        val scored = ArrayList<Int>()

        // Train pass first: it doubles as the warmup (the lazy index build happens here).
        for ((position, word) in words.withIndex()) {
            val heldOut = isHeldOut(word)
            val started = System.nanoTime()
            val count = decodePath(paths[position], decoder, reusablePath)
            val elapsed = System.nanoTime() - started
            val top1 = count > 0 && decodeResult.words[0] == word
            var top3 = false
            for (slot in 0 until minOf(3, count)) {
                if (decodeResult.words[slot] == word) top3 = true
            }
            if (heldOut) {
                heldCount++
                if (top1) heldTop[0]++
                if (top3) heldTop[1]++
                timings.add(elapsed)
                candidates.add(decoder.lastCandidateCount)
                scored.add(decoder.lastScoredCount)
            } else {
                trainCount++
                if (top1) trainTop[0]++
                if (top3) trainTop[1]++
            }
        }

        val trainTop1 = trainTop[0].toDouble() / trainCount * 100.0
        val trainTop3 = trainTop[1].toDouble() / trainCount * 100.0
        val heldTop1 = heldTop[0].toDouble() / heldCount * 100.0
        val heldTop3 = heldTop[1].toDouble() / heldCount * 100.0
        val sortedTimings = timings.sorted()
        val p50 = percentileNanos(sortedTimings, 0.50)
        val p95 = percentileNanos(sortedTimings, 0.95)
        val max = sortedTimings.last() / 1_000_000.0
        val candidateP95 = percentileInt(candidates.sorted(), 0.95)
        val candidateMax = candidates.max()
        val scoredP95 = percentileInt(scored.sorted(), 0.95)

        println(
            "Glide P7-1 calibration: set=${words.size} train=$trainCount heldOut=$heldCount " +
                "train_top1=${fmt(trainTop1)}% train_top3=${fmt(trainTop3)}% " +
                "heldout_top1=${fmt(heldTop1)}% heldout_top3=${fmt(heldTop3)}% " +
                "G1(top3>=60,top1>=35)=${if (heldTop3 >= G1_TOP3_MIN && heldTop1 >= G1_TOP1_MIN) "PASS" else "FAIL"} " +
                "decodeMs_p50=${fmt(p50)} decodeMs_p95=${fmt(p95)} decodeMs_max=${fmt(max)} " +
                "G2(p95<=2ms)=${if (p95 <= G2_P95_MS) "PASS" else "FAIL"} " +
                "candidates_p95=$candidateP95 candidates_max=$candidateMax scored_p95=$scoredP95",
        )

        assertTrue("held-out top-3 ${fmt(heldTop3)}% below the 60% gate", heldTop3 >= G1_TOP3_MIN)
        assertTrue("held-out top-1 ${fmt(heldTop1)}% below the 35% gate", heldTop1 >= G1_TOP1_MIN)
        assertTrue("decode p95 ${fmt(p95)}ms over the 2ms host gate", p95 <= G2_P95_MS)
    }

    /**
     * The tuning surface: top-3 on the TRAIN split (every second word) over a sigma grid around
     * the shipped constants — the ported PR #1870 values (22.08 / 0.5109) included — plus a
     * frequency-exponent row at the shipped sigmas. The constants were chosen train-side; the
     * held-out numbers of [gatesG1AndG2OnTheRealDictionary] are the reported ones. Diagnostic
     * only — it prints, it does not assert a threshold.
     */
    @Test
    fun tuningSurfaceOnTheTrainSplit() {
        val words = selectWords()
        val (_, paths) = renderSet(words)
        val train = trainWordIndices(words)
        val reusablePath = GlidePath(1024)
        val inventory = TdictGlideInventory(realIndex!!)
        val wordIndex = GlideWordIndex.build(inventory, geometry)

        fun top3OnTrain(constants: GlideDecoder.GlideConstants): Double {
            val decoder = GlideDecoder(geometry, inventory, constants)
            decoder.preloadIndex(wordIndex)
            var top3 = 0
            var count = 0
            for (position in train) {
                count++
                val found = decodePath(paths[position], decoder, reusablePath)
                var hit = false
                for (slot in 0 until minOf(3, found)) {
                    if (decodeResult.words[slot] == words[position]) hit = true
                }
                if (hit) top3++
            }
            return top3.toDouble() / count * 100.0
        }

        val shapeStds = listOf(5.52f, 8.28f, 11.04f, 16.56f, 22.08f)
        val locationFactors = listOf(0.1277f, 0.18f, 0.2555f, 0.5109f)
        println("Glide P7-1 tuning surface (train split, top-3 %, gamma = shipped 0.25):")
        println("shapeStd\\locFactor " + locationFactors.joinToString(" ") { "%8.4f".format(it) })
        for (shapeStd in shapeStds) {
            val row = StringBuilder("%13.2f ".format(java.util.Locale.ROOT, shapeStd))
            for (locationFactor in locationFactors) {
                row.append(
                    "%9.4f".format(
                        java.util.Locale.ROOT,
                        top3OnTrain(
                            GlideDecoder.GlideConstants(
                                shapeStd = shapeStd, locationStdFactor = locationFactor,
                            ),
                        ),
                    ),
                )
            }
            println(row.toString())
        }
        val gammaRow = StringBuilder("gamma sweep (shipped sigmas): ")
        for (gamma in listOf(0.0f, 0.15f, 0.25f, 0.35f, 0.5f, 0.75f, 1.0f)) {
            gammaRow.append(
                "%.2f->%.2f%%  ".format(
                    java.util.Locale.ROOT, gamma,
                    top3OnTrain(GlideDecoder.GlideConstants(frequencyExponent = gamma)),
                ),
            )
        }
        println(gammaRow.toString())
    }

    /** Every second TRAIN word's index (the tuning grid must not pay full-set decodes). */
    private fun trainWordIndices(all: List<String>): List<Int> {
        val result = ArrayList<Int>(all.size / 4)
        for ((index, word) in all.withIndex()) {
            if (!isHeldOut(word) && index % 2 == 0) result.add(index)
        }
        return result
    }

    @Test
    fun gateG3ZeroAllocationsAfterWarmupOnTheRealDictionary() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val decoder = sharedDecoder()
        val words = selectWords()
        val (_, paths) = renderSet(words)
        val reusablePath = GlidePath(1024)
        val realGesture = paths[paths.size / 2]
        // The all-pruned path: a long zigzag whose length prunes every bucket it touches.
        val zigzag = GlidePath(1024)
        var x = 0f
        for (i in 0 until 512) {
            zigzag.addPoint(x, if (i % 2 == 0) 0f else 50_000f, i * 8f)
            x += 400f
        }

        fun perCallBytes(path: GeneratedPath?, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (i in 0 until iterations) {
                if (path == null) decoder.decode(zigzag, decodeResult)
                else decodePath(path, decoder, reusablePath)
            }
            val after = threadBean.getThreadAllocatedBytes(threadId)
            return (after - before) / iterations
        }

        // Warm the lazy index, JIT and the result slots.
        perCallBytes(realGesture, 200)
        perCallBytes(null, 200)

        val prunedBytes = perCallBytes(null, 2_000)
        assertEquals("the zigzag path returned candidates", 0, decodeResult.count)
        assertTrue("machinery bytes/call on an all-pruned path: $prunedBytes", prunedBytes <= 8L)

        val wordBytes = perCallBytes(realGesture, 2_000)
        assertTrue("the real gesture returned no candidates", decodeResult.count > 0)
        // The one documented allocation of the decode path: each surfaced word materializes a
        // String plus its decoded backing array (measured ~185 B/word on this host; bound
        // carries headroom). The machinery itself is pinned at zero by the pruned path above.
        assertTrue(
            "bytes/call with results: $wordBytes (bound = ${GlideDecoder.TOP_N} strings)",
            wordBytes <= GlideDecoder.TOP_N * 256L,
        )
        println(
            "Glide P7-1 G3: pruned_bytes_per_call=$prunedBytes " +
                "result_bytes_per_call=$wordBytes (results materialize <= ${GlideDecoder.TOP_N} strings) " +
                "G3=${if (prunedBytes <= 8L && wordBytes <= GlideDecoder.TOP_N * 256L) "PASS" else "FAIL"}",
        )
    }

    /** The held-out split bit: deterministic per word, independent of the gesture stream. */
    private fun isHeldOut(word: String): Boolean =
        java.lang.Long.remainderUnsigned(
            splitmix64(SPLIT_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))), 2L,
        ) == 1L

    private fun percentileNanos(sorted: List<Long>, fraction: Double): Double {
        val rank = maxOf(1, ceil(sorted.size * fraction).toInt())
        return sorted[rank - 1] / 1_000_000.0
    }

    private fun percentileInt(sorted: List<Int>, fraction: Double): Int {
        val rank = maxOf(1, ceil(sorted.size * fraction).toInt())
        return sorted[rank - 1]
    }

    private fun fmt(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // Bit-identical to scripts/glide_pack.py (asserted by the set SHA-256 pin below).
        private const val GLIDE_SEED = 20260924L
        private const val SPLIT_SEED = 20260925L
        private const val MIN_WORD_CODE_POINTS = 5
        private const val DICT_MODULUS = 40
        private const val LOOP_MODULUS = 8L
        private const val CUT_MODULUS = 10L
        private const val STEP_DIVISOR = 6
        private const val TSTEP_MIN = 8
        private const val TSTEP_VAR = 9L
        private const val JITTER_PERCENT = 18
        private const val WANDER_DIVISOR = 6

        // The pinned identity of the synthetic set (the same pins tests/glide_pack/ asserts).
        private const val SET_SIZE = 4498
        private const val SET_BYTES = 9181165
        private const val SET_SHA256 =
            "ea7a58fa090906fd4f4af66da9ba9e928d1d8e405882adc9b9ebb390c4e59549"

        // The written gates of docs/GLIDE-PLAN.md (P7-1), fixed before any code.
        private const val G1_TOP3_MIN = 60.0
        private const val G1_TOP1_MIN = 35.0
        private const val G2_P95_MS = 2.0

        private val geometry = GlideTestFixtures.tatarGeometry()
        private var realIndex: TdictPrefixIndex? = null
        private var sharedDecoder: GlideDecoder? = null
        private lateinit var vocabulary: List<String>
        private lateinit var evalLines: List<String>

        private fun sharedDecoder(): GlideDecoder {
            if (sharedDecoder == null) {
                sharedDecoder = GlideDecoder(geometry, TdictGlideInventory(realIndex!!))
            }
            return sharedDecoder!!
        }

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val asset = locate(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val rawFile = File.createTempFile("glide-calibration-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val raw = rawFile.readBytes()
                val identity = DictionaryIdentity(
                    spec.generation,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                realIndex = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw),
                    identity,
                    validated.entryCount,
                    validated.rawSize,
                )
                check(realIndex != null)
                vocabulary = DictionaryTestFixtures.words(raw)
                check(vocabulary.size == spec.expectedEntryCount.toInt())
            } finally {
                rawFile.delete()
            }
            evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8).map { it.trim() }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
