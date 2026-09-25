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
import java.io.InputStream

/**
 * Audit 2026-09-02, C7: the ONE parsed copy of `assets/emoji/emoji_search_v1.txt` per process.
 * Before this, the suggestion strip (emoji names for TalkBack) and the panel search each parsed
 * the ~545 KiB asset independently and kept their own instance alive.
 *
 * Both consumers are lazy and load off the UI thread, each on its own executor, so two first
 * calls may race: [get] is synchronized, parses at most once, and caches a failure
 * ([EmojiSearchIndex.EMPTY]) too — both consumers already treat an unusable asset as terminal
 * for the process, so a retry would only re-read an asset that cannot get better. (O2: the idle
 * memory release — [releaseProcessWide] — softens that terminality to one retry per idle cycle;
 * it only ever re-reads an asset that could not be read, a bounded silent cost on an
 * already-dead feature.)
 *
 * Threading note: [EmojiSearchIndex.search] reuses rank buckets and stays single-threaded — the
 * panel is the only searcher and drives it from the UI thread, exactly as before; the suggest
 * side only ever calls the read-only [EmojiSearchIndex.nameOf]. Sharing the instance changes
 * neither.
 */
class SharedEmojiSearchIndex internal constructor(
    private val openAsset: () -> InputStream?,
) {
    @Volatile
    private var cached: EmojiSearchIndex? = null

    /**
     * The shared index, parsing the asset on the first call. The parse blocks the caller — call
     * it off the UI thread, as both production consumers do.
     */
    fun get(): EmojiSearchIndex {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    /**
     * O2 (docs/OPTIMIZE-2026-09-25.md): drops the parsed copy on the idle memory release; the
     * next [get] reparses lazily on the caller's background thread, exactly like the first load.
     * The same synchronized discipline as [get] makes a release racing a load resolve to one
     * valid state either way.
     */
    fun release() {
        synchronized(this) {
            cached = null
        }
    }

    private fun load(): EmojiSearchIndex {
        val stream = try {
            openAsset()
        } catch (_: Throwable) {
            null
        } ?: return EmojiSearchIndex.EMPTY
        return stream.use { EmojiSearchIndex.parse(it) }
    }

    companion object {
        const val ASSET_PATH = "emoji/emoji_search_v1.txt"

        @Volatile
        private var processWide: SharedEmojiSearchIndex? = null

        /**
         * The process-wide holder over the app assets. Creating it is cheap — it keeps only the
         * application context and the opener — so both consumers may ask for it from their own
         * lazy paths; the asset itself is read on the first [get], never on the cold-start path.
         */
        fun of(context: Context): SharedEmojiSearchIndex {
            processWide?.let { return it }
            return synchronized(this) {
                processWide ?: run {
                    val appContext = context.applicationContext
                    SharedEmojiSearchIndex {
                        try {
                            appContext.assets.open(ASSET_PATH)
                        } catch (_: Throwable) {
                            null
                        }
                    }.also { processWide = it }
                }
            }
        }

        /**
         * O2 (docs/OPTIMIZE-2026-09-25.md): the process-wide idle release — drops the parsed
         * index so a long-hidden keyboard does not hold it. No-op when nothing was ever loaded;
         * the next consumer's [get] reparses lazily. Callers that kept their own reference (the
         * filtered panel copy, the suggest source) are released by their own owners on the same
         * deallocate pass. `@JvmStatic` because the caller is LatinIME (Java).
         */
        @JvmStatic
        fun releaseProcessWide() {
            processWide?.release()
        }
    }
}
