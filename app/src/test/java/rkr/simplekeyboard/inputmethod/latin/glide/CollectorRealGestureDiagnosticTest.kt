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

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-gesture diagnostic over the project's own gesture collector (private recordings: the
 * JSONL never enters git). Inert unless TT_GESTURE_FILE points at an exported session file of
 * research/glide-collector/index.html:
 *
 *   TT_GESTURE_FILE=build/gesture-records/session-1.jsonl \
 *     ./gradlew :app:testDebugUnitTest --tests '*CollectorRealGestureDiagnosticTest*'
 *
 * The record's path_x/path_y are normalized by the collector's key radius, whose grid matches
 * [GlideTestFixtures.tatarGeometry] (same percent rows, gap, padding and vertical constants), so
 * a point replays as (coordinate x keyRadius) and dt_ms accumulates into the path timestamps.
 * Unlike the FUTO diagnostic the lexicon is the SHIPPED Tatar dictionary through the production
 * [TdictGlideInventory], not the recording itself: a prompt missing from the dictionary is a
 * genuine miss. There is no context channel here — the collector prompts single words.
 *
 * The assertions cover structural sanity only (records parsed and decoded); accuracy is printed,
 * never asserted. The printout lands in the test's XML report (system-out), and every miss is
 * listed with its top candidates so the failure classes are visible.
 */
class CollectorRealGestureDiagnosticTest {

    @Test
    fun decodeRecordedGestures() {
        val evalName = System.getenv("TT_GESTURE_FILE")
        assumeTrue("TT_GESTURE_FILE not set: collector diagnostic skipped", !evalName.isNullOrEmpty())
        val evalFile = File(evalName!!)
        assumeTrue("TT_GESTURE_FILE does not exist: $evalFile", evalFile.isFile)
        val limit = System.getenv("TT_GESTURE_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE

        val geometry = GlideTestFixtures.tatarGeometry()
        val radius = geometry.keyRadius
        val records = ArrayList<Pair<String, FloatArray>>()
        var parsed = 0
        var skipped = 0
        evalFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val record = MinimalJson.parse(line) as? Map<*, *> ?: continue
                if (record["layout_id"] != "tt") {
                    skipped++
                    continue
                }
                val prompt = record["prompt"] as? String ?: continue
                @Suppress("UNCHECKED_CAST")
                val xs = record["path_x"] as? List<Double>
                @Suppress("UNCHECKED_CAST")
                val ys = record["path_y"] as? List<Double>
                @Suppress("UNCHECKED_CAST")
                val dts = record["dt_ms"] as? List<Double>
                if (xs == null || ys == null || dts == null ||
                    xs.size != ys.size || xs.size != dts.size || xs.size !in 2..512
                ) {
                    skipped++
                    continue
                }
                parsed++
                if (records.size < limit) {
                    val coords = FloatArray(xs.size * 3)
                    var t = 0f
                    for (index in xs.indices) {
                        t += dts[index].toFloat()
                        coords[index * 3] = xs[index].toFloat() * radius
                        coords[index * 3 + 1] = ys[index].toFloat() * radius
                        coords[index * 3 + 2] = t
                    }
                    records.add(prompt to coords)
                }
            }
        }
        assertTrue("no usable records parsed from $evalFile", parsed > 0)
        assertTrue("no records decoded within the limit", records.isNotEmpty())

        val loaded = loadTatarDictionary()
        val decoder = GlideDecoder(geometry, TdictGlideInventory(loaded.index), GlideDecoder.GlideConstants.TATAR)
        val vocabulary = loaded.vocabulary
        val result = GlideResult()
        val path = GlidePath(512 + 8)
        var top1 = 0
        var top3 = 0
        var empty = 0
        val misses = ArrayList<String>()
        data class ClassStat(var n: Int = 0, var top1: Int = 0, var top3: Int = 0)
        val classes = linkedMapOf(
            "in-vocab" to ClassStat(),
            "oov" to ClassStat(),
            "doubled" to ClassStat(),
            "hard-sign" to ClassStat(),
            "h-letter" to ClassStat(),
            "short<5cp" to ClassStat(),
            "long>=5cp" to ClassStat(),
        )
        for ((prompt, coords) in records) {
            path.clear()
            for (index in 0 until coords.size / 3) {
                path.addPoint(coords[index * 3], coords[index * 3 + 1], coords[index * 3 + 2])
            }
            val count = decoder.decode(path, result)
            val candidates = (0 until count).map { result.words[it]!! }
            val hit1 = candidates.firstOrNull() == prompt
            val hit3 = prompt in candidates.take(3)
            if (hit1) top1++
            if (hit3) top3++
            if (count == 0) empty++
            val doubled = prompt.indices.any { it > 0 && prompt[it] == prompt[it - 1] }
            val tags = ArrayList<String>()
            val inVocab = prompt in vocabulary
            tags += if (inVocab) "in-vocab" else "oov"
            if (doubled) tags += "doubled"
            if ('ъ' in prompt) tags += "hard-sign"
            if ('һ' in prompt) tags += "h-letter"
            tags += if (prompt.length < 5) "short<5cp" else "long>=5cp"
            for (tag in tags) {
                val stat = classes.getValue(tag)
                stat.n++
                if (hit1) stat.top1++
                if (hit3) stat.top3++
            }
            if (!hit1) {
                misses += "$prompt -> ${candidates.take(3).joinToString(", ").ifEmpty { "<empty>" }}" +
                    (if (tags.isNotEmpty()) "  [${tags.joinToString(",")}]" else "")
            }
        }
        val n = records.size
        fun percent(value: Int, total: Int): String =
            "%.2f".format(java.util.Locale.ROOT, value * 100.0 / total)
        println(
            "TT collector diagnostic: file=$evalName decoded=$n parsed=$parsed skipped=$skipped " +
                "keyRadius=$radius",
        )
        println("TT collector top1=${percent(top1, n)}% top3=${percent(top3, n)}% empty=$empty")
        for ((tag, stat) in classes) {
            if (stat.n == 0) continue
            println(
                "TT collector class $tag: n=${stat.n} top1=${percent(stat.top1, stat.n)}% " +
                    "top3=${percent(stat.top3, stat.n)}%",
            )
        }
        println("TT collector misses (${misses.size}):")
        for (miss in misses) println("TT MISS $miss")
    }

    private fun loadTatarDictionary(): LoadedTatarDictionary {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val asset = listOf("src/main/assets/${spec.assetPath}", "app/src/main/assets/${spec.assetPath}")
            .map(::File).firstOrNull(File::isFile) ?: error("cannot locate ${spec.assetPath}")
        val rawFile = File.createTempFile("collector-eval-", ".tdict")
        try {
            rawFile.outputStream().use { output ->
                TdictValidator().inflateAsset(asset.inputStream(), output, spec)
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            val raw = rawFile.readBytes()
            val index = TdictPrefixIndex.open(
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), identity,
                validated.entryCount, validated.rawSize,
                autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            ) ?: error("index did not open for ${spec.family}")
            return LoadedTatarDictionary(index, DictionaryTestFixtures.words(raw).toSet())
        } finally {
            rawFile.delete()
        }
    }

    private class LoadedTatarDictionary(
        val index: TdictPrefixIndex,
        val vocabulary: Set<String>,
    )
}
