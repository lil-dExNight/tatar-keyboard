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
     * One recent entry was removed. If the panel is currently shown, re-bind [snapshot] (the same
     * base with the recents minus the removed entry) so the cell disappears through the same path
     * a category change uses; if the panel is not shown, this is a no-op. Defaulted so existing
     * fakes need not implement it.
     */
    fun refreshAfterRecentRemoved(snapshot: EmojiSetSnapshot) {}

    /**
     * Enter the emoji search with [index] bound: the panel steps aside, the letter keyboard comes
     * back and the search view takes the suggestion strip's place. Defaulted so existing fakes need
     * not implement it.
     */
    fun showEmojiSearch(index: EmojiSearchIndex) {}

    /**
     * The skin-tone table finished loading. Bind it to the panel so a long press on a tone-capable
     * cell offers the five tones. Defaulted so existing fakes need not implement it.
     */
    fun bindSkinTones(tones: EmojiSkinTones) {}

    /**
     * The emoji-name index finished loading (for the search, or started for the panel's spoken
     * cell names). Bind [names] and [language] to the panel view so TalkBack reads names instead
     * of raw sequences; the view falls back to the sequence until this arrives. Defaulted so
     * existing fakes need not implement it.
     */
    fun bindSpokenNames(names: EmojiSearchIndex, language: String?) {}

    /**
     * The search cell was tapped and no search can be opened, now or later in this process.
     *
     * The cell is painted whether or not the index can be read, and the "unusable" verdict is
     * cached for the process, so the surface must tell the user instead of silently ignoring the
     * tap. Defaulted so existing fakes need not implement it.
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
    // The font probe of the toned forms; null leaves the tone table unfiltered.
    private val toneProbeFactory: () -> GlyphProbe? = { null },
    private val toneVerdicts: EmojiToneVerdicts = EmojiToneVerdicts(),
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
                // noBackupFilesDir, not device-protected and not a preferences store. The
                // provider is built lazily on first panel show, so IME start never touches the
                // path and stays directBootAware. noBackupFilesDir is not a subdirectory of
                // files/, so the medium is outside every backup domain.
                RecentEmojiFileProvider { File(appContext.noBackupFilesDir, RECENT_EMOJI_FILE_NAME) },
                AtomicRecentEmojiFileOps,
                gate,
            )
        },
        { AssetSearchIndexSource(context.applicationContext) },
        { AssetSkinToneSource(context.applicationContext) },
        // Constructed on the background preparation only; see PaintGlyphProbe.
        { PaintGlyphProbe(Paint()) },
        EmojiToneVerdicts.PROCESS,
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
     * [releaseSearchIndex] drops a usable index but keeps the EMPTY verdict.
     */
    private var searchIndex: EmojiSearchIndex? = null

    private var searchLoading = false

    /**
     * The skin-tone table, read once in the same background preparation as the snapshot (it is
     * small) and never re-read.
     */
    private var skinTones: EmojiSkinTones = EmojiSkinTones.EMPTY

    /** The single latest-only deferred "show the search". Never more than one outstanding. */
    private var pendingSearch = false

    // Same latest-only deferred shape as [pendingSearch], for the panel show.
    private var pendingShow = false

    private var destroyed = false

    /**
     * The asset language tag the panel's spoken cell names use (`tt` picks the Tatar name,
     * anything else the Russian one), set from the active subtype when the panel is shown. Null
     * until then, which speaks the Russian channel — the same default the search uses.
     */
    private var spokenNameLanguage: String? = null

    fun preparationState(): EmojiPanelPreparation = preparation

    /**
     * Sets the language the panel's spoken cell names use (the asset language tag of the active
     * subtype) and re-binds the already-loaded index under it. The IME calls this on every panel
     * show, so a subtype switch re-announces in the new language without reloading anything.
     */
    fun setSpokenNameLanguage(language: String?) {
        spokenNameLanguage = language
        val loaded = searchIndex
        if (loaded != null && !loaded.isEmpty) {
            surface.bindSpokenNames(loaded, language)
        }
    }

    /**
     * The emoji key was pressed. The press feedback is the ordinary key-down path's, identical
     * for every press; the panel show adds none of its own.
     *
     * @return true when the caller should show the panel or has already handed off a request that
     *   will show it once ready; false when the panel will not show in this process (the key is a
     *   no-op).
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
     * The search cell was tapped. The index is loaded once per process on the background executor;
     * until it arrives a single latest-only deferred show is armed, dropped by exactly the same
     * lifecycle events that drop a deferred panel show.
     */
    fun onSearchRequested() {
        if (destroyed) return
        val loaded = searchIndex
        if (loaded != null) {
            // The verdict is cached for the process, so every tap after the first lands here and
            // must be answered too.
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
        // The panel's spoken cell names ride along on the same load, filtered the same way.
        if (!loaded.isEmpty) {
            surface.bindSpokenNames(loaded, spokenNameLanguage)
        }
        if (pendingSearch) {
            pendingSearch = false
            // Answer the tap that armed this either way, including when the index is unusable.
            if (loaded.isEmpty) surface.onEmojiSearchUnavailable() else surface.showEmojiSearch(loaded)
        }
    }

    /** The loaded index, or null while it has never been asked for; used by tests. */
    fun searchIndexOrNull(): EmojiSearchIndex? = searchIndex

    /**
     * Idle memory release (LatinIME MSG_DEALLOCATE_MEMORY): drops the filtered search index; the
     * next search request rebuilds it on the lazy background path. The EMPTY verdict is kept, so a
     * release never retries an unusable load. A load already in flight still lands and re-caches.
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
     * recording gate is re-read on the background executor before the in-memory list is touched,
     * so a closed gate records nothing.
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

    /**
     * One recent entry was removed through a long press on its cell: the store drops it on the
     * background executor (the list is owned there) and any shown panel re-binds the base
     * snapshot with the remaining recents on the UI thread. The removal is persisted by the next
     * hide's flush, like every other list change.
     */
    fun onRecentRemoved(sequence: String) {
        val store = recentStore ?: return
        val backgroundExecutor = backgroundExecutor() ?: return
        val available = availableSequences
        try {
            backgroundExecutor.execute {
                store.removeRecent(sequence, available)
                try {
                    val base = snapshot
                    if (base != null) {
                        val remaining = store.currentRecents(available)
                        uiPoster.post {
                            if (!destroyed) {
                                surface.refreshAfterRecentRemoved(
                                    EmojiDisplaySnapshots.withRecents(base, remaining),
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // Never crash typing; a missed removal is acceptable, a crash is not.
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

    /**
     * Erases the recents on the executor, then refreshes any shown panel on the UI thread.
     * [onFinished] runs once with [RecentEmojiStore.clear]'s answer, on the executor, or with false
     * on the calling thread when the erase cannot be queued.
     */
    internal fun requestClearRecents(onFinished: (Boolean) -> Unit) {
        val store = recentStore
        val backgroundExecutor = backgroundExecutor()
        if (store == null || backgroundExecutor == null) {
            onFinished(false)
            return
        }
        try {
            backgroundExecutor.execute(ClearRecentsTask(onFinished) {
                val cleared = store.clear()
                try {
                    val base = snapshot
                    if (base != null) {
                        uiPoster.post { if (!destroyed) surface.refreshAfterRecentsCleared(base) }
                    }
                } catch (_: Throwable) {
                }
                cleared
            })
        } catch (_: Throwable) {
            onFinished(false)
        }
    }

    fun onDestroy() {
        destroyed = true
        pendingShow = false
        pendingSearch = false
        if (liveInstance === this) setLive(null)
        executor?.shutdownNow()?.forEach { (it as? ClearRecentsTask)?.dropped() }
        executor = null
    }

    /** A recents erase that answers exactly once: with the erase result, or false if dropped. */
    private class ClearRecentsTask(
        private val onFinished: (Boolean) -> Unit,
        private val erase: () -> Boolean,
    ) : Runnable {
        override fun run() {
            var cleared = false
            try {
                cleared = erase()
            } finally {
                onFinished(cleared)
            }
        }

        fun dropped() = onFinished(false)
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
                // costs the long-press variants, never the panel. Bases whose tones the font
                // cannot draw leave the table here, so they get no picker and no toned recents.
                val tones = try {
                    toneVerdicts.drawable(skinSource?.load() ?: EmojiSkinTones.EMPTY, toneProbeFactory)
                } catch (_: Throwable) {
                    EmojiSkinTones.EMPTY
                }
                val available = if (built.isEmpty) {
                    emptySet()
                } else {
                    EmojiDisplaySnapshots.availableSequences(built, tones)
                }
                uiPoster.post { onPrepared(built, tones, available) }
            }
        } catch (_: Throwable) {
            finishUnavailable()
        }
    }

    private fun onPrepared(
        built: EmojiSetSnapshot,
        tones: EmojiSkinTones,
        available: Set<String>,
    ) {
        if (destroyed) return
        if (built.isEmpty) {
            finishUnavailable()
            return
        }
        snapshot = built
        skinTones = tones
        availableSequences = available
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
     * Publishes the panel. With no recents store (JVM tests) the base snapshot shows synchronously.
     * In production the recents are read on the background executor — which
     * re-reads the unlock gate, so a process started before unlock reads the file only after it —
     * and the combined snapshot (recents first, only when non-empty) is shown on the UI thread. The
     * recents read never runs on the UI thread, and the base snapshot is shown if the recents path
     * fails for any reason.
     */
    private fun publish(base: EmojiSetSnapshot) {
        // The spoken cell names come from the search index: make sure it is loading (one load per
        // process, off the UI thread), so a screen reader gets names from the first panel show.
        ensureSpokenNamesIndex()
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

    /**
     * Starts the search-index load when it has not been attempted, without answering any search
     * tap: the panel's spoken cell names need the index, and [onSearchIndexLoaded] binds it. A
     * load already in flight, a usable one already loaded, or the cached-unusable verdict all
     * leave this untouched; the spoken names then stay on the sequence fallback.
     */
    private fun ensureSpokenNamesIndex() {
        if (searchIndex != null || searchLoading) return
        val backgroundExecutor = backgroundExecutor() ?: return
        val source = try {
            searchIndexSourceFactory()
        } catch (_: Throwable) {
            null
        } ?: return
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
         * Clears the recents through [RecentEmojiStore.clear]: via the live controller when the IME
         * runs in this process (its list and open panel update too), else directly. [onFinished]
         * gets the answer on the store's worker, or false at once when the erase cannot be queued.
         */
        @JvmStatic
        @JvmOverloads
        fun clearRecents(context: Context, onFinished: (Boolean) -> Unit = {}) {
            val live = liveInstance
            if (live != null) {
                live.requestClearRecents(onFinished)
                return
            }
            clearStandalone(context.applicationContext, onFinished)
        }

        private fun clearStandalone(appContext: Context, onFinished: (Boolean) -> Unit) {
            val standaloneExecutor = try {
                Executors.newSingleThreadExecutor()
            } catch (_: Throwable) {
                onFinished(false)
                return
            }
            try {
                standaloneExecutor.execute(ClearRecentsTask(onFinished) {
                    val gate = RecentEmojiGate {
                        val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
                        // Clearing records nothing, so only the path gate matters here.
                        RecentEmojiGateState(
                            shouldShowSuggestions = false,
                            userUnlocked = userManager == null || userManager.isUserUnlocked,
                            noPersonalizedLearning = false,
                            keyguardLocked = false,
                            incognito = false,
                        )
                    }
                    val cleared = RecentEmojiStore(
                        RecentEmojiFileProvider { File(appContext.noBackupFilesDir, RECENT_EMOJI_FILE_NAME) },
                        AtomicRecentEmojiFileOps,
                        gate,
                    ).clear()
                    cleared
                })
            } catch (_: Throwable) {
                // The task was never queued, so it will not answer.
                onFinished(false)
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
            context.assets.open(EmojiSkinTones.ASSET_PATH).use { input -> EmojiSkinTones.parse(input) }
        } catch (_: Throwable) {
            EmojiSkinTones.EMPTY
        }
}

/**
 * Production [EmojiSearchIndexSource]: returns the process-wide [SharedEmojiSearchIndex], which
 * the emoji-suggest source also reads for spoken names. Cheap to construct on the UI thread; the
 * AssetManager is touched only inside [load], on the controller's background executor the first
 * time the user opens the search. The result is never written to any persistent store.
 */
private class AssetSearchIndexSource(private val context: Context) : EmojiSearchIndexSource {
    override fun load(): EmojiSearchIndex = SharedEmojiSearchIndex.of(context).get()
}
