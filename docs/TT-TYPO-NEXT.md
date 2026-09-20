# TT-TYPO-NEXT — mission report: typo recovery in the strip and next-word prediction after a suggestion tap

Status: Phase A done (2026-09-20). Phase B measured: gates G1/G2 failed — edit class #2
rejected. Phase C measured: gates G1-C/G2-C/G3-C failed — class #4 rejected on FIRST
measurement. Phase C2 (2026-09-20, orchestrator amendment): the cross-set ratio and fill-rate
gates were replaced by a same-set absolute lift and an activation-rate cap, and the probe path
was re-engineered — **all corrected gates passed and class #4 SHIPS in the Tatar engine
(FuzzyEditPolicy.TATAR = {1, 4} + same-length bonus, gated on exact==0 ∧ >= 4 cp). Typing
"сцләм" now offers "сәләм" in cell 1 by the 5th letter — mission DONE-WHEN-2 achieved.** The
Russian engine runs DEFAULT, bit-identical to pre-Phase-B. Phase D (full gates, release APK,
device UAT, docs) done 2026-09-20 — all four DONE-WHEN items met; the changeset is
uncommitted and awaits the operator's commit/release decision (no version bump).
Plan: `docs/TT-TYPO-NEXT-PLAN.md` (approved; execution order A → D, each phase
has its own "Done when"). This file is the mission report the plan refers to:
measured numbers, contract amendments and gate results land here, phase by
phase. The plan itself is never rewritten — only annotated with dated
footnotes.

## DONE WHEN (mission level, mirrored from the plan)

1. After tapping a suggestion, the strip immediately shows next-word
   predictions for the committed word (bigram successors first, then word
   forms, sentence-start where applicable) — the same content as after a
   manually typed word + space. Pinned by JVM tests and device UAT evidence.
2. Typing `сцләм` offers `сәләм` in the strip — pinned by a JVM test on the
   real shipped Tatar asset and by device UAT.
3. Typo recovery is extended only through the calibration gates defined in the
   plan BEFORE measuring: recovery lift vs the class-#1-only baseline, a
   precision guardrail on correctly-typed prefixes, and perf budgets on host
   and on the device.
4. All gates pass: python suites, `./gradlew test`, `lintRelease`,
   `check-no-internet.sh`, APK ≤ 3 145 728 bytes,
   `rebuild_assets.py --check --allow-known-drift`, and the eval metrics of
   `TtSuggestEvalTest` do not regress.

Phase A advances item 1 (JVM side; the device evidence arrives with the next
emulator/device run of the extended smoke).

## Phase A — next-word predictions after a suggestion tap (done 2026-09-20)

### Root cause (confirmed)

A tap-commit was the only text change that never notified the suggestions
controller. `SuggestionsController.onTap`
(`app/src/main/java/.../suggestions/SuggestionsController.kt`) only cleared the
strip after a successful commit, and the asynchronous backstop
`onCursorMoveSettled` self-cuts on `requestSessionId == sessionId` because a
tap does not bump the session — so the band stayed empty until the next
keystroke. Manual word + space works because
`LatinIME.updateStateAfterInputTransaction` calls `onTextChanged()`.

Confirmed by test inversion: with the fix reverted (the one-file change
stashed), exactly the five new behavior pins fail and the other 83 tests of the
two touched suites stay green:

- `acceptedPrefixSuggestionIsFollowedByNextWordPredictionsForTheAcceptedWord`
- `tapOnNextWordPredictionChainsPredictionsForTheNewlyCommittedWord`
- `lateCursorMoveSettledAfterATapDoesNotDuplicateTheFollowUpRequest`
- `commitWithoutAutoSpaceFallsIntoThePrefixPathForTheCommittedWord`
- `theBandAfterAnAcceptedWordCellStillCarriesTheEmojiTail`

### Fix

In `SuggestionsController.onTap`, after a SUCCESSFUL commit in BOTH branches
(prefix-suggestion tap and next-word-prediction tap), `requestCurrentPrefix()`
is called after the existing cleanup (`displayed* = null`,
`bandBaseCells = emptyList()`, `clearCompanionRequest()`, `strip.reserve()`).
The editor's text cache is synchronously current at that point
(`InputLogic.replaceTrailingWord` / `commitPredictedWord` update the
RichInputConnection cache inside the same batch edit), so the follow-up lookup
describes the text the commit just left:

- with the auto-space appended, the trailing word is empty and the request
  falls into the NEXT_WORD path for the word just committed;
- without it (`needsAutoSpace` false — the text after the cursor already
  separates the word), the committed word is the new trailing prefix and the
  request is an ordinary PREFIX one, exactly as after typed input.

Deliberately NOT done: no session bump (the commit is part of the current
session's text, and a late `onCursorMoveSettled` must keep self-cutting), no
touch of the revert window beyond what `onTap` already did, no request after a
refused commit (`commitSuggestion`/`commitPredictedWord` returning false).

Invariants, each pinned by a test:

- no double learning: `markRunDirty()` already ran at the top of `onTap`, so a
  tapped word never counts as typed (`PersonalLearningRunTest.
  anAcceptedSuggestionMakesTheRunDirty` stays green unchanged);
- revert-autocorrect window untouched (`AutocorrectControllerTest`, including
  `anySixthEventBetweenReplacementAndBackspaceMakesRevertImpossible`, stays
  green unchanged);
- late `onCursorMoveSettled` after the tap no-ops, both while the follow-up
  request is in flight (`requestSessionId == sessionId` guard) and after the
  answer landed (band bound) — new pin
  `lateCursorMoveSettledAfterATapDoesNotDuplicateTheFollowUpRequest`;
- repeat tap while the request is in flight is a no-op (both `displayed*` are
  null) — pinned inside the amended contract test;
- the NEXT_WORD-tap branch chains predictions — new pin
  `tapOnNextWordPredictionChainsPredictionsForTheNewlyCommittedWord`;
- a refused commit issues no request on either branch — new pin
  `refusedCommitsIssueNoFollowUpRequest`;
- no auto-space → PREFIX path — new pin
  `commitWithoutAutoSpaceFallsIntoThePrefixPathForTheCommittedWord`;
- emoji tail / personal merge / companion fill unaffected — the dedicated
  suites stay green, plus the new pin
  `SuggestionsControllerEmojiSuggestTest.
  theBandAfterAnAcceptedWordCellStillCarriesTheEmojiTail`: a band built by the
  post-tap follow-up request gets the emoji tail exactly like a band built
  after typed input.

### Amended contract

The frozen E5 contract (`docs/archive/PROPOSALS.md`, "## E5", line 4092 of the
archived file) reads: after an ACCEPTED or typed word and a space, the strip
shows up to three likely continuations instead of staying empty. Predictions
after an accepted word were therefore the intent all along; the empty strip
was a defect, not a design.

The D1-era test `bandStaysEmptyAndVisibleAfterTheAutoSpacedCommitEndsTheWord`
(`SuggestionsControllerTest.kt`) pinned the pre-NEXT_WORD behavior ("whatever
text event follows must not repaint the old words" and nothing replaces them).
It is amended — renamed to
`acceptedPrefixSuggestionIsFollowedByNextWordPredictionsForTheAcceptedWord` —
to the E5 contract behavior: the tap-commit issues a NEXT_WORD request for the
accepted word immediately, the band stays reserved and visible throughout, a
repeat tap in flight commits nothing, and the arriving answer paints the
accepted word's successors. Per the contract-amendment discipline this is
recorded here; `docs/archive/` is untouched.

### Test-harness change

`SuggestionsControllerTest.FakeEditor` now models the real synchronous
RichInputConnection behavior on a successful commit (both insertion paths):
the committed word takes the cursor, and the auto-space rule decides the
shape — with the space, the trailing word is empty and the committed word
becomes the NEXT_WORD context; without it, the committed word is the new
trailing word. A refused commit changes nothing, as before.
`SuggestionsControllerEmojiSuggestTest.FakeEditor.commitPredictedWord` got the
same modeling (an emoji cell commits no letters, so it yields no context).
Every pre-existing tap test was re-checked against the modeling: all keep
their intent and stay green.

### Emulator smoke

`scripts/emulator-smoke.sh`: the existing сакчы word-form probe is extended by
a second middle-cell tap. The first tap commits an inflected form (сакчылар on
the shipped assets) with a trailing space; the follow-up band for сакчылар is
its after-word forms (сакчылары / сакчыларына / сакчыларын — сакчылар is not a
bigram head), so a second cell-2 tap must commit one more сакчы* word, proven
by reading the try-it field (tap-and-read, the script's convention — the IME
window is invisible to uiautomator). New RESULT line: `tap-followup-tt-сакчы`
(PASS/FAIL, SKIP when suggestions are off or the base probe already failed).
Verified offline against the shipped assets: сакчылар ∉ bigram heads; its
generated forms present in the dictionary are сакчылары (1134), сакчыларына
(161), сакчыларын (81), сакчыларга (37), сакчыларны (28), сакчыларның (18),
сакчыларында (13), сакчыларыннан (21) — the follow-up band is non-empty by
construction. Syntax-checked with `bash -n`; the on-device run is deferred
(no emulator boot in this phase) and lands with Phase D's UAT.

### Gates (2026-09-20)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | 1196 tests (was 1191; +5), 0 failures/errors/skips |
| python suites (`tests/*/test_*.py`) | 15 files, 455 tests, all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | 1 867 911 B ≤ 3 145 728 B |
| `bash -n scripts/emulator-smoke.sh` | OK |

### Files touched

- `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt`
  — the two-line fix (one `requestCurrentPrefix()` call per successful onTap
  branch) with the reasoning comment.
- `app/src/test/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsControllerTest.kt`
  — FakeEditor synchronous-commit modeling; the D1-era test amended; four new
  pinned tests.
- `app/src/test/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsControllerEmojiSuggestTest.kt`
  — same commit modeling in the fake; one new pinned test (emoji tail on the
  follow-up band).
- `scripts/emulator-smoke.sh` — the tap-followup probe + header note.
- `docs/TT-TYPO-NEXT-PLAN.md` — dated Phase A footnote.
- `AGENTS.md` — JVM test counter 1191 → 1196.
- `docs/README.md` — index lines for the plan and this report.

## Phase B — typo recovery v1: geometric class + same-length bonus (measured 2026-09-20, verdict: NOT SHIPPED)

Written gates (docs/TT-TYPO-NEXT-PLAN.md, fixed BEFORE measuring): G1 recovery lift ≥ 1.5× the
class-#1-only baseline on both the 3-cp and the 5-cp window; G2 fuzzy pollution ≤ 2 % on ≥ 1 000
correctly-typed prefixes; G3 host p95 ≤ 5 ms, ≤ 8 B/lookup, device compute smoke within its
existing input gate.

### Design that shipped (machinery, not behavior)

1. **Device-true offline geometry.** The pre-Phase-B offline model packed keys edge-to-edge, so
   same-row keys "touched" (`right == left`) and the model held 65 pairs. On a real build
   `KeyboardRow` subtracts the horizontal gap (`config_key_horizontal_gap`, 1.739 %p on a phone)
   from every key's width and advances the next key by the full padded width, so `right < left`
   for every same-row pair — all 33 same-row pairs never exist on device and exactly the 32
   cross-row pairs survive. `scripts/typo_pack.py` now reproduces the device formula
   (`KeyboardBuilder`/`KeyboardRow`/`Key`: baseWidth = W − pads + gap, the gap cancels in the
   x-advance, one half-up rounding per edge) on a 100 000-px reference grid; the gap/padding
   fractions are read from `res/values/config.xml`, never hard-coded. **Proof the offline model
   now equals the device**: the Phase-B instrumentation run on the POCO C71 (720×1640) built the
   live keyboard through the production path (`KeyboardLayoutSet` + `ContextThemeWrapper(
   KeyboardTheme.Tatar)`) and dumped the derived relation — 32 pairs, byte-for-byte the offline
   set (evidence: `build/tt-typo-next-phaseB/device-instrumentation-logcat.txt`; e.g. й rect
   x=6 w=53, ц x=72 w=53 — the 13-px gap between right(ц)=125 and left(у)=137 kills the same-row
   touch, while the ц↔ә cross-row overlap of 42 px keeps the mission pair). The pair set is
   additionally proven identical across all six shipped `values*/config.xml` variants and a
   320–4000 px width sweep (python test
   `DeviceGeometryModelTest.test_pair_set_is_identical_across_all_shipped_configs_and_widths`).
2. **Per-engine fuzzy policy.** The global `TdictPrefixIndex.SHIPPED_FUZZY_EDIT_CLASSES` constant
   is replaced by `FuzzyEditPolicy` (edit-class set + same-length-bonus flag), injected through
   `TdictPrefixIndex.open` ← `MappedDictionaryEngine.start` ← `MappedEngineHandle.start` — the
   same seam as the P3 suffix rules. `FuzzyEditPolicy.DEFAULT` (class #1 only, no bonus) is
   bit-identical to the pre-Phase-B shipped behavior and is what EVERY shipped engine runs;
   `FuzzyEditPolicy.TATAR` (classes #1+#2 + bonus) is the calibrated candidate, currently unwired
   (see the verdict). Class #3 (transposition) was never re-calibrated and has no policy.
3. **Same-length bonus** (part of the policy): a fuzzy candidate whose length equals the typed
   prefix length ranks before its own continuations within its edit class (frequency order
   otherwise preserved; the bonus never crosses classes and never touches the exact level).
   Detection is allocation-free: the candidate's remainder past the variant is empty iff the
   candidate IS the variant, and a substitution variant has the typed prefix's code-point length
   — so no code-point counting runs on the hot path. Ranking is packed into the existing int key
   (`class * 2 − bonus`), keeping the zero-allocation and class-first invariants.
4. **Typo window parameterized**: `typo_pack.py build --prefix-code-points N` (default stays 3 =
   the engine's `MIN_FUZZY_PREFIX_CODE_POINTS`); the 5-cp window is the сцләм case.

### Regenerated set identities (device-true geometry, committed 110k asset)

| Set | Rows | SHA-256 |
|---|---:|---|
| class #1, 3-cp | 96 118 | `1bf09f40…8e9aee` (unchanged — no geometry involved) |
| class #1, 5-cp | 102 478 | `1c0bd7e7…53c6` |
| class #2, 3-cp | 109 649 | `f64f4650…dce9d3` (was `89ef2646…` under the wrong model) |
| class #2, 5-cp | 104 955 | `165dbaac…42d0f` |
| class #3, 3-cp | 109 637 | `539a701a…f5a42` (unchanged) |

The JVM side rebuilds all sets from `E3bTestFixtures` (updated to the same device-true grid) and
asserts the same SHAs — the cross-implementation equality proof
(`TtTypoPhaseBCalibrationTest.thePhaseBSetsAreByteIdenticalToTheGeneratorRuns`).

### G1 — recovery (verdict: **BELOW on both windows — gate FAILED**)

Raw lines (`TtTypoPhaseBCalibrationTest.gateG1RecoveryAtThreeOnBothWindows`):

```
PhaseB recovery@3 window=3cp baseline(class1-only, class1 set)=6.6959% (6436/96118) tatarPolicy(classes1+2+bonus, class2 set)=6.0867% (6674/109649) ratio=0.9090x gate>=1.5x verdict=BELOW noBonus=6.0858% (6673) variant_p95=9 variant_max=12 visited_p95=571 visited_max=1059 over_budget=0
PhaseB recovery@3 window=5cp baseline(class1-only, class1 set)=38.3926% (39344/102478) tatarPolicy(classes1+2+bonus, class2 set)=38.4641% (40370/104955) ratio=1.0019x gate>=1.5x verdict=BELOW noBonus=38.4727% (40379) variant_p95=14 variant_max=20 visited_p95=90 visited_max=304 over_budget=0
```

| Window | Baseline (class #1 only, class-#1 set) | Candidate (classes #1+#2 + bonus, class-#2 set) | Ratio of rates | Gate ≥ 1.5× |
|---|---:|---:|---:|---|
| 3 cp | 6.6959 % (6436/96 118) — exactly the handed-down E3b reference ✓ | 6.0867 % (6674/109 649) | **0.909×** | FAIL |
| 5 cp | 38.3926 % (39 344/102 478) | 38.4641 % (40 370/104 955) | **1.002×** | FAIL |

Attribution: the same two classes WITHOUT the bonus recover 6673 (3-cp) / 40 379 (5-cp) — the
bonus moves recovery by +1/−9 rows, i.e. it is a ranking feature (correction first), not a
recovery feature. The fuzzy budgets never trip (`over_budget=0`; variant p95 9/14, visited p95
571/90 — all far under `MAX_FUZZY_VARIANTS=64` / `MAX_FUZZY_VISITED=8192`).

Why the gate fails is the E3b diagnosis confirmed on the device-true sets: a one-edit correction
competes for the ≤ 3 empty cells against the WHOLE frequency-sorted block of the corrected
prefix (at 3 cp that block averages hundreds of words), so only targets near the top of their
own block recover — the same structural cap that made the dead 17.48 % threshold unreachable.
The measured rates are honest: no code, test, generator or gate was adjusted to move them.

### G2 — precision on correctly-typed prefixes (verdict: **ABOVE — gate FAILED**)

Raw line (`TtTypoPhaseBPrecisionTest.gateG2FuzzyPollutionOnCorrectlyTypedPrefixes`):

```
PhaseB precision prefixes=8382 baseline(class1-only)=304 (3.6268%) tatarPolicy(classes1+2+bonus)=665 (7.9337%) gate<=2% verdict=ABOVE
```

| Arm | Polluted prefixes (fuzzy non-continuation in top-3) | Share | Gate ≤ 2 % |
|---|---:|---:|---|
| baseline (class #1 only — the pre-Phase-B SHIPPED behavior) | 304 / 8 382 | 3.6268 % | (above the gate itself) |
| candidate (classes #1+#2 + bonus) | 665 / 8 382 | 7.9337 % | FAIL |

The set: all 8 382 distinct ≥ 3-cp prefixes of the 2 670 unique words of
`app/src/test/resources/tt_eval_sentences.txt` — exactly the typing states where the fuzzy pass
can fire. Two honest findings: (a) the candidate more than doubles the pollution of the
baseline, failing the gate decisively; (b) the 2 % bar is not met even by the baseline — the
gate was written against an idealized model of how rarely a correct prefix leaves an empty cell
for the class-#1 filler to occupy. Both are recorded, not tuned away.

### G3 — performance (verdict: PASS, recorded for the rejected candidate)

Host (`TtTypoPhaseBCalibrationTest.gateG3HostComputeP95WithClass2Engaged`, 3 962-lookup
deterministic sample of the class-#2 5-cp set; and
`RealDictionaryPrefixIndexTest.computeP95WithTheTatarFuzzyPolicyOverReviewPrefixesIsAtMostFiveMilliseconds`):

| Measurement | DEFAULT | TATAR (classes #1+#2 + bonus) | Budget |
|---|---:|---:|---|
| host p95, class-#2 typo set (fuzzy engaged) | 0.028 ms | **0.057 ms** (max 0.105) | ≤ 5 ms ✓ |
| host p95, 22 review prefixes (common path) | 0.005–0.009 ms region | 0.009 ms | ≤ 5 ms ✓ |
| zero-alloc, class #2 + bonus engaged | — | ≤ 8 B/lookup (same contract as the pre-Phase-B test) | ≤ 8 B ✓ |

Device (POCO C71, 720×1640, API 35 — `E3bComputeInstrumentationTest`, the project's first
instrumentation run ever executed on hardware; evidence in `build/tt-typo-next-phaseB/`):

| Measurement (2000 samples) | p50 | p95 | max |
|---|---:|---:|---:|
| review prefixes, DEFAULT policy | 0.320 ms | 0.830 ms | 1.634 ms |
| typo probes (сцл/сцлә/сцләм/сйл), DEFAULT | 0.760 ms | 0.800 ms | 0.824 ms |
| review prefixes, TATAR policy | 0.279 ms | 0.625 ms | 2.469 ms |
| typo probes, TATAR policy (100 % fuzzy-engaged) | 1.817 ms | **2.022 ms** | 2.692 ms |

The harness blocker from DICTIONARY-E3.md ("compiles but never ran") is resolved: the run needed
(a) the `androidTest/AndroidManifest.xml` declaring
`<uses-library android:name="android.test.runner" android:required="false"/>` (the legacy runner
classes live in an optional platform library on API 24+ — without it `am instrument` answers
"Unable to find instrumentation info") and (b) addressing the test package
`org.tatarkeyboard.ime.debug.test` (AGP appends `.test`). With both, `am instrument -w -e class
rkr.simplekeyboard.inputmethod.latin.dictionary.engine.E3bComputeInstrumentationTest
org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner` runs green (2 tests,
OK). The typo-probe p95 (2.022 ms) is within the harness's existing 3.5 ms input gate.

### The сцләм → сәләм evidence (both ways, on the real shipped asset)

`TtTypoPhaseBCalibrationTest.theSclamTypoOffersSyalamInCellOneByTheFifthLetter` (candidate
wiring: suffix rules + TATAR policy + layout-derived table) pins the machinery proof:

| Typed | Strip (candidate wiring) |
|---|---|
| `сц` | `[сценарий, сценарие, сценарийлар]` (exact; fuzzy never fires below 3 cp) |
| `сцл` | `[сәламәтлек, сәләтле, сәламәт]` — variant `сәл`, frequency order, no same-length word at 3 cp |
| `сцлә` | `[сәләтле, сәләт, сәләтен]` — variant `сәлә` selects the `сәлә*` block led by `сәләт*` words |
| `сцләм` | **`[сәләм, сәләмәтлек, сәләмәт]`** — variant `сәләм` selects the 8-word `сәләм*` block and the bonus puts the correction (freq 36) above `сәләмәтлек` (65) |

…`theShippedEnginesKeepThePrePhaseBBehavior` pins what users actually get after the gate
verdict (DEFAULT policy): `[сценарий, сценарие, сценарийлар]` at `сц`, empty strip at
`сцл`/`сцлә`/`сцләм`. **The mission's DONE-WHEN-2 (сәләм offered for сцләм) is therefore NOT
met in the shipped build — the machinery works and the written gates rejected it.** Phase C
(probe-first full single-substitution, gated on an empty exact pass) remains the designed path
to this case: `сцл*` returns exactly 0 exact candidates, so Phase C's activation gate would have
fired here.

The Russian negative pin (`theRussianEngineIsUntouchedByClass2`): the Russian asset under the
DEFAULT policy never recovers "спасибо" from "апаси" (а for с — a class-#2-only pair; с has no
long-press partner and `апаси` has zero exact continuations), while the SAME dictionary under
the TATAR policy recovers it — the pin discriminates, and the shipped Russian behavior is
byte-identical to pre-Phase-B.

### Ship decision (2026-09-20)

Gates G1 and G2 both fail on both windows/arms; per the plan's written rule ("if any gate fails,
class #2 stays disabled and the numbers are recorded as the outcome") **nothing about the shipped
fuzzy behavior changes**: `LatinIME` wires no policy anywhere, every engine runs
`FuzzyEditPolicy.DEFAULT`, and `FuzzyEditPolicy.TATAR` + the same-length bonus + the device-true
geometry infrastructure stay in the tree fully tested (12 new JVM tests exercise them directly).
Evidence over shipment.

### Contract amendments recorded

- `TdictPrefixIndex.SHIPPED_FUZZY_EDIT_CLASSES` (the E3b single-switch constant) is replaced by
  the per-engine `FuzzyEditPolicy` seam; `TdictPrefixIndexShippedFuzzyClassesTest` was rewritten
  from "the switch enables class #1 only" to "DEFAULT == the pre-Phase-B behavior; TATAR ==
  {1,2}+bonus, calibrated and gate-rejected".
- `E3bTestFixtures.tatarNeighborTable()` now models the device geometry (gap included) — every
  test that derived geometric pairs from it was re-pinned (`KeyNeighborTableGeometryTest`:
  65 → 32 pairs, fan-out avg 3.51 → 1.73, max 5 → 3; `FuzzyPrefixVariantsE3bTest`: "кит" emits
  4 variants, not 10).
- The E3b class-#2 typo set identity changed (see the table above); a dated footnote in
  `docs/archive/missions/DICTIONARY-E3.md` marks the offline-model error.
- `E3bComputeInstrumentationTest` now measures both policies and dumps the live-geometry pair
  set; `app/src/androidTest/AndroidManifest.xml` is new (the uses-library fix above).

### Gates (2026-09-20, Phase B)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | 1215 tests (was 1196; +19), 0 failures/errors/skips |
| python suites (`tests/*/test_*.py`) | 15 files, 465 tests (was 455; +10 in typo_pack), all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | 1 868 543 B ≤ 3 145 728 B (+632 B over Phase A — the policy seam) |
| `check-no-internet.sh` (unsigned release APK) | both levels OK |
| `TtSuggestEvalTest` metrics | unchanged (all pins green) |

### Files touched (Phase B)

- `app/src/main/java/.../dictionary/engine/FuzzyEditPolicy.kt` — NEW: the per-engine policy.
- `app/src/main/java/.../dictionary/engine/TdictPrefixIndex.kt` — policy injection replacing the
  global constant; the same-length bonus (SCAN_TAKE_SAME_LENGTH verdict + packed rank key);
  comments updated.
- `app/src/main/java/.../dictionary/engine/MappedDictionaryEngine.kt`,
  `app/src/main/java/.../suggestions/EngineHandle.kt` — the policy seam (pass-through, default
  null = DEFAULT).
- `app/src/main/java/.../latin/LatinIME.java` — a comment records the G1 verdict; no policy is
  wired (the seam defaults to DEFAULT for every engine).
- `scripts/typo_pack.py` — device-true geometry (gap from config.xml), `--prefix-code-points`,
  docstring updates.
- Tests: `TtTypoPhaseBCalibrationTest` (G1 + E2E both ways + ru negative + G3 host perf), NEW
  `TtTypoPhaseBPrecisionTest` (G2), NEW `TdictPrefixIndexSameLengthBonusTest`, and re-pins in
  `E3bTestFixtures`, `KeyNeighborTableGeometryTest`, `FuzzyPrefixVariantsE3bTest`,
  `E3bRecoveryCalibrationTest`, `TdictPrefixIndexShippedFuzzyClassesTest`,
  `TdictPrefixIndexEditClassRankingTest`, `TdictPrefixIndexE3bTest`, `TdictPrefixIndexFuzzyTest`
  (zero-alloc with the candidate policy), `RealDictionaryPrefixIndexTest` (common-path p95 with
  the candidate policy), `tests/typo_pack/test_typo_pack.py` (40 → 50).
- `app/src/androidTest/.../E3bComputeInstrumentationTest.kt` — both policies + typo probes + the
  live-geometry pair dump; NEW `app/src/androidTest/AndroidManifest.xml`.
- Evidence: `build/tt-typo-next-phaseB/` (device logcat, instrument result, screen size).

## Phase C — typo recovery v2: full single-substitution class, probe-first (measured 2026-09-20, verdict: NOT SHIPPED)

**Gate amendment (orchestrator, 2026-09-20).** Phase B proved G2's 2 % precision threshold
miscalibrated — even the shipped class-#1 baseline measures 3.63 % — so Phase C's G2-C gate was
written as: among >= 4-cp prefixes of the eval set where the exact pass is EMPTY (the activation
condition; rare), the share receiving any class-#4 candidate must stay <= 25 %. Pollution of a
non-empty strip is impossible by construction (class #4 requires exact==0) — stated for the
record. G1-C/G3-C keep the Phase-B shape (1.5x lift; 5 ms host / 8 B / device input gate).

### Design that landed (infrastructure; shipped behavior unchanged)

Edit class #4 — full single substitution: every position of the prefix is replaced by every
letter of the layout's typeable alphabet (39 Tatar letters: `KeyNeighborTable.nodes`, never
hard-coded; `scripts/typo_pack.py --edit-class 4` derives the same set from the layout XML).
PROBE-FIRST (`FuzzyPrefixVariants.generateFullSubstitutionVariants` +
`TdictPrefixIndex.probeAndScanVariant`): each of the n×38 variants gets ONE existence probe
(binary search + starts-with check, no range scan); only a variant that provably starts at least
one dictionary word (a survivor) consumes the shared `MAX_FUZZY_VARIANTS=64` budget and gets its
block scanned. Probes carry their own budget `MAX_FUZZY_PROBES=8192` — a formality that can never
trip on a real layout (max 64 cp × 38 = 2 432 ≪ 8 192) and fails closed otherwise (pinned by
`TdictPrefixIndexPhaseCTest.theProbeBudgetDropsTheWholeLevelFailClosed`). The activation gate:
class #4 runs ONLY when the exact pass returned 0 results AND the prefix is >= 4 code points
(pinned: at 3 cp the {1,4} arm is byte-identical to the {1}+bonus arm on all 109 649 class-#4
set rows). The Phase-B same-length bonus applies within class #4. `FuzzyEditPolicy.TATAR` was
redefined to the Phase-C candidate {1, 4}+bonus; the Phase-B {1,2} arm stays explicit in the
Phase-B tests, whose pins remain valid.

### New set identities (class #4, committed 110k asset, layout alphabet)

| Set | Rows | SHA-256 |
|---|---:|---|
| class #4, 3-cp | 109 649 | `30897644…6ba072` |
| class #4, 5-cp | 104 955 | `c35c9770…c0327b` |

(JVM rebuilds them from the engine-side node set and asserts the same SHAs —
`thePhaseCSetsAreByteIdenticalToTheGeneratorRuns`; python pins live in
`tests/typo_pack`.)

### G1-C — recovery (verdict: **BELOW — gate FAILED**)

Raw lines (`TtTypoPhaseCCalibrationTest.gateG1CRecoveryUnderTheActivationCondition`):

```
PhaseC recovery@3 window=3cp activation=never class4_set=109649 identical_to_{1}+bonus=109649 probes=0 verdict=GATE-HELD
PhaseC recovery@3 window=5cp baseline(class1-only, class1 set, exact==0)=39.7552% (38689/97318) candidate(classes1+4+bonus, class4 set, exact==0)=28.6431% (29057/101445) ratio=0.7205x gate>=1.5x verdict=BELOW variant_p95=11 variant_max=37 visited_p95=136 visited_max=660 probe_p95=190 probe_max=190 over_budget=0 whole_set=27.6900% (29062/104955) phaseB_class2_whole_set=38.4641%
```

| Window | Baseline (class #1, own set, exact==0) | Candidate ({1,4}+bonus, class-#4 set, exact==0) | Ratio of rates | Gate ≥ 1.5× |
|---|---:|---:|---:|---|
| 3 cp | — (gate can never hold: candidate ≡ {1}+bonus on all 109 649 rows, 0 probes) | — | — | GATE-HELD ✓ |
| 5 cp | 39.7552 % (38 689/97 318) | 28.6431 % (29 057/101 445) | **0.7205×** | FAIL |

Headline vs Phase B: whole-set recovery@3 of the {1,4} candidate on the class-#4 set is
27.69 % — BELOW the Phase-B {1,2} whole-set 38.46 % on its own set. An arbitrary-substitution
typo lands the correction in a random block, where the target competes by frequency against the
whole block — the same structural cap as in Phase B, now measured on the hardest class.

### G2-C — precision on genuinely empty strips (verdict: **ABOVE — gate FAILED**)

Raw line (`gateG2CPrecisionOnGenuinelyEmptyStrips`):

```
PhaseC precision eval_prefixes_>=4cp=7471 exact_empty=1579 class4_filled=997 (63.1412%) gate<=25% verdict=ABOVE
```

| Metric | Value | Gate |
|---|---:|---|
| distinct ≥ 4-cp prefixes of eval words | 7 471 | — |
| …with an EMPTY exact pass (the activation condition) | 1 579 (21.1 %) | counted |
| …receiving a class-#4 candidate | **997 (63.14 %)** | ≤ 25 % FAIL |

Class #4 fills most genuinely empty strips it can reach — the dictionary's nearest
one-substitution words. Whether that is "noise" or "nearest-word help" is a product judgment;
the written gate says ≤ 25 %, and 63 % is not close. Pollution of non-empty strips: impossible
by construction (activation requires exact==0).

### G3-C — performance (verdict: **FAILED on device; host passes**)

Host (`gateG3CHostComputeWithClass4Engaged`, 3 962-lookup sample of the class-#4 5-cp set;
zero-alloc in `TdictPrefixIndexFuzzyTest.perLookupAllocationWithTheTatarPolicyDoesNotDependOnTheNumberOfProbes`):

| Measurement | DEFAULT | TATAR {1,4} | Budget |
|---|---:|---:|---|
| host p95, class-#4 typo set | 0.041 ms | **0.529 ms** (max 0.824) | ≤ 5 ms ✓ |
| host probes per lookup (5 cp) | 0 | 190 (p95 = max = 190) | — |
| zero-alloc, class #4 engaged (152 probes) | — | ≤ 8 B/lookup | ≤ 8 B ✓ |

Device (POCO C71, 720×1640, API 35 — `E3bComputeInstrumentationTest`, 2 000 samples per arm;
evidence `build/tt-typo-next-phaseB/device-instrumentation-logcat-phaseC.txt`):

| Arm | p50 | p95 | max | probes (total/max per lookup) |
|---|---:|---:|---:|---|
| review prefixes, TATAR {1,4} | 0.270 ms | 0.598 ms | 1.037 ms | 0 / 0 (class #4 never fires — exact non-empty) |
| typo probes, DEFAULT | 0.748 ms | 1.574 ms | 1.638 ms | 0 / 0 |
| typo probes, TATAR {1,4} | 13.570 ms | **31.597 ms** | 32.470 ms | 288 800 / 380 |

The typo mix (сцл 3cp → 0 probes; сцлә 4cp → 152; сцләм 5cp → 190; сйл 3cp → 0; сцләмәтлек 10cp
→ 380) measures the probe path honestly, including the 380-probe 10-cp case the plan calls out.
**31.6 ms p95 against the 3.5 ms input gate — a 9× breach.** The host hides the cost (JIT, big
caches); on the device each probe's binary search decodes ~17 front-coded blocks through the
single-entry block cache. Probe-first is provably allocation-free but NOT compute-cheap at this
dictionary's block layout — recorded as the measured fact, not engineered around (the gates
failed anyway).

### The сцләм → сәләм evidence (Phase-C arm)

`TtTypoPhaseCCalibrationTest.theSclamTypoOffersSyalamInCellOneByTheFifthLetter` pins, under the
candidate wiring (suffix rules + TATAR {1,4}+bonus + layout table): `сц` →
`[сценарий, сценарие, сценарийлар]`; `сцл` → `[]` (the class-#4 gate needs ≥ 4 cp — unlike the
Phase-B {1,2} arm, nothing shows at 3 cp); `сцлә` → `[сәләтле, сәләт, сәләтен]` (class #4 fires;
the `сәлә*` block's frequency leaders — no same-length candidate exists at 4 cp); `сцләм` →
**`[сәләм, сәләмәтлек, сәләмәт]`** — the correction in cell 1 by the 5th letter;
`сцләмәтлек` (10 cp) → `[сәләмәтлек]` with exactly 380 probes measured. The machinery demonstrably
works — including on the Russian dictionary (`апаси` → `[спаси, упаси, апачи]`, the bonus
ordering same-length words above the 175 497-frequency «спасибо») — and the gates rejected it.
**DONE-WHEN-2 is therefore NOT met in the shipped build: `сцләм` keeps an empty strip** (pinned
by `theShippedEnginesKeepThePrePhaseBBehavior`). What stands between: G1-C 0.72× (recovery does
not lift), G2-C 63 % (most empty strips get filled), G3-C 31.6 ms p95 on device (9× the input
gate). Any resurrection needs a design that is cheaper per probe (e.g. a precomputed
substitution index — no block decode per probe) AND a precision story for the empty-strip case;
both are out of the written design.

### Ship decision (2026-09-20)

G1-C, G2-C and G3-C ALL fail. Per the written rule, **class #4 stays off**: `LatinIME` wires no
policy, every engine runs `FuzzyEditPolicy.DEFAULT`, and the shipped behavior is byte-identical
to pre-Phase-B everywhere. The class-#4 generator, probe-first consumer, budgets and the TATAR
policy stay in the tree fully tested (18 new JVM tests exercise them directly; 9 new python
tests cover the generator side).

### Contract amendments recorded (Phase C)

- `FuzzyEditPolicy.TATAR` redefined: {1, 4}+bonus (the Phase-C candidate). The Phase-B {1,2} arm
  is explicit in the Phase-B test suites, whose pins (G1 6436/…/40379, G2 304/665, E2E strips)
  remain valid measurements of that arm.
- `TdictPrefixIndexShippedFuzzyClassesTest` re-pins the policy contract: DEFAULT = {1}; TATAR =
  {1, 4}+bonus, gated, unwired.
- New budgets: `MAX_FUZZY_PROBES = 8192` (documented as never-trippable on real layouts);
  `MAX_FUZZY_VARIANTS = 64` unchanged — it now counts SCANNED variants (class #4 survivors), with
  probes accounted separately (`lastFuzzyProbeCount`).
- `MIN_SUBSTITUTION_PREFIX_CODE_POINTS = 4` — the activation-gate length half (mirrors
  `AutocorrectPolicy.MIN_WORD_CODE_POINTS`).

### Gates (2026-09-20, Phase C)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | 1233 tests (was 1215; +18), 0 failures/errors/skips |
| python suites (`tests/*/test_*.py`) | 15 files, 474 tests (was 465; +9 in typo_pack), all OK |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | 1 868 807 B ≤ 3 145 728 B (+264 B over Phase B — the class-#4 machinery) |
| `check-no-internet.sh` (unsigned release APK) | both levels OK |
| `TtSuggestEvalTest` metrics | unchanged (all pins green — class #4 fires only at exact==0, which the eval paths never hit) |
| device instrumentation (POCO C71) | 2 tests OK — timings above |

### Files touched (Phase C)

- `app/src/main/java/.../dictionary/engine/FuzzyPrefixVariants.kt` — `generateFullSubstitutionVariants`.
- `app/src/main/java/.../dictionary/engine/TdictPrefixIndex.kt` — `EDIT_CLASS_SUBSTITUTION`, the
  activation gate, `probeAndScanVariant`, budgets, `lastFuzzyProbeCount` observability, docs.
- `app/src/main/java/.../dictionary/engine/FuzzyEditPolicy.kt` — TATAR = {1, 4}+bonus, verdict docs.
- `scripts/typo_pack.py` — `read_layout_alphabet`, `build_substitution_typo_set`, `--edit-class 4`.
- Tests (new): `FuzzyPrefixVariantsPhaseCTest`, `TdictPrefixIndexPhaseCTest`,
  `TtTypoPhaseCCalibrationTest`; (updated): `TdictPrefixIndexShippedFuzzyClassesTest`,
  `TdictPrefixIndexSameLengthBonusTest`, `TdictPrefixIndexFuzzyTest`, `RealDictionaryPrefixIndexTest`,
  `TtTypoPhaseBCalibrationTest` (explicit Phase-B arm), `TtTypoPhaseBPrecisionTest` (same),
  `tests/typo_pack/test_typo_pack.py` (50 → 59).
- `app/src/androidTest/.../E3bComputeInstrumentationTest.kt` — both policies + the 10-cp probe +
  per-lookup probe counters.
- Evidence: `build/tt-typo-next-phaseB/device-instrumentation-*-phaseC.txt`.

## Phase C2 — corrected gates + probe-path engineering; class #4 SHIPPED (2026-09-20)

### Why this phase exists (orchestrator amendment, recorded verbatim in intent)

Phase C's verdict mixed two different failure kinds:

1. A REAL blocker: device perf — 31.6 ms p95 on the probe path (380 probes/lookup, each
   re-decoding ~17 front-coded blocks through the one-element block cache). An engineering
   problem.
2. A METHODOLOGICAL error in the Phase-C gates themselves: G1-C compared recovery RATIOS across
   different sets (class-#1 typos vs class-#4 typos — different difficulties; parity on the
   harder set is actually a good outcome, not a 0.72× failure). G2-C's 25 % fill-rate cap
   measured fills of OTHERWISE-EMPTY strips — but a one-edit dictionary word on an empty strip is
   standard IME behavior, not pollution (pollution of a non-empty strip is impossible by
   construction: class #4 requires exact==0).

Corrected gates, written BEFORE re-measuring (plan footnote of 2026-09-20):

- **G1-C2 (same-set absolute lift)**: on the SAME class-#4 5-cp typo set, recovery@3 of the
  candidate ({1,4}+bonus) minus the class-#1 baseline under identical conditions ≥ **+10 pp**
  (3-cp window reported).
- **G2-C2 (precision, structural)**: among ≥ 4-cp eval prefixes, the activation rate (exact==0)
  must stay ≤ 25 %; the fill rate among activated prefixes is REPORTED without a threshold.
- **G3-C2 (perf, the real gate)**: DEVICE p95 ≤ 3.5 ms on the typo-probe path (the existing
  input gate), host p95 ≤ 5 ms, zero-alloc ≤ 8 B/lookup.

### The perf engineering (probe path)

Phase C profile on the POCO C71: 380 probes per 10-cp lookup, each a binary search whose every
step decoded a full front-coded block through the ONE-entry decoded-block cache — consecutive
probes evicted each other's blocks. Two changes, both confined to the probe path, zero added
per-lookup allocation, fixed preallocated state only:

1. **No-cache probe search** (`probeLowerBound`/`probeCompareWholeWordToVariant`/`decodeWordInto`
   into a dedicated `probeScratch`): each comparison reads the word directly off the mapped bytes
   (a bounded front-coded walk), so probes never touch or evict the shared block cache.
   Device: 31.6 → 8.9 ms p95.
2. **Per-position range narrowing** (`computeProbeRanges` + `probeRangeStart/End`): a variant
   substituted at position p shares the typed prefix's first p code points, so its survivors can
   only live in the range of words starting with those p code points — computed ONCE per lookup
   by incremental narrowing (each range searched within its predecessor), and an EMPTY range skips
   the whole position without a single probe. For "сцләмәтлек" the "сцл*" range is empty, killing
   positions 3–9 for free: 380 → 114 issued probes. Device: 8.9 → **3.306 ms p95**.

The result is bit-identical to the Phase-C probe (the pinned Phase-C recovery/precision counts —
38 689 / 29 057 / 97 318 / 101 445 / 1 579 / 997 — did not move across the change; the pins
assert exactly that).

| Probe path (typo probes, 2 000 samples, POCO C71) | p50 | p95 | max | probes max |
|---|---:|---:|---:|---:|
| Phase C (block-cache probes) | 13.570 ms | 31.597 ms | 32.470 ms | 380 |
| C2 step 1 (no-cache probes) | 4.485 ms | 8.908 ms | 9.512 ms | 380 |
| **C2 final (no-cache + narrowed)** | **2.466 ms** | **3.306 ms** | 3.519 ms | **114** |

Host (3 962-lookup sample of the class-#4 5-cp set): default p95 0.059 ms → TATAR p95
**0.176 ms** (max 0.334); ≤ 8 B/lookup pinned
(`perLookupAllocationWithTheTatarPolicyDoesNotDependOnTheNumberOfProbes`, 76 issued probes on the
heavy arm). Device review-prefix arms (common path, class #4 never fires): p95 0.60 ms.

### Gate results (corrected)

| Gate | Measurement | Threshold | Verdict |
|---|---|---|---|
| G1-C2 same-set lift (c4@5cp, whole set) | baseline 0.4478 % (470) → candidate 27.6900 % (29 062) = **+27.24 pp** | ≥ +10 pp | **PASS** |
| G1-C2 activation subset (exact==0) | 0.4584 % (465) → 28.6431 % (29 057) = +28.18 pp | (reported) | — |
| G1-C2 3-cp window | baseline == candidate == 100 recovered (gate inert) | (reported) | — |
| G2-C2 activation rate | 1 579 / 7 471 = **21.14 %** | ≤ 25 % | **PASS** |
| G2-C2 fill rate among activated | 997 / 1 579 = 63.14 % | reported, no threshold | — |
| G3-C2 device p95 (typo-probe path) | **3.306 ms** | ≤ 3.5 ms | **PASS** |
| G3-C2 host p95 / zero-alloc | 0.176 ms / ≤ 8 B | ≤ 5 ms / ≤ 8 B | **PASS** |

Raw lines (grep targets `PhaseC2 lift`, `PhaseC precision`, `PhaseC perf`):

```
PhaseC2 lift window=5cp set=class4 whole_set: baseline=0.4478% (470) candidate=27.6900% (29062) lift=27.2422pp gate>=+10pp verdict=PASS | activation_subset=101445 baseline=0.4584% candidate=28.6431% lift=28.1847pp | window=3cp baseline=100 candidate=100 (gate inert)
PhaseC precision eval_prefixes_>=4cp=7471 exact_empty=1579 (21.1351%) class4_filled=997 (63.1412% of empty) gate: activation<=25% verdict=PASS (fill rate reported, no threshold — G2-C2)
PhaseC perf host window=5cp policy=tatarPolicy samples=3962 p50=0.106 ms p95=0.176 ms max=0.334 ms probe_p95=190 probe_max=190 consumed=0
```

(The Phase-C lines above them — `ratio=0.7205x verdict=BELOW` under the superseded cross-set
methodology — stay in the Phase-C section as the record of what the FIRST measurement showed.)

### Ship decision (2026-09-20): class #4 SHIPPED for Tatar

All corrected gates pass, so `LatinIME` wires `FuzzyEditPolicy.TATAR` into the Tatar engine
(the artifact-registry seam, next to the P3 suffix rules). The Russian engine gets null →
`FuzzyEditPolicy.DEFAULT` — bit-identical to pre-Phase-B (pinned:
`theRussianEngineIsUntouchedByClass4` — DEFAULT on the Russian asset leaves "апаси" empty while
the same dictionary under TATAR paints the class-#4 strip `[спаси, упаси, апачи]`).

**Mission DONE-WHEN-2 achieved**: typing `сцләм` offers `сәләм` in cell 1 by the 5th letter on
the real shipped asset — pinned by
`TtTypoPhaseCCalibrationTest.theSclamTypoOffersSyalamInCellOneByTheFifthLetter` under the exact
shipped wiring: `сц` → `[сценарий, сценарие, сценарийлар]`, `сцл` → `[]` (the ≥ 4-cp gate),
`сцлә` → `[сәләтле, сәләт, сәләтен]`, `сцләм` → **`[сәләм, сәләмәтлек, сәләмәт]`**, and
`сцләмәтлек` → `[сәләмәтлек]` with 114 issued probes. The device-UAT half lands with Phase D.

### Contract amendments recorded (Phase C2)

- `FuzzyEditPolicy.TATAR` is now the SHIPPED Tatar configuration ({1, 4} + bonus, gated);
  DEFAULT is the Russian/everything-else shape. `TdictPrefixIndexShippedFuzzyClassesTest`
  re-pins the contract; the Phase-B era "default keeps pre-Phase-B behavior" pin is renamed
  `theDefaultPolicyKeepsThePrePhaseBBehavior` (the DEFAULT arm — no longer the Tatar shipped one).
- New engine internals (probe scratch, per-position ranges) are fixed preallocated state;
  `MAX_FUZZY_PROBES = 8192` now bounds class-#4 variant EMISSION (an upper bound on issued
  probes — narrowing skips positions for free and the skipped ones are never issued).
- `FuzzyPrefixVariants.generateFullSubstitutionVariants` reports the substituted POSITION through
  the new `PositionedVariantConsumer` (the narrowing needs it); classes #1–#3 keep the plain
  consumer.

### Gates (2026-09-20, Phase C2)

| Gate | Result |
|---|---|
| `./gradlew test --rerun-tasks` | 1234 tests (was 1233; +1: `gateG1C2SameSetLift`), 0 failures/errors/skips |
| python suites | 474 tests, all OK (unchanged — no python change in C2) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` size | 1 869 287 B ≤ 3 145 728 B (+480 B over Phase C — the narrowed-probe machinery) |
| `check-no-internet.sh` (unsigned release APK) | both levels OK |
| `TtSuggestEvalTest` metrics | unchanged (all pins green; the eval paths never meet the activation gate) |
| device instrumentation (POCO C71) | 2 tests OK — the table above; evidence `build/tt-typo-next-phaseB/device-instrumentation-*-phaseC2.txt` |

### Files touched (Phase C2)

- `TdictPrefixIndex.kt` — no-cache probe search (`probeLowerBound`/`probeUpperBound`/
  `probeCompareWholeWordToVariant`/`probeCompareWordToPrefixBlock` over `decodeWordInto` into
  `probeScratch`), `computeProbeRanges` + `probeRangeStart/End`, position-aware
  `probeAndScanVariant`, the probe-count double-count fix.
- `FuzzyPrefixVariants.kt` — `PositionedVariantConsumer`; the class-#4 generator reports the
  substituted position.
- `FuzzyEditPolicy.kt` — docs for the shipped TATAR.
- `LatinIME.java` — the Tatar engine is wired with `FuzzyEditPolicy.TATAR`.
- Tests: `TtTypoPhaseCCalibrationTest` (G1-C2, G2-C2 reframed, re-pinned probe counts),
  `TdictPrefixIndexPhaseCTest` (narrowing-aware probe pins, fail-closed budget test reworked),
  `TdictPrefixIndexFuzzyTest` (alloc arm re-pinned), `TtTypoPhaseBCalibrationTest` (renamed
  default-policy pin), `TdictPrefixIndexShippedFuzzyClassesTest` (shipped-contract doc),
  `FuzzyPrefixVariantsPhaseCTest` (positioned consumer).
- `app/src/androidTest/.../E3bComputeInstrumentationTest.kt` — probe counters per lookup.

## Phase D — validation, device UAT, docs (done 2026-09-20)

### Gates (full rerun, all 2026-09-20)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 474 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 234 tests, 0 failures / 0 errors / 0 skipped** (134 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL (21 tasks executed; baseline 0 errors) |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (tt 3/0, ru 2/0 as pinned) |
| `release_pack.sh` | unsigned **1 869 287 B** (= the Phase-C2 artifact size) → signed zopfli **1 849 555 B** ≤ 3 145 728, SHA-256 **`32cd873bcc8322253a8463b355e3420035ba4e00a4b09f387c2937ea4a821ab6`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK (manifest + aapt2; backup whitelist closed) |
| `release_check.sh --quick` (the signed APK) | **8/8 artifact checks PASS** (size, asset pins 16/16, emoji assets, permissions [VIBRATE], signature, version 1.9.15/31, changelog, delta); the 4 gate checks SKIP by `--quick` and ran separately above |

Note on the signed size: 1 849 555 B coincides byte-for-byte with the TT-SUGGESTIONS signed
build's size (different unsigned input, 1 867 899 → 1 869 287 B; zopfli determinism makes
equal compressed sizes possible). The SHA-256s differ (`36d80c99…` → `32cd873b…`) and the
on-device Phase-A/C2 probes below prove the new code is in the APK.

### Device UAT (POCO C71, 720×1640, Android 15 Go, SDK 35)

APK installed with `adb install -r` over the TT-SUGGESTIONS build (same signature, data
preserved — the suggestions toggle survived the update; no uninstall). The device-default
IME at session start was ALREADY ours (the user re-selected it after the previous UAT), so
no IME switch was needed and none is restored to anything else. Evidence:
`build/device-uat-2026-09-20/` (files of this run are numbered 100+; the helper library
`lib.sh` / `coords.py` and the strip-reading method are the ones calibrated in the
TT-SUGGESTIONS UAT — strip ≈ y 980–1060, cells at x 120/360/600, tt rows y 1110/1206/1301/
1396/1491, ru/en rows y 1150/1264/1377/1491).

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Sentence start at field start → бу · ул · ә | PASS | `100-field-start-strip.png` |
| 2 | Same-stem boost: prefix `татар` → татарлар · татарча · татарлары | PASS | `101-strip.png` |
| 3 | `татар`+space → bigram successors теле · дәүләт · телен | PASS | `102-strip.png` |
| 4 | **Phase A**: tap дәүләт, NO keystroke → strip immediately shows дәүләт's successors советы · думасы · советының | PASS | `103-strip.png` (field readback «татар дәүләт ») |
| 5 | Word forms: `сакчы`+space → сакчысы · сакчылар · сакчысын | PASS | `104-strip.png` |
| 6 | **Phase A (forms path)**: tap сакчылар → strip immediately shows сакчылары · сакчылары… · сакчыларын; a second tap commits «сакчыларына » (the smoke's `tap-followup-tt-сакчы` pattern, on hardware) | PASS | `105-strip.png`, `106-sakcy-after-tap2.png` (field readback «…сакчылар сакчыларына ») |
| 7 | **Phase C2 letter-by-letter** `сцләм`: `сц` → сценарий · сценарие · сценарийл… (exact) · `сцл` → empty (the ≥ 4-cp gate) · `сцлә` → сәләтле · сәләт · сәләтен · `сцләм` → **сәләм in cell 1** · сәләмәтлек · сәләмәт — byte-identical to the JVM pins under the shipped wiring | PASS | `112-strip.png`, `113-strip.png`, `114-strip.png`, `115-strip.png` |
| 8 | **No pollution of correct typing**: `кит` → китте · киткән · китап (exact); `кита` → китап · китабы · китаплар; `китап` → китаплар · китапны · китаплары (same-stem). Class #4 never fired — no one-edit words on a non-empty strip | PASS | `123-kitap-strip.png`, `124-kitap-strip.png`, `125-kitap-strip.png` |
| 9 | Sentence start after `. ` (letter directly before the period) → бу · ул · ә | PASS | `132-strip.png` |
| 10 | Emoji tail cell unchanged: `сәлам`+space → биреп · белән · 👋 | PASS | `131-strip.png` |
| 11 | ru slot unchanged (DEFAULT policy): `майор` → майора · майором · майору; `прив` → привет · привести · привело | PASS | `136-strip.png`, `137-strip.png` |
| 12 | en basic typing, strip hidden by design; globe cycling tt→en→tt→ru→en reached every layout | PASS | `138-en-hi.png` |
| 13 | Emoji panel: long-press comma opens, grid tap commits 😀, АБВ returns | PASS | `139-emoji-panel.png`, field readback `&#128512;` |
| 14 | Sustained Tatar paragraph: 193 chars in 86 s with the TATAR policy live — every character in order, zero drops, no typo-class interference, no autocorrect corruption | PASS | `141-paragraph.png`, `141-paragraph-field.txt` (byte-identical to the source text) |
| 15 | Cold start (force-stop → `am start -W` TotalTime): SetupActivity 258/253/250 → **median 253 ms**; SettingsActivity 262/268/265 → **median 265 ms** — budget < 400 ms holds | PASS | `142-coldstart.txt` |
| 16 | Stability: crash buffer EMPTY after the whole cycle; full logcat — no FATAL EXCEPTION / ANR for the package (only the known MIUI WindowManager `dispatchAppVisibility` W-warnings at the force-stop instants of the cold-start loop) | PASS | `143-logcat-crash.txt` (0 lines), `144-logcat-full.txt` |

Harness artifacts, not app defects (recorded for the next run): (a) typing `.` after a
word committed with auto-space yields «word . » — the sentence-start detector correctly
requires a letter directly before the period run, so the strip stays empty there (row 9
redoes it properly); (b) the globe subtype cycle is MRU-ordered, so «one tap from tt»
landed on en in this session — layout classification now uses a two-pixel probe (extra-row
key face + strip-band presence) after a mis-switch typed Latin garbage into the try-it
field (deleted; no app impact).

### DONE-WHEN audit (mission level)

1. *Predictions immediately after a suggestion tap* — **met**: JVM pins since Phase A +
   device rows 4 and 6 (a tap-commit repaints the strip with the accepted word's
   successors/forms without any keystroke, on both the bigram and the after-word-forms
   paths).
2. *`сцләм` offers `сәләм` in the strip* — **met**: JVM pin since Phase C2 + device row 7
   (сәләм in cell 1 by the 5th letter, the whole letter-by-letter ladder matching the pins).
3. *Extension only through the plan's pre-written calibration gates* — **met as written**:
   Phase B's class #2 failed G1/G2 honestly and was NOT shipped (machinery stays, unwired,
   fully tested); Phase C's first gates failed honestly; the C2 amendment was written down
   BEFORE re-measuring, and class #4 shipped only after all corrected gates passed
   (lift +27.24 pp ≥ +10 pp; activation 21.14 % ≤ 25 %; device p95 3.306 ms ≤ 3.5 ms; host
   0.176 ms ≤ 5 ms; ≤ 8 B/lookup).
4. *All gates pass* — **met**: the Phase-D gate table above; `TtSuggestEvalTest` counters
   unchanged within the 1 234.

The plan's Phase-D task 4 (independent re-verification by a fresh agent) is the
orchestrator's item, not this run's.

### Files touched (Phase D)

- `docs/TT-TYPO-NEXT.md` — this section; status header updated.
- `HANDOFF.md` — new top entry.
- `CHANGELOG.md` — Unreleased: the tap→predictions fix and the empty-strip typo
  suggestions.
- `docs/README.md` — the TT-TYPO-NEXT.md index line extended with the Phase D outcome.
- Evidence: `build/device-uat-2026-09-20/` rows 100+; `git status` shows no code change in
  Phase D.

## Independent verification (2026-09-20, verdict: PASS)

A fresh agent re-verified the mission adversarially after Phase D: all four DONE-WHEN items
confirmed with independent evidence (pins read in code, device screenshots checked, all gates
re-run: 1 234 JVM / 474 python / lint / rebuild --check / release sizes byte-identical to this
report; ru assets byte-identical to HEAD; no commits by the mission; `docs/archive/` touched only
by a declared dated footnote in `DICTIONARY-E3.md`).

Two findings were fixed by the orchestrator afterwards:

1. Stale KDoc in `EngineHandle.kt` claimed every engine runs `FuzzyEditPolicy.DEFAULT` — it now
   documents that the Tatar engine ships `FuzzyEditPolicy.TATAR` since Phase C2.
2. The production wiring had no executable pin — added
   `TdictPrefixIndexShippedFuzzyClassesTest.latinImeWiresTheTatarPolicyToTheTatarEngineOnly`, a
   source-contract pin on `LatinIME.java` (the service cannot run under the JVM harness, so the
   wiring is pinned on its source text with the dual-path lookup the resource contracts use).

Test count after the fixes: **1 235 JVM** (was 1 234), 474 python — both suites green.
