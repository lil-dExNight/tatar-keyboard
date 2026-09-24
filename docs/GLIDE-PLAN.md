# GLIDE-PLAN — Phase 7: glide (swipe) typing

Status: approved work plan (operator: "реализовать фазы 5-7"). Research basis:
`/tmp/glide-research/` clones (FlorisBoard Apache-2.0 reference implementation,
AnySoftKeyboard PR #1870 parameter study), SHARK² literature, patent landscape
(fundamentals expired by 2025-09). Mission report: docs/ROADMAP-P7.md.

## DONE WHEN

1. Gliding over the Tatar keyboard produces word candidates in the strip;
   tapping or lifting commits; toggle `pref_glide_typing` (default ON,
   subordinate to the suggestions toggle).
2. Offline calibration on synthetic gestures meets the written recovery gates
   (below) on the real Tatar dictionary.
3. Tap typing is byte-identical when glide is off (pinned); no gesture
   conflicts (space/delete swipe, long-press, more-keys, key repeat).
4. Perf: decode of one gesture on the POCO C71 ≤ 5 ms p95; zero allocations
   in the draw loop; lookup-path contracts untouched.
5. All gates green; device UAT passes; the 3-cell strip contract holds.

## Design (from research)

- **Algorithm**: SHARK²-style two-channel statistical classifier — shape
  channel (resampled path, bounding-box normalized, pointwise L2, Gaussian
  σ≈22) × location channel (absolute coords, pointwise L1, σ≈0.51×key
  radius) × word frequency. No corpus, no ML, no NDK, no new assets.
- **Pruning**: extremity pruning (2 nearest keys to start × 2 to end —
  99.5 % dictionary rejection at 93.9 % sensitivity per PR #1870), then
  length-channel pruning; only survivors are scored.
- **Ideal paths**: each candidate word's ideal path = polyline through key
  centers; doubled letters add a small loop (the pool/poll trick).
- **MVP scope decisions**: decode once on ACTION_UP (no live scoring per
  MOVE); NO trail rendering in MVP (keeps the draw loop untouched — a
  follow-up can add it); dictionary candidates only (personal dictionary
  mixing is a follow-up); tt + ru layouts (the machinery is language-agnostic,
  the geometry table is per-layout).
- **Touch side** (hardest part): record the finger path in a preallocated
  ring buffer; glide detection by distance/time from down-point; when armed,
  suppress key previews/haptics/long-press timers/space-and-delete swipe
  branches; on UP hand the path to the controller. Read
  getHistoricalX/Y batches (currently dropped).
- **Engine side**: new LookupKind.GLIDE through the existing worker
  (LatestOnlyPrefixEngine), a third strip binding (displayedGlide*), commit
  via the predicted-word path, session stamps as everywhere else.
- **Calibration**: scripts/glide_pack.py generates synthetic gesture paths
  over corpus/eval words on the committed layout geometry (center-to-center
  polylines + a documented noise model), pinned like typo_pack; JVM
  calibration tests measure recovery.

## Phases and gates

### P7-1 — decoder core + offline calibration harness

Files: new `latin/glide/` package (Android-free: path model, resampling,
geometry table, pruning, scoring), `scripts/glide_pack.py` +
tests/glide_pack/, JVM calibration suites.

Written gates (before measuring):
- G1: top-3 recovery on the synthetic set (5-cp+ words, documented noise
  model) ≥ 60 %; top-1 ≥ 35 %. (Numbers from FlorisBoard/ASK-class systems;
  adjustable only with a dated reason.)
- G2: host decode p95 ≤ 2 ms at the survivor counts the pruner produces.
- G3: zero allocations after warmup in the decode path.

### P7-2 — touch integration

PointerTracker path recording + detection + suppression; historical batches
consumed. Gates: tap-typing byte-identical with glide OFF (pinned by
touch-path tests); with glide ON, taps still resolve as taps (slop rule);
gesture conflicts resolved by priority pins (space/delete swipe > glide when
the gesture starts on those keys).

### P7-3 — engine + strip + settings

LookupKind.GLIDE, third binding, commit path, casing (lowercase default +
shift state), toggle wiring, personal-dictionary exclusion (documented).
Gates: full JVM suites incl. new controller pins; integration e2e on real
assets (synthetic glide for сәләм → сәләм in top-3).

### P7-4 — calibration tuning + device UAT + release

Tune σ constants on the eval-derived synthetic set (and re-check against
overfitting with a held-out split), device p95 measurement on the POCO C71
(instrumentation harness), full UAT, docs (ROADMAP-P7.md), CHANGELOG,
HANDOFF. Release decision: operator.

## Explicitly out of scope

- Live per-MOVE scoring, trail rendering, personal-dictionary glide
  candidates, glide-triggered learning (follow-ups after the MVP proves
  itself).
- ML/neural decoders (violates zero-dependency constraint).
