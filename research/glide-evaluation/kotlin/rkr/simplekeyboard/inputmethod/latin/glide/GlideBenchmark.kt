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

import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Host-only bridge to the production decoder. The Python front end writes normalized JSONL here;
 * this main decodes every record through [GlideDecoder] and reports the target's index, endpoint,
 * length and result stages. Candidate scoring is never reimplemented; the target-only pruning
 * diagnostics read the same public index/constants and do not alter the production decode.
 *
 * This source is added to the app's JVM-test source set only by glide-benchmark.init.gradle. It
 * is never packaged in either APK. Environment contract:
 *   GLIDE_BENCHMARK_INPUT  normalized JSONL records
 *   GLIDE_BENCHMARK_OUTPUT bridge JSONL results
 *   GLIDE_BENCHMARK_ROOT   repository root (asset lookup)
 */
object GlideBenchmark {
    private const val EXTRA_ROW = "әөүҗңһёъ"

    @JvmStatic
    fun main(args: Array<String>) {
        val input = requiredFile("GLIDE_BENCHMARK_INPUT")
        val output = requiredPath("GLIDE_BENCHMARK_OUTPUT")
        val root = File(System.getenv("GLIDE_BENCHMARK_ROOT") ?: ".").canonicalFile
        val engines = HashMap<String, ArrayList<Engine>>()
        val records = ArrayList<Map<*, *>>()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) if (line.isNotBlank()) records += MinimalJson.parse(line) as Map<*, *>
        }
        // Resolve every custom geometry before decoding. A bad record fails the run instead of
        // silently disappearing from an accuracy denominator.
        val decoded = ArrayList<Map<String, Any?>>(records.size)
        val metadata = LinkedHashMap<String, Any?>()
        for (record in records) {
            val layout = string(record, "layout_id")
            val geometry = geometry(record, layout)
            // A live decoder is rebuilt when its keyboard geometry changes. Cache the same way:
            // one production decoder/index per distinct recorded geometry, never per record.
            val pool = engines.getOrPut(layout) { ArrayList() }
            val active = pool.firstOrNull { it.geometry.sameLayoutAs(geometry) }
                ?: Engine(root, layout, geometry).also { pool += it }
            decoded += decode(active, record)
            val geometryId = layout + ":" + pool.indexOf(active) + ":" +
                active.geometry.keyRadius + ":" + active.geometry.keyCount
            if (!metadata.containsKey(geometryId)) {
                metadata[geometryId] = linkedMapOf<String, Any?>(
                    "layout_id" to layout,
                    "key_radius" to active.geometry.keyRadius,
                    "key_count" to active.geometry.keyCount,
                    "alias_count" to active.geometry.aliasCount,
                    "dictionary_raw_sha256" to active.identity.rawSha256,
                    "dictionary_entry_count" to active.inventory.entryCount,
                    "indexed_word_count" to active.index.wordCount,
                    "unmappable_dictionary_word_count" to active.index.skippedWordCount,
                )
            }
        }
        output.parentFile?.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(JsonWriter.render(linkedMapOf<String, Any?>(
                "type" to "metadata",
                "production_decoder" to GlideDecoder::class.java.name,
                "geometries" to metadata,
            )))
            writer.newLine()
            for (record in decoded) {
                writer.write(JsonWriter.render(record))
                writer.newLine()
            }
        }
    }

    private fun decode(engine: Engine, record: Map<*, *>): Map<String, Any?> {
        val sequence = number(record, "record_index").toInt()
        val prompt = Normalizer.normalize(string(record, "prompt"), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
        val xs = doubles(record["path_x"])
        val ys = doubles(record["path_y"])
        val dts = doubles(record["dt_ms"])
        val path = GlidePath(xs.size + 8)
        var time = 0f
        for (i in xs.indices) {
            time += dts[i].toFloat()
            path.addPoint(xs[i].toFloat() * engine.geometry.keyRadius,
                ys[i].toFloat() * engine.geometry.keyRadius, time)
        }
        val out = GlideResult()
        val count = engine.decoder.decode(path, out)
        val candidates = ArrayList<String>(count)
        for (slot in 0 until count) candidates += out.words[slot]!!
        val normalizedCandidates = candidates.map {
            Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        }
        val rank = normalizedCandidates.indexOf(prompt).let { if (it < 0) 0 else it + 1 }

        val codePoints = prompt.codePoints().toArray()
        val missing = ArrayList<String>()
        val aliases = ArrayList<String>()
        val extraRow = ArrayList<String>()
        var doubled = false
        var verticalTransitions = 0
        var previousKey = -1
        var previousLetter = -1
        for (letter in codePoints) {
            val key = engine.geometry.keyIndexOfLetter(letter)
            if (key < 0) {
                val value = String(Character.toChars(letter))
                if (value !in missing) missing += value
            } else {
                val value = String(Character.toChars(letter))
                if (engine.aliasLetters.contains(letter) && value !in aliases) aliases += value
                if (value in EXTRA_ROW && value !in extraRow) extraRow += value
                if (previousKey >= 0 && key != previousKey &&
                    engine.geometry.centerY(key) != engine.geometry.centerY(previousKey)
                ) {
                    verticalTransitions++
                }
            }
            if (letter == previousLetter) doubled = true
            previousLetter = letter
            previousKey = key
        }

        val targetEntry = engine.entries[prompt]
        val targetIndexed = targetEntry != null &&
            engine.index.keySeqEnd(targetEntry) > engine.index.keySeqStart(targetEntry)
        var startPruned = false
        var endPruned = false
        var lengthPruned = false
        if (targetIndexed && targetEntry != null) {
            val startKey = engine.index.keySeqAt(engine.index.keySeqStart(targetEntry))
            val endKey = engine.index.keySeqAt(engine.index.keySeqEnd(targetEntry) - 1)
            val startKeys = IntArray(engine.constants.startExtremityNeighbors)
            val endKeys = IntArray(engine.constants.endExtremityNeighbors)
            engine.geometry.findClosestKeys(path.firstX, path.firstY, startKeys)
            engine.geometry.findClosestKeys(path.lastX, path.lastY, endKeys)
            startPruned = startKey !in startKeys
            endPruned = endKey !in endKeys
            val maxDistance = engine.constants.lengthThreshold * engine.geometry.keyRadius
            val plain = engine.index.plainLengthAt(targetEntry)
            val looped = engine.index.loopLengthAt(targetEntry)
            lengthPruned = abs(path.length() - plain) >= maxDistance &&
                (looped < 0f || abs(path.length() - looped) >= maxDistance)
        }
        val targetStage = when {
            missing.isNotEmpty() || !targetIndexed && targetEntry != null -> "unmappable"
            targetEntry == null -> "out_of_vocabulary"
            startPruned || endPruned -> "endpoint_pruned"
            lengthPruned -> "length_pruned"
            rank > 0 -> "returned"
            count == 0 -> "decoder_rejected"
            else -> "below_engine_top4"
        }
        return linkedMapOf<String, Any?>(
            "type" to "record",
            "record_index" to sequence,
            "normalized_prompt" to prompt,
            "candidates" to candidates,
            "rank" to rank,
            "decode_empty" to (count == 0),
            "path_too_short" to (path.size < 2),
            "candidate_count_after_endpoint_pruning" to engine.decoder.lastCandidateCount,
            "fully_scored_candidate_count" to engine.decoder.lastScoredCount,
            "target_indexed" to targetIndexed,
            "unmappable_letters" to missing,
            "alias_letters" to aliases,
            "doubled_letter" to doubled,
            "vertical_transition_count" to verticalTransitions,
            "extra_row_letters" to if (string(record, "layout_id") == "tt") extraRow else emptyList<String>(),
            "endpoint_start_pruned" to startPruned,
            "endpoint_end_pruned" to endPruned,
            "length_pruned" to lengthPruned,
            "target_stage" to targetStage,
        )
    }

    private fun geometry(record: Map<*, *>, layout: String): GlideKeyGeometry {
        val descriptor = record["key_geometry"] as? Map<*, *> ?: return when (layout) {
            "tt" -> GlideTestFixtures.tatarGeometry()
            "ru" -> GlideTestFixtures.russianGeometry()
            else -> throw IllegalArgumentException("unsupported layout $layout")
        }
        require(descriptor["model"] == "grid-v1") { "unsupported key geometry model" }
        val rawKeys = ArrayList<GlideKeyGeometry.RawKey>()
        for (item in descriptor["keys"] as List<*>) {
            val key = item as Map<*, *>
            val letter = string(key, "letter")
            val more = (key["more"] as? List<*> ?: emptyList<Any?>()).flatMap { value ->
                (value as String).codePoints().toArray().asIterable()
            }.toIntArray()
            rawKeys += GlideKeyGeometry.RawKey(
                letter.codePointAt(0),
                number(key, "left").toInt(), number(key, "top").toInt(),
                number(key, "right").toInt(), number(key, "bottom").toInt(), more,
            )
        }
        return GlideKeyGeometry.build(rawKeys)
    }

    private class Engine(root: File, layout: String, val geometry: GlideKeyGeometry) {
        val constants = if (layout == "ru") GlideDecoder.GlideConstants.RUSSIAN
            else GlideDecoder.GlideConstants.TATAR
        val identity: DictionaryIdentity
        val inventory: TdictGlideInventory
        val index: GlideWordIndex
        val decoder: GlideDecoder
        val entries: Map<String, Int>
        val aliasLetters: Set<Int>

        init {
            val spec = if (layout == "ru") DictionaryArtifactSpec.RUSSIAN_TOP100K_V1
                else DictionaryArtifactSpec.TATAR_TOP100K_V1
            val asset = File(root, "app/src/main/assets/${spec.assetPath}")
            val rawFile = File.createTempFile("glide-benchmark-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                val raw = rawFile.readBytes()
                val prefix = TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), identity,
                    validated.entryCount, validated.rawSize,
                    autocorrectMinCandidateFrequency = if (layout == "ru")
                        AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU
                    else AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                ) ?: throw IllegalArgumentException("dictionary did not open: ${spec.family}")
                inventory = TdictGlideInventory(prefix)
            } finally {
                rawFile.delete()
            }
            index = GlideWordIndex.build(inventory, geometry)
            decoder = GlideDecoder(geometry, inventory, constants)
            decoder.preloadIndex(index)
            val byWord = HashMap<String, Int>(inventory.entryCount)
            var entry = 0
            inventory.forEachWord { word, _ ->
                byWord[Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)] = entry
                entry++
            }
            entries = byWord
            val aliases = HashSet<Int>()
            for (slot in 0 until geometry.aliasCount) aliases += geometry.aliasLetterAt(slot)
            aliasLetters = aliases
        }
    }

    private fun requiredFile(name: String): File {
        val file = requiredPath(name)
        require(file.isFile) { "$name is not a file: $file" }
        return file
    }

    private fun requiredPath(name: String): File =
        File(System.getenv(name) ?: throw IllegalArgumentException("$name is required")).absoluteFile

    private fun string(record: Map<*, *>, key: String): String =
        record[key] as? String ?: throw IllegalArgumentException("missing string $key")

    private fun number(record: Map<*, *>, key: String): Double =
        record[key] as? Double ?: throw IllegalArgumentException("missing number $key")

    private fun doubles(value: Any?): List<Double> =
        (value as? List<*>)?.map { it as Double } ?: throw IllegalArgumentException("not a number array")
}

/** Small deterministic JSON writer; keys retain LinkedHashMap insertion order. */
private object JsonWriter {
    fun render(value: Any?): String {
        val out = StringBuilder()
        append(out, value)
        return out.toString()
    }

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int -> out.append(value)
            is Long -> out.append(value)
            is Float -> if (value.isFinite()) out.append(value) else out.append("null")
            is Double -> if (value.isFinite()) out.append(value) else out.append("null")
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, key as String)
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value ${value.javaClass}")
        }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ') out.append(String.format(Locale.ROOT, "\\u%04x", char.code))
                    else out.append(char)
            }
        }
        out.append('"')
    }
}
