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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import android.test.InstrumentationTestCase
import android.util.Log
import android.view.ContextThemeWrapper
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.KeyNeighborTableBuilder
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * E3b/Phase-B device-side compute harness — the project's only instrumental test. It exists so
 * the fuzzy compute latency and the geometric-neighbour relation can be measured on real
 * hardware, which the host JVM cannot stand in for.
 *
 * TT-TYPO-NEXT Phases B/C (docs/TT-TYPO-NEXT.md) extended it in three ways:
 *
 *  1. It measures BOTH policies: the shipped [FuzzyEditPolicy.DEFAULT] (class #1 only — exactly
 *     the pre-Phase-B behavior) and the calibrated candidate [FuzzyEditPolicy.TATAR] (Phase C:
 *     classes #1 + #4 probe-first full substitution + same-length bonus), over the same 22 review
 *     prefixes AND over typo probes ("сцл", "сцлә", "сцләм", "сйл" — plus the 10-code-point
 *     "сцләмәтлек": 380 probes per lookup, the honest long-prefix class-#4 cost).
 *  2. [testLiveKeyboardPairSetMatchesTheOfflineModel] builds the Tatar alphabet keyboard exactly
 *     like production does (KeyboardLayoutSet over a themed context) and dumps the derived
 *     geometric pair set — the on-device counterpart of the offline `typo_pack.py` model.
 *  3. The embedded offline-model fixture now reproduces the device geometry WITH the horizontal
 *     gap (KeyboardRow subtracts it from every key), the same grid the JVM E3bTestFixtures uses.
 *
 * It lives in `app/src/androidTest` and is packaged only into the debug androidTest APK — never
 * the release APK. JUnit3 / legacy-runner style deliberately: the SDK's
 * `android.test.InstrumentationTestRunner` and `android.test.InstrumentationTestCase` resolve
 * offline, so the harness needs no downloaded androidx.test artifact.
 */
class E3bComputeInstrumentationTest : InstrumentationTestCase() {

    fun testComputeP50P95MaxOverReviewPrefixes() {
        val context = instrumentation.targetContext
        val raw = inflateTatarDictionary(context)
        for ((policyName, policy) in POLICIES) {
            val index = openIndex(raw, policy)
            index.updateKeyNeighbors(offlineModelNeighborTable())
            measure(index, REVIEW_PREFIXES, "review-$policyName")
            measure(index, TYPO_PROBES, "typo-$policyName")
        }
    }

    /**
     * Builds the live Tatar alphabet keyboard exactly as production does (themed context,
     * KeyboardLayoutSet, display metrics of this device) and logs the derived geometric pair set
     * plus the key-rectangle evidence (the gap between same-row keys). The pair list is compared
     * with the offline model of scripts/typo_pack.py in docs/TT-TYPO-NEXT.md.
     */
    fun testLiveKeyboardPairSetMatchesTheOfflineModel() {
        val context = instrumentation.targetContext
        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        val resources = themed.resources
        val width = resources.displayMetrics.widthPixels
        val height = ResourceUtils.getDefaultKeyboardHeight(resources)
        val layoutSet = KeyboardLayoutSet.Builder(themed, null)
            .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
            .setKeyboardGeometry(width, height, 0)
            .setSubtype(Subtype("tt_RU", "tatar", "tatar", false, resources))
            .setLanguageSwitchKeyEnabled(true)
            .setShowSpecialChars(true)
            .setShowNumberRow(false)
            .setShowEmojiKey(false)
            .build()
        val keyboard = layoutSet.getKeyboard(KeyboardId.ELEMENT_ALPHABET)
        val table = KeyNeighborTableBuilder.fromKeyboard(keyboard, "tt_RU")
        Log.i(TAG, "live keyboard: widthPx=$width heightPx=$height letterKeys=${table.letterKeyCount} nodes=${table.nodes.size}")

        // Key-rectangle evidence: the horizontal gap between two same-row neighbours (й,ц) and
        // the cross-row overlap that keeps the mission pair (ц,ә) connected.
        for (key in keyboard.sortedKeys) {
            if (key.code in PROBED_KEY_CODES) {
                Log.i(
                    TAG,
                    "key ${Integer.toHexString(key.code)} x=${key.x} y=${key.y} w=${key.width} h=${key.height}",
                )
            }
        }

        val pairs = sortedSetOf<String>()
        for (node in table.nodes) {
            for (partner in table.geometricNeighborsOf(node) ?: IntArray(0)) {
                if (node < partner) {
                    pairs.add("${Integer.toHexString(node)}-${Integer.toHexString(partner)}")
                }
            }
        }
        Log.i(TAG, "geometric pair count on device: ${pairs.size}")
        for (pair in pairs) Log.i(TAG, "pair $pair")
    }

    private fun measure(index: TdictPrefixIndex, prefixesCp: List<String>, label: String) {
        val prefixes = prefixesCp.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        repeat(200) { index.lookup(prefixes[it % prefixes.size]) }
        val timings = LongArray(2_000)
        var consumed = 0
        var probes = 0
        var maxProbes = 0
        for (sample in timings.indices) {
            val prefix = prefixes[sample % prefixes.size]
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            consumed = consumed xor results.size
            probes += index.lastFuzzyProbeCount
            maxProbes = maxOf(maxProbes, index.lastFuzzyProbeCount)
        }
        timings.sort()
        val p50 = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        val max = timings.last() / 1_000_000.0
        Log.i(
            TAG,
            "PhaseC device compute $label p50=${fmt(p50)} ms p95=${fmt(p95)} ms max=${fmt(max)} ms " +
                "samples=${timings.size} consumed=$consumed totalProbes=$probes maxProbes=$maxProbes",
        )
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.3f", value)

    private fun inflateTatarDictionary(context: android.content.Context): ByteArray {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val rawFile = File.createTempFile("e3b-compute-", ".tdict", context.cacheDir)
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

    private fun openIndex(raw: ByteArray, policy: FuzzyEditPolicy): TdictPrefixIndex {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val identity = DictionaryIdentity(
            spec.generation, spec.schemaId, spec.formatVersion, spec.expectedRawSha256,
        )
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                null, policy,
            ),
        )
    }

    // Offline-model neighbour table on the device-true grid (TT-TYPO-NEXT Phase B): the same
    // formula and constants as the JVM E3bTestFixtures — KeyboardRow subtracts the horizontal gap
    // from every key's width and advances by the padded width, so no two same-row keys touch.
    private fun rowGeometry(row: Int): List<Double> = when (row) {
        0 -> List(6) { 16.667 }
        1 -> List(11) { 9.091 }
        2 -> List(11) { 9.091 }
        3 -> listOf(10.8) + List(9) { 8.711 }
        else -> error("row $row")
    }

    private fun geoKey(base: Char, row: Int, col: Int, vararg partners: Char): KeyNeighborTable.RawKey {
        val widths = rowGeometry(row)
        var x = GRID_PADDING
        for (index in 0 until col) x += widths[index] / 100.0 * GRID_BASE_WIDTH
        var width = widths[col] / 100.0 * GRID_BASE_WIDTH - GRID_GAP
        if (x + width > GRID_RIGHT_EDGE) width = GRID_RIGHT_EDGE - x
        return KeyNeighborTable.RawKey(
            base.code, Math.round(x).toInt(), row, Math.round(x + width).toInt(), row + 1,
            IntArray(partners.size) { partners[it].code },
        )
    }

    private fun offlineModelNeighborTable(): KeyNeighborTable =
        KeyNeighborTable.build(
            "tt_RU", true,
            listOf(
                geoKey('ә', 0, 0), geoKey('ө', 0, 1), geoKey('ү', 0, 2),
                geoKey('җ', 0, 3), geoKey('ң', 0, 4), geoKey('һ', 0, 5),
                geoKey('й', 1, 0), geoKey('ц', 1, 1), geoKey('у', 1, 2, 'ү'),
                geoKey('к', 1, 3), geoKey('е', 1, 4, 'ё'), geoKey('н', 1, 5, 'ң'),
                geoKey('г', 1, 6, 'һ'), geoKey('ш', 1, 7), geoKey('щ', 1, 8),
                geoKey('з', 1, 9), geoKey('х', 1, 10, 'һ'),
                geoKey('ф', 2, 0), geoKey('ы', 2, 1), geoKey('в', 2, 2),
                geoKey('а', 2, 3, 'ә'), geoKey('п', 2, 4), geoKey('р', 2, 5),
                geoKey('о', 2, 6, 'ө'), geoKey('л', 2, 7), geoKey('д', 2, 8),
                geoKey('ж', 2, 9, 'җ'), geoKey('э', 2, 10, 'ә'),
                geoKey('я', 3, 1), geoKey('ч', 3, 2), geoKey('с', 3, 3),
                geoKey('м', 3, 4), geoKey('и', 3, 5), geoKey('т', 3, 6),
                geoKey('ь', 3, 7, 'ъ'), geoKey('б', 3, 8), geoKey('ю', 3, 9),
            ),
        )

    companion object {
        private const val TAG = "E3bCompute"

        // Device-true grid (100 000 px reference width): mirrors res/values/config.xml (phone
        // portrait) — 1.739%p horizontal gap, 0.870%p side paddings.
        private const val GRID_WIDTH = 100_000.0
        private const val GRID_GAP = 1.739 / 100.0 * GRID_WIDTH
        private const val GRID_PADDING = 0.870 / 100.0 * GRID_WIDTH
        private const val GRID_BASE_WIDTH = GRID_WIDTH - 2 * GRID_PADDING + GRID_GAP
        private const val GRID_RIGHT_EDGE = GRID_WIDTH - GRID_PADDING

        private val POLICIES = listOf(
            "default" to FuzzyEditPolicy.DEFAULT,
            "tatar" to FuzzyEditPolicy.TATAR,
        )

        // The 22 prefixes of docs/DICTIONARY-D1A-QUERY-REVIEW.tsv (RealDictionaryPrefixIndexTest
        // reads them from disk on the host; on device they are inlined query data, not layout).
        private val REVIEW_PREFIXES = listOf(
            "сә", "рәх", "исәнм", "хәерл", "безн", "татарч", "кеш", "бал", "мәкт", "китап", "эшл",
            "йорт", "авыл", "шәһ", "вак", "көн", "тел", "гаил", "әни", "әти", "дус", "яң",
        )

        // The Phase-B/C workload: the target case at 3/4/5 code points, "сйл" (a row of the
        // regenerated class-#2 set: сәләм with ә→й), and the 10-code-point "сцләмәтлек" — the
        // long-prefix class-#4 probe path (10 x 38 = 380 probes per lookup).
        private val TYPO_PROBES = listOf("сцл", "сцлә", "сцләм", "сйл", "сцләмәтлек")

        private val PROBED_KEY_CODES = setOf('й'.code, 'ц'.code, 'у'.code, 'ә'.code, 'ө'.code)
    }
}
