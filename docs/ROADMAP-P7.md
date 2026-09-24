# ROADMAP-P7 — phase 7 report: glide (swipe) typing

Status: **P7-1 (decoder core + offline calibration harness) done 2026-09-24**
— all three written gates pass on the held-out split of the synthetic gesture
set against the real shipped Tatar dictionary; **P7-2 (touch integration) done
2026-09-24** — the decider + the PointerTracker surgery are in, pref-gated and
fail-closed, the controller side is a documented stub (P7-3 wires it);
**P7-3 (engine + strip + settings) done 2026-09-24** — LookupKind.GLIDE, the
third strip binding, the settings row, and the real-assets e2e pins (сәләм in
top-3 → commit → the сәләмә · һәм · белән chain; the Russian layout too). What
remains: P7-4 calibration on device + UAT + release decision.

Plan: `docs/GLIDE-PLAN.md`. Reference implementation studied: FlorisBoard's
`StatisticalGlideTypingClassifier.kt` (Apache-2.0, (C) the FlorisBoard
contributors) — the SHARK²-style two-channel math and the AnySoftKeyboard
PR #1870 parameter values are ported with attribution; every line of code here
is written fresh for this engine's discipline.

## P7-1 — decoder core + offline calibration harness

### What was built

New Android-free package `rkr.simplekeyboard.inputmethod.latin.glide`
(a JVM-source-contract test pins: no Android imports, no Cyrillic literals in
code, no logging):

- `GlidePath` — fixed-capacity (512) parallel float buffers x/y/t; over-capacity
  points are dropped (fail-closed: a truncated gesture decodes worse, never
  wrong-er than its data).
- `GlideKeyGeometry` — letter → center/rect table (sorted code points + flat
  parallel arrays, binary-search lookup, zero-alloc repeated-minimum nearest-key
  scan). Built by a pure function from plain `RawKey` records; the live-Keyboard
  adapter `GlideKeyGeometryBuilder` (suggestions package, mirroring
  `KeyNeighborTableBuilder`) is the single Android crossing and is unused until
  P7-2.
- `GlideResampler` — arc-length resampling into EXACTLY N=200 points (both
  endpoints included; zero-length paths/segments degenerate cleanly) and
  bounding-box normalization by the longest side. Deliberate deviation from the
  reference: it targets approximately N points and reads past the end as zeros;
  exact-N keeps the pointwise channel sums aligned on both sides.
- `GlideIdealPaths` — per-word ideal polyline writer (key centers; a doubled
  letter contributes the four quarter-key loop corners, the pool/poll trick).
- `GlideWordIndex` — the once-per-dictionary start/end key-pair index (CSR
  buckets keyed by firstKey × lastKey, entries in dictionary order), plus
  per-entry key sequences (bytes), frequencies and BOTH ideal-path lengths
  (plain / looped) precomputed — the length pruner is two float reads per
  candidate. Measured on the shipped 110 000-entry Tatar dictionary (printed by
  the calibration test): **109 178 indexed, 822 skipped** (words with
  more-key-only letters — they can never be glide candidates, fail-closed),
  **retained ≈ 3 158 405 B (~3.0 MiB)**, build **≈ 17-19 ms** on the host, 1 067
  of 1 369 buckets non-empty, largest bucket 2 302 words. The build is lazy
  (first decode) and happens on the engine worker — already a background
  thread; the index is immutable and published through a `@Volatile` reference.
- `GlideDecoder` — the decode entry `decode(path, out) -> count`:
  1. extremity pruning — 2 nearest keys to the gesture's start × 2 to its end
     select ≤ 4 CSR buckets (the study's ~99.5 % rejection);
  2. length pruning — |userLength − idealLength| < 8.42 × key radius, plain or
     looped variant;
  3. scoring per survivor — shape channel (bbox-normalized pointwise L2 over
     the resampled paths, Gaussian) × location channel (absolute pointwise
     L1/2, Gaussian) × frequency weight, best of the word's (plain, looped)
     variants, fail-fast against the current k-th worst: frequency pre-reject,
     then monotone-partial-sum early bails inside both distance loops (the
     bails are verdict-exact: partial sums only grow). Top-8 out; ties break on
     dictionary order; deterministic; zero-alloc machinery after warmup.

Dictionary integration (decode side only): `GlideWordInventory` seam +
`TdictGlideInventory` adapter over a new `TdictPrefixIndex.forEachWordCold` — a
cold sequential walk with local varint state (the `containsWordCold`
discipline), so the index build never touches the per-keystroke lookup path,
its block cache, or its budgets. The per-keystroke contracts are untouched; the
new engine code is covered by `TdictPrefixIndexColdWalkTest`.

### The synthetic gesture set (scripts/glide_pack.py)

Deterministic, stdlib-only, fail-closed, pinned exactly like typo_pack.
4 498 gestures (set SHA-256 `ea7a58fa…c4e59549`, 9 181 165 B), word sources:
eval-file tokens AND a deterministic 1/40 thinning of the dictionary, all words
≥ 5 code points with every letter on the layout, union sorted by code point.
Geometry: typo_pack's device-true x model plus a documented vertical model
(default 5-row Tatar keyboard at 205.6 dp on a 1080 px/440 dpi reference
screen, rounded half-up per value, scaled into the 100 000-unit grid).
Noise model (per-word SplitMix64 stream, all draws in a documented order;
integer-exact — the single square root is an IEEE correctly-rounded double on
both sides — so the JVM calibration regenerates the set byte-for-byte and pins
the same SHA):

1. doubled-letter loop drawn with probability 1/8 (users rarely draw it);
2. sampling step per word ∈ [w/6, w/3) of the narrowest key (~12-25 device px);
3. timestamp step 8-16 ms/sample (carried, unused by scoring);
4. corner cutting: each interior non-loop vertex independently (p = 1/10)
   pulled halfway toward its chord, V′ = (P + 2V + Q)/4, integer floor;
5. integer segment walk (rounded segment lengths, floor-division interpolation);
6. smooth wander: clamped random walk of the per-point offset, envelope ±18 %
   of the key radius, increments ±envelope/6.

The walk's step deliberately produces ~113 points per average gesture (max
457) — denser than a real digitizer's, exercising the resampler.

### Calibration numbers (train/held-out)

Deterministic split per word (SplitMix64 bit, seed 20260925): train 2 189,
held-out 2 309. Constants were tuned against the TRAIN split only; held-out
carries the reported numbers.

**Held-out (shipped constants): top-1 69.94 %, top-3 77.57 %; train: 70.08 /
78.07 %** — sub-1 pp gap, no overfitting. Decode latency (host): p50 0.32 ms,
p95 1.19 ms, max 2.28 ms; candidates visited p95 2 741 (max 4 225), fully
scored p95 1 204.

Shipped constants (`GlideConstants` defaults): `sampleCount = 200`,
`extremityNeighbors = 2`, `lengthThreshold = 8.42`, `frequencyWeight = 255`
(ported PR #1870 values); `shapeStd = 11.04`, `locationStdFactor = 0.18`,
`frequencyExponent = 0.25` (tuned). The tuning surface on the train split
(top-3 %, γ = 0.25):

| shapeStd \ locFactor | 0.1277 | 0.18 | 0.2555 | 0.5109 (PR #1870) |
|---|---|---|---|---|
| 5.52 | 77.88 | 77.88 | 77.60 | 77.05 |
| 8.28 | 77.97 | 77.97 | 77.70 | 77.05 |
| **11.04 (shipped)** | 77.70 | **77.79** | 77.79 | 76.96 |
| 16.56 | 77.51 | 77.60 | 77.79 | 76.50 |
| 22.08 (PR #1870) | 77.42 | 77.51 | 77.33 | 75.58 |

The plateau is broad (76.5-78.1 everywhere) — the shipped cell is
plateau-center, not a knife edge. γ sweep at shipped sigmas (train top-3 %):
0.00 → 77.60, 0.15 → 77.79, **0.25 → 77.79**, 0.35 → 77.79, 0.50 → 77.60,
0.75 → 77.05, 1.00 → 76.87 — a mild frequency prior costs nothing on the
synthetic set and stays available for real-world shape ties.

### Gates (written in the plan before any code)

- **G1: PASS.** Held-out top-3 77.57 % ≥ 60 %; top-1 69.94 % ≥ 35 %.
- **G2: PASS.** Host decode p95 1.19 ms ≤ 2 ms (at the pruner's real survivor
  counts, p95 2 741 candidates visited / 1 204 scored).
- **G3: PASS, with the documented result-materialization carve-out.** After
  warmup, a pruned-out decode allocates **0 B/call**; a result-bearing decode
  allocates only the surfaced strings — measured 1 480 B/call at the full 8
  candidates (~185 B per word: the String plus its decoded backing), pinned at
  ≤ 8 × 256 B. Same contract the per-keystroke `lookup()` already has.

### Deviations from the plan (all documented, all decided on measurements)

1. **Frequency weight law.** The reference's `255 × frequency` presumes a
   frequency byte quantized 0..255 (FlorisBoard's `getFrequencyForWord` is
   `stored/255.0`); our raw counts span 1..660 385, and a linear normalization
   drowns both channels (measured: held-out top-3 32.65 % at γ = 1.0). The
   weight is `255 × (f/maxFreq)^γ` with γ = 0.25 — the dynamic-range
   compressor is the tunable, exactly as the quantized byte is the reference's.
2. **Noise model: smooth wander, not independent per-point jitter.** The first
   implementation jittered each sample independently (±28 % key radius); that
   inflates the path length ~2.7× (killing the true word at the length pruner)
   and is not how finger paths deviate. The clamped random walk is the
   documented replacement. For the record, the very first honest measurement
   (harsh noise + PR #1870 sigmas + linear frequency) was top-3 32.65 % —
   fixed by the documented tuning, one round.
3. **Tuned sigmas.** σ_shape 22.08 → 11.04, σ_location-factor 0.5109 → 0.18:
   the sharper location channel is what separates one-key-apart competitors at
   this keyboard's scale; both shipped values sit inside a broad flat plateau
   (table above), and the train/held-out gap is < 1 pp.
4. **Exact-N resampler** (see `GlideResampler`).

### Watch items for P7-2/P7-3/P7-4

- Device p95: host 1.19 ms at ~1 200 scored candidates; the POCO C71 budget is
  ≤ 5 ms (P7-4 instrumentation decides; the monotone early bails are the
  lever if it overshoots).
- Words with more-key-only letters (ё/ъ) are never glide candidates (822 of
  110 000 skipped) — acceptable for the MVP; the Russian layout decision (ё is
  a long-press there too) is documented for P7-3.
- The path cap (512 points) truncates gestures longer than ~4-8 s of travel;
  the synthetic set's maximum is 457.
- Index build is lazy on first glide (~17-19 ms host once per dictionary); P7-3
  may move it to engine startup.

### Files

- Main: `latin/glide/` — `GlidePath.kt`, `GlideKeyGeometry.kt`,
  `GlideResampler.kt`, `GlideIdealPaths.kt`, `GlideWordIndex.kt`,
  `GlideDecoder.kt`, `GlideWordInventory.kt` (seam + `TdictGlideInventory`);
  `latin/suggestions/GlideKeyGeometryBuilder.kt` (unused until P7-2);
  `TdictPrefixIndex.kt` gained `ColdWordVisitor` + `forEachWordCold` (additive,
  cold, off the lookup path).
- Tests: `app/src/test/.../glide/` — fixtures + `GlideResamplerTest`,
  `GlideKeyGeometryTest`, `GlideWordIndexTest`, `GlideDecoderTest`,
  `GlideSourceContractTest`, `GlideRecoveryCalibrationTest` (set-identity pin,
  index stats, G1/G2, tuning surface, G3); `TdictPrefixIndexColdWalkTest`.
  JVM 1 456 → 1 494.
- Pipeline: `scripts/glide_pack.py` + `tests/glide_pack/test_glide_pack.py`
  (python 484 → 505).

## P7-2 — touch integration (done 2026-09-24)

### Detection design (`GlideGestureDecider`, pure Kotlin, Android-free)

One small state machine per pointer: IDLE → TRACKING (eligible down) → ARMED |
REJECTED. Eligibility is decided once at DOWN: the glide preference on AND the
down key a letter key (`Character.isLetter` — space, delete, shift, enter and
the digit row never start a glide; their swipe/long-press behaviors keep
priority by construction). A TRACKING gesture arms when it has travelled more
than one key width from the touch-down point at a velocity above 0.10 dp/ms,
decided within a 500 ms window — FlorisBoard's `GlideTypingGesture.Detector`
constants (Apache-2.0), with two deliberate deviations: the decision uses the
MotionEvent's own `eventTime` (monotonic, not wall-clock), and the distance
threshold is the live keyboard's `mMostCommonKeyWidth` in px instead of a dp
dimen (the decider takes pixels, staying density-free and JVM-testable).
ARMED is sticky within the touch; after the window a touch can never become a
glide (a slow press is a long-press candidate); `cancelGlide()` drops
TRACKING/ARMED to REJECTED so the gesture can never deliver.

### The PointerTracker surgery (minimal, all branches ahead of legacy)

- DOWN (`onDownEventInternal`): starts the decider, opens the path buffer with
  the down point. Pref OFF → decider sits in REJECTED, every glide branch is
  dead, and the touch path is byte-identical to the pre-glide code (pinned by
  `GlideTouchIntegrationContractTest` at source level; PointerTracker's static
  state needs a live `Resources`, so it cannot be instantiated in a JVM test —
  the behavioral pins live in the decider tests, exactly like the
  KeyDetector-level pins of SlidingModifierSlopTest).
- MOVE (`onMoveEventInternal`): an ARMED glide feeds the path and returns —
  ahead of the space/delete swipe branches, so a glide passing over space or
  delete mid-path is never hijacked. A TRACKING (undecided) gesture feeds the
  decider first — a gesture that arms on this very point is a glide, not a
  cursor swipe — and then the legacy sliding-key-input behavior proceeds
  unchanged (a cursor swipe that already started can no longer become a glide).
  `processMotionEvent` now consumes the historical MOVE batch
  (`getHistoricalX/Y/EventTime`) for glide-active pointers — the stock flow
  dropped it, starving the path of half its samples; never fed to a more-keys
  panel.
- Arming (`armGlide`): cancels the pointer's long-press/repeat timers
  (`cancelKeyTimersOf`) and dismisses the last-entered key's preview. Until
  arming, the legacy path runs verbatim — the down key's press feedback and
  possibly one early key-change preview fire (unavoidable: detection needs
  movement; same as the reference). After arming: no previews, no haptics, no
  long-press, no repeat. The in-flight timer callbacks (`onLongPressed`,
  `onKeyRepeat`) carry armed guards.
- UP (`onUpEventInternal`): an armed glide delivers its path through the new
  `KeyboardActionListener.onGlideInput(GlidePath)` and never commits a key;
  the delivery is guarded by `!mIsTrackingForActionDisabled` (fail-closed).
  The path is the tracker's live buffer, valid only during the call (the
  P7-3 receiver snapshots via `GlidePath.copyInto`).
- Multi-touch: a second finger's DOWN cancels every other pointer's ARMED
  glide (`cancelArmedGlideTrackersExcept` — fail-closed, nothing committed);
  a still-undecided TRACKING gesture is left alone (two-finger chording is
  today's behavior). A phantom up cancels the glide before the up handling.
  CANCEL resets both decider and path.

### The controller side (stub by design)

`KeyboardActionListener.onGlideInput` is a `default` no-op — every existing
caller compiles untouched. `LatinIME` carries an explicit no-op override that
marks the P7-3 wiring point (snapshot → engine worker → third strip binding).
Nothing commits until P7-3.

### The preference

`pref_glide_typing` (default ON) exists from P7-2 and is read subordinate to
the suggestions master toggle — `Settings.readGlideTypingEnabled` mirrors the
emoji-suggest idiom; `SettingsValues.mGlideTypingEnabled` is the field the
touch side reads per gesture. The settings UI row lands with P7-3.

### Tests (P7-2)

- `GlideGestureDeciderTest` (12): tap never arms; slow long-press rejected
  after the window; ineligible start (space/delete/digit) never arms; fast
  glide arms; distance alone / velocity alone are not enough; thresholds are
  strict; arming is sticky; cancel drops TRACKING and ARMED to REJECTED; a new
  down resets; the reference constants are pinned (500 ms, 0.10 dp/ms).
- `GlidePathTest` (4): length/first/last; snapshot copy semantics; capacity
  cap; empty path.
- `GlideTouchIntegrationContractTest` (9): branch order ahead of the legacy
  swipe branches; the legacy swipe branches' verbatim survival; fail-closed UP
  delivery before the key commit; multi-touch + phantom-up cancels; arming
  cancels timers and releases the preview; the historical batch is consumed
  for glide-active pointers only (never a more-keys panel); eligibility at
  DOWN (pref + letter gate); the listener default no-op + the LatinIME stub;
  the preference's subordination to the suggestions toggle.

### Deviations and notes

- `eventTime` over wall-clock, px thresholds over dp (see the design section).
- The pre-arm legacy feedback (down-key press, possibly one key preview) is
  kept deliberately: suppressing it would mean second-guessing the detector
  before it can possibly decide, and the reference does the same.
- A slow drag that reaches space/delete before arming is today's cursor swipe
  — only gestures that arm (fast by definition) are glides; this is the slop
  rule working, and the "passes over space mid-path" pin covers ARMED glides.
- 512-point path cap: a glide longer than ~4-8 s of travel keeps its leading
  prefix (fail-closed, documented in P7-1).
- **2026-09-24 (fix, after the 3.0.1 release cut):** the reference detector
  measures the detection window and the velocity from touch-DOWN, so a user
  who rests a finger on the first key ~300-500 ms before moving is rejected
  forever — field report: "зажимаю букву и начинаю вести её в сторону второй
  — не работает". Fixed by anchoring both clocks at the first sample that
  leaves a small slop (a quarter of the key width, just above the platform's
  8 dp touch slop; `GlideGestureDecider.DEFAULT_SLOP_KEY_WIDTH_FRACTION`):
  while no sample exceeds the slop the machine stays TRACKING regardless of
  elapsed time (a still finger is a long-press candidate, not a rejected
  glide). The distance threshold is unchanged (one key width from down); an
  immediately-swiped gesture anchors on its first move sample and behaves
  exactly as before. A long-press that actually FIRES while the machine is
  still TRACKING cancels the decider (`PointerTracker.onLongPressed` — a finger
  moving after the panel opened is a panel selection, never a glide; the panel
  branch already ate those MOVEs, this makes the boundary fail-closed).
  Pins: `GlideGestureDeciderTest` 12 → 17 (hold-then-swipe arms even past the
  old window; the window runs from the first move; the velocity is measured
  from the anchor; the slop boundary is strict; an immediate swipe is
  unchanged; a still finger never starts the clock) and the integration
  contract gained the fired-long-press cancel pin. Device evidence on the POCO
  C71: `GlidePointerDeviceTest` (real PointerTracker, real MotionEvents, real
  700 ms hold) 3/3 — hold-then-swipe delivers, immediate swipe delivers, a
  fired long-press never does; and `GlideUiDeviceTest` (the full live UI: the
  debug IME over the SetupActivity try-it field, system-level injection with a
  real hold) — the strip showed сәлләм · сәләм · сайлыйм and the cell tap
  committed "сәләм " (screenshot + run log in build/glide-uat-2026-09-24/).
  Gates: JVM 1 543 → 1 548, python 507, lintRelease, rebuild --check, release
  APK 1 860 960 B, check-no-internet — all green.

### Gates (P7-2)

JVM 1 519 (1 494 + 25), `--rerun-tasks` 0 failures; python 505 OK;
lintRelease OK; rebuild_assets --check OK; release APK 1 854 116 B (budget
3 145 728); check-no-internet OK (source + debug + release APK). Device
smoke: not run (P7-4 owns device UAT; the touch path with the pref OFF is
pinned identical by the contract tests).

### Files (P7-2)

- Main: `latin/glide/GlideGestureDecider.kt` (new), `GlidePath.kt` gained
  `copyInto`; `keyboard/PointerTracker.java` (the surgery above),
  `keyboard/KeyboardActionListener.java` (the default no-op),
  `latin/LatinIME.java` (the P7-3 stub override),
  `latin/settings/Settings.java` + `SettingsValues.java` (the pref).
- Tests: `GlideGestureDeciderTest`, `GlidePathTest`,
  `GlideTouchIntegrationContractTest`.

## P7-3 — engine + strip + settings (done 2026-09-24)

### The engine path

`LookupKind.GLIDE` joins PREFIX/NEXT_WORD with the same token, serial and
staleness discipline: `requestGlide(editorSessionId, subtypeId, path)` on
`LatestOnlyPrefixEngine` snapshots the path inside the request (the caller's
GlidePath is the PointerTracker's live buffer; the snapshot is one allocation
per gesture, off every hot path), and the token's `normalizedQuery` carries a
documented empty sentinel — identity lives in the serial. A degenerate path
(< 2 points) is rejected WITHOUT invalidating the generation: unlike an empty
prefix ("the user cleared the word"), a junk gesture says nothing about the
text. The worker's drain routes GLIDE to `(computer as? GlideComputer)` — a
separate call, so the per-keystroke lookup budgets are untouched.

The decode side: `GlideDecoderHost` (engine package) owns the lazily built
`GlideDecoder` — the word index builds once per engine on the FIRST decode, on
the engine worker, which is already a background thread — plus the current
layout geometry, pushed from the UI thread by a `@Volatile` swap
(`updateGlideGeometry`, the `updateKeyNeighbors` shape). A geometry change
rebuilds the decoder (the word index's key indices are only meaningful against
the geometry they were built from); a null or empty geometry fails closed.
The host hangs off `CompositePrefixComputer` (which implements the two new
seams and delegates), so the Russian engine works identically — its own
dictionary, its own layout's geometry. `TdictGlideInventory` moved from the
glide package to the engine package on the way: the dependency direction is
now strictly engine → glide, and the glide package depends on nothing.

`EngineHandle` gained `requestGlide` (default null) and `updateGlideGeometry`
(default no-op); `MappedEngineHandle` forwards both.

### The controller (third strip binding)

`displayedGlideContext` is the third binding, the exact shape of
`displayedPrefix`/`displayedContextWord`: a glide band is bound to the
NEXT_WORD context of the gesture moment (the word before the cursor, "" at a
field start), session-stamped; every binding site in the controller clears the
other two (the invariant "exactly one binding non-null" now covers three).
`onGlideInput` (called by LatinIME on the UI thread) fails closed in every
direction: controller destroyed or ineligible, the glide toggle off (re-checked
live, `GlideGate` — same seam shape as the autocorrect gate), no usable
engine, unknown cursor, a letter right after the cursor, or a half-typed
trailing word (the decoder decodes whole words; completing a typed prefix by
glide is not the MVP). Nothing shows during the gesture (decode once at
ACTION_UP). The empty-result answer is the reserved empty band.

`applyGlideResult` paints at most three cells; casing is the display-time rule
of the prefix path, sourced from the new `ShiftStateGate` (wired in LatinIME
from the live keyboard's element id: the manually or automatically shifted
alphabet element means the committed word is capitalized, exactly as typed
letters would be). A tap on a glide cell commits through the E5d
`commitPredictedWord` path — its live re-checks (collapsed cursor, empty
trailing word, the context still equal) are the second line of defense — with
the band unbound, the pair boundary trusted (`trustPairBoundary`), and the
NEXT_WORD chain for the committed word requested immediately. Learning
semantics, pinned: a glide-committed word behaves exactly like a tapped
suggestion — NOT a clean run for the word itself (the shared `markRunDirty`
already did that), and deliberately NOT `noteAcceptedPrediction` (the band was
not a pair prediction, so no usage counter moves). The personal dictionary is
never consulted for glide candidates (main dictionary only, documented MVP
decision); no companion fill rides a glide band.

### The settings row

`pref_glide_typing` gets its switchRow in the preferences screen, default ON
(the reader and the screen share the default — pinned by the contract test),
grayed when the suggestions master switch is off (the same subordination dance
as the emoji-suggest row). Strings in en/ru/tt; the two tt rows are in
`docs/archive/dictionary/TATAR-REVIEW-QUEUE.tsv` (approved, dExNight,
2026-09-24, per the 2026-08-20 convention).

### The e2e pins (real assets, JVM)

`GlideEndToEndTest` (suggestions package): the controller driven by an engine
handle backed by the production machinery — the real Tatar and Russian
dictionaries, the real Tatar bigram table, the production NEXT_WORD shape
(suffix rules + forms + fallback), and the real `GlideDecoder` over the fixture
layout geometries:

- a synthetic glide for сәләм over the Tatar layout → the strip shows сәләм in
  the top-3 (the decoder ranks сәлләм first on this path — the doubled letter's
  plain ideal is a zero-length jog, so the pair is a degenerate-path collision
  decided by the frequency prior; the pin is top-3 + commit, as planned);
- the tap commits "сәләм" with auto-space through the predicted-word path, and
  the strip immediately shows the pinned chain сәләмә · һәм · белән;
- shift on → the strip shows and the tap commits "Сәләм";
- the same on the Russian layout against the Russian dictionary (работа);
- the glide toggle off → nothing; a half-typed word in front → nothing; no
  geometry pushed → nothing; a session bump between the gesture and the tap →
  the tap is a no-op (stale-tap fail-closed).

`MappedDictionaryEngineGlideTest` pins the engine discipline on a fixture
dictionary: GLIDE decodes through the worker with the right kind; a newer
prefix request stales an in-flight glide result and vice versa; no geometry →
empty with the right kind; an empty geometry → empty; a degenerate path is
rejected without invalidating a pending prefix request.

### Deviations and notes (P7-3)

- The glide token carries an empty query sentinel (documented): the path is
  the request payload, the serial is the identity.
- Casing is read off the keyboard's shift state through a controller seam, not
  off typed text (a gesture types nothing) — the one structural difference
  from the prefix path.
- `TdictGlideInventory` moved glide → engine (dependency direction).

### Gates (P7-3)

JVM 1 534 (1 519 + 15), `--rerun-tasks` 0 failures; python 505 OK;
lintRelease OK; rebuild_assets --check OK; release APK 1 862 388 B (budget
3 145 728); check-no-internet OK (source + debug + release APK). Device smoke:
P7-4.

### Files (P7-3)

- Main: `latin/glide/GlideComputer.kt` (the two seams); `engine/`
  `GlideDecoderHost.kt` + `TdictGlideInventory.kt` (moved), and GLIDE wiring in
  `LatestOnlyPrefixEngine`, `CompositePrefixComputer`, `MappedDictionaryEngine`;
  `suggestions/EngineHandle.kt` (requestGlide + updateGlideGeometry),
  `suggestions/SuggestionsController.kt` (the third binding),
  `suggestions/SuggestionSurfaces.kt` (GlideGate + ShiftStateGate);
  `latin/LatinIME.java` (the receiver, the gates, the geometry push);
  `settings/SettingsHostActivity.kt` (the row); strings en/ru/tt.
- Tests: `MappedDictionaryEngineGlideTest` (6), `GlideEndToEndTest` (8), the
  extended `GlideTouchIntegrationContractTest`; `GlideTestFixtures` gained the
  Russian layout + the shared ideal-path helper.
- Docs: `TATAR-REVIEW-QUEUE.tsv` +2 rows.

## P7-4 — calibration tuning + device iteration (perf pass done 2026-09-24)

### The failure and the iteration

The first device measurement of the decode on the POCO C71 (the
`GlideDeviceInstrumentationTest` harness, debug build, 1 000 samples over 6
probe words, deterministic, no GC) FAILED the written gate: **p95 40.0 ms
against ≤ 5 ms** (host p95 1.19 ms). A temporary stage profiler in the decoder
(removed after the iteration) gave the breakdown per decode (~1030 candidates
visited, ~211 scored, ~237 variants): the per-variant resample + normalize +
shape + location passes were ~94 % of the time; weight/ff1, the ideal write and
the user side were noise.

What the iteration established, in order:

1. **Fusion.** The per-variant pipeline (resample → normalize → shape loop →
   location loop) became ONE loop: the ideal path's arc-equidistant points are
   produced on the fly by the same segment walk the resampler runs (identical
   arithmetic; the segment lengths are computed once by the path writer, the
   interpolation divides turned into multiplies by cached reciprocals), and
   both channels accumulate against them. Both sides' shape normalization moved
   to the RAW path bbox (the fused loop needs the factors before it starts; the
   raw extent is the honest one — a resampled path can miss an unsampled
   extremal vertex). Device: 40.0 → **27.3 ms**; host p95 1.22 → 0.83 ms.
   Recovery bit-identical (held-out 77.5660 % top-3 before AND after — the
   normalization change is recovery-neutral on the calibration set).
2. **No-ops measured and rejected:** the per-point division kill (27.3 → 27.3),
   JIT warmup depth (100 → 1 000 warmups: 40.0 → 36.5 — the cost is not
   interpretation warm-up), a 3-probe arc-fraction prefilter (distributions
   overlap: true p90 = 6.9 radii vs others p5 = 5.1 — the wander model defeats
   it), a tighter length channel (true words reach |Δlen| = 14 radii at p99 —
   the 8.42 threshold is earned, not loose).
3. **The library calls were the wall.** A raw microbenchmark on the device:
   `kotlin.math.abs(Float)` per call ~1 847 ns, `Math.sqrt` per call ~594 ns —
   while a manual select (`if (d < 0) -d else d`) costs the same ~370 ns as a
   mul-sub iteration. The fused loop called sqrt once AND abs twice PER POINT.
   Replacing all three with manual branches and switching the shape channel to
   pointwise **L1** (same family as the location channel; the Gaussian sigma
   re-validated, not re-tuned — the grid says the plateau is flat there):
   device **27.3 → 3.4 ms**.

### Final numbers (same harness, same device, same build kind)

- **Before: p50 16.1 ms, p95 40.0 ms** (the reported 50.06 was a colder run of
  the same code). **After: p50 1.60 ms, p95 3.37 ms, max 4.80 ms — PASS** vs
  the ≤ 5 ms gate. Host: p95 0.83 ms (G2 ≤ 2 ms holds with more margin).
- The live-geometry decode proof still lands сәләм in the top-N (second, after
  сәлләм — the degenerate-path pair documented in P7-3).
- **Recovery delta: none within noise, in fact marginally up** — held-out
  top-1 69.94 → 70.07 %, top-3 77.57 → 77.70 % (the ±0.5 pp budget is met);
  train 70.08/78.07 → 70.17/78.07. The tuning surface on the train split is
  flat around the shipped constants under the L1 shape channel too
  (77.5-78.2 % across the σ grid), so NO constant moved in this iteration.
- G3 holds: pruned-path decode 0 B/call; result decode materializes only the
  surfaced strings (pinned ≤ 8 × 256 B).

### What changed semantically (all documented here)

- Shape channel: pointwise **L1** instead of L2 (the location channel was
  always L1). The Gaussian σ_shape = 11.04 is unchanged — validated by the
  calibration suite's held-out numbers, not re-tuned.
- Shape normalization: bbox of the RAW path (both sides) instead of the
  resampled samples' bbox.
- Scoring structure: one fused loop per variant instead of four passes;
  segment lengths + reciprocal cached per variant by the path writer; the
  location channel's own early-bail is subsumed by the shape bail (a
  shape-bailed candidate never pays location samples).
- Buckets of the word index are now frequency-sorted at build (visit order
  best-frequency-first, deterministic; merge at decode). Measured as a
  candidate-count cut — it is NOT what moved the needle (the frequency
  fail-fast bound is weak against the σ plateau); kept because it is free and
  verdict-exact.

### Gates after the iteration

JVM 1 534, `--rerun-tasks` 0 failures; python 505 OK; lintRelease OK;
rebuild_assets --check OK; release APK 1 863 324 B (budget 3 145 728);
check-no-internet OK. The instrumentation harness keeps the two P7-4 tests
(the latency gate + the live-geometry decode proof); the temporary profiler
and the raw microbenchmark were removed after the iteration.

---

## P7-4 — device calibration + UAT (2026-09-24)

### Gates (final tree, all 2026-09-24)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 505 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 534 tests, 0 failures / 0 errors / 0 skipped** (162 suites) |
| `./gradlew lintRelease --rerun-tasks` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 863 324 B** → signed zopfli **1 842 468 B** ≤ 3 145 728, SHA-256 **`048f04b9…00da14`** (the verifier's twice-reproduced current build; the `9c7420a3…` recorded in the first cut of this table was the PRE-fix build), v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** (version 2.0.1/33, changelog on the existing 33.txt) |

### Device decode measurement (the P7-4 perf gate) — initially FAIL, then PASS after the perf iteration

> **2026-09-24 (reconciled):** the first measurement below stands as the honest record of
> the PRE-fix decoder; the perf iteration (the dated section above, "…No-ops measured…
> library calls were the wall… Final numbers") brought the same harness to **p50 1.621 ms,
> p95 3.388 ms, max 4.744 ms — PASS** vs the ≤ 5 ms gate, re-measured on the POCO C71 this
> day with the raw logcat saved to `u17-instrument-logcat.txt` (the verifier's gap — the
> number now has saved raw evidence).

New instrumentation `GlideDeviceInstrumentationTest`
(`app/src/androidTest/.../glide/`, the E3b-harness shape — legacy JUnit3 runner,
resolves offline, never in the release APK): the LIVE Tatar keyboard is built through
the production `KeyboardLayoutSet` path, the real 110 000-entry Tatar dictionary is
inflated, and the decoder runs over realistic ~8 px-sampled paths.

- `testSalamDecodesOnTheLiveGeometry` — **PASS** (both eras): a path through с→ә→л→ә→м
  yields `сәләм` in the top-8 on the live geometry (pre-fix top: сәлләм, **сәләм**,
  сайлыйм…; post-fix top: сәлләм, **сәләм**, мәкаләм, мәтәм… — the L1 shape channel
  reshuffles the tail, the pinned degenerate-path collision is unchanged: сәлләм first
  by the frequency prior, exactly as the JVM e2e pinned it).
- `testDecodeLatencyOnDevice` (1 000 samples over six probe words, warmed index),
  **pre-fix**: **p50 14.551 ms, p95 50.063 ms, max 51.670 ms — FAIL against the ≤ 5 ms
  gate** (reproduced identically on a second run: p95 50.027 ms — deterministic, zero GC
  events in logcat). The host measured 1.19 ms p95; this Go-edition budget phone runs the
  same workload ~40× slower (the E3b harness showed 60–120× for review-prefix lookups —
  the ratio is the platform, not the algorithm). **Post-fix (the perf iteration above,
  re-measured 2026-09-24): p50 1.621 ms, p95 3.388 ms, max 4.744 ms — PASS** (the
  iteration's own measurement: p50 1.60, p95 3.37, max 4.80 — the two runs agree within
  noise). The decode runs ONCE per gesture at ACTION_UP on the engine worker.

### Device UAT — interactive part BLOCKED (owner interference), cold start + crash measured

The POCO C71 was connected all session but **in active use by its owner**: the screen
went landscape mid-drive and `default_input_method` flipped to Gboard twice within
minutes (measured via `mCurMethodId`). Interactive drives (the settings row on the
device, the swipe on the device) were contaminated and are marked BLOCKED. What the
device DID carry cleanly:

- the instrumentation above (decode + latency) — runs in the app process, immune to the
  screen fights;
- cold start (force-stop → `am start -W`): SetupActivity 269/247/252 → **median 252 ms**,
  SettingsActivity 274/269/269 → **median 269 ms** — both < 400 ms (`p03-coldstart-device.txt`);
- crash buffer after the instrumentation + session: **EMPTY (0 lines)**; full logcat:
  no FATAL EXCEPTION / ANR for the package (`p04-logcat-crash.txt`, `p05-logcat-full.txt`).

### Emulator UAT (the interactive half, on the debug build of the same tree)

`tt_suggest_a14` (1080×2280). The strip-level drive uses `input motionevent` chains
(one continuous gesture across invocations — the dispatcher tracks the touch state per
device), with a DENSE (~60 px) polyline — the corners-only first attempt under-ranks
(the decoder's calibration expects human-dense paths). Evidence:
`build/device-uat-2026-09-24/p7/` (36 files).

| # | Scenario | Result | Evidence |
|---|---|---|---|
| G1 | Glide row exists in Preferences, **default ON**, subordinate to suggestions (the pinned contract) | PASS | `u10-glide-row-on.png` (checked=true), `GlideTouchIntegrationContractTest` |
| G2 | **The mission scenario**: a genuine continuous с→ә→л→ә→м gesture on the tt layout → **сәләм in the strip top-3** (сәлләм · **сәләм** · мәүләви — the documented degenerate-path collision, exactly the JVM e2e's pinned ordering) | PASS | `u05-strip.png` |
| G3 | Tap the сәләм cell → commits «сәләм » with the trailing space; the strip immediately shows the chain **сәләмә · һәм · белән** | PASS | `u06-strip.png`, field readback «сәләм » |
| G4 | ru layout: the работа gesture → **работа** in cell 1 (рабов · раба after); the first attempt painted the ru fallback while the engine settled after the subtype switch — retried clean | PASS | `u08-strip.png` |
| G5 | Tap-typing regression with glide ON (normal taps type) | PASS | field readbacks |
| G6 | Space-swipe (cursor move) NOT eaten by glide: a left swipe on the space bar moved the cursor (a marker typed at the moved position landed mid-text) | PASS | field readbacks («җсәләм ») |
| G7 | Toggle OFF → the same dense path behaves as legacy: the drag committed one letter (м) and the strip showed the ordinary м-prefix completions (мин · мең · милли) — **no glide candidates ever** | PASS | `u11-strip.png`, field readback «м» |
| G8 | MVP UX note documented with evidence: mid-gesture (finger held mid-path) the screen shows NOTHING glide-related — no trail, no live candidates; candidates paint at lift (`u12-mid-gesture.png` vs `u13-strip.png`) | PASS (documented) | `u12-mid-gesture.png`, `u13-strip.png` |
| G9 | Emulator crash buffer after the session: EMPTY; no FATAL/ANR | PASS | `u14-logcat-crash.txt`, `u15-logcat-full.txt` |

### DONE-WHEN audit (docs/GLIDE-PLAN.md)

1. *Gliding produces candidates; tap/lift commits; the toggle default ON and subordinate* —
   **met** (G2/G3/G1; the strip-contract paint at lift, not mid-gesture, is documented).
2. *Offline calibration meets the written recovery gates* — **met** (P7-1: top-3 77.57 %
   held-out, host p95 1.19 ms).
3. *Tap typing byte-identical with glide off; no gesture conflicts* — **met** (JVM pins +
   G5/G6/G7 on screen).
4. *Perf: decode on the POCO C71 ≤ 5 ms p95* — **met after the perf iteration**: the
   initial measurement failed honestly (50.063 ms p95 on the debug package — kept above
   as the dated record); the iteration (fused per-variant loop, manual abs, pointwise-L1
   shape channel, NO constant moved, recovery re-validated 77.57 → 77.70 % top-3)
   brought the same harness to **p95 3.388 ms — PASS**, re-measured 2026-09-24 with the
   raw logcat saved (`u17-instrument-logcat.txt`).
5. *All gates green; device UAT passes; the 3-cell strip contract holds* — gates green;
   the interactive device UAT is BLOCKED by the owner's active use (the emulator carries
   the interactive proof); the 3-cell contract held in every screenshot.

### Files (P7-4)

- `app/src/androidTest/.../glide/GlideDeviceInstrumentationTest.kt` — NEW (never in the
  release APK): the live-geometry сәләм decode proof + the 1 000-sample latency gate.
- Evidence: `build/device-uat-2026-09-24/p7/` — 36 emulator files (u01–u15) + the
  device cold start/logs (p01–p05) + the 2026-09-24 post-fix re-measurement:
  `u16-instrument-result.txt` (the OK (2 tests) runner output) and
  `u17-instrument-logcat.txt` (the raw logcat with the measured p50/p95/max lines — the
  gap the independent verifier named).

What remains: the release decision, the interactive device UAT when the phone is free,
and the follow-ups the plan already parks (trail rendering, live per-MOVE scoring,
personal glide candidates).

> **2026-09-24 (3.0.0 audit — worst-case memory, accepted):** in the worst case both
> warm engines each hold a lazily built glide word index at once — ~3.0-3.2 MB for
> Tatar (measured 3 158 405 B on the 110 000-entry dictionary, P7-1) plus ~2.8 MB
> for Russian, ≈ 5.8 MB total. An index is released on engine destroy and on a
> geometry swap (the decoder is rebuilt against the new geometry and the old index
> becomes garbage). Recorded as an accepted decision for now; a shared or
> demand-paged index stays available as a future optimization.

## P7-5 — lift-commit UX + след свайпа и подсветка клавиши (2026-09-24)

### Lift-commit (поправка UX к P7-3)

Раньше подъём пальца только рисовал полосу кандидатов, а коммитом был тап. Теперь:

- Подъём после armed-глайда **коммитит top-1 сразу** (авто-пробел, регистр по шифту) через
  `commitPredictedWord` — тот же путь, что у тапа по предиктивной подсказке, включая
  `trustPairBoundary()` и `markRunDirty()`.
- Полоса показывает **оставшиеся** кандидаты (2..4) как тапабельные альтернативы; тап по
  альтернативе **заменяет** коммиченное слово на месте (`InputLogic.replaceGlideLiftedWord` —
  suffix-матч слова+пробела у курсора, отказ при выделении/букве после курсора).
- Один backspace сразу после lift-commit **удаляет всё слово целиком**
  (`LatinImeGlide.maybeUndoGlideCommit` → `deleteGlideLiftedWord`); после замены альтернативой
  undo переезжает на неё. Любое промежуточное изменение текста закрывает окно undo.
- Ноль кандидатов → ничего не коммитится (reserve-без-коммита, как раньше).
- Обучение = семантика тапнутой подсказки, а не clean run.

### След и подсветка (trail)

- `keyboard/internal/GlideTrail.kt` — кольцевой буфер (ёмкость 48, перезапись старейшей
  точки — в отличие от GlidePath, который fail-closed обрезает), окно видимости 150 мс,
  альфа линейно от 0 на хвосте до 0x66 у пальца; возраст считается от НОВЕЙШЕЙ точки
  (перерисовка случается только на новых точках), чистый Kotlin без android-импортов,
  ноль аллокаций (пин `GlideTrailTest.feedAndDrawReadPathsAllocateNothing` — 0 Б/жест).
- Seam `DrawingProxy.onGlideTrailPoint/onGlideTrailEnd`; вызовы стоят ровно в двух
  armed-ветках `PointerTracker.onMoveEventInternal` — при выключенном глайд-тумблере
  ветки мертвы by construction, путь касания побайтово прежний.
- `MainKeyboardView` рисует полилинию ПОСЛЕ клавиш (поверх offscreen-блита на software-пути,
  поэтому след не запекается в буфер), предвыделенным Paint; цвет — новый attr
  `glideTrailColor` (@color/app_accent в `MainKeyboardView.Tatar`; тема одна), толщина —
  `config_glide_trail_stroke_width` (9dp). `onGlideTrailEnd` при пустом следе — no-op
  (нет лишнего invalidate на каждом отпускании клавиши).
- Клавиша под пальцем подсвечена через существующий pressed-state (`onKeyPressed(key,
  withPreview=false)` — без preview-попапа, без хаптики, без listener-колбэков); смена
  hover-клавиши отпускает предыдущую. Конец жеста/отмена/мультитач-перехват/закрытие
  клавиатуры (`cancelAllPointerTrackers` доходит без CANCEL-события) — все зовут
  `endGlideFeedback()`, идемпотентно.
- Осознанное упрощение: после подъёма след гаснет мгновенно (без анимированного fade-out —
  он требовал бы цикла инвалидейшенов; внимание пользователя уже на слове и полосе).

### Гейты (финальное дерево, 2026-09-24)

- JVM: **1566/1566** (`./gradlew test --rerun-tasks`), включая `GlideTrailTest` (7) и
  `GlideTrailContractTest` (5); 13 e2e-пинов lift-commit в `GlideEndToEndTest`.
- Python-конвейер: весь цикл зелёный; `rebuild_assets.py --check --allow-known-drift` — ok.
- `lintRelease` — зелёный; release APK 1 846 564 Б (бюджет 3 145 728);
  `check-no-internet` — оба уровня OK.

### Device UAT (POCO C71, release-подписанный APK, 2026-09-24)

Свидетельства: `build/device-uat-2026-09-24/p7-lift-trail/`.

- `d01-pointer-instrument.txt` — `GlidePointerDeviceTest` 4/4 OK, включая новый
  `testArmedGlideFeedsTheTrailAndHoversKeys` (след наполняется при armed-глайде,
  `onGlideTrailEnd` приходит, клавиши ә/л/м загораются под пальцем).
- `d02-ui-liftcommit.txt` — `GlideUiDeviceTest` (обновлён под lift-commit) 1/1 OK на живом
  debug-IME: hold-then-swipe сәләм-жест → подъём коммитит top-1 «сәлләм » сразу → тап по
  левой ячейке (первая альтернатива) заменяет на «сәләм ».
- `u03-trail.png` — след виден в середине жеста (550 мс в 1500-мс свайпе ц→х),
  синий, с затуханием к хвосту; `u02-before.png` — до жеста следа нет.
- `u04-after.png` — подъём коммитит «цех » сразу, полоса показывает альтернативы
  йоз · йөз; след и подсветка погашены.
- `u05-after-replace.xml` + `u06-after-replace.png` — тап по средней ячейке заменяет
  слово на месте: поле читается «йөз ».
- `u07-after-undo.xml` — один backspace (тап по ⌫ клавиатуры) после замены удаляет всё
  слово (поле пустое — text-атрибут показывает hint, сверено с пустым baseline u01).
- `u08-field-readbacks.txt` — негативный контроль: «цех » → буква → bs стирает только
  букву («цех »), второй bs — только пробел («цех»): окно undo закрыто вводом.
- `u09-logcat-crash.txt` — crash-буфер пуст; FATAL/ANR нет.

Состояние устройства восстановлено: дефолтный IME оператора (Gboard), штатный
`tatar-keyboard-3.0.1.apk` из `dist/`, «Word suggestions» возвращён в OFF (было OFF),
debug/test-пакеты удалены.

## P7-6 — два полевых бага: независимость от мастера подсказок + коммит после «сүз ? » (2026-09-25; репорты 2026-09-24)

### БАГ 1: глайд мёртв при выключенном мастере «Word suggestions»

Ожидание пользователя (паритет с Gboard): свайп — самостоятельный тумблер. Диагноз (три
зацепки, все закрыты):

1. `Settings.readGlideTypingEnabled` читал свой тумблер В КОНЪЮНКЦИИ с мастером
   (`Settings.java:391`, решение P7-2) → отключён.
2. `SuggestionsController.onGlideInput` стоял за `eligible` — флагом suggestions-пути; введён
   раздельный `glideEligible` (те же полевые проверки — подтип со словарём, не-парольное поле,
   без NO_PERSONALIZED_LEARNING, известный курсор — но без мастера), LatinIME передаёт
   `isGlideEligible()` в `onStartInput`/`onSubtypeChanged` отдельным параметром.
3. **Поймано только device UAT'ом** (e2e пушит геометрию напрямую и мимо него проходил):
   `LatinIME.updateKeyNeighbors` пушил glide-геометрию под `isSuggestionsEligible()` — при
   выключенном мастере геометрия уходила null и декод был выключен fail-closed. Теперь гейт
   `isGlideEligible()` (пин в GlideTouchIntegrationContractTest).

Семантика при выключенном мастере: подъём коммитит top-1 в текст (это ввод, не подсказка),
полоса — поверхность подсказок — не показывает НИЧЕГО (альтернативы подавлены в
`applyGlideResult`; `clearToReservedBand` при не-eligible зовёт `hideSuggestions()`, а не
`reserve()` — пустая полоса 40dp не всплывает); undo слова работает (гейт
`maybeUndoGlideCommit` переведён на `glideEligible`). Машинерия движка живёт при одном лишь
глайде: `maybeStartEngine`/`requestPreparationIfNeeded` допускают `eligible || glideEligible`;
`onSuggestionsSettingDisabled` при живом `glideEligible` НЕ ставит releasePending (движок греется
— ровно как сегодня он греется на неподходящем поле). Тесты релизной механики переведены на
явный сценарий «оба тумблера off» + новый пин keep-warm. Экран настроек: строка глайда больше
не гаснет за мастером (только MDM-ограничение).

### БАГ 2: при пустой полосе («Синен хэллэр ничек ? » — пробел перед знаком) глайд не работает

Диагноз одним предложением: lift-commit переиспользовал `InputLogic.commitPredictedWord`, чей
P4-страж против протухших тапов отказывает пустому контексту вне начала предложения
(`InputLogic.java:719-726`), а «сүз ? » (пробел перед знаком) — ровно такая позиция:
`isSentenceStartContext` требует, чтобы серия пунктуации непосредственно следовала за буквой
(`TatarWordUtils.kt:250` — перед «?» стоит пробел, не буква), поэтому коммит молча отклонялся
и жест выглядел мёртвым.

Фикс (минимальный, по доктрине «один путь коммита на вид результата»): свой
`InputLogic.commitGlideWord` — те же живые перепроверки (выделение, буква за курсором, пустой
хвост, РАВЕНСТВО контекста) без требования начала предложения для пустого контекста; seam
`EditorSurface.commitGlideWord` (default false). Fail-closed сохранён: слово перед курсором,
буква впереди, неподходящее поле, отсутствующий движок — всё как раньше. Тап-путь предсказаний
не тронут (его страж на месте и запинен).

### Пины (JVM)

- `GlideEndToEndTest` (+5): коммит с мастером OFF и пустая полоса; undo при OFF; регрессия
  «мастер OFF прячет полосу при обычном вводе»; коммит после «сүз? »; коммит после
  «сүз ? » (пробел-перед-знаком); явный пин начала поля.
- `GlideTouchIntegrationContractTest`: субординация переписана в независимость (reader без
  мастера; строка настроек не следует за мастером; раздельные гейты в onStartInput; геометрия
  под glide-гейтом); lift-commit пин переведён на `commitGlideWord` + асимметрия
  «commitGlideWord без isSentenceStartContext, commitPredictedWord с ним».
- `SuggestionsControllerLanguageSwitchTest` (+1): мастер OFF при живом глайде не трогает
  движок.

### Device UAT (POCO C71, release /tmp/tt-glide3.apk, sha256 69f664c8…)

Свидетельства: `build/device-uat-2026-09-24/p7-6/`.

- (b) `u01-…png` — предусловие «сүз . » (полоса пустая, как в репорте) → глайд ц→з → поле
  «сүз . йоз » (`u02-…png/.xml`); регрессия рядом: тап альтернативы → «сүз . йөз », один ⌫ →
  «сүз . » (`u02-readbacks.txt`).
- (a) мастер OFF: `u05-nosugg-keyboard.png` (полосы нет) → глайд → «цех » в поле
  (`u07-…xml`, `u08-…png`), ⌫ удаляет слово целиком; строка «Glide typing» в настройках
  enabled=true при выключенном мастере, когда подчинённые строки серые
  (`u03-glide-row-master-off.xml`; скринкапы SettingsHostActivity на этом устройстве
  отрисовываются чёрными — доказательство по uiautomator).
- `u09-logcat-crash.txt` — пусто. Состояние устройства восстановлено: все тумблеры ON (как
  было у пользователя), дефолтный IME — наш, сборка пользователя (HEAD 2269fbff) поставлена
  обратно, accelerometer_rotation=1 (как было; см. u10 о гонке ротации).

### Гейты (финальное дерево)

- JVM: **1572/1572** (`test --rerun-tasks`); python-конвейер зелёный; `lintRelease` зелёный;
  `rebuild_assets.py --check --allow-known-drift` ok; release APK **1 846 564 Б**;
  `check-no-internet` — оба уровня.
