# TT-NEXTWORD-FILL — plan: never-empty strip after a committed word

Status: approved work plan. Origin: operator bug report 2026-09-20 (device,
2.0.0): after accepting `сәләм` the strip offered only `сәләмә`; after
accepting `сәләмә` the strip went empty until the next letter.

## Diagnosis (verified against the shipped assets)

- `сәләм` (freq 36) is not a bigram-table head (10 204 heads are cut off far
  above it) → zero bigram successors.
- The only inflection of `сәләм` attested in the 110 000-entry dictionary is
  `сәләмә` (28) → the strip shows exactly one cell.
- `сәләмә` is not a head and has no attested forms → empty strip.
- The code behaves per contract; the gap is data coverage for non-head words.
  Gboard-class keyboards fill this case with globally frequent words.

## DONE WHEN

1. After committing a Tatar word, the strip shows up to 3 cells by the
   priority chain: bigram successors → word forms → global top-frequency
   words. The strip after a committed word is never empty while suggestions
   are on.
2. The fallback never displaces bigram successors, word forms, or the emoji
   tail cell; the committed word itself and already-shown words are excluded.
3. Measured: the strip-empty-after-word rate on the eval set drops to ~0;
   all other `TtSuggestEvalTest` metrics unchanged.
4. All gates green; device UAT replays the operator's exact scenario
   (сэлэм → tap сәләм → 3 cells; tap a cell → 3 cells again).

## Design

- New read API on the prefix index: `topFrequentWords(n)` — one lazy O(N)
  frequency scan of the mmap'd dictionary per engine start (fixed-size
  selection, preallocated buffers, never on the lookup path), cached.
  No new asset, no new pins.
- New `FallbackWords` seam in `CompositePrefixComputer.predict` (same pattern
  as the P3 after-word forms seam): fills cells still empty after bigrams,
  forms and the emoji tail; excludes the committed word and duplicates;
  order = frequency desc as produced by `topFrequentWords`.
- Enabled for both language engines (fill-only change, empty cells only;
  Russian behavior change is strictly "empty cells get global top words" and
  gets pinned in tests).
- Amends the frozen strip contract deliberately (fallback cells after a
  committed word); recorded in the mission report, archive untouched.

## Tasks

A. `topFrequentWords` on `TdictPrefixIndex` + unit tests (real-asset pin of
   the top list; zero-allocation contract of the lookup path untouched).
B. `FallbackWords` seam + merge + tests: composite-level order/dedup/
   exclusion cases; controller end-to-end: commit `сәләм` →
   `[сәләмә, top1, top2]`; commit `сәләмә` → `[top1, top2, top3]`; bigram-head
   word unchanged (3 successors → no fallback); emoji tail unchanged; ru pin.
C. Eval metric: strip-empty-after-word rate in `TtSuggestEvalTest`
   (before → after, re-pinned). Gates: JVM, python, lint, rebuild --check,
   release APK size, check-no-internet.
D. Device UAT (POCO C71, connected): replay the operator's scenario with
   screenshots; cold start; crash buffer. Mission report
   `docs/TT-NEXTWORD-FILL.md`, HANDOFF.md, CHANGELOG.md (Unreleased),
   docs/README.md, AGENTS.md counters. No version bump (post-2.0.0 work;
   the release decision is the operator's).
E. Independent re-verification by a fresh agent.

> 2026-09-20 — Phases A–C DONE (uncommitted). `topFrequentWords` landed with
> the top-8 pins of both assets (tt: һәм, белән, да, бу, дә, дип, ул, өчен;
> ru: я, не, в, и, что, ты, на, это); the `FallbackWords` seam fills the
> NEXT_WORD cells in the order bigrams > forms > fallback, wired for both
> shipped languages through the artifact registry. Eval: strip-empty-after-word
> 33.30 % (889/2 670 unique words) → 0. e2e pins: сәләм → [сәләмә, һәм, белән],
> сәләмә → [һәм, белән, да], сәлам unchanged, тюлень → [я, не, в]. All gates
> green: 1 257 JVM tests (+22 over 1 235), 474 python, lint, asset check, release APK
> 1 869 779 B ≤ 3 145 728, no-INTERNET both levels. Recorded consequence: the
> companion-language NEXT_WORD fill no longer fires after a commit (the band is
> always full) — deliberate. Full record: docs/TT-NEXTWORD-FILL.md.

## Out of scope

- Bigram head-count expansion (asset change, separate cost/benefit).
- Autocorrect floor changes.
