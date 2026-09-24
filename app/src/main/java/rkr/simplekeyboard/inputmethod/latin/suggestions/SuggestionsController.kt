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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import android.content.Context
import android.os.Handler
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupKind
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramPreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PreparationResult
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.emoji.AssetEmojiSuggestPreparation
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestIndex
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestPreparation
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiSuggestSource
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Suggestion-strip UI seam. All methods are called on the UI thread. */
interface StripSurface {
    fun showSuggestions(first: String, second: String?, third: String?)

    /**
     * Optional spoken labels for cells whose own text does not read well aloud — an emoji cell
     * (mission 2 of `docs/EMOJI-SUGGEST-PLAN.md`). Called immediately after [showSuggestions]
     * with one entry per cell; a null entry means "speak the cell's text". Defaults to a no-op so
     * a surface written before emoji suggestions keeps compiling and simply speaks the glyph.
     */
    fun setSpokenCellLabels(first: String?, second: String?, third: String?) {}

    /**
     * The autocorrect preview's emphasis marker (P2 of Phase 3, docs/ROADMAP-P3.md): the cell
     * holding the correction the next separator would insert, or
     * [SuggestionStripState.NO_CELL] on every ordinary band. Called immediately after
     * [showSuggestions] by the same owner call, so the marker can never describe a band it did
     * not arrive with; a surface written before P2 keeps compiling and simply never emphasizes.
     */
    fun setEmphasizedCell(cell: Int) {}

    /** Make the strip VISIBLE with no words (empty band), keeping its reserved 40dp height. */
    fun reserve()

    fun hideSuggestions()
    fun setTapListener(listener: SuggestionTapListener)
}

/**
 * Drives Tatar prefix suggestions from IME lifecycle callbacks.
 *
 * Threading: every public method must be called on the UI thread. The single-thread background
 * executor is used only for the (blocking) engine start and dictionary preparation. Engine results
 * arrive on a worker thread via [ResultCallback] and are re-marshaled onto [uiPoster] before any
 * state is touched. Dictionary-readiness notifications are likewise marshaled onto [uiPoster] so
 * every mutation of controller state happens on the single serialized UI owner.
 *
 * Eligibility (opt-in setting, tt_RU subtype, editor allows suggestions, known cursor) is computed
 * by LatinIME and passed in via [onStartInput]/[onSubtypeChanged]/[onSuggestionsSettingEnabled];
 * dictionary readiness is tracked internally.
 *
 * Nothing is built eagerly: the background executor, the storage controller and the dictionary
 * itself only come into existence once suggestions are actually wanted.
 */
class SuggestionsController internal constructor(
    private val strip: StripSurface,
    private val editor: EditorSurface,
    private val uiPoster: UiPoster,
    private val engineFactory: (String, ResultCallback) -> EngineHandle?,
    private val executorFactory: () -> ExecutorService?,
    private val preparationFactory: (ExecutorService, String) -> DictionaryPreparation?,
    initialDictionaryReady: Boolean,
    // E5c: trailing default so every existing internal test constructor below (there is no
    // bigram table in any of their fakes) needs no change at all — only the production
    // constructor passes real wiring.
    private val bigramPreparationFactory: (ExecutorService, String) -> BigramPreparation? =
        { _, _ -> null },
    // Emoji-suggest (mission 2 of docs/EMOJI-SUGGEST-PLAN.md): trailing default so every existing
    // test constructor keeps compiling with no emoji source at all — which is also the exact
    // fail-closed shape a missing asset has.
    private val emojiSuggestPreparationFactory: (ExecutorService) -> EmojiSuggestPreparation? =
        { null },
    // P4/P3b sentence-start tables (docs/TT-SUGGESTIONS.md, docs/ROADMAP-P1.md): same
    // trailing-default shape as the bigram factory — (executor, subtypeId), no factory, no
    // source, no sentence-start band, and every pre-P4 test constructor keeps compiling. The
    // subtypeId parameter is what makes the load per-language: the production factory resolves
    // the table through the artifact registry, never through a language string of its own.
    private val sentStartPreparationFactory: (ExecutorService, String) -> SentStartPreparation? =
        { _, _ -> null },
) {
    /** Production entry point (frozen contract). */
    constructor(
        context: Context,
        strip: StripSurface,
        editor: EditorSurface,
        uiHandler: Handler,
        engineFactory: (String, ResultCallback) -> EngineHandle?,
    ) : this(
        strip,
        editor,
        UiPoster { runnable -> uiHandler.post(runnable) },
        engineFactory,
        { Executors.newSingleThreadExecutor() },
        { executor, subtypeId ->
            DeviceProtectedDictionaryPreparation.create(context, executor, subtypeId)
        },
        false,
        { executor, subtypeId ->
            DeviceProtectedBigramPreparation.create(context, executor, subtypeId)
        },
        { executor -> AssetEmojiSuggestPreparation(context, executor) },
        { executor, subtypeId ->
            // P3b: which language ships a sentence-start table is the artifact registry's
            // answer, not a language string checked here — a language absent from the registry
            // simply gets no preparation and its sentence starts stay silent.
            DictionaryArtifactSpec.sentStartAssetForSubtype(subtypeId)?.let { assetPath ->
                AssetSentStartPreparation(context, executor, assetPath)
            }
        },
    )

    /** Test entry point: injects a synchronous poster + executor and pre-marks the dictionary. */
    internal constructor(
        strip: StripSurface,
        editor: EditorSurface,
        uiPoster: UiPoster,
        engineFactory: (ResultCallback) -> EngineHandle?,
        backgroundExecutor: ExecutorService,
    ) : this(strip, editor, uiPoster, engineFactory, backgroundExecutor, true)

    /**
     * Test entry point that lets a test drive the not-ready -> ready path: same as the primary test
     * constructor but with an explicit initial readiness so a test can start ineligible/not-ready
     * and later fire readiness via [signalDictionaryReadyForTest].
     */
    internal constructor(
        strip: StripSurface,
        editor: EditorSurface,
        uiPoster: UiPoster,
        engineFactory: (ResultCallback) -> EngineHandle?,
        backgroundExecutor: ExecutorService,
        dictionaryReady: Boolean,
    ) : this(
        strip,
        editor,
        uiPoster,
        { _, callback -> engineFactory(callback) },
        { backgroundExecutor },
        { _, _ -> null },
        dictionaryReady,
    )

    private var executor: ExecutorService? = null

    /**
     * One slot per language, created on first need and kept for the controller's lifetime.
     *
     * Concurrent because the production engine factory reads it from the background executor
     * through [engineCatalog] while the UI thread may be inserting the slot of a language the user
     * has just switched to.
     */
    private val slots = ConcurrentHashMap<String, LanguageSlot>()

    /**
     * The subtype whose dictionary is currently selected, or null when the active subtype ships
     * none. This is the ONE place the choice of dictionary is made; everything downstream —
     * storage directory, engine, personal store, lookup key — follows from it.
     */
    private var activeLanguage: String? = DEFAULT_LANGUAGE

    private var eligible: Boolean = false

    /**
     * P7-6 (docs/ROADMAP-P7.md): the glide's own field-level gate — the same field checks as
     * [eligible] (a real cursor, no password-type field, no NO_PERSONALIZED_LEARNING flag, a
     * shipped dictionary) but WITHOUT the suggestions master. A gesture may commit its word in a
     * suggestions-off field; the strip (the suggestions surface) then shows nothing.
     */
    private var glideEligible: Boolean = false
    private var destroyed: Boolean = false

    // The key-neighbor table for the fuzzy pass, built by LatinIME from the live layout. Remembered
    // so an engine started later is handed the current table, and re-pushed on every publish. Null
    // disables the fuzzy pass; the strip and its exact suggestions are unaffected either way.
    private var keyNeighbors: KeyNeighborTable? = null

    /** Set by LatinIME; see [DictionaryUnavailableListener]. */
    var dictionaryUnavailableListener: DictionaryUnavailableListener? = null

    init {
        // The frozen test entry points seed readiness without naming a language; they mean the one
        // that was the only one for the app's first five releases.
        if (initialDictionaryReady) slotFor(DEFAULT_LANGUAGE).dictionaryReady = true
    }

    /** The slot of [subtypeId], created on first mention. Creating one costs no I/O. */
    private fun slotFor(subtypeId: String): LanguageSlot =
        slots.getOrPut(subtypeId) { LanguageSlot(subtypeId) }

    /** The slot of the active language, or null when the active subtype ships no dictionary. */
    private fun activeSlot(): LanguageSlot? = activeLanguage?.let(::slotFor)

    /**
     * Points the controller at [subtypeId]'s dictionary, idling whatever engine the language being
     * left still holds.
     *
     * Idling, not destroying: see [LanguageSlot]. The caller has already bumped the session, so
     * nothing in flight for the old language can repaint the strip.
     */
    private fun setActiveLanguage(subtypeId: String?) {
        val resolved = subtypeId?.takeIf { DictionaryArtifactSpec.forSubtype(it) != null }
        if (resolved == activeLanguage) return
        activeSlot()?.engine?.finishInput()
        activeLanguage = resolved
    }

    // Monotonic edit-session counter. Bumped on every lifecycle boundary so results computed for an
    // older editor state are dropped even if the engine's own generation check would still pass.
    private var sessionId: Long = 0L
    private var requestSessionId: Long = NO_SESSION

    // The latest prefix a lookup was requested for. Not what is on screen; see [displayedPrefix].
    private var pendingPrefix: String = ""

    // The prefix (and session) the candidates currently shown on the strip were computed for. Set
    // ONLY in [applyResult] when non-empty suggestions are actually displayed; cleared to null the
    // instant those words are cleared or superseded. A tap is bound to THIS value, never to the
    // mutable [pendingPrefix], so a stale candidate can never commit against a newer prefix.
    private var displayedPrefix: String? = null
    private var displayedSessionId: Long = NO_SESSION

    // --- E5d NEXT_WORD state, the exact same shape as pendingPrefix/displayedPrefix above, for the
    // other kind of query. At most one of displayedPrefix/displayedContextWord is ever non-null at a
    // time (PROPOSALS.md, "Контракт текста" amendment, "Сосуществование") — enforced by clearing the
    // other one every time either request path runs, not assumed.
    private var pendingContextWord: String = ""
    private var displayedContextWord: String? = null

    // --- P7-3 GLIDE state (docs/GLIDE-PLAN.md), the exact same shape as the other two bindings.
    // At most one of displayedPrefix/displayedContextWord/displayedGlideAlternativesFor is ever
    // non-null — enforced the same way: every request path clears the other two. A glide's decode
    // is requested against the NEXT_WORD context of the gesture moment (the word before the
    // cursor; "" at a field start): the lift-commit goes through the E5d predicted-word path,
    // whose own re-checks (empty trailing word, live context still equal) are the second line of
    // defense.
    //
    // The UX amendment (2026-09-24, the "tapping or lifting commits" line of docs/GLIDE-PLAN.md's
    // DONE-WHEN): finger lift commits the top-1 candidate immediately; the strip then shows the
    // remaining candidates as tappable ALTERNATIVES bound to the committed word
    // ([displayedGlideAlternativesFor]), and one backspace right after a lift-commit deletes the
    // whole committed word ([glideCommittedWord] — the gesture-undo).
    private var pendingGlideContext: String = ""
    /** The word the currently shown glide alternatives belong to (null when no such band). */
    private var displayedGlideAlternativesFor: String? = null
    /** The word a backspace right now would delete whole (the lift-committed or its replacement). */
    private var glideCommittedWord: String? = null

    /** The glide pref, read live; OFF until LatinIME wires the real one. Same seam shape as the
     * autocorrect gate. */
    private var glideGate: GlideGate = GlideGate { false }

    /** The keyboard's shift state for the glide commit's casing rule; OFF until wired. */
    private var glideShiftGate: ShiftStateGate = ShiftStateGate { false }

    // The current layout's key geometry for the glide decode side, built by LatinIME from the
    // live keyboard. Remembered so an engine started later is handed it, and re-pushed on every
    // publish — the [keyNeighbors] pattern verbatim. Null disables glide (fail-closed).
    private var glideGeometry: GlideKeyGeometry? = null

    // Audit 2026-09-02, B4: whether the band the ACTIVE language painted for [pendingContextWord]
    // holds at least one WORD cell of that language. This is NOT "the band is occupied": an
    // emoji-only band and a band the companion language filled both leave it false, and both still
    // deserve the re-request a finished bigram attach exists to issue ([onBigramAttached]). Written
    // only where the NEXT_WORD band is (re)bound: a fresh request clears it for the new moment, and
    // [applyNextWordResult] sets it from the answer it actually painted.
    private var bandHasActiveLanguageWord: Boolean = false

    // --- Правило приоритета языков (docs/LANG-PRIORITY.md). The layout the user chose with their
    // own hand owns the band; the other language may only fill the cells that language left empty,
    // and only from the end.
    //
    // [bandBaseCells] is what is on the strip right now, in strip order and already re-cased — the
    // exact strings handed to [StripSurface.showSuggestions]. The companion's candidates are
    // APPENDED to this list and never mixed into it, which is what makes "no cell the current
    // language occupies ever changes" true by construction rather than by review.
    private var bandBaseCells: List<String> = emptyList()

    // The one outstanding companion lookup, or null when there is none. The kind and the exact text
    // it was made for are kept here so a result that arrives after the user has typed on is dropped
    // by comparing against them, never by trusting the engine's own currency check alone: the
    // companion engine is not asked again on every keystroke, so its newest token can be an old
    // word's.
    private var companionSlot: LanguageSlot? = null
    private var companionKind: LookupKind? = null
    private var companionQuery: String = ""

    /**
     * The clean-run machines of E4c (completed words) and P1 (completed pairs,
     * docs/ROADMAP-P2.md) — state and transitions live in [CleanRunMachine]; everything here used
     * to be six loose fields on this class.
     */
    private val runMachine = CleanRunMachine(editor)

    // --- D3 autocorrect state. Nothing here is persisted and nothing leaves this object except the
    // two editor calls that perform the replacement and its single undo.
    /** The autocorrect setting, read live. OFF until LatinIME wires the real one. */
    private var autocorrectGate: AutocorrectGate = AutocorrectGate { false }

    // --- P2 of Phase 3 (docs/ROADMAP-P3.md): the autocorrect preview. Nothing here is persisted
    // either; both fields describe the current trailing word only and die with it.
    /**
     * The displayed text of the keep-typed cell while the band IS a preview, null on every
     * other band. Consulted only under the tap path's own freshness guards (bound prefix, live
     * session), so a value left behind by an unbound band is never acted on; every
     * [applyPrefixResult] rewrites it, null or fresh.
     */
    private var previewKeepTypedCell: String? = null

    /**
     * The word whose coming correction the user refused by tapping the keep-typed cell, in its
     * raw as-typed form. Word-scoped: cleared the moment the trailing word is anything else
     * (including empty), consumed by the first separator it refuses, and dropped at every
     * boundary [clearRevertState] covers.
     */
    private var suppressedPreviewWord: String? = null

    // --- Emoji-suggest state (mission 2 of docs/EMOJI-SUGGEST-PLAN.md). Nothing here is persisted;
    // the source is immutable once loaded and the band carries no emoji state of its own — the
    // emoji cell is simply part of [bandBaseCells], so every existing clear/invalidate path covers
    // it unchanged.
    /** The emoji-suggestions setting, read live. OFF until LatinIME wires the real one. */
    private var emojiSuggestGate: EmojiSuggestGate = EmojiSuggestGate { false }

    /** The loaded table, or null while it has never finished loading. Load failure is terminal. */
    private var emojiSource: EmojiSuggestSource? = null

    /** Lazily built loading seam; null means fail-closed, with no emoji cell ever. */
    private var emojiPreparation: EmojiSuggestPreparation? = null

    /** Set the moment the one-per-process load is requested; a failure is not retried. */
    private var emojiPreparationRequested: Boolean = false

    // --- Sentence-start state (P4, docs/TT-SUGGESTIONS.md; per-language since P3b,
    // docs/ROADMAP-P1.md). The exact emoji-suggest shape, keyed by language: a source is
    // immutable once loaded, the band carries no sentence-start state of its own beyond the
    // empty-context binding, and every failure direction is silent. Loads are at most once per
    // language per process and happen only when that language actually reaches a sentence
    // start — a Tatar-only user never pays for the Russian table.
    /** The loaded tables by language; a language absent here has never finished loading. */
    private val sentStartSources = HashMap<String, SentStartSource>()

    /** Lazily built loading seams by language; a null factory answer means fail-closed. */
    private val sentStartPreparations = HashMap<String, SentStartPreparation>()

    /** The languages whose one-per-process load was requested; a failure is not retried. */
    private val sentStartPreparationRequested = HashSet<String>()

    /**
     * The D3 undo window: state and transitions live in [RevertWindow]. Nothing here is persisted
     * and nothing leaves this object except the two editor calls that perform the replacement and
     * its single undo.
     */
    private val revertWindow = RevertWindow()

    /** Set once by LatinIME. Kept out of the constructor so the frozen test entry points stay put. */
    fun setCompletionSink(sink: WordCompletionSink) {
        runMachine.completionSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setPairCompletionSink(sink: PairCompletionSink) {
        runMachine.pairCompletionSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setAutocorrectGate(gate: AutocorrectGate) {
        autocorrectGate = gate
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setEmojiSuggestGate(gate: EmojiSuggestGate) {
        emojiSuggestGate = gate
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setGlideGate(gate: GlideGate) {
        glideGate = gate
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setGlideShiftStateGate(gate: ShiftStateGate) {
        glideShiftGate = gate
    }

    /**
     * Registers the tap listener and deliberately nothing else: the background executor, the
     * storage controller and the dictionary are created on first actual need, so a keyboard start
     * with the setting off does no dictionary work at all.
     */
    fun onCreate() {
        strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
    }

    /**
     * Publishes the key-neighbor table used by the fuzzy suggestion pass. LatinIME rebuilds it from
     * the live layout whenever the keyboard or subtype changes and hands it here; a null table (a
     * non-alphabet layout or an ineligible field) disables the fuzzy pass. Stored so an engine
     * started later still receives it, and forwarded to the running engine at once. UI thread only.
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {
        keyNeighbors = table
        // Only the active language's engine is ever asked anything, and LatinIME rebuilds the table
        // from the live layout on every subtype change, so a warm engine of another language keeps
        // the table of its own layout until it becomes active again and is handed a fresh one.
        activeSlot()?.engine?.updateKeyNeighbors(table)
    }

    /**
     * Publishes the live layout's key geometry for the glide decode side (P7-3). Same shape as
     * [updateKeyNeighbors]: LatinIME rebuilds it whenever the keyboard or subtype changes; stored
     * so an engine started later still receives it, and forwarded to the running engine at once.
     * A null geometry disables glide decoding without touching anything else. UI thread only.
     */
    fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        glideGeometry = geometry
        activeSlot()?.engine?.updateGlideGeometry(geometry)
    }

    @JvmOverloads
    fun onStartInput(eligible: Boolean, subtypeId: String? = DEFAULT_LANGUAGE,
                     glideEligible: Boolean = eligible) {
        runMachine.markRunDirty()
        // A new field is one of the six events that make an undo impossible.
        clearRevertState()
        // Lifecycle boundary: one of the only two places allowed to run the blocking engine
        // teardown that a disabled setting scheduled.
        runPendingRelease()
        sessionId++
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandHasActiveLanguageWord = false
        bandBaseCells = emptyList()
        clearCompanionRequest()
        setActiveLanguage(subtypeId)
        this.eligible = eligible && activeLanguage != null
        // P7-6: glide answers its own toggle and its own field gate — not the suggestions master.
        this.glideEligible = glideEligible && activeLanguage != null
        if (!this.eligible && !this.glideEligible) {
            strip.hideSuggestions()
            activeSlot()?.engine?.finishInput()
            return
        }
        // Eligibility alone is not enough to expose the band: while the dictionary is preparing
        // or the engine is unavailable the frozen state table requires GONE/0dp. A successful
        // publishEngine() transitions a cold session to the reserved state.
        val engineWasReady = usableEngine() != null
        if (!this.eligible) {
            // P7-6: suggestions off — the band stays hidden (it is the suggestions surface), but
            // the engine still warms below: the glide decode reads it.
            strip.hideSuggestions()
        } else {
            if (!engineWasReady) {
                strip.hideSuggestions()
            } else {
                strip.reserve()
            }
            strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
        }
        // Becoming eligible is the second of the two events that may request preparation; the flag
        // keeps every later start from queueing another one.
        requestPreparationIfNeeded()
        // A warm engine has no publication callback in this new editor session. Re-request the
        // cached prefix now so changing fields never requires an extra keystroke. Capture readiness
        // before maybeStartEngine() so a cold engine that publishes inline still requests exactly
        // once from publishEngine().
        maybeStartEngine()
        // E5d: no longer gated on a non-empty prefix — requestCurrentPrefix() falls through to
        // NEXT_WORD on an empty one, and re-requesting on this boundary is what lets switching
        // fields show a prediction without an extra keystroke, the same reason this call exists
        // for PREFIX at all. (Itself gated on [eligible]: the band is the suggestions surface.)
        if (this.eligible && engineWasReady && editor.hasKnownCursor()) {
            requestCurrentPrefix()
        }
    }

    fun onTextChanged() {
        revertWindow.advance(sessionId)
        // The lift-commit's whole-word undo lives exactly one text change: any other edit (a
        // typed character above all) closes it — and dissolves a pending alternatives band with
        // the rest of the band state (re-derived below).
        glideCommittedWord = null
        runMachine.trackCleanRun(editor.cachedWordBeforeCursor())
        requestCurrentPrefix()
    }

    fun onSelectionChanged() {
        // A selection change — external, or an internal cursor gesture, which LatinIME routes here —
        // breaks the run: what looks like growth afterwards may be growth of a different word. It is
        // also two of the six events that close the undo window, and for the same reason: the text
        // the replacement described is no longer the text at the cursor.
        runMachine.markRunDirty()
        clearRevertState()
        sessionId++
        activeSlot()?.engine?.finishInput()
        // Any in-flight request is invalidated and whatever was shown is no longer bound to the
        // live editor state, so drop the displayed binding immediately.
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        // Keep the reserved band only after an engine has actually published. Eligibility while
        // the dictionary is preparing/unavailable remains fail-closed at GONE/0dp.
        if (eligible) {
            if (usableEngine() == null) {
                strip.hideSuggestions()
            } else {
                strip.reserve()
            }
        }
    }

    /**
     * The cursor move that [onSelectionChanged] invalidated the band for has settled, and the
     * editor cache behind [EditorSurface] now describes the position the cursor actually stopped at.
     *
     * [onSelectionChanged] does half the job: it drops the binding, blanks the band and invalidates
     * the in-flight generation. It deliberately does NOT look anything up — the emoji panel routes
     * through it precisely to get a band that stays empty, and at the moment it runs the text cache
     * of an EXTERNAL move has not been refetched yet, so a lookup made there would be a lookup for
     * the text the cursor has already left. This is the other half: the band is re-derived from the
     * live editor state once, when that state is known to be current.
     *
     * Without it the band stays blank until the next keystroke, although the cursor sits at the end
     * of a word the dictionary answers perfectly well — the very failure this method exists for.
     * Every other boundary that unbinds the band already re-derives it in exactly this way
     * ([onStartInput], [onSubtypeChanged], [publishEngine]); a cursor move was the one that did not.
     *
     * Three guards keep it from costing anything on the ordinary typing path, where it is posted
     * after every editor cache reload:
     *  - a band that is still BOUND to displayed candidates describes live text already;
     *  - a lookup already issued for this session is on its way, and re-issuing it would drop the
     *    outstanding companion request with it;
     *  - an ineligible field, an unusable engine or an unknown cursor have nothing to derive from.
     *
     * UI thread only, like every other method here.
     */
    fun onCursorMoveSettled() {
        if (destroyed || !eligible) return
        if (usableEngine() == null) return
        if (!editor.hasKnownCursor()) return
        if (displayedPrefix != null || displayedContextWord != null) return
        // A bound glide-alternatives band is a bound band: the backstop must not re-derive over it.
        if (displayedGlideAlternativesFor != null) return
        if (requestSessionId == sessionId) return
        requestCurrentPrefix()
    }

    fun onFinishInput() {
        runMachine.markRunDirty()
        // The contract names this boundary explicitly: the replacement state is erased on
        // onFinishInput and never outlives the editor session.
        clearRevertState()
        // The one boundary where the personal store writes what it has accumulated: usage counters
        // and pending hashes, once, and only if something changed. The pair store (P1) flushes at
        // the same boundary and under the same rule.
        runMachine.onInputFinished()
        sessionId++
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        // Close eligibility before hiding/finishing. A readiness notification queued behind this
        // lifecycle boundary must not start or publish an engine for the finished editor session.
        eligible = false
        glideEligible = false
        strip.hideSuggestions()
        activeSlot()?.engine?.finishInput()
        // The other lifecycle boundary at which a deferred release may run.
        runPendingRelease()
    }

    /**
     * The active subtype changed. [subtypeId] is the NEW subtype's identifier, and it — not the
     * boolean — is what selects the dictionary: switching between the Tatar and the Russian layout
     * switches which of the two shipped dictionaries answers the next keystroke.
     *
     * The boolean-only overload is the monolingual shorthand every caller written before the second
     * dictionary used: it means "still the Tatar subtype, eligibility recomputed".
     */
    @JvmOverloads
    fun onSubtypeChanged(eligible: Boolean, subtypeId: String? = DEFAULT_LANGUAGE,
                         glideEligible: Boolean = eligible) {
        runMachine.markRunDirty()
        // A subtype change is one of the six events that make an undo impossible.
        clearRevertState()
        sessionId++
        // Idles the engine of the language being left; setActiveLanguage does the same for a real
        // language change, and doing it here as well keeps a same-language subtype change (a
        // different layout for the same dictionary) behaving exactly as it always did.
        activeSlot()?.engine?.finishInput()
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        setActiveLanguage(subtypeId)
        this.eligible = eligible && activeLanguage != null
        // P7-6: the glide gate follows the new subtype exactly like the suggestions gate does.
        this.glideEligible = glideEligible && activeLanguage != null
        if (this.eligible) {
            val engineWasReady = usableEngine() != null
            if (engineWasReady) {
                strip.reserve()
            } else {
                // Cold, preparing and unavailable engines all stay GONE until publish succeeds.
                strip.hideSuggestions()
            }
            // The strip view is created lazily, so a listener registered while it did not exist
            // yet was silently dropped. Switching INTO the Tatar subtype with the globe key in an
            // already-open field is a routine path for a bilingual user and may be the first
            // moment the strip exists, so (re)wire the tap listener exactly like onStartInput()
            // does; without this a tap would do nothing for the rest of the editor session.
            strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
            // Switching INTO an eligible subtype in an already-open field must start the engine
            // (if not already running); a freshly started engine looks up the current prefix from
            // publishEngine(). An already-published engine has no publish callback to do that work,
            // so re-request the cached prefix immediately after tt -> non-tt -> tt. Capture the
            // state before maybeStartEngine() so even an inline test executor cannot double-request
            // when a cold engine publishes synchronously.
            requestPreparationIfNeeded()
            maybeStartEngine()
            // E5d: see the comment on the identical gate in onStartInput().
            if (engineWasReady && editor.hasKnownCursor()) {
                requestCurrentPrefix()
            }
        } else {
            strip.hideSuggestions()
            if (this.glideEligible) {
                // P7-6: the band stays out, but the NEW language's engine still warms for glide.
                requestPreparationIfNeeded()
                maybeStartEngine()
            }
        }
    }

    /**
     * Personal words were erased ("Erase all" or "Forget", E4b) while the keyboard is up.
     *
     * Uses EXACTLY the mechanism of an actual subtype change — bump the session (which invalidates
     * any in-flight generation), idle the engine, unbind the displayed candidates — because a second
     * mechanism for the same job is what drifts apart later. The engine itself is untouched: the
     * personal source it reads has already published an empty snapshot, so the next lookup simply
     * finds nothing personal.
     *
     * Saying only "the NEXT lookup has no personal candidates" would not be enough: the user who
     * just confirmed the dialog would still see the erased word in the band, and a tap would insert
     * it through the single commit path like any other candidate. For a feature whose whole value is
     * "erased means erased", that is a defect in the guarantee itself.
     */
    fun onPersonalDictionaryErased() {
        if (destroyed) return
        clearRevertState()
        sessionId++
        activeSlot()?.engine?.finishInput()
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        displayedSessionId = NO_SESSION
        if (eligible) strip.reserve() else strip.hideSuggestions()
    }

    /**
     * The suggestions setting went ON -> OFF while the IME is live.
     *
     * Everything the user can see or reach stops at once, but the engine teardown is only
     * *scheduled*: [destroyHandle] blocks the UI thread for up to 240 ms, which must never land on
     * the keystroke that flipped the setting. Deliberately a separate method rather than a reuse of
     * [onSubtypeChanged]: the two events differ in exactly the part that matters here, whether the
     * engine has to go away at all.
     */
    fun onSuggestionsSettingDisabled() {
        if (destroyed) return
        // Autocorrect is subordinate to suggestions, so the undo window closes with them.
        clearRevertState()
        // Bumping the session invalidates any in-flight lookup, so a result computed for the older
        // generation can no longer repaint the strip.
        sessionId++
        eligible = false
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        requestSessionId = NO_SESSION
        strip.hideSuggestions()
        // P7-6: glide is independent of the master — with the glide gate still open the engine
        // stays warm for the decode and only the band dies with the setting. (The engine staying
        // warm is the same treatment an ineligible field already gets in publishEngine.)
        if (glideEligible) return
        // The setting is global, so EVERY language stops, not just the active one: a warm engine of
        // a language the user is not typing in right now still holds a lease and a mapping, and the
        // user turned the whole feature off.
        for (slot in slots.values) {
            slot.engine?.finishInput()
            // Unconditional: a start that is still in flight publishes a live handle even now (see
            // publishEngine's ineligible branch), and that handle must be released at the boundary
            // too.
            slot.releasePending = true
        }
    }

    /**
     * The suggestions setting went OFF -> ON while the IME is live: the mirror image of
     * [onSuggestionsSettingDisabled] and the only thing that sets eligibility on this path. A
     * SharedPreferences change calls neither [onStartInput] nor [onSubtypeChanged], so without this
     * method the strip would stay hidden until the user left the field and came back.
     *
     * @param eligible freshly recomputed by LatinIME for the current field and subtype.
     */
    @JvmOverloads
    fun onSuggestionsSettingEnabled(eligible: Boolean, subtypeId: String? = DEFAULT_LANGUAGE) {
        if (destroyed) return
        clearRevertState()
        // Mirrors onSuggestionsSettingDisabled: the setting is global, so every language it stopped
        // is un-stopped here.
        for (slot in slots.values) {
            // The setting came back before the deferred release ran: the live engine is still the
            // right mapping, so the release is cancelled instead of being performed and immediately
            // undone. Only a release that has not been ATTEMPTED yet may be cancelled: a refused
            // attempt has already stopped the engine for good (it rejects every later lookup), so
            // that one stays scheduled and the next boundary retries it, after which a fresh engine
            // is started.
            if (!slot.releaseAttemptFailed) {
                slot.releasePending = false
            }
            if (slot.lastPreparationUnavailable) {
                // The single reset point of the requested flag: the last attempt ended Unavailable
                // and a new OFF -> ON transition has now been observed.
                slot.preparationRequested = false
                slot.lastPreparationUnavailable = false
            }
        }
        setActiveLanguage(subtypeId)
        this.eligible = eligible && activeLanguage != null
        if (!this.eligible) {
            // The setting is on, but this field or subtype does not qualify. Nothing may become
            // visible, yet the observed transition still requests preparation so the dictionary is
            // there by the time a Tatar field is opened.
            requestPreparationIfNeeded(explicitEnable = true)
            return
        }
        val engineWasReady = usableEngine() != null
        if (engineWasReady) {
            strip.reserve()
        } else {
            // Cold, preparing and unavailable dictionaries all stay GONE until publish succeeds.
            strip.hideSuggestions()
        }
        // The strip view is inflated lazily from setTapListener(), and while the setting was off it
        // may never have existed at all, so an earlier registration was silently dropped. Re-wire
        // it exactly like onStartInput() does; without this a tap would do nothing for the rest of
        // the editor session (closed HIGH finding of the D1 audit).
        strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
        requestPreparationIfNeeded(explicitEnable = true)
        // Same shape as onStartInput(): capture readiness first so a cold engine that publishes
        // inline requests the current prefix exactly once, from publishEngine().
        maybeStartEngine()
        // E5d: no longer gated on a non-empty prefix — requestCurrentPrefix() falls through to
        // NEXT_WORD on an empty one, and re-requesting on this boundary is what lets switching
        // fields show a prediction without an extra keystroke, the same reason this call exists
        // for PREFIX at all.
        if (engineWasReady && editor.hasKnownCursor()) {
            requestCurrentPrefix()
        }
    }

    fun onDestroy() {
        // Set the guard first so an engine start that publishes after this point (posted onto the
        // UI thread from the background executor) is torn down instead of orphaned.
        destroyed = true
        clearRevertState()
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        for (slot in slots.values) {
            val handle = slot.engine ?: continue
            if (destroyHandle(handle)) {
                // Only drop the reference once the lease is actually released; a still-leaked lease
                // stays recoverable rather than being made permanently unreachable.
                slot.engine = null
            }
        }
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Catalog for the production engine factory. Null until a preparation request has actually
     * created the storage controller; the factory then produces no engine at all and the strip
     * stays GONE, which is the intended fail-closed behaviour rather than an error.
     */
    fun engineCatalog(subtypeId: String): PublishedDictionaryCatalog? =
        slots[subtypeId]?.preparation?.catalog()

    /**
     * P1 of Phase 2 (docs/ROADMAP-P2.md): the dictionary half of the personal-bigram context
     * gate — exact whole-word membership of [normalizedWord] in the dictionary of [subtypeId]'s
     * CURRENT engine, or false when that language has no live engine. Safe to call from the
     * personal store's worker thread: [slots] is concurrent, [LanguageSlot.engine] is `@Volatile`,
     * and the handle's answer is a cache-free read of the read-only mapping. A cold or missing
     * engine answers false — the pair simply does not graduate, the same fail-closed posture the
     * E4c empty-result filter has when the engine never answered.
     */
    fun engineContainsWord(subtypeId: String, normalizedWord: String): Boolean =
        slots[subtypeId]?.engine?.containsWord(normalizedWord) == true

    /**
     * Test seam: drives the exact dictionary-ready path the production prepare callback drives
     * (post [onDictionaryReady] onto the injected [uiPoster]). Lets a test start not-ready and fire
     * readiness deterministically without a real storage controller.
     */
    internal fun signalDictionaryReadyForTest() {
        val slot = activeSlot() ?: slotFor(DEFAULT_LANGUAGE)
        uiPoster.post { onDictionaryReady(slot) }
    }

    /**
     * Handles the dictionary becoming ready. Always runs on the UI owner. A late notification that
     * arrives after [onDestroy] or [onFinishInput] starts nothing and requests nothing.
     */
    private fun onDictionaryReady(slot: LanguageSlot) {
        if (destroyed) return
        slot.dictionaryReady = true
        // A dictionary that finished inflating for a language the user has already switched away
        // from is remembered, not acted on: its engine starts the moment that language is active
        // again.
        if (slot === activeSlot()) maybeStartEngine()
    }

    /**
     * Requests background preparation at most once per enable cycle, creating the background
     * executor and the storage controller on the way if they do not exist yet. Called from every
     * path that can make suggestions wanted; the flag, not the callers, is what keeps the count at
     * one.
     */
    private fun requestPreparationIfNeeded(explicitEnable: Boolean = false) {
        if (destroyed) return
        val slot = activeSlot() ?: return
        if (slot.preparationRequested) {
            // The request is already in flight, but its PROVENANCE can still be upgraded, and must
            // be. A preparation started implicitly by opening a field takes hundreds of milliseconds
            // (unpacking and validating the artifact); the user who sees no band in that window goes
            // to the settings and flips the switch OFF -> ON, which lands here. Dropping the explicit
            // enable on the floor meant the Unavailable result that followed was attributed to the
            // implicit request and reported to nobody — the switch looked like it simply did nothing,
            // the one outcome DictionaryUnavailableListener exists to prevent. Upgrade only: an
            // implicit call never downgrades an explicit request that is already outstanding.
            if (explicitEnable) slot.preparationRequestedByExplicitEnable = true
            return
        }
        // A dictionary that is already published is never prepared again: readiness outlives every
        // later transition of the setting.
        if (slot.dictionaryReady) return
        val active = dictionaryPreparation(slot) ?: return
        slot.preparationRequested = true
        slot.preparationRequestedByExplicitEnable = explicitEnable
        active.prepare { result ->
            // The callback runs on the background executor. Marshal onto the serialized UI owner
            // before touching any controller state.
            uiPoster.post { onPreparationResult(slot, result) }
        }
    }

    /**
     * Handles a preparation result on the UI owner.
     *
     * [PreparationResult.Unavailable] is terminal for the current enable cycle: the strip stays
     * GONE, plain typing is untouched and nothing is logged. Only a fresh OFF -> ON transition of
     * the setting can clear the flag and allow another attempt. The user is shown nothing either,
     * with one exception — a request the user made themselves gets the one-shot message of
     * [DictionaryUnavailableListener].
     */
    private fun onPreparationResult(slot: LanguageSlot, result: PreparationResult) {
        if (destroyed) return
        when (result) {
            is PreparationResult.Published -> {
                slot.lastPreparationUnavailable = false
                // Start the engine (and look up whatever is already typed) so a field opened before
                // the dictionary finished preparing still gets suggestions this session.
                onDictionaryReady(slot)
            }
            is PreparationResult.Unavailable -> {
                slot.lastPreparationUnavailable = true
                if (slot.preparationRequestedByExplicitEnable) {
                    dictionaryUnavailableListener?.onDictionaryUnavailableAfterExplicitEnable()
                }
            }
        }
    }

    /** Lazily built storage seam; null means fail-closed, with no dictionary and no engine. */
    private fun dictionaryPreparation(slot: LanguageSlot): DictionaryPreparation? {
        slot.preparation?.let { return it }
        val backgroundExecutor = backgroundExecutor() ?: return null
        val created = try {
            preparationFactory(backgroundExecutor, slot.subtypeId)
        } catch (_: Throwable) {
            null
        } ?: return null
        slot.preparation = created
        return created
    }

    /** E5c two-stage readiness: [dictionaryPreparation]'s exact shape, for the bigram table. */
    private fun bigramPreparationSeam(slot: LanguageSlot): BigramPreparation? {
        slot.bigramPreparation?.let { return it }
        val backgroundExecutor = backgroundExecutor() ?: return null
        val created = try {
            bigramPreparationFactory(backgroundExecutor, slot.subtypeId)
        } catch (_: Throwable) {
            null
        } ?: return null
        slot.bigramPreparation = created
        return created
    }

    /**
     * PROPOSALS.md, "E5c. Готовность вычислителя двухступенчатая": called from [publishEngine]
     * strictly AFTER the dictionary engine has already been assigned, reserved and (if a prefix
     * was already typed) looked up — never on the path that gets there. Preparing and attaching
     * the bigram table both happen on the background executor and touch no UI-visible state of
     * their own while they run: unlike [onDictionaryReady], there is nothing here for the strip
     * to reflect — [CompositePrefixComputer.predict] simply starts answering once attached.
     * A missing, corrupted, or not-yet-published table leaves [handle] answering NEXT_WORD with
     * an empty list, exactly like before this ran — no failure path reaches the UI thread.
     *
     * The one thing a SUCCESSFUL attach must repair is the window it just closed
     * (docs/NEXTWORD-RACE.md): a NEXT_WORD request that ran while the table was still attaching
     * got its empty list from "not attached yet", not from "no prediction for this context" — and
     * nothing would ask again until the next keystroke, so a field opened on a draft that already
     * ends in "слово␣" showed an empty band indefinitely. [onBigramAttached] re-derives the band
     * on the serialized UI owner, guarded to the exact NEXT_WORD moment the lost request was made
     * for; a context the table genuinely has no answer for comes back empty again and the band
     * stays silent, exactly as before.
     */
    private fun maybeAttachBigramSource(slot: LanguageSlot, handle: EngineHandle) {
        val preparation = bigramPreparationSeam(slot) ?: return
        try {
            preparation.prepare { result ->
                // Runs on the background executor, exactly like requestPreparationIfNeeded's own
                // callback — attachBigramSource performs the same class of blocking I/O and must
                // stay off the UI thread, so this is NOT re-marshaled through uiPoster. Only the
                // tiny re-derivation nudge after a successful attach is.
                if (result is BigramPreparationResult.Published &&
                    handle.attachBigramSource(preparation.catalog())
                ) {
                    uiPoster.post { onBigramAttached(slot, handle) }
                }
            }
        } catch (_: Throwable) {
            // Best-effort: NEXT_WORD simply keeps answering empty, exactly like a corrupted or
            // missing table would.
        }
    }

    /**
     * The bigram table finished attaching to [handle] (E5c's second stage) — the repair half of
     * the first-NEXT_WORD-request race (docs/NEXTWORD-RACE.md).
     *
     * Every guard fails towards leaving the band exactly as it is, the same shape as
     * [onEmojiSuggestReady]: the attach may belong to a language the user has already left, to an
     * engine a scheduled release has since made unusable, or to a controller that is gone; and the
     * live editor state must still be the exact NEXT_WORD moment the outstanding request was
     * built for — re-derived through [EditorSurface] exactly like the tap path re-derives it, so
     * an attach that finished after the user typed on changes nothing.
     *
     * The last guard is about WHAT the band shows, not THAT it shows something (audit 2026-09-02,
     * B4): an emoji-only band and a band the companion language filled both bind
     * [displayedContextWord] without a single word of the ACTIVE language on them, and both still
     * deserve the re-request — the attach is exactly what could turn their "no answer yet" into
     * words. Only a band that already holds an active-language word cell is left alone: the
     * request was answered late-but-correctly and needs nothing. The re-issued request of a
     * context the table genuinely does not answer comes back empty and the reserved band stays
     * empty: legitimate silence is preserved, only the "never asked again" kind is repaired.
     *
     * An attach of a NON-active slot takes [onCompanionBigramAttached]: the companion language
     * races its own table the same way (docs/LANG-PRIORITY.md).
     */
    private fun onBigramAttached(slot: LanguageSlot, handle: EngineHandle) {
        if (destroyed || !eligible) return
        if (slot !== activeSlot()) {
            onCompanionBigramAttached(slot, handle)
            return
        }
        if (usableEngine() !== handle) return
        if (requestSessionId != sessionId) return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        val context = editor.cachedNextWordContext()
        if (context.isEmpty() || context != pendingContextWord) return
        if (bandHasActiveLanguageWord) return
        requestCurrentPrefix()
    }

    /**
     * The bigram table of the COMPANION language finished attaching (audit 2026-09-02, B4). A
     * companion NEXT_WORD lookup issued while its table was still attaching answered empty exactly
     * like the active language's did, and nothing would ask again — so the tail cells stayed empty
     * until the next keystroke. The guards mirror the active path's (same session, same live
     * NEXT_WORD moment, the attach must belong to the engine the slot still holds), plus the one
     * the fill rule itself lives by: there must be a cell left to fill. A moment whose active
     * request is still in flight may see one companion lookup too many — the active answer, when
     * it lands, re-issues the fill anyway ([applyNextWordResult]), so the band always ends in the
     * state the fill rule prescribes.
     */
    private fun onCompanionBigramAttached(slot: LanguageSlot, handle: EngineHandle) {
        if (slot.releasePending) return
        if (slot.engine !== handle) return
        if (requestSessionId != sessionId) return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        val context = editor.cachedNextWordContext()
        if (context.isEmpty() || context != pendingContextWord) return
        // The room rule of [applyCompanionResult]: word cells stay put, the pinned emoji tail is
        // not a cell the companion may take.
        val base = bandBaseCells
        val emojiTail = if (base.isNotEmpty() && isEmojiCell(base.last())) base.last() else null
        val room = SuggestionStripState.CELL_COUNT - (if (emojiTail != null) 1 else 0)
        val wordBase = if (emojiTail != null) base.dropLast(1) else base
        if (wordBase.size >= room) return
        requestCompanionFill(LookupKind.NEXT_WORD, context)
    }

    /**
     * The single background executor, created on the first real need (dictionary preparation or
     * engine start) and never recreated after [onDestroy].
     */
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

    private fun maybeStartEngine() {
        // P7-6: a suggestions-off but glide-eligible session still starts the engine — the glide
        // decode reads it; the band never does.
        if (!eligible && !glideEligible) return
        val slot = activeSlot() ?: return
        if (slot.engine != null || slot.starting || !slot.dictionaryReady) return
        // A lease that has not been released yet still belongs to this controller: never map a
        // second dictionary on top of it. The retry happens at the next lifecycle boundary.
        if (slot.releasePending) return
        val backgroundExecutor = backgroundExecutor() ?: return
        slot.starting = true
        val callback = ResultCallback { token, suggestions, kind ->
            uiPoster.post { applyResult(slot, token, suggestions, kind) }
        }
        val factory = engineFactory
        try {
            backgroundExecutor.execute {
                val handle = try {
                    factory(slot.subtypeId, callback)
                } catch (_: Throwable) {
                    null
                }
                uiPoster.post { publishEngine(slot, handle) }
            }
        } catch (_: Throwable) {
            slot.starting = false
        }
    }

    private fun publishEngine(slot: LanguageSlot, handle: EngineHandle?) {
        slot.starting = false
        if (destroyed) {
            // onDestroy already ran; this start is racing a torn-down controller. Release the
            // freshly acquired lease instead of assigning it, and never expose it as the engine.
            if (handle != null) {
                destroyHandle(handle)
            }
            return
        }
        if (handle == null) {
            // Engine creation failed: do not reserve an empty band for an unavailable dictionary.
            if (slot !== activeSlot()) return
            displayedPrefix = null
            displayedContextWord = null
            displayedGlideAlternativesFor = null
            if (eligible) {
                strip.hideSuggestions()
            }
            return
        }
        slot.engine = handle
        // E5c two-stage readiness: started AFTER the engine is already assigned, never before —
        // this is what makes it true that the bigram table cannot delay publication. Kicked off
        // regardless of `eligible` below: the engine stays warm across an ineligible editor, and
        // attaching costs nothing the UI can observe either way.
        maybeAttachBigramSource(slot, handle)
        if (slot !== activeSlot()) {
            // The user switched language while this engine was starting. Keep it — warm and idle —
            // for the moment they switch back, and leave the strip to whatever the language they
            // are actually typing in is doing. Its key-neighbor table is pushed when it becomes
            // active, because the live layout is the other language's right now.
            handle.finishInput()
            return
        }
        // Hand the freshly started engine the current key-neighbor table so its fuzzy pass is armed
        // without waiting for the next layout change. Null is a valid value (fuzzy pass disabled).
        handle.updateKeyNeighbors(keyNeighbors)
        // P7-3: same push for the glide geometry (null disables glide decoding, fail-closed).
        handle.updateGlideGeometry(glideGeometry)
        if (!eligible) {
            handle.finishInput()
            strip.hideSuggestions()
            return
        }
        // Successful publication is the transition from preparing/unavailable (GONE) to the stable
        // eligible band. Look up whatever the user has already typed without waiting for another
        // keystroke; an empty/unknown prefix leaves the now-available band reserved with 0 results.
        strip.reserve()
        // E5d: see the comment on the identical gate in onStartInput().
        if (editor.hasKnownCursor()) {
            requestCurrentPrefix()
        }
    }

    /**
     * Runs the engine release that a disabled setting scheduled, if it has not been cancelled.
     *
     * Called only from the two lifecycle boundaries ([onStartInput] and [onFinishInput]), never
     * from the settings handler. It does not mark the controller destroyed and does not shut the
     * background executor down: the controller stays fully usable, and re-enabling the setting
     * starts a fresh engine from the already published file. The engine reference is dropped only
     * on a successful release; a lease that refused to close keeps the request pending so the next
     * boundary retries it, and until then no new engine is started on top of it. A refusal is also
     * remembered in [releaseAttemptFailed], which takes the cancellation in
     * [onSuggestionsSettingEnabled] off the table for this release: the handle has already been
     * asked to stop and would be kept alive as a permanently mute engine.
     */
    private fun runPendingRelease() {
        for (slot in slots.values) {
            if (!slot.releasePending) continue
            val handle = slot.engine
            if (handle == null) {
                // Nothing was ever started, or it is already gone: the request is satisfied.
                slot.releasePending = false
                slot.releaseAttemptFailed = false
                continue
            }
            if (destroyHandle(handle)) {
                slot.engine = null
                slot.releasePending = false
                slot.releaseAttemptFailed = false
            } else {
                // Remember the refusal: from here on the setting coming back on no longer cancels
                // this release, because the handle it would keep can no longer serve a lookup.
                slot.releaseAttemptFailed = true
            }
        }
    }

    /**
     * The engine that may still be used, or null.
     *
     * An engine with a pending release is deliberately invisible to every path that exposes the
     * band or dispatches a lookup. Before the release is attempted this only avoids painting a band
     * that is about to go away; after a refused attempt it is what keeps the strip honest, because
     * the handle rejects every request from then on and a reserved band would stay empty forever.
     * The reference itself is kept so the release can be retried at the next boundary.
     */
    private fun usableEngine(): EngineHandle? {
        val slot = activeSlot() ?: return null
        return if (slot.releasePending) null else slot.engine
    }

    /**
     * Bounded engine teardown: one quick attempt, then a single longer bounded retry so a lease
     * that missed the first deadline still gets a chance to release without risking an ANR.
     * Returns true only if the engine fully released.
     */
    private fun destroyHandle(handle: EngineHandle): Boolean {
        if (handle.destroy(DESTROY_TIMEOUT_MS)) return true
        return handle.destroy(DESTROY_TIMEOUT_MS * 3)
    }

    /**
     * The single request path. Reads the current cached prefix and dispatches a lookup, clearing
     * the displayed binding whenever the words are (or become) empty/unresolvable and whenever the
     * prefix changes so stale candidates cannot be tapped in the window before the fresh result
     * arrives. Reused verbatim by [onTextChanged] and by [publishEngine] right after a successful
     * engine publish.
     *
     * It is also where the frozen text contract's "0 results" states are enforced, all BEFORE the
     * engine is asked anything: a cursor sitting inside a word (shared by both PREFIX and NEXT_WORD),
     * and a prefix in mixed capitalization (PREFIX only — NEXT_WORD does not check the context word's
     * casing at all, PROPOSALS.md, "Контракт текста" amendment, "Регистр предсказаний").
     *
     * E5d: an EMPTY prefix no longer unconditionally clears the band. It falls through to
     * [requestNextWordContext], which is where NEXT_WORD's own "0 results" state (no context word
     * available) is enforced — "Сосуществование" in the same amendment: a non-empty prefix always
     * means PREFIX-only, an empty one means NEXT_WORD-only or nothing, never both in the same band.
     */
    /**
     * The language that may fill the cells the active one leaves empty, or null when there is none.
     *
     * "May" is deliberately narrow: the slot must already hold a live engine. A language whose
     * dictionary was never prepared is NOT prepared for this, and a cold engine is NOT started for
     * it — starting one costs an mmap and a worker thread on the input path, and the cold-start
     * invariant is not something a tail cell is worth. The shipped languages are read off
     * [DictionaryArtifactSpec.ALL] in its own order, so which language answers is fixed by the
     * registry rather than by iteration order of the slot map.
     */
    private fun companionSlotForFill(): LanguageSlot? {
        val active = activeLanguage ?: return null
        for (spec in DictionaryArtifactSpec.ALL) {
            if (spec.languageTag == active) continue
            val slot = slots[spec.languageTag] ?: continue
            if (slot.releasePending) continue
            if (slot.engine != null) return slot
        }
        return null
    }

    /**
     * Asks the companion language for [query], but only after the active language has already
     * answered and left a cell empty.
     *
     * Deliberately lazy. The active language fills all three cells for about nine keystrokes in ten
     * (docs/LANG-PRIORITY.md, "Цена"), so asking both engines on every press would pay twice for
     * nothing nine times out of ten; and because this runs only AFTER the active result was applied,
     * the first cell reaches the screen at exactly the moment it does today.
     */
    private fun requestCompanionFill(kind: LookupKind, query: String) {
        clearCompanionRequest()
        if (query.isEmpty()) return
        val slot = companionSlotForFill() ?: return
        val engine = slot.engine ?: return
        val bytes = TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(query))
        val token = when (kind) {
            LookupKind.PREFIX -> engine.request(sessionId, slot.subtypeId, bytes)
            LookupKind.NEXT_WORD -> engine.requestNextWord(sessionId, slot.subtypeId, bytes)
            // A companion language is never asked for a glide: the gesture belongs to the active
            // layout, and the fill rule is a prefix/next-word feature (P7-3 MVP).
            LookupKind.GLIDE -> null
        }
        if (token == null) return
        companionSlot = slot
        companionKind = kind
        companionQuery = query
    }

    /** Forgets the outstanding companion lookup; a result for it can no longer reach the band. */
    private fun clearCompanionRequest() {
        companionSlot = null
        companionKind = null
        companionQuery = ""
    }

    /**
     * Appends the companion language's candidates to the cells the active language left empty.
     *
     * Every guard here fails towards leaving the band exactly as the active language painted it.
     * The three that carry the rule itself: the result must belong to the ONE outstanding companion
     * lookup, the text it was made for must still be the text under the cursor, and the base cells
     * are copied first, so no candidate of the active language can be displaced or reordered.
     */
    private fun applyCompanionResult(
        slot: LanguageSlot,
        token: Any,
        suggestions: List<String>,
        kind: LookupKind,
    ) {
        if (!eligible) return
        if (slot !== companionSlot || kind != companionKind) return
        if (sessionId != requestSessionId) return
        if (slot.releasePending) return
        val engine = slot.engine ?: return
        if (!engine.isCurrent(token)) return
        val query = companionQuery
        val live = when (kind) {
            LookupKind.PREFIX -> pendingPrefix
            LookupKind.NEXT_WORD -> pendingContextWord
            // A companion never holds a glide request (see requestCompanionFill).
            LookupKind.GLIDE -> pendingGlideContext
        }
        if (live != query) return
        clearCompanionRequest()
        if (suggestions.isEmpty()) return
        val base = bandBaseCells
        // The emoji cell, when present, is always the tail one (mission 2 of
        // docs/EMOJI-SUGGEST-PLAN.md): companion words insert BEFORE it and never push it out.
        // A word cell is always a letter sequence; only the emoji cell is letter-free.
        val emojiTail = if (base.isNotEmpty() && isEmojiCell(base.last())) base.last() else null
        val wordBase = if (emojiTail != null) base.dropLast(1) else base
        val room = SuggestionStripState.CELL_COUNT - (if (emojiTail != null) 1 else 0)
        if (wordBase.size >= room) return
        // PREFIX re-applies the typed capitalization to every cell it shows, so the companion's
        // candidates get exactly the same treatment; NEXT_WORD applies none, to either language.
        val casing = if (kind == LookupKind.PREFIX) TatarWordUtils.classifyCasing(query) else null
        val cells = ArrayList<String>(SuggestionStripState.CELL_COUNT)
        cells.addAll(wordBase)
        for (candidate in suggestions) {
            val shown = if (casing == null) candidate else TatarWordUtils.applyCasing(candidate, casing)
            // A word both languages offer occupies ONE cell, and it is the one the active language
            // already gave it.
            if (cells.contains(shown)) continue
            cells.add(shown)
            if (cells.size >= room) break
        }
        if (cells.size == wordBase.size) return
        if (emojiTail != null) cells.add(emojiTail)
        when (kind) {
            LookupKind.PREFIX -> displayedPrefix = query
            LookupKind.NEXT_WORD -> displayedContextWord = query
            // A companion never holds a glide request (see requestCompanionFill); unreachable.
            LookupKind.GLIDE -> return
        }
        displayedSessionId = sessionId
        showBand(cells)
    }

    /** The single call into the strip that paints words, and the only writer of [bandBaseCells]. */
    private fun showBand(
        cells: List<String>,
        emphasizedCell: Int = SuggestionStripState.NO_CELL,
    ) {
        bandBaseCells = cells
        strip.showSuggestions(cells[0], cells.getOrNull(1), cells.getOrNull(2))
        strip.setSpokenCellLabels(
            spokenLabelFor(cells[0]),
            spokenLabelFor(cells.getOrNull(1)),
            spokenLabelFor(cells.getOrNull(2)),
        )
        // P2: the emphasis travels with the words it marks, in the same publication — a marker
        // set apart from them could describe a band that is already gone.
        strip.setEmphasizedCell(emphasizedCell)
    }

    /**
     * True when [cell] holds the emoji candidate rather than a word: every word the band can ever
     * show is a letter sequence (dictionary, bigram and personal candidates are all alphabet-checked
     * by their packers), so a letter-free cell IS the emoji cell. Char-level on purpose: words here
     * are BMP, and a supplementary letter would read as two non-letters — "emoji", the safe
     * direction.
     */
    private fun isEmojiCell(cell: String): Boolean {
        var index = 0
        while (index < cell.length) {
            if (Character.isLetter(cell[index])) return false
            index++
        }
        return true
    }

    /**
     * The spoken label of a cell, or null when the cell's own text reads fine aloud. Only the emoji
     * cell gets a label — the emoji's short name from the search index; a source that never loaded
     * (or a name the index does not hold) leaves the glyph to speak for itself, which TalkBack
     * already does meaningfully.
     */
    private fun spokenLabelFor(cell: String?): String? {
        if (cell.isNullOrEmpty() || !isEmojiCell(cell)) return null
        return emojiSource?.spokenNameOf(cell)
    }

    // --- Emoji suggest (mission 2 of docs/EMOJI-SUGGEST-PLAN.md) --------------------------------

    /**
     * The emoji mapped to [contextWord] on the active language, or null. Every early exit is
     * silent by design: the feature off, a subtype with no table, an unloaded or unusable asset
     * and a word without a mapping all look exactly alike from the strip — the band shows what it
     * would have shown anyway. The FIRST eligible miss is what starts the one-per-process
     * background load, so a user who never turns the toggle on never reads the asset at all.
     */
    private fun emojiCandidate(contextWord: String): String? {
        if (!emojiSuggestGate.isOn()) return null
        val language = activeLanguage ?: return null
        if (emojiSource == null) {
            // Not loaded yet: start the one-time background load. Re-read the field afterwards —
            // a preparation that answers synchronously (a direct test executor) has already
            // published the source by the time the call returns.
            maybePrepareEmojiSuggest()
        }
        val source = emojiSource ?: return null
        return source.emojiFor(
            EmojiSuggestIndex.assetLanguageOf(language),
            TatarWordUtils.normalizeForLookup(contextWord),
        )
    }

    /**
     * Appends the emoji cell to the tail of the current NEXT_WORD band, applying the same
     * tail-pinning [applyNextWordResult] applies synchronously: front cells keep their order, the
     * emoji takes the tail, and the lowest-ranked word cell is the only one that ever yields. Runs
     * from [onEmojiSuggestReady] only, for a band painted before the table finished loading; when
     * it is what puts the first cell on an otherwise empty band it also BINDS the band, so the tap
     * path treats the emoji exactly like a predicted word.
     */
    private fun maybeAppendEmojiTail(contextWord: String) {
        if (contextWord.isEmpty()) return
        val emoji = emojiCandidate(contextWord) ?: return
        if (bandBaseCells.contains(emoji)) return
        val cells = ArrayList<String>(SuggestionStripState.CELL_COUNT)
        val wordCap = SuggestionStripState.CELL_COUNT - 1
        for (cell in bandBaseCells) {
            cells.add(cell)
            if (cells.size >= wordCap) break
        }
        cells.add(emoji)
        displayedGlideAlternativesFor = null
        displayedContextWord = contextWord
        displayedSessionId = sessionId
        showBand(cells)
    }

    /**
     * Starts the one-per-process background load of the emoji-suggest table, at most once and only
     * while the feature is on. A factory or executor failure is silent and terminal: the band
     * simply never grows an emoji cell.
     */
    private fun maybePrepareEmojiSuggest() {
        if (destroyed || emojiPreparationRequested) return
        if (!emojiSuggestGate.isOn()) return
        val backgroundExecutor = backgroundExecutor() ?: return
        val preparation = emojiPreparation ?: run {
            val created = try {
                emojiSuggestPreparationFactory(backgroundExecutor)
            } catch (_: Throwable) {
                null
            } ?: return
            emojiPreparation = created
            created
        }
        emojiPreparationRequested = true
        try {
            preparation.prepare { source ->
                // The callback may run on the background executor. Marshal onto the serialized UI
                // owner before touching any controller state.
                uiPoster.post { onEmojiSuggestReady(source) }
            }
        } catch (_: Throwable) {
            // Silent: the band behaves exactly as if no word ever had a mapping.
        }
    }

    /**
     * The table finished loading. A NEXT_WORD band painted before the table arrived gets its emoji
     * cell filled NOW rather than after the next word — but only if the live editor state is still
     * exactly the NEXT_WORD moment the last request was built for: the same re-derivation the tap
     * path performs, so a load that finished after the user typed on changes nothing.
     */
    private fun onEmojiSuggestReady(source: EmojiSuggestSource?) {
        if (destroyed) return
        emojiSource = source ?: return
        if (!eligible) return
        if (requestSessionId != sessionId) return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        val context = editor.cachedNextWordContext()
        if (context.isEmpty() || context != pendingContextWord) return
        maybeAppendEmojiTail(context)
    }

    private fun requestCurrentPrefix() {
        if (!eligible) return
        val activeEngine = usableEngine()
        if (activeEngine == null) {
            // Text events can arrive while preparation/start is still in flight. Do not expose the
            // band until publishEngine() establishes that the dictionary is actually available.
            displayedPrefix = null
            displayedContextWord = null
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            clearCompanionRequest()
            strip.hideSuggestions()
            return
        }
        if (!editor.hasKnownCursor()) {
            clearToReservedBand()
            return
        }
        // Cursor inside a word: the contract clears the results instead of offering a replacement
        // that would be spliced into the middle of the user's text ("ки|тап" + "т" must not become
        // "китапларtап"). Checked before either path below so the engine is never even asked, and
        // checked ONCE — moved ahead of the prefix/context branch below (it used to run only on the
        // PREFIX path) because "Контракт текста" amendment пункт 2 requires it to gate NEXT_WORD too:
        // "При selection или букве... сразу после курсора правило действует без изменений — NEXT_WORD
        // запрос не строится вообще". Neither check depended on the other's outcome, so this reorders
        // without changing PREFIX behaviour at all.
        if (editor.hasLetterAfterCursor()) {
            clearToReservedBand()
            return
        }
        val word = editor.cachedWordBeforeCursor()
        // P2: the keep-typed refusal is word-scoped — it lives exactly as long as the trailing
        // word it was made for. A different word, including none at all, is a new occurrence.
        if (word != suppressedPreviewWord) suppressedPreviewWord = null
        if (word.isEmpty()) {
            requestNextWordContext(activeEngine)
            return
        }
        // Mixed capitalization has no defined display form in the frozen contract, which requires
        // 0 results for it. Classified on the RAW prefix, before NFC/lowercase folding.
        val casing = TatarWordUtils.classifyCasing(word)
        if (casing == TatarWordUtils.PrefixCasing.MIXED) {
            clearToReservedBand()
            return
        }
        // Prefix changed relative to what is on screen: invalidate the displayed candidates NOW so
        // a tap arriving before the new result can never commit the old candidate against the new
        // prefix. [unbindPaintedBand] takes the words off the strip in the same breath — see its
        // own comment for why unbinding alone is not enough.
        if (word != displayedPrefix) {
            displayedPrefix = null
            unbindPaintedBand()
        }
        // A non-empty prefix is unconditionally PREFIX mode: drop whatever NEXT_WORD state might
        // still be bound from a moment ago, so the two kinds never coexist in the band. The P7-3
        // glide binding is dropped the same way.
        if (displayedContextWord != null) {
            displayedContextWord = null
            unbindPaintedBand()
        }
        if (displayedGlideAlternativesFor != null) {
            displayedGlideAlternativesFor = null
            unbindPaintedBand()
        }
        // A fresh lookup of the active language supersedes whatever the companion was asked
        // before it: the answer is about a word the user has already typed past.
        clearCompanionRequest()
        pendingPrefix = word
        requestSessionId = sessionId
        val prefixBytes = TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(word))
        val token = activeEngine.request(sessionId, activeLanguage ?: return, prefixBytes)
        if (token == null) {
            clearToReservedBand()
        }
    }

    /**
     * E5d NEXT_WORD request path, the sibling [requestCurrentPrefix] falls through to on an empty
     * prefix. Mirrors its PREFIX counterpart's shape exactly (change detection, session stamping,
     * clear-on-null-token) but has no casing gate — "Контракт текста" amendment, "Регистр
     * предсказаний": a mixed-case context word does not suppress a prediction, because nothing about
     * its casing is ever carried into the shown/inserted form.
     */
    private fun requestNextWordContext(activeEngine: EngineHandle) {
        val context = editor.cachedNextWordContext()
        if (context.isEmpty()) {
            // P4 (docs/TT-SUGGESTIONS.md): an empty context at a sentence boundary is not silence
            // but a fresh sentence start, answered synchronously from the sentence-start table —
            // no engine request is issued, so the bigram successors and the P3 after-word forms
            // are suppressed for this slot by construction (a sentence boundary resets context).
            // Anywhere else the frozen "no prediction without a context word" behavior stands.
            if (requestSentenceStart()) return
            clearToReservedBand()
            return
        }
        if (context != displayedContextWord) {
            displayedContextWord = null
            unbindPaintedBand()
        }
        // A NEXT_WORD request is unconditionally not PREFIX mode: drop whatever prefix candidates
        // might still be bound (there should not be any, since this path only runs on an empty
        // prefix, but the invariant is enforced here rather than assumed). The glide binding too.
        if (displayedPrefix != null) {
            displayedPrefix = null
            unbindPaintedBand()
        }
        if (displayedGlideAlternativesFor != null) {
            displayedGlideAlternativesFor = null
            unbindPaintedBand()
        }
        clearCompanionRequest()
        pendingContextWord = context
        // A new NEXT_WORD moment begins: whatever the band painted for the previous one says
        // nothing about this one (audit B4).
        bandHasActiveLanguageWord = false
        requestSessionId = sessionId
        val contextBytes = TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(context))
        val token = activeEngine.requestNextWord(sessionId, activeLanguage ?: return, contextBytes)
        if (token == null) {
            clearToReservedBand()
        }
    }

    // --- Glide (P7-3, docs/GLIDE-PLAN.md) ---------------------------------------------------------

    /**
     * A glide gesture completed on the letter keys (PointerTracker via LatinIME, UI thread).
     * Requests the decode on the engine worker; nothing is shown during the gesture itself
     * (MVP: decode once at ACTION_UP). Every early exit is silent and leaves the band exactly as
     * it is, fail-closed in every direction:
     *  - the feature off (the glide toggle — since P7-6 glide is INDEPENDENT of the suggestions
     *    master; [glideEligible] carries the field-level gate), a destroyed controller, no usable
     *    engine;
     *  - the editor in a state the commit path could not honor: an unknown cursor, a letter right
     *    after the cursor, or a half-typed trailing word (the decoder decodes WHOLE words;
     *    completing a typed prefix by glide is not the MVP).
     *
     * The glide band is bound to the NEXT_WORD context of the moment (the word before the cursor,
     * "" at a field start) — exactly what the lift-commit's [EditorSurface.commitGlideWord]
     * re-derives live before editing. One binding at a time: the other two are dropped here.
     */
    fun onGlideInput(path: GlidePath) {
        if (destroyed || !glideEligible) return
        if (!glideGate.isOn()) return
        val activeEngine = usableEngine() ?: return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        val context = editor.cachedNextWordContext()
        // A glide gesture ends any word's preview moment: no stale keep-typed cell may ride the
        // glide band (the tap path's refusal check would swallow the tap).
        previewKeepTypedCell = null
        displayedPrefix = null
        displayedContextWord = null
        unbindPaintedBand()
        clearCompanionRequest()
        pendingGlideContext = context
        requestSessionId = sessionId
        val token = activeEngine.requestGlide(sessionId, activeLanguage ?: return, path)
        if (token == null) {
            clearToReservedBand()
        }
    }

    /**
     * The glide counterpart of [applyPrefixResult]/[applyNextWordResult] — and, since the UX
     * amendment (2026-09-24, docs/ROADMAP-P7.md), the lift-commit: the plan's DONE-WHEN always
     * said "tapping or lifting commits"; P7-3 shipped tap-only, this closes the Gboard-parity
     * behavior. With candidates present the top-1 is committed IMMEDIATELY through the glide's
     * own commit path ([EditorSurface.commitGlideWord] — P7-6: the predicted-word path's live
     * re-checks minus the sentence-start requirement for an empty context), and the strip then
     * shows the REMAINING candidates as tappable alternatives bound to the committed word; a tap
     * on one replaces the committed word in the editor ([onTap]). With the suggestions master
     * off ([eligible] false, P7-6) the commit still lands — typing, not a suggestion — and the
     * strip shows NOTHING: no alternatives, no follow-up chain.
     *
     * Casing is the display-time rule of the prefix path, sourced from the shift gate (a gesture
     * types no letters to read the casing off): the committed AND the shown forms carry it. With
     * zero candidates nothing is committed and nothing special shows (fail-closed). With exactly
     * one, there are no alternatives and the strip falls through to the ordinary NEXT_WORD chain
     * for the committed word.
     *
     * Learning (pinned): a lift-committed word behaves exactly like a tapped suggestion — the run
     * is marked dirty (it is not a clean run for the word itself) and the boundary it establishes
     * is trusted for the pair machine; it is NOT a noteAcceptedPrediction (nothing was predicted
     * from a learned pair). A later alternative replacement keeps the same semantics.
     */
    private fun applyGlideResult(suggestions: List<String>) {
        previewKeepTypedCell = null
        if (suggestions.isEmpty()) {
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            if (eligible) strip.reserve()
            return
        }
        val casing = if (glideShiftGate.isShifted()) {
            TatarWordUtils.PrefixCasing.INITIAL_CAPS
        } else {
            TatarWordUtils.PrefixCasing.LOWER
        }
        val committed = TatarWordUtils.applyCasing(suggestions[0], casing)
        // The lift-commit: the glide's own commit path re-derives the live context and refuses a
        // stale gesture itself (P7-6: without the prediction tap's sentence-start requirement for
        // an empty context — a gesture at a context-free position like "сүз ? " still types its
        // word); a refusal commits nothing and shows nothing special.
        if (!editor.commitGlideWord(pendingGlideContext, committed)) {
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            if (eligible) strip.reserve()
            return
        }
        // Not the user spelling the word out: the run stops counting; the boundary the committed
        // word just established is trusted for the pair machine (the tap path's exact semantics).
        runMachine.markRunDirty()
        runMachine.trustPairBoundary()
        // One backspace right after the lift deletes the whole committed word (the gesture-undo):
        // the undo word tracks the editor's content — it moves to an alternative if one replaces.
        glideCommittedWord = committed
        if (!eligible) {
            // P7-6: suggestions off — the lift-commit stands on its own (typing, not a
            // suggestion), and the strip, the suggestions surface, shows NOTHING: no alternatives
            // band, no NEXT_WORD chain request.
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            return
        }
        val alternatives = ArrayList<String>(SuggestionStripState.CELL_COUNT)
        for (index in 1 until suggestions.size) {
            alternatives.add(TatarWordUtils.applyCasing(suggestions[index], casing))
            if (alternatives.size >= SuggestionStripState.CELL_COUNT) break
        }
        if (alternatives.isEmpty()) {
            // Nothing to offer as an alternative: the strip falls through to the NEXT_WORD chain
            // for the committed word (the same re-request a tap-commit issues).
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            clearCompanionRequest()
            strip.reserve()
            requestCurrentPrefix()
            return
        }
        displayedGlideAlternativesFor = committed
        displayedSessionId = sessionId
        showBand(alternatives)
    }

    // --- Sentence start (P4, docs/TT-SUGGESTIONS.md) ---------------------------------------------

    /**
     * Paints the sentence-start band when the current position is one, synchronously — the table
     * is static, so there is no engine request, no token and no callback, and the whole paint
     * happens on the UI thread inside the request path, exactly where a NEXT_WORD request would
     * have been issued. Returns false (the caller then falls back to the reserved empty band) in
     * every no-show direction, all silent by design: the active language ships no table (the
     * artifact registry decides per language — since P3b both shipped languages carry one; a
     * subtype absent from the registry never gets a band), the position is not a sentence
     * start, the table is missing/broken/still loading, or it has nothing to offer.
     *
     * The band is bound to the EMPTY context — the one value [displayedContextWord] can hold that
     * the NEXT_WORD path never binds (it refuses an empty context outright) — and the tap path
     * commits it through the same E5d predicted-word editor call, whose production implementation
     * re-derives the live sentence start before editing. [requestSessionId] is stamped NO_SESSION
     * on purpose: no engine request is outstanding, so nothing in flight — a late prefix result of
     * the word before the period above all — may ever land on top of this band, the exact
     * protection [clearToReservedBand] buys with the same stamp. No companion is ever asked either:
     * its query would be the empty context, which [requestCompanionFill] rejects itself.
     */
    private fun requestSentenceStart(): Boolean {
        val language = activeLanguage ?: return false
        if (!editor.isAtSentenceStart()) return false
        var source = sentStartSources[language]
        if (source == null) {
            // Not loaded yet: start this language's one-time background load. Re-read the map
            // afterwards — a preparation that answers synchronously (a direct test executor)
            // has already published the source by the time the call returns.
            maybePrepareSentStart(language)
            source = sentStartSources[language]
        }
        if (source == null) return false
        val words = try {
            source.topWords(SuggestionStripState.CELL_COUNT)
        } catch (_: RuntimeException) {
            return false
        }
        if (words.isEmpty()) return false
        // P3a (docs/ROADMAP-P1.md): a sentence start is where a capital belongs, so the cells
        // are shown capitalized — the same display-boundary casing applyPrefixResult applies to
        // prefix candidates, and the tap commits the displayed (capitalized) string verbatim.
        // The table itself and every lookup stay lowercase; this is display-only.
        val cells = ArrayList<String>(words.size)
        for (word in words) {
            cells.add(TatarWordUtils.applyCasing(word, TatarWordUtils.PrefixCasing.INITIAL_CAPS))
        }
        displayedPrefix = null
        displayedGlideAlternativesFor = null
        pendingContextWord = SENTENCE_START_CONTEXT
        displayedContextWord = SENTENCE_START_CONTEXT
        displayedSessionId = sessionId
        bandHasActiveLanguageWord = true
        requestSessionId = NO_SESSION
        clearCompanionRequest()
        showBand(cells)
        return true
    }

    /**
     * Starts [language]'s one-per-process background load of its sentence-start table, at most
     * once. A factory or executor failure is silent and terminal: a sentence start simply shows
     * the reserved empty band, exactly as before the feature existed.
     */
    private fun maybePrepareSentStart(language: String) {
        if (destroyed || language in sentStartPreparationRequested) return
        val backgroundExecutor = backgroundExecutor() ?: return
        val preparation = sentStartPreparations[language] ?: run {
            val created = try {
                sentStartPreparationFactory(backgroundExecutor, language)
            } catch (_: Throwable) {
                null
            } ?: return
            sentStartPreparations[language] = created
            created
        }
        sentStartPreparationRequested.add(language)
        try {
            preparation.prepare { source ->
                // The callback may run on the background executor. Marshal onto the serialized UI
                // owner before touching any controller state.
                uiPoster.post { onSentStartReady(language, source) }
            }
        } catch (_: Throwable) {
            // Silent: the band behaves exactly as if the table did not exist.
        }
    }

    /**
     * [language]'s table finished loading. The source is stored for its language even when the
     * user has since switched away — the next switch back finds it ready. A sentence start
     * reached BEFORE the table arrived showed the reserved empty band; fill it now rather than
     * after the next keystroke — but only if that language is still the active one and the live
     * editor state is still exactly a sentence-start moment with nothing bound, the same
     * re-derivation [onEmojiSuggestReady] performs, so a load that finished after the user typed
     * on changes nothing.
     */
    private fun onSentStartReady(language: String, source: SentStartSource?) {
        if (destroyed) return
        val loaded = source ?: return
        sentStartSources[language] = loaded
        if (!eligible) return
        if (language != activeLanguage) return
        if (usableEngine() == null) return
        if (displayedPrefix != null || displayedContextWord != null) return
        if (displayedGlideAlternativesFor != null) return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        if (editor.cachedNextWordContext().isNotEmpty()) return
        // Not a sentence start: requestSentenceStart's own guards say no.
        requestSentenceStart()
    }

    /**
     * Takes off the strip whatever it is still painting, once the candidates behind it have been
     * unbound. Keeps the reserved height, so the keyboard does not resize.
     *
     * The invariant this exists for is the one the user relies on and the only one they can check:
     * **what the strip is painting is tappable.** Unbinding alone does not hold it. [onTap] reads
     * [displayedPrefix]/[displayedContextWord] and returns without committing when both are null,
     * so between the unbind and the arrival of the fresh result every word still on the strip is a
     * button that does nothing and says nothing — the exact shape of failure this keyboard treats
     * as a defect even where the code is formally right.
     *
     * Costs one repaint per keystroke that changes the prefix, and only when words were actually
     * painted: `bandBaseCells` empty means the strip is already blank and nothing is touched. The
     * blank lasts one engine round trip, which the caller has just dispatched (see
     * docs/FINAL-POLISH.md for the measured length of that window).
     *
     * Deliberately does NOT touch [requestSessionId] — unlike [clearToReservedBand], the callers
     * here are about to issue a lookup and the result of that lookup must be allowed to land.
     */
    private fun unbindPaintedBand() {
        if (bandBaseCells.isEmpty()) return
        bandBaseCells = emptyList()
        strip.reserve()
    }

    /**
     * Publishes the empty-but-visible band and unbinds everything the strip was showing.
     *
     * The in-flight request generation is invalidated too: these paths deliberately do NOT issue a
     * new lookup, so the engine would still consider an older token current and a late result
     * could repaint words for text the user has already left. Clearing means clearing.
     */
    private fun clearToReservedBand() {
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        requestSessionId = NO_SESSION
        // P7-6: the band is the suggestions surface — with the master off a rejected glide
        // request must not summon an empty 40dp band either.
        if (eligible) strip.reserve() else strip.hideSuggestions()
    }


    private fun applyResult(
        slot: LanguageSlot,
        token: Any,
        suggestions: List<String>,
        kind: LookupKind,
    ) {
        // P7-6: a GLIDE result answers the glide gate, not the suggestions master — with the
        // master off the lift-commit must still land (the band stays out of it either way).
        if (!eligible && !(kind == LookupKind.GLIDE && glideEligible)) return
        // A result computed by the engine of a language the user has left may never repaint the
        // band ON ITS OWN. The session check below already covers it (every language change bumps
        // the session), and the engine's own token carries the dictionary identity, but the owner of
        // the state says so itself rather than relying on either.
        //
        // The ONE thing such a result may do is fill cells the active language left empty, and only
        // when this controller asked it to — that path is [applyCompanionResult] and it never
        // touches a cell the active language occupies.
        if (slot !== activeSlot()) {
            applyCompanionResult(slot, token, suggestions, kind)
            return
        }
        if (sessionId != requestSessionId) return
        val activeEngine = usableEngine() ?: return
        if (!activeEngine.isCurrent(token)) return
        when (kind) {
            LookupKind.PREFIX -> applyPrefixResult(suggestions)
            LookupKind.NEXT_WORD -> applyNextWordResult(suggestions)
            LookupKind.GLIDE -> applyGlideResult(suggestions)
        }
    }

    private fun applyPrefixResult(suggestions: List<String>) {
        if (suggestions.isEmpty()) {
            runMachine.observeEmptyResult(pendingPrefix)
        }
        // P2 (docs/ROADMAP-P3.md): when the separator-time policy would fire on this word, the
        // band stops ranking continuations and announces the coming replacement instead —
        // exactly the AOSP visual contract. The preview owns the whole band: no companion fill
        // rides a band whose cells are a refusal and a correction.
        val preview = computeAutocorrectPreview(autocorrectGate, pendingPrefix, suppressedPreviewWord) {
            usableEngine()?.autocorrectAdvice()
        }
        previewKeepTypedCell = preview?.typedShown
        if (preview != null) {
            displayedGlideAlternativesFor = null
            displayedPrefix = pendingPrefix
            displayedSessionId = sessionId
            showBand(
                listOf(preview.typedShown, preview.correctionShown),
                PREVIEW_EMPHASIZED_CELL,
            )
            return
        }
        if (suggestions.isEmpty()) {
            displayedPrefix = null
            bandBaseCells = emptyList()
            strip.reserve()
            // Nothing of the active language is displaced by an empty band, so the companion may
            // fill it from the first cell — that is the one case where its candidate leads.
            requestCompanionFill(LookupKind.PREFIX, pendingPrefix)
            return
        }
        // The result passed the session and engine currency guards, so pendingPrefix is exactly the
        // prefix these candidates were computed for. Bind the displayed candidates to it atomically.
        displayedGlideAlternativesFor = null
        displayedPrefix = pendingPrefix
        displayedSessionId = sessionId
        // Ranking runs on the normalized lowercase forms, so the typed capitalization is re-applied
        // here, after ranking and to the candidates that are actually shown. The casing comes from
        // the prefix this result was computed for, never from the live editor state, and the strip
        // hands the very same string back on tap, so the displayed and the inserted form match.
        val casing = TatarWordUtils.classifyCasing(pendingPrefix)
        val cells = ArrayList<String>(SuggestionStripState.CELL_COUNT)
        for (candidate in suggestions) {
            cells.add(TatarWordUtils.applyCasing(candidate, casing))
            if (cells.size >= SuggestionStripState.CELL_COUNT) break
        }
        showBand(cells)
        if (cells.size < SuggestionStripState.CELL_COUNT) {
            requestCompanionFill(LookupKind.PREFIX, pendingPrefix)
        }
    }


    /**
     * E5d NEXT_WORD counterpart of [applyPrefixResult]. No E4c learning (that filter is about
     * PROPER prefixes of a growing word; NEXT_WORD only ever fires on an empty prefix, so there is no
     * prefix growth to observe) and no casing re-application — "Контракт текста" amendment, "Регистр
     * предсказаний": predictions are shown and inserted exactly as the bigram table stores them.
     */
    private fun applyNextWordResult(suggestions: List<String>) {
        // The emoji cell is pinned to the TAIL of the band whenever the context word maps to one
        // (mission 2 of docs/EMOJI-SUGGEST-PLAN.md): word predictions keep their order in the
        // front cells, the emoji never leads a band that has words, and the one candidate that
        // ever yields to it is the lowest-ranked tail one (bigram #3). With no mapping the band
        // is byte-for-byte what it was before this feature existed.
        val emoji = emojiCandidate(pendingContextWord)
        if (suggestions.isEmpty()) {
            // The active language put no WORD on the band either way (audit B4): a later bigram
            // attach may still re-ask this context — the emoji cell and any companion fill must
            // not read as "the active language already answered with words".
            bandHasActiveLanguageWord = false
            if (emoji == null) {
                displayedContextWord = null
                bandBaseCells = emptyList()
                strip.reserve()
            } else {
                displayedGlideAlternativesFor = null
                displayedContextWord = pendingContextWord
                displayedSessionId = sessionId
                showBand(listOf(emoji))
            }
            requestCompanionFill(LookupKind.NEXT_WORD, pendingContextWord)
            return
        }
        displayedGlideAlternativesFor = null
        displayedContextWord = pendingContextWord
        displayedSessionId = sessionId
        bandHasActiveLanguageWord = true
        val wordCap = if (emoji == null) {
            SuggestionStripState.CELL_COUNT
        } else {
            SuggestionStripState.CELL_COUNT - 1
        }
        val cells = ArrayList<String>(SuggestionStripState.CELL_COUNT)
        for (candidate in suggestions) {
            cells.add(candidate)
            if (cells.size >= wordCap) break
        }
        if (emoji != null && !cells.contains(emoji)) cells.add(emoji)
        showBand(cells)
        if (cells.size < SuggestionStripState.CELL_COUNT) {
            requestCompanionFill(LookupKind.NEXT_WORD, pendingContextWord)
        }
    }

    /**
     * A word separator has been pressed and is ABOUT to be committed: the last chance to correct the
     * word it finishes (D3). Returns true when the trailing word was actually replaced.
     *
     * Called before the separator reaches the input logic on purpose. At this instant the editor is
     * in exactly the state an accepted suggestion needs — a trailing word, a collapsed cursor right
     * after it — so the replacement is the same single delete + commit, with the same re-checks, and
     * the separator afterwards travels the ordinary path untouched (auto-space, the double-space
     * gesture and the shift update all behave as they always did).
     *
     * Every condition is checked here, and every one of them fails towards NOT editing text:
     *  - the feature is on (and subordinate to suggestions: [eligible] already carries that);
     *  - the user has not refused THIS occurrence's correction through the P2 preview's
     *    keep-typed cell (docs/ROADMAP-P3.md) — a refusal is one-shot and consumed here;
     *  - an engine is usable and the cursor is known;
     *  - the cursor is not inside a word, and the word is not in mixed case (which the frozen
     *    contract gives 0 results for, so it has no defined replacement form either);
     *  - the word is long enough ([AutocorrectPolicy.MIN_WORD_CODE_POINTS], on the normalized form);
     *  - a verdict exists AND was computed for THIS word — a coalesced or never-answered lookup
     *    leaves an older verdict behind, and applying it to a different word is exactly the failure
     *    this comparison exists to prevent;
     *  - the candidate is frequent enough ([AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY]).
     *
     * The last two are re-checked here although the engine already applied them, for the same reason
     * the tap path re-checks the prefix inside the editor: one side of a two-sided decision must not
     * be the only place a rule lives.
     */
    fun maybeAutocorrectBeforeSeparator(separatorCodePoint: Int): Boolean {
        if (destroyed || !eligible) return false
        if (!autocorrectGate.isOn()) return false
        val activeEngine = usableEngine() ?: return false
        if (!editor.hasKnownCursor()) return false
        if (editor.hasLetterAfterCursor()) return false
        val word = editor.cachedWordBeforeCursor()
        if (word.isEmpty()) return false
        // P2 (docs/ROADMAP-P3.md): the user refused THIS occurrence's correction by tapping the
        // preview's keep-typed cell. One-shot: the very separator it armed itself against
        // consumes the refusal, so the same word typed later is a new occurrence.
        if (word == suppressedPreviewWord) {
            suppressedPreviewWord = null
            return false
        }
        val casing = TatarWordUtils.classifyCasing(word)
        if (casing == TatarWordUtils.PrefixCasing.MIXED) return false
        val normalized = TatarWordUtils.normalizeForLookup(word)
        if (normalized.codePointCount(0, normalized.length) <
            AutocorrectPolicy.MIN_WORD_CODE_POINTS
        ) {
            return false
        }
        val advice = activeEngine.autocorrectAdvice() ?: return false
        if (advice.typedWord != normalized) return false
        if (advice.frequency < AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY) return false
        // The user's capitalization is re-applied exactly as it is to a shown candidate, so the
        // replacement is the word they would have got by tapping it.
        val replacement = TatarWordUtils.applyCasing(advice.replacement, casing)
        if (replacement == word) return false
        if (!editor.replaceTypedWord(word, replacement)) return false
        // A correction is not the user spelling the word out: the run stops counting, exactly as it
        // does for an accepted suggestion, so the replaced word reaches neither the pending set nor
        // the personal dictionary.
        runMachine.markRunDirty()
        // Whatever the band was showing described the word that no longer stands there.
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        revertWindow.arm(word, replacement, separatorCodePoint, sessionId)
        // ...but the boundary it establishes IS trusted for pairs (2026-09-24 audit, finding 7),
        // exactly like the tap path: the cursor sits provably right after a real word and the
        // separator the user pressed, so the NEXT cleanly typed word may form a pair with the
        // corrected one. Without this re-arm the early return of the run machine's
        // no-change branch would keep the pair machine dirty past the boundary.
        runMachine.trustPairBoundary()
        return true
    }

    /**
     * A backspace has been pressed. Returns true when it was consumed by undoing the replacement
     * made immediately before it, in which case no character is deleted; false leaves the key to the
     * ordinary backspace path.
     *
     * There is exactly ONE undo. The state is dropped BEFORE the editor is asked to do anything, so
     * a refused undo cannot be retried and the second backspace deletes a character like any other.
     */
    fun maybeRevertAutocorrect(): Boolean {
        val replacement = revertWindow.take() ?: return false
        if (destroyed || !eligible) return false
        if (replacement.sessionId != sessionId) return false
        if (!autocorrectGate.isOn()) return false
        if (!editor.hasKnownCursor()) return false
        runMachine.markRunDirty()
        return editor.revertTypedWord(
            replacement.insertedForm, replacement.separator, replacement.typedForm,
        )
    }

    /**
     * Drops the undo window outright: a field, subtype, selection or setting boundary. The P2
     * keep-typed refusal dies at the same boundaries — each of them also ends the word
     * occurrence the refusal was scoped to.
     */
    private fun clearRevertState() {
        revertWindow.clear()
        suppressedPreviewWord = null
        // The lift-commit's whole-word undo dies at the same boundaries as the autocorrect undo.
        glideCommittedWord = null
    }

    private fun onTap(suggestion: String) {
        // P2 (docs/ROADMAP-P3.md): a tap on the keep-typed cell of a preview band refuses the
        // coming correction for THIS occurrence of the word. It is deliberately NOT an accepted
        // suggestion: nothing is committed (the run is not dirtied — the user typed every letter
        // themselves), the undo window is untouched (a preview can only exist several keystrokes
        // after it provably closed), and the band falls back to the ordinary suggestions of the
        // same word, re-derived so the refusal is visible at once.
        val keepTyped = previewKeepTypedCell
        if (keepTyped != null && displayedSessionId == sessionId && suggestion == keepTyped) {
            val prefix = displayedPrefix ?: return
            suppressedPreviewWord = prefix
            previewKeepTypedCell = null
            displayedPrefix = null
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            clearCompanionRequest()
            strip.reserve()
            requestCurrentPrefix()
            return
        }
        // An accepted suggestion is not the user spelling the word out: the run stops counting.
        runMachine.markRunDirty()
        // A tap is one of the six events that close the undo window.
        clearRevertState()
        if (displayedSessionId != sessionId) {
            // Nothing bound to the current session is displayed (e.g. the text changed and the old
            // candidates were invalidated): a tap must be a no-op and must never commit.
            return
        }
        // Exactly one of the three bindings is ever non-null (PROPOSALS.md, "Контракт текста"
        // amendment, "Сосуществование") — the owner of state reads the kind off what is actually
        // bound, not off the tapped string's content, which is the same rule E5c's engine-level
        // guarantee exists for, one layer up. The branches below are deliberately EXCLUSIVE
        // (2026-09-24 audit, finding 8): if the invariant ever broke, a tap must still commit at
        // most once — never one edit per binding.
        val prefix = displayedPrefix
        if (prefix != null) {
            // Commit against the DISPLAYED prefix, not the mutable pendingPrefix. The editor's own
            // stale-tap guard (re-reads live cache, requires collapsed selection and live trailing
            // word == expectedPrefix, deletes by code points) is the second line of defense.
            if (editor.commitSuggestion(prefix, suggestion)) {
                // The field is still eligible after a commit; clear the words but keep the reserved
                // band so accepting a suggestion does not resize the keyboard.
                displayedPrefix = null
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                // The accepted cell counts as a use: if it shows a saved personal word, the sink
                // bumps its usage counter (in memory; the file moves at the session boundary). A
                // dictionary or unknown word changes nothing — the sink decides (2026-09-24 audit,
                // finding 2; the pairs mirror is noteAcceptedPrediction in the NEXT_WORD branch).
                runMachine.noteAcceptedSuggestion(suggestion)
                // P1: the tapped word itself never counts (markRunDirty above already saw to
                // that), but the boundary it just established — the cursor sits provably right
                // after the committed word and its auto-space — is one the pair machine trusts:
                // the NEXT word the user types out cleanly may form a pair with it ("typed or
                // tapped" context, docs/ROADMAP-P2.md).
                runMachine.trustPairBoundary()
                // A tap-commit never reaches onTextChanged() (no InputTransaction wraps it) and the
                // settle backstop self-cuts on requestSessionId == sessionId, so unless the
                // follow-up lookup is issued right here the band stays empty until the next
                // keystroke. The editor's text cache is synchronously current at this point: with
                // the auto-space appended this falls into the NEXT_WORD path for the word just
                // committed (E5, docs/archive/PROPOSALS.md — predictions after an ACCEPTED word);
                // without it the committed word is the new trailing prefix, exactly as after typed
                // input. The session is deliberately NOT bumped: the commit is part of this
                // session's text, and a late onCursorMoveSettled must still self-cut.
                requestCurrentPrefix()
            }
            return
        }
        val context = displayedContextWord
        val glideAlternativesFor = displayedGlideAlternativesFor
        if (context != null) {
            // Same second line of defense as the PREFIX path, through the E5d commit path instead:
            // the editor re-derives the live context word and refuses a stale tap itself.
            if (editor.commitPredictedWord(context, suggestion)) {
                displayedContextWord = null
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                // P1: an accepted NEXT_WORD cell backed by a learned pair bumps that pair's usage
                // counter (the other half of the pinned usage-then-frequency ranking). The sink
                // decides whether the cell IS a learned pair — a static successor, a word form or
                // a fallback word changes nothing — and a sentence-start band (empty context) is
                // never a pair at all.
                if (context.isNotEmpty()) {
                    runMachine.noteAcceptedPrediction(context, suggestion)
                }
                // Same pair-machine recovery as the PREFIX branch above: the committed prediction
                // is a trusted context for whatever the user types next.
                runMachine.trustPairBoundary()
                // Same reasoning as the PREFIX branch above: the predictions for the word just
                // committed are requested from here, or they never are.
                requestCurrentPrefix()
            }
        } else if (glideAlternativesFor != null) {
            // P7-3.5 (the lift-commit UX amendment, docs/ROADMAP-P7.md): a tap on a glide
            // ALTERNATIVE replaces the just-committed glide word in the editor — the editor's own
            // suffix re-check ("word + its auto-space" must still stand right before the cursor)
            // is the second line of defense. The replacement is still not a clean run (the
            // markRunDirty above already saw to that), the pair machine's trusted boundary moves
            // to the alternative, and the strip refreshes to the NEXT_WORD chain for it.
            if (editor.replaceGlideLiftedWord(glideAlternativesFor, suggestion)) {
                // The undo window tracks the text: a backspace now deletes the ALTERNATIVE whole.
                glideCommittedWord = suggestion
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                runMachine.trustPairBoundary()
                requestCurrentPrefix()
            }
        }
    }

    /**
     * One backspace right after a glide lift-commit deletes the whole committed word (and its
     * auto-space) instead of one character — the Gboard gesture-undo. Returns true when it did.
     *
     * The window holds exactly one word and dies the moment the text changes for any other reason
     * (a typed character, a selection move, a boundary), exactly like the autocorrect undo window
     * it mirrors: the state is dropped BEFORE the editor is asked, so a refused undo cannot be
     * retried and the second backspace deletes a character like any other. The editor's own
     * suffix re-check (the committed word + its auto-space must still stand right before the
     * cursor) is the second line of defense.
     */
    fun maybeUndoGlideCommit(): Boolean {
        val word = glideCommittedWord ?: return false
        glideCommittedWord = null
        // The band of alternatives (if any) described the word that no longer stands there.
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        // P7-6: the undo is part of the gesture's typing UX, so it answers the glide gate — it
        // works with the suggestions master off, where no band ever painted.
        if (destroyed || !glideEligible) return false
        if (!editor.hasKnownCursor()) return false
        return editor.deleteGlideLiftedWord(word)
    }

    companion object {
        /**
         * The language a call that names none means.
         *
         * The app shipped monolingual for five releases and its whole test suite drives the
         * controller through the boolean-only overloads; those mean the language that used to be
         * the only one. Reads the single source of truth (`PersonalSubtypes.TATAR_RU`) so the
         * request key can never drift from `LatinIME.isSuggestionsEligible()`, which reads the same
         * constant.
         */
        internal const val DEFAULT_LANGUAGE = PersonalSubtypes.TATAR_RU
        private const val DESTROY_TIMEOUT_MS = 60L

        // Sentinel for "no request is outstanding". [sessionId] starts at 0 and only ever grows,
        // so this can never be mistaken for a live generation.
        private const val NO_SESSION = -1L

        /**
         * The [displayedContextWord]/[pendingContextWord] binding of a sentence-start band (P4):
         * the empty string, the one context value the NEXT_WORD path never binds — it refuses an
         * empty context outright — so it names "a sentence start" unambiguously. The tap path
         * commits it through the ordinary E5d predicted-word call, whose production implementation
         * re-derives the live sentence start before editing.
         */
        private const val SENTENCE_START_CONTEXT = ""
    }
}
