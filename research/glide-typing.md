# Glide typing research

How to improve gesture typing. Proof rules: `research/measurement-framework.md`. Sources are
linked inline; measured numbers carry their harness and were true at research time.

## Current state

Our decoder (`latin/glide/`) is a SHARK2-family statistical classifier written from scratch in
pure Kotlin: 200-point arc-length resampling, bbox normalization, firstKey×lastKey CSR bucket
pruning plus length pruning, fused Gaussian(shape) × Gaussian(location) × frequency^0.25
scoring, top-8 candidates. Commit on lift with a context-aware leading space; alternates in
the strip with tap-to-replace; one backspace undoes the whole word. Doubled letters score
against a looped ideal path, with a twinless free pass. Aliases map keyless letters to base
keys. Calibration is fully synthetic (`scripts/glide_pack.py` + the bit-identical JVM mirror).

## What the world does

### The SHARK lineage (our algorithm family)

- SHARK (Zhai & Kristensson, CHI 2003) measured elastic (DTW-style) matching and **rejected**
  it: warp freedom trades selectivity for slack in a crowded template space. Their
  proportional matcher — resample both paths and compare pointwise — is exactly our shape
  channel. Our design choice is validated by their measurement.
- SHARK2 (UIST 2004) ships three mechanisms our port does **not** carry:
  1. A language-model channel: bigram probability, Gaussian-transformed, multiplied into the
     candidate confidence (their Eq. 12–13).
  2. Speed-adaptive channel weighting: a Fitts-law normative duration per word; faster-than-
     normative gestures trust shape over location (Eq. 10–11). `GlidePath` already records
     timestamps; scoring ignores them.
  3. Confidence semantics: per-channel 2σ pruning and an explicit N-best, plus a "stream
     editor" where tapping a recognized word reveals its alternates (2004 — our
     tap-to-replace, two decades earlier).
  Its confusion census quantifies the ambiguity floor: in a 20K lexicon on QWERTY, ~7% of
  words are shape-identical; endpoint and location cues cut that ~3×; the residue needs
  frequency and context.
- Zhai & Kristensson (CACM 2012): a mobile version held the model for 50K words in ~450 KB
  decoding in under 20 ms on a 168 MHz CPU — direct proof that our budget class is
  achievable. Novices reached 15/20/25 wpm after 5/20/40 minutes.
- Kristensson & Zhai (IUI 2005): a tap sequence treated as a geometric pattern matched
  against key-center word patterns — the strongest published statement that tap autocorrect
  and glide shape matching are the same computation at different sampling densities.

### Production systems

- Gboard's FST decoder (Ouyang et al. 2017, arxiv.org/abs/1704.03987): doubled letters are
  *optional arcs* in the lexicon (structural, not a gesture requirement). Their single
  biggest published decoder-side win is **post-correction**: revising the previous word once
  the next word is known, bounded by a temporal window and high confidence (gesture WER
  11.51% → 9.20%).
- Gboard's Neural Spatial Model (Google Research blog): one CTC-trained LSTM replaced both
  the Gaussian tap model and the rule-based gesture error model; trained on interaction
  signals — reverted autocorrections as negative labels, suggestion picks as positive. Those
  exact signals exist locally in our code (undo window, strip taps); the network itself is
  closed to us (training data, size, runtime).
- In the wild (Reyal, Zhai & Kristensson, CHI 2015): gesture typing is faster than tap
  (33.6 → 39.1 wpm over 4 weeks) but less accurate (CER 3.3–4.1%); OOV sentences hit gestures
  much harder. User complaints map to decoder UX: one wrong motion makes a word
  unrecoverable; letters cannot be fixed inside a glided word; words split in two.
- VelociTap (Vertanen et al., CHI 2015): nearest-key-only decoding gives 20.2% CER vs 4.7%
  with the full spatial+LM model — the model *is* the product. A 5-character correction
  window approximates full-sentence decoding.
- Smith, Bi & Zhai (CHI 2015): gesture error is largely layout-inherent confusability
  (layout-optimized clarity cut error 37–52%). Our layouts are fixed; budget expectations
  accordingly.

### Touch models (the location channel's literature)

- Touch offsets are systematic, not random (Henze et al., "100,000,000 Taps", MobileHCI
  2011): distributions skew toward a lower-right point; a data-derived shift function cut
  error 7.8% in a live A/B, and 9.1% on real typing on top of a shipping keyboard.
- Bi & Zhai's dual-Gaussian model (UIST 2013/2016): probabilistic target selection beats
  nearest-key decisively (5.9% vs 19.6% selection error); touch σ scales lawfully with key
  size — we store per-key half-extents but collapse them into one scalar `keyRadius`.
- Yin et al. (CHI 2013, MIT+Google), hierarchical spatial backoff — the central cautionary
  result: a per-key model pooled across users and postures scored **worse** than the global
  model (postures cancel out). Adaptation must be staged and conditioned correctly, or not at
  all. Oracle upper bounds: user+key adaptation −14.2% CER.
- Per-user models learn from few samples (Weir et al. UIST 2012: ~200; Buschek et al.
  MobileHCI 2013: ~60) — and touch models are identifying enough to recognize users, a
  privacy property to handle deliberately.
- Findlater & Wobbrock (CHI 2012): adapt the model, never the visuals.

### Open-source decoders and datasets

- AnySoftKeyboard PR #1870 (Apache-2.0) — our constants' ancestor. Its σ values were fitted
  to real self-recorded traces: shape σ ≈ 22.9, location σ ≈ 0.525 — **about twice our
  synthetic-tuned values (11.04 / 0.18)**. The sharpest calibration-validity red flag in this
  research. It also measured endpoint pruning on real gestures: 2 nearest start/end keys keep
  93.9% sensitivity; 3 keys reach 96.9% — our `extremityNeighbors = 2` carries a measured ~6%
  recall ceiling loss on real input that our synthetic harness cannot see.
- FlorisBoard's classifier (our port's other ancestor) is now commented out upstream; we are
  the living implementation of this line, and our calibration harness is the only regression
  net for the algorithm family.
- WM Keyboard (MIT): the most complete SHARK-family product design found — dwell-to-mark
  (pause relative to the stroke's own speed forces a letter), mid-swipe candidates, post-hoc
  n-gram rerank on by default, a 90%-spellable gate before glide is enabled on a layout.
- swipeboard (Python, unlicensed — ideas only): corridor pruning with endpoint fallback, and
  `--diag` reporting **pruning recall separately** ("a word dropped by pruning can never be
  ranked") — a metric our harness lacks.
- FUTO's swipe corpus (MIT, ~1M real donated swipes, 11 layouts / 8 languages) and
  swipe-negatives (Apache-2.0, mined confusable sets) are the first usable real-gesture
  ground truth for build-time calibration. Their decoder paper's anchor: a SHARK2-style
  template matcher achieves ~80% top-1 / ~90% top-3 on real QWERTY swipes; and their measured
  finding that **scoring calibration does not transfer between languages** (retuning on
  Russian gave +2.79 pt) justifies per-language constants.
- How We Swipe (Leiva et al., MobileHCI 2021): the only public raw-trajectory study corpus
  (1,338 users, web canvas keyboard, decoder-free correctness gate); the collector code is
  unlicensed — reimplement, never fork.
- HeliBoard runs an NLnet-funded open gesture library plus an opt-in data-gathering program
  (dictionary-words-only labels; a public dataset is promised after collection ends in late
  2026 — watch, don't plan on it). Yandex Cup gesture data: license unstated — private
  diagnostics only, never committed.
- Neural decoders (FUTO TCN, Grammarly seq2seq, proshian transformer): disqualified by size,
  NDK and runtime rules; their published measurements remain free.

### Patent landscape (engineering assessment, not legal advice)

The portfolio chain: Swype/Tegic → Nuance → Cerence; ShapeWriter/IBM base patents → Nuance →
Cerence; Google holds its own Gboard-era family.

- The core-idea patents are **expired**: US7098896 (the Swype architecture — first/last-key
  grouping, expected path length, frequency ranking, auto-space; expired 2024) and US7251367
  (the SHARK template-matching idea; expired 2025). Our mechanisms are covered only by
  expired claims or by 2003–2004 academic prior art.
- Live watch items (do not adopt without review): US7750891 (per-position character entry
  from velocity/pause features — we never extract characters from motion), US8884872
  (wiggle-to-repeat — our loop is decoder-side template geometry), US9304595 (Gboard-style
  incremental LM-guided straight-line-angle decoding), US8843845 (cross-gesture completion),
  US10241673 (correction-data alternative hypotheses in glide).
- Enforcement history: Cerence sued Apple (filed September 2025) — four of the six asserted
  patents were already expired. A free, offline, no-revenue keyboard is a poor damages target.
- Verdict: **low risk**; feature-freeze the five live-claim areas; commission a one-page FTO
  opinion before US-scale distribution; confirm the first glide release postdates the core
  patent's expiry (the glide work postdates it).

## Decision memos (ranked)

Gates are proposals to pre-register per the framework. Harness: `GlideRecoveryCalibrationTest`
(held-out top-1/top-3, per-class tolerances, host p95, zero allocations) plus device tests.

**G1 — Recalibrate the synthetic generator against real gesture statistics (do first).**
Build-time analysis over the FUTO MIT corpus (+ How We Swipe when its terms are verified):
per-point offset envelope, corner-cutting depth vs turn angle, path-length deviation, sampling
density, speed/dwell profiles; refit `glide_pack.py` constants, optionally upgrade the noise
model to Quinn–Zhai minimum jerk. Deliverable is a pinned synthetic-vs-real distribution
report; existing gates must stay green under regenerated pins. This removes the largest known
validity risk (σ tuned on guesses, plausibly 2× too tight). Harness-only; no app code.

#### Recalibration evidence (G1, landed)

Measured by `research/corpus/futo_glide_analysis.py` on the swipe-1 validation slice
(`~/corpora-futo/dev.jsonl`, 54,269 rows, 44,436 kept after the documented caps; the same
procedure measures the synthetic side on the same 9,286 target words over the same QWERTY
layout). Distances in key radii (min key width/height of the record's own canvas). Real paths
have a closest-approach floor around 0.2 radii at every turn angle and do not start on the key:
the old model's "path starts on the true center" gap and 2× too tight wander were both real.

| statistic | real | synthetic, before | synthetic, recalibrated |
| --- | --- | --- | --- |
| mid-segment offset, radii p50 / p90 / p95 | 0.16 / 0.50 / 0.67 | 0.09 / 0.20 / 0.37 | 0.14 / 0.37 / 0.46 |
| touch-down offset, radii p50 / p95 | 0.28 / 0.68 | 0.15 / 0.23 | 0.32 / 0.65 |
| lift-off offset, radii p50 / p95 | 0.40 / 1.14 | 0.15 / 0.23 | 0.43 / 1.12 |
| corner closest approach, 150–180° turn, p50 / p90 | 0.23 / 0.71 | 0.15 / 0.36 | 0.29 / 0.74 |
| path length / ideal length, p50 | 1.07 | 0.98 | 0.96 |
| samples per radius, p10 / p50 / p90 | 2.7 / 4.7 / 8.8 | 3.8 / 4.8 / 6.7 | 3.8 / 4.8 / 6.7 |
| inter-sample dt, ms p10 / p50 / p90 | 8 / 12 / 18 | 8 / 12 / 16 | 8 / 12 / 18 |

Generator changes from the fit: wander envelope 18 → 22 % of the key radius; corner cutting
1/10 of interior vertices at depth |P+Q−2V|/4 → 1/3 at |P+Q−2V|/20 (the old fixed depth could
not express the measured p90 at any frequency); new per-gesture route shift ±30 % (real
per-gesture mean offset p50 0.20 radii — no zero-mean wander expresses it); new endpoint
offsets ±18 % touch-down / ±40 % lift-off, widened ×3 for 1/7 of gestures (the measured
p95/p50 ratio needs a heavy tail a single bounded draw cannot express); timestamp step widened
to the measured dt p10–p90 (still decoder-irrelevant). Held-out calibration after re-pinning:
top-1 88.01 %, top-3 94.61 % (floors 35/60 hold), decode p95 1.37 ms (gate 2 ms). One pin
moved the other way: with realistic noise the twin/no-jog confusion of doubled words rose
(class ceiling re-pinned 10 % → 25 % of a small class) — loop discrimination now genuinely
needs the dwell channel (G11).

Not fitted (model limits, not knob values): the offset tail above p90 (real paths bow between
keys and overshoot corners; a bounded shift+wander family tops out at p95 0.46 vs the real
0.67) and the above-1 path-length ratio (1.07 vs 0.96 — the same route curvature). A
per-segment bow is the smallest change that would close both; deferred — the shape channel is
bbox-normalized and the length gate is wide, so the residual does not move calibration.

**G2 — Real-gesture diagnostic harness (not a gate).** Private eval runner decoding the FUTO
validation slice (and Yandex Cup data privately, license unstated — never committed). Compare
against the published template-matcher anchor (~80/90 top-1/top-3 on real QWERTY). Produces
the first honest external-validity number and detects sim-to-real regressions that G1 alone
cannot see. G1 and G2 land together.

#### Real-gesture evidence (G2, landed)

`FutoRealGestureDiagnosticTest` (inert unless `FUTO_EVAL_FILE` names a local dev.jsonl) decodes
the full validation slice with the production decoder and constants on the QWERTY geometry:
**top-1 82.4 %, top-3 90.6 %** over 44,436 gestures (top-1 82.9 % for under-5-letter targets,
81.7 % for longer ones). The published SHARK2-style template-matcher anchor on this corpus is
80.1 / 90.5, measured with a 162k-word AOSP lexicon extended with the targets; the diagnostic's
lexicon is the slice's own 13,414 target and sentence words with their occurrence counts —
smaller, hence easier. Same accuracy class either way: the port and its synthetic-tuned
constants transfer to real gestures, and there is no sim-to-real collapse to fix. What the
number does not yet cover: a deployment-size lexicon (the anchor's protocol) and any
Cyrillic-layout gesture (G13).

**G3 — Confidence-aware commit with a refuse path.** Compute a frequency-free geometric score
(today's fused score punishes rare words even on perfect gestures) and commit on lift only
when top-1 clears an absolute floor and a margin over the runner-up; otherwise show candidates
with the existing refusal tick. Add a garbage-gesture class to the generator and a
risk–coverage assertion to the calibration test (El-Yaniv & Wiener's selective-classification
frame). Eliminates the worst user-visible failure: the confident wrong commit.

**G4 — Speed-adaptive channel weighting (SHARK2 Eq. 10–11).** Gesture duration vs a normative
duration per candidate; fast gestures widen the location σ. Generator gains slow/fast persona
classes with correlated sloppiness. Targets the known tracer-vs-recaller mixture; O(1) per
decode, timestamps already recorded.

**G5 — Bigram channel on the glide N-best (SHARK2 Eq. 12–13).** Multiply each top-8
candidate's confidence by the Gaussian-transformed bigram probability given the previous word
(tables already mmap'd). Attacks the short-word and same-shape residue, which the confusion
census says frequency alone cannot resolve. This is also the productive continuation of the
closed A6 question: the blanket rerank failed its gate and the threshold was confirmed; what
remains testable is a *pair-conditional* rerank (fire only on mined confusion pairs).
Harness: extend the calibration set with context rows from the eval sentences; gate on
held-out top-1 with context vs without.

**G6 — Endpoint pruning n=2→3.** Measured on real gestures (ASK study): +3 pp sensitivity,
some p95 headroom spent. Cheap to A/B in the harness with an endpoint-noise gesture class;
invisible on today's synthetic set (the generator always starts on the true key — fix that
in G1).

**G7 — End-weighted location channel + tunnel dead-zone (SHARK2 Eq. 3–6).** Precomputed
200-sample weight profile (low middle, rising ends — users attend to endpoints) and a one-key-
radius dead zone in the location sum. Trivial cost, principled; measure on the train-split
tuning surface.

**G8 — Per-language scoring constants + length term.** FUTO measured that glide scoring
calibration does not transfer between languages; we ship one constant set for tt and ru.
Split constants per language and add a length term to the score (fixes the short-vs-long
bias); tune on the train split only.

**G9 — Pruning recall metric + confusables eval class.** Pin pruning recall (target survives
the bucket and length gates) next to final top-k — separates "pruned away" from "ranked
wrong" (swipeboard's key diagnostic). Mine top-confusable word pairs from our own ideal paths
offline (the FUTO swipe-negatives idea, zero data dependency) and add them as a calibration
class: top-3 recovery is the honest metric there, top-1 is provably luck.

**G10 — Conservative post-correction of glide commits (Ouyang's biggest win).** When the next
word commits, re-evaluate the previous glide-committed word against its stored N-best under
the bigram pair; replace only on a large confidence margin, inside the existing undo window,
with instant revert in the strip. Precision must be near-perfect — SHARK2 itself warned that
retroactive text changes are T9-like trust killers. Medium cost; UX risk is the gate, not
accuracy.

**G11 — Dwell channel for doubled letters and confusables.** SwiftKey documents dwell-based
doubling; Grammarly uses dwell for to/too-type pairs; WM Keyboard measures pause relative to
the stroke's own speed. Use local speed minima near keys as evidence for the looped variant.
Prerequisite: G1 must make the generator emit realistic timing first — our synthetic
timestamps are currently decorative.

**G12 — Per-user touch offset learning (staged, coarse first).** A store mirroring the
personal-dictionary gates (pause learning, quarantine, erasure, never logged — touch profiles
identify users) holding a running Welford mean offset, learned only from high-confidence
committed glides (Gboard's negative-signal lesson: rejected gestures and undos are excluded;
committed-word labels are noisy whenever the decoder was already wrong). Applied by rebuilding
the personalized ideal-path geometry at index build time — never per decode point (zero-
allocation and p95 budgets). Staging per Yin's negative result: global vector → coarse grid →
per-cluster; per-key only with enough data. Ships validated on skewed-synthetic canaries plus
manual dogfooding; the tap→glide transfer of offset gains is a plausible extrapolation, not a
verified fact.

**G13 — Real tt/ru gesture collection (measurement-framework gap #1).** Two protocols, in
order: (a) a web-based crowdsourced collector (reimplemented, not forked) rendering our exact
layout geometry, prompted-word transcription with the decoder-free correctness gate,
pseudonymous JSONL export, consent page — target N≈100–300 per layout via Tatar communities;
(b) a lab panel on the reference device plus a debug-build recorder (new `debug` source set,
local ring buffer in `no_backup`, manual export, contract test asserting absence in release).
FL/DP telemetry is disqualified (needs servers and fleet scale, and aggregates cannot
calibrate trajectories anyway).

## Rejected / parked with reasons

- Neural decoders (LSTM/CTC, TCN, transformers): size, NDK, and training-data requirements
  each disqualify alone. Gboard's own numbers mark the realistic payoff; revisit only if a
  real tt/ru corpus exists (G13) and the budget analysis passes.
- DTW as the primary shape metric: rejected by SHARK2's measurement and by practitioner
  reports; revisit only if G2 shows time-alignment failures.
- Flow-through-space multi-word gestures: segmentation ambiguity and sentence-LM size cost;
  no measurable demand at our scale. Revisit after G5 resolves.
- AOSP's C++ gesture decoder and Google's swypelibs: NDK/proprietary — dead on arrival.
- GPL/unlicensed code (HeliBoard, Urik, swipeboard, the How-We-Swipe collector): ideas only.
- Wiggle-repeat, per-position character extraction, incremental LM-guided decoding: live
  patents — feature-frozen regardless of merit.

## Risks and open questions

- **Layout/language transfer**: calibration is now evidence-based on English QWERTY (the G1
  generator fit and the G2 anchor-class result), but no public tt/ru gesture corpus exists, so
  the Tatar-layout transfer of both the generator statistics and the scoring constants rests on
  the key-radius normalization, not on measurement (G8, G13).
- **Label quality in personalization**: learning from our own decoder's commits can entrench
  systematic errors; learn only from high-confidence commits (ties G12 to G3).
- **Tatar morphology stress**: long agglutinative words stress the length gate and the
  frequency tail; the bigram channel's gain may be *larger* than in English because forms are
  sparser — measure with per-length classes.
- **Strip stability**: any rerank that reorders cells between keystrokes re-incurs the
  attend-and-evaluate cost of a suggestion (Quinn & Zhai, CHI 2016); hold order stable within
  a word's lifetime. (An earlier draft cited Palin et al. 2019 here — that paper measures the
  attention-shift cost of prediction, not reordering; the stability rule stands on Quinn &
  Zhai and remains partly a hypothesis worth a lab check.)
- **Open questions**: whether a location-channel offset improves decode at all given that
  shape is bbox-normalized (a synthetic canary answers cheaply); whether dwell realism in the
  generator is achievable without breaking Python/JVM bit parity; whether the HeliBoard
  dataset (promised after collection ends in late 2026) is usable for Cyrillic layouts.
