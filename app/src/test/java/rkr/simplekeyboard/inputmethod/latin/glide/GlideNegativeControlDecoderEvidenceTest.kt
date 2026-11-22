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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic JVM counterpart of the device negative-control replay: the frozen records are
 * decoded through the production [GlideDecoder] with the same coordinate mapping
 * [GlideNegativeControlDeviceTest.replayCoordinates] uses (recorded key-radius units scaled onto
 * the reference keyboard's occupied width and letter-row block) and the recorded sample times, so
 * every number here is reproducible without an emulator.
 *
 * Three things are pinned:
 *
 *  1. The demonstrated false commits. The seven frozen non-tap negatives the device decider arms
 *     and delivers all decode to a non-empty top candidate — production commits that word on lift
 *     ([SuggestionsController.applyGlideResult] commits the decode head). The device test asserts
 *     these away only by failing; this test records the exact decodes so the acceptance gap stays
 *     measurable while the product decision (documented in the negative-control report) is open.
 *
 *  2. The separation evidence. The tremor record 83f39982-49 and its paired ordinary positive
 *     83f39982-35 are path twins (total travel within 0.2 key radii, identical top decode), so no
 *     geometry, tempo, straightness or score rule can reject the negative without rejecting the
 *     positive; and the delivered short drags share the length envelope of top1-correct short-word
 *     glides (ideal `ми` traces 1.25 radii, `ин` 3.0, `юл` 2.8, `өч` 4.6 — all asserted to decode
 *     below). Any plausibility floor low enough to empty the drags also empties those words.
 *
 *  3. Paired-positive retention. Every paired positive keeps its decoded candidates, with the
 *     prompt in the visible top 3 for the 24 records that hit there today; 83f39982-136 is pinned
 *     to its stable empty decode (same result under the collector mapping, the device mapping,
 *     recorded timestamps and live replay) and 23bdf97a-151 to a non-empty decode.
 *
 * This test never alters the frozen records or the decoder; it is the measurement the
 * zero-projected-commit acceptance has to be argued against.
 */
class GlideNegativeControlDecoderEvidenceTest {

    private data class FrozenRecord(val json: Map<*, *>) {
        val id: String get() = json["record_id"] as String
        val kind: String get() = json["trial_kind"] as String
        val prompt: String? get() = json["prompt"] as String?
        @Suppress("UNCHECKED_CAST")
        val geo: Map<String, Any?> get() = json["key_geometry"] as Map<String, Any?>
        val radius: Float get() = (geo["key_radius"] as Number).toFloat()
        val boardW: Float get() = (json["board_w"] as Number).toFloat()
        val boardH: Float get() = (json["board_h"] as Number).toFloat()
        @Suppress("UNCHECKED_CAST")
        val xs: List<Double> get() = json["path_x"] as List<Double>
        @Suppress("UNCHECKED_CAST")
        val ys: List<Double> get() = json["path_y"] as List<Double>
        @Suppress("UNCHECKED_CAST")
        val dts: List<Double> get() = json["dt_ms"] as List<Double>
    }

    private val geometry = GlideTestFixtures.tatarGeometry()
    private val decoder by lazy {
        GlideDecoder(geometry, TdictGlideInventory(openTatarIndex()), GlideDecoder.GlideConstants.TATAR)
    }

    /** The frozen negatives, in file order (record_id-sorted, manifest-pinned). */
    private val negatives: List<FrozenRecord> by lazy { load("frozen-negatives.jsonl") }

    /** The paired ordinary positives, in file order (manifest-pinned). */
    private val positives: List<FrozenRecord> by lazy { load("frozen-positive-pairs.jsonl") }

    /**
     * The records the device decider arms and delivers on the API 35 run
     * (build/fixes-device/glide-negative-controls.txt): five short drags and two tremors. Each
     * decodes to a non-empty top candidate — the false commit production would type on lift.
     */
    private val deviceDelivered = setOf(
        "23bdf97a-102", "23bdf97a-52", "83f39982-106", "83f39982-162", "83f39982-51",
        "83f39982-104", "83f39982-49",
    )

    @Test
    fun deliveredNonTapNegativesDecodeToCommittableWords() {
        val decoded = ArrayList<String>()
        for (record in negatives) {
            if (record.id !in deviceDelivered || record.kind == "tap") continue
            val result = decode(record)
            assertTrue(
                "${record.id} stopped projecting a commit — update the device-test gap list",
                result.count > 0,
            )
            decoded += "${record.id}(${record.kind}) -> ${result.words[0]}"
        }
        assertEquals(7, decoded.size)
        // The exact decodes of the API 35 run, pinned so the gap stays observable on the JVM.
        assertEquals("җ", topOf("23bdf97a-102"))
        assertEquals("ге", topOf("23bdf97a-52"))
        assertEquals("ин", topOf("83f39982-106"))
        assertEquals("һа", topOf("83f39982-162"))
        assertEquals("бо", topOf("83f39982-51"))
        assertEquals("тәки", topOf("83f39982-104"))
        assertEquals("инде", topOf("83f39982-49"))
    }

    @Test
    fun tremor83f39982_49IsAPathTwinOfItsPairedPositive() {
        val tremor = negatives.first { it.id == "83f39982-49" }
        val positive = positives.first { it.id == "83f39982-35" }
        // Both trace инде: travel within 0.2 radii, chord within 0.05, and the SAME top decode —
        // the tremor's top1 score (5.7e6 in this harness) sits inside the paired positives' top1
        // score range (1.4e6..1.5e11). Every input-side channel therefore sees the same gesture.
        assertEquals(
            "tremor travel diverged from its paired positive",
            pathLengthRadii(positive), pathLengthRadii(tremor), 0.2f,
        )
        assertEquals("инде", topOf("83f39982-49"))
        assertEquals("инде", topOf("83f39982-35"))
        assertEquals(
            "chord divergence grew beyond the twin bound",
            chordRadii(positive), chordRadii(tremor), 0.05f,
        )
    }

    @Test
    fun deliveredShortDragsShareTheLengthEnvelopeOfTop1CorrectShortWords() {
        // Any travel floor that empties the five drags (max 5.70 radii here, 5.76 under the
        // collector's mapping) sits below every paired positive (min 9.98) but ALSO below the
        // ideal traces of ми (1.25), юл (2.82), ин (3.00) and өч (4.56) — the next test pins
        // those words to decode. The gap is documented, not closed, for exactly this reason.
        val maxDrag = deviceDelivered
            .mapNotNull { id -> negatives.firstOrNull { it.id == id && it.kind == "short_drag" } }
            .maxOf { pathLengthRadii(it) }
        val minPositive = positives.minOf { pathLengthRadii(it) }
        assertTrue("drag envelope moved: $maxDrag", maxDrag < 5.8f)
        assertTrue("positive envelope moved: $minPositive", minPositive > 9.9f)
    }

    @Test
    fun shortWordGlidesDecodeTop1Correct() {
        // The legitimate input class any projected-commit rejection must not break: real Tatar
        // words whose normative ideal traces are shorter than the delivered false-commit drags.
        for (word in listOf("ми", "юл", "ин", "өч", "мә", "юк", "без")) {
            val path = GlideTestFixtures.idealPath(word, geometry)
            val result = GlideResult()
            val count = decoder.decode(requireNotNull(path), result)
            assertTrue("ideal $word decoded to nothing", count > 0)
            assertEquals("ideal $word lost its top-1 decode", word, result.words[0])
        }
    }

    @Test
    fun pairedPositivesRetainTheirDecodedCandidates() {
        // 23bdf97a-151's prompt ranks below the visible strip under this mapping (rank 7 in the
        // benchmark) and 83f39982-136 prunes to an empty decode; both keep their current
        // behavior. Every other paired positive keeps the prompt in the visible top 3 (all rank
        // 1 today except the timing-marginal 23bdf97a-106, top 2 on the device run).
        val visibleTop3Exceptions = setOf("23bdf97a-151", "83f39982-136")
        var retained = 0
        for (record in positives) {
            val result = decode(record)
            if (record.id == "83f39982-136") {
                assertEquals("83f39982-136 is empty today; its prune stopped holding", 0, result.count)
                continue
            }
            assertTrue("${record.id} lost its decoded candidates", result.count > 0)
            if (record.id !in visibleTop3Exceptions) {
                val top3 = (0 until minOf(3, result.count)).map { result.words[it]!! }
                assertTrue(
                    "${record.id} dropped ${record.prompt} out of the visible top 3: $top3",
                    record.prompt in top3,
                )
                retained++
            }
        }
        assertEquals(24, retained)
    }

    private fun topOf(recordId: String): String {
        // The twins assert across both files: the negative's top decode is compared against its
        // paired positive's, so look the record up in negatives first, then positives.
        val record = (negatives + positives).first { it.id == recordId }
        val result = decode(record)
        assertTrue("${record.id} decoded to nothing", result.count > 0)
        return result.words[0]!!
    }

    /** Device-mapped replay: recorded radius units onto the reference keyboard, recorded times. */
    private fun decode(record: FrozenRecord): GlideResult {
        val letterBottom = GlideTestFixtures.tatarRawKeys().maxOf { it.bottom }
        val recordedHeightUnits = record.boardH / record.boardW * GRID_WIDTH
        val path = GlidePath(record.xs.size + 8)
        var time = 0f
        for (index in record.xs.indices) {
            time += record.dts[index].toFloat()
            val x = record.xs[index].toFloat() * record.radius
            val y = record.ys[index].toFloat() * record.radius * letterBottom / recordedHeightUnits
            path.addPoint(x, y, time)
        }
        val result = GlideResult()
        decoder.decode(path, result)
        return result
    }

    private fun pathLengthRadii(record: FrozenRecord): Float {
        val letterBottom = GlideTestFixtures.tatarRawKeys().maxOf { it.bottom }
        val recordedHeightUnits = record.boardH / record.boardW * GRID_WIDTH
        var length = 0f
        for (index in 1 until record.xs.size) {
            val dx = (record.xs[index] - record.xs[index - 1]).toFloat() * record.radius
            val dy = (record.ys[index] - record.ys[index - 1]).toFloat() * record.radius *
                letterBottom / recordedHeightUnits
            length += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return length / geometry.keyRadius
    }

    private fun chordRadii(record: FrozenRecord): Float {
        val letterBottom = GlideTestFixtures.tatarRawKeys().maxOf { it.bottom }
        val recordedHeightUnits = record.boardH / record.boardW * GRID_WIDTH
        val dx = (record.xs.last() - record.xs.first()).toFloat() * record.radius
        val dy = (record.ys.last() - record.ys.first()).toFloat() * record.radius *
            letterBottom / recordedHeightUnits
        return kotlin.math.sqrt(dx * dx + dy * dy) / geometry.keyRadius
    }

    private fun load(name: String): List<FrozenRecord> {
        val file = ASSET_ROOTS.map { root -> File(root, "glide-negative-controls/$name") }
            .firstOrNull(File::isFile)
            ?: error("cannot locate glide-negative-controls/$name from ${File(".").absolutePath}")
        return file.readLines().filter { it.isNotBlank() }.map { line ->
            @Suppress("UNCHECKED_CAST")
            FrozenRecord(MinimalJson.parse(line) as Map<String, Any?>)
        }
    }

    private fun openTatarIndex(): TdictPrefixIndex {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val asset = listOf("src/main/assets/${spec.assetPath}", "app/src/main/assets/${spec.assetPath}")
            .map(::File).firstOrNull(File::isFile) ?: error("cannot locate ${spec.assetPath}")
        val rawFile = File.createTempFile("negative-evidence-", ".tdict")
        try {
            rawFile.outputStream().use { output ->
                TdictValidator().inflateAsset(asset.inputStream(), output, spec)
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            val raw = rawFile.readBytes()
            return requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), identity,
                    validated.entryCount, validated.rawSize,
                    autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                ),
            ) { "index did not open for ${spec.family}" }
        } finally {
            rawFile.delete()
        }
    }

    companion object {
        /** The unit-test working directory is the app module; the repo root is one level up. */
        private val ASSET_ROOTS = listOf("src/androidTest/assets", "app/src/androidTest/assets")

        /** The reference grid the collector records against (GlideTestFixtures.GRID_WIDTH). */
        private const val GRID_WIDTH = 100_000f
    }
}
