package rkr.simplekeyboard.inputmethod.latin.glide

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.BigramTableIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TatBigrPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Glide decoder calibration against the REAL bundled Tatar dictionary on the synthetic gesture set
 * of `scripts/glide_pack.py`.
 *
 * The set is REGENERATED here bit-for-bit: the word selection and the integer noise model below
 * mirror the python generator draw-for-draw (the same FNV-1a + SplitMix64 primitive the typo
 * packs use, all coordinate arithmetic integer-exact, the one square root an IEEE
 * correctly-rounded double on both sides), and the byte-identity of the render is pinned by
 * SHA-256 — the same pin `tests/glide_pack/` asserts python-side.
 *
 * Gates (the G1/G2 constants below):
 *  - G1: top-3 recovery >= 60 %, top-1 >= 35 % on the HELD-OUT split;
 *  - G2: host decode p95 <= 2 ms at the survivor counts the pruner produces (asserted on
 *    developer hosts; skipped under `CI` where runner load makes wall-clock bounds flaky);
 *  - G3: zero allocations after warmup in the decode path (the result strings are the only
 *    allocation, bounded per candidate).
 *
 * The memo gates live alongside: [gatesG4SpeedAdaptationOnTheRealDictionary] (the speed channel
 * beats the unadapted decoder on the fast-persona class), [gatesG8PerLanguageConstants] (the
 * per-language constants never lose to the shared set on either language) and
 * [gatesG5BigramChannel] (the bigram channel beats the plain decode on held-out context rows).
 * The tuning surfaces print and never assert.
 * The train/held-out split is deterministic (a per-word SplitMix64 bit): constants are tuned
 * against the train split only, and the held-out split carries the reported numbers. The tuning
 * surface is a diagnostic printout ([tuningSurfaceOnTheTrainSplit]), never an assert.
 */
class GlideRecoveryCalibrationTest {

    // ---- Word selection, mirroring select_words() of glide_pack.py. ----

    private fun selectWords(
        words: List<String>,
        sentences: List<String>,
        geo: GeneratorGeometry,
    ): List<String> {
        val letters = geo.byLetter.keys
        val dictionary = HashSet(words)
        val selected = sortedSetOf<String>()
        for (word in words) {
            if (word.codePointCount(0, word.length) < MIN_WORD_CODE_POINTS) continue
            if (!lettersMappable(word, letters)) continue
            val draw = java.lang.Long.remainderUnsigned(
                splitmix64(GLIDE_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))),
                DICT_MODULUS.toLong(),
            )
            if (draw == 0L) selected.add(word)
        }
        for (line in sentences) {
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

    /**
     * The generator's view of one layout: the letter -> key rectangle map (every long-press letter
     * without a key of its own on its base key's rectangle, resolved through the built
     * [GlideKeyGeometry] so multi-key aliases follow the production alias rule), the narrowest key
     * width and the key radius.
     */
    private class GeneratorGeometry(raw: List<GlideKeyGeometry.RawKey>) {
        val byLetter: Map<Int, Rect>
        val minWidth = raw.minOf { it.right - it.left }
        val radius = raw.minOf { minOf(it.right - it.left, it.bottom - it.top) }

        init {
            val built = GlideKeyGeometry.build(raw)
            val rects = HashMap<Int, Rect>()
            for (key in raw) rects[key.codePoint] = Rect(key.left, key.top, key.right, key.bottom)
            val byKeyIndex = HashMap<Int, Rect>()
            for (key in raw) {
                byKeyIndex[built.keyIndexOfLetter(key.codePoint)] = rects.getValue(key.codePoint)
            }
            for (slot in 0 until built.aliasCount) {
                rects[built.aliasLetterAt(slot)] = byKeyIndex.getValue(built.aliasKeyAt(slot))
            }
            byLetter = rects
        }
    }

    /** Two adjacent letters on one key: a doubled letter, or a letter and its alias. */
    private fun hasDoubledKey(word: String, geo: GeneratorGeometry): Boolean {
        var previous: Rect? = null
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            val rect = geo.byLetter.getValue(Character.toLowerCase(codePoint))
            if (rect === previous) return true
            previous = rect
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private class GeneratedPath(val xs: IntArray, val ys: IntArray, val ts: IntArray, val drewLoop: Boolean) {
        /** The word of the set row this path was generated for (rows outnumber words). */
        var rowWord: String = ""

        /** The committed previous word of a CONTEXT row; empty for plain word rows. */
        var rowContext: String = ""
    }

    private fun generateGesture(
        geo: GeneratorGeometry,
        word: String,
        drawLoop: Boolean = false,
        jitterPercent: Int = JITTER_PERCENT,
        cutModulus: Long = CUT_MODULUS,
        wanderDivisor: Int = WANDER_DIVISOR,
        cutDivisor: Int = CUT_DIVISOR,
        gestureOffsetPercent: Int = GESTURE_OFFSET_PERCENT,
        endpointStartPercent: Int = ENDPOINT_START_PERCENT,
        endpointEndPercent: Int = ENDPOINT_END_PERCENT,
        tstepMin: Int = TSTEP_MIN,
        tstepVar: Long = TSTEP_VAR,
        streamSeed: Long = GLIDE_SEED,
    ): GeneratedPath {
        val minWidth = geo.minWidth
        val radius = geo.radius

        val codePoints = word.codePoints().toArray()
        val hasDouble = hasDoubledKey(word, geo)
        var stream = splitmix64(streamSeed xor fnv1a64(word.toByteArray(Charsets.UTF_8)))
        // The loop is the CALLER's decision (the set carries both variants of a doubled word), so
        // the stream feeds the step draw at once.
        val loop = drawLoop && hasDouble
        val stepMin = minWidth / STEP_DIVISOR
        val step = stepMin + java.lang.Long.remainderUnsigned(stream, stepMin.toLong()).toInt()
        stream = splitmix64(stream)
        val tstep = tstepMin + java.lang.Long.remainderUnsigned(stream, tstepVar).toInt()
        stream = splitmix64(stream)

        // Ideal vertices (loop corners replace the doubled letter's center when drawn).
        val vertexX = IntArray(5 * codePoints.size)
        val vertexY = IntArray(5 * codePoints.size)
        val vertexIsLoop = BooleanArray(5 * codePoints.size)
        var vertexCount = 0
        var previous: Rect? = null
        for (codePoint in codePoints) {
            val rect = geo.byLetter.getValue(Character.toLowerCase(codePoint))
            if (loop && rect === previous) {
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
            previous = rect
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
            // floorDiv: the dividend goes negative (mirror of the python generator's //).
            cutX[v] = vertexX[v] + Math.floorDiv(vertexX[v - 1] + vertexX[v + 1] - 2 * vertexX[v], cutDivisor)
            cutY[v] = vertexY[v] + Math.floorDiv(vertexY[v - 1] + vertexY[v + 1] - 2 * vertexY[v], cutDivisor)
        }

        // Endpoint offsets (draw group 4 of the noise model): a per-axis uniform draw in a
        // half-range of ENDPOINT_*_PERCENT % of the key radius, scaled by ENDPOINT_WIDE_SCALE
        // when the branch draw fires (branch, then x and y, per endpoint).
        var startHalf = radius * endpointStartPercent / 100
        if (java.lang.Long.remainderUnsigned(stream, ENDPOINT_WIDE_MODULUS) == 0L) {
            startHalf *= ENDPOINT_WIDE_SCALE
        }
        stream = splitmix64(stream)
        val startDx = java.lang.Long.remainderUnsigned(stream, 2L * startHalf + 1).toInt() - startHalf
        stream = splitmix64(stream)
        val startDy = java.lang.Long.remainderUnsigned(stream, 2L * startHalf + 1).toInt() - startHalf
        stream = splitmix64(stream)
        var endHalf = radius * endpointEndPercent / 100
        if (java.lang.Long.remainderUnsigned(stream, ENDPOINT_WIDE_MODULUS) == 0L) {
            endHalf *= ENDPOINT_WIDE_SCALE
        }
        stream = splitmix64(stream)
        val endDx = java.lang.Long.remainderUnsigned(stream, 2L * endHalf + 1).toInt() - endHalf
        stream = splitmix64(stream)
        val endDy = java.lang.Long.remainderUnsigned(stream, 2L * endHalf + 1).toInt() - endHalf
        stream = splitmix64(stream)
        cutX[0] += startDx
        cutY[0] += startDy
        cutX[vertexCount - 1] += endDx
        cutY[vertexCount - 1] += endDy

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
        // The gesture shift (draw group 5): one constant offset for the whole path.
        val shift = radius * gestureOffsetPercent / 100
        val shiftSpan = 2L * shift + 1
        val shiftX = java.lang.Long.remainderUnsigned(stream, shiftSpan).toInt() - shift
        stream = splitmix64(stream)
        val shiftY = java.lang.Long.remainderUnsigned(stream, shiftSpan).toInt() - shift
        stream = splitmix64(stream)
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
            xs[index] = pointX[index] + shiftX + offsetX
            ys[index] = pointY[index] + shiftY + offsetY
            ts[index] = index * tstep
        }
        return GeneratedPath(xs, ys, ts, loop)
    }

    /** The word's persona (mirror of glide_pack.persona_of). */
    private fun personaOf(word: String): Int = java.lang.Long.remainderUnsigned(
        splitmix64(PERSONA_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8))),
        PERSONA_MODULUS,
    ).toInt()

    /** The generateGesture overrides of one persona (mirror of glide_pack.persona_knobs). */
    private class PersonaKnobs(
        val jitterPercent: Int,
        val gestureOffsetPercent: Int,
        val endpointStartPercent: Int,
        val endpointEndPercent: Int,
        val tstepMin: Int,
        val tstepVar: Long,
    )

    private fun personaKnobs(persona: Int): PersonaKnobs = when (persona) {
        PERSONA_FAST -> PersonaKnobs(
            JITTER_PERCENT * PERSONA_FAST_NUM / PERSONA_FAST_DEN,
            GESTURE_OFFSET_PERCENT * PERSONA_FAST_NUM / PERSONA_FAST_DEN,
            ENDPOINT_START_PERCENT * PERSONA_FAST_NUM / PERSONA_FAST_DEN,
            ENDPOINT_END_PERCENT * PERSONA_FAST_NUM / PERSONA_FAST_DEN,
            TSTEP_FAST_MIN, TSTEP_FAST_VAR,
        )
        PERSONA_SLOW -> PersonaKnobs(
            JITTER_PERCENT * PERSONA_SLOW_NUM / PERSONA_SLOW_DEN,
            GESTURE_OFFSET_PERCENT * PERSONA_SLOW_NUM / PERSONA_SLOW_DEN,
            ENDPOINT_START_PERCENT * PERSONA_SLOW_NUM / PERSONA_SLOW_DEN,
            ENDPOINT_END_PERCENT * PERSONA_SLOW_NUM / PERSONA_SLOW_DEN,
            TSTEP_SLOW_MIN, TSTEP_SLOW_VAR,
        )
        else -> PersonaKnobs(
            JITTER_PERCENT, GESTURE_OFFSET_PERCENT,
            ENDPOINT_START_PERCENT, ENDPOINT_END_PERCENT, TSTEP_MIN, TSTEP_VAR,
        )
    }

    private fun generateGesture(
        geo: GeneratorGeometry,
        word: String,
        drawLoop: Boolean,
        knobs: PersonaKnobs,
        streamSeed: Long = GLIDE_SEED,
    ): GeneratedPath =
        generateGesture(
            geo, word, drawLoop,
            jitterPercent = knobs.jitterPercent,
            gestureOffsetPercent = knobs.gestureOffsetPercent,
            endpointStartPercent = knobs.endpointStartPercent,
            endpointEndPercent = knobs.endpointEndPercent,
            tstepMin = knobs.tstepMin,
            tstepVar = knobs.tstepVar,
            streamSeed = streamSeed,
        )

    private fun renderSet(
        words: List<String>,
        geo: GeneratorGeometry,
        contextPairs: List<Pair<String, String>> = emptyList(),
    ): Pair<StringBuilder, List<GeneratedPath>> {
        val rendered = StringBuilder()
        val paths = ArrayList<GeneratedPath>(words.size)
        for (word in words) {
            // A doubled word contributes BOTH variants — the no-jog row first, then the jog row —
            // drawn from the same word stream (mirror of glide_pack.generate_set). Both variants
            // carry the word's persona.
            val knobs = personaKnobs(personaOf(word))
            appendRow(word, generateGesture(geo, word, false, knobs), rendered, paths)
            if (hasDoubledKey(word, geo)) {
                appendRow(word, generateGesture(geo, word, true, knobs), rendered, paths)
            }
        }
        // Context rows: one no-jog row per selected pair, after the word rows.
        for ((previous, word) in contextPairs) {
            val knobs = personaKnobs(personaOf(word))
            val path = generateGesture(
                geo, word, false, knobs, streamSeed = contextPairSeed(previous, word),
            )
            path.rowContext = previous
            appendRow(word, path, rendered, paths)
        }
        return rendered to paths
    }

    private fun appendRow(
        word: String,
        path: GeneratedPath,
        rendered: StringBuilder,
        paths: ArrayList<GeneratedPath>,
    ) {
        path.rowWord = word
        paths.add(path)
        rendered.append(word).append('\t')
        if (path.rowContext.isNotEmpty()) rendered.append(path.rowContext).append('\t')
        for (i in path.xs.indices) {
            if (i > 0) rendered.append(';')
            rendered.append(path.xs[i]).append(',').append(path.ys[i]).append(',')
                .append(path.ts[i])
        }
        rendered.append('\n')
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
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        assertEquals(SET_SIZE, words.size)
        val (rendered, _) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
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
        assertTrue("skipped words are the minority with a letter neither on a key nor aliased", index.skippedWordCount < 5_000)
        assertTrue(index.maxFrequency > 0)
    }

    @Test
    fun gatesG1AndG2OnTheRealDictionary() {
        val decoder = sharedDecoder()
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
        val reusablePath = GlidePath(1024)

        val trainTop = IntArray(2)
        val heldTop = IntArray(2)
        var trainCount = 0
        var heldCount = 0
        // Per-class split (the doubled-letter evidence rule): plain words, doubled words whose
        // gesture drew the loop, and doubled words whose gesture did not — the no-jog class split
        // again by whether the undoubled twin exists in the dictionary (a twinless doubled word is
        // the only shape candidate on its path and keeps winning, which is correct). Words with an
        // alias letter (a long-press letter decoded on its base key) form a fifth class, so the
        // other four keep their membership.
        val classNames = arrayOf(
            "plain", "doubled_jog", "doubled_nojog_twinless", "doubled_nojog_twin", "alias",
        )
        val classHeldCount = IntArray(CLASS_COUNT)
        val classHeldTop = Array(CLASS_COUNT) { IntArray(2) }
        val timings = ArrayList<Long>()
        val candidates = ArrayList<Int>()
        val scored = ArrayList<Int>()

        // Train pass first: it doubles as the warmup (the lazy index build happens here). The
        // iteration unit is the SET ROW (a doubled word contributes two); the row carries its word
        // with it.
        for ((position, path) in paths.withIndex()) {
            if (path.rowContext.isNotEmpty()) continue // context rows belong to the G5 gate
            val word = path.rowWord
            val heldOut = isHeldOut(word)
            val started = System.nanoTime()
            val count = decodePath(path, decoder, reusablePath)
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
                val clazz = when {
                    hasAliasLetter(word) -> 4
                    !hasDoubledLetter(word) -> 0
                    path.drewLoop -> 1
                    twinInDictionary(word) -> 3
                    else -> 2
                }
                classHeldCount[clazz]++
                if (top1) classHeldTop[clazz][0]++
                if (top3) classHeldTop[clazz][1]++
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
        // The per-class printout of the doubled-letter evidence rule.
        val classTop1 = DoubleArray(CLASS_COUNT)
        val classTop3 = DoubleArray(CLASS_COUNT)
        for (clazz in 0 until CLASS_COUNT) {
            if (classHeldCount[clazz] == 0) continue
            classTop1[clazz] = classHeldTop[clazz][0].toDouble() / classHeldCount[clazz] * 100.0
            classTop3[clazz] = classHeldTop[clazz][1].toDouble() / classHeldCount[clazz] * 100.0
            println(
                "Glide P7-8 class ${classNames[clazz]}: n=${classHeldCount[clazz]} " +
                    "top1=${fmt(classTop1[clazz])}% " +
                    "top3=${fmt(classTop3[clazz])}%",
            )
        }

        assertTrue("held-out top-3 ${fmt(heldTop3)}% below the 60% gate", heldTop3 >= G1_TOP3_MIN)
        assertTrue("held-out top-1 ${fmt(heldTop1)}% below the 35% gate", heldTop1 >= G1_TOP1_MIN)
        // G2 is a wall-clock budget: asserted on developer hosts, skipped on shared CI runners
        // whose CPU scheduling makes any millisecond bound flaky. The measurement stays in the
        // printout above either way, and on-device latency is asserted by
        // GlideDeviceInstrumentationTest.
        if (System.getenv("CI") == null) {
            assertTrue("decode p95 ${fmt(p95)}ms over the 2ms host gate", p95 <= G2_P95_MS)
        }

        // Per-class tolerances of the doubled-letter evidence rule: plain words may not regress
        // more than 1.0 pp, a no-jog doubled word must usually lose to its dictionary twin (the
        // twin wins most of those rows), and a jog must still decode the doubled word. The pinned
        // values describe the persona-mixed set under the shipped constants.
        assertTrue(
            "plain top-1 regressed past the 1.0 pp tolerance (pinned 86.3396)",
            classTop1[0] >= 86.3396 - 1.0,
        )
        assertTrue(
            "plain top-3 regressed past the 1.0 pp tolerance (pinned 93.2179)",
            classTop3[0] >= 93.2179 - 1.0,
        )
        assertTrue(
            "a doubled word must not win its no-jog row when the twin exists (ceiling 25%)",
            classTop1[3] <= 25.0,
        )
        assertTrue(
            "doubled words must still decode when the path jogs (floor 70%)",
            classTop1[1] >= 70.0,
        )
        // A twinless doubled word also scores against its loop-free path, which no other word owns.
        assertTrue(
            "twinless no-jog top-1 regressed past the 1.0 pp tolerance (pinned 86.5672)",
            classTop1[2] >= 86.5672 - 1.0,
        )
        assertTrue(
            "twinless no-jog top-3 regressed past the 1.0 pp tolerance (pinned 93.5323)",
            classTop3[2] >= 93.5323 - 1.0,
        )
    }

    /**
     * The tuning surface: top-3 on the TRAIN split (every second row; the iteration unit is the set
     * ROW, a doubled word contributes two) over a sigma grid around the shipped constants — the
     * ported PR #1870 values (22.08 / 0.5109) included — plus a frequency-exponent row at the
     * shipped sigmas. The constants were chosen train-side; the held-out numbers of
     * [gatesG1AndG2OnTheRealDictionary] are the reported ones. Diagnostic only — it prints, it does
     * not assert a threshold.
     */
    @Test
    fun tuningSurfaceOnTheTrainSplit() {
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
        val train = trainWordIndices(paths)
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
                    if (decodeResult.words[slot] == paths[position].rowWord) hit = true
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

    /**
     * The speed-channel tuning surface: top-1 on the TRAIN split per persona class over the
     * (normative speed x widen cap) grid, diagnostic only. The shipped values were chosen here;
     * the held-out gate is [gatesG4SpeedAdaptationOnTheRealDictionary].
     */
    @Test
    fun tuningSurfaceSpeedChannelOnTheTrainSplit() {
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
        val train = trainWordIndices(paths)
        val reusablePath = GlidePath(1024)
        val inventory = TdictGlideInventory(realIndex!!)
        val wordIndex = GlideWordIndex.build(inventory, geometry)

        // (top1 per persona) for one constants set on the train rows.
        fun top1PerPersona(constants: GlideDecoder.GlideConstants): DoubleArray {
            val decoder = GlideDecoder(geometry, inventory, constants)
            decoder.preloadIndex(wordIndex)
            val top = IntArray(3)
            val count = IntArray(3)
            for (position in train) {
                val persona = personaOf(paths[position].rowWord)
                count[persona]++
                val found = decodePath(paths[position], decoder, reusablePath)
                if (found > 0 && decodeResult.words[0] == paths[position].rowWord) top[persona]++
            }
            return DoubleArray(3) { if (count[it] == 0) 0.0 else top[it].toDouble() / count[it] * 100.0 }
        }

        println("Glide P7-1 speed surface (train split, top-1 % per persona normal/fast/slow):")
        for (speed in listOf(0.012f, 0.016f, 0.02f, 0.024f, 0.03f)) {
            val row = StringBuilder("normative=%6.3f ".format(java.util.Locale.ROOT, speed))
            for (widen in listOf(1.0f, 1.5f, 2.0f, 3.0f)) {
                val top = top1PerPersona(
                    GlideDecoder.GlideConstants(
                        normativeSpeedRadiiPerMs = speed, speedWidenMax = widen,
                    ),
                )
                row.append(
                    " w=%3.1f:%5.2f/%5.2f/%5.2f".format(
                        java.util.Locale.ROOT, widen, top[0], top[1], top[2],
                    ),
                )
            }
            println(row.toString())
        }
    }

    // ---- G8: per-language constants and the length channel. ----

    /** One language's calibration inputs: dictionary inventory, layout and generator geometry. */
    private inner class LanguageBench(
        val name: String,
        val inventory: GlideWordInventory,
        val geometry: GlideKeyGeometry,
        val generatorGeometry: GeneratorGeometry,
        val words: List<String>,
        val sentences: List<String>,
    ) {
        /** The language's generated set rows, computed once per bench. */
        val paths: List<GeneratedPath> by lazy {
            renderSet(
                selectWords(words, sentences, generatorGeometry), generatorGeometry,
                selectContextPairs(words, sentences, generatorGeometry),
            ).second
        }

        /** The decoder word index over the bench's inventory, built once per bench. */
        val wordIndex: GlideWordIndex by lazy { GlideWordIndex.build(inventory, geometry) }
    }

    private fun tatarBench() = LanguageBench(
        "tt", TdictGlideInventory(realIndex!!), geometry, tatarGeneratorGeometry,
        vocabulary, evalLines,
    )

    private fun russianBench() = LanguageBench(
        "ru", TdictGlideInventory(russianIndex!!), russianGeometry, russianGeneratorGeometry,
        russianVocabulary, emptyList(),
    )

    /** (top1, top3) percentages of [constants] over the bench's rows, train or held-out split. */
    private fun measureTop(
        bench: LanguageBench,
        constants: GlideDecoder.GlideConstants,
        heldOut: Boolean,
    ): DoubleArray {
        val decoder = GlideDecoder(bench.geometry, bench.inventory, constants)
        decoder.preloadIndex(bench.wordIndex)
        val reusablePath = GlidePath(1024)
        var top1 = 0
        var top3 = 0
        var count = 0
        for (path in bench.paths) {
            if (path.rowContext.isNotEmpty()) continue
            if (isHeldOut(path.rowWord) != heldOut) continue
            count++
            val found = decodePath(path, decoder, reusablePath)
            if (found > 0 && decodeResult.words[0] == path.rowWord) top1++
            for (slot in 0 until minOf(3, found)) {
                if (decodeResult.words[slot] == path.rowWord) {
                    top3++
                    break
                }
            }
        }
        return doubleArrayOf(top1 * 100.0 / count, top3 * 100.0 / count)
    }

    /**
     * The G8 tuning surface: on each language's TRAIN rows — the length-channel sigma grid at the
     * shipped sigmas, the sigma grid at the chosen length sigma, and the top-1 split by word
     * length (the short-vs-long bias the length channel targets). Diagnostic only; the gate is
     * [gatesG8PerLanguageConstants].
     */
    @Test
    fun tuningSurfacePerLanguageAndLengthChannel() {
        val lengthSigmas = listOf(Float.POSITIVE_INFINITY, 6f, 4f, 3f, 2f)
        for (bench in listOf(tatarBench(), russianBench())) {
            println("Glide P7-10 ${bench.name} length-channel surface (train split, top-1 / top-3 %):")
            for (lengthSigma in lengthSigmas) {
                val top = measureTop(
                    bench,
                    GlideDecoder.GlideConstants(lengthStdFactor = lengthSigma),
                    heldOut = false,
                )
                println(
                    "  lengthStdFactor=${if (lengthSigma.isInfinite()) "off" else lengthSigma} " +
                        "top1=${fmt(top[0])}% top3=${fmt(top[1])}%",
                )
            }
            // The short-vs-long bias, with the length channel off and at the grid's best cell.
            for (lengthSigma in listOf(Float.POSITIVE_INFINITY, 4f)) {
                val perLength = measureTopPerLength(
                    bench,
                    GlideDecoder.GlideConstants(lengthStdFactor = lengthSigma),
                )
                println(
                    "  ${bench.name} top-1 by word length (5-6 / 7-9 / 10+ cp), " +
                        "lengthStdFactor=${if (lengthSigma.isInfinite()) "off" else lengthSigma}: " +
                        perLength.joinToString(" / ") { fmt(it) } + "%",
                )
            }
            println("Glide P7-10 ${bench.name} sigma grid (train split, top-1 %):")
            val shapeStds = listOf(6.9f, 8.28f, 9.66f, 11.04f)
            val locationFactors = listOf(0.09f, 0.11f, 0.1277f, 0.15f, 0.18f)
            val lengthSigmas2 = listOf(4f, 6f)
            for (lengthSigma in lengthSigmas2) {
                println("  lengthStdFactor=$lengthSigma:")
                println("  shapeStd\\locFactor " + locationFactors.joinToString(" ") { "%8.4f".format(it) })
                for (shapeStd in shapeStds) {
                    val row = StringBuilder("  %13.2f ".format(java.util.Locale.ROOT, shapeStd))
                    for (locationFactor in locationFactors) {
                        row.append(
                            "%9.4f".format(
                                java.util.Locale.ROOT,
                                measureTop(
                                    bench,
                                    GlideDecoder.GlideConstants(
                                        shapeStd = shapeStd, locationStdFactor = locationFactor,
                                        lengthStdFactor = lengthSigma,
                                    ),
                                    heldOut = false,
                                )[0],
                            ),
                        )
                    }
                    println(row.toString())
                }
            }
        }
    }

    /** Top-1 percentages on the train rows, bucketed by word code-point length (5-6, 7-9, 10+). */
    private fun measureTopPerLength(
        bench: LanguageBench,
        constants: GlideDecoder.GlideConstants,
    ): DoubleArray {
        val decoder = GlideDecoder(bench.geometry, bench.inventory, constants)
        decoder.preloadIndex(bench.wordIndex)
        val reusablePath = GlidePath(1024)
        val top = IntArray(3)
        val count = IntArray(3)
        for (path in bench.paths) {
            if (path.rowContext.isNotEmpty()) continue
            if (isHeldOut(path.rowWord)) continue
            val length = path.rowWord.codePointCount(0, path.rowWord.length)
            val bucket = if (length <= 6) 0 else if (length <= 9) 1 else 2
            count[bucket]++
            val found = decodePath(path, decoder, reusablePath)
            if (found > 0 && decodeResult.words[0] == path.rowWord) top[bucket]++
        }
        return DoubleArray(3) { if (count[it] == 0) 0.0 else top[it] * 100.0 / count[it] }
    }

    /**
     * The G8 gate: the per-language constants must beat or match the one shared constant set on
     * the held-out split of BOTH languages, top-1 and top-3. The per-language values were chosen
     * on the train splits ([tuningSurfacePerLanguageAndLengthChannel]).
     */
    @Test
    fun gatesG8PerLanguageConstants() {
        val lines = ArrayList<String>()
        var failures = 0
        for (bench in listOf(tatarBench(), russianBench())) {
            val shared = measureTop(bench, SHARED, heldOut = true)
            val perLanguage = measureTop(
                bench,
                if (bench.name == "tt") {
                    GlideDecoder.GlideConstants.TATAR
                } else {
                    GlideDecoder.GlideConstants.RUSSIAN
                },
                heldOut = true,
            )
            println(
                "Glide P7-10 ${bench.name} held-out: shared top1=${fmt(shared[0])}% " +
                    "top3=${fmt(shared[1])}% | per-language top1=${fmt(perLanguage[0])}% " +
                    "top3=${fmt(perLanguage[1])}%",
            )
            if (perLanguage[0] < shared[0]) {
                failures++
                lines.add(
                    "${bench.name}: per-language top-1 regressed vs the shared constants " +
                        "(${fmt(perLanguage[0])}% < ${fmt(shared[0])}%)",
                )
            }
            if (perLanguage[1] < shared[1]) {
                failures++
                lines.add(
                    "${bench.name}: per-language top-3 regressed vs the shared constants " +
                        "(${fmt(perLanguage[1])}% < ${fmt(shared[1])}%)",
                )
            }
        }
        assertTrue(lines.joinToString("; "), failures == 0)
    }

    // ---- G5: the bigram channel on the glide N-best. ----

    /**
     * Top-1 on the context rows of one split with the channel at [rankPenalty] (1f: off — the
     * plain decode). The oracle is the bundled Tatar bigram table, as in production.
     */
    private fun contextRowTop1(rankPenalty: Float, heldOut: Boolean): Pair<Double, Int> {
        val bigrams = tatarBigrams!!
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
        val decoder = sharedDecoder()
        val reusablePath = GlidePath(1024)
        val adjusted = FloatArray(GlideDecoder.TOP_N)
        var top1 = 0
        var count = 0
        for (path in paths) {
            if (path.rowContext.isEmpty()) continue
            if (isHeldOut(path.rowWord) != heldOut) continue
            count++
            val found = decodePath(path, decoder, reusablePath)
            if (found <= 0) continue
            val successors = try {
                bigrams.predict(
                    ImmutableUtf8Prefix.copyOf(path.rowContext.toByteArray(Charsets.UTF_8)),
                )
            } catch (_: RuntimeException) {
                emptyList()
            }
            GlideBigramRerank.rerank(decodeResult, successors, rankPenalty, adjusted)
            if (decodeResult.words[0] == path.rowWord) top1++
        }
        return (top1 * 100.0 / count) to count
    }

    /**
     * The G5 tuning surface: context-row top-1 on the TRAIN split over the rank-penalty grid.
     * Diagnostic only; the gate is [gatesG5BigramChannel].
     */
    @Test
    fun tuningSurfaceBigramChannelOnTheTrainSplit() {
        println("Glide P7-12 bigram channel (train context rows, top-1 %):")
        for (penalty in listOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)) {
            val (top1, count) = contextRowTop1(penalty, heldOut = false)
            println("  rankPenalty=%4.2f top1=%s%% (n=%d)".format(java.util.Locale.ROOT, penalty, fmt(top1), count))
        }
    }

    /**
     * The G5 gate: on the HELD-OUT context rows the channel (the shipped rank penalty) must beat
     * the plain decode's top-1. Non-context rows never see the channel (no context word), so they
     * are unchanged by construction — [gatesG1AndG2OnTheRealDictionary] covers them.
     */
    @Test
    fun gatesG5BigramChannel() {
        val (without, count) = contextRowTop1(1.0f, heldOut = true)
        val (with, _) = contextRowTop1(GlideDecoder.GlideConstants.TATAR.bigramRankPenalty, heldOut = true)
        println(
            "Glide P7-12 bigram channel held-out: top1_without=${fmt(without)}% " +
                "top1_with=${fmt(with)}% (n=$count)",
        )
        assertTrue(
            "the bigram channel must improve held-out context-row top-1 " +
                "(${fmt(with)}% vs ${fmt(without)}% without)",
            with > without,
        )
    }

    /** Every second TRAIN row's index (the tuning grid must not pay full-set decodes). */
    private fun trainWordIndices(paths: List<GeneratedPath>): List<Int> {
        val result = ArrayList<Int>(paths.size / 4)
        for ((index, path) in paths.withIndex()) {
            if (!isHeldOut(path.rowWord) && index % 2 == 0) result.add(index)
        }
        return result
    }

    /**
     * The G4 gate: the speed-adaptive location sigma (the shipped constants) must beat the
     * unadapted decoder on the fast-persona class of the held-out split, with no regression on
     * the other persona classes. The constants were chosen on the train split
     * ([tuningSurfaceSpeedChannelOnTheTrainSplit]).
     */
    @Test
    fun gatesG4SpeedAdaptationOnTheRealDictionary() {
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
        val reusablePath = GlidePath(1024)
        val inventory = TdictGlideInventory(realIndex!!)
        val wordIndex = GlideWordIndex.build(inventory, geometry)
        val adapted = GlideDecoder(geometry, inventory)
        adapted.preloadIndex(wordIndex)
        val unadapted = GlideDecoder(
            geometry, inventory, GlideDecoder.GlideConstants(speedWidenMax = 1f),
        )
        unadapted.preloadIndex(wordIndex)

        val personaNames = arrayOf("normal", "fast", "slow")
        val personaCount = IntArray(3)
        val personaAdaptedTop = IntArray(3)
        val personaUnadaptedTop = IntArray(3)
        for (path in paths) {
            if (path.rowContext.isNotEmpty()) continue
            if (!isHeldOut(path.rowWord)) continue
            val persona = personaOf(path.rowWord)
            personaCount[persona]++
            val adaptedCount = decodePath(path, adapted, reusablePath)
            if (adaptedCount > 0 && decodeResult.words[0] == path.rowWord) personaAdaptedTop[persona]++
            val unadaptedCount = decodePath(path, unadapted, reusablePath)
            if (unadaptedCount > 0 && decodeResult.words[0] == path.rowWord) {
                personaUnadaptedTop[persona]++
            }
        }
        val adaptedTop1 = DoubleArray(3)
        val unadaptedTop1 = DoubleArray(3)
        for (persona in 0 until 3) {
            adaptedTop1[persona] =
                personaAdaptedTop[persona].toDouble() / personaCount[persona] * 100.0
            unadaptedTop1[persona] =
                personaUnadaptedTop[persona].toDouble() / personaCount[persona] * 100.0
            println(
                "Glide P7-9 persona ${personaNames[persona]}: n=${personaCount[persona]} " +
                    "top1_adapted=${fmt(adaptedTop1[persona])}% " +
                    "top1_unadapted=${fmt(unadaptedTop1[persona])}%",
            )
        }
        assertTrue(
            "the speed channel must improve the fast-persona held-out top-1 " +
                "(${fmt(adaptedTop1[1])}% vs ${fmt(unadaptedTop1[1])}% unadapted)",
            adaptedTop1[1] > unadaptedTop1[1],
        )
        assertTrue(
            "the speed channel must not regress the normal-persona held-out top-1 past 1.0 pp",
            adaptedTop1[0] >= unadaptedTop1[0] - 1.0,
        )
        assertTrue(
            "the speed channel must not regress the slow-persona held-out top-1 past 1.0 pp",
            adaptedTop1[2] >= unadaptedTop1[2] - 1.0,
        )
    }

    @Test
    fun gateG3ZeroAllocationsAfterWarmupOnTheRealDictionary() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id

        val decoder = sharedDecoder()
        val words = selectWords(vocabulary, evalLines, tatarGeneratorGeometry)
        val (_, paths) = renderSet(words, tatarGeneratorGeometry, tatarContextPairs)
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
        // The only allocation of the decode path: each surfaced word materializes a String plus its
        // decoded backing array (the bound carries headroom). The machinery itself is pinned at
        // zero by the pruned path above.
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

    /** Class split: the word carries a letter that has no key of its own on the fixture. */
    private fun hasAliasLetter(word: String): Boolean {
        val keyLetters = GlideTestFixtures.tatarRawKeys().mapTo(HashSet()) { it.codePoint }
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            if (Character.toLowerCase(codePoint) !in keyLetters) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }

    /** Class split: the word carries a doubled letter (adjacent equal code points). */
    private fun hasDoubledLetter(word: String): Boolean {
        var previous = -1
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            if (codePoint == previous) return true
            previous = codePoint
            offset += Character.charCount(codePoint)
        }
        return false
    }

    /** The undoubled twin of [word] (every doubled run collapsed once) is a dictionary word,
     * so the two compete on the same path. */
    private fun twinInDictionary(word: String): Boolean {
        val twin = StringBuilder(word.length)
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            twin.appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
            if (offset < word.length && word.codePointAt(offset) == codePoint) {
                offset += Character.charCount(codePoint)
            }
        }
        return twin.toString() in vocabularySet
    }

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
        private const val CUT_MODULUS = 3L
        private const val CUT_DIVISOR = 20
        private const val STEP_DIVISOR = 6
        private const val TSTEP_MIN = 8
        private const val TSTEP_VAR = 11L
        private const val JITTER_PERCENT = 22
        private const val WANDER_DIVISOR = 6
        private const val GESTURE_OFFSET_PERCENT = 30
        private const val ENDPOINT_START_PERCENT = 18
        private const val ENDPOINT_END_PERCENT = 40
        private const val ENDPOINT_WIDE_MODULUS = 7L
        private const val ENDPOINT_WIDE_SCALE = 3
        // Persona knobs (mirror of glide_pack.py).
        private const val PERSONA_SEED = 0x50EF5AL
        private const val PERSONA_MODULUS = 3L
        private const val PERSONA_FAST = 1
        private const val PERSONA_SLOW = 2
        private const val PERSONA_FAST_NUM = 3
        private const val PERSONA_FAST_DEN = 2
        private const val PERSONA_SLOW_NUM = 2
        private const val PERSONA_SLOW_DEN = 3
        private const val TSTEP_FAST_MIN = 5
        private const val TSTEP_FAST_VAR = 7L
        private const val TSTEP_SLOW_MIN = 14
        private const val TSTEP_SLOW_VAR = 13L
        // Context rows (the bigram channel's calibration class; mirror of glide_pack.py).
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

        private const val CONTEXT_SEED = 0xC047E5L
        private const val CONTEXT_MODULUS = 4L

        private fun lettersMappable(word: String, letters: Set<Int>): Boolean {
            var offset = 0
            while (offset < word.length) {
                val codePoint = word.codePointAt(offset)
                offset += Character.charCount(codePoint)
                if (Character.toLowerCase(codePoint) !in letters) return false
            }
            return true
        }

        /** The pair's stream value: the thinning draw and the gesture's stream seed. */
        private fun contextPairSeed(previous: String, word: String): Long =
            splitmix64(CONTEXT_SEED xor fnv1a64("$previous $word".toByteArray(Charsets.UTF_8)))

        /**
         * (previous, word) pairs of consecutive eval tokens (mirror of
         * glide_pack.select_context_pairs): both dictionary words, the word mappable and at least
         * MIN_WORD_CODE_POINTS long, thinned by the pair draw.
         */
        private fun selectContextPairs(
            words: List<String>,
            sentences: List<String>,
            geo: GeneratorGeometry,
        ): List<Pair<String, String>> {
            val dictionary = HashSet(words)
            val pairs = sortedSetOf<Pair<String, String>>(
                compareBy({ it.first }, { it.second }),
            )
            for (line in sentences) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tokens = line.split(" ")
                for (i in 0 until tokens.size - 1) {
                    val previous = tokens[i]
                    val word = tokens[i + 1]
                    if (previous !in dictionary || word !in dictionary) continue
                    if (word.codePointCount(0, word.length) < MIN_WORD_CODE_POINTS) continue
                    if (!lettersMappable(word, geo.byLetter.keys)) continue
                    pairs.add(previous to word)
                }
            }
            return pairs.filter {
                java.lang.Long.remainderUnsigned(contextPairSeed(it.first, it.second), CONTEXT_MODULUS) == 0L
            }
        }

        // The pinned identity of the synthetic set (the same pins tests/glide_pack/ asserts). The
        // set carries both variants of every doubled word (rows outnumber words).
        private const val SET_SIZE = 4526
        private const val SET_BYTES = 11420862
        private const val SET_SHA256 =
            "4e2ea296ed6c492ca6f94cfb2f3db2f163fd3105d26354a9850df9e51bbcdc1d"

        // The per-class split of gatesG1AndG2OnTheRealDictionary.
        private const val CLASS_COUNT = 5

        // Recovery and host-latency gates.
        private const val G1_TOP3_MIN = 60.0
        private const val G1_TOP1_MIN = 35.0
        private const val G2_P95_MS = 2.0

        // The pre-G8 shared constant set (one set serving both languages): the G8 gate compares
        // the per-language constants against it on each language's held-out split.
        private val SHARED = GlideDecoder.GlideConstants(
            shapeStd = 11.04f,
            locationStdFactor = 0.18f,
            normativeSpeedRadiiPerMs = 0.016f,
            speedWidenMax = 1.5f,
            lengthStdFactor = Float.POSITIVE_INFINITY,
        )

        private val geometry = GlideTestFixtures.tatarGeometry()
        private val russianGeometry = GlideTestFixtures.russianGeometry()
        private var realIndex: TdictPrefixIndex? = null
        private var russianIndex: TdictPrefixIndex? = null
        private var tatarBigrams: TatBigrPrefixIndex? = null
        private var sharedDecoder: GlideDecoder? = null
        private lateinit var vocabulary: List<String>
        internal var vocabularySet: Set<String> = emptySet()
        private lateinit var russianVocabulary: List<String>
        internal var russianVocabularySet: Set<String> = emptySet()
        private lateinit var evalLines: List<String>

        /** The generator's geometry view of the Tatar fixture layout. */
        private val tatarGeneratorGeometry by lazy { GeneratorGeometry(GlideTestFixtures.tatarRawKeys()) }

        /** The eval-sentence context pairs of the Tatar set (mirror of glide_pack). */
        private val tatarContextPairs: List<Pair<String, String>> by lazy {
            selectContextPairs(vocabulary, evalLines, tatarGeneratorGeometry)
        }

        /** The generator's geometry view of the Russian fixture layout. */
        private val russianGeneratorGeometry by lazy { GeneratorGeometry(GlideTestFixtures.russianRawKeys()) }

        private fun sharedDecoder(): GlideDecoder {
            if (sharedDecoder == null) {
                sharedDecoder = GlideDecoder(geometry, TdictGlideInventory(realIndex!!))
            }
            return sharedDecoder!!
        }

        private fun loadDictionary(spec: DictionaryArtifactSpec): Pair<TdictPrefixIndex, List<String>> {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
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
                val index = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw),
                    identity,
                    validated.entryCount,
                    validated.rawSize,
                )
                check(index != null)
                val words = DictionaryTestFixtures.words(raw)
                check(words.size == spec.expectedEntryCount.toInt())
                return index to words
            } finally {
                rawFile.delete()
            }
        }

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssets() {
            val tatar = loadDictionary(DictionaryArtifactSpec.TATAR_TOP100K_V1)
            realIndex = tatar.first
            vocabulary = tatar.second
            vocabularySet = vocabulary.toSet()
            val russian = loadDictionary(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
            russianIndex = russian.first
            russianVocabulary = russian.second
            russianVocabularySet = russianVocabulary.toSet()
            evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8).map { it.trim() }
            tatarBigrams = loadBigramTable(BigramArtifactSpec.TATAR_BIGRAMS_V1, realIndex!!)
        }

        private fun loadBigramTable(
            spec: BigramArtifactSpec,
            dictionary: TdictPrefixIndex,
        ): TatBigrPrefixIndex {
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("glide-calibration-", ".tatbigr")
            try {
                rawFile.outputStream().use { output ->
                    TatBigrValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TatBigrValidator().validateRaw(rawFile, spec)
                val identity = BigramTableIdentity(
                    spec.generation,
                    spec.fileLanguageTag,
                    validated.schemaId,
                    validated.formatVersion,
                    validated.rawSha256,
                )
                return requireNotNull(
                    TatBigrPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()),
                        identity,
                        dictionary,
                        validated.headCount,
                        validated.rawSize,
                    ),
                )
            } finally {
                rawFile.delete()
            }
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed test resource")
    }
}
