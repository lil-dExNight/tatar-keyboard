# ROADMAP-P6 — phase 6 report: foundations (T2, god-object splits)

Status: **part 1 of 3 (SuggestionsController.kt) done 2026-09-23** — six units
extracted as pure moves, the file is 2 525 → 2 000 lines, full gates green with
an identical test count (1 456); **part 2 of 3 (LatinIME.java) done
2026-09-23** — four units extracted as pure moves, the file is 2 440 → 2 254
lines, same gates, same count; **part 3 of 3 (SettingsHostActivity.kt /
EmojiPanelView.kt) done 2026-09-23** — five units extracted as pure moves, the
files are 1 568 → 1 129 and 1 317 → 1 168 lines, same gates, same count. The
phase's T2 verdict is in the close-out table below. No commits — the operator
commits.

Baseline before the part: JVM 1 456 tests, python 484 tests, release 2.5.0-dev
tree at 784d8991. Rules enforced throughout: pure moves only (no behavior
change, no API redesign), `./gradlew test` green with the identical count after
EVERY extraction, every KDoc/comment travels with its unit, new files carry a
short file-level KDoc.

## Part 1 — splitting SuggestionsController.kt (done 2026-09-23)

### What was extracted where

| Unit | New file | Lines (new file) | From original file | Rationale |
|---|---|---|---|---|
| `EditorSurface`, `SuggestionTapListener`, `AutocorrectGate`, `EmojiSuggestGate`, `UiPoster`, `DictionaryUnavailableListener` | `SuggestionSurfaces.kt` | 155 | 72–187, 301–312 | Injected seams: pure declarations with zero references to controller state. |
| `DictionaryPreparation`, `BigramPreparation`, `DeviceProtectedDictionaryPreparation`, `DeviceProtectedBigramPreparation` | `SuggestionPreparation.kt` | 148 | 189–299 | Storage-preparation seams and their production implementations; the two `DeviceProtected*` classes went `private` → `internal` — the mechanical minimum, since file-private cannot be referenced from another file (the production constructor uses them). |
| `LanguageSlot` | `LanguageSlot.kt` | 80 | 420–477 | The per-language slot (storage seams, readiness, engine, release bookkeeping). It was a `private inner class` that referenced NO outer state, so `inner` is simply gone; `internal` replaces file-private for the same reason as above. |
| E4c clean-run machine + P1 pair-run machine | `CleanRunMachine.kt` | 197 | state 276–299; `trackCleanRun`/`reportCompletionIfClean`/`reportPairCompletionIfClean`/`markRunDirty` 1689–1771; the empty-result observation out of `applyPrefixResult`; the sink flush out of `onFinishInput` | A self-contained state machine: one run of text, two cleanliness bits, two sinks. The four tiny entry points the controller performed inline moved with it verbatim: `observeEmptyResult(prefix)`, `onInputFinished()`, `trustPairBoundary()`, `noteAcceptedPrediction(context, suggestion)`. The editor read (`cachedWordBeforeTrailingWord`) arrives through the same `EditorSurface` instance, at the same moment. |
| D3 undo window (`Replacement`, armed/revertable, advance/clear, separatorString) | `RevertWindow.kt` | 101 | class 337–349, fields 351–359, `advanceRevertWindow` 1948–1964, `separatorString` 1977–1978 | At most one armed + one revertable replacement; the window's transitions never touch anything besides the session id, which is now a parameter. The editor calls (`replaceTypedWord`/`revertTypedWord`) deliberately STAY in the controller — `AutocorrectSourceContractTest` pins their exact call sites to that file. |
| Autocorrect preview (`AutocorrectPreview`, `computeAutocorrectPreview`, `PREVIEW_EMPHASIZED_CELL`) | `AutocorrectPreview.kt` | 92 | class 1730–1740, function 1742–1783, const 2052–2055 | The P2 (phase 3) preview decision mirrors `maybeAutocorrectBeforeSeparator` check-for-check. It read four controller fields; it takes them as parameters now, in the same order and at the same moments — the engine advice is still read LAZILY (a provider lambda invoked exactly where the volatile read used to be), so the "cheapest-first, no allocation on the common band" property is preserved by construction. |

### What deliberately did NOT move, and why

- `StripSurface` stays in `SuggestionsController.kt`:
  `SuggestionStripSourceContractTest.theEmphasisMarkerTravelsFromTheControllerThroughTheProductionWiring`
  pins `fun setEmphasizedCell(cell: Int) {}` and the `showBand` body to that
  file. Moving the interface would break the pin, and the phase rule is that a
  broken pin means the extraction was wrong.
- The sentence-start block, the emoji-suggest block, and the companion-fill
  block were evaluated and rejected: their result-application halves WRITE the
  shared band/session state (`displayedPrefix`/`displayedContextWord`/
  `bandBaseCells`/`displayedSessionId`/`requestSessionId`) from asynchronous
  callbacks, so an "extraction" would have to invent a callback seam back into
  the controller — a redesign, not a move.
- The engine lifecycle (preparation → start → publish → attach → deferred
  release) is the controller's spine and touches every piece of state above;
  same conclusion.
- A "state object" holding the remaining ~20 loose fields was rejected: every
  method in the file would change for a bag of public mutable fields — churn
  without cohesion, and worse encapsulation than today.

### The size target (documented exception)

The phase asks for ≤ ~1 500 lines or a documented exception. The file lands at
**2 000** and the exception is recorded here. What remains is the coordination
core, and every remaining block both reads and writes the shared band/session
state, which is exactly what the rejected candidates demonstrated: constructors
and production wiring (~105), the state fields themselves (~180), the lifecycle
entry points `onStartInput`…`onDestroy` (~370), engine preparation/start/
publish/release and the bigram attach (~360), companion fill (~100), emoji
suggest (~115), sentence start (~130), the two request paths (~115), the three
apply paths plus `showBand` (~200), the autocorrect/revert entry points and
`onTap` (~190), `StripSurface` and the companion object (~95, pinned in-file).
Cutting another ~500 lines means introducing indirection between methods that
share mutable state — visible neither to tests nor to users, and therefore
capable only of adding risk, never of removing it. A partial clean result was
chosen over a forced one, per the phase's own rule.

### Line counts

| File | Before | After |
|---|---|---|
| `SuggestionsController.kt` | 2 525 | **2 000** |
| `SuggestionSurfaces.kt` | — | 155 |
| `SuggestionPreparation.kt` |  —| 148 |
| `CleanRunMachine.kt` | — | 197 |
| `RevertWindow.kt` | — | 101 |
| `AutocorrectPreview.kt` | — | 92 |
| `LanguageSlot.kt` | — | 80 |

### Gates (2026-09-23)

| Gate | Result |
|---|---|
| `./gradlew test` after EACH of the six extractions | 1 456 tests, 0 failures, every time |
| `./gradlew test --rerun-tasks` (final, honest run) | 1 456 tests, 0 failures/errors/skips — identical count, suite untouched |
| python suites (`tests/*/test_*.py`) | all pass, untouched |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `assembleRelease -PskipReleaseSigning` size | 1 855 716 B (pre-split: 1 853 820 B; +1 896 B = the six new class shells and their metadata) — ≤ 3 145 728 budget holds |
| `check-no-internet.sh` (source + built APK) | both levels OK |
| AGENTS.md counters | unchanged (1 456 was already the recorded count) |

Housekeeping inside the moved code: five imports that only the extracted
preparation implementations used left the controller file with them; the KDoc
cross-references that pointed at controller privates were re-pointed in prose
(`[destroyHandle]`, `[dictionaryUnavailableListener]`, `[onTap]`) so the moved
comments still name the right things.

## Part 2 — splitting LatinIME.java (done 2026-09-23)

Baseline before the part: JVM 1 456 tests, python 484 tests, the part-1 tree
(uncommitted). LatinIME.java was **2 440** lines. The same rules: pure moves,
`./gradlew test` green with the identical 1 456 after EVERY extraction, every
comment travels with its unit, no commits.

### What makes this file different from the controller

LatinIME.java is not a god object by accretion alone: it is the
`InputMethodService` itself, and twenty source-contract tests pin its TEXT.
Before touching a line, every test that reads the file was mapped
(`grep -rl "LatinIME.java" app/src/test` → 20 files). The pins fall into three
strengths:

1. **Body-pinned** (a method's body, sliced by signature, must contain exact
   strings — the method cannot leave the file): the whole dialog block
   (`showForgetPersonalWordDialog` … `attachDialogToInputWindow`, including an
   exact count of TEN `attachDialogToInputWindow(` occurrences,
   DialogObscuredTouchContractTest), `showEmojiPanel`, `setUpEmojiPanelController`
   (with the `showPanel` body), `onStartInputViewInternal`, `onWindowHidden`,
   `onFinishInputViewInternal`, `deallocateMemory`, `onUpdateSelection`,
   `onCurrentSubtypeChanged` + `announceCurrentLanguageForAccessibility`, the
   four cursor gestures, `onSuggestionsAffectingCursorMove` +
   `refreshSuggestionBandAfterCursorMove`, `maybeOfferTatarSuggestions`,
   `isSuggestionsEligible(boolean)`, `mayLearnPersonalWords`,
   `onDestroy`, the `UIHandler` (`removeMessages(` must live in a file NAMED
   LatinIME.java, HandlerMessageIdSourceContractTest).
2. **Region/order-pinned**: `onCreate` … `loadSettings`;
   `setUpEmojiPanelController` … `setUpSuggestionsOffer`;
   `showForgetPersonalWordDialog` … `showSuggestionsUnavailableDialog`;
   `maybeOfferTatarSuggestions` … `// A helper method to split the code point`;
   `onFinishInputViewInternal` … `deallocateMemory` … `onUpdateSelection`.
3. **Contains-pinned** (the string must be somewhere in the file):
   `tatarEngine ? FuzzyEditPolicy.TATAR : null`, both `sinkFor(` wirings with
   `this::mayLearnPersonalWords`, exactly two `() -> Settings.\w+(mDevicePrefs)`,
   `PersonalDictionaries.sourceFor(`, the verbatim two-line quarantine-listener
   registrations, `attachDialogToInputWindow(dialog, windowToken)` sites,
   `mRichImm.setCurrentSubtype(primaryHintLocale)`,
   `outInsets.touchableRegion.setEmpty()`, three — exactly three —
   `mSuggestionsController.onSelectionChanged()` call sites, and the absence of
   a bare `"tt_RU"` literal and of every store-mutation verb.

Two named candidates from the task turned out to be **blocked by the pins, not
merely unpinned-but-expensive**: the settings-change listener block and the
key-neighbor memo both call `isSuggestionsEligible(...)`, whose DECLARATION text
(`private boolean isSuggestionsEligible(final boolean`) is a pinned substring
(PersonalLearningGatesTest slices from it) — widening it to package-visible
would break the pin, so neither caller can leave the class. They stay, recorded
here as evaluated-and-rejected, not overlooked.

### What was extracted where

| Unit | New file | Lines | Rationale |
|---|---|---|---|
| D3 interception pair `maybeAutocorrectTatarWord` + `maybeRevertTatarAutocorrection` | `LatinImeAutocorrect.java` | 88 | The two probes that run before the input logic sees the event; one feature, one seam, both unpinned. Bodies verbatim with an `ime` parameter. |
| Key feedback `hapticAndAudioFeedback` + `hapticTickFeedback` + the repeat-period constant | `LatinImeKeyFeedback.java` | 65 | Pure audio/haptic feedback over `AudioAndHapticFeedbackManager`; the constant was used by nothing else. |
| Soft-input window: `updateSoftInputWindowLayoutParameters` + `onInputGeometryChanged` + `setNavigationBarColor` | `LatinImeSoftInputWindow.java` | 98 | The window's layout and appearance bookkeeping; the only users of five imports (`Gravity`, `ViewGroup.LayoutParams`, `WindowInsetsController`, `ResourceUtils`, `ViewLayoutUtils`), which left with it. |
| Emoji-search routing `maybeRouteToEmojiSearch` + `updateEmojiSearchView` | `LatinImeEmojiSearch.java` | 84 | The single seam that types into the query; its KDoc travels. The `mEmojiSearchQuery` FIELD stays on the service: `refreshSuggestionBandAfterCursorMove`'s pinned body reads it there. |

The seam shape is uniform and matches part 1's "mechanical minimum": each
helper is a package-private `final class` with static methods taking
`final LatinIME ime`, so bodies moved verbatim with `ime.` prefixes and
evaluation order is unchanged by construction. Three fields went
`private` → package-private to make that legal: `mSuggestionsController`,
`mInputView`, `mEmojiSearchQuery`. No method signature, no evaluation order,
no call sequence changed.

### Line counts

| File | Before | After |
|---|---|---|
| `LatinIME.java` | 2 440 | **2 254** |
| `LatinImeAutocorrect.java` | — | 88 |
| `LatinImeEmojiSearch.java` | — | 84 |
| `LatinImeSoftInputWindow.java` | — | 98 |
| `LatinImeKeyFeedback.java` | — | 65 |

### The size target (documented exception)

The phase asks for ≤ ~1 500 lines or a documented exception. The file lands at
**2 254** and the exception is recorded here. Of the 2 254 lines that remain,
the quantitative breakdown of why they cannot move:

- ~700 lines are **body-pinned text** (the dialog block 342, UIHandler 176,
  the pinned lifecycle bodies) — moving them fails a test, and the phase rule
  is that a broken pin means the extraction was wrong;
- ~590 lines are the **wired-at-onCreate surfaces** (`setUpSuggestionsController`
  302, `setUpEmojiPanelController` 70, `setUpSuggestionsOffer` 109, plus their
  pinned fragments elsewhere) — every one carries at least one contains-pin or
  body-pin, and the two that don't fully (offer environment) would turn a
  living pin vacuous, which the discipline forbids equally;
- ~410 lines are **`@Override` framework entry points** (`onStartInputView`,
  `onComputeInsets`, `onConfigurationChanged`, `onWindowShown/Hidden`, the
  `KeyboardActionListener` methods, …) — an override of `InputMethodService`
  cannot leave the service class at all;
- the remaining ~550 lines are the event spine (`onEvent`, `onTextInput`),
  the shared state readers (`getCurrentAutoCapsState`, `isSuggestionsEligible`,
  `activeDictionarySubtype`, `mayLearnPersonalWords`) that the pinned text
  itself calls, and ~20-line helpers whose extraction would be churn, not
  cohesion — part 1's rejected "bag of public fields" in another shape.

A partial clean result was chosen over a forced one, per the phase's own rule.

### Gates (2026-09-23)

| Gate | Result |
|---|---|
| `./gradlew test` after EACH of the four extractions | 1 456 tests, 0 failures, every time |
| `./gradlew test --rerun-tasks` (final, honest run) | 1 456 tests, 0 failures — identical count, suite untouched |
| python suites (`tests/*/test_*.py`) | all pass, untouched |
| `./gradlew lintRelease` | BUILD SUCCESSFUL (0 errors, 4 warnings — the same pre-existing environmental ones) |
| `assembleRelease -PskipReleaseSigning` size | 1 856 116 B (part-1 tree: 1 855 716 B; +400 B = the four new class shells and their metadata) — ≤ 3 145 728 budget holds |
| `check-no-internet.sh` (source + built APK) | both levels OK |
| AGENTS.md counters | unchanged (1 456 was already the recorded count) |

Housekeeping inside the moved code: the five window-only imports left
LatinIME.java with their unit; the two `{@link #maybeRouteToEmojiSearch}`
cross-references were re-pointed to `{@link LatinImeEmojiSearch#…}` so the
remaining comments still name the right class.

## Part 3 — splitting SettingsHostActivity.kt and EmojiPanelView.kt (done 2026-09-23)

Baseline before the part: JVM 1 456 tests, python 484 tests, the part-2 tree
(uncommitted). SettingsHostActivity.kt was **1 568** lines (phase 5 had added the
keyboard-height presets to it since the roadmap's 1 293), EmojiPanelView.kt
**1 317**. The same rules: pure moves, `./gradlew test` green with the identical
1 456 after EVERY extraction, every comment travels with its unit, no commits.

### What makes these two files different from parts 1–2

Both files are pinned harder than either predecessor. Before touching a line,
every test reading them was mapped (`grep -rl` over `app/src/test`):
**16 test files read SettingsHostActivity.kt**, **6 read EmojiPanelView.kt**.
The pins fall into the same three strengths as part 2:

1. **Body/region-pinned** (a function body, sliced by its signature, must
   contain exact strings IN THIS FILE): in the activity — the whole
   personal-dictionary screen (`buildPersonalDictionaryScreen`, both quarantine
   card builders, all six store dialogs, `afterPersonalMutation`,
   `applyPrivateInputFlags`, `textInputRow`, `usageRow`), `buildPreferencesScreen`
   (incognito/personal/emoji-suggest switch text), `buildAppearanceScreen` +
   the keyboard-height row (`switchRow(Settings.PREF_SHOW_EMOJI_KEY, true`,
   `setItems(labels)`, `KeyboardHeightPresets.SCALES[which]`, …),
   `buildDataSourcesScreen`, `buildRootScreen`, `showClearRecentEmojiDialog`,
   `openUrl`, `onCreate`/`onDestroy`/`onSaveInstanceState`, the
   `prefChangeListener`; in the view — `onDraw`…`@Suppress` (the visible-rows
   tokens live in `drawContent`'s loop), `onTouchEvent`…`onVisibilityChanged`,
   `release`/`releaseSnapshotCaches` before `onMeasure`, `dispatchTarget` before
   `cancelDeleteRepeat`, `computeScroll` before `onDraw`, the whole
   accessibility helper (`getVisibleVirtualViews`/`onPopulateNodeForHost`/
   `onPopulateNodeForVirtualView`/`onPerformActionForVirtualView` region chain),
   `activateForAccessibility` before the `scrollOneViewport` KDoc.
2. **Count-pinned**: `AlertDialog.Builder(` == `DialogUtils.filterObscuredTouches(`
   in the activity (10 == 10 after the split — the locale picker took one pair
   with it, verbatim, so the balance and the property both hold);
   exactly one `.invalidateRoot()`, one `OverScroller(`, one
   `VelocityTracker.obtain(` in the view.
3. **Contains/forbidden-pinned** everywhere else (switch texts, quarantine
   invalidations, no-`RichInputMethodManager.getCurrentSubtype()`, no recents
   reads, no `CODE_SPACE`/`Bitmap`/`SharedPreferences` in the view, …).

The consequence is the shape of the extraction: whatever is pinned stays, and
what moves must leave every call site textually identical.

### The seam shape

Both targets are Kotlin, so the "mechanical minimum" is the **internal
extension function**: `internal fun SettingsHostActivity.buildLanguagesScreen()`
resolves at the old call site `buildLanguagesScreen()` with no text change, and
the moved bodies read `this`'s members exactly as before. The price is the
visibility floor: an extension in another file cannot see a private member, so
the members the moved units touch went `private` → `internal` (the activity:
`prefs`, `richImm`, `contentView`, `currentDialog`, `detailLocale`,
`restrictionKeys`, `navigateTo`, `showScreen`, the `Screen` enum, companion
constants `DISABLED_ALPHA`/`PERCENTAGE_FLOAT`; the view: `state`, `scroller`,
the paints/metrics/px fields the painters read, `popupVariants`, `searchHint`,
`tabLabels`, `hasRecentTab`, `skinTones`, `longPressRunnable`,
`longPressTimeoutMs`, companion `BACK_LABEL`/`DELETE_LABEL`/`SECTION_JUMP_MS`,
and the companion object itself — a `private companion` is invisible from
another file). Companion members referenced from the new files are qualified
(`SettingsHostActivity.PERCENTAGE_FLOAT`, `EmojiPanelView.BACK_LABEL`,
`EmojiPanelView.SECTION_JUMP_MS`) — the only textual delta inside moved bodies,
the same role the `ime.` prefix played in part 2. No method signature, no
evaluation order, no call sequence changed.

### What was extracted where

SettingsHostActivity.kt (1 568 → **1 129**):

| Unit | New file | Lines | Rationale |
|---|---|---|---|
| Row builders (`inflateRow`×2, `linkRow`×2, `textRow`, `actionRow`, `switchRow`, `switchRowRaw`, `valueRow`) + card scaffolding (`addSectionHeader`, `addCard`, `setRowEnabled`, `isRestricted`, `dp`) | `SettingsRows.kt` | 218 | The shared row vocabulary every screen speaks; unpinned as declarations (the pins are on the CALL text, which did not move). |
| Languages screens (`buildLanguagesScreen`, `showLocalePickerDialog`, `buildLanguageDetailScreen`) | `SettingsLanguagesScreens.kt` | 184 | One feature cluster, zero pins. The locale picker carries its `AlertDialog.Builder`+`filterObscuredTouches` pair, so the dialog contract's balance holds with the file's totals 11 → 10. |
| Key-press screen + the three seek-bar proxies (`buildKeyPressScreen`, `keypressSoundVolumeProxy`, `keyLongpressTimeoutProxy`, `bottomOffsetProxy`) | `SettingsKeyPressScreen.kt` | 138 | One screen plus its value plumbing. `bottomOffsetProxy` rides along although its caller (Appearance) stays — the appearance screen is pinned, the proxy is not. |

EmojiPanelView.kt (1 317 → **1 168**):

| Unit | New file | Lines | Rationale |
|---|---|---|---|
| The painters: `drawSkinTonePopup`, `drawTabRow`, `drawClockIcon`, `drawSearchBar`, `drawFloatingKeys` | `EmojiPanelDrawing.kt` | 155 | Pure `EmojiPanelState`-geometry → canvas painters. `drawContent` deliberately STAYS: the "only the visible rows are drawn" tokens the contracts pin live in its loop, and the draw region is sliced `onDraw`…`@Suppress`. |
| The gesture helpers: `maybeArmLongPress`, `cancelSkinTonePopupTimer`, `maybeJumpSection` | `EmojiPanelGestures.kt` | 51 | The long-press arming and the section jump; unpinned. `obtainVelocityTracker`/`recycleVelocityTracker`/`maybeFling` stay: they carry the pinned `VelocityTracker.obtain(`/`velocityTracker?.recycle()`/`EmojiFling.*` text. |

### The size targets (recorded rationale)

The part-3 brief asks for ≤ ~1 000 lines with a recorded rationale. The
activity lands at **1 129**, the view at **1 168**, and the rationale is the
pin map: everything that remains in either file is pinned text or the state it
operates on. In the activity the five personal-dictionary contract suites alone
body-pin ~480 lines (screen, quarantine cards, dialogs), and the preferences /
appearance / data-sources / root screens plus the lifecycle core are pinned by
eight more suites. In the view the region-pinned spine (touch body, content
walker, the accessibility helper, the release sequence) plus the theme/paint
state that cannot leave the class account for the remainder; the only unpinned
blocks larger than a helper were the ones extracted. A partial clean result
over a forced one, per the phase's rule.

### Line counts

| File | Before | After |
|---|---|---|
| `SettingsHostActivity.kt` | 1 568 | **1 129** |
| `SettingsRows.kt` | — | 236 |
| `SettingsLanguagesScreens.kt` | — | 182 |
| `SettingsKeyPressScreen.kt` | — | 138 |
| `EmojiPanelView.kt` | 1 317 | **1 168** |
| `EmojiPanelDrawing.kt` | — | 165 |
| `EmojiPanelGestures.kt` | — | 58 |

### Gates (2026-09-23)

| Gate | Result |
|---|---|
| `./gradlew test` after EACH of the five extractions | 1 456 tests, 0 failures, every time |
| `./gradlew test --rerun-tasks` (final, honest run) | 1 456 tests, 0 failures — identical count, suite untouched |
| python suites (`tests/*/test_*.py`) | all pass, untouched |
| `rebuild_assets.py --check --allow-known-drift` | PASS (`ok: true`) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL (0 errors, 32 baselined + 4 pre-existing live warnings — unchanged) |
| `assembleRelease -PskipReleaseSigning` size | **1 857 224 B** (part-2 tree: 1 856 116 B; +1 108 B = the five new file shells) — ≤ 3 145 728 budget holds |
| `check-no-internet.sh` (source + built APK) | both levels OK |
| AGENTS.md counters | unchanged (1 456 was already the recorded count) |

Housekeeping inside the moved code: the imports that only the moved units used
left with them (`Locale`/`TreeSet`/`LocaleUtils`/`SubtypeLocaleUtils`/`Switch`
to the languages file, `AudioManager` to the key-press file, `ViewGroup`/
`CompoundButton`/`AccessibilityNodeInfo` to the rows file); the activity's and
the view's class KDocs now name their satellite files. The settings
`Screen` enum gained a two-line comment saying why it is `internal`.

## Phase close-out — T2 verdict

The roadmap's done-when: *no file above a negotiated ceiling (1 500 lines)
without a recorded exception; full gates; device UAT.*

| File | Roadmap size | Final size | Verdict |
|---|---|---|---|
| `SuggestionsController.kt` | 2 100+ | 2 000 | Above the ceiling with the recorded part-1 exception: the coordination core of the band/session state; every further cut demonstrated to be indirection without cohesion. |
| `LatinIME.java` | 2 331 | 2 254 | Above the ceiling with the recorded part-2 exception: ~700 lines are body-pinned text, ~590 the wired-at-onCreate surfaces, ~410 framework overrides; the rest is the event spine. |
| `SettingsHostActivity.kt` | 1 293 (1 568 by phase 5) | 1 129 | Under the 1 500 ceiling. Above the ~1 000 part-3 target with the recorded rationale: sixteen contract suites pin the remaining text. |
| `EmojiPanelView.kt` | 1 317 | 1 168 | Under the ceiling outright. Above the ~1 000 part-3 target with the recorded rationale: the region-pinned spine and the unpinnable view state. |

All four god objects are split to their pin-honest floors, each extraction a
pure move with the suite green at the identical 1 456 after every step; the
full gates are green on the final tree. Device UAT and the final
baseline-profile regeneration remain the phase's separate work, per the
roadmap's working agreements.

---

# Final block: baseline profile + gates + UAT (2026-09-24)

## Baseline profile regenerated

`ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile` (the serial
pin keeps the physically-connected POCO C71 out of the connected-test matrix — its MIUI
build fails the profile-save broadcast, recorded in ROADMAP-P1). BUILD SUCCESSFUL;
generated to `app/src/release/generated/baselineProfiles/baseline-prof.txt` and promoted
to the committed `app/src/main/baseline-prof.txt` per the module's documented flow.

| Metric | Before (post-P1) | After |
|---|---:|---:|
| lines | 2 900 | 3 071 |
| rules | 2 433 | **2 561** (+128) |
| diff lines (+/−) | — | +341 / −170 (511 total) |
| rules naming the extracted classes (LatinIme*, CleanRunMachine, RevertWindow, AutocorrectPreview, LanguageSlot, SuggestionPreparation, SuggestionSurfaces, EmojiPanel*, Settings*) | 0 | **113** (e.g. `LatinImeAutocorrect.maybeAutocorrectTatarWord` / `maybeRevertTatarAutocorrection` present) |

The refactor's new classes ARE hot at startup and now carry profile rules — exactly what
this regen was for.

## Full gates (final tree, all 2026-09-24)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **EXACTLY 1 456 tests, 0 failures / 0 errors / 0 skipped** (150 suites) — the refactor added no tests, as required |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 852 804 B** → signed zopfli **1 834 276 B** ≤ 3 145 728, SHA-256 **`f6ec74bc3687317f63c69b759f07ff95e0b145810455ea73e10497eb6bab73ca`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** (version 2.0.1/33, changelog on the existing 33.txt) |

## UAT — the refactor changed nothing

**Device leg**: the POCO C71 was CONNECTED at session start but **in active use by its
owner during the drive** — mid-session the screen went landscape, Gboard's emoji panel
replaced the IME window twice and `default_input_method` flipped to Gboard twice within
minutes (measured: `mCurMethodId` reads). Every screenshot taken against that state is
contaminated; after three recovery rounds the honest call was the documented fallback.
Device rows are **BLOCKED** (owner in active use), not passed. The mission-critical
evidence — that the pure-move refactor changed no behavior — is layout-independent, so
the emulator replay below carries it.

**Emulator fallback** (debug build of the SAME tree, `tt_suggest_a14`, Android 14,
1080×2280; suggestions on via run-as; evidence `build/device-uat-2026-09-24/p6/`, 55
files):

| # | Scenario | Result | Evidence |
|---|---|---|---|
| E1 | Field start → **Бу · Ул · Ә** (capitalized sentence start) | PASS | `e04-strip.png` (e01 raced the async load — noted) |
| E2 | `сцлэм` → **сәләм** in cell 1 (typo class #4) | PASS | `e02-sclam-strip.png` |
| E3 | tap сәләм → immediately **сәләмә · һәм · белән** (tap-followup + fallback) | PASS | `e03-chain-strip.png` |
| E4 | `татар` prefix → татарлар · татарча · татарлары (same-stem) | PASS | `e05-tatar-prefix-strip.png` |
| E5 | `татар`+space → теле · дәүләт · телен (bigrams) | PASS | `e06-tatar-space-strip.png` |
| E6 | `татар, ` → теле · дәүләт · телен (after-comma) | PASS | `e07-comma-strip.png` |
| E7 | `сәләм`+space → сәләмә · һәм · белән (fallback; no personal pair — fresh store, matching the cleared device) | PASS | `e08-salam-strip.png` |
| E8 | `сәлам`+space → биреп · белән · 👋 (emoji tail) | PASS | `e09-tail-strip.png` |
| E9 | ru: `майор` → майора · майором · майору; `тюлень`+space → я · не · в | PASS | `e10-strip.png`, `e11-strip.png` |
| E10 | Emoji panel open → 😀 committed into the field; rotation landscape↔portrait with content intact | PASS | `e12-emoji-panel.png`, field readback `&#128512;`, `e13-landscape.png` / `e14-portrait-back.png` |
| E11 | **Autocorrect preview on the refactored tree**: toggle ON → `йорәк` → preview [йорәк \| **йөрәк** (bold/accent/underlined) \| empty]; space applies («йөрәк »); one backspace reverts («йорәк »); toggle OFF restored | PASS | `e16-strip.png`, field readbacks |
| E12 | Cold start ×3 (emulator, debug — informational): SetupActivity 409/372/377 → median **377 ms**; crash buffer EMPTY; no FATAL/ANR | PASS | `e17-coldstart-emu.txt`, `e18-logcat-crash.txt`, `e19-logcat-full.txt` |

The device-leg reminders for when the phone is free: the same core table on hardware,
cold start ×3 < 400 ms, crash buffer. Nothing in this changeset is user-visible (a pure
refactor) — the emulator evidence plus the identical 1 456-test count is the proof
shape the phase asked for.

## DONE-WHEN audit (Phase 6 of `docs/ROADMAP.md`)

- **T2 (split the god objects)** — done per the three part sections above; every
  extraction a pure move, gates byte-green, count unchanged (1 456) at every step.
- **Final baseline-profile regeneration** — done today (2 433 → 2 561 rules; the
  extracted classes present and hot).
- The phase adds no user-visible feature; CHANGELOG carries a single internal note.
- Open: the operator's commit/release decision (2.6.0); the device-leg UAT rows when the
  phone is free; roadmap Phase 7.
