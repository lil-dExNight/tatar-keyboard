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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiSource
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
     * Optional spoken labels for cells whose own text does not read well aloud (an emoji cell).
     * Called in the same publication as [showSuggestions], after the words and their emphasis; a
     * null entry means "speak the cell's text". Default no-op: the glyph is spoken.
     */
    fun setSpokenCellLabels(first: String?, second: String?, third: String?) {}

    /**
     * The autocorrect preview's emphasis marker: the cell holding the correction the next
     * separator would insert, or [SuggestionStripState.NO_CELL] on every ordinary strip. Called
     * right after [showSuggestions] and before the spoken labels (the emphasis triggers the
     * display rebuild, which must happen even if a label lookup fails). Default no-op.
     */
    fun setEmphasizedCell(cell: Int) {}

    /** Make the strip VISIBLE with no words, keeping its reserved height. */
    fun reserve()

    fun hideSuggestions()
    fun setTapListener(listener: SuggestionTapListener)
}

/**
 * Receives the sequence of an emoji cell accepted from the NEXT_WORD strip, so the recent-emoji
 * list learns it like a panel or search pick. Word cells never reach it, and a tap the editor
 * refused is not an insertion. Fired on the UI thread.
 */
fun interface EmojiInsertionSink {
    fun onEmojiInserted(sequence: String)
}

/**
 * Receives the (context word, emoji) events of the strip's emoji cell, for learned emoji.
 * [noteObservation] fires on every committed emoji cell of a NEXT_WORD strip; [noteUse] fires
 * additionally when the tapped cell is what the learned source itself offered for that word;
 * [onInputFinished] is the session-end flush boundary shared with the word and pair sinks. UI
 * thread only; what persists is decided by the store under its learning predicate. Shaped like
 * [rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink], so an
 * observation-only listener can be a lambda.
 */
fun interface PersonalEmojiSink {
    fun noteObservation(contextWord: String, emojiSequence: String)

    fun noteUse(contextWord: String, emojiSequence: String) {}

    fun onInputFinished() {}
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
    // Trailing defaults (bigram, emoji, sentence-start, tracer): the test constructors below pass
    // none of them, which is also how a missing asset behaves; only the production constructor
    // passes real wiring.
    private val bigramPreparationFactory: (ExecutorService, String) -> BigramPreparation? =
        { _, _ -> null },
    private val emojiSuggestPreparationFactory: (ExecutorService) -> EmojiSuggestPreparation? =
        { null },
    // Per language: the production factory resolves the table through the artifact registry.
    private val sentStartPreparationFactory: (ExecutorService, String) -> SentStartPreparation? =
        { _, _ -> null },
    // Perfetto tracing of the lookup round trip; DISABLED in plain-JVM tests (no android.os.Trace).
    private val lookupTracer: LookupTracer = LookupTracer.DISABLED,
) {
    /** Production entry point. */
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
            // The artifact registry decides which language ships a sentence-start table; a
            // language absent from it gets no preparation and no sentence-start predictions.
            DictionaryArtifactSpec.sentStartAssetForSubtype(subtypeId)?.let { assetPath ->
                AssetSentStartPreparation(context, executor, assetPath)
            }
        },
        LookupTracer.ATRACE,
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
     * The glide's own field-level gate: the field checks of [eligible] (a real cursor, no
     * password-type field, no NO_PERSONALIZED_LEARNING flag, a bundled dictionary) without the
     * suggestions setting. A gesture may commit its word with suggestions off; the strip then
     * shows nothing.
     */
    private var glideEligible: Boolean = false
    private var destroyed: Boolean = false

    // The key-neighbor table for typo recovery, built by LatinIME from the live layout. Remembered
    // so an engine started later is handed the current table, and re-pushed on every publish. Null
    // disables typo recovery; exact suggestions are unaffected.
    private var keyNeighbors: KeyNeighborTable? = null

    /** Set by LatinIME; see [DictionaryUnavailableListener]. */
    var dictionaryUnavailableListener: DictionaryUnavailableListener? = null

    init {
        // The test entry points seed readiness without naming a language; they mean the default
        // one.
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
        // The leaving language's engine is idled and its in-flight lookup (only the active
        // language's lookup is traced) is invalidated with it: no result will arrive.
        endLookupTrace()
        activeLanguage = resolved
        // LatinIME publishes the new layout before the switch, so the stored table and geometry
        // are already the new language's; a warm engine still holds the layout it last saw.
        activeSlot()?.engine?.let {
            it.updateKeyNeighbors(keyNeighbors)
            it.updateGlideGeometry(glideGeometry)
        }
    }

    // Monotonic edit-session counter. Bumped on every lifecycle boundary so results computed for an
    // older editor state are dropped even if the engine's own generation check would still pass.
    private var sessionId: Long = 0L
    private var requestSessionId: Long = NO_SESSION

    // The cookie of the one outstanding traced lookup. At most one active-language request is
    // awaited (a new request supersedes the old one and the engine drops the stale result), so a
    // superseded trip ends where its replacement begins.
    private var traceLookupCookie: Int = NO_TRACE_COOKIE
    private var traceCookieSerial: Int = 0

    // The latest prefix a lookup was requested for. Not what is on screen; see [displayedPrefix].
    private var pendingPrefix: String = ""

    // The prefix (and session) the candidates currently shown on the strip were computed for. Set
    // ONLY in [applyResult] when non-empty suggestions are actually displayed; cleared to null the
    // instant those words are cleared or superseded. A tap is bound to THIS value, never to the
    // mutable [pendingPrefix], so a stale candidate can never commit against a newer prefix.
    private var displayedPrefix: String? = null
    private var displayedSessionId: Long = NO_SESSION

    // --- NEXT_WORD state, shaped like pendingPrefix/displayedPrefix above. At most one of
    // displayedPrefix/displayedContextWord is non-null at a time; every request path clears the
    // other one.
    private var pendingContextWord: String = ""
    private var displayedContextWord: String? = null

    // --- GLIDE state, shaped like the other two bindings. At most one of displayedPrefix,
    // displayedContextWord and displayedGlideAlternativesFor is non-null; every request path clears
    // the other two. A glide decode is requested against the NEXT_WORD context of the gesture
    // (the word before the cursor; "" at a field start), and the commit re-checks it live.
    //
    // Lifting the finger commits the top candidate immediately; the strip then shows the remaining
    // candidates as tappable alternatives bound to the committed word
    // ([displayedGlideAlternativesFor]), and one backspace right after the lift deletes the whole
    // committed word ([glideCommittedWord]).
    private var pendingGlideContext: String = ""
    /** The word the currently shown glide alternatives belong to (null when none are shown). */
    private var displayedGlideAlternativesFor: String? = null
    /** The word a backspace right now would delete whole (the lift-committed or its replacement). */
    private var glideCommittedWord: String? = null

    /** Whether [glideCommittedWord]'s commit prepended the chain space; the undo deletes the
     * space with the word exactly when the commit added it. */
    private var glideCommitPrependedSpace: Boolean = false

    /** The glide setting, read live; OFF until LatinIME wires the real one. */
    private var glideGate: GlideGate = GlideGate { false }

    /** The keyboard's shift state for the glide commit's casing rule; OFF until wired. */
    private var glideShiftGate: ShiftStateGate = ShiftStateGate { TatarWordUtils.PrefixCasing.LOWER }

    // The current layout's key geometry for the glide decoder, built by LatinIME from the live
    // keyboard. Remembered and re-pushed like [keyNeighbors]. Null disables glide.
    private var glideGeometry: GlideKeyGeometry? = null

    // Whether the strip the active language painted for [pendingContextWord] holds at least one
    // word cell of that language. Not "the strip is occupied": an emoji-only strip and one filled
    // by the companion language both leave it false and still deserve the re-request that a
    // finished bigram attach issues ([onBigramAttached]). A fresh NEXT_WORD request clears it;
    // [applyNextWordResult] sets it from the answer it painted.
    private var bandHasActiveLanguageWord: Boolean = false

    // --- Language priority. The layout the user chose owns the strip; the other (companion)
    // language may only fill the cells that language left empty, and only from the end.
    //
    // [bandBaseCells] is what is on the strip right now, in strip order and already re-cased (the
    // strings handed to [StripSurface.showSuggestions]). The companion's candidates are appended to
    // this list, never mixed into it, so no cell of the current language ever changes.
    private var bandBaseCells: List<String> = emptyList()

    // The one outstanding companion lookup, or null. Its kind and text are kept so a result that
    // arrives after the user typed on is dropped by comparing against them: the companion engine
    // is not asked on every keystroke, so its newest token can belong to an old word.
    private var companionSlot: LanguageSlot? = null
    private var companionKind: LookupKind? = null
    private var companionQuery: String = ""

    /** The clean-run machines for completed words and completed pairs; see [CleanRunMachine]. */
    private val runMachine = CleanRunMachine(editor)

    // --- Autocorrect state. Nothing here is persisted and nothing leaves this object except the
    // two editor calls that perform the replacement and its single undo.
    /** The autocorrect setting, read live. OFF until LatinIME wires the real one. */
    private var autocorrectGate: AutocorrectGate = AutocorrectGate { false }

    // --- Autocorrect preview. Not persisted; both fields describe the current trailing word only.
    /**
     * The displayed text of the typed-word cell while the strip is a preview, null otherwise.
     * Consulted only under the tap path's freshness guards (bound prefix, live session), and
     * rewritten by every [applyPrefixResult].
     */
    private var previewKeepTypedCell: String? = null

    /**
     * The word whose coming correction the user refused by tapping the typed-word cell, in its
     * raw as-typed form. Word-scoped: cleared the moment the trailing word is anything else
     * (including empty), consumed by the first separator it refuses, and dropped at every
     * boundary [clearRevertState] covers.
     */
    private var suppressedPreviewWord: String? = null

    /**
     * Normalized words whose correction the user undid in this field session; they are not
     * corrected or previewed again until the session ends. Memory only, capped at
     * [MAX_REFUSED_CORRECTIONS] (the oldest refusal goes first).
     */
    private val refusedCorrections = LinkedHashSet<String>()

    // --- Emoji suggestion state. Not persisted; the source is immutable once loaded, and the emoji
    // cell is simply part of [bandBaseCells], so every clear/invalidate path covers it.
    /** The emoji-suggestions setting, read live. OFF until LatinIME wires the real one. */
    private var emojiSuggestGate: EmojiSuggestGate = EmojiSuggestGate { false }

    /** The loaded table, or null while it has never finished loading. Load failure is terminal. */
    private var emojiSource: EmojiSuggestSource? = null

    /** Lazily built loading seam; null means no emoji cell, ever. */
    private var emojiPreparation: EmojiSuggestPreparation? = null

    /** Set the moment the one-per-process load is requested; a failure is not retried. */
    private var emojiPreparationRequested: Boolean = false

    /** Records an accepted emoji cell in the recents; null (no recording) until wired. */
    private var emojiInsertionSink: EmojiInsertionSink? = null

    /**
     * Learns the (context word, emoji) pair of an accepted emoji cell and flushes it at the
     * session boundary; null (no learning) until wired.
     */
    private var personalEmojiSink: PersonalEmojiSink? = null

    /**
     * The learned word→emoji source consulted before the static table for the emoji cell; null
     * (static only) until wired. Read live on every fill and on the tap that decides
     * [PersonalEmojiSink.noteUse]. The personal-dictionary gate lives inside the source; pausing
     * learning gates writes only, never reads, as for words and pairs.
     */
    private var personalEmojiSource: PersonalEmojiSource? = null

    // --- Sentence-start state, per language, shaped like the emoji state: a source is immutable
    // once loaded and every failure is silent. Each language loads at most once per process, and
    // only when it actually reaches a sentence start.
    /** The loaded tables by language; a language absent here has never finished loading. */
    private val sentStartSources = HashMap<String, SentStartSource>()

    /** Lazily built loading seams by language; a null factory answer means no table. */
    private val sentStartPreparations = HashMap<String, SentStartPreparation>()

    /** The languages whose one-per-process load was requested; a failure is not retried. */
    private val sentStartPreparationRequested = HashSet<String>()

    /** The undo-autocorrect window; see [RevertWindow]. */
    private val revertWindow = RevertWindow()

    /** Set once by LatinIME. Kept out of the constructor so the test entry points stay unchanged. */
    fun setCompletionSink(sink: WordCompletionSink) {
        runMachine.completionSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setPairCompletionSink(sink: PairCompletionSink) {
        runMachine.pairCompletionSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setEmojiInsertionSink(sink: EmojiInsertionSink) {
        emojiInsertionSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setPersonalEmojiSink(sink: PersonalEmojiSink) {
        personalEmojiSink = sink
    }

    /** Set once by LatinIME, for the same reason as [setCompletionSink]. */
    fun setPersonalEmojiSource(source: PersonalEmojiSource) {
        personalEmojiSource = source
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
     * Publishes the key-neighbor table used by typo recovery. LatinIME rebuilds it from the live
     * layout on every keyboard or subtype change; null (a non-alphabet layout or an ineligible
     * field) disables typo recovery. Stored for engines started later and forwarded to the running
     * engine at once. UI thread only.
     */
    fun updateKeyNeighbors(table: KeyNeighborTable?) {
        keyNeighbors = table
        // Only the active language's engine is ever asked anything. A warm engine of another
        // language is handed the stored table when it becomes active again (setActiveLanguage).
        activeSlot()?.engine?.updateKeyNeighbors(table)
    }

    /**
     * Publishes the live layout's key geometry for the glide decoder, like [updateKeyNeighbors].
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
        clearRevertState()
        refusedCorrections.clear()
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
        // A new field ends whatever traced lookup the old one was waiting for (the ineligible
        // branch below idles the engine, which suppresses the result delivery).
        endLookupTrace()
        setActiveLanguage(subtypeId)
        this.eligible = eligible && activeLanguage != null
        // Glide has its own setting and field gate, independent of the suggestions setting.
        this.glideEligible = glideEligible && activeLanguage != null
        if (!this.eligible && !this.glideEligible) {
            strip.hideSuggestions()
            activeSlot()?.engine?.finishInput()
            return
        }
        // Eligibility alone does not show the strip: while the dictionary is preparing or the
        // engine is unavailable it stays GONE (0dp). A successful publishEngine() moves a cold
        // session to the reserved state.
        val engineWasReady = usableEngine() != null
        if (!this.eligible) {
            // Suggestions off: the strip stays hidden, but the engine still warms below because
            // the glide decoder reads it.
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
        // Not gated on a non-empty prefix: requestCurrentPrefix() falls through to NEXT_WORD on an
        // empty one, so switching fields shows a prediction without an extra keystroke. Gated on
        // [eligible], because the strip belongs to suggestions.
        if (this.eligible && engineWasReady && editor.hasKnownCursor()) {
            requestCurrentPrefix()
        }
    }

    fun onTextChanged() {
        revertWindow.advance(sessionId)
        // The glide whole-word undo lives exactly one text change: any other edit closes it, and
        // the alternatives strip is re-derived below with the rest of the strip state.
        glideCommittedWord = null
        glideCommitPrependedSpace = false
        runMachine.trackCleanRun(editor.cachedWordBeforeCursor())
        requestCurrentPrefix()
    }

    fun onSelectionChanged() {
        // A selection change (external, or an internal cursor gesture routed here by LatinIME)
        // breaks the run: what looks like growth afterwards may be a different word. It also
        // closes the undo window, because the replaced text is no longer at the cursor.
        runMachine.markRunDirty()
        clearRevertState()
        sessionId++
        activeSlot()?.engine?.finishInput()
        // finishInput invalidates the in-flight generation, so no result will arrive for it.
        endLookupTrace()
        // Any in-flight request is invalidated and whatever was shown is no longer bound to the
        // live editor state, so drop the displayed binding immediately.
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        // Keep the reserved strip only after an engine has published. While the dictionary is
        // preparing or unavailable, the strip stays GONE (0dp).
        if (eligible) {
            if (usableEngine() == null) {
                strip.hideSuggestions()
            } else {
                strip.reserve()
            }
        }
    }

    /**
     * The cursor move that [onSelectionChanged] invalidated the strip for has settled, and the
     * editor cache now describes the position the cursor stopped at.
     *
     * [onSelectionChanged] drops the binding, blanks the strip and invalidates the in-flight
     * generation, but does not look anything up: the emoji panel routes through it to get a strip
     * that stays empty, and for an external move the text cache has not been refetched yet. This
     * is the other half: the strip is re-derived once, when the editor state is current, like
     * [onStartInput], [onSubtypeChanged] and [publishEngine] do. Otherwise the strip would stay
     * blank until the next keystroke.
     *
     * Guards that keep it free on the ordinary typing path, where it is posted after every cache
     * reload:
     *  - a strip still bound to displayed candidates already describes live text;
     *  - a lookup already issued for this session is on its way, and re-issuing it would drop the
     *    outstanding companion request;
     *  - an ineligible field, an unusable engine or an unknown cursor has nothing to derive from.
     *
     * UI thread only.
     */
    fun onCursorMoveSettled() {
        if (destroyed || !eligible) return
        if (usableEngine() == null) return
        if (!editor.hasKnownCursor()) return
        if (displayedPrefix != null || displayedContextWord != null) return
        // A strip bound to glide alternatives is bound too: do not re-derive over it.
        if (displayedGlideAlternativesFor != null) return
        if (requestSessionId == sessionId) return
        requestCurrentPrefix()
    }

    fun onFinishInput() {
        runMachine.markRunDirty()
        // The replacement state never outlives the editor session.
        clearRevertState()
        refusedCorrections.clear()
        // The one boundary where the personal dictionary and learned pairs write what they have
        // accumulated; see [CleanRunMachine.onInputFinished].
        runMachine.onInputFinished()
        // Learned emoji flush at the same boundary; the sink decides whether anything may be
        // written.
        personalEmojiSink?.onInputFinished()
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
        // The idled engine's in-flight lookup is invalidated with it: end its trace slice.
        endLookupTrace()
        // The other lifecycle boundary at which a deferred release may run.
        runPendingRelease()
    }

    /**
     * The active subtype changed. [subtypeId], not the boolean, selects the dictionary: switching
     * between the Tatar and the Russian layout switches which bundled dictionary answers the next
     * keystroke.
     *
     * The boolean-only overload means "still the Tatar subtype, eligibility recomputed".
     */
    @JvmOverloads
    fun onSubtypeChanged(eligible: Boolean, subtypeId: String? = DEFAULT_LANGUAGE,
                         glideEligible: Boolean = eligible) {
        runMachine.markRunDirty()
        clearRevertState()
        refusedCorrections.clear()
        sessionId++
        // Idles the engine of the language being left; setActiveLanguage does the same for a real
        // language change, and doing it here too covers a same-language subtype change (a
        // different layout for the same dictionary).
        activeSlot()?.engine?.finishInput()
        // The idled engine's in-flight lookup is invalidated with it: end its trace slice.
        endLookupTrace()
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        setActiveLanguage(subtypeId)
        this.eligible = eligible && activeLanguage != null
        // The glide gate follows the new subtype like the suggestions gate does.
        this.glideEligible = glideEligible && activeLanguage != null
        if (this.eligible) {
            val engineWasReady = usableEngine() != null
            if (engineWasReady) {
                strip.reserve()
            } else {
                // Cold, preparing and unavailable engines all stay GONE until publish succeeds.
                strip.hideSuggestions()
            }
            // The strip view is created lazily, so a listener registered before it existed was
            // dropped. Switching into the Tatar subtype in an open field may be the first moment
            // the strip exists, so re-wire the tap listener like onStartInput() does; otherwise a
            // tap would do nothing for the rest of the editor session.
            strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
            // Switching into an eligible subtype in an open field must start the engine if
            // needed; a fresh engine looks up the current prefix from publishEngine(). An already
            // published engine has no publish callback, so re-request the cached prefix here.
            // Readiness is captured before maybeStartEngine() so an inline executor cannot
            // double-request when a cold engine publishes synchronously.
            requestPreparationIfNeeded()
            maybeStartEngine()
            // See the comment on the same gate in onStartInput().
            if (engineWasReady && editor.hasKnownCursor()) {
                requestCurrentPrefix()
            }
        } else {
            strip.hideSuggestions()
            if (this.glideEligible) {
                // The strip stays hidden, but the new language's engine still warms for glide.
                requestPreparationIfNeeded()
                maybeStartEngine()
            }
        }
    }

    /**
     * Personal words were erased ("Erase all" or "Forget") while the keyboard is up.
     *
     * Uses the same mechanism as a subtype change: bump the session (invalidating any in-flight
     * generation), idle the engine, unbind the displayed candidates. The engine itself is
     * untouched; the personal source it reads has already published an empty snapshot.
     *
     * Clearing the strip now matters: otherwise the user who just confirmed the dialog would still
     * see the erased word, and a tap would insert it.
     */
    fun onPersonalDictionaryErased() {
        if (destroyed) return
        clearRevertState()
        sessionId++
        activeSlot()?.engine?.finishInput()
        // The idled engine's in-flight lookup is invalidated with it: end its trace slice.
        endLookupTrace()
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
     * scheduled: [destroyHandle] blocks the UI thread, which must not happen on the keystroke that
     * flipped the setting. Separate from [onSubtypeChanged] because here the engine has to go
     * away.
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
        // Every engine is idled below (or only the strip goes, glide keeping the engine warm);
        // the session bump invalidates the in-flight lookup either way.
        endLookupTrace()
        strip.hideSuggestions()
        // Glide is independent of the suggestions setting: with the glide gate open the engine
        // stays warm for the decoder and only the strip goes (as for an ineligible field in
        // publishEngine).
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
            // right mapping, so the release is cancelled. Only a release not yet attempted may be
            // cancelled: a refused attempt has already stopped the engine for good, so it stays
            // scheduled, the next boundary retries it, and a fresh engine is started afterwards.
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
        // may never have existed, so an earlier registration was dropped. Re-wire it like
        // onStartInput() does; otherwise a tap would do nothing for the rest of the session.
        strip.setTapListener(SuggestionTapListener { suggestion -> onTap(suggestion) })
        requestPreparationIfNeeded(explicitEnable = true)
        // Same shape as onStartInput(): capture readiness first so a cold engine that publishes
        // inline requests the current prefix exactly once, from publishEngine().
        maybeStartEngine()
        // See the comment on the same gate in onStartInput().
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
        // The engines are torn down below; no in-flight lookup will deliver.
        endLookupTrace()
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
     * created the storage controller; the factory then produces no engine and the strip stays
     * GONE, which is intended, not an error.
     */
    fun engineCatalog(subtypeId: String): PublishedDictionaryCatalog? =
        slots[subtypeId]?.preparation?.catalog()

    /**
     * The dictionary half of the learned-pair context check: exact whole-word membership of
     * [normalizedWord] in the dictionary of [subtypeId]'s current engine. Safe from the personal
     * store's worker thread ([slots] is concurrent, [LanguageSlot.engine] is `@Volatile`, the read
     * is cache-free). A cold or missing engine answers false, and the pair is not learned.
     */
    fun engineContainsWord(subtypeId: String, normalizedWord: String): Boolean =
        slots[subtypeId]?.engine?.containsWord(normalizedWord) == true

    /**
     * Releases every live engine's glide word index while idle; called from LatinIME's
     * MSG_DEALLOCATE_MEMORY after the keyboard closes. Each handle posts the drop onto its own
     * worker (the decoder is worker-confined), so this only enqueues; the next glide rebuilds the
     * index on the worker.
     */
    fun releaseGlideIndexes() {
        for (slot in slots.values) {
            slot.engine?.releaseGlideIndex()
        }
    }

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
            // The request is already in flight, but its provenance must still be upgraded. A
            // preparation started implicitly by opening a field takes a while (unpacking and
            // validating the artifact); a user who flips the switch OFF -> ON meanwhile lands
            // here, and an Unavailable result must then be reported to them
            // ([DictionaryUnavailableListener]). Upgrade only: an implicit call never downgrades
            // an outstanding explicit request.
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

    /** Lazily built storage seam; null means no dictionary and no engine. */
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

    /** Lazily built storage seam of the bigram table, like [dictionaryPreparation]. */
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
     * Second readiness stage: prepares and attaches the bigram table. Called from [publishEngine]
     * only after the dictionary engine is assigned, reserved and (if a prefix was typed) looked
     * up, so the table never delays publication. Both steps run on the background executor and
     * touch no UI state; [CompositePrefixComputer.predict] starts answering once attached. A
     * missing, corrupted or unpublished table leaves NEXT_WORD answering an empty list.
     *
     * A successful attach must repair one race: a NEXT_WORD request that ran while the table was
     * attaching got an empty list, and nothing would ask again until the next keystroke (a field
     * opened on a draft ending in "слово " would show an empty strip). [onBigramAttached]
     * re-derives the strip on the UI thread, guarded to the NEXT_WORD moment of the lost request.
     */
    private fun maybeAttachBigramSource(slot: LanguageSlot, handle: EngineHandle) {
        val preparation = bigramPreparationSeam(slot) ?: return
        try {
            preparation.prepare { result ->
                // Runs on the background executor: attachBigramSource does blocking I/O and must
                // stay off the UI thread. Only the re-derivation after a successful attach is
                // posted through uiPoster.
                if (result is BigramPreparationResult.Published &&
                    handle.attachBigramSource(preparation.catalog())
                ) {
                    uiPoster.post { onBigramAttached(slot, handle) }
                }
            }
        } catch (_: Throwable) {
            // Best effort: NEXT_WORD keeps answering empty, as with a missing table.
        }
    }

    /**
     * The bigram table finished attaching to [handle]: the repair half of the race described at
     * [maybeAttachBigramSource].
     *
     * Every guard leaves the strip as it is, like [onEmojiSuggestReady]: the attach may belong to
     * a language the user has left, to an engine a scheduled release made unusable, or to a
     * destroyed controller; and the live editor state, re-derived through [EditorSurface] like on
     * the tap path, must still be the NEXT_WORD moment of the outstanding request.
     *
     * The last guard checks what the strip shows: an emoji-only strip and one filled by the
     * companion language still deserve the re-request. Only a strip that already holds an
     * active-language word is left alone. A context the table has no answer for comes back empty
     * again.
     *
     * An attach of a non-active slot goes to [onCompanionBigramAttached].
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
     * The bigram table of the companion language finished attaching. A companion NEXT_WORD lookup
     * issued while its table was attaching answered empty, and nothing would ask again. Guards
     * mirror the active path (same session, same live NEXT_WORD moment, the engine the slot still
     * holds), plus the fill rule: a cell must be left to fill. If the active request is still in
     * flight, one extra companion lookup may run; the active answer re-issues the fill anyway
     * ([applyNextWordResult]).
     */
    private fun onCompanionBigramAttached(slot: LanguageSlot, handle: EngineHandle) {
        if (slot.releasePending) return
        if (slot.engine !== handle) return
        if (requestSessionId != sessionId) return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        if (editor.cachedWordBeforeCursor().isNotEmpty()) return
        val context = editor.cachedNextWordContext()
        if (context.isEmpty() || context != pendingContextWord) return
        // The room rule of [applyCompanionResult]: word cells stay put, and the companion may not
        // take the emoji cell at the end.
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
        // A suggestions-off but glide-eligible session still starts the engine for the glide
        // decoder.
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
            // Engine creation failed: do not reserve an empty strip for an unavailable dictionary.
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
        // Started after the engine is assigned, so the bigram table cannot delay publication.
        // Regardless of `eligible`: the engine stays warm across an ineligible editor, and
        // attaching has no visible effect.
        maybeAttachBigramSource(slot, handle)
        if (slot !== activeSlot()) {
            // The user switched language while this engine was starting. Keep it — warm and idle —
            // for the moment they switch back, and leave the strip to whatever the language they
            // are actually typing in is doing. Its key-neighbor table and glide geometry are pushed
            // by setActiveLanguage when it becomes active, because the live layout is the other
            // language's right now.
            handle.finishInput()
            return
        }
        // Hand the fresh engine the current key-neighbor table so typo recovery works without
        // waiting for the next layout change. Null disables typo recovery.
        handle.updateKeyNeighbors(keyNeighbors)
        // Same for the glide geometry (null disables glide decoding).
        handle.updateGlideGeometry(glideGeometry)
        if (!eligible) {
            handle.finishInput()
            strip.hideSuggestions()
            return
        }
        // Successful publication moves the strip from GONE (preparing/unavailable) to reserved.
        // Look up whatever the user has already typed without waiting for a keystroke; an empty
        // or unknown prefix leaves the strip reserved with 0 results.
        strip.reserve()
        // See the comment on the same gate in onStartInput().
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
     * remembered in [LanguageSlot.releaseAttemptFailed], so [onSuggestionsSettingEnabled] cannot
     * cancel this release: the handle was already told to stop and would stay a mute engine.
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
     * An engine with a pending release is invisible to every path that shows the strip or
     * dispatches a lookup: before the release attempt this avoids painting a strip about to go
     * away; after a refused attempt the handle rejects every request, and a reserved strip would
     * stay empty forever. The reference is kept so the release can be retried at the next boundary.
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
     * The language that may fill the cells the active one leaves empty, or null when there is none.
     *
     * The slot must already hold a live engine: a dictionary is not prepared and a cold engine is
     * not started for this, since that costs an mmap and a worker thread on the input path. The
     * languages are read from [DictionaryArtifactSpec.ALL] in order, so the registry, not the
     * slot map's iteration order, decides which language answers.
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
     * Lazy on purpose: the active language usually fills all three cells, so asking both engines on
     * every keystroke would mostly be wasted work; and because this runs only after the active
     * result was applied, it never delays the first cell.
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
            // layout, and the fill rule covers only prefix and next-word lookups.
            LookupKind.GLIDE -> null
        }
        if (token == null) return
        companionSlot = slot
        companionKind = kind
        companionQuery = query
    }

    /** Forgets the outstanding companion lookup; a result for it can no longer reach the strip. */
    private fun clearCompanionRequest() {
        companionSlot = null
        companionKind = null
        companionQuery = ""
    }

    /**
     * Appends the companion language's candidates to the cells the active language left empty.
     *
     * Every guard leaves the strip as the active language painted it. The key ones: the result must
     * belong to the one outstanding companion lookup, its text must still be the text under the
     * cursor, and the base cells are copied first, so no candidate of the active language is
     * displaced or reordered.
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
        // The emoji cell, when present, is always the last one: companion words go before it and
        // never push it out. A word cell is always a letter sequence; only the emoji cell is not.
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
        // The emphasis travels with the words it marks, in the same publication. It runs before
        // the spoken labels: setEmphasis triggers the view's one display rebuild, and the label
        // lookup reads the emoji index, which can fail. A label failure then leaves a consistent
        // strip with missing labels, which the next publication re-sends.
        strip.setEmphasizedCell(emphasizedCell)
        strip.setSpokenCellLabels(
            spokenLabelFor(cells[0]),
            spokenLabelFor(cells.getOrNull(1)),
            spokenLabelFor(cells.getOrNull(2)),
        )
    }

    /**
     * True when [cell] holds the emoji candidate rather than a word: every word the strip can show
     * is a letter sequence (all candidate sources are alphabet-checked), so a letter-free cell is
     * the emoji cell. Char-level on purpose: words here are BMP, and a supplementary letter would
     * read as two non-letters, i.e. "emoji", the safe answer.
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
     * cell gets a label (the emoji's short name from the search index); if the source never loaded
     * or has no name, TalkBack speaks the glyph itself.
     */
    private fun spokenLabelFor(cell: String?): String? {
        if (cell.isNullOrEmpty() || !isEmojiCell(cell)) return null
        return emojiSource?.spokenNameOf(cell)
    }

    // --- Emoji suggestions --------------------------------------------------------------------

    /**
     * The emoji mapped to [contextWord] on the active language, or null. Every early exit is
     * silent: the feature off, a subtype with no table, an unloaded or unusable asset and a word
     * without a mapping all look alike to the strip. The first eligible miss starts the
     * one-per-process background load, so the asset is never read while the setting is off.
     *
     * Learned emoji outrank the static table for this cell. The learned read is live and not
     * gated by paused learning (which gates writes only); its gate is the personal-dictionary
     * setting inside the source.
     */
    private fun emojiCandidate(contextWord: String): String? {
        if (!emojiSuggestGate.isOn()) return null
        val language = activeLanguage ?: return null
        if (emojiSource == null) {
            // Not loaded yet: start the one-time background load. The field is re-read afterwards,
            // because a synchronous preparation (a direct test executor) has already published it.
            maybePrepareEmojiSuggest()
        }
        val normalized = TatarWordUtils.normalizeForLookup(contextWord)
        personalEmojiSource?.emojiFor(normalized)?.let { return it }
        val source = emojiSource ?: return null
        return source.emojiFor(
            EmojiSuggestIndex.assetLanguageOf(language),
            normalized,
        )
    }

    /**
     * Appends the emoji cell to the end of the current NEXT_WORD strip, like [applyNextWordResult]
     * does: front cells keep their order, the emoji takes the last cell, and only the lowest-ranked
     * word cell may yield. Runs from [onEmojiSuggestReady] only, for a strip painted before the
     * table loaded; it also binds the strip, so the tap path treats the emoji like a predicted word.
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
     * while the feature is on. A factory or executor failure is silent and terminal: the strip
     * never gets an emoji cell.
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
            // Silent: the strip behaves as if no word had a mapping.
        }
    }

    /**
     * The table finished loading. A NEXT_WORD strip painted before it arrived gets its emoji cell
     * now, but only if the live editor state is still the NEXT_WORD moment of the last request
     * (re-derived like on the tap path), so a load that finishes after the user typed on changes
     * nothing.
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

    /**
     * Releases the emoji suggestion table while idle (LatinIME MSG_DEALLOCATE_MEMORY). A loaded
     * table is reloaded lazily on the next eligible miss; a failed load stays failed (the flag is
     * reset only when a source existed). UI thread. Called only after the keyboard has closed, so
     * no emoji cell is on screen.
     */
    fun releaseEmojiSuggest() {
        if (emojiSource == null) return
        emojiSource = null
        emojiPreparationRequested = false
    }

    /**
     * Opens the async Perfetto slice of a lookup round trip, closing any slice still open: that
     * request was just superseded, and the engine never delivers its stale result. See
     * [LookupTracer] for what may be traced.
     */
    private fun beginLookupTrace() {
        endLookupTrace()
        val cookie = if (traceCookieSerial == Int.MAX_VALUE) 1 else traceCookieSerial + 1
        traceCookieSerial = cookie
        traceLookupCookie = cookie
        lookupTracer.beginAsync(cookie)
    }

    /**
     * Closes the outstanding lookup slice, if any. Called where the answer arrives ([applyResult])
     * and at every boundary that cancels an in-flight request without replacing it, so a canceled
     * trip never shows as a long open slice. Ending an unopened cookie is ignored by the platform.
     */
    private fun endLookupTrace() {
        val cookie = traceLookupCookie
        if (cookie == NO_TRACE_COOKIE) return
        traceLookupCookie = NO_TRACE_COOKIE
        lookupTracer.endAsync(cookie)
    }

    /**
     * The single request path. Reads the cached prefix and dispatches a lookup, clearing the
     * displayed binding whenever the words become empty or the prefix changes, so stale candidates
     * cannot be tapped before the fresh result arrives. Used by [onTextChanged] and [publishEngine].
     *
     * The "0 results" states are enforced here, before the engine is asked: a cursor inside a word
     * (PREFIX and NEXT_WORD) and a prefix in mixed case (PREFIX only; NEXT_WORD ignores the context
     * word's casing). An empty prefix falls through to [requestNextWordContext]: a non-empty prefix
     * means PREFIX only, an empty one NEXT_WORD or nothing, never both on one strip.
     */
    private fun requestCurrentPrefix() {
        if (!eligible) return
        val activeEngine = usableEngine()
        if (activeEngine == null) {
            // Text events can arrive while preparation/start is still in flight. Do not show the
            // strip until publishEngine() establishes that the dictionary is available.
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
        // Cursor inside a word: clear the results instead of offering a replacement that would be
        // spliced into the middle of the user's text ("ки|тап" + "т" must not become
        // "китапларtап"). Checked once, before both the PREFIX and the NEXT_WORD path, so the
        // engine is never asked.
        if (editor.hasLetterAfterCursor()) {
            clearToReservedBand()
            return
        }
        val word = editor.cachedWordBeforeCursor()
        // A refused preview lives exactly as long as the trailing word it was made for. A
        // different word, including none, is a new occurrence.
        if (word != suppressedPreviewWord) suppressedPreviewWord = null
        if (word.isEmpty()) {
            requestNextWordContext(activeEngine)
            return
        }
        // Mixed capitalization has no defined display form, so it yields 0 results. Classified on
        // the raw prefix, before NFC/lowercase folding.
        val casing = TatarWordUtils.classifyCasing(word)
        if (casing == TatarWordUtils.PrefixCasing.MIXED) {
            clearToReservedBand()
            return
        }
        // Duplicate suppression: the strip is already bound to this word's results from this
        // session, and a re-request would be answered identically. A prefix revisited after a
        // different one (backspace) does not match and re-requests. A companion fill in flight for
        // this word survives the skip and still appends its answer.
        if (word == displayedPrefix && displayedSessionId == sessionId) return
        // The prefix changed relative to what is on screen: invalidate the displayed candidates now
        // so a tap before the new result cannot commit an old candidate against the new prefix.
        // [unbindPaintedBand] also takes the words off the strip; see its comment.
        if (word != displayedPrefix) {
            displayedPrefix = null
            unbindPaintedBand()
        }
        // A non-empty prefix is always PREFIX mode: drop any NEXT_WORD or glide binding, so the
        // kinds never coexist on the strip.
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
        val language = activeLanguage ?: return
        // The round trip crosses threads, hence the async slice; a null token means no request
        // left the UI thread, so the slice closes at once.
        beginLookupTrace()
        val token = activeEngine.request(sessionId, language, prefixBytes)
        if (token == null) {
            endLookupTrace()
            clearToReservedBand()
        }
    }

    /**
     * NEXT_WORD request path, which [requestCurrentPrefix] falls through to on an empty prefix.
     * Mirrors the PREFIX path (change detection, session stamping, clear on a null token) without
     * the casing gate: the context word's casing is never carried into the shown or inserted form.
     */
    private fun requestNextWordContext(activeEngine: EngineHandle) {
        val context = editor.cachedNextWordContext()
        if (context.isEmpty()) {
            // An empty context at a sentence boundary is a sentence start, answered synchronously
            // from the sentence-start table. No engine request is issued, so bigram successors and
            // after-word forms do not appear. Anywhere else: no context word, no prediction.
            if (requestSentenceStart()) return
            clearToReservedBand()
            return
        }
        // Duplicate suppression as in the PREFIX path, plus bandHasActiveLanguageWord: an
        // emoji-only strip also carries displayedContextWord, and skipping the re-request that
        // onCompanionBigramAttached sends for it would leave the cells empty. With an
        // active-language word on the strip the attach handler never re-requests, so the skip is
        // safe.
        if (context == displayedContextWord && displayedSessionId == sessionId
            && bandHasActiveLanguageWord
        ) return
        if (context != displayedContextWord) {
            displayedContextWord = null
            unbindPaintedBand()
        }
        // A NEXT_WORD request is never PREFIX mode: drop any prefix or glide binding (there should
        // be none on an empty prefix, but the invariant is enforced, not assumed).
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
        // A new NEXT_WORD moment begins: what the strip painted for the previous one says nothing
        // about this one.
        bandHasActiveLanguageWord = false
        requestSessionId = sessionId
        val contextBytes = TatarWordUtils.toLookupBytes(TatarWordUtils.normalizeForLookup(context))
        val language = activeLanguage ?: return
        // Same async round trip as the PREFIX path; see beginLookupTrace.
        beginLookupTrace()
        val token = activeEngine.requestNextWord(sessionId, language, contextBytes)
        if (token == null) {
            endLookupTrace()
            clearToReservedBand()
        }
    }

    // --- Glide typing -----------------------------------------------------------------------------

    /**
     * A glide gesture completed on the letter keys (PointerTracker via LatinIME, UI thread).
     * Requests the decode on the engine worker; nothing is shown during the gesture (one decode at
     * ACTION_UP). Every early exit is silent and leaves the strip as it is:
     *  - glide off (its own setting, independent of suggestions; [glideEligible] is the field
     *    gate), a destroyed controller, no usable engine;
     *  - an editor state the commit path could not honor: an unknown cursor, a letter right after
     *    the cursor, or a half-typed trailing word (the decoder decodes whole words). The one
     *    tolerated trailing word is the chain's previous glide commit, which gets the chain space.
     *
     * The glide strip is bound to the NEXT_WORD context of the moment (the word before the cursor,
     * "" at a field start), which [EditorSurface.commitGlideWord] re-derives live before editing.
     * One binding at a time: the other two are dropped here.
     */
    fun onGlideInput(path: GlidePath) {
        if (destroyed || !glideEligible) return
        if (!glideGate.isOn()) return
        val activeEngine = usableEngine() ?: return
        if (!editor.hasKnownCursor() || editor.hasLetterAfterCursor()) return
        // The one tolerated trailing word is the chain's previous glide commit (still in its undo
        // window); a second glide extends it ("сәләм" → "сәләм дөнья"). Any other trailing word is
        // a half-typed prefix, which glide does not complete.
        val trailingWord = editor.cachedWordBeforeCursor()
        if (trailingWord.isNotEmpty() && trailingWord != glideCommittedWord) return
        val context = editor.cachedNextWordContext()
        // A glide gesture ends any autocorrect preview: no stale typed-word cell may stay on the
        // glide strip (the tap path's refusal check would swallow the tap).
        previewKeepTypedCell = null
        displayedPrefix = null
        displayedContextWord = null
        unbindPaintedBand()
        clearCompanionRequest()
        // The decode supersedes any lookup in flight (the engine drops its stale result, so no
        // arrival will end its slice). The decode itself is not traced.
        endLookupTrace()
        pendingGlideContext = context
        requestSessionId = sessionId
        // Only one glide word index may be resident. Warm slots stay alive on purpose (see
        // LanguageSlot), so before this language builds its index the others drop theirs. Each
        // drop is posted to its own engine's worker (LatestOnlyPrefixEngine.releaseGlideIndex),
        // and a language the user returns to rebuilds lazily on its next gesture.
        releaseGlideIndexesExcept(activeLanguage)
        val language = activeLanguage ?: return
        val token = activeEngine.requestGlide(sessionId, language, path)
        if (token == null) {
            clearToReservedBand()
        }
    }

    /**
     * Drops every glide word index but [keepSubtypeId]'s. A null id drops all of them (with no
     * active language no index is worth keeping).
     */
    private fun releaseGlideIndexesExcept(keepSubtypeId: String?) {
        for ((subtypeId, slot) in slots) {
            if (subtypeId == keepSubtypeId) continue
            slot.engine?.releaseGlideIndex()
        }
    }

    /**
     * The glide counterpart of [applyPrefixResult]/[applyNextWordResult], and the lift-commit: the
     * top candidate is committed immediately through [EditorSurface.commitGlideWord], and the strip
     * then shows the remaining candidates as alternatives bound to the committed word; a tap on
     * one replaces the committed word ([onTap]). With suggestions off ([eligible] false) the
     * commit still lands (it is typing) and the strip shows nothing.
     *
     * Casing follows the prefix path's display rule, taken from the shift gate (a gesture types
     * no letters to read casing from); the committed and the shown forms both carry it. With zero
     * candidates nothing is committed. With exactly one there are no alternatives, and the strip
     * is derived afresh as if the word had been typed (the committed word is the trailing word).
     *
     * Learning: a lift-committed word behaves like a tapped suggestion. The run is marked dirty,
     * the new boundary is trusted for pairs, and the word is reported as an accepted suggestion
     * (not as an accepted prediction). A later alternative counts the same way. Neither the
     * replacement nor the one-backspace undo rolls back the usage bump, as on the tap path, which
     * keeps the undo paths free of counter arithmetic.
     */
    private fun applyGlideResult(suggestions: List<String>) {
        previewKeepTypedCell = null
        if (suggestions.isEmpty()) {
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            if (eligible) strip.reserve()
            return
        }
        val casing = glideShiftGate.glideCasing()
        val committed = TatarWordUtils.applyCasing(suggestions[0], casing)
        // The glide commit path re-derives the live context and refuses a stale gesture itself
        // (see [EditorSurface.commitGlideWord]); a refusal commits nothing.
        val commitResult = editor.commitGlideWord(pendingGlideContext, committed, glideCommittedWord)
        if (commitResult == EditorSurface.GLIDE_COMMIT_REFUSED) {
            displayedGlideAlternativesFor = null
            bandBaseCells = emptyList()
            if (eligible) strip.reserve()
            return
        }
        // Not the user spelling the word out: the run stops counting, and the new boundary is
        // trusted for pairs, as on the tap path.
        runMachine.markRunDirty()
        runMachine.trustPairBoundary()
        // The lift-commit is the acceptance: a saved personal word's usage counter moves (in
        // memory; the file is written at the session boundary). The sink applies the learning
        // predicate, so this also runs with suggestions off.
        runMachine.noteAcceptedSuggestion(committed)
        // One backspace right after the lift deletes the whole committed word. The undo word
        // tracks the editor (it moves to an alternative if one replaces it), and the chain space
        // goes with the word exactly when the commit added it.
        glideCommittedWord = committed
        glideCommitPrependedSpace = commitResult == EditorSurface.GLIDE_COMMIT_PREPENDED
        if (!eligible) {
            // Suggestions off: the lift-commit stands on its own (it is typing), and the strip
            // shows nothing: no alternatives, no NEXT_WORD request.
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
            // No alternatives: derive the strip afresh for the committed word, which is the
            // trailing word, so the strip is what a typed word would get (the prefix path).
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

    // --- Sentence start ------------------------------------------------------------------------

    /**
     * Paints the sentence-start strip synchronously when the position is a sentence start: the
     * table is static, so there is no engine request, token or callback. Returns false (the caller
     * then shows the reserved empty strip) when the active language ships no table (the artifact
     * registry decides), the position is not a sentence start, the table is missing, broken or
     * still loading, or it has nothing to offer.
     *
     * The strip is bound to the empty context, a value the NEXT_WORD path never binds, and the tap
     * path commits it through the predicted-word editor call, which re-derives the live sentence
     * start. [requestSessionId] is set to NO_SESSION because no engine request is outstanding, so
     * no late result (such as a prefix result for the word before the period) can land on top, as
     * in [clearToReservedBand]. No companion is asked: [requestCompanionFill] rejects an empty
     * query.
     */
    private fun requestSentenceStart(): Boolean {
        val language = activeLanguage ?: return false
        if (!editor.isAtSentenceStart()) return false
        var source = sentStartSources[language]
        if (source == null) {
            // Not loaded yet: start this language's one-time background load. The map is re-read
            // afterwards, because a synchronous preparation (a direct test executor) has already
            // published the source.
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
        // A sentence start takes a capital, so the cells are shown capitalized (display-time
        // casing, as in applyPrefixResult), and the tap commits the displayed string. The table
        // and every lookup stay lowercase.
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
     * once. A factory or executor failure is silent and terminal: a sentence start then shows the
     * reserved empty strip.
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
            // Silent: the strip behaves as if the table did not exist.
        }
    }

    /**
     * [language]'s table finished loading. The source is stored even if the user has switched
     * away. A sentence start reached before the table arrived showed the reserved empty strip;
     * fill it now, but only if the language is still active and the live editor state is still a
     * sentence start with nothing bound (re-derived like in [onEmojiSuggestReady]).
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
     * Invariant: what the strip paints is tappable. Unbinding alone does not hold it: [onTap]
     * returns without committing when [displayedPrefix] and [displayedContextWord] are both null,
     * so until the fresh result arrives every word left on the strip would be a dead button.
     *
     * Costs one repaint per keystroke that changes the prefix, only when words were painted (empty
     * `bandBaseCells` means the strip is already blank). The blank lasts one engine round trip,
     * which the caller has just dispatched.
     *
     * Does not touch [requestSessionId], unlike [clearToReservedBand]: the callers are about to
     * issue a lookup whose result must be allowed to land.
     */
    private fun unbindPaintedBand() {
        if (bandBaseCells.isEmpty()) return
        bandBaseCells = emptyList()
        strip.reserve()
    }

    /**
     * Shows the empty but visible strip and unbinds everything it was showing.
     *
     * The in-flight request generation is invalidated too: these paths issue no new lookup, so the
     * engine would still consider an older token current and a late result could repaint words
     * for text the user has already left.
     */
    private fun clearToReservedBand() {
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        requestSessionId = NO_SESSION
        // With suggestions off, a rejected glide request must not show an empty strip either.
        if (eligible) strip.reserve() else strip.hideSuggestions()
    }


    private fun applyResult(
        slot: LanguageSlot,
        token: Any,
        suggestions: List<String>,
        kind: LookupKind,
    ) {
        // A GLIDE result is gated by the glide gate, not the suggestions setting: with suggestions
        // off the lift-commit must still land.
        if (!eligible && !(kind == LookupKind.GLIDE && glideEligible)) return
        // A result from the engine of a language the user has left may never repaint the strip on
        // its own. The session check below covers it too, but the state owner checks explicitly.
        //
        // Such a result may only fill cells the active language left empty, and only when this
        // controller asked for it: [applyCompanionResult], which never touches an active cell.
        if (slot !== activeSlot()) {
            applyCompanionResult(slot, token, suggestions, kind)
            return
        }
        // The arrival ends the round trip. The engine drops stale handoffs, so an active-slot
        // result here answers the newest request; ending before the currency guards keeps a
        // session-crossed answer from leaving its slice open.
        endLookupTrace()
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
        // Typo recovery and personal words can fill a strip whose exact dictionary pass was
        // empty; the learning rule needs only the empty exact pass.
        val exactMiss = usableEngine()?.exactMissPrefix()
        if (suggestions.isEmpty() ||
            (exactMiss != null && exactMiss == TatarWordUtils.normalizeForLookup(pendingPrefix))
        ) {
            runMachine.observeEmptyResult(pendingPrefix)
        }
        // When the separator-time autocorrect would fire on this word, the strip shows the coming
        // replacement instead of continuations, as in AOSP. The preview owns the whole strip: no
        // companion fill.
        val preview = computeAutocorrectPreview(
            autocorrectGate, pendingPrefix, suppressedPreviewWord, refusedCorrections,
        ) {
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
            // An empty strip displaces nothing of the active language, so the companion may fill
            // it from the first cell; the one case where its candidate leads.
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
     * NEXT_WORD counterpart of [applyPrefixResult]. No empty-result observation (NEXT_WORD fires on
     * an empty prefix, so there is no prefix growth) and no casing re-application: predictions are
     * shown and inserted exactly as stored.
     */
    private fun applyNextWordResult(suggestions: List<String>) {
        // When the context word maps to an emoji, the emoji takes the last cell: word predictions
        // keep their order in the front cells, the emoji never leads a strip that has words, and
        // only the lowest-ranked word yields to it.
        val emoji = emojiCandidate(pendingContextWord)
        if (suggestions.isEmpty()) {
            // The active language put no word on the strip: a later bigram attach may still
            // re-ask this context, so the emoji cell and any companion fill must not count as an
            // active-language answer.
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
     * A word separator has been pressed and is about to be committed: the last chance to correct
     * the word it finishes. Returns true when the trailing word was actually replaced.
     *
     * Called before the separator reaches the input logic on purpose. At this instant the editor is
     * in exactly the state an accepted suggestion needs — a trailing word, a collapsed cursor right
     * after it — so the replacement is the same single delete + commit, with the same re-checks, and
     * the separator afterwards travels the ordinary path untouched (auto-space, the double-space
     * gesture and the shift update all behave as they always did).
     *
     * Every condition fails towards not editing text:
     *  - the feature is on (and suggestions too: [eligible] carries that);
     *  - the user has not refused this occurrence's correction through the preview's typed-word
     *    cell (a one-shot refusal, consumed here);
     *  - the user has not undone a correction of this word earlier in the field session;
     *  - an engine is usable and the cursor is known;
     *  - the cursor is not inside a word, and the word is not in mixed case (no defined form);
     *  - the word is long enough ([AutocorrectPolicy.MIN_WORD_CODE_POINTS], on the normalized form);
     *  - a verdict exists and was computed for this word: a coalesced or unanswered lookup leaves
     *    an older verdict behind, which must not be applied to a different word;
     *  - the candidate is frequent enough ([AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY]).
     *
     * The last two are re-checked although the engine already applied them, so the rule does not
     * live on one side of the decision only.
     */
    fun maybeAutocorrectBeforeSeparator(separatorCodePoint: Int): Boolean {
        if (destroyed || !eligible) return false
        if (!autocorrectGate.isOn()) return false
        val activeEngine = usableEngine() ?: return false
        if (!editor.hasKnownCursor()) return false
        if (editor.hasLetterAfterCursor()) return false
        val word = editor.cachedWordBeforeCursor()
        if (word.isEmpty()) return false
        // The user refused this occurrence's correction by tapping the preview's typed-word cell.
        // One-shot: this separator consumes the refusal, so the same word typed later is a new
        // occurrence.
        if (word == suppressedPreviewWord) {
            suppressedPreviewWord = null
            return false
        }
        val casing = TatarWordUtils.classifyCasing(word)
        if (casing == TatarWordUtils.PrefixCasing.MIXED) return false
        val normalized = TatarWordUtils.normalizeForLookup(word)
        // The user undid this word's correction earlier in the field session.
        if (normalized in refusedCorrections) return false
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
        // Whatever the strip was showing described the word that no longer stands there.
        displayedPrefix = null
        displayedContextWord = null
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        revertWindow.arm(word, replacement, separatorCodePoint, sessionId)
        // ...but the boundary it establishes is trusted for pairs, as on the tap path: the cursor
        // is known to sit right after a real word and the separator, so the next cleanly typed
        // word may pair with the corrected one. Without this the pair machine would stay dirty
        // past the boundary (the run machine returns early when the word is unchanged).
        runMachine.trustPairBoundary()
        return true
    }

    /**
     * A backspace has been pressed. Returns true when it was consumed by undoing the replacement
     * made immediately before it, in which case no character is deleted; false leaves the key to the
     * ordinary backspace path.
     *
     * There is exactly one undo. The state is dropped before the editor is asked to do anything, so
     * a refused undo cannot be retried and the second backspace deletes a character like any other.
     */
    fun maybeRevertAutocorrect(): Boolean {
        val replacement = revertWindow.take() ?: return false
        if (destroyed || !eligible) return false
        if (replacement.sessionId != sessionId) return false
        if (!autocorrectGate.isOn()) return false
        if (!editor.hasKnownCursor()) return false
        runMachine.markRunDirty()
        val reverted = editor.revertTypedWord(
            replacement.insertedForm, replacement.separator, replacement.typedForm,
        )
        // An undone correction is not repeated for that word in this field session.
        if (reverted) refuseCorrection(TatarWordUtils.normalizeForLookup(replacement.typedForm))
        return reverted
    }

    private fun refuseCorrection(normalized: String) {
        refusedCorrections.remove(normalized)
        refusedCorrections.add(normalized)
        if (refusedCorrections.size > MAX_REFUSED_CORRECTIONS) {
            refusedCorrections.remove(refusedCorrections.first())
        }
    }

    /**
     * Closes the undo windows. Called at every event that makes an undo impossible: a new field,
     * a subtype change, a selection change, a tap, a setting change. A refused preview is dropped
     * too, since each of these also ends the word occurrence it was scoped to.
     */
    private fun clearRevertState() {
        revertWindow.clear()
        suppressedPreviewWord = null
        // The lift-commit's whole-word undo dies at the same boundaries as the autocorrect undo.
        glideCommittedWord = null
        glideCommitPrependedSpace = false
    }

    private fun onTap(suggestion: String) {
        // A tap on the typed-word cell of a preview strip refuses the coming correction for this
        // occurrence of the word. It is not an accepted suggestion: nothing is committed (the run
        // stays clean, the user typed every letter), the undo window is untouched (it closed
        // keystrokes ago), and the strip falls back to the word's ordinary suggestions at once.
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
        clearRevertState()
        if (displayedSessionId != sessionId) {
            // Nothing bound to the current session is displayed (e.g. the text changed and the old
            // candidates were invalidated): a tap must be a no-op and must never commit.
            return
        }
        // At most one of the three bindings is non-null, and the kind is read off what is bound,
        // not off the tapped string. The branches below are exclusive: even if the invariant
        // broke, a tap would still commit at most once.
        val prefix = displayedPrefix
        if (prefix != null) {
            // Commit against the DISPLAYED prefix, not the mutable pendingPrefix. The editor's own
            // stale-tap guard (re-reads live cache, requires collapsed selection and live trailing
            // word == expectedPrefix, deletes by code points) is the second line of defense.
            if (editor.commitSuggestion(prefix, suggestion)) {
                // The field is still eligible after a commit; clear the words but keep the reserved
                // strip so accepting a suggestion does not resize the keyboard.
                displayedPrefix = null
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                // The accepted cell counts as a use: for a saved personal word the sink bumps its
                // usage counter (in memory; the file is written at the session boundary).
                runMachine.noteAcceptedSuggestion(suggestion)
                // The tapped word itself never counts (markRunDirty above), but the boundary after
                // it (the committed word and its auto-space) is trusted for pairs: the next cleanly
                // typed word may pair with it.
                runMachine.trustPairBoundary()
                // A tap-commit never reaches onTextChanged() (no InputTransaction wraps it), and
                // onCursorMoveSettled skips when requestSessionId == sessionId, so the follow-up
                // lookup must be issued here or the strip stays empty until the next keystroke. The
                // text cache is current: with the auto-space this takes the NEXT_WORD path for the
                // committed word; without it the committed word is the new trailing prefix. The
                // session is not bumped: the commit is part of this session's text.
                requestCurrentPrefix()
            }
            return
        }
        val context = displayedContextWord
        val glideAlternativesFor = displayedGlideAlternativesFor
        if (context != null) {
            // Same second line of defense as the PREFIX path, through the predicted-word commit:
            // the editor re-derives the live context word and refuses a stale tap itself.
            if (editor.commitPredictedWord(context, suggestion)) {
                displayedContextWord = null
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                // An accepted emoji cell is an emoji insertion like a panel or search pick, so
                // the recent-emoji list learns it. Word cells never reach the sink, and a refused
                // commit never gets here.
                if (isEmojiCell(suggestion)) {
                    emojiInsertionSink?.onEmojiInserted(suggestion)
                    // The same pick teaches learned emoji: always as an observation, and also as a
                    // use when the tapped cell is what the learned source itself offered for this
                    // context. The learned lookup runs at tap time rather than being remembered
                    // from the fill, so a stale answer cannot bump the wrong entry. A
                    // sentence-start strip has no word to learn against.
                    val personalSink = personalEmojiSink
                    if (personalSink != null && context.isNotEmpty()) {
                        personalSink.noteObservation(context, suggestion)
                        val offered =
                            personalEmojiSource?.emojiFor(TatarWordUtils.normalizeForLookup(context))
                        if (offered == suggestion) {
                            personalSink.noteUse(context, suggestion)
                        }
                    }
                }
                // See [CleanRunMachine.noteAcceptedPrediction]; a sentence-start strip (empty
                // context) is never a pair.
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
            // A tap on a glide alternative replaces the just-committed glide word in the editor,
            // which re-checks that the committed word still stands right before the cursor. The
            // chain space stays as committed. The run stays dirty (markRunDirty above), the
            // alternative counts as the accepted suggestion, the trusted pair boundary moves to
            // it, and the strip refreshes for it.
            if (editor.replaceGlideLiftedWord(glideAlternativesFor, suggestion,
                    glideCommitPrependedSpace)) {
                // The undo window tracks the text: a backspace now deletes the alternative whole
                // (with the same chain-space treatment).
                glideCommittedWord = suggestion
                displayedGlideAlternativesFor = null
                bandBaseCells = emptyList()
                clearCompanionRequest()
                strip.reserve()
                runMachine.noteAcceptedSuggestion(suggestion)
                runMachine.trustPairBoundary()
                requestCurrentPrefix()
            }
        }
    }

    /**
     * One backspace right after a glide lift-commit deletes the whole committed word instead of
     * one character, including the chain space when the commit prepended it, so undoing the second
     * glide of a chain returns to the first word's state. Returns true when it did.
     *
     * The window holds one word and closes when the text changes for any other reason, like the
     * autocorrect undo: the state is dropped before the editor is asked, so a refused undo cannot
     * be retried. The editor re-checks that the committed word still stands before the cursor.
     */
    fun maybeUndoGlideCommit(): Boolean {
        val word = glideCommittedWord ?: return false
        val prependedSpace = glideCommitPrependedSpace
        glideCommittedWord = null
        glideCommitPrependedSpace = false
        // The alternatives (if any) described the word that no longer stands there.
        displayedGlideAlternativesFor = null
        bandBaseCells = emptyList()
        clearCompanionRequest()
        // The undo belongs to glide typing, so it follows the glide gate and works with
        // suggestions off.
        if (destroyed || !glideEligible) return false
        if (!editor.hasKnownCursor()) return false
        return editor.deleteGlideLiftedWord(word, prependedSpace)
    }

    companion object {
        /**
         * The language a call that names none means.
         *
         * The boolean-only overloads (used by most tests) mean Tatar. Reads
         * `PersonalSubtypes.TATAR_RU`, the same constant as `LatinIME.isSuggestionsEligible()`, so
         * the two cannot drift apart.
         */
        internal const val DEFAULT_LANGUAGE = PersonalSubtypes.TATAR_RU
        private const val DESTROY_TIMEOUT_MS = 60L

        /** How many undone corrections one field session remembers. */
        internal const val MAX_REFUSED_CORRECTIONS = 16

        // Sentinel for "no request is outstanding". [sessionId] starts at 0 and only ever grows,
        // so this can never be mistaken for a live generation.
        private const val NO_SESSION = -1L

        // Sentinel for "no lookup slice is open"; real cookies are serials starting at 1.
        private const val NO_TRACE_COOKIE = -1

        /**
         * The [displayedContextWord]/[pendingContextWord] binding of a sentence-start strip: the
         * empty string, which the NEXT_WORD path never binds, so it means "a sentence start". See
         * [requestSentenceStart].
         */
        private const val SENTENCE_START_CONTEXT = ""
    }
}
