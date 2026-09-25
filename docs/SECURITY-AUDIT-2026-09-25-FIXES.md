# SECURITY-AUDIT-2026-09-25-FIXES — the input-robustness half of the fix wave

Status: done 2026-09-25. Companion to `docs/SECURITY-AUDIT-2026-09-25.md` (the audit itself,
written by the parallel agent). This report covers the input-robustness findings — the editor
connection (`RichInputConnection`), the batch-edit call sites (`InputLogic`), the touch layer
(`PointerTracker`/`PointerTrackerQueue`), and the three Kotlin data classes (`GlidePath`,
`SuggestionStripState`, `EmojiSuggestIndex`). The scripts/UI/privacy findings belong to the
parallel agent's wave. No commits — the operator commits. Canonical English; every finding names
the change, the pin, and the evidence. Gate numbers live at the bottom.

F9/F12/F15 are absent by assignment (not this half).

## F1 — paste of a large clip kills the IME (fixed)

`RichInputConnection.pasteClipboard` funneled every text clip into `onTextInput` →
`commitText` — an IPC parcel copy with no length cap; a clipboard of ~0.5 MB and up throws
`TransactionTooLargeException` inside the editor call and takes the IME down. The threshold
lives in `RichInputConnection.java` (`MAX_DIRECT_PASTE_CHARS = 64 * 1024` — an order of
magnitude under the binder's ~1 MB transaction cap): below it the commit path is byte-identical
to before; at/above it the code falls through to
`performContextMenuAction(android.R.id.paste)` — the editor pulls the clipboard itself and no
parcel copy crosses the binder. The decision sits in a package-private Android-free predicate
(`shouldCommitPasteDirectly`) so the boundary is test-pinned.

Pins: `RichInputConnectionRobustnessTest.thePasteThresholdAdmitsOnlySmallNonEmptyClips`
(null/empty/1-char/64 Ki−1/64 Ki/70 Ki boundary behavior);
`RichInputConnectionRobustnessContractTest.thePasteThresholdIsPinnedAndGuardsTheDirectCommit`
(the constant, the guard order, the fallback wiring).

## F2 — sticky `mCursorMoved` (fixed)

`PointerTracker.mCursorMoved` reset only on a plain up (`onUpEventInternal`); a cancelled
space/delete swipe leaked the state into the next touch, whose up then dereferenced a null
`currentKey` and fired the swipe callbacks for a gesture that never swiped. The flag now resets
in `onDownEventInternal` (a new gesture starts clean) and in `onCancelEventInternal` (a cancel
ends the gesture), and the two up-site reads carry `currentKey != null` guards.

Pin: `PointerTrackerRobustnessContractTest` (source-level — PointerTracker's static state needs
a live `Resources`; the same harness as `GlideTouchIntegrationContractTest`):
`theSwipeStateResetsAtDownAndAtCancel`, `theSwipeUpCallbacksAreNullGuarded`.

## F3 — unbounded before-cursor cache (fixed)

`commitText` and the three `sendKeyEvent` append sites grew `mTextBeforeCursor` without a limit
(a megabyte-long paste stayed resident in full). All four now funnel through
`appendToTextBeforeCursor`: the cache keeps the TAIL of `EDITOR_CONTENTS_CACHE_SIZE` (1024)
chars — the size a full reload asks for — and cutting the head clears the C6
`mCacheReachedTextStart` provenance flag at exactly that moment (the field's javadoc gained the
exception; every other local mutation still preserves it, as `CacheTextStartProvenanceTest`
pins).

Pins: `RichInputConnectionRobustnessTest.appendingBeyondTheWindowKeepsTheTailAndDropsTheProvenance`,
`appendingUpToTheWindowKeepsTheProvenance`; the pre-existing provenance suite passes unchanged.

## F4 — `setTextAroundCursor` without index validation (fixed)

A host reporting a selection outside its own `SurroundingText` (negative, inverted, past the
end) crashed the IME in `String.subSequence`. The index arithmetic moved into a package-private
Android-free core, `applyTextAroundCursor(text, selStart, selEnd)`: an out-of-range report gets
the same empty-cache treatment as a null `SurroundingText` and answers false (the wrapper logs;
the core stays Android-free so the JVM tests drive it). The `SurroundingText` overload keeps
its null path byte-identical (now expressed as the valid empty split).

Pins: `RichInputConnectionRobustnessTest.aValidSurroundingTextSplitsAroundTheSelection`,
`anInvertedOrOutOfRangeSurroundingSelectionYieldsEmptyCaches`.

## F5 — batch-edit pairing (fixed)

All nine `beginBatchEdit`/`endBatchEdit` pairs in `InputLogic` (tryDoubleSpacePeriod, the
double-space revert, performRecapitalization, replaceTrailingWord, revertTatarAutocorrection,
commitPredictedWord, commitGlideWord, replaceGlideLiftedWord, deleteGlideLiftedWord) and the one
in `RichInputConnection.deleteSelectedText` are try/finally now: a RuntimeException from a
dying editor mid-edit no longer sticks the nest level forever. Nothing is caught anywhere —
the exception propagates exactly as before. The `final boolean connected = isConnected()`
ordering the frozen text contract pins (`CommitPathConnectionContractTest`) is preserved
verbatim: the connection read sits between the begin and the try, the guarded edit inside, one
begin and one end per path — that suite passes unchanged.

Pins: new `BatchEditPairingContractTest` — every batch opens a try before its first edit, every
end sits inside a finally, no `catch` exists in either file, and the scan anchors to the known
counts (9/9 InputLogic, 1/1 RichInputConnection) so a silently-missed site goes loud.

## F6 — NPEs on a dead editor (fixed)

`deleteSelectedText` checks `isConnected()` right after `beginBatchEdit()` (the refresh point)
inside the try — a dead editor gets no edit at all rather than a cache-only mutation, the same
doctrine the commit paths follow; the batch still closes in finally. `replaceText` refreshes and
checks the connection BEFORE the cache mutation (previously the cache was rewritten and only
then a stale `mIC` was dereferenced). `pasteClipboard`'s context-menu fallback refreshes and
checks before `performContextMenuAction`.

Pin: `RichInputConnectionRobustnessContractTest.theDeadEditorGuardsSitBeforeAnyMutation`
(guard-before-mutation order per method),
`deleteSelectedTextClosesItsBatchInFinally`.

## F7 — inverted selection from the host (fixed)

`RichInputConnection.updateSelection` normalizes an inverted report (start > end) by swapping —
the one choke point every selection report funnels through — and `performRecapitalization`
gained the second line of defense: `numCharsSelected < 0 → return`, ahead of the substring work
that used to crash on the negative range.

Pins: `RichInputConnectionRobustnessTest.anInvertedSelectionReportIsNormalized` (behavioral);
`BatchEditPairingContractTest.performRecapitalizationRefusesANegativeSelectionLength`
(guard placement, source-level — InputLogic needs a live IME).

## F8 — background cache write race (fixed)

`reloadTextCache` applied the background read result from the background thread: a
check-then-set across threads, so a UI mutation landing between the staleness check and the
write was overwritten by the stale read (and the pre-S branch could leave a HALF-applied cache
when staleness was detected mid-way). The background task now only READS; the result is applied
by a runnable posted to the IME handler, which re-verifies the expected selection there — on the
UI thread, serialized with every mutation — and writes all three caches atomically. A dropped
apply touches nothing and asks for no re-derivation, exactly like the pre-fix early returns.

Pins: `RichInputConnectionRobustnessContractTest.theBackgroundSectionNeverWritesTheCache`
(neither the S+ section nor the pre-S reader contains a cache write),
`theApplyRunsOnTheUiThreadAgainstAReVerifiedSelection` (each posted apply re-checks first and
ends with the completion bookkeeping; the S+ re-check precedes the window write).

## F10 — reload coalescing (fixed)

Every `onUpdateSelection` used to enqueue a reload, and the S+ branch paid the
`getSurroundingText` IPC before checking anything. Now: at most one reload is in flight
(`mReloadInFlight`), a request arriving mid-flight folds into `mReloadRequestedWhileInFlight`
and produces exactly one follow-up from `finishReloadTextCache`, and the staleness check runs
BEFORE the first IPC. The completion bookkeeping is posted even on the stale/disconnected/
dying-editor paths (a `finally` around the background body — no catch), so the flag can never
stick. Both flags are UI-thread confined.

Pins: `RichInputConnectionRobustnessContractTest.theReloadCoalescesAndChecksStalenessBeforeTheIpc`,
`aReloadThatAppliesNothingStillClearsTheInFlightFlag`.

## F11 — `getUnicodeSteps` hygiene (fixed)

The two `== ""` reference comparisons are gone. Deviation from the audit's suggested
`.isEmpty()`: the locals are `CharSequence`, and `CharSequence.isEmpty()` is a default method
that exists only from API 35 (verified against the API-34 android.jar: no `isEmpty`; API-37 has
it) while the app ships minSdk 24 with no core desugaring — `.length() == 0` is the equivalent
that runs everywhere. The ZWJ boundary claim holds: the backward pass checked `i > 1` before
reading `charAt(i - 1)`, but index `i - 1` is valid for `i >= 1` — with the old boundary a
cached window starting mid-cluster (a ZWJ at index 0) skipped the joiner check at index 1 and
the step stopped short, leaving the dangling joiner behind the cursor. Fixed to `i >= 1`.

Pins: `RichInputConnectionRobustnessTest` —
`aZwjClusterEndingAtTheCursorIsOneStep` (control, unchanged),
`aZwjTailAtTheWindowStartIsSwallowedWhole` (`"\u200dб"` → −2; pre-fix returned −1),
`aZwjPlusEmojiTailAtTheWindowStartIsSwallowedWhole` (`"\u200d👩"` → −3; pre-fix −2),
`ordinaryAndSurrogateStepsAreUnchanged`, `anEmptyCacheReturnsTheRequestUnchanged`,
`aForwardStepOverAClusterCoversTheWholeCluster`.

## F13 — PointerTrackerQueue dedup and eviction (fixed)

`add` is idempotent per element: a tracker whose up event was lost (e.g. the keyboard closed
mid-touch) no longer enters the queue twice. `cancelAllPointerTrackers()` now EVICTS after
cancelling — cancelled trackers used to linger forever, inflating the active-pointer count and
receiving phantom ups meant for live touches. The duplicate tripwires in
`remove`/`releaseAllPointers*` stay as the loud alarm.

The ACTION_CANCEL ordering is preserved by construction: `PointerTracker.onCancelEvent` used to
run cancel-all, then `releaseAllPointers` (the phantom-up wave), then its own cancel; with
eviction inside cancel-all that release would iterate an empty queue, so the wave moved INTO the
queue as `cancelAllPointerTrackers(eventTime)` — disable everyone first (a phantom up delivered
to a still-enabled tracker would commit its key), phantom-up second (pressed graphics and
in-flight glides released), evict last. The zero-arg static `PointerTracker.cancelAllPointerTrackers()`
keeps its exact contract for the closing path (`MainKeyboardView.cancelAllOngoingEvents`).

Pins: `PointerTrackerQueueTest` (behavioral — the queue is plain Java): add dedup, cancel-all
evicts, the disable-before-phantom-before-evict order, unchanged release semantics.
`PointerTrackerRobustnessContractTest`: the caller-side wiring.

## F14 — `GlidePath.addPoint` drops non-finite samples (fixed)

A NaN/Infinity coordinate would poison every distance the decoder measures from the path
(`length()`, the resampler, the geometry channels). `addPoint` now refuses non-finite x/y/t and
answers false — the same fail-closed shape as the capacity cap, ahead of it.

Pin: `GlidePathTest.nonFinitePointsAreRefusedFailClosed` (all three coordinates, both
infinities, buffer state and finite geometry afterwards).

## F16 — `EmojiSuggestIndex.parse` record cap (fixed)

Mirrors `SentStartIndex`: `MAX_RECORDS = 4096`, fail-closed — the packer ships 3 976 entries, so
tens of thousands mean junk; parsing stops at the cap keeping the FIRST records (file order,
like the duplicate rule). Note for the next asset regen: the shipped table sits 120 records
under the cap; `EmojiSuggestIndexTest` pins 3 976 exactly, so a growing table fails loudly there
first.

Pin: `EmojiSuggestIndexTest.theRecordCountIsCappedFailClosed` (5 000-line input → exactly 4 096
entries, word0 kept, word4096 absent); the shipped-asset tests pass unchanged.

## F17 — `SuggestionStripState.cellAt` NaN guard (fixed)

A NaN coordinate passes every bounds comparison (all false) and fell through to the LAST cell —
a MotionEvent carrying NaN would click a cell the finger never touched. `cellAt` now returns
`NO_CELL` for non-finite x/y before the bounds checks (inline `isFinite`, allocation-free — the
zero-allocation tests still pass).

Pin: `SuggestionStripStateTest.nonFiniteCoordinatesHitNoCell` (NaN/±Infinity on both axes, a NaN
down starts no gesture, a NaN up mid-gesture clicks nothing).

## Gates

All run on the shared working tree of 2026-09-25 (this half plus the parallel agent's). Two
`./gradlew test --rerun-tasks` attempts and the first `lintRelease` died to build-dir races with
the parallel agent's concurrent gradle runs (`NoSuchFileException`/`EOFException` on
`test-results/.../binary`, a deleted lint model) — infrastructure, not red tests; every gate was
re-run in a quiescent window and the results below are the clean runs.

| Gate | Result |
|---|---|
| Python pipeline suites | 16/16 test files green (`for f in tests/*/test_*.py; do python3 "$f"; done`) |
| `./gradlew test --rerun-tasks` | **1633 tests, 0 failures / 0 errors / 0 skipped** (1588 → 1633: +35 this wave, +10 the parallel agent's; `AGENTS.md` updated) |
| `./gradlew lintRelease` | exit 0 — no new errors (baseline/lint.xml classification holds) |
| `python3 scripts/rebuild_assets.py --check --allow-known-drift` | `ok: true` (all pins and dictionary↔bigram links verified) |
| Release APK (unsigned, `-PskipReleaseSigning`) | 1 778 766 B ≤ 3 145 728 B budget |
| `bash scripts/check-no-internet.sh` (source + APK) | level 1 OK (manifest), level 2 OK (aapt2: no INTERNET; backup whitelist OK) |
