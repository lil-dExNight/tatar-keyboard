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
import android.os.Handler
import android.os.UserManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Emoji-panel UI seam, modelled on `StripSurface`. All methods run on the UI thread. */
interface EmojiSurface {
    /** Bind [snapshot] to the panel view and make it the active surface. */
    fun showPanel(snapshot: EmojiSetSnapshot)

    /**
     * The recents were just cleared. If the panel is currently shown, re-bind [base] (which carries
     * no recents category) so the Recent tab disappears through the same path as a category change;
     * if the panel is not shown, this is a no-op. Defaulted so existing fakes need not implement it.
     */
    fun refreshAfterRecentsCleared(base: EmojiSetSnapshot) {}

    /**
     * Enter the emoji search with [index] bound: the panel steps aside, the letter keyboard comes
     * back and the search bands take the suggestion strip's place. Defaulted so existing fakes need
     * not implement it.
     */
    fun showEmojiSearch(index: EmojiSearchIndex) {}

    /**
     * The skin-tone table finished loading. Bind it to the panel so a long press on a tone-capable
     * cell offers the five tones. Defaulted so existing fakes need not implement it.
     */
    fun bindSkinTones(tones: EmojiSkinTones) {}

    /**
     * The search pill was tapped and no search can be opened, now or later in this process.
     *
     * The pill is painted whether or not the index can be read, and the verdict "unusable" is
     * cached for the life of the process — so without this the pill is a button that stays on the
     * screen and does nothing, forever, without a word. Defaulted so existing fakes need not
     * implement it.
     */
    fun onEmojiSearchUnavailable() {}
}

/**
 * Marshals a [Runnable] onto the UI thread. Production wraps a [Handler]; JVM tests inject a
 * synchronous or deferrable poster so no real Handler is needed.
 */
fun interface EmojiUiPoster {
    fun post(runnable: Runnable)
}

/**
 * Builds the filtered emoji snapshot off the UI thread — reading the asset, probing glyphs and
 * composing categories. Production reads `assets/emoji/emoji_set_v1.txt` through [EmojiSet.build];
 * JVM tests inject a fake and count how many times [build] runs, which is what makes "one
 * preparation per process" observable without Android.
 */
fun interface EmojiSnapshotSource {
    fun build(): EmojiSetSnapshot
}

/**
 * Reads and parses the skin-tone table off the UI thread. Production reads
 * `assets/emoji/emoji_skin_v1.txt`; JVM tests inject a fake.
 */
fun interface EmojiSkinToneSource {
    fun load(): EmojiSkinTones
}

/**
 * Reads and parses the emoji-search index off the UI thread. Production reads
 * `assets/emoji/emoji_search_v1.txt`; JVM tests inject a fake and count how many times [load] runs,
 * which is what makes "one load per process" observable without Android.
 */
fun interface EmojiSearchIndexSource {
    fun load(): EmojiSearchIndex
}

/** Where a process's single snapshot preparation stands. */
enum class EmojiPanelPreparation { NOT_PREPARED, PREPARING, READY, UNAVAILABLE }

/**
 * Serialized owner of the emoji panel's snapshot, modelled on `SuggestionsController`.
 *
 * Threading: every public method must be called on the UI thread. The single-thread background
 * executor runs only the (blocking) snapshot preparation. Its result is re-marshaled onto
 * [uiPoster] before any controller state is touched, so every mutation happens on one serialized
 * owner.
 *
 * Preparation runs exactly once per process: it is started on the FIRST emoji key press, never in
 * `onCreate` and never on the UI thread, and it is never restarted — not after [EmojiPanelPreparation.READY]
 * (the snapshot is cached), and not after [EmojiPanelPreparation.UNAVAILABLE] (the panel then never
 * shows in this process and the emoji key is a no-op). The glyph-probe result lives only in memory;
 * it is never written to shared preferences or a file, because a stale cache would reintroduce the
 * very "tofu" the probe exists to prevent.
 *
 * Until the snapshot is published, a press arms exactly one latest-only deferred show. It is
 * dropped by any other press (which simply re-arms it), by an editor-session change, by
 * `onFinishInputView`, and by input-view recreation, so a panel never pops up for an editor the
 * user has already left.
 */
class EmojiPanelController internal constructor(
    private val surface: EmojiSurface,
    private val uiPoster: EmojiUiPoster,
    private val executorFactory: () -> ExecutorService?,
    private val snapshotSourceFactory: () -> EmojiSnapshotSource?,
    private val recentStoreFactory: () -> RecentEmojiStore? = { null },
    private val searchIndexSourceFactory: () -> EmojiSearchIndexSource? = { null },
    private val skinToneSourceFactory: () -> EmojiSkinToneSource? = { null },
) {
    /** Production entry point. */
    constructor(
        context: Context,
        surface: EmojiSurface,
        uiHandler: Handler,
        gate: RecentEmojiGate,
    ) : this(
        surface,
        EmojiUiPoster { runnable -> uiHandler.post(runnable) },
        { Executors.newSingleThreadExecutor() },
        { AssetSnapshotSource(context.applicationContext) },
        {
            val appContext = context.applicationContext
            RecentEmojiStore(
                // The recents live in a plain file under the base (credential-protected)
                // noBackupFilesDir — not device-protected, and not a preferences store. The provider is
                // built lazily on first panel show, so IME start never touches the path and stays
                // directBootAware. noBackupFilesDir is not a subdirectory of files/, so the medium
                // is excluded from every backup domain by construction.
                RecentEmojiFileProvider { File(appContext.noBackupFilesDir, RECENT_EMOJI_FILE_NAME) },
                AtomicRecentEmojiFileOps,
                gate,
            )
        },
        { AssetSearchIndexSource(context.applicationContext) },
        { AssetSkinToneSource(context.applicationContext) },
    ) {
        setLive(this)
    }

    private var executor: ExecutorService? = null

    // Built lazily on first use so IME start never resolves the recents path (directBoot-safe).
    private val recentStore: RecentEmojiStore? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            recentStoreFactory()
        } catch (_: Throwable) {
            null
        }
    }

    // The base set's sequences, computed once when the snapshot is prepared and reused so recording
    // and showing allocate nothing per tap.
    private var availableSequences: Set<String> = emptySet()

    // Written on the UI thread after a successful preparation and read on the UI thread by showNow.
    @Volatile
    private var snapshot: EmojiSetSnapshot? = null

    private var preparation = EmojiPanelPreparation.NOT_PREPARED

    /**
     * The search index, loaded at most once per process and only when the user first opens the
     * search — never on the cold-start path and never on the UI thread. `null` means "not loaded
     * yet"; [EmojiSearchIndex.EMPTY] means "loaded and unusable", which is not retried.
     * (O2: the idle memory release drops a LIVE index via [releaseSearchIndex]; the EMPTY
     * verdict is deliberately kept — a release must not resurrect a load that proved unusable.)
     */
    private var searchIndex: EmojiSearchIndex? = null

    private var searchLoading = false

    /**
     * The skin-tone table, read on the same one-shot background preparation as the snapshot. It is
     * 1.5 KB, so it costs nothing to read alongside; it is never re-read.
     */
    private var skinTones: EmojiSkinTones = EmojiSkinTones.EMPTY

    /** The single latest-only deferred "show the search". Never more than one outstanding. */
    private var pendingSearch = false

    // The single latest-only deferred show. Never more than one outstanding.
    private var pendingShow = false

    private var destroyed = false

    fun preparationState(): EmojiPanelPreparation = preparation

    /**
     * The emoji key was pressed.
     *
     * @return true when the caller should show the panel or has already handed off a request that
     *   will show it once ready; false when the panel will not show in this process (the key is a
     *   no-op that only gives haptic feedback).
     */
    fun onEmojiKeyPressed(): Boolean {
        if (destroyed) return false
        when (preparation) {
            EmojiPanelPreparation.READY -> {
                showNow()
                return true
            }
            EmojiPanelPreparation.UNAVAILABLE -> return false
            EmojiPanelPreparation.NOT_PREPARED -> {
                preparation = EmojiPanelPreparation.PREPARING
                pendingShow = true
                startPreparation()
                return preparation != EmojiPanelPreparation.UNAVAILABLE
            }
            EmojiPanelPreparation.PREPARING -> {
                // Latest-only: a second press does not start a second preparation.
                pendingShow = true
                return true
            }
        }
    }

    /**
     * The search pill was tapped. The index is loaded once per process on the background executor;
     * until it arrives a single latest-only deferred show is armed, dropped by exactly the same
     * lifecycle events that drop a deferred panel show.
     */
    fun onSearchRequested() {
        if (destroyed) return
        val loaded = searchIndex
        if (loaded != null) {
            // The verdict is cached for the life of the process, so this is the branch a real
            // finger reaches on every tap after the first — nobody taps a dead button only once.
            if (loaded.isEmpty) surface.onEmojiSearchUnavailable() else surface.showEmojiSearch(loaded)
            return
        }
        pendingSearch = true
        if (searchLoading) return
        val backgroundExecutor = backgroundExecutor()
        val source = try {
            searchIndexSourceFactory()
        } catch (_: Throwable) {
            null
        }
        if (backgroundExecutor == null || source == null) {
            searchIndex = EmojiSearchIndex.EMPTY
            pendingSearch = false
            surface.onEmojiSearchUnavailable()
            return
        }
        searchLoading = true
        val available = availableSequences
        try {
            backgroundExecutor.execute {
                val built = try {
                    val raw = source.load()
                    if (available.isEmpty()) raw else raw.filterTo(available)
                } catch (_: Throwable) {
                    EmojiSearchIndex.EMPTY
                }
                uiPoster.post { onSearchIndexLoaded(built) }
            }
        } catch (_: Throwable) {
            searchLoading = false
            searchIndex = EmojiSearchIndex.EMPTY
            pendingSearch = false
            surface.onEmojiSearchUnavailable()
        }
    }

    private fun onSearchIndexLoaded(loaded: EmojiSearchIndex) {
        searchLoading = false
        if (destroyed) return
        searchIndex = loaded
        if (pendingSearch) {
            pendingSearch = false
            // Answered either way: the tap that armed this is the one being served, and an index
            // that came back unusable is the whole reason the pill would otherwise go quiet.
            if (loaded.isEmpty) surface.onEmojiSearchUnavailable() else surface.showEmojiSearch(loaded)
        }
    }

    /** The loaded index, or null while it has never been asked for; used by tests. */
    fun searchIndexOrNull(): EmojiSearchIndex? = searchIndex

    /**
     * O2 (docs/OPTIMIZE-2026-09-25.md): the idle memory release (LatinIME MSG_DEALLOCATE_MEMORY)
     * drops the filtered search index; the next search request re-derives it through the same
     * lazy background path that served the first one (the shared parse may itself have been
     * released on the same pass — it reloads too). The terminal EMPTY verdict survives: a
     * release must not resurrect a load that proved unusable. UI thread, like every method here;
     * a load already in flight is left to land — it re-caches, which is only a wasted release,
     * never a wrong one.
     */
    fun releaseSearchIndex() {
        val loaded = searchIndex ?: return
        if (loaded.isEmpty) return
        searchIndex = null
    }

    /** Drops the single deferred show without letting it fire later. */
    private fun cancelPendingShow() {
        pendingShow = false
        pendingSearch = false
    }

    /** A new editor session began; a deferred show for the previous one must not fire. */
    fun onEditorSessionChanged() = cancelPendingShow()

    fun onFinishInputView() {
        cancelPendingShow()
        flushRecentsOnHide()
    }

    /** The input view was recreated (rotation, theme or height change). */
    fun onInputViewRecreated() {
        cancelPendingShow()
        flushRecentsOnHide()
    }

    /** The panel was hidden (the "АБВ" key, or any lifecycle hide): persist the recents once. */
    fun onPanelHidden() = flushRecentsOnHide()

    /**
     * An emoji was inserted from the panel — a grid tap, including a tap inside the Recent tab. The
     * three-factor gate is re-read on the background executor before the in-memory list is touched,
     * so a forbidden field, a locked device or a no-personalized-learning field records nothing.
     */
    fun onEmojiInserted(sequence: String) {
        val store = recentStore ?: return
        val backgroundExecutor = backgroundExecutor() ?: return
        val available = availableSequences
        try {
            backgroundExecutor.execute { store.recordUse(sequence, available) }
        } catch (_: Throwable) {
            // Never crash typing; a missed record is acceptable, a crash is not.
        }
    }

    private fun flushRecentsOnHide() {
        val store = recentStore ?: return
        val backgroundExecutor = backgroundExecutor() ?: return
        try {
            backgroundExecutor.execute { store.flushOnHide() }
        } catch (_: Throwable) {
        }
    }

    /** Erases the recents on the executor, then refreshes any shown panel on the UI thread. */
    private fun requestClearRecents() {
        val store = recentStore ?: return
        val backgroundExecutor = backgroundExecutor() ?: return
        try {
            backgroundExecutor.execute {
                store.clear()
                val base = snapshot
                if (base != null) {
                    uiPoster.post { if (!destroyed) surface.refreshAfterRecentsCleared(base) }
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun onDestroy() {
        destroyed = true
        pendingShow = false
        pendingSearch = false
        if (liveInstance === this) setLive(null)
        executor?.shutdownNow()
        executor = null
    }

    private fun startPreparation() {
        val backgroundExecutor = backgroundExecutor()
        if (backgroundExecutor == null) {
            finishUnavailable()
            return
        }
        val source = try {
            snapshotSourceFactory()
        } catch (_: Throwable) {
            null
        }
        if (source == null) {
            finishUnavailable()
            return
        }
        val skinSource = try {
            skinToneSourceFactory()
        } catch (_: Throwable) {
            null
        }
        try {
            backgroundExecutor.execute {
                val built = try {
                    source.build()
                } catch (_: Throwable) {
                    EmojiSetSnapshot.EMPTY
                }
                // The tone table rides along on the same background pass; a failure to read it only
                // costs the long-press variants, never the panel.
                val tones = try {
                    skinSource?.load() ?: EmojiSkinTones.EMPTY
                } catch (_: Throwable) {
                    EmojiSkinTones.EMPTY
                }
                uiPoster.post { onPrepared(built, tones) }
            }
        } catch (_: Throwable) {
            finishUnavailable()
        }
    }

    private fun onPrepared(built: EmojiSetSnapshot, tones: EmojiSkinTones) {
        if (destroyed) return
        if (built.isEmpty) {
            finishUnavailable()
            return
        }
        snapshot = built
        skinTones = tones
        // The recents may hold a toned pick, so the set they are checked against has to know the
        // toned forms too — otherwise a skin-toned emoji would be silently dropped from "recent".
        availableSequences = if (tones.isEmpty) {
            EmojiDisplaySnapshots.availableSequences(built)
        } else {
            EmojiDisplaySnapshots.availableSequences(built) + tones.allTonedSequences()
        }
        if (!tones.isEmpty) surface.bindSkinTones(tones)
        preparation = EmojiPanelPreparation.READY
        if (pendingShow) {
            pendingShow = false
            publish(built)
        }
    }

    private fun finishUnavailable() {
        preparation = EmojiPanelPreparation.UNAVAILABLE
        pendingShow = false
    }

    private fun showNow() {
        val ready = snapshot ?: return
        publish(ready)
    }

    /**
     * Publishes the panel. With no recents store (JVM tests) the base snapshot shows synchronously,
     * exactly as before. In production the recents are read on the background executor — which
     * re-reads the unlock gate, so a process started before unlock reads the file only after it —
     * and the combined snapshot (recents first, only when non-empty) is shown on the UI thread. The
     * recents read never runs on the UI thread, and the base snapshot is shown if the recents path
     * fails for any reason.
     */
    private fun publish(base: EmojiSetSnapshot) {
        val store = recentStore
        if (store == null) {
            surface.showPanel(base)
            return
        }
        val backgroundExecutor = backgroundExecutor()
        if (backgroundExecutor == null) {
            surface.showPanel(base)
            return
        }
        val available = availableSequences
        try {
            backgroundExecutor.execute {
                val recents = try {
                    store.currentRecents(available)
                } catch (_: Throwable) {
                    emptyList<String>()
                }
                uiPoster.post {
                    if (!destroyed) surface.showPanel(EmojiDisplaySnapshots.withRecents(base, recents))
                }
            }
        } catch (_: Throwable) {
            surface.showPanel(base)
        }
    }

    private fun backgroundExecutor(): ExecutorService? {
        if (destroyed) return null
        executor?.let { return it }
        val created = try {
            executorFactory()
        } catch (_: Throwable) {
            null
        } ?: return null
        executor = created
        return created
    }

    companion object {
        private const val RECENT_EMOJI_FILE_NAME = "recent_emoji_v1"

        @Volatile
        private var liveInstance: EmojiPanelController? = null

        private fun setLive(controller: EmojiPanelController?) {
            liveInstance = controller
        }

        /**
         * Clears the recent-emoji medium. When the IME exists in this process the erase is routed to
         * the live controller so its in-memory list and any open panel are updated too; otherwise the
         * medium is replaced directly. Both paths call the single storage method
         * [RecentEmojiStore.clear]. Never touches the UI thread's editor and never reads the list.
         */
        @JvmStatic
        fun clearRecents(context: Context) {
            val live = liveInstance
            if (live != null) {
                live.requestClearRecents()
                return
            }
            clearStandalone(context.applicationContext)
        }

        private fun clearStandalone(appContext: Context) {
            val standaloneExecutor = try {
                Executors.newSingleThreadExecutor()
            } catch (_: Throwable) {
                return
            }
            try {
                standaloneExecutor.execute {
                    val gate = RecentEmojiGate {
                        val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
                        RecentEmojiGateState(
                            shouldShowSuggestions = false,
                            userUnlocked = userManager == null || userManager.isUserUnlocked,
                            noPersonalizedLearning = false,
                        )
                    }
                    RecentEmojiStore(
                        RecentEmojiFileProvider { File(appContext.noBackupFilesDir, RECENT_EMOJI_FILE_NAME) },
                        AtomicRecentEmojiFileOps,
                        gate,
                    ).clear()
                }
            } catch (_: Throwable) {
                // Nothing to clean up on the UI side.
            } finally {
                standaloneExecutor.shutdown()
            }
        }
    }
}

/**
 * Production [EmojiSnapshotSource]: reads the packed asset and filters it through
 * [PaintGlyphProbe]. Constructed cheaply on the UI thread (it only keeps the application context);
 * the AssetManager and `Paint.hasGlyph` are touched only inside [build], which runs on the
 * controller's background executor, never on the UI or cold-start path. The result is returned to
 * the caller and never written to any persistent store.
 */
private class AssetSnapshotSource(private val context: Context) : EmojiSnapshotSource {
    override fun build(): EmojiSetSnapshot =
        try {
            context.assets.open(ASSET_PATH).use { input ->
                EmojiSet.build(input, PaintGlyphProbe(Paint()))
            }
        } catch (_: Throwable) {
            EmojiSetSnapshot.EMPTY
        }

    private companion object {
        const val ASSET_PATH = "emoji/emoji_set_v1.txt"
    }
}

/**
 * Production [EmojiSkinToneSource]: reads and parses the packed skin-tone asset on the controller's
 * background executor, as part of the one-shot preparation. Never touched on the cold-start path.
 */
private class AssetSkinToneSource(private val context: Context) : EmojiSkinToneSource {
    override fun load(): EmojiSkinTones =
        try {
            context.assets.open(ASSET_PATH).use { input -> EmojiSkinTones.parse(input) }
        } catch (_: Throwable) {
            EmojiSkinTones.EMPTY
        }

    private companion object {
        const val ASSET_PATH = "emoji/emoji_skin_v1.txt"
    }
}

/**
 * Production [EmojiSearchIndexSource]: hands out the process-wide shared index
 * ([SharedEmojiSearchIndex], audit 2026-09-02 C7) — the emoji-suggest source reads the same asset
 * for its spoken names, and one parse serves both. Constructed cheaply on the UI thread (it only
 * keeps the application context); the AssetManager is touched only inside [load], which runs on
 * the controller's background executor the first time the user opens the search — never on the
 * cold-start path. The result is returned to the caller and never written to any persistent store.
 */
private class AssetSearchIndexSource(private val context: Context) : EmojiSearchIndexSource {
    override fun load(): EmojiSearchIndex = SharedEmojiSearchIndex.of(context).get()
}
