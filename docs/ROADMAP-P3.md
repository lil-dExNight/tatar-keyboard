# ROADMAP-P3 — phase 3 autocorrect report: P2 visual contract

Status: **P2 (autocorrect visual contract, AOSP standard) implemented 2026-09-23**; all gates
green on the host; device UAT remains separate work. P7 (autocorrect widening, gated) is NOT
started — by the phase's own order it comes after the contract below is visible and undoable on
device. No commits — the operator commits.

Baseline before the item: JVM 1 400 tests, python 484 tests, release 2.2.0.

## P2 — autocorrect visual contract (done 2026-09-23)

### What the item is

Before this item the D3 autocorrection was invisible until it fired: the verdict was computed
per keystroke inside the very lookup that feeds the band
(`TdictPrefixIndex.lookup` → `computeAutocorrectAdvice`), but it was only READ at the separator
(`SuggestionsController.maybeAutocorrectBeforeSeparator`), so the first time the user saw the
replacement was after it had already happened, with one backspace of undo. The AOSP standard
asks for the opposite: the user must SEE a coming replacement before it happens and must be
able to refuse it.

Now, whenever the separator-time policy — unchanged, same `AutocorrectPolicy` numbers, same
single class-#1 candidate — would fire on the word under the cursor, the suggestion strip shows
the **autocorrect preview**: the typed word in the left cell (tappable, "keep what I typed"),
the correction in the center cell, **emphasized** (bold face, theme accent `@color/app_accent`,
an underline measured against exactly the drawn text), the third cell empty so the two read as
a decision rather than a ranking. Tapping the typed-word cell suppresses the advice for THIS
occurrence; tapping the correction cell commits it through the ordinary accepted-suggestion
path; the separator still applies exactly the emphasized cell, and the backspace undo is
untouched.

### Design (file:line)

- `SuggestionsController.kt:2107` — `applyPrefixResult` gains the preview branch. The E4c
  empty-result observation keeps running first (learning evidence is unchanged); then
  `computeAutocorrectPreview()` decides; a preview OWNS the whole band (no companion fill is
  requested for it), binds `displayedPrefix`/`displayedSessionId` like any band, and is painted
  by the same single writer `showBand` (`:1590`), which now carries the emphasized cell index
  alongside the words and the spoken labels — a marker can never describe a band it did not
  arrive with.
- `SuggestionsController.kt:2199` — `computeAutocorrectPreview()` mirrors EVERY condition of
  `maybeAutocorrectBeforeSeparator` (`:2301`): the live gate, the word-scoped refusal, the
  casing (MIXED → nothing), the ≥ 4 code-point floor, the verdict's provenance
  (`advice.typedWord` == the normalized live word — the same anti-coalescing defense the
  separator makes), the ≥ 411 frequency floor, and "replacement != word". Checks are ordered
  cheapest-first: the common band (short word, dictionary word with no verdict) never
  allocates; `normalizeForLookup` runs only once a verdict exists. The two editor-side
  conditions (known cursor, cursor not inside a word) are not re-checked — a PREFIX result can
  only be applied for a trailing word at a known cursor, which `requestCurrentPrefix`
  established before the request went out.
- **No engine change, no new per-keystroke computation.** The advice was already computed per
  keystroke inside `TdictPrefixIndex.lookup` (D3); the preview reads the same `@Volatile`
  verdict through `EngineHandle.autocorrectAdvice()` at result-apply time. p95 impact: see
  Gates below — the lookup p95 gates (hard ≤ 5 ms) are byte-unchanged and pass at
  0.015–0.016 ms; the render-side addition is one `rebuildDisplaySuggestions` + `invalidate`
  per preview band, the same class of work a normal band paint already does, and the draw loop
  itself stays allocation-free (pinned by the pre-existing source contract
  `hotDrawAndTouchBodiesContainNoKnownAllocationSites`, which the new draw code had to pass
  unchanged).
- `SuggestionsController.kt:620,628` — the two new state fields, both word-scoped and never
  persisted: `previewKeepTypedCell` (the displayed text of the keep-typed cell while the band
  IS a preview, consulted only under the tap path's own freshness guards — a value left behind
  by an unbound band is never acted on because `applyPrefixResult` rewrites it, null or fresh,
  for every bound PREFIX band) and `suppressedPreviewWord` (the refused word, raw as typed).
- The **keep-typed tap** (`onTap`, `:2402`): binds the refusal to the word, unbinds the preview
  and re-derives the band — the ordinary suggestions of the same word return at once. It is
  deliberately NOT an accepted suggestion: nothing is committed, the clean run is NOT dirtied
  (the user spelled every letter; an unknown word may still be learned, unlike a correction —
  pinned), and the undo window is untouched (a preview can only exist several keystrokes after
  the window provably closed).
- The **suppression lifecycle**: consumed one-shot by the first separator
  (`maybeAutocorrectBeforeSeparator`, `:2312`), cleared the moment the trailing word is
  anything else including empty (`requestCurrentPrefix`, `:1762`), and dropped at every
  boundary `clearRevertState` already covered (`:2393`) — a field, subtype, selection or
  setting change ends the occurrence the refusal was scoped to. Typing the same word again is a
  new occurrence: the preview returns and the separator corrects (pinned).
- `StripSurface.setEmphasizedCell` (`:63`) — a default no-op, the same seam shape as
  `setSpokenCellLabels`: a surface written before P2 keeps compiling and simply never
  emphasizes. Production wiring: `LatinIME.java` `StripSurface.setEmphasizedCell` →
  `InputView.setSuggestionStripEmphasis` (never creates the strip) →
  `SuggestionStripView.setEmphasis` (`SuggestionStripView.kt:243`).
- `SuggestionStripState` — the emphasis flag per cell, pure and JVM-testable: only a populated
  cell can be emphasized, an empty/out-of-range cell is refused, `NO_CELL` clears, every
  `setSuggestions`/`clear` resets the marker (a fresh publication describes the whole band),
  zero-allocation pinned.
- `SuggestionStripView` (`:79,145,243,310-325,440`) — a second `TextPaint` created once (bold
  via `Typeface.create(..., BOLD)`, colour from the new theme attr `suggestionEmphasisColor`
  with the plain text colour as the fail-closed default); the emphasized cell draws with it and
  gets an underline (`canvas.drawLine`, width = `measureText` of the ellipsized display string,
  allocation-free inside `onDraw`); ellipsizing measures the emphasized cell against the bold
  paint. `attrs.xml` declares `suggestionEmphasisColor` (KeyboardTheme + the strip styleable);
  `themes-tatar.xml` sets it to `@color/app_accent` (the only keyboard theme).

### The state machine (pinned end-to-end)

```
typing word W (≥ 4 cp, not a dictionary word, exactly one class-#1 candidate f ≥ 411)
  └─ result arrives ──► PREVIEW band [W | C*]   (* = emphasized)
        ├─ tap W ("keep what I typed") ──► suppression armed for THIS occurrence;
        │     band = ordinary suggestions of W; separator commits W as-is;
        │     suppression consumed one-shot / dies with the word / dies at boundaries;
        │     W typed again later → preview returns.
        ├─ tap C ──► ordinary accepted-suggestion commit (auto-space), exactly like
        │     tapping any candidate; no correction machinery involved.
        ├─ type on ──► preview gone (next result repaints; advice for the longer word
        │     decides fresh).
        └─ separator ──► correction fires EXACTLY as the emphasized cell showed it
              (same policy, same armed/revert machinery); backspace reverts;
              after the revert + deleting the separator the preview returns.
Gate OFF / ineligible field / MIXED casing / < 4 cp / frequency 410 / stale verdict
  └─ no preview, ever (fail-closed); the OFF band is byte-for-byte the pre-P2 band.
Dictionary words and NEXT_WORD bands never carry the marker.
```

### Tests (+24: JVM 1 400 → 1 424)

- `AutocorrectPreviewControllerTest` (new, 19 tests) — the state machine end-to-end through a
  result-delivering fake engine: preview appears exactly when the policy would fire (incl.
  casing carried onto the correction); dictionary word → ordinary band; non-word without a
  verdict → reserved band; short word / below-floor candidate / stale verdict / MIXED casing /
  toggle OFF / ineligible field → no preview; keep-typed suppresses (band re-derives, separator
  commits as-is, the refusal is not an edit and does NOT dirty the clean run — the word may
  still be learned); suppression dies with its word; a stale tap is a no-op; the separator
  applies exactly the emphasized cell; backspace reverts and the preview returns after the
  separator is deleted; NEXT_WORD bands never ride the marker.
- `SuggestionStripStateTest` (+3) — emphasis sticks only to populated cells, refuses empty and
  out-of-range ones, `NO_CELL` clears, every publication resets the marker (even an
  identical-content one — the reset IS the visual change), zero-allocation after warmup.
- `SuggestionStripSourceContractTest` (+2) — the emphasized cell draws through the bold accent
  paint + underline inside the allocation-free draw body; the theme attr is declared and set to
  `@color/app_accent`; the marker travels controller → `LatinIME` → `InputView` → strip.
- Existing suites unchanged and green: `AutocorrectControllerTest` (D3 separator/revert
  machinery untouched apart from the suppression check, which is inert without a refusal),
  `TdictPrefixIndexAutocorrectTest` (engine untouched — zero amendments).

### Gates (2026-09-23, host)

| gate | result |
|---|---|
| python pipeline suites | all green (`ALL_PYTHON_OK`) |
| `./gradlew test --rerun-tasks` | **1 424 tests, 0 failures** |
| `./gradlew lintRelease` | 0 errors; 4 warnings, all pre-existing/environmental (2× NewerVersionAvailable on build-time deps, 2× UnusedResources on the pre-existing 5-row fractions); baseline untouched |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (assets untouched by this item) |
| `./gradlew assembleRelease -PskipReleaseSigning` | **1 851 760 B** (budget 3 145 728) |
| `check-no-internet.sh` on the APK | Level 1 + Level 2 OK, backup whitelist closed |
| lookup p95 (unchanged engine, hard gate ≤ 5 ms) | median 0.005 ms, p95 0.015–0.016 ms; request-to-handoff p95 0.034 ms — the preview reads the verdict the band's lookup already computed |

### Deviations and notes

- The ROADMAP text says "tapping it reverts"; the implemented and pinned semantics is the task
  text's one: tapping the typed-word cell means "keep what I typed" (suppress this occurrence),
  NOT a revert — the backspace undo remains the revert. The center cell tap commits the
  correction through the ordinary accepted-suggestion path.
- The preview replaces the band's ranked continuations for the keystrokes where the policy
  would fire (AOSP behaviour); the ordinary suggestions return on keep-typed or on typing on.
  This is deliberate: the correction IS the information those keystrokes need.
- The third cell stays empty in a preview band so the two cells read as a decision; companion
  fill is not requested for a preview band.
- Device verification (visible emphasis, taps, undo) remains for the phase's device UAT on the
  POCO C71; the emulator smoke coordinates for a follow-up probe are untouched by this item.

## P7 — autocorrect widening (gated): written gates BEFORE measuring (2026-09-23)

Item P7 of Phase 3 (docs/ROADMAP.md): widen the D3 autocorrect edit classes beyond class #1,
now that the correction is visible and refusable/undoable (the P2 contract above). The widening
candidates are CURRENT {1} vs WIDENED {1, 4} (class #4 = the probe-first full single
substitution, the strongest measured class of TT-TYPO-NEXT; class #2 geometric stays rejected on
the TT-TYPO-NEXT evidence). The frequency floor (411) and the single-candidate rule are
unchanged and out of scope. Tatar only — autocorrect is a Tatar feature; the Russian engine must
stay untouched (verified and pinned). These gates are amendable only with a dated reason.

**G1 (false corrections).** The policy fires only on words ABSENT from the dictionary — the
invariant that correctly typed dictionary words are safe by construction (pinned as a test). The
real risk is OOV words (names, rare forms). Metric: (a) run the widened policy over the eval
set's non-dictionary typed words (tt_eval_sentences.txt) and count the cases where it would
fire — every such case is LISTED in the report and must read as a plausible intended correction
(manual review, fail-closed: one implausible case = gate failed); (b) the dictionary's own
lowest-frequency decile as a rare-but-correct proxy — at the variant level (simulating absence),
count words whose substitution space contains exactly one other dictionary word with
frequency >= 411; same listing and review. Baseline class-#1 would-fire counts reported for
comparison.

**G2 (recovery).** Autocorrect-recovery@separator on the 5-cp typo sets (the >= 4 cp floor of
the policy: the 3-cp window is structurally inert): the class-#1 set as the baseline and the
class-#4 set for the widened candidate, identical conditions (typed prefix treated as the
complete typed word, absent, single candidate, frequency >= 411). Gate: absolute lift of WIDENED
vs CURRENT on the class-#4 set >= +5 percentage points.

**G3 (multi-candidate safety).** Widening produces more multi-candidate no-fire outcomes
(fail-closed by the single-candidate rule, which STAYS — widening the rule itself is out of
scope). Reported: zero-match / exactly-one / multi-match rates on the class-#4 5-cp set. No
threshold — the rule is structural.

**G4 (perf/contracts).** The advice computation stays on the existing lookup path (no new
request, no new token); host p95 <= 5 ms and the zero-allocation contract of lookup stay green
with the widened policy engaged, measured on the firing-path workload (absent >= 4-cp words).

Ship iff G1–G4 all pass; otherwise keep {1} and record the rejection with numbers.

### Gates measured (2026-09-23)

**Verdict: NOT SHIPPED — G1 and G2 both fail; the autocorrect class set stays {1} in every
shipped policy (`FuzzyEditPolicy.DEFAULT` AND `FuzzyEditPolicy.TATAR` — pinned).** The widening
machinery (the `autocorrectClasses` policy parameter and the probe-first class-#4 advice pass,
reusing the TT-TYPO-NEXT C2 narrowed no-cache probe engineering) stays in the tree as tested
infrastructure, unwired. Arms in every measurement differ ONLY in `autocorrectClasses` ({1} vs
{1, 4}); the display configuration of both is the shipped Tatar one ({1, 4} + the bonus).

**G1 (false corrections) — FAIL on manual review.** The construction invariant is pinned: a
dictionary word never gets a verdict (asserted on all 2 373 eval words present in the dictionary,
under the widened policy). On the eval OOV set (297 absent words of 2 670 unique eval words):

| Arm | Would-fire | Cases |
|---|---:|---|
| CURRENT {1} | **1** (0.34 %) | көнеңә → көненә (4 968): a conversational spelling normalized to the standard word — a GOOD correction |
| WIDENED {1,4} | **5** (1.68 %) | see below |

Widened cases, reviewed against the eval sentences (fail-closed rule: one bad case = gate
failed — there are three bad and one hazardous):

- японияның → япониянең (443) — GOOD: the harmony misspelling normalized («токио японияның
  башкаласы»).
- ертты → артты (2 422) — BAD: «ул газетаны урталай ертты» means "he TORE the newspaper"
  (йыртты); the correction changes it to "burnt".
- сүзлегеннән → күзлегеннән (717) — BAD: «татар сүзлегеннән файдаланыгыз» means "use the Tatar
  SPELLING"; the correction changes it to "through his glasses".
- җәрәхәтләде → җәрәхәтләре (1 215) — BAD: «ул тез буынын җәрәхәтләде» means "he injured his
  knee" (colloquial җәрәхәтләнде); the correction turns the verb into a noun.
- әгъва → әгъза (1 028) — HAZARDOUS: a proper name (Агъва) would become "member".

The lowest-frequency-decile proxy (11 000 rare-but-correct words, variant level): 151
class-#1-only and 199 class-#4-only words sit one edit from exactly one ≥ 411-frequency word —
recorded in the raw test output; the proxy exists to show the OOV risk is structural, not
eval-specific, and the eval review above is the binding one.

**G2 (recovery) — FAIL.** Autocorrect-recovery@separator on the 5-cp typo sets, identical
conditions (absent typed word, single candidate, floor 411):

| Set | CURRENT {1} | WIDENED {1,4} | Lift | Gate ≥ +5 pp |
|---|---:|---:|---:|---|
| class-#1 set (active 99 863) | 1.1015 % (1 100) | 0.5748 % (574) | **−0.53 pp** | — |
| class-#4 set (active 103 488) | 0.0087 % (9) | 0.5778 % (598) | **+0.57 pp** | FAIL |

The unchanged 411 floor caps the widened recovery near zero (most single-match candidates are
rarer words), and widening actively HURTS the class-#1 set: class-#4 matches turn formerly
single candidates into refused ambiguities (1 100 → 574).

**G3 (multi-candidate safety) — reported, no threshold.** On the class-#4 5-cp set (103 488
active rows): zero matches 41.07 % (42 505), exactly one 30.62 % (31 684 — the fire-eligible
bucket before the floor), multi 28.31 % (29 299 — refused by the rule that STAYS). The rule
itself is untouched by design.

**G4 (perf/contracts) — PASS on host.** p95 lookup on a 3 962-lookup firing-path sample
(absent ≥ 4-cp words): CURRENT 0.117 ms, WIDENED 0.182 ms (max 0.375), autocorrect probes
p95 = max = 190 (5 cp × 38, narrowing on); ≤ 5 ms ✓. Zero-alloc on a non-firing widened lookup
≤ 8 B/lookup ✓ (pinned). The advice pass stays inside the existing lookup — no new request,
token, or executor.

Raw lines (grep targets `P7 G1a`, `P7 G1b`, `P7 G2`, `P7 G3`, `P7 G4`):
`AutocorrectWideningCalibrationTest` (7 tests) + `TdictPrefixIndexAutocorrectTest` (+6 machinery
pins: default classes stay {1} everywhere, class-#4-only correction under the candidate policy,
two-candidate refusal, cross-class dedup, presence invariant, probe counters). Pinned real cases:
WIDENED corrects «аашнең» → «ааҗнең» (1 738, CURRENT leaves it alone); «ааҗнңң» (ааҗның + ааҗнең)
refused by the rule under WIDENED too.

### P7 gates (host, 2026-09-23)

| gate | result |
|---|---|
| verdict | **NOT SHIPPED** — G1 (manual review) and G2 (lift +0.57 pp) failed; classes stay {1} |
| `./gradlew test --rerun-tasks` | **1 437 tests (+13), 0 failures** |
| python pipeline suites | all green (`ALL_PYTHON_OK`, 484 tests) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `./gradlew assembleRelease -PskipReleaseSigning` | **1 851 944 B** ≤ 3 145 728 (+184 B over P2 — the unwired policy machinery) |
| `check-no-internet.sh` on the APK | Level 1 + Level 2 OK, backup whitelist closed |

---

# Final block: full gates + device UAT (2026-09-23)

## Full gates (final tree, all 2026-09-23)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 437 tests, 0 failures / 0 errors / 0 skipped** (148 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL (21 tasks executed) |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 851 944 B** (= the P7 recorded size — reproducible) → signed zopfli **1 834 276 B** ≤ 3 145 728, SHA-256 **`38f836fd0e3d8618fc62b9d9730c161302c7b3b51c864fcb82abc3182ad6525f`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** — version 2.0.1/33 matches `app/build.gradle`; changelog gate passes on the existing `metadata/en-US/changelogs/33.txt` |

## Device UAT — **BLOCKED (hardware absent); emulator fallback executed**

The POCO C71 was **physically disconnected for the whole session**: `adb devices` empty at
start, a 5-minute poll (15 × 20 s) and a later retry brought nothing, and `lsusb` shows no
Xiaomi device at the USB layer. **Nothing was installed on the device; all device rows are
BLOCKED, not passed.** The toggles/settings the device was left with (our IME as default,
Word suggestions / Personal dictionary / Incognito ON, the learned test pair сәләм → дөнья)
were not touched and stay as they are — the planned autocorrect-toggle restore is moot
(autocorrect was never enabled on the device; its user state is OFF by default).

Fallback evidence (explicitly NOT the POCO UAT): the debug build of the SAME tree on AVD
`tt_suggest_a14` (Android 14, 1080×2280), suggestions + autocorrect enabled via run-as prefs.
Probe word chosen offline BEFORE the run: `йорәк` — absent from the dictionary, exactly ONE
class-#1 candidate `йөрәк` (freq 2 190 ≥ 411), 5 code points — deterministic policy input.
Evidence: `build/device-uat-2026-09-23/p3/`.

| # | Scenario (emulator, debug build of the release tree) | Result | Evidence |
|---|---|---|---|
| F1 | Type `йорәк` → **preview band**: typed `йорәк` left (plain), correction `йөрәк` center — **bold, theme accent (blue), underlined**, third cell empty | PASS | `e01-strip.png` |
| F2 | Tap the typed cell ("keep") + space → field `йорәк ` — no replacement; the suppression consumed one-shot by the separator | PASS | `e02-strip.png`, field readback |
| F3 | Re-type `йорәк` → **preview returns** (a new occurrence); hit space directly → **replacement applied**: field `йорәк йөрәк ` | PASS | `e03-preview-again.png`, field readback |
| F4 | ONE keyboard backspace immediately → **reverted** to `йорәк ` (fresh field, clean window) | PASS | `e06-revert-clean.png` (field readback `йорәк `) |
| F5 | Delete the separator → **preview returns** (`йорәк | йөрәк*` again) — the pinned state machine | PASS | `e07-preview-returns-strip.png` |
| F6 | Autocorrect toggle OFF (prefs) → re-type `йорәк` → **no preview ever**: the ordinary band `йөрәк · йөрәкле · йөрәктән` (plain candidates, no emphasis) | PASS | `e08-strip.png` |
| F7 | Regression spot-checks: empty field → **Бу · Ул · Ә** (capitalized sentence start); `сцләм` → **сәләм** in cell 1 | PASS | `e09-sentstart-caps-strip.png`, `e10-sclam-strip.png` |
| F8 | Crash buffer of the whole emulator session | EMPTY (0 lines) | `e11-logcat-crash.txt` |

Harness artefact for the record (not an app defect): a `uiautomator dump` between the
replacement space and the undo backspace consumes the 5-event revert window — the undo
"failed" once in exactly that order and worked the moment the backspace was sent with no
intervening dump. Also recorded: a mis-typed residue run glued `йорәкйорәк` (no candidate,
no replacement — consistent with the policy).

BLOCKED (device-only, pending the phone's return): (a) the preview on the POCO C71 with the
theme accent in dark theme; (b) personal-bigrams regression across the app update + the
«сәләм → дөнья» forget cleanup (the pair stays learned in the device's tt store —
harmless, noted for the next session); (c) the full regression core on hardware; (d) cold
start ×3 + crash buffer on hardware.

## DONE-WHEN audit (Phase 3 of `docs/ROADMAP.md`)

- **P2 (autocorrect visual contract)** — *"emphasis visible on device"*: **met on the
  emulator with screenshot evidence of every state transition** (F1–F6); the POCO leg is
  BLOCKED (hardware absent) and stays open. The full state machine is JVM-pinned
  (`AutocorrectPreviewControllerTest`, 19 tests) and its pinned semantics matches the
  roadmap text's intent with the task-text's refinement (typed-cell tap = keep-this-
  occurrence, backspace = revert).
- **P7 (autocorrect widening, gated)** — *"shipped with gate numbers or rejected with gate
  numbers"*: **met by the P7 section above** — rejected with numbers (G1 manual review: 3
  bad + 1 hazardous of 5; G2 lift +0.57 pp vs ≥ +5 pp), the class set stays {1} everywhere
  (pinned), the machinery stays in the tree unwired and fully tested.
- All gates green (table above).

What remains: the BLOCKED device items above (a–d), the operator's commit/release
decision (2.3.0), and roadmap Phase 4+.
