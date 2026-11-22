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
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/** Receives a background font verdict for one learned emoji sequence. */
fun interface LearnedEmojiDrawabilityObserver {
    fun onResolved(emoji: String, drawable: Boolean)
}

/**
 * What the suggestion strip needs from emoji suggestions: the emoji mapped to a finished word,
 * and a spoken name for the emoji cell so TalkBack reads the name instead of the glyph.
 * Implementations must be immutable and safe to call on the UI thread; lookup and known-verdict
 * reads allocate nothing.
 */
interface EmojiSuggestSource {
    /** The emoji for ([language], [normalizedWord]), or null — a miss is silent by design. */
    fun emojiFor(language: String, normalizedWord: String): String?

    /**
     * The spoken name of [emoji] in [language] (the asset language tag, as
     * `EmojiSuggestIndex.assetLanguageOf` derives it from the subtype id), or null when no name
     * is known; the caller falls back to the glyph.
     */
    fun spokenNameOf(language: String, emoji: String): String?

    /**
     * A cached system-font verdict for a learned sequence: true or false when known, null while
     * its one background probe is pending. The observer is coalesced per sequence and receives
     * the completed verdict. No font probe runs on this UI-thread lookup path.
     */
    fun learnedEmojiDrawability(
        emoji: String,
        observer: LearnedEmojiDrawabilityObserver,
    ): Boolean? = true
}

/**
 * Lazily loads the one [EmojiSuggestSource] of the process off the UI thread (same shape as the
 * controller's `DictionaryPreparation`), so a user who leaves the setting off never reads the
 * asset. [onResult] may arrive on any thread and is called exactly once; a null source
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
        // The glyph filter runs during the parse, so the load never holds an unfiltered and a
        // filtered table at once. Only the first load of the process probes the font.
        val filtered = try {
            appContext.assets.open(SUGGEST_ASSET_PATH).use { stream ->
                EmojiGlyphVerdicts.PROCESS.parse(stream) { PaintGlyphProbe(Paint()) }
            }
        } catch (_: Throwable) {
            EmojiSuggestIndex.EMPTY
        }
        if (filtered.isEmpty) return null
        // Names ride along on the same background pass; an unreadable search asset only costs the
        // spoken labels (TalkBack then reads the glyph itself), never the feature. The index is
        // the process-wide [SharedEmojiSearchIndex]: the panel search reads the same asset.
        val names = SharedEmojiSearchIndex.of(appContext).get()
        probeToneGlyphs()
        return LoadedEmojiSuggestSource(
            filtered,
            names,
            EmojiToneVerdicts.PROCESS,
            LearnedEmojiGlyphVerdicts(executor, PaintGlyphProbe(Paint())),
        )
    }

    /**
     * Runs the process's skin-tone probe unless the panel already did, so a learned toned emoji
     * is checked even when the panel was never opened. An unreadable table leaves it unknown.
     */
    private fun probeToneGlyphs() {
        if (EmojiToneVerdicts.PROCESS.isKnown) return
        try {
            val tones = appContext.assets.open(EmojiSkinTones.ASSET_PATH).use { stream ->
                EmojiSkinTones.parse(stream)
            }
            EmojiToneVerdicts.PROCESS.drawable(tones) { PaintGlyphProbe(Paint()) }
        } catch (_: Throwable) {
            // The verdicts stay unknown: a learned toned emoji is then offered as learned.
        }
    }

    private class LoadedEmojiSuggestSource(
        private val table: EmojiSuggestIndex,
        private val names: EmojiSearchIndex,
        private val toneVerdicts: EmojiToneVerdicts,
        private val learnedVerdicts: LearnedEmojiGlyphVerdicts,
    ) : EmojiSuggestSource {
        override fun emojiFor(language: String, normalizedWord: String): String? =
            table.lookup(language, normalizedWord)

        override fun spokenNameOf(language: String, emoji: String): String? =
            names.nameOf(emoji, language)

        override fun learnedEmojiDrawability(
            emoji: String,
            observer: LearnedEmojiDrawabilityObserver,
        ): Boolean? {
            if (toneVerdicts.isUndrawableToned(emoji)) return false
            return learnedVerdicts.verdict(emoji, observer)
        }
    }

    private companion object {
        const val SUGGEST_ASSET_PATH = "emoji/emoji_suggest_v1.txt"
    }
}

/**
 * One cached system-font verdict per learned emoji sequence. Unknown sequences are hidden while a
 * single background probe runs; repeated UI-thread reads only query [verdicts]. This covers every
 * cluster shape, including restored ZWJ, regional-indicator and tag sequences.
 */
internal class LearnedEmojiGlyphVerdicts(
    private val executor: ExecutorService,
    private val probe: GlyphProbe,
) {
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val lock = Any()
    private val pending = HashMap<String, ArrayList<LearnedEmojiDrawabilityObserver>>()

    /** See [EmojiSuggestSource.learnedEmojiDrawability]. */
    fun verdict(emoji: String, observer: LearnedEmojiDrawabilityObserver): Boolean? {
        verdicts[emoji]?.let { return it }
        synchronized(lock) {
            verdicts[emoji]?.let { return it }
            val observers = pending[emoji]
            if (observers != null) {
                if (!observers.contains(observer)) observers.add(observer)
                return null
            }
            pending[emoji] = arrayListOf(observer)
        }
        try {
            executor.execute {
                val drawable = try {
                    probe.hasGlyph(emoji)
                } catch (_: Throwable) {
                    false
                }
                complete(emoji, drawable)
            }
        } catch (_: Throwable) {
            // Rejection is an unsupported verdict, not permission to display an unchecked sequence.
            complete(emoji, false)
        }
        return null
    }

    private fun complete(emoji: String, drawable: Boolean) {
        val observers = synchronized(lock) {
            verdicts[emoji] = drawable
            pending.remove(emoji).orEmpty()
        }
        for (observer in observers) {
            try {
                observer.onResolved(emoji, drawable)
            } catch (_: Throwable) {
                // One stale consumer must not prevent the others from receiving the cached verdict.
            }
        }
    }
}

/**
 * The emoji sequences of the suggestion table that the system font cannot draw, remembered after
 * the first complete load so an idle reload filters without a [GlyphProbe]. Kept in memory only:
 * fonts do not change within a process, while a file could outlive a font update and then show
 * tofu or hide emoji that became drawable.
 */
internal class EmojiGlyphVerdicts {
    @Volatile
    private var rejected: Set<String>? = null

    /**
     * Parses [input], keeping the emoji the font can draw. Probes with [probe] (a probe error
     * counts as "cannot draw") until one parse gives a non-empty table, then reuses its verdicts.
     */
    fun parse(input: InputStream, probe: () -> GlyphProbe): EmojiSuggestIndex {
        val known = rejected
        if (known != null) return EmojiSuggestIndex.parse(input) { it !in known }
        val glyphs = probe()
        val rejectedNow = HashSet<String>()
        val table = EmojiSuggestIndex.parse(input) { sequence ->
            val drawable = try {
                glyphs.hasGlyph(sequence)
            } catch (_: Throwable) {
                false
            }
            if (!drawable) rejectedNow += sequence
            drawable
        }
        if (!table.isEmpty) rejected = Collections.unmodifiableSet(rejectedNow)
        return table
    }

    companion object {
        /** The instance shared by every load in the process. */
        val PROCESS = EmojiGlyphVerdicts()
    }
}
