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

import android.test.InstrumentationTestCase
import android.util.Log
import android.view.ContextThemeWrapper
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.GlideKeyGeometryBuilder
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * P7-4 device-side glide harness (docs/GLIDE-PLAN.md): the decode latency gate (p95 ≤ 5 ms on
 * the POCO C71) can only be measured on real hardware, and the сәләм decode is proven against
 * the LIVE Tatar keyboard geometry (built through the production KeyboardLayoutSet path, the
 * same construction E3bComputeInstrumentationTest uses for the neighbour table).
 *
 * Same JUnit3/legacy-runner shape as the E3b harness — resolves offline, never packaged into
 * the release APK.
 */
class GlideDeviceInstrumentationTest : InstrumentationTestCase() {

    /** Decode proof on the real dictionary over the live keyboard geometry: сәләм in top-N. */
    fun testSalamDecodesOnTheLiveGeometry() {
        val context = instrumentation.targetContext
        val geometry = liveTatarGeometry(context)
        assertFalse("live geometry must not be empty", geometry.isEmpty)
        val index = openIndex(inflateTatarDictionary(context))
        val decoder = GlideDecoder(geometry, TdictGlideInventory(index))
        val path = requireNotNull(pathThrough(geometry, "сәләм")) {
            "every letter of сәләм must have a key in the live geometry"
        }
        val out = GlideResult()
        val count = decoder.decode(path, out)
        Log.i(TAG, "live keyboard: keyCount=${geometry.keyCount}")
        val top = (0 until count).map { out.words[it] }
        Log.i(TAG, "decode of сәләм path: count=$count top=$top")
        assertTrue("сәләм must be in the top-$count: $top", top.contains("сәләм"))
    }


    /** The P7-4 gate: decode p95 ≤ 5 ms over the real dictionary on this device. */
    fun testDecodeLatencyOnDevice() {
        val context = instrumentation.targetContext
        val geometry = liveTatarGeometry(context)
        val index = openIndex(inflateTatarDictionary(context))
        val decoder = GlideDecoder(geometry, TdictGlideInventory(index))
        val paths = PROBE_WORDS.mapNotNull { pathThrough(geometry, it) }
        assertEquals(PROBE_WORDS.size, paths.size)
        // Warmup: index build + first decodes.
        val out = GlideResult()
        repeat(100) { decoder.decode(paths[it % paths.size], out) }
        val timings = LongArray(SAMPLES)
        var consumed = 0
        for (sample in timings.indices) {
            val started = System.nanoTime()
            consumed = consumed xor decoder.decode(paths[sample % paths.size], out)
            timings[sample] = System.nanoTime() - started
        }
        timings.sort()
        val p50 = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        val max = timings.last() / 1_000_000.0
        Log.i(
            TAG,
            "P7-4 device glide decode p50=${fmt(p50)} ms p95=${fmt(p95)} ms max=${fmt(max)} ms " +
                "samples=${timings.size} consumed=$consumed gate=p95<=5.0ms " +
                "verdict=${if (p95 <= 5.0) "PASS" else "FAIL"}",
        )
    }

    /** A realistic gesture through the word's key centers: the ideal polyline sampled ~8 px. */
    private fun pathThrough(geometry: GlideKeyGeometry, word: String): GlidePath? {
        val centers = ArrayList<Pair<Float, Float>>(word.length)
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            offset += Character.charCount(codePoint)
            val key = geometry.keyIndexOfLetter(Character.toLowerCase(codePoint))
            if (key < 0) return null
            centers.add(geometry.centerX(key) to geometry.centerY(key))
        }
        val path = GlidePath()
        var t = 0f
        for (segment in 0 until centers.size - 1) {
            val (x1, y1) = centers[segment]
            val (x2, y2) = centers[segment + 1]
            val dx = x2 - x1
            val dy = y2 - y1
            val steps = maxOf(2, (kotlin.math.hypot(dx, dy) / 8f).toInt())
            for (step in 0 until steps) {
                path.addPoint(x1 + dx * step / steps, y1 + dy * step / steps, t)
                t += 8f
            }
        }
        centers.last().let { path.addPoint(it.first, it.second, t) }
        return path
    }

    private fun liveTatarGeometry(context: android.content.Context): GlideKeyGeometry {
        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        val resources = themed.resources
        val layoutSet = KeyboardLayoutSet.Builder(themed, null)
            .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
            .setKeyboardGeometry(
                resources.displayMetrics.widthPixels,
                ResourceUtils.getDefaultKeyboardHeight(resources),
                0,
            )
            .setSubtype(Subtype("tt_RU", "tatar", "tatar", false, resources))
            .setLanguageSwitchKeyEnabled(true)
            .setShowSpecialChars(true)
            .setShowNumberRow(false)
            .setShowEmojiKey(false)
            .build()
        return GlideKeyGeometryBuilder.fromKeyboard(layoutSet.getKeyboard(KeyboardId.ELEMENT_ALPHABET))
    }

    private fun inflateTatarDictionary(context: android.content.Context): ByteArray {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val rawFile = File.createTempFile("glide-device-", ".tdict", context.cacheDir)
        try {
            context.assets.open(spec.assetPath).use { input ->
                rawFile.outputStream().use { output -> TdictValidator().inflateAsset(input, output, spec) }
            }
            TdictValidator().validateRaw(rawFile, spec)
            return rawFile.readBytes()
        } finally {
            rawFile.delete()
        }
    }

    private fun openIndex(raw: ByteArray): TdictPrefixIndex {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val identity = DictionaryIdentity(
            spec.generation, spec.schemaId, spec.formatVersion, spec.expectedRawSha256,
        )
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                null, FuzzyEditPolicy.DEFAULT,
            ),
        )
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.3f", value)

    companion object {
        private const val TAG = "GlideDevice"
        private const val SAMPLES = 1_000
        // Probe words with every letter on the Tatar layout (ё/ъ never glide — P7-1 note).
        private val PROBE_WORDS = listOf("сәләм", "татар", "сакчы", "теле", "дәүләт", "белән")
    }
}
