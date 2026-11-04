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

package rkr.simplekeyboard.inputmethod.latin.emoji

import android.graphics.Paint
import android.test.InstrumentationTestCase
import android.util.Log
import kotlin.math.ceil

/**
 * Device-side cost of reloading the emoji indexes after MSG_DEALLOCATE_MEMORY dropped them.
 * Both reloads run on their consumers' background executors, so the UI thread never pays this;
 * the test measures, over [RUNS] passes each:
 *
 *  - the panel search reload: SharedEmojiSearchIndex re-parse of emoji_search_v1.txt plus the
 *    glyph-available filter the panel applies;
 *  - the suggestion reload: emoji_suggest_v1.txt parse plus the glyph probe over the distinct
 *    emoji (the first AssetEmojiSuggestPreparation pass of a process), and the same parse
 *    filtered by the remembered verdicts (every later pass).
 *
 * Each phase is held to a p95 and a max budget; the slowest passes are the cold re-reads an
 * idle reload actually pays. Uses the JUnit3 legacy runner like the other device tests; not
 * part of the release APK.
 */
class EmojiIndexReloadInstrumentationTest : InstrumentationTestCase() {

    fun testReloadCostsOnDevice() {
        val context = instrumentation.targetContext

        // Panel search reload: a plain re-parse of the shared index asset.
        val search = phaseStats {
            context.assets.open(SharedEmojiSearchIndex.ASSET_PATH).use {
                EmojiSearchIndex.parse(it).entryCount
            }
        }

        // First suggestion pass of a process: parse, probe the font over every distinct emoji,
        // filter. A fresh probe each pass mirrors a fresh process.
        val probed = phaseStats {
            val table = context.assets.open(SUGGEST_ASSET).use { EmojiSuggestIndex.parse(it) }
            val probe = PaintGlyphProbe(Paint())
            val available = table.distinctEmoji().filterTo(HashSet()) { sequence ->
                try {
                    probe.hasGlyph(sequence)
                } catch (_: Throwable) {
                    false
                }
            }
            table.filterTo(available).entryCount
        }

        // Every later pass: the process-wide verdicts are already remembered, so the parse
        // filters without probing the font again.
        val verdicts = EmojiGlyphVerdicts()
        context.assets.open(SUGGEST_ASSET).use { stream ->
            verdicts.parse(stream) { PaintGlyphProbe(Paint()) }
        }
        val remembered = phaseStats {
            context.assets.open(SUGGEST_ASSET).use { stream ->
                verdicts.parse(stream) { error("a reload must not probe the font") }.entryCount
            }
        }

        Log.i(
            TAG,
            "emoji reload: searchParse=${fmt(search)} (entries=${search.result}) " +
                "suggestParseAndProbe=${fmt(probed)} (entries=${probed.result}) " +
                "suggestParseRemembered=${fmt(remembered)} (entries=${remembered.result})",
        )
        assertTrue("the search index must parse to something", search.result > 0)
        assertTrue("the suggest table must survive the glyph probe", probed.result > 0)
        assertEquals("remembered verdicts keep the probed table", probed.result, remembered.result)

        assertBudget("search-index re-parse", search, SEARCH_P95_BUDGET_MS, SEARCH_MAX_BUDGET_MS)
        assertBudget("suggest parse + glyph probe", probed, PROBE_P95_BUDGET_MS, PROBE_MAX_BUDGET_MS)
        assertBudget("suggest parse with remembered verdicts", remembered, REMEMBERED_P95_BUDGET_MS, REMEMBERED_MAX_BUDGET_MS)
    }

    /**
     * Runs [body] [RUNS] times, collecting wall-clock milliseconds and the last result, and
     * returns p50/p95/max plus that result.
     */
    private fun phaseStats(body: () -> Int): PhaseStats {
        var result = 0
        val timings = DoubleArray(RUNS)
        repeat(RUNS) { run ->
            val started = System.nanoTime()
            result = body()
            timings[run] = (System.nanoTime() - started) / 1_000_000.0
        }
        timings.sort()
        return PhaseStats(
            p50 = timings[timings.size / 2],
            p95 = timings[ceil(timings.size * 0.95).toInt() - 1],
            max = timings.last(),
            result = result,
        )
    }

    private fun assertBudget(label: String, stats: PhaseStats, p95Budget: Double, maxBudget: Double) {
        assertTrue(
            "$label p95 ${fmt(stats.p95)} ms is over the ${p95Budget} ms budget",
            stats.p95 <= p95Budget,
        )
        assertTrue(
            "$label max ${fmt(stats.max)} ms is over the ${maxBudget} ms budget",
            stats.max <= maxBudget,
        )
    }

    private fun fmt(stats: PhaseStats): String =
        "p50=${fmt(stats.p50)} ms p95=${fmt(stats.p95)} ms max=${fmt(stats.max)} ms"

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

    private data class PhaseStats(val p50: Double, val p95: Double, val max: Double, val result: Int)

    companion object {
        private const val TAG = "EmojiReload"
        private const val SUGGEST_ASSET = "emoji/emoji_suggest_v1.txt"

        /** Passes per phase. With this few samples the p95 is the slowest pass — the cold
         *  re-read an idle reload pays — which is what the budgets must survive. */
        private const val RUNS = 10

        // Budgets calibrated from the device-measured reload costs recorded when the lazy
        // emoji loads landed (search re-parse and suggest parse + probe were both around a
        // fifth of a second per pass on the reference device), with a wide margin: the first
        // pass re-reads the asset from flash, and the glyph probe cost varies with the font.
        // The remembered pass skips the probe entirely, so its budget sits well below the
        // probing one; a regression there means the verdicts stopped being reused.
        private const val SEARCH_P95_BUDGET_MS = 500.0
        private const val SEARCH_MAX_BUDGET_MS = 1200.0
        private const val PROBE_P95_BUDGET_MS = 500.0
        private const val PROBE_MAX_BUDGET_MS = 1200.0
        private const val REMEMBERED_P95_BUDGET_MS = 250.0
        private const val REMEMBERED_MAX_BUDGET_MS = 600.0
    }
}
