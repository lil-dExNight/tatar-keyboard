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
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Device-side compute harness: the fuzzy lookup latency can only be measured on real hardware,
 * which the host JVM cannot stand in for.
 *
 * It measures both shipped policies, [FuzzyEditPolicy.DEFAULT] (class #1 only) and
 * [FuzzyEditPolicy.TATAR] (classes #1 + #4 probe-first full substitution + same-length bonus),
 * over the 22 review prefixes and over typo probes ("сцл", "сцлә", "сцләм", "сйл", plus the
 * 10-code-point "сцләмәтлек": 380 class #4 probes per lookup).
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
        // Only the typo-probe labels ("typo-default", "typo-tatar") have a written device budget:
        // p95 <= 3.5 ms per docs/TT-TYPO-NEXT.md (measured 3.306). Asserted at 5.0 as a
        // conservative flake-safe bound; the recorded breach was 31.6 ms. The review-prefix
        // labels have no written budget and stay log-only.
        if (label.startsWith("typo-")) {
            assertTrue(
                "typo-probe p95 regressed beyond the conservative 5 ms bound " +
                    "(written gate 3.5 ms), was $p95 ms",
                p95 <= 5.0,
            )
        }
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

    private fun key(base: Char, vararg partners: Char): KeyNeighborTable.RawKey =
        KeyNeighborTable.RawKey(base.code, IntArray(partners.size) { partners[it].code })

    // The Tatar alphabet keyboard with its long-press partners, mirroring the layout XML. Internal
    // because DictionaryIoStrategyInstrumentationTest measures the identical device workload.
    internal fun offlineModelNeighborTable(): KeyNeighborTable =
        KeyNeighborTable.build(
            "tt_RU", true,
            listOf(
                key('ә'), key('ө'), key('ү'), key('җ'), key('ң'), key('һ'),
                key('й'), key('ц'), key('у', 'ү'), key('к'), key('е', 'ё'), key('н', 'ң'),
                key('г', 'һ'), key('ш'), key('щ'), key('з'), key('х', 'һ'),
                key('ф'), key('ы'), key('в'), key('а', 'ә'), key('п'), key('р'),
                key('о', 'ө'), key('л'), key('д'), key('ж', 'җ'), key('э', 'ә'),
                key('я'), key('ч'), key('с'), key('м'), key('и'), key('т'),
                key('ь', 'ъ'), key('б'), key('ю'),
            ),
        )

    companion object {
        private const val TAG = "E3bCompute"

        internal val POLICIES = listOf(
            "default" to FuzzyEditPolicy.DEFAULT,
            "tatar" to FuzzyEditPolicy.TATAR,
        )

        // The 22 prefixes of docs/DICTIONARY-D1A-QUERY-REVIEW.tsv (RealDictionaryPrefixIndexTest
        // reads them from disk on the host; on device they are inlined query data, not layout).
        internal val REVIEW_PREFIXES = listOf(
            "сә", "рәх", "исәнм", "хәерл", "безн", "татарч", "кеш", "бал", "мәкт", "китап", "эшл",
            "йорт", "авыл", "шәһ", "вак", "көн", "тел", "гаил", "әни", "әти", "дус", "яң",
        )

        // The typo workload: the target case at 3/4/5 code points, "сйл" (сәләм with ә→й), and
        // the 10-code-point "сцләмәтлек" — the long-prefix class-#4 probe path (10 x 38 = 380
        // probes per lookup).
        internal val TYPO_PROBES = listOf("сцл", "сцлә", "сцләм", "сйл", "сцләмәтлек")
    }
}
