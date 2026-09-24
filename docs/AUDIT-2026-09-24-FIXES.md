# AUDIT-2026-09-24-FIXES — the code half of the 3.0.0 audit fix wave

Status: done 2026-09-24. The wave's other half (docs/python/strings/NOTICE) belongs to a
parallel agent; this report covers the code/resources half only. The audited tree was release
3.0.0 (HEAD `b8de3916`). No commits — the operator commits. Canonical English; every finding
below names the change, the pin, and the gate numbers. The final gate row lives at the bottom.

## F1 — MAJOR: the revert path ran without a connection (fixed)

`InputLogic.revertTatarAutocorrection` deleted and committed unconditionally — the one
insertion-path sibling 25c1ae28 missed when it armed the other two. The same flag pattern is
now applied (`InputLogic.java:642-660`): the connection is asked once, right after
`beginBatchEdit()` (which is what refreshes it from the framework), both mutations run only
inside the guarded block, the batch still closes exactly once on either path, and a missing
editor gets `false` instead of a cache-only mutation reported as success.

Pin: `CommitPathConnectionContractTest` — the revert body joined `paths()` (so the
check-exists, check-placement and single-batch tests all cover it) and the delete-guard
assertion now iterates both deleting paths. The shipped-shape fail-capability test is
unchanged and still rejects the pre-guard body.

## F2 — MAJOR: dead word-usage updates (fixed)

`PersonalDictionaryStore.noteAcceptedSuggestion` existed with tests but no production caller.
The event now flows: `SuggestionsController.onTap` PREFIX branch reports the accepted word
(`SuggestionsController.kt:2093`, right after a successful commit, mirroring the pairs branch)
→ `CleanRunMachine.noteAcceptedSuggestion` (`CleanRunMachine.kt:186-193`, the same pass-through
shape as `noteAcceptedPrediction`) → `WordCompletionSink.onAcceptedSuggestion`
(`WordCompletionSink.kt:33-40`, a DEFAULT no-op so every existing sink keeps compiling —
the `fun interface` keeps its single abstract method) → `PersonalLearning.sinkFor`
(`PersonalLearning.kt:85-97`), gated by the very same `mayLearn()` predicate (incognito
included) and the per-event subtype resolution, into the store's in-memory-only bump. The file
is never rewritten per tap: the session-end flush persists counters, as before.

Pins: `PersonalLearningRunTest.anAcceptedPrefixSuggestionCountsAsAUseOnTheWordSink` (tap →
sink event) and `aCleanlyTypedWordIsNotAnAcceptance` (typing never arrives as an acceptance);
`PersonalLearningGatesTest` amended (old → new): the shape test gained the third event
(`aClosedPredicateWritesNothingOnEitherEventPath` → `…OnAnyEventPath`), and the guard-count
pin `bothSinkMethodsAreGatedInProductionToo` → `allThreeSinkMethodsAreGatedInProductionToo`
(2 → 3 `if (!predicate.mayLearn()) return` sites). The store's in-memory-bump/locked-gate
behavior stays pinned by `PersonalDictionaryStoreWriteTest`, untouched. The pairs path is
unchanged (its own sink and pins cover it).

## F3 — MEDIUM: the baseline profile had no glide CUJ (fixed)

`ImeBaselineProfileGenerator` gained a glide step
(`baselineprofile/.../ImeBaselineProfileGenerator.java`): after the next-word prediction
commit, one continuous polyline swipe through the five letter-key centers of «сәлам»
(`UiDevice.swipe(Point[], segmentSteps)` — the finger never lifts, so the decider arms), a
settle, and a tap on the strip's left cell. Regenerated on the `tt_suggest_a14` emulator with
the physical device excluded (`ANDROID_SERIAL=emulator-5554`, per the finding — the MIUI
device breaks the profile broadcast).

Rule counts, packaged profile `app/src/main/baseline-prof.txt`: **3 071 → 3 240** rules
(generator's own comparison: 256 added, 87 removed — the removed ones are pre-3.0.0 class
shapes the current tree no longer has, e.g. pre-split names). Glide coverage present: 152
`Glide`-mentioning rule lines, including `GlideDecoderHost`, `CompositePrefixComputer.decodeGlide`,
`updateGlideGeometry`, `PointerTracker.armGlide`, `LatinIME.onGlideInput`.

## F4 — app_restrictions.xml swapped titles (fixed)

`app/src/main/res/xml/app_restrictions.xml`: `pref_enable_ime_switch` back to
`@string/pref_enable_ime_switch`, `pref_space_swipe` back to `@string/space_swipe`, and
`pref_keypress_sound_volume` to `@string/prefs_keypress_sound_volume_settings` (was also
`@string/pref_enable_ime_switch`). Resource-only; covered by the build.

## F5 — metadata logging (fixed)

`LatinIME.java:1594` and `:1738`: the "Starting input…" and "Update Selection…" lines printed
cursor positions on every focus/selection — both are now gated behind the file's own
`if (TRACE)` debug flag. `Settings.java:191-265` (`loadRestrictions`): every per-key log line
now names the KEY only; the values (subtype list, theme, colors, booleans) are never printed —
six call sites changed, with the convention noted at the loop head. No behavior change; the
`Log.e`/`Log.i` severity structure is untouched.

## F6 — stale geometry on language switch (fixed)

`LatinIME.onCurrentSubtypeChanged` (`LatinIME.java:1529-1542`): `updateKeyNeighbors()` now runs
BEFORE `mSuggestionsController.onSubtypeChanged(...)` — the controller's handler may
immediately re-derive the band for a warm engine, and that lookup must already see the new
layout's neighbor table (the glide geometry rides the same table). Pin:
`SubtypeSwitchAnnouncementSourceContractTest.theNewLayoutGeometryReachesTheEngineBeforeTheSubtypeChangeIsAnnounced`
(source-contract on the handler body ordering).

## F7 — pair boundary after autocorrect (fixed)

After a successful `maybeAutocorrectBeforeSeparator` replacement the run machine's
`markRunDirty()` left the pair machine dirty, and the separator's `onTextChanged` hit the
no-change early return, so the boundary never re-armed and the corrected word could never
become a pair's context half. The fix mirrors the tap path: `runMachine.trustPairBoundary()`
after the successful replacement (`SuggestionsController.kt:2007`). Pin:
`AutocorrectControllerTest.aCorrectedWordStillBecomesPairContextForTheNextCleanWord` — a
corrected «китап» followed by a cleanly typed «дөнья» reports the pair («китап», «дөнья»),
while the words machine still learns nothing from the correction.

## F8 — onTap branch guard (fixed)

`SuggestionsController.onTap` (`SuggestionsController.kt:2100-2170`): the NEXT_WORD and glide
branches are now mutually exclusive (`else if` at :2139), with the contract note extended —
the three bindings (prefix / next-word context / glide context) are "exactly one non-null" by
invariant, but a broken invariant must still commit at most once per tap. The PREFIX branch
already returned early; the change is invisible to all green tests (they exercise one binding
at a time).

## F9 — dialog contract covers the languages screen (fixed)

`DialogObscuredTouchContractTest.everySettingsDialogInstallsTheFilter` now also counts
`AlertDialog.Builder(` vs `DialogUtils.filterObscuredTouches(` in
`SettingsLanguagesScreens.kt` (the add-language picker) — currently 1 == 1, and any future
dialog there that forgets the filter fails.

## F10 — InputAttributes.toString() (fixed)

`InputAttributes.java:153-168`: the `targetApp=` component (the host app's package name) is
gone from the debug string. The public field stays (it is constructor-assigned public API of
the class); only the log surface changed.

## F11 — recents medium read without a size cap (fixed)

`RecentEmojiStore.kt` (`AtomicRecentEmojiFileOps.read`, :189-208): the length is checked BEFORE
reading — the written medium is bounded by `RecentEmojiList.MAX_CHARS` (512 UTF-16 `char`, under
2 KiB in UTF-8), so `MAX_MEDIUM_BYTES = 4096` refuses a bloated file without decoding a byte.
Fail-closed, like the personal stores' caps. Pin: new `AtomicRecentEmojiFileOpsTest` (4 tests —
verbatim round-trip, oversized refused, the boundary itself readable, absent → null).

## F12 — error-prone LongFloatConversion (fixed)

`PointerTracker.java:594, 717, 721`: the three `mGlidePath.addPoint(x, y, eventTime)` calls
converted `long → float` implicitly into `GlidePath.ts`; the cast is now explicit, with the
reasoning at the first site (gesture DELTAS are milliseconds apart — the 24-bit mantissa is
exact where it matters). Behavior is identical by construction. Verified:
`compileReleaseJavaWithJavac --rerun-tasks` no longer prints the three warnings (the
pre-existing `ClassInitializationDeadlock` at KeyboardActionListener.java:92 stays — F2
documents it; the ReferenceEquality warnings at PointerTracker are pre-existing and untouched).

## F13 — quarantine notice semantics (fixed, per-language)

`PersonalDictionaries.consumeQuarantineNotice()` consumed the durable marks of ALL open stores
for the one dialog shown — a second language that lost data had its mark spent on a dialog
about nothing it could see. Both owners now keep a per-language pending set
(`PersonalDictionaries.kt:76, 133-160`; `PersonalBigramDictionaries.kt` mirrored): raising adds
the subtype, `consumeQuarantineNotice()` takes the earliest pending ONE and clears only that
store's durable mark, and the next pending language is delivered at the next input start. The
IME call sites keep their Boolean contract unchanged; a language whose store never opened keeps
its file mark and raises its own notice on open (fail-closed, as before).

Pins amended in `PersonalQuarantineNoticeSourceContractTest.theNoticeWaitsWhenNobodyIsListeningYet`
(old → new): `quarantinePending = true` → `pendingQuarantineNotices.add(subtypeId)`;
`if (!quarantinePending) return false` → `pendingQuarantineNotices.firstOrNull() ?: return false`;
added the per-language spend pin (`stores[subtypeId] }?.noticeDelivered()`); reset now pinned
as `pendingQuarantineNotices.clear()`.

## F14 — duplicate pending-file read (fixed)

`PersonalDictionaryStore.load()` read the pending counters file twice per open (before and
after the dictionary read); `readPending` REPLACES state, so the second read was pure duplicate
I/O. The first read — the one that runs on every path, including a missing or quarantined
dictionary — stayed; the second is gone (`PersonalDictionaryStore.kt:538-564`). Pin:
`PersonalQuarantineNoticeSourceContractTest.thePendingFileIsReadOncePerOpen`.

## F15 — L4/L5 settings UX (half fixed, half handed to F2 by design)

(a) Dependent rows disabled while suggestions are off swallow taps: NOT changed — the toast
needs a new string ("turn on Word suggestions first"), and strings are the parallel agent's
half of the wave. A `TODO(F2 …)` marks the exact wiring point
(`SettingsHostActivity.buildPreferencesScreen`, the master-switch handler). Documented here as
the deliberate split the finding itself allowed.
(b) `SettingsLanguagesScreens.buildLanguagesScreen`: the "Add language" row is hidden when
there is nothing left to add (the picker would open on an empty list), and the actions card is
skipped entirely when it would be empty.

## F16 — chevron autoMirrored (fixed)

`app/src/main/res/drawable/app_chevron.xml`: `android:autoMirrored="true"` added — the `_back`
sibling already had it.

## Gates (2026-09-24, host)

| Gate | Result |
|---|---|
| python suites (`tests/*/test_*.py`) | all pass (505, untouched) |
| `./gradlew test --rerun-tasks` | **1 543 tests, 0 failures** (1 534 → 1 543: +9 — four new `AtomicRecentEmojiFileOpsTest`, two new `PersonalLearningRunTest` pins, one `AutocorrectControllerTest` pair pin, one `SubtypeSwitchAnnouncementSourceContractTest` ordering pin, one `PersonalQuarantineNoticeSourceContractTest` duplicate-read pin; renamed/amended pins recorded per finding above) |
| `./gradlew lintRelease` | 0 errors; 4 warnings — the same pre-existing environmental set |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (assets untouched) |
| `./gradlew assembleRelease -PskipReleaseSigning` | **1 860 976 B** (budget 3 145 728) — carries the regenerated profile (3 240 rules) |
| `check-no-internet.sh` on the APK | Level 1 + Level 2 OK, backup whitelist closed |
| error-prone recompile | LongFloatConversion ×3 GONE (verified); ClassInitializationDeadlock stays (F2 documents) |

The two mid-wave reds and their resolutions: `GlideTouchIntegrationContractTest.armedGlidePrecedesAndShortCircuitsTheLegacyMoveBranches`
pinned the literal `mGlidePath.addPoint(x, y, eventTime);` — amended to the explicit-cast form
(old → new recorded in F12), and `DataSourcesScreenSourceContractTest.the_opensubtitles_link_is_present_in_the_product`
pinned `http://www.opensubtitles.org/` in the resource while the parallel agent's strings edit
moved it to https — the assertion now follows the shipped resource (https), the NOTICE side of
the pin unchanged.
