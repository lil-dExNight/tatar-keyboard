package rkr.simplekeyboard.inputmethod.latin.golden

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.CompositeGlideInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideDecoder
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGestureDecider
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideResampler
import rkr.simplekeyboard.inputmethod.latin.glide.GlideResult
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordIndex
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory
import java.io.File
import java.io.Writer
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Glide golden-vector exporter (`glide.jsonl`). Inert unless GLIDE_GOLDEN_OUT names an existing
 * directory holding keys-tt.tsv / keys-ru.tsv; its output feeds the iOS port's parity suite.
 * Nothing here reaches the APK.
 *
 *   GLIDE_GOLDEN_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*GlideGoldenExportTest*'
 *
 * Both sides decode identical inputs: the integer key rectangles are written into the file, and
 * the synthetic gestures come from a mirror of the `scripts/glide_pack.py` generator (checked
 * against GlideRecoveryCalibrationTest's pinned render). Records: geometry tables, word-index
 * digests, the rendered-set identity, every row's top-8 words + score bits + prune counters, a
 * personal-dictionary composite, decider scripts and resampler vectors.
 */
class GlideGoldenExportTest {

    @Test
    fun export() {
        val outName = System.getenv("GLIDE_GOLDEN_OUT")
        assumeTrue("GLIDE_GOLDEN_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "GLIDE_GOLDEN_OUT must be an existing directory" }

        val tatar = openIndex(DictionaryArtifactSpec.TATAR_TOP100K_V1)
        val russian = openIndex(DictionaryArtifactSpec.RUSSIAN_TOP100K_V1)
        val evalLines = locate("src/test/resources/tt_eval_sentences.txt")
            .readLines(Charsets.UTF_8).map { it.trim() }

        File(out, "glide.jsonl").bufferedWriter(Charsets.UTF_8).use { w ->
            val fixtureTt = Geo("fixture-tt", "tt", GlideTestFixtures.tatarRawKeys())
            val fixtureRu = Geo("fixture-ru", "ru", GlideTestFixtures.russianRawKeys())
            // The key files carry rectangles only; the long-press keys come from the fixture of
            // the same layout, so the aliases match the built keyboard.
            val keysTt = Geo("keys-tt", "tt", withMoreKeys(readKeys(File(out, "keys-tt.tsv")), fixtureTt.raw))
            val keysRu = Geo("keys-ru", "ru", withMoreKeys(readKeys(File(out, "keys-ru.tsv")), fixtureRu.raw))

            val ttWords = selectWords(tatar.second, fixtureTt.raw, evalLines, true)
            val ruWords = selectWords(russian.second, keysRu.raw, emptyList(), false)

            for ((geo, lang) in listOf(fixtureTt to tatar, keysTt to tatar, keysRu to russian, fixtureRu to russian)) {
                val words = if (geo.lang == "tt") ttWords else ruWords
                // fixture-ru pins the geometry, index and set only (the e2e Russian test's layout).
                exportGeometry(w, geo, lang.first, words, decodes = geo.id != "fixture-ru")
            }
            exportPersonal(w, keysTt, tatar.first, ttWords)
            exportPersonal(w, fixtureTt, tatar.first, ttWords)
            exportDecider(w)
            exportResampler(w)
        }
    }

    private class Geo(val id: String, val lang: String, val raw: List<GlideKeyGeometry.RawKey>) {
        val geometry: GlideKeyGeometry = GlideKeyGeometry.build(raw)
    }

    // ---------------------------------------------------------------------------------------
    // Geometry, index, set, decodes.

    private fun exportGeometry(w: Writer, geo: Geo, index: TdictPrefixIndex, words: List<String>, decodes: Boolean) {
        val g = geo.geometry
        val sb = StringBuilder("{\"kind\":\"geometry\",\"id\":").append(json(geo.id))
            .append(",\"lang\":").append(json(geo.lang)).append(",\"raw\":[")
        geo.raw.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append('[').append(k.codePoint).append(',').append(k.left).append(',').append(k.top)
                .append(',').append(k.right).append(',').append(k.bottom).append(']')
        }
        sb.append("],\"keyCount\":").append(g.keyCount)
            .append(",\"keyRadius\":").append(bits(g.keyRadius))
        val cx = (0 until g.keyCount).map { bits(g.centerX(it)) }
        val cy = (0 until g.keyCount).map { bits(g.centerY(it)) }
        val hw = (0 until g.keyCount).map { bits(g.halfWidth(it)) }
        val hh = (0 until g.keyCount).map { bits(g.halfHeight(it)) }
        sb.append(",\"cx\":").append(cx).append(",\"cy\":").append(cy)
            .append(",\"hw\":").append(hw).append(",\"hh\":").append(hh)
        // Letter per key index: every raw letter's lookup.
        val lookups = geo.raw.map { g.keyIndexOfLetter(Character.toLowerCase(it.codePoint)) }
        sb.append(",\"lookups\":").append(lookups)
        // Long-press codes per raw key and the resulting alias table (letter, key index).
        sb.append(",\"more\":").append(geo.raw.map { it.moreKeyCodePoints.toList() })
        sb.append(",\"aliases\":").append((0 until g.aliasCount).map { listOf(g.aliasLetterAt(it), g.aliasKeyAt(it)) })
        sb.append('}')
        w.write(sb.toString()); w.write("\n")

        val inventory = TdictGlideInventory(index)
        val wordIndex = GlideWordIndex.build(inventory, g)
        writeIndexDigest(w, geo.id, "base", wordIndex, inventory.entryCount, g.keyCount)

        val (rendered, paths) = renderSet(words, geo.raw)
        val bytes = rendered.toString().toByteArray(Charsets.UTF_8)
        val sha = sha256(bytes)
        if (geo.id == "fixture-tt") {
            // The generator mirror must reproduce the pinned calibration set byte for byte.
            assertEquals(4531, words.size)
            assertEquals(10195627, bytes.size)
            assertEquals("7c497d92be0e1a31741254de827b37004b13f79f1335a6d6f8a3e71916606a6b", sha)
        }
        w.write("{\"kind\":\"set\",\"id\":${json(geo.id)},\"words\":${words.size},\"rows\":${paths.size}," +
            "\"bytes\":${bytes.size},\"sha256\":${json(sha)}}\n")

        if (!decodes) return
        val decoder = GlideDecoder(g, inventory)
        val result = GlideResult()
        val reusable = GlidePath(1024)
        for ((row, path) in paths.withIndex()) {
            fill(reusable, path)
            val count = decoder.decode(reusable, result)
            writeDecode(w, "decode", geo.id, row, path.rowWord, path.drewLoop, count, result,
                decoder.lastCandidateCount, decoder.lastScoredCount, null)
        }
    }

    private fun writeIndexDigest(w: Writer, id: String, variant: String, index: GlideWordIndex, entryCount: Int, keyCount: Int) {
        var plain = FNV_OFFSET
        var loop = FNV_OFFSET
        var seq = FNV_OFFSET
        var freq = FNV_OFFSET
        for (entry in 0 until entryCount) {
            plain = fnvLong(plain, bits(index.plainLengthAt(entry)).toLong())
            loop = fnvLong(loop, bits(index.loopLengthAt(entry)).toLong())
            seq = fnvLong(seq, index.keySeqStart(entry).toLong())
            for (p in index.keySeqStart(entry) until index.keySeqEnd(entry)) seq = fnvLong(seq, index.keySeqAt(p).toLong())
            freq = fnvLong(freq, index.frequencyAt(entry))
        }
        var offsets = FNV_OFFSET
        var entries = FNV_OFFSET
        for (s in 0 until keyCount) for (e in 0 until keyCount) {
            offsets = fnvLong(offsets, index.pairRangeStart(s, e).toLong())
            for (p in index.pairRangeStart(s, e) until index.pairRangeEnd(s, e)) entries = fnvLong(entries, index.pairEntryAt(p).toLong())
        }
        w.write("{\"kind\":\"index\",\"id\":${json(id)},\"variant\":${json(variant)},\"wordCount\":${index.wordCount}," +
            "\"skipped\":${index.skippedWordCount},\"maxFrequency\":${index.maxFrequency}," +
            "\"retained\":${index.retainedByteEstimate},\"plain\":${json(hex(plain))},\"loop\":${json(hex(loop))}," +
            "\"seq\":${json(hex(seq))},\"freq\":${json(hex(freq))},\"offsets\":${json(hex(offsets))}," +
            "\"entries\":${json(hex(entries))}}\n")
    }

    private fun writeDecode(
        w: Writer, kind: String, id: String, row: Int, word: String, loop: Boolean, count: Int,
        result: GlideResult, candidates: Int, scored: Int, snapshot: String?,
    ) {
        val sb = StringBuilder("{\"kind\":").append(json(kind)).append(",\"id\":").append(json(id))
        if (snapshot != null) sb.append(",\"snap\":").append(json(snapshot))
        sb.append(",\"row\":").append(row).append(",\"word\":").append(json(word))
            .append(",\"loop\":").append(loop).append(",\"top\":[")
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append(json(result.words[i]!!))
        }
        sb.append("],\"scores\":[")
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append(bits(result.scores[i]))
        }
        sb.append("],\"cand\":").append(candidates).append(",\"scored\":").append(scored).append('}')
        w.write(sb.toString()); w.write("\n")
    }

    // ---------------------------------------------------------------------------------------
    // The personal composite: a deterministic snapshot of learned
    // words (new words, casing overrides of dictionary words, an unmappable word), decoded on
    // every 7th set row and on each learned word's own generated gesture.

    private fun exportPersonal(w: Writer, geo: Geo, index: TdictPrefixIndex, words: List<String>) {
        val letters = geo.raw.map { it.codePoint }.toSet()
        val alphabet = "абвгдежзийклмнопрстуфхцчшщыэюяәөүҗңһ"
        val entries = ArrayList<Pair<String, Int>>()
        val learned = ArrayList<String>()
        var i = 0
        while (i < words.size && entries.size < 90) {
            val word = words[i]
            val usage = (i * 7) % 23 + 1
            when ((i / 50) % 3) {
                0 -> {
                    // A new word: the last letter swapped until the result is no dictionary word.
                    val cps = word.codePoints().toArray()
                    for (k in alphabet.indices) {
                        cps[cps.size - 1] = alphabet[(k + i) % alphabet.length].code
                        val candidate = String(cps, 0, cps.size)
                        if (!index.containsWordCold(candidate) && lettersMappable(candidate, letters)) {
                            entries.add(candidate to usage)
                            learned.add(candidate)
                            break
                        }
                    }
                }
                1 -> entries.add(word.replaceFirstChar { it.uppercaseChar() } to usage) // casing override
                else -> entries.add((word + "x") to usage) // unmappable on the Cyrillic layouts
            }
            i += 50
        }
        // Normalized-ascending, as the store publishes (the fixture's own rule).
        val snapshot = GlideTestFixtures.personalDictionary(*entries.toTypedArray())
        val sb = StringBuilder("{\"kind\":\"personalSnapshot\",\"id\":").append(json(geo.id))
            .append(",\"snap\":\"p1\",\"raw\":[")
        for (k in 0 until snapshot.size) { if (k > 0) sb.append(','); sb.append(json(snapshot.rawFormAt(k))) }
        sb.append("],\"norm\":[")
        for (k in 0 until snapshot.size) { if (k > 0) sb.append(','); sb.append(json(snapshot.normalizedFormAt(k))) }
        sb.append("],\"usage\":[")
        for (k in 0 until snapshot.size) { if (k > 0) sb.append(','); sb.append(snapshot.usageCountAt(k)) }
        sb.append("]}")
        w.write(sb.toString()); w.write("\n")

        val base = TdictGlideInventory(index)
        val composite: GlideWordInventory = CompositeGlideInventory(base, snapshot, index::containsWordCold)
        // The composite's own walk and materialization, digested.
        var walk = FNV_OFFSET
        var count = 0
        composite.forEachWord { word, frequency ->
            if (count >= base.entryCount) {
                walk = fnvString(walk, word)
                walk = fnvLong(walk, frequency)
            }
            count++
        }
        val tail = (base.entryCount until composite.entryCount).map { composite.wordAt(it) }
        val overrides = ArrayList<String>()
        for (k in 0 until snapshot.size) {
            val norm = snapshot.normalizedFormAt(k)
            if (index.containsWordCold(norm)) {
                val entry = findEntry(index, norm)
                overrides.add(composite.wordAt(entry))
            }
        }
        w.write("{\"kind\":\"personalInventory\",\"id\":${json(geo.id)},\"snap\":\"p1\",\"entryCount\":${composite.entryCount}," +
            "\"walkTail\":${json(hex(walk))},\"tail\":${jsonList(tail)},\"overrides\":${jsonList(overrides)}}\n")
        val compositeIndex = GlideWordIndex.build(composite, geo.geometry)
        writeIndexDigest(w, geo.id, "p1", compositeIndex, composite.entryCount, geo.geometry.keyCount)

        val decoder = GlideDecoder(geo.geometry, composite)
        val result = GlideResult()
        val reusable = GlidePath(1024)
        val (_, paths) = renderSet(words, geo.raw)
        for ((row, path) in paths.withIndex()) {
            if (row % 7 != 0) continue
            fill(reusable, path)
            val n = decoder.decode(reusable, result)
            writeDecode(w, "pdecode", geo.id, row, path.rowWord, path.drewLoop, n, result,
                decoder.lastCandidateCount, decoder.lastScoredCount, "p1")
        }
        val (_, learnedPaths) = renderSet(learned, geo.raw)
        for ((row, path) in learnedPaths.withIndex()) {
            fill(reusable, path)
            val n = decoder.decode(reusable, result)
            writeDecode(w, "pword", geo.id, row, path.rowWord, path.drewLoop, n, result,
                decoder.lastCandidateCount, decoder.lastScoredCount, "p1")
        }
    }

    private fun findEntry(index: TdictPrefixIndex, word: String): Int {
        var low = 0
        var high = index.entryCount
        val key = word.toByteArray(Charsets.UTF_8)
        while (low < high) {
            val mid = (low + high) ushr 1
            val probe = index.wordAt(mid).toByteArray(Charsets.UTF_8)
            var order = 0
            for (k in 0 until minOf(probe.size, key.size)) {
                val d = (probe[k].toInt() and 0xff) - (key[k].toInt() and 0xff)
                if (d != 0) { order = d; break }
            }
            if (order == 0) order = probe.size - key.size
            if (order < 0) low = mid + 1 else if (order > 0) high = mid else return mid
        }
        error("not found")
    }

    // ---------------------------------------------------------------------------------------
    // Decider scripts: deterministic pseudo-random touch sequences over the reference-phone
    // thresholds (key width 98 px, 0.10 dp/ms at 2.75 density, 500 ms, slop 0.25 x key width).

    private fun exportDecider(w: Writer) {
        var stream = 0x5EED_61DEL
        fun next(bound: Long): Long {
            stream = splitmix64(stream)
            return java.lang.Long.remainderUnsigned(stream, bound)
        }
        for (script in 0 until 600) {
            val keyWidth = 60f + next(80).toFloat()
            val density = 2f + next(3).toFloat() * 0.5f
            val params = floatArrayOf(keyWidth, 0.10f * density, 500f, keyWidth * 0.25f)
            val decider = GlideGestureDecider(params[0], params[1], 500L, params[3])
            val events = StringBuilder()
            val states = ArrayList<String>()
            var t = 1_000_000L + next(1000)
            var x = 500f
            var y = 300f
            val count = 3 + next(40).toInt()
            for (e in 0 until count) {
                if (e > 0) events.append(',')
                val choice = if (e == 0) 0L else next(100)
                when {
                    e == 0 || choice < 4 -> {
                        val eligible = next(10) != 0L
                        x = 100f + next(900).toFloat()
                        y = 100f + next(500).toFloat()
                        t += next(300)
                        decider.onDown(x, y, t, eligible)
                        events.append("[\"d\",").append(bits(x)).append(',').append(bits(y)).append(',')
                            .append(t).append(',').append(eligible).append(']')
                    }
                    choice < 7 -> { decider.cancelGlide(); events.append("[\"c\"]") }
                    choice < 9 -> { decider.onUpOrCancel(); events.append("[\"u\"]") }
                    else -> {
                        val speed = next(4)
                        val step = when (speed) { 0L -> next(4); 1L -> next(12); 2L -> next(40); else -> next(120) }
                        x += (next(2 * step + 1) - step).toFloat() + next(8).toFloat() / 8f
                        y += (next(step + 1) - step / 2).toFloat()
                        t += next(if (speed == 0L) 400 else 40)
                        decider.onMove(x, y, t)
                        events.append("[\"m\",").append(bits(x)).append(',').append(bits(y)).append(',')
                            .append(t).append(']')
                    }
                }
                states.add(decider.state.name)
            }
            w.write("{\"kind\":\"decider\",\"params\":[${bits(params[0])},${bits(params[1])},500,${bits(params[3])}]," +
                "\"events\":[$events],\"states\":${jsonList(states)}}\n")
        }
    }

    private fun exportResampler(w: Writer) {
        var stream = 0x0DDBA11L
        fun next(bound: Long): Long {
            stream = splitmix64(stream)
            return java.lang.Long.remainderUnsigned(stream, bound)
        }
        for (case in 0 until 80) {
            val size = 1 + next(30).toInt()
            val xs = FloatArray(size)
            val ys = FloatArray(size)
            for (i in 0 until size) {
                // Repeated points (zero-length segments) on purpose, plus sub-unit fractions.
                if (i > 0 && next(5) == 0L) { xs[i] = xs[i - 1]; ys[i] = ys[i - 1]; continue }
                xs[i] = next(20_000).toFloat() / (1 + next(4)).toFloat()
                ys[i] = next(9_000).toFloat() / (1 + next(3)).toFloat()
            }
            val n = if (case % 3 == 0) 200 else 1 + next(60).toInt()
            val outX = FloatArray(n)
            val outY = FloatArray(n)
            GlideResampler.resample(xs, ys, size, outX, outY, n)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until size) {
                if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
                if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
            }
            val nx = FloatArray(n)
            val ny = FloatArray(n)
            GlideResampler.normalizeByBoxSide(outX, outY, n, minX, maxX, minY, maxY, nx, ny)
            val path = GlidePath(64)
            for (i in 0 until size) path.addPoint(xs[i], ys[i], i * 8f)
            w.write("{\"kind\":\"resample\",\"xs\":${bitsList(xs)},\"ys\":${bitsList(ys)},\"n\":$n," +
                "\"outX\":${bitsList(outX)},\"outY\":${bitsList(outY)},\"nx\":${bitsList(nx)},\"ny\":${bitsList(ny)}," +
                "\"length\":${bits(path.length())}}\n")
        }
    }

    // ---------------------------------------------------------------------------------------
    // The generator: GlideRecoveryCalibrationTest's mirror of scripts/glide_pack.py, verbatim,
    // parametrized by the key rectangles.

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

    private fun lettersMappable(word: String, letters: Set<Int>): Boolean {
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.toLowerCase(codePoint) !in letters) return false
        }
        return true
    }

    private fun selectWords(vocabulary: List<String>, raw: List<GlideKeyGeometry.RawKey>,
                            evalLines: List<String>, withEval: Boolean): List<String> {
        val letters = rectsByLetter(raw).keys
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
        if (withEval) {
            for (line in evalLines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                for (token in line.split(" ")) {
                    if (token.codePointCount(0, token.length) < MIN_WORD_CODE_POINTS) continue
                    if (token !in dictionary) continue
                    if (lettersMappable(token, letters)) selected.add(token)
                }
            }
        }
        return selected.toList()
    }

    private class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private class GeneratedPath(val xs: IntArray, val ys: IntArray, val ts: IntArray, val drewLoop: Boolean) {
        var rowWord: String = ""
    }

    /**
     * Letter -> rectangle, every long-press letter without a key of its own on its base key's
     * rectangle; a letter on several keys takes the key [GlideKeyGeometry] resolves it to.
     */
    private fun rectsByLetter(raw: List<GlideKeyGeometry.RawKey>): Map<Int, Rect> {
        val rects = HashMap<Int, Rect>()
        for (key in raw) rects[key.codePoint] = Rect(key.left, key.top, key.right, key.bottom)
        val geometry = GlideKeyGeometry.build(raw)
        val byKeyIndex = HashMap<Int, Rect>()
        for (key in raw) byKeyIndex[geometry.keyIndexOfLetter(key.codePoint)] = rects.getValue(key.codePoint)
        for (slot in 0 until geometry.aliasCount) {
            rects[geometry.aliasLetterAt(slot)] = byKeyIndex.getValue(geometry.aliasKeyAt(slot))
        }
        return rects
    }

    private fun hasDoubledKey(word: String, byLetter: Map<Int, Rect>): Boolean {
        var previous: Rect? = null
        for (codePoint in word.codePoints().toArray()) {
            val rect = byLetter.getValue(Character.toLowerCase(codePoint))
            if (rect === previous) return true
            previous = rect
        }
        return false
    }

    private fun withMoreKeys(
        keys: List<GlideKeyGeometry.RawKey>,
        fixture: List<GlideKeyGeometry.RawKey>,
    ): List<GlideKeyGeometry.RawKey> {
        val more = fixture.associate { it.codePoint to it.moreKeyCodePoints }
        return keys.map {
            GlideKeyGeometry.RawKey(it.codePoint, it.left, it.top, it.right, it.bottom, more[it.codePoint] ?: IntArray(0))
        }
    }

    private fun generateGesture(word: String, drawLoop: Boolean, raw: List<GlideKeyGeometry.RawKey>): GeneratedPath {
        val byLetter = rectsByLetter(raw)
        val minWidth = raw.minOf { it.right - it.left }
        val radius = raw.minOf { minOf(it.right - it.left, it.bottom - it.top) }

        val codePoints = word.codePoints().toArray()
        val hasDouble = hasDoubledKey(word, byLetter)
        var stream = splitmix64(GLIDE_SEED xor fnv1a64(word.toByteArray(Charsets.UTF_8)))
        val loop = drawLoop && hasDouble
        val stepMin = minWidth / STEP_DIVISOR
        val step = stepMin + java.lang.Long.remainderUnsigned(stream, stepMin.toLong()).toInt()
        stream = splitmix64(stream)
        val tstep = TSTEP_MIN + java.lang.Long.remainderUnsigned(stream, TSTEP_VAR).toInt()
        stream = splitmix64(stream)

        val vertexX = IntArray(5 * codePoints.size)
        val vertexY = IntArray(5 * codePoints.size)
        val vertexIsLoop = BooleanArray(5 * codePoints.size)
        var vertexCount = 0
        var previous: Rect? = null
        for (codePoint in codePoints) {
            val rect = byLetter.getValue(Character.toLowerCase(codePoint))
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

        val cut = BooleanArray(vertexCount)
        for (v in 1 until vertexCount - 1) {
            if (vertexIsLoop[v]) continue
            cut[v] = java.lang.Long.remainderUnsigned(stream, CUT_MODULUS) == 0L
            stream = splitmix64(stream)
        }
        val cutX = vertexX.copyOf(vertexCount)
        val cutY = vertexY.copyOf(vertexCount)
        for (v in 1 until vertexCount - 1) {
            if (!cut[v]) continue
            cutX[v] = (vertexX[v - 1] + 2 * vertexX[v] + vertexX[v + 1]) / 4
            cutY[v] = (vertexY[v - 1] + 2 * vertexY[v] + vertexY[v + 1]) / 4
        }

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

        val jitter = radius * JITTER_PERCENT / 100
        val wander = jitter / WANDER_DIVISOR
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
        return GeneratedPath(xs, ys, ts, loop)
    }

    private fun renderSet(words: List<String>, raw: List<GlideKeyGeometry.RawKey>): Pair<StringBuilder, List<GeneratedPath>> {
        val rendered = StringBuilder()
        val paths = ArrayList<GeneratedPath>(words.size)
        val byLetter = rectsByLetter(raw)
        for (word in words) {
            appendRow(word, generateGesture(word, false, raw), rendered, paths)
            if (hasDoubledKey(word, byLetter)) appendRow(word, generateGesture(word, true, raw), rendered, paths)
        }
        return rendered to paths
    }

    private fun appendRow(word: String, path: GeneratedPath, rendered: StringBuilder, paths: ArrayList<GeneratedPath>) {
        path.rowWord = word
        paths.add(path)
        rendered.append(word).append('\t')
        for (i in path.xs.indices) {
            if (i > 0) rendered.append(';')
            rendered.append(path.xs[i]).append(',').append(path.ys[i]).append(',').append(path.ts[i])
        }
        rendered.append('\n')
    }

    private fun fill(target: GlidePath, path: GeneratedPath) {
        target.clear()
        for (i in path.xs.indices) target.addPoint(path.xs[i].toFloat(), path.ys[i].toFloat(), path.ts[i].toFloat())
    }

    // ---------------------------------------------------------------------------------------
    // Assets, keys and the JSON writer.

    private fun openIndex(spec: DictionaryArtifactSpec): Pair<TdictPrefixIndex, List<String>> {
        val asset = locate("src/main/assets/${spec.assetPath}")
        val rawFile = File.createTempFile("glide-golden-", ".tdict")
        try {
            rawFile.outputStream().use { TdictValidator().inflateAsset(asset.inputStream(), it, spec) }
            val v = TdictValidator().validateRaw(rawFile, spec)
            val raw = rawFile.readBytes()
            val identity = DictionaryIdentity(spec.generation, v.schemaId, v.formatVersion, v.rawSha256)
            val index = requireNotNull(TdictPrefixIndex.open(ByteBuffer.wrap(raw), identity, v.entryCount, v.rawSize))
            return index to DictionaryTestFixtures.words(raw)
        } finally {
            rawFile.delete()
        }
    }

    private fun readKeys(file: File): List<GlideKeyGeometry.RawKey> =
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            GlideKeyGeometry.RawKey(f[0].toInt(16), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt())
        }

    private fun bits(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

    private fun bitsList(values: FloatArray): String = values.joinToString(",", "[", "]") { bits(it).toString() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fnvLong(hash: Long, value: Long): Long {
        var h = hash
        for (shift in 0 until 64 step 8) {
            h = h xor ((value ushr shift) and 0xffL)
            h *= 0x100000001B3L
        }
        return h
    }

    private fun fnvString(hash: Long, value: String): Long {
        var h = hash
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            h = h xor (byte.toLong() and 0xffL)
            h *= 0x100000001B3L
        }
        return h
    }

    private fun hex(value: Long): String = java.lang.Long.toHexString(value)

    private fun jsonList(values: List<String>): String = values.joinToString(",", "[", "]") { json(it) }

    private fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun locate(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull(File::isFile) ?: error("cannot locate $path")

    companion object {
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        private const val GLIDE_SEED = 20260924L
        private const val MIN_WORD_CODE_POINTS = 5
        private const val DICT_MODULUS = 40
        private const val CUT_MODULUS = 10L
        private const val STEP_DIVISOR = 6
        private const val TSTEP_MIN = 8
        private const val TSTEP_VAR = 9L
        private const val JITTER_PERCENT = 18
        private const val WANDER_DIVISOR = 6
    }
}
