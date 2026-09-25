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

import android.content.Context
import android.graphics.Paint
import java.util.concurrent.ExecutorService

/**
 * What the suggestion strip needs from the emoji-suggest feature (mission 2 of
 * `docs/EMOJI-SUGGEST-PLAN.md`): the emoji mapped to a finished word, and a spoken name for the
 * emoji cell so TalkBack reads the name instead of the glyph. Implementations must be immutable
 * and safe to call on the UI thread; both methods are allocation-free lookups.
 */
interface EmojiSuggestSource {
    /** The emoji for ([language], [normalizedWord]), or null — a miss is silent by design. */
    fun emojiFor(language: String, normalizedWord: String): String?

    /** The spoken name of [emoji], or null when no name is known; the caller falls back to the glyph. */
    fun spokenNameOf(emoji: String): String?
}

/**
 * Lazily loads the one [EmojiSuggestSource] of the process off the UI thread — the exact shape of
 * the controller's `DictionaryPreparation` seam, so a user who leaves the toggle off never reads
 * the asset at all. [onResult] may arrive on any thread and is called exactly once; a null source
 * means "unusable" (missing/corrupt asset) and is terminal for the process.
 */
fun interface EmojiSuggestPreparation {
    fun prepare(onResult: (EmojiSuggestSource?) -> Unit)
}

/**
 * Production [EmojiSuggestPreparation]: reads the suggest table and the search index (the only
 * place emoji names live) from the assets on the given executor and filters the table through the
 * glyph probe, so a device without the glyph never sees a "tofu" cell — the same rule the panel
 * itself is built by. Constructed cheaply on the UI thread (it only keeps the application
 * context); the AssetManager and `Paint.hasGlyph` are touched only inside the background task,
 * never on the cold-start path. The result is returned to the caller and never written to any
 * persistent store.
 */
class AssetEmojiSuggestPreparation(
    context: Context,
    private val executor: ExecutorService,
) : EmojiSuggestPreparation {
    private val appContext = context.applicationContext

    override fun prepare(onResult: (EmojiSuggestSource?) -> Unit) {
        try {
            executor.execute {
                onResult(load())
            }
        } catch (_: Throwable) {
            onResult(null)
        }
    }

    private fun load(): EmojiSuggestSource? {
        // C2 (docs/ROADMAP-P8-PLAN.md): the glyph probe runs DURING the parse, so the load holds
        // one table instead of two (the unfiltered one used to stay alive until the filtered copy
        // was finished). Same verdicts, same probe, same number of probe calls — one per distinct
        // emoji sequence, memoized inside parse.
        val probe = PaintGlyphProbe(Paint())
        val filtered = try {
            appContext.assets.open(SUGGEST_ASSET_PATH).use { stream ->
                EmojiSuggestIndex.parse(stream) { sequence ->
                    try {
                        probe.hasGlyph(sequence)
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        } catch (_: Throwable) {
            EmojiSuggestIndex.EMPTY
        }
        if (filtered.isEmpty) return null
        // Names ride along on the same background pass; an unreadable search asset only costs the
        // spoken labels (TalkBack then reads the glyph itself), never the feature. The index is
        // the process-wide shared one (audit C7): the panel search reads the same asset.
        val names = SharedEmojiSearchIndex.of(appContext).get()
        return LoadedEmojiSuggestSource(filtered, names)
    }

    private class LoadedEmojiSuggestSource(
        private val table: EmojiSuggestIndex,
        private val names: EmojiSearchIndex,
    ) : EmojiSuggestSource {
        override fun emojiFor(language: String, normalizedWord: String): String? =
            table.lookup(language, normalizedWord)

        override fun spokenNameOf(emoji: String): String? = names.nameOf(emoji)
    }

    private companion object {
        const val SUGGEST_ASSET_PATH = "emoji/emoji_suggest_v1.txt"
    }
}
