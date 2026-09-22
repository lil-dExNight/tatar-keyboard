# ROADMAP — remaining prediction, UX and tech-debt work

Scope: everything recorded as remaining in the prediction engine, UX/design,
and technical debt after release 2.0.1. External operator actions (IzzyOnDroid,
relicensing letters, Common Voice download, store publishing) are NOT part of
this roadmap.

Working agreements (every phase, no exceptions):
- each phase is one release candidate: plan doc → implementation → all gates
  green (JVM + python + lint + no-internet + rebuild --check + size ≤ 3 145 728)
  → device UAT on the POCO C71 → marker commit + tag;
- behavior changes ship only behind the calibration gates written in the
  phase's plan BEFORE measuring (the TT-TYPO-NEXT discipline);
- frozen contracts are amended deliberately and recorded in the phase report;
- new docs in canonical English; commits by the operator, Russian,
  conventional.

## Phase order at a glance

| # | Phase | Content | Size | Version |
|---|-------|---------|------|---------|
| 1 | Quick wins & hygiene | P3, P4, T3, T4, T5, T6, T1 | S–M | 2.1.0 |
| 2 | Personalization | U7, U8, P1 | L | 2.2.0 |
| 3 | Autocorrect | P2, P7 | M | 2.3.0 |
| 4 | Prediction depth | P5, P6, T7 | L | 2.4.0 |
| 5 | UX polish & validation | U6, U1–U5 | M | 2.5.0 |
| 6 | Foundations | T2 | L | 2.6.0 |
| 7 | Glide typing | U9 | XL | 3.0.0 |

Order rationale: cheap high-visibility wins first; personalization features
that share the personal-dictionary store batched together (T5 must precede
them); the autocorrect visual contract lands before any autocorrect widening
so the widened behavior is visible and undoable; asset/eval-heavy prediction
work is batched; device validation and UX polish follow the feature waves;
refactoring happens only after features settle (merge-conflict avoidance);
glide typing is the flagship last mission.

## Phase 1 — quick wins & hygiene (2.1.0)

- **P3a. Sentence-start capitalization**: capitalize sentence-start
  suggestions at field start (shift-state seam; currently always lowercase).
- **P3b. Sentence-start for Russian**: build the ru table with the same
  pipeline; identical runtime, ru asset + pins.
- **P4. Prediction after `, ` and other non-final punctuation**: currently
  nothing is predicted there; use the committed word before the punctuation
  as next-word context (bigrams > forms > fallback), sentence-start stays
  exclusive to sentence-final punctuation. Contract amendment with tests.
- **T3. Move `tatar-keyboard-release.jks` out of the repo root** (untracked;
  release_pack.sh path param; no committed reference to the new location).
- **T4. Remove unused legacy layouts** (azerty/bepo/colemak/dvorak/workman/
  pcqwerty XML remnants; locales are tt/ru/en only). Verify no resource
  references remain; APK shrinks.
- **T5. Kill the manual `PersonalSubtypes` ↔ `LatinIME` subtype mirror**:
  single source of truth (the artifact registry), source-contract test.
- **T6. `.temp/` decision**: delete or gitignore (operator confirms content
  is junk).
- **T1. Regenerate the baseline profile** for the new hot paths (emulator;
  verify cold start stays < 400 ms on the device).

Done when: each item pinned by tests/checks; full gates; device UAT core
checklist passes.

## Phase 2 — personalization (2.2.0)

The privacy promise stays absolute: everything on-device, no new storage
leaves `no_backup/`, PRIVACY.md updated to describe every new store.

- **U7. Personal dictionary screen**: view learned words per language,
  delete one, clear all. Extends SettingsHostActivity; includes the personal
  bigrams store once P1 lands (or a follow-up row).
- **U8. Incognito mode**: pause all learning (words + bigrams) from a
  settings toggle; visible state; learned content never written while on.
- **P1. Personal bigrams**: learn the user's word pairs (after a clean
  committed pair, N ≥ 2 threshold, salted-hash counters first — mirroring the
  personal words design), per-subtype bounded store (LRU, size caps),
  ranking: personal pairs outrank static table successors only within
  defined limits (static bigrams first, personal pairs in free cells, then
  forms, then fallback — exact order calibrated and pinned); forgetting via
  the U7 screen and via LRU; quarantine/corruption handling mirrors the
  existing store discipline.

Done when: learning observed on device (type a pair twice → predicted),
deletion works, gates + UAT pass, PRIVACY.md covers the new store.

## Phase 3 — autocorrect (2.3.0)

- **P2. Autocorrect visual contract (AOSP standard)**: when a separator will
  trigger a replacement, the center cell shows the correction emphasized
  (bold/accent per theme), the typed word appears in the left cell and
  tapping it reverts; strip SuggestionStripView + controller wiring; tests
  pin every state transition; settings toggle unchanged (default off —
  emphasis visible only when autocorrect is enabled).
- **P7. Autocorrect widening (gated)**: with the correction now visible and
  undoable, calibrate widening — classes beyond #1 (geometric/substitution)
  and/or the frequency floor, against written gates: false-correction rate
  on correctly typed eval words must stay ~0, recovery lift measured on the
  typo sets. Ships only if gates pass; otherwise documented rejection.

Done when: emphasis visible on device; widened autocorrect either shipped
with gate numbers or rejected with gate numbers.

## Phase 4 — prediction depth (2.4.0)

Asset work; every candidate ships through written calibration gates.

- **P5a. More bigram heads**: raise H beyond 10 132 (size-budgeted) so
  conversational words like сәләм get successors; measure next-word hit-rate
  lift on the eval set; repack + pins + device re-inflation cost recorded.
- **P5b. Trigram evaluation**: 2-word context — measure potential hit-rate
  lift offline first (corpus stats), implement only if the written gate
  clears the size/CPU budget.
- **P6. Two-edit typo recovery (gated)**: cover cases like сэлэм → сәләм;
  activation only on empty exact pass, probe budget engineered like Phase
  C2; gates = recovery lift + precision + device p95.
- **T7. K=4 decision**: either show the 4th stored successor (4-cell strip
  variant) or repack at K=3 and reclaim ~49 KB; decide by measurement,
  implement the chosen side.

Done when: each item shipped with improved eval metrics or rejected with
numbers; APK size still ≤ budget; device UAT.

## Phase 5 — UX polish & validation (2.5.0)

- **U6. Keyboard height preference** (settings; existing 5/6-row variants
  are the starting point).
- **U1. TalkBack pass**: human-verified announcements for keys, strip
  content, emoji panel; fix what fails (focused mini-mission inside the
  phase).
- **U2. Direct Boot with PIN** — verify + fix.
- **U3. Telegram field** (and one more popular messenger) — verify + fix.
- **U4. Gesture navigation** — verify + fix.
- **U5. Real tablet** — verify layout/Enter; fix what fails.
Each Ux item: verify on device, file concrete bugs, fix, re-verify.

Done when: every scenario verified on device with evidence; fixes gated.

## Phase 6 — foundations (2.6.0)

- **T2. Split the god objects**: LatinIME.java (2 331 lines),
  SuggestionsController.kt (2 100+), SettingsHostActivity.kt (1 293),
  EmojiPanelView.kt (1 317) — extract cohesive units one at a time, pinned
  by the full suite after each extraction; no behavior change allowed
  (refactor-only commits, gates must stay byte-green).
- Final baseline-profile regeneration.

Done when: no file above a negotiated ceiling (e.g. 1 500 lines) without a
recorded exception; full gates; device UAT.

## Phase 7 — glide (swipe) typing (3.0.0)

The flagship mission. No open-source implementation exists in AOSP lineage
(Google's is a closed library) — research phase first: algorithms (spatial
model + dictionary beam search), geometry reuse from KeyNeighborTable,
personal+dictionary+wordform integration, perf budget on POCO C71, then a
written plan with prototypes and gates. Only after the plan is approved:
implementation, calibration, UAT, release as 3.0.0.

## Explicitly out of this roadmap

- IzzyOnDroid submission, relicensing letters, Common Voice download —
  operator actions (CV data would feed Phase 4 if provided).
- Store publishing of each release (manual GitHub Release while gh is
  pull-only).
- New locales/layouts (frozen at tt/ru/en).
