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

package rkr.simplekeyboard.inputmethod.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Debug
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.latin.InputView
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripView

/**
 * O3 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): the fail-closed allocation gate for the board
 * draw loop — the project's hard budget is ZERO allocations per frame in the draw path, and until
 * now nothing pinned it.
 *
 * Two probes, both against the LIVE IME (the debug IME is enabled and made default by the host
 * before the run, exactly like GlideUiDeviceTest):
 *
 *  1. [testBoardAndStripDrawLoopsAllocateNothing] — the gate proper. The press → invalidate →
 *     redraw sequence a real tap causes is replayed SYNCHRONOUSLY on the live views, on the main
 *     thread (the thread the IME draws on), under per-thread allocation counting
 *     (Debug.startAllocCounting + getThreadAllocCount). The synchronous window is hermetic: while
 *     it runs, no other main-thread work (suggestion publications, framework callbacks) can
 *     interleave, so a nonzero delta IS a draw-path allocation. The windows: a control (the
 *     press/invalidate choreography without draws), a blit window (the per-frame shell), a
 *     same-state redraw window, the full board cycle (press+release per key + full redraw), and
 *     strip redraws of a live four-cell band. Negative-test ritual (plan O3 verification): a
 *     deliberate `Rect()` planted in KeyboardView.onDraw must fail this test; the plant is then
 *     reverted.
 *
 *     Provenance (POCO C71, HyperOS, 2026-09-29): same-state redraws, blits and the strip
 *     measured ZERO and are asserted at zero. The board cycle measured 18 over 9 draws; the
 *     step breakdown (testDrawAllocStepBreakdown) attributes it exactly: 2 allocations per
 *     StateListDrawable STATE CHANGE on the shared key background (a pressed key draws with
 *     state_pressed, then the released redraw toggles back), zero for same-state redraws, and
 *     the isolation step reproduces the same 2-per-toggle on a bare drawable with no view
 *     involved — the allocation is the platform's drawable state resolution, provoked by a
 *     semantically required state change. The board window is therefore bounded at
 *     BOARD_TOGGLE_FLOOR_BOUND (documented per-measurement), while every per-frame path that our
 *     code controls stays asserted at exactly zero.
 *
 *  2. [testTapBurstsStayWithinAllocationBudget] — the integration picture. Real
 *     sendPointerSync tap bursts on the try-it field (a letters burst with the suggestion strip
 *     live, then a delete burst on the empty field as the board-only contrast), each preceded by
 *     an identical warmup burst so one-time caches (preview popup, ellipsize widths, MotionEvent
 *     pools, JIT) are not mistaken for steady-state cost. The main-thread delta here is the WHOLE
 *     per-tap pipeline — input dispatch, InputLogic, composing text, the EditText, the strip
 *     republication — which is genuinely non-zero and belongs to the platform and the input path,
 *     not the draw loop; it is therefore bounded (calibrated on the POCO C71, see the constants)
 *     rather than zeroed, and the per-burst numbers are logged as RESULT lines.
 *
 * Same JUnit3/legacy-runner shape as the other device harnesses — resolves offline, never
 * packaged into the release APK.
 */
class DrawAllocInstrumentationTest : InstrumentationTestCase() {

    private var savedSuggestions = false
    private var startedActivity: Activity? = null

    // Written inside main-thread blocks, read back on the instrumentation thread after
    // runOnMainSync returns (its synchronized round-trip is the happens-before edge).
    private var keyboardView: MainKeyboardView? = null
    private var stripView: SuggestionStripView? = null
    private var probeKeys = emptyArray<Key>()
    private var boardCanvas: Canvas? = null
    private var stripCanvas: Canvas? = null
    private var rawSelector: Drawable? = null
    private var lastWindowThreadDelta = -1L
    private var lastWindowGlobalDelta = -1L
    private var mainThreadFailure: Throwable? = null

    // One reused snapshot runnable: no per-snapshot lambda allocation on the instrumentation
    // thread inside the measured burst windows (keeps the global counter meaningful).
    private var mainThreadAllocSnapshot = -1L
    private val mainThreadAllocReader = Runnable {
        mainThreadAllocSnapshot = Debug.getThreadAllocCount().toLong()
    }

    override fun setUp() {
        // Every tap coordinate below is calibrated for the POCO C71's 720x1640 px display;
        // on any other geometry the probes miss and the run wedges, so fail fast here.
        // (Same guard as GlideUiDeviceTest.)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        if (metrics.widthPixels != 720 || metrics.heightPixels != 1640) {
            fail(
                "the tap coordinates are calibrated for a 720x1640 px display (POCO C71); " +
                    "the actual display is ${metrics.widthPixels}x${metrics.heightPixels} px",
            )
        }
        val prefs = PreferenceManagerCompat.getDeviceSharedPreferences(instrumentation.targetContext)
        savedSuggestions = prefs.getBoolean("pref_tatar_suggestions", false)
        prefs.edit().putBoolean("pref_tatar_suggestions", true).commit()
    }

    override fun tearDown() {
        // Counting must never leak into the next harness: while armed it costs a per-allocation
        // bookkeeping bump for the whole VM.
        Debug.stopAllocCounting()
        startedActivity?.finish()
        val prefs = PreferenceManagerCompat.getDeviceSharedPreferences(instrumentation.targetContext)
        prefs.edit().putBoolean("pref_tatar_suggestions", savedSuggestions).commit()
    }

    /**
     * The gate: blits, same-state key redraws and strip redraws must allocate exactly ZERO on the
     * drawing thread; the press/release board cycle stays within the documented platform floor
     * (StateListDrawable state toggles — see the class KDoc).
     */
    fun testBoardAndStripDrawLoopsAllocateNothing() {
        prepareField()
        setupLiveViewsAndWarmUp()
        val view = keyboardView!!
        val strip = stripView!!
        val keys = probeKeys
        val board = boardCanvas!!
        val stripCanvas = stripCanvas!!

        // Control: the press/invalidate choreography WITHOUT a single draw. Whatever the
        // framework's invalidate path itself charges (traversal scheduling, message pool misses)
        // lands here, so a red draw window can be attributed: draw window >> control window means
        // the allocation is in OUR draw code; draw window == control window means framework floor.
        measureWindow("control", 0) {
            runInvalidateOnlySequence(view, keys, strip)
        }
        val controlThread = lastWindowThreadDelta
        val controlGlobal = lastWindowGlobalDelta
        // The control left presses/invalidation pending on purpose; flush them uncounted, then
        // settle the shared background drawables back to the released state — otherwise the
        // redraw window's first draw would pay the one toggle left over from the flush's full
        // redraw (a sticky/action key's state), which belongs to the board window's floor.
        onMain {
            runBoardDrawSequence(view, keys, board)
            runStripDrawSequence(strip, stripCanvas)
            for (key in keys) {
                view.invalidateKey(key)
                view.onDraw(board)
            }
        }

        // Blit: onDraw with nothing invalidated — the per-frame shell (offscreen-buffer blit plus
        // the glide-trail check) without a single key redraw. Strict zero.
        measureWindow("blit", BLIT_DRAWS_PER_WINDOW) {
            repeat(BLIT_DRAWS_PER_WINDOW) { view.onDraw(board) }
        }
        val blitThread = lastWindowThreadDelta
        val blitGlobal = lastWindowGlobalDelta

        // Redraw: same-state partial key redraws — the steady-state frame a key press causes once
        // the background drawable already carries the right state. Strict zero: this is the "zero
        // allocations in the draw loop" budget proper.
        measureWindow("redraw", REDRAW_DRAWS_PER_WINDOW) {
            for (key in keys) {
                view.invalidateKey(key)
                view.onDraw(board)
            }
            for (key in keys) {
                view.invalidateKey(key)
                view.onDraw(board)
            }
        }
        val redrawThread = lastWindowThreadDelta
        val redrawGlobal = lastWindowGlobalDelta

        // Board: the full press/release cycle per key plus a full-board redraw. This window
        // INCLUDES the press-state transitions of the shared key background drawable, and on this
        // platform each StateListDrawable state change costs the framework two allocations (see
        // the raw-selector-toggle step of testDrawAllocStepBreakdown — a bare drawable with no
        // view involved pays the same 2 per toggle). Bounded at the documented floor, not zeroed:
        // the floor is outside our code.
        measureWindow("board", BOARD_DRAWS_PER_WINDOW) {
            runBoardDrawSequence(view, keys, board)
        }
        val boardThread = lastWindowThreadDelta
        val boardGlobal = lastWindowGlobalDelta

        measureWindow("strip", STRIP_DRAWS_PER_WINDOW) {
            runStripDrawSequence(strip, stripCanvas)
        }
        val stripThread = lastWindowThreadDelta
        val stripGlobal = lastWindowGlobalDelta

        Log.i(
            TAG,
            "RESULT|drawalloc|gate keys=${keys.size} boardDraws=$BOARD_DRAWS_PER_WINDOW " +
                "stripDraws=$STRIP_DRAWS_PER_WINDOW control=$controlThread blit=$blitThread " +
                "redraw=$redrawThread board=$boardThread strip=$stripThread " +
                "boardFloorBound=$BOARD_TOGGLE_FLOOR_BOUND controlGlobal=$controlGlobal " +
                "blitGlobal=$blitGlobal redrawGlobal=$redrawGlobal boardGlobal=$boardGlobal " +
                "stripGlobal=$stripGlobal " +
                "verdict=${if (blitThread == 0L && redrawThread == 0L && stripThread == 0L &&
                    boardThread <= BOARD_TOGGLE_FLOOR_BOUND) "PASS" else "FAIL"}",
        )
        assertEquals(
            "the per-frame draw shell must stay allocation-free: $BLIT_DRAWS_PER_WINDOW " +
                "blit-only draws allocated $blitThread objects on the drawing thread",
            0L, blitThread,
        )
        assertEquals(
            "same-state key redraws must stay allocation-free: $REDRAW_DRAWS_PER_WINDOW partial " +
                "redraws allocated $redrawThread objects on the drawing thread",
            0L, redrawThread,
        )
        assertEquals(
            "the suggestion strip draw loop must stay allocation-free: " +
                "$STRIP_DRAWS_PER_WINDOW redraws of a live four-cell band allocated " +
                "$stripThread objects on the drawing thread",
            0L, stripThread,
        )
        assertTrue(
            "the board press/release draw cycle allocated $boardThread objects on the drawing " +
                "thread over $BOARD_DRAWS_PER_WINDOW draws; the documented platform floor is " +
                "2 allocations per StateListDrawable state toggle (8 toggles per window = 16) " +
                "plus the full redraw's functional-key states (~2), measured 18, bounded at " +
                "$BOARD_TOGGLE_FLOOR_BOUND — anything above it is ours",
            boardThread <= BOARD_TOGGLE_FLOOR_BOUND,
        )
    }

    /**
     * The attribution tool for a red gate: one continuous counting session on the main thread.
     * The aggregate steps measure repeated ops (each with its own in-window warmup pass, so a
     * one-time cost lands in the warmup and the reported delta is the per-call cost); the micro
     * section then snapshots INDIVIDUAL draws of one press/release cycle per key, which splits
     * "per draw of a pressed key" from "per background-state change on the shared key drawable".
     * Log-only — when the gate goes red, these RESULT lines name the allocating call.
     */
    fun testDrawAllocStepBreakdown() {
        prepareField()
        setupLiveViewsAndWarmUp()
        val view = keyboardView!!
        val strip = stripView!!
        val keys = probeKeys
        val board = boardCanvas!!
        val stripCanvas = stripCanvas!!
        val selector = rawSelector!!
        onMain {
            val labels = ArrayList<String>()
            val deltas = ArrayList<Int>()
            fun record(label: String, delta: Int) {
                labels.add(label)
                deltas.add(delta)
            }
            fun step(label: String, n: Int, op: () -> Unit) {
                var i = 0
                while (i < n) { op(); i++ } // warmup pass: absorbs one-time costs
                val t0 = Debug.getThreadAllocCount()
                i = 0
                while (i < n) { op(); i++ } // measured pass
                record(label, Debug.getThreadAllocCount() - t0)
            }
            fun micro(label: String, op: () -> Unit) {
                val t0 = Debug.getThreadAllocCount()
                op()
                record(label, Debug.getThreadAllocCount() - t0)
            }
            Debug.startAllocCounting()
            try {
                step("press-release", 8) { keyCyclePressRelease(keys[0]) }
                step("partial-redraw-unpressed", 8) { view.invalidateKey(keys[0]); view.onDraw(board) }
                step("partial-redraw-pressed", 4) { keyCycleDraw(view, keys[0], board) }
                step("full-redraw", 3) { view.invalidateAllKeys(); view.onDraw(board) }
                step("board-sequence", 2) { runBoardDrawSequence(view, keys, board) }
                step("control-sequence", 2) { runInvalidateOnlySequence(view, keys, strip) }
                step("strip-invalidate-draw", 4) { strip.invalidate(); strip.draw(stripCanvas) }
                // Isolation: the bare key-background selector with no view involved. If a bare
                // StateListDrawable state toggle costs the same 2 allocations the pressed-key
                // draw pays, the cost is the platform's drawable state resolution, not our code.
                step("raw-selector-toggle", 8) {
                    selector.setState(PRESSED_STATE)
                    selector.setState(RELEASED_STATE)
                }
                step("raw-selector-same-state", 8) { selector.setState(RELEASED_STATE) }

                // Micro: individual draws of one press/release cycle, per key. The "again" draws
                // repeat with the SAME press state already drawn — they isolate the cost of the
                // state CHANGE on the shared background drawable from the cost of the draw itself.
                for (k in keys.indices) {
                    val key = keys[k]
                    micro("key$k-press") { key.onPressed() }
                    micro("key$k-draw-pressed") { view.invalidateKey(key); view.onDraw(board) }
                    micro("key$k-draw-pressed-again") { view.invalidateKey(key); view.onDraw(board) }
                    micro("key$k-release") { key.onReleased() }
                    micro("key$k-draw-released") { view.invalidateKey(key); view.onDraw(board) }
                    micro("key$k-draw-released-again") { view.invalidateKey(key); view.onDraw(board) }
                }
                micro("full-redraw-a") { view.invalidateAllKeys(); view.onDraw(board) }
                micro("full-redraw-b") { view.invalidateAllKeys(); view.onDraw(board) }
            } finally {
                Debug.stopAllocCounting()
            }
            var i = 0
            while (i < labels.size) {
                Log.i(TAG, "RESULT|drawalloc|step=${labels[i]} delta=${deltas[i]}")
                i++
            }
        }
    }

    /** One press/release pair with no drawing: the breakdown's zero-cost reference step. */
    private fun keyCyclePressRelease(key: Key) {
        key.onPressed()
        key.onReleased()
    }

    /** One full press/release draw cycle for a single key: the per-tap redraw work. */
    private fun keyCycleDraw(view: MainKeyboardView, key: Key, canvas: Canvas) {
        key.onPressed()
        view.invalidateKey(key)
        view.onDraw(canvas)
        key.onReleased()
        view.invalidateKey(key)
        view.onDraw(canvas)
    }

    /**
     * The scripted-burst picture: real taps through the live IME, warmup burst first, then the
     * measured identical burst — one with the suggestion strip live (letters), one board-only
     * (delete on the empty field). Bounded, not zeroed: the main-thread delta here is the whole
     * per-tap pipeline, not the draw loop alone (see the class KDoc).
     */
    fun testTapBurstsStayWithinAllocationBudget() {
        val field = prepareField()

        // Warmup burst, identical to the measured one.
        driveTapBurst(LETTER_TAPS)
        Thread.sleep(SETTLE_MS)
        field.text.clear()
        Thread.sleep(SETTLE_MS)

        Debug.startAllocCounting()
        val globalA0 = Debug.getGlobalAllocCount()
        val mainA0 = snapshotMainThreadAllocs()
        driveTapBurst(LETTER_TAPS)
        Thread.sleep(SETTLE_MS)
        val mainA1 = snapshotMainThreadAllocs()
        val globalA1 = Debug.getGlobalAllocCount()
        Debug.stopAllocCounting()
        val lettersThread = mainA1 - mainA0
        val lettersGlobal = globalA1 - globalA0
        Log.i(
            TAG,
            "RESULT|drawalloc|burst=letters taps=${LETTER_TAPS.size / 2} strip=live " +
                "threadDelta=$lettersThread globalDelta=$lettersGlobal " +
                "budget=$MAX_MAIN_ALLOCS_LETTER_BURST " +
                "verdict=${if (lettersThread <= MAX_MAIN_ALLOCS_LETTER_BURST) "PASS" else "FAIL"}",
        )
        field.text.clear()
        Thread.sleep(SETTLE_MS)

        Debug.startAllocCounting()
        val globalB0 = Debug.getGlobalAllocCount()
        val mainB0 = snapshotMainThreadAllocs()
        driveTapBurst(DELETE_TAPS)
        Thread.sleep(SETTLE_MS)
        val mainB1 = snapshotMainThreadAllocs()
        val globalB1 = Debug.getGlobalAllocCount()
        Debug.stopAllocCounting()
        val deleteThread = mainB1 - mainB0
        val deleteGlobal = globalB1 - globalB0
        Log.i(
            TAG,
            "RESULT|drawalloc|burst=delete taps=${DELETE_TAPS.size / 2} strip=empty " +
                "threadDelta=$deleteThread globalDelta=$deleteGlobal " +
                "budget=$MAX_MAIN_ALLOCS_DELETE_BURST " +
                "verdict=${if (deleteThread <= MAX_MAIN_ALLOCS_DELETE_BURST) "PASS" else "FAIL"}",
        )

        assertTrue(
            "the letters tap burst allocated $lettersThread objects on the main thread over " +
                "${LETTER_TAPS.size / 2} taps (budget $MAX_MAIN_ALLOCS_LETTER_BURST; see KDoc — " +
                "the whole per-tap pipeline counts here, the draw loop itself is pinned at zero " +
                "by testBoardAndStripDrawLoopsAllocateNothing)",
            lettersThread <= MAX_MAIN_ALLOCS_LETTER_BURST,
        )
        assertTrue(
            "the delete tap burst allocated $deleteThread objects on the main thread over " +
                "${DELETE_TAPS.size / 2} taps (budget $MAX_MAIN_ALLOCS_DELETE_BURST)",
            deleteThread <= MAX_MAIN_ALLOCS_DELETE_BURST,
        )
    }

    /**
     * Runs [body] on the main thread between allocation-counter snapshots. The counting window
     * is synchronous, so nothing else on the main thread can leak into the delta.
     */
    private fun measureWindow(name: String, draws: Int, body: () -> Unit) {
        onMain {
            Debug.startAllocCounting()
            try {
                val thread0 = Debug.getThreadAllocCount()
                val global0 = Debug.getGlobalAllocCount()
                body()
                val thread1 = Debug.getThreadAllocCount()
                val global1 = Debug.getGlobalAllocCount()
                lastWindowThreadDelta = (thread1 - thread0).toLong()
                lastWindowGlobalDelta = (global1 - global0).toLong()
            } finally {
                Debug.stopAllocCounting()
            }
        }
        Log.i(
            TAG,
            "RESULT|drawalloc|window=$name draws=$draws " +
                "threadDelta=$lastWindowThreadDelta globalDelta=$lastWindowGlobalDelta",
        )
    }

    /** Resolves the live views, fixes known strip content, and warms every cache the windows touch. */
    private fun setupLiveViewsAndWarmUp() {
        onMain {
            val view = requireNotNull(KeyboardSwitcher.getInstance().mainKeyboardView) {
                "the live keyboard view must exist while the IME is up"
            }
            val inputView = requireNotNull(inputViewOf(view)) {
                "the keyboard view must sit inside the InputView"
            }
            // Four live cells, one emphasized: the fullest band the strip ever paints. Goes
            // through the production entry point, which also inflates the strip stub on first
            // use; the paired setEmphasis runs the (one-off, unmeasured) ellipsize rebuild.
            val strip = requireNotNull(
                inputView.showSuggestionStrip(
                    STRIP_WORDS[0], STRIP_WORDS[1], STRIP_WORDS[2], STRIP_WORDS[3],
                ),
            ) { "the suggestion strip must inflate" }
            strip.setEmphasis(0)
            keyboardView = view
            stripView = strip
            probeKeys = findLetterKeys(view, PROBED_LETTERS)
            boardCanvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
            stripCanvas = Canvas(Bitmap.createBitmap(strip.width, strip.height, Bitmap.Config.ARGB_8888))
            // The bare key-background selector for the framework-attribution isolation step.
            rawSelector = requireNotNull(view.context.getDrawable(R.drawable.ios_key_normal)) {
                "the key background selector must resolve"
            }
            // Warmup, uncounted: the exact measured sequences twice. The first draws fill the
            // per-key draw-params cache (KeyboardView.mKeyDrawParamsCache), the typeface metrics
            // caches and the framework's message/traversal pools — none of that is steady-state
            // draw cost.
            repeat(2) {
                runBoardDrawSequence(view, probeKeys, boardCanvas!!)
                runStripDrawSequence(strip, stripCanvas!!)
            }
            // Warm the counting machinery itself once, so any one-time ART bookkeeping of the
            // first startAllocCounting lands here and not in a measured window.
            Debug.startAllocCounting()
            Debug.stopAllocCounting()
        }
    }

    /**
     * The exact redraw work one tap causes on the board, per probed key — press → invalidate →
     * draw, release → invalidate → draw — plus one full-board redraw. The draw call is the
     * view's own onDraw: a plain invalidate() marks the view dirty-RECT only, and View.draw()
     * would then skip onDraw entirely (PFLAG_DIRTY_OPAQUE) — calling onDraw directly keeps the
     * measured path exactly the one a frame runs, with the framework's recording canvas replaced
     * by a bitmap canvas (production draws into a bitmap canvas the same way, see the
     * offscreen-buffer note in KeyboardView.onDraw).
     */
    private fun runBoardDrawSequence(view: MainKeyboardView, keys: Array<Key>, canvas: Canvas) {
        for (key in keys) {
            key.onPressed()
            view.invalidateKey(key)
            view.onDraw(canvas)
            key.onReleased()
            view.invalidateKey(key)
            view.onDraw(canvas)
        }
        view.invalidateAllKeys()
        view.onDraw(canvas)
    }

    /** Redraws the live suggestion band, as a suggestion republication does. */
    private fun runStripDrawSequence(strip: SuggestionStripView, canvas: Canvas) {
        var i = 0
        while (i < STRIP_DRAWS_PER_WINDOW) {
            strip.invalidate()
            strip.draw(canvas)
            i++
        }
    }

    /** The control window: every non-draw call of the board/strip sequences, no draw at all. */
    private fun runInvalidateOnlySequence(
        view: MainKeyboardView,
        keys: Array<Key>,
        strip: SuggestionStripView,
    ) {
        for (key in keys) {
            key.onPressed()
            view.invalidateKey(key)
            key.onReleased()
            view.invalidateKey(key)
        }
        view.invalidateAllKeys()
        var i = 0
        while (i < STRIP_DRAWS_PER_WINDOW) {
            strip.invalidate()
            i++
        }
    }

    private fun findLetterKeys(view: MainKeyboardView, letters: CharArray): Array<Key> {
        val keyboard = requireNotNull(view.keyboard) { "the live keyboard view has no keyboard" }
        val sorted = keyboard.sortedKeys
        return Array(letters.size) { i ->
            val wanted = Character.toLowerCase(letters[i].code)
            var found: Key? = null
            for (j in sorted.indices) {
                val key = sorted[j]
                if (Character.toLowerCase(key.code) == wanted) {
                    found = key
                    break
                }
            }
            found ?: throw IllegalStateException("no key for '${letters[i]}' on the live keyboard")
        }
    }

    private fun inputViewOf(view: View): InputView? {
        var node = view.parent
        while (node != null) {
            if (node is InputView) return node
            node = node.parent
        }
        return null
    }

    /** Main-thread alloc counter read; the reused runnable keeps the snapshot itself alloc-free. */
    private fun snapshotMainThreadAllocs(): Long {
        mainThreadAllocSnapshot = -1L
        instrumentation.runOnMainSync(mainThreadAllocReader)
        return mainThreadAllocSnapshot
    }

    /**
     * Everything meant for the main thread goes through here: runOnMainSync lets a failure escape
     * on the MAIN thread (it would crash the IME process); catch it and rethrow it on the
     * instrumentation thread, where JUnit reports it as an ordinary test failure.
     */
    private fun onMain(block: () -> Unit) {
        mainThreadFailure = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                mainThreadFailure = t
            }
        }
        mainThreadFailure?.let { throw it }
    }

    /** The shared bring-up: the activity, the focused try-it field, the live engine. */
    private fun prepareField(): EditText {
        val instrumentation = getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent()
                .setClassName(context, "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        startedActivity = activity // tearDown finishes it, so the next start creates a fresh one
        val field = activity.findViewById<EditText>(R.id.setup_test_field)
        assertNotNull("the try-it field must exist", field)
        // Focus the field with ONE tap, then wait passively for the IME window.
        val fieldLocation = IntArray(2)
        field.getLocationOnScreen(fieldLocation)
        tap(fieldLocation[0] + field.width / 2f, fieldLocation[1] + field.height / 2f)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var immUp = false
        for (attempt in 0 until 20) {
            Thread.sleep(500)
            if (imm.isAcceptingText) {
                immUp = true
                break
            }
        }
        assertTrue("the IME never accepted text (keyboard never came up)", immUp)
        Thread.sleep(3000)
        // Close the loop on the process/window race: a probe letter must land in the field before
        // anything is measured (a stale keyboard window from a previous run would eat it).
        var probes = 0
        while (probes < 10) {
            field.getLocationOnScreen(fieldLocation)
            tap(fieldLocation[0] + field.width / 2f, fieldLocation[1] + field.height / 2f)
            Thread.sleep(700)
            tap(PROBE_TAP_X, PROBE_TAP_Y) // с
            Thread.sleep(700)
            if (field.text.toString().endsWith("с")) break
            field.text.clear()
            probes++
            Thread.sleep(1000)
        }
        assertTrue("the keyboard never processed a probe tap", field.text.toString().endsWith("с"))
        field.text.clear()
        Thread.sleep(500)
        // The engine publishes seconds after the first field focus on this class of device;
        // wait it out so the strip is genuinely live for the bursts (same wait GlideUiDeviceTest
        // documents).
        Thread.sleep(9000)
        return field
    }

    /**
     * A burst of real taps. Flat coordinate array and no lambdas on purpose: the loop itself must
     * not jitter the instrumentation thread's own allocation count (the global counter sees it).
     */
    private fun driveTapBurst(taps: FloatArray) {
        var i = 0
        while (i < taps.size) {
            tap(taps[i], taps[i + 1])
            Thread.sleep(TAP_GAP_MS)
            i += 2
        }
    }

    private fun tap(x: Float, y: Float) {
        inject(MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(TOUCH_MS)
        inject(MotionEvent.ACTION_UP, x, y)
    }

    private fun inject(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TAG = "DrawAlloc"

        // Screen coordinates of the live 720x1640 layout (calibrated from the device screencap,
        // 2026-09-24 — the same grid GlideUiDeviceTest uses): с ә л ә м, and the delete key.
        private const val PROBE_TAP_X = 234f
        private const val PROBE_TAP_Y = 1396f
        private val LETTER_TAPS = floatArrayOf(
            234f, 1396f, // с
            60f, 1110f, // ә
            491f, 1301f, // л
            60f, 1110f, // ә
            297f, 1396f, // м
        )
        private val DELETE_TAPS = floatArrayOf(
            663f, 1396f, 663f, 1396f, 663f, 1396f, 663f, 1396f, 663f, 1396f,
        )
        private val PROBED_LETTERS = charArrayOf('с', 'ә', 'л', 'м')
        private val STRIP_WORDS = arrayOf("сәләм", "сәләмнән", "сәләмле", "сәләмгә")

        // Per probed key: press draw + release draw; plus the full-board redraw.
        private const val BOARD_DRAWS_PER_WINDOW = 4 * 2 + 1
        private const val STRIP_DRAWS_PER_WINDOW = 4
        // Same-state partial redraws: two passes over the probed keys, all released.
        private const val REDRAW_DRAWS_PER_WINDOW = 4 * 2
        // The blit window draws the same number of frames as the board window, so a regression
        // in the per-frame shell is directly comparable against the board number.
        private const val BLIT_DRAWS_PER_WINDOW = BOARD_DRAWS_PER_WINDOW

        // The board window's documented platform floor (POCO C71, HyperOS, 2026-09-29): every
        // press/release cycle toggles the SHARED key background StateListDrawable twice, and on
        // this platform a state change costs the framework 2 allocations (isolated in
        // testDrawAllocStepBreakdown: a bare selector toggles at the same 2 with no view
        // involved) — 8 toggles = 16, plus the full redraw's functional/sticky key states ≈ 2,
        // measured 18. Bounded at 24: any NEW allocation in our draw path (e.g. one object per
        // draw = +9) trips it. Same-state redraws, blits and the strip stay asserted at ZERO.
        private const val BOARD_TOGGLE_FLOOR_BOUND = 24L

        private val PRESSED_STATE = intArrayOf(android.R.attr.state_pressed)
        private val RELEASED_STATE = intArrayOf()

        private const val TOUCH_MS = 60L
        private const val TAP_GAP_MS = 280L
        private const val SETTLE_MS = 1200L

        // Whole-pipeline per-burst bounds for testTapBurstsStayWithinAllocationBudget (the draw
        // loop alone is pinned at ZERO by the gate test). Calibrated on the POCO C71, 2026-09-29:
        // the letters burst measured 2148 main-thread allocations over 5 taps (input dispatch,
        // composing text, suggestion publication, the EditText's growth — the platform and
        // input-path floor), the delete burst on the empty field 1565. Bounded at roughly twice
        // the measurement so a structural regression (a per-tap bitmap, a leaked listener) trips
        // the gate while framework jitter cannot.
        private const val MAX_MAIN_ALLOCS_LETTER_BURST = 4000L
        private const val MAX_MAIN_ALLOCS_DELETE_BURST = 3000L
    }
}
