# Research

Improvement research for the keyboard. Each document is self-contained: current state,
options with sources, constraint check, decision memos. The proof rules every proposal must
satisfy are in [measurement-framework.md](measurement-framework.md).

## Documents

- [measurement-framework.md](measurement-framework.md) — how an improvement is proven.
- [prediction-engine.md](prediction-engine.md) — word completion and next-word prediction:
  morphology backoff, corpus sources, evaluation upgrades, ranked decision memos.
- [glide-typing.md](glide-typing.md) — the gesture decoder: SHARK2 literature, patents,
  real-gesture data and collection protocols, ranked decision memos.
- [ux.md](ux.md) — strip, autocorrect, glide discovery, onboarding, emoji and privacy UX,
  the fifth-row protocol; the lab study as the telemetry substitute.
- [ui.md](ui.md) — iOS fidelity, zero-dependency dynamic color, contrast fixes, reduced
  motion, edge-to-edge and cutouts, dark-theme robustness.
- [competitor-features.md](competitor-features.md) — feature landscape and demand evidence,
  the clipboard decision memo, distribution channels, feedback without telemetry.
- [voice-input.md](voice-input.md) — go/no-go on voice: delegate via a mic key, never embed;
  the Tatar ASR reality and the one-permission identity analysis.
- [optimization.md](optimization.md) — measured APK composition, cold-start profile audit,
  PSS, runtime, battery discipline, build loop.

Documents that finish their job (a decision made and recorded in `docs/ROADMAP.md` or
`BRIEF.md`) are removed and listed in `docs/HISTORY.md`.

# The program at a glance

Cross-cutting synthesis of all seven studies: what to do, in what order, and which decisions
only the operator can make. Every item below is detailed, sourced and gated in its own
document; the harness named in brackets is where it is proven.

## Tier 0 — measurement prerequisites (everything else gates on these)

Harness-only work, zero product risk, mostly days:

1. Extend the eval harness: composite next-word hit measurement, the typo-mutated held-out
   set (unlocks autocorrect false-trigger gating and wider typo classes), the Russian
   held-out set, cross-corpus decontamination, bootstrap CIs and top-1 pins, the
   keystroke-savings simulator. [prediction-engine, prerequisites 1–7]
2. Recalibrate the synthetic glide generator against the FUTO MIT corpus and stand up the
   private real-gesture diagnostic harness — fixes the σ validity risk that everything
   glide-side depends on. [glide-typing G1–G2]
3. Device observability legs: the PSS anon/file split, the suggestion round-trip budget, the
   battery leg, the uiMode-flip and font-scale probes. [optimization; ui]
4. Dev loop: split the calibration suites out of the default test leg (full set stays in CI
   and release_check), parallelize the python loop, CI reproducible job 4 builds → 2.
   [optimization]

## Tier 1 — quick wins (days, high certainty)

- Contrast: fix the three WCAG failures (incl. the undocumented dark action accent) and pin
  all ratios in a contract test. [ui UI1]
- Default-config glide recovery: alternates and refusals visible even with suggestions off;
  undo re-shows the gesture's N-best; trail contrast per theme. [ux UX6–UX8]
- Balloon clamping + neck mirroring; finish the two pending device verifications (more-keys
  border, emoji-panel inset). [ui UI4]
- Reduced-motion gating of the hand-rolled animations; emoji haptic through the app toggle.
  [ui UI5–UI6]
- Onboarding: pre-arm the system warning, auto-return from system settings, the try-it field
  invites a glide. [ux UX11–UX13]
- Free trust signals: Data safety alignment, localized screenshots, reproducible-builds line.
  [ux UX19, competitor-features]
- Gestures and editing: word-delete swipe, the long-press text-editing menu,
  shift-cycles-case. [competitor-features]
- Memory/startup hygiene: onTrimMemory → deallocateMemory, lazy bigram attach, profile
  verification gate, record the cold-start median in the release record. [optimization]

## Tier 2 — the big rocks (weeks, each gated on Tier 0)

1. **Morphology backoff** — stem-keyed bigram table as a second-chance source (simulate
   offline first; the engine changes only if the simulated gate passes). The top prediction
   lever: 45% of eval tokens are inflected. [prediction-engine P1]
2. **Wider typo classes** (insertion/deletion/transposition ranked by edit distance) — the
   largest realistic completion gain. [prediction-engine P2]
3. **Corpus upgrade** — corpus.tatar permission letter (biggest single ranking lever) +
   HPLT/MADLAD ingestion with Russian-bleed filtering. [prediction-engine P3]
4. **Glide decoder**: confidence-aware commit, speed-adaptive weighting, the bigram channel
   on the N-best (this is also the productive closure of the parked A6 patch), endpoint
   pruning n=3, per-language constants. [glide-typing G3–G8]
5. **Dynamic-color theme variant** (zero-dep, framework palette). [ui UI2]
6. **Inline autofill** in the strip (the #1 complaint class against offline keyboards) and
   **backup/export** of learned data via SAF (the only migration story an offline app has).
   [competitor-features]
7. **Inline revert cell** for autocorrect and **persistent refused corrections**. [ux UX1–2]
8. **The lab program**: the fifth-row A/B/C protocol (three arms — current, frequency,
   incumbent/desktop order; N=24; pre-registered gate) inside the standing lab instrument.
   [ux UX20, UX16]
9. ~~Distribution~~ — descoped by the operator.
10. ~~Feedback channels~~ — descoped with the distribution/community track.

## Tier 3 — operator decisions (recorded)

- **Clipboard**: decided — text shortcuts and the in-memory recent-clip cell are in scope
  (Tier 2); the persistent history pane is declined. [competitor-features]
- **Voice**: declined entirely — no in-app recognition, no delegation key; the BRIEF
  exclusion stands unchanged. [voice-input]
- **A6 glide context rerank**: closed — the threshold is confirmed, the patch stays parked;
  the pair-conditional variant may be tested under G5. [glide-typing]
- **Strip stays at 3 cells**: if final, the K=3 repack reclaims ~20–25 KB. [prediction-engine
  P-notes]
- **Corpus licensing**: all surveyed corpus and frequency-list sources (incl. CC BY-SA
  Wikipedia/Taiga and the corpus.tatar lists) may be used for now; the legal review is
  deferred and out of the plan. Personal-data scraping stays excluded — that is privacy, not
  licensing. [prediction-engine]

## Conflicts and trade-offs found

- **Merged tt+ru prediction** would dissolve the language switch for the bilingual audience
  but collides with the PSS ceiling and cold start; the code-switched eval set sizes the gap
  before any commitment. [ux UX23]
- **Toolbar real estate** vs the iOS-minimal identity: the long-press menu is the cheap 80%;
  HeliBoard's full toolbar has as many remove-votes as extend-votes. [competitor-features]
- **Dynamic color** ships as a variant, never the default — the iOS look is the identity.
  [ui UI2]
- **Size headroom**: spend it on features, not on compression heroics; keep ≥40% free.
  [optimization]
- **Voice identity tension** resolved by delegation: the mic lives in the user's chosen app,
  our process never opens it. [voice-input]

## Rejected with evidence (do not reopen without new data)

Runtime neural LMs and neural glide decoders; SentencePiece/BPE at runtime; in-app ASR and
in-IME SpeechRecognizer; trigram prediction (stays); exponential recency decay (our LRU +
counters are at the published optimum); Liquid Glass imitation; true-black theme; a theme
engine/store; flow-through-space gestures; DTW as the primary glide metric; wiggle-repeat and
incremental LM-guided decoding (live patents); stem+affix dictionary repack; bundling a font;
zstd/brotli assets (undecodable by the runtime); translation/GIF/stickers/Emoji Kitchen/
proofread AI (network, size, or IP); `org.gradle.parallel`; profileinstaller (accepted API
24–27 profile limitation, worth one documented line).
