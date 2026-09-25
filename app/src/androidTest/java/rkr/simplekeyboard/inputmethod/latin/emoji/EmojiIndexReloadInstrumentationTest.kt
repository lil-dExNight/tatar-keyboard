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

/**
 * O2 (docs/OPTIMIZE-2026-09-25.md): the device-side price of the emoji indexes' idle release —
 * what a lazy RELOAD costs after MSG_DEALLOCATE_MEMORY dropped them. Both reloads run on their
 * consumers' background executors, so the UI thread never pays this; the test only puts numbers
 * on the worker-side work:
 *
 *  - the panel search reload: SharedEmojiSearchIndex re-parse of emoji_search_v1.txt plus the
 *    glyph-available filter the panel applies;
 *  - the suggest reload: emoji_suggest_v1.txt parse plus the glyph probe over the distinct
 *    emoji (the AssetEmojiSuggestPreparation pass).
 *
 * Same JUnit3/legacy-runner shape as the other device harnesses here — never packaged into the
 * release APK.
 */
class EmojiIndexReloadInstrumentationTest : InstrumentationTestCase() {

    fun testReloadCostsOnDevice() {
        val context = instrumentation.targetContext

        var searchMs = 0.0
        var searchEntries = 0
        run {
            val started = System.nanoTime()
            val index = context.assets.open(SharedEmojiSearchIndex.ASSET_PATH).use {
                EmojiSearchIndex.parse(it)
            }
            searchMs = (System.nanoTime() - started) / 1_000_000.0
            searchEntries = index.entryCount
        }

        var suggestMs = 0.0
        var suggestEntries = 0
        var drawable = 0
        run {
            val started = System.nanoTime()
            val table = context.assets.open(SUGGEST_ASSET).use { EmojiSuggestIndex.parse(it) }
            val probe = PaintGlyphProbe(Paint())
            val available = table.distinctEmoji().filterTo(HashSet()) { sequence ->
                try {
                    probe.hasGlyph(sequence)
                } catch (_: Throwable) {
                    false
                }
            }
            val filtered = table.filterTo(available)
            suggestMs = (System.nanoTime() - started) / 1_000_000.0
            suggestEntries = filtered.entryCount
            drawable = available.size
        }

        Log.i(
            TAG,
            "O2 emoji reload: searchParse=${fmt(searchMs)} ms (entries=$searchEntries) " +
                "suggestParseAndProbe=${fmt(suggestMs)} ms (entries=$suggestEntries, drawable=$drawable)",
        )
        assertTrue("the search index must parse to something", searchEntries > 0)
        assertTrue("the suggest table must survive the glyph probe", suggestEntries > 0)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

    companion object {
        private const val TAG = "EmojiReload"
        private const val SUGGEST_ASSET = "emoji/emoji_suggest_v1.txt"
    }
}
