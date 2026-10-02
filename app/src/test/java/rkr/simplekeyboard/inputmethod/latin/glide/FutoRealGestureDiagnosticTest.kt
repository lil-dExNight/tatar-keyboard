package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-gesture diagnostic over the FUTO swipe corpus (private: the data never enters git).
 * Inert unless FUTO_EVAL_FILE points at a local copy of the swipe-1 validation slice
 * (dev.jsonl of the MIT dataset published by FUTO); the QWERTY key rectangles come from a keys
 * TSV (FUTO_EVAL_KEYS, default keys-qwerty.tsv next to the data file; both are produced by
 * research/corpus/futo_glide_analysis.py, and the TSV's maximum key right/bottom is the
 * reference canvas the gesture coordinates are scaled by).
 *
 *   FUTO_EVAL_FILE=~/corpora-futo/dev.jsonl ./gradlew :app:testDebugUnitTest --tests '*FutoRealGestureDiagnosticTest*'
 *
 * The lexicon is built from the file itself: every target word plus every mappable token of the
 * prompt sentences, with its occurrence count as the frequency. The published SHARK2-style
 * anchor (~80% top-1 / ~90% top-3 on real QWERTY swipes) decodes against a 162k-word AOSP
 * wordlist EXTENDED with the targets; this lexicon is far smaller and contains every target, so
 * the printed accuracy is inflated relative to that protocol. Diagnostic only: the assertions
 * cover structural sanity (records parsed and decoded), never accuracy.
 */
class FutoRealGestureDiagnosticTest {

    @Test
    fun decodeRealGestures() {
        val evalName = System.getenv("FUTO_EVAL_FILE")
        assumeTrue("FUTO_EVAL_FILE not set: real-gesture diagnostic skipped", !evalName.isNullOrEmpty())
        val evalFile = File(evalName!!)
        assumeTrue("FUTO_EVAL_FILE does not exist: $evalFile", evalFile.isFile)
        val keysFile = File(
            System.getenv("FUTO_EVAL_KEYS")
                ?: File(evalFile.absoluteFile.parentFile, "keys-qwerty.tsv").path,
        )
        assumeTrue("keys TSV does not exist: $keysFile", keysFile.isFile)
        val limit = System.getenv("FUTO_EVAL_LIMIT")?.toIntOrNull() ?: 2000

        val rawKeys = keysFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            GlideKeyGeometry.RawKey(f[0].toInt(16), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt())
        }
        val geometry = GlideKeyGeometry.build(rawKeys)
        val canvasWidth = rawKeys.maxOf { it.right }.toFloat()
        val canvasHeight = rawKeys.maxOf { it.bottom }.toFloat()

        // The filters of research/corpus/futo_glide_analysis.py: the collector's distance
        // sentinel, empty or pathological digitizer runs, words off the a-z layout, and
        // one-letter targets (a degenerate ideal path).
        var parsed = 0
        var skipped = 0
        val records = ArrayList<Pair<String, FloatArray>>()
        val lexiconCounts = HashMap<String, Long>()
        evalFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = Json.parse(line) as? Map<*, *> ?: continue
                val word = (record["word"] as? String)?.lowercase() ?: continue
                (record["sentence"] as? String)?.let { sentence ->
                    for (token in sentence.split(' ')) {
                        val cleaned = token.lowercase().trim { it !in 'a'..'z' }
                        if (cleaned.isNotEmpty()) {
                            lexiconCounts.merge(cleaned, 1L, Long::plus)
                        }
                    }
                }
                val usable = (record["distance"] as? Double ?: 0.0) < 1000.0 &&
                    word.length in 2..32 && word.all { it in 'a'..'z' }
                @Suppress("UNCHECKED_CAST")
                val data = record["data"] as? List<Map<String, Double>>
                if (!usable || data == null || data.size !in 2..2048) {
                    skipped++
                    continue
                }
                parsed++
                lexiconCounts.merge(word, 1L, Long::plus)
                if (records.size < limit) {
                    val coords = FloatArray(data.size * 3)
                    for ((index, point) in data.withIndex()) {
                        coords[index * 3] = (point["x"]!!.toFloat() * canvasWidth)
                        coords[index * 3 + 1] = (point["y"]!!.toFloat() * canvasHeight)
                        coords[index * 3 + 2] = point["t"]!!.toFloat()
                    }
                    records.add(word to coords)
                }
            }
        }
        assertTrue("no usable records parsed from $evalFile", parsed > 0)
        assertTrue("no records decoded within the limit", records.isNotEmpty())

        val entries = lexiconCounts.entries.sortedBy { it.key }.map { it.key to it.value }
        val decoder = GlideDecoder(geometry, ListGlideInventory(entries))
        val result = GlideResult()
        val path = GlidePath(2048 + 8)
        var top1 = 0
        var top3 = 0
        var shortTop1 = 0
        var shortCount = 0
        var longTop1 = 0
        var longCount = 0
        for ((word, coords) in records) {
            path.clear()
            for (index in 0 until coords.size / 3) {
                path.addPoint(coords[index * 3], coords[index * 3 + 1], coords[index * 3 + 2])
            }
            val count = decoder.decode(path, result)
            val hit1 = count > 0 && result.words[0] == word
            var hit3 = hit1
            for (slot in 1 until minOf(3, count)) {
                if (result.words[slot] == word) hit3 = true
            }
            if (hit1) top1++
            if (hit3) top3++
            // The synthetic calibration set covers words of >= 5 code points only; report the
            // split so the comparison stays honest.
            if (word.length < 5) {
                shortCount++
                if (hit1) shortTop1++
            } else {
                longCount++
                if (hit1) longTop1++
            }
        }
        val n = records.size
        fun percent(value: Int, total: Int): String =
            "%.2f".format(java.util.Locale.ROOT, value * 100.0 / total)
        println(
            "FUTO real-gesture diagnostic: file=$evalName decoded=$n parsed=$parsed " +
                "skipped=$skipped lexicon=${entries.size} keyRadius=${geometry.keyRadius}",
        )
        println(
            "FUTO top1=${percent(top1, n)}% top3=${percent(top3, n)}% " +
                "(SHARK2-family anchor on this corpus: about 80% / 90% with a 162k-word lexicon " +
                "extended with the targets)",
        )
        println(
            "FUTO top1 by word length: <5cp ${percent(shortTop1, maxOf(1, shortCount))}% " +
                "(n=$shortCount) >=5cp ${percent(longTop1, maxOf(1, longCount))}% (n=$longCount)",
        )
    }

    /**
     * Minimal JSON reader for the FUTO record shape: objects, arrays, strings (with escapes),
     * numbers (as Double), true/false/null. Fails with [IllegalArgumentException] on malformed
     * input; the corpus is machine-generated, so no recovery is attempted.
     */
    private class Json private constructor(private val text: String) {
        private var pos = 0

        private fun error(): Nothing = throw IllegalArgumentException("malformed JSON at $pos")

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        private fun value(): Any? {
            skipWhitespace()
            if (pos >= text.length) error()
            return when (text[pos]) {
                '{' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    skipWhitespace()
                    if (pos < text.length && text[pos] == '}') {
                        pos++
                        return map
                    }
                    while (true) {
                        skipWhitespace()
                        val key = value() as? String ?: error()
                        skipWhitespace()
                        if (pos >= text.length || text[pos] != ':') error()
                        pos++
                        map[key] = value()
                        skipWhitespace()
                        if (pos >= text.length) error()
                        if (text[pos] == '}') {
                            pos++
                            return map
                        }
                        if (text[pos] != ',') error()
                        pos++
                    }
                }
                '[' -> {
                    pos++
                    val list = ArrayList<Any?>()
                    skipWhitespace()
                    if (pos < text.length && text[pos] == ']') {
                        pos++
                        return list
                    }
                    while (true) {
                        list.add(value())
                        skipWhitespace()
                        if (pos >= text.length) error()
                        if (text[pos] == ']') {
                            pos++
                            return list
                        }
                        if (text[pos] != ',') error()
                        pos++
                    }
                }
                '"' -> {
                    pos++
                    val out = StringBuilder()
                    while (pos < text.length && text[pos] != '"') {
                        val c = text[pos]
                        if (c == '\\') {
                            pos++
                            if (pos >= text.length) error()
                            out.append(
                                when (val escaped = text[pos]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    'b' -> '\b'
                                    'f' -> ''
                                    'u' -> {
                                        if (pos + 4 >= text.length) error()
                                        val code = text.substring(pos + 1, pos + 5).toInt(16)
                                        pos += 4
                                        code.toChar()
                                    }
                                    else -> escaped
                                },
                            )
                        } else {
                            out.append(c)
                        }
                        pos++
                    }
                    if (pos >= text.length) error()
                    pos++
                    out.toString()
                }
                't' -> {
                    if (!text.startsWith("true", pos)) error()
                    pos += 4
                    true
                }
                'f' -> {
                    if (!text.startsWith("false", pos)) error()
                    pos += 5
                    false
                }
                'n' -> {
                    if (!text.startsWith("null", pos)) error()
                    pos += 4
                    null
                }
                else -> {
                    val start = pos
                    while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
                    if (start == pos) error()
                    text.substring(start, pos).toDouble()
                }
            }
        }

        companion object {
            fun parse(text: String): Any? {
                val reader = Json(text)
                val value = reader.value()
                reader.skipWhitespace()
                if (reader.pos != text.length) reader.error()
                return value
            }
        }
    }
}
