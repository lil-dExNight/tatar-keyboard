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
 * The one parsed copy of `assets/emoji/emoji_search_v1.txt` per process, shared by the
 * suggestion strip (emoji names for TalkBack) and the panel search.
 *
 * Both consumers load lazily off the UI thread on their own executors, so first calls may race:
 * [get] is synchronized, parses at most once, and also caches a failure ([EmojiSearchIndex.EMPTY]),
 * which both consumers treat as terminal. [releaseProcessWide] allows one retry per idle release.
 *
 * Threading: [EmojiSearchIndex.search] reuses rank buckets and is single-threaded; only the panel
 * searches, on the UI thread. The suggestion side calls only the read-only
 * [EmojiSearchIndex.nameOf].
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
     * Drops the parsed copy on the idle memory release; the next [get] reparses lazily on the
     * caller's background thread. Synchronized like [get], so a release racing a load ends in
     * one valid state.
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
         * Idle release: drops the parsed index so a long-hidden keyboard does not hold it (no-op
         * when nothing was loaded). Holders of their own reference (the filtered panel copy, the
         * suggest source) are released by their owners in the same pass. Called from LatinIME.
         */
        @JvmStatic
        fun releaseProcessWide() {
            processWide?.release()
        }
    }
}
