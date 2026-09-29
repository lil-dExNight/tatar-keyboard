# InputConnection binder audit — O6, 2026-09-29

Plan item O6 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`. Every
`InputConnection` (IC) call is a binder transaction into the host app, and the
platform docs name binder stalls a top UI-thread stall source. Scope:
`latin/inputlogic/InputLogic.java`, `latin/RichInputConnection.java`,
`latin/RichInputMethodManager.java` (IC-adjacent parts), and every IC call site
reachable per keystroke / per commit / per suggestion tap.

Verdict up front: **no code fix needed** — the 2026-09-25 F-wave
(`docs/SECURITY-AUDIT-2026-09-25-FIXES.md`) had already closed every batching
and staleness hole. What this audit adds is the *inventory proof*: the complete
set of IC call sites, count-anchored in source pins so any new site goes loud
(`InputConnectionBinderContractTest`). The `adb shell am trace-ipc` evidence leg
is wave 3's, not this item's.

## The complete IC inventory (RichInputConnection is the sole holder)

`mIC` lives only in `RichInputConnection`; `InputLogic` holds no raw connection
and imports no `android.view.inputmethod.InputConnection`. Every call site:

| Call | Sites | When |
|---|---|---|
| `commitText` | 2 | per keystroke/paste/emoji (`RichInputConnection.commitText`), pre-34 `replaceText` fallback |
| `deleteSurroundingText` | 3 | backspace (`deleteTextBeforeCursor`), `deleteSelectedText`, pre-34 `replaceText` fallback |
| `sendKeyEvent` | 1 | digits + Enter/backspace compat path (`sendDownUpKeyEvent`, DOWN+UP) |
| `setSelection` | 1 | cursor gestures, recapitalization, `deleteSelectedText` |
| `replaceText` | 1 | recapitalization on API 34+ |
| `performEditorAction` | 1 | action keys |
| `performContextMenuAction` | 1 | large-paste fallback (≥ 64 Ki chars — F1) |
| `beginBatchEdit` / `endBatchEdit` | 1 + 1 | the batched edit paths below |
| `getSurroundingText` | 1 | background reload only, window bounded 2×1024 chars |
| `getTextBeforeCursor` / `getTextAfterCursor` / `getSelectedText` | 1 + 1 + 1 | pre-S branch of the same background reload |
| `getCursorCapsMode` | **0** | deliberately absent — caps derive from the local cache |

`EditorInfo.getInitialSurroundingText` (field start, S+) reads the
already-delivered parcel — **no binder round trip**.

## Q1 — per-keystroke cost: CLEAN

A letter keystroke costs exactly **one** UI-thread binder transaction
(`commitText` via `InputLogic.sendKeyCodePoint`,
`RichInputConnection.java:406`). Backspace: one `deleteSurroundingText`
(`:579`). Digits: DOWN+UP `sendKeyEvent` pair (AOSP backward compat). Every
per-keystroke *read* is local-cache only:

- auto-caps `getCursorCapsMode` reads `mTextBeforeCursor`
  (`RichInputConnection.java:452-463`, pinned by
  `EditorTextCachePrivacySourceContractTest`);
- suggestion context reads (`cachedWordBeforeCursor`,
  `cachedNextWordContext`, `isAtSentenceStart`, …) are all
  `getCachedTextBeforeCursor/AfterCursor` (`LatinIME.java:497-611`);
- the backspace emoji-cluster measure reads the cache
  (`InputLogic.java:429-430`);
- the feedback gate `canDeleteCharacters` reads the expected-selection ints
  (`LatinImeKeyFeedback.java:43`);
- EditorInfo-derived state (action id, password gate) comes from
  `InputMethodService`'s cached field — no override anywhere in `LatinIME`.

Per keystroke the framework's own `onUpdateSelection` callback schedules one
background `getSurroundingText` reload (F10: at most one in flight, bursts
fold into one follow-up, staleness checked *before* the IPC). That transaction
runs on `RichInputConnection`'s single-thread executor — off the UI thread —
and revalidates the local cache against the editor's ground truth (hosts may
transform or reject a commit). Deliberate design, kept.

`RichInputMethodManager` holds no IC at all; its per-commit
`resetSubtypeCycleOrder()` (called from `RichInputConnection.commitText`/
`sendKeyEvent`/`setSelection`) is an in-process singleton that early-returns
when the cycle order is untouched and writes prefs via `apply()`
(`Settings.java:437-448`) — no binder, no sync disk I/O.

## Q2 — batching: CLEAN

All ten multi-step edit sequences are wrapped in
`beginBatchEdit()/endBatchEdit()` with try/finally (F5): the nine in
`InputLogic` (`tryDoubleSpacePeriod` :368, double-space revert :413,
`performRecapitalization` :482, `replaceTrailingWord` :597,
`revertTatarAutocorrection` :663, `commitPredictedWord` :755,
`commitGlideWord` :855, `replaceGlideLiftedWord` :910, `deleteGlideLiftedWord`
:959) and `RichInputConnection.deleteSelectedText` (:589). Pairing and counts
are pinned by `BatchEditPairingContractTest`; the connected-check ordering by
`CommitPathConnectionContractTest`.

Verified non-batches, both deliberate:

- `sendDownUpKeyEvent`'s DOWN+UP pair — batch edits are ignored for key
  events (they travel a different, asynchronous binder; the AOSP javadoc at
  `InputLogic.java:1066-1068` says so verbatim). Now pinned.
- `RichInputConnection.replaceText`'s pre-34 fallback issues two calls
  (delete + commit, `:557-558`) and has exactly one caller —
  `performRecapitalization`, which batches the whole rotation. Now pinned.

## Q3 — draw path: CLEAN

Zero IC reachability from any View. The `keyboard/` package (KeyboardView,
MainKeyboardView, MoreKeysKeyboardView, PointerTracker, …) contains no
`InputConnection`/`mConnection` reference at all; neither do the latin-side
canvas views (`SuggestionStripView.kt`, `EmojiPanelView.kt`,
`EmojiSearchView.kt`). `KeyboardView.onDraw` is pure offscreen-bitmap work
(`KeyboardView.java:221-247`). The only holders of `mInputLogic` are
`LatinIME` (a Service — cannot be on a draw path) and `LatinImeKeyFeedback`
(press feedback, cache-only read). Now pinned by package-wide source scans.

## Q4 — getSurroundingText: CLEAN

Exactly one call site (`RichInputConnection.java:267`), on the background
reload executor, window bounded at `EDITOR_CONTENTS_CACHE_SIZE` (1024) chars
in each direction — worst-case payload ~4 KB parcelled. Frequency discipline
(F10): requested from `onUpdateSelection` and the space-slide release only;
at most one in flight plus one coalesced follow-up; the staleness check runs
before the IPC. Result validation is F4's (`applyTextAroundCursor`,
fail-closed on out-of-range selections). The field-start fill on S+ uses the
EditorInfo parcel (`getInitialSurroundingText`) — zero round trips. All now
count-anchored.

## Q5 — the cache absorbs per-keystroke reads: CLEAN

Confirmed. Every consumer reads the local cache; per-keystroke writes keep it
in sync without IPC (`commitText` → `appendToTextBeforeCursor`, F3-bounded to
the 1024-char tail; `deleteTextBeforeCursor` truncates; `setSelection`
re-slices the window). Reloads happen only at: field start (EditorInfo parcel
on S+), cursor moves (background, coalesced), the space-slide release — plus
the privacy boundaries that *clear* instead (password fields, window hidden,
finish input; pinned by `EditorTextCachePrivacySourceContractTest`). The
provenance flag (`mCacheReachedTextStart`, C6) is written only by the full
reload. Call-site counts now pinned.

## Pins added

`app/src/test/java/rkr/simplekeyboard/inputmethod/latin/InputConnectionBinderContractTest.kt`
(source-contract idiom, same as `CommitPathConnectionContractTest`):

- `inputLogicReachesTheEditorOnlyThroughTheCacheAwareWrapper` — no
  `InputConnection` import, no `mIC.` in `InputLogic`.
- `aLetterKeystrokeCostsExactlyOneEditorMutation` — one `commitText` in
  `sendKeyCodePoint`, no editor read on the path.
- `editorInfoAndConnectionComeFromTheFrameworkCache` — no overrides in
  `LatinIME`.
- `theOnlyEditorReadsAreTheCoalescedBackgroundReload` — the four IC reads
  exist exactly once each, all inside `reloadTextCache()`, the
  `getSurroundingText` window bounded; the `getCursorCapsMode` binder trap
  absent.
- `theFieldStartCacheFillReadsTheEditorInfoParcelNotTheBinder` — S+ field
  start pays zero round trips.
- `theEditorCallInventoryIsExactlyTheKnownSet` — the eleven IC call kinds
  count-anchored in the sole IC holder.
- `keyEventsStayUnbatchedByPlatformDesign` — the DOWN/UP pair keeps its
  doctrine comment and no batch.
- `replaceTextsTwoCallFallbackOnlyEverRunsBatched` — one caller, inside the
  recapitalization batch; delete+commit adjacent.
- `theKeyboardPackageNeverReferencesTheConnection` /
  `theLatinSideCanvasViewsNeverReferenceTheConnection` — the draw layer
  cannot reach the binder.
- `theCacheIsReloadedAtExactlyTheKnownBoundaries` — two cursor-driven reload
  sites + one field-start site in `LatinIME`, count-anchored.

## Not done here (by plan)

- The `adb shell am trace-ipc` transcript — wave 3's device leg, archived
  under `build/` by whoever runs it.
