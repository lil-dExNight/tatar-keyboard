# GLIDE-LIVE-STRIP4 — live glide scoring, glide learning, the four-cell strip (2026-09-27)

One wave, three roadmap leftovers shipped together on top of release 3.2.0:

1. **Live per-MOVE scoring** (parked in `docs/GLIDE-PLAN.md`): the strip now answers DURING an
   armed glide gesture, not only at the lift.
2. **Glide-triggered learning** (same parking lot): a glide commit moves the personal
   dictionary's usage counters, exactly like a strip tap.
3. **The four-cell suggestion strip** (T7 of `docs/ROADMAP-P4.md`, reopened): the measured
   latent value at stored rank 4 (+1.5643 pp) is realized — and the Tatar bigram table is
   repacked at K = 4 to feed the fourth cell.

## 1. Live per-MOVE scoring

**Design.** `PointerTracker` emits the partial path while an armed glide moves, throttled by
BOTH a time gate and a fresh-points gate (`maybeEmitGlideProgress`: 120 ms AND 5 new points —
the interval rides the POCO C71's measured decode cost, p95 ≈ 50 ms from P7-4, so the engine
worker stays under ~50 % duty; the latest-only engine drops whatever it cannot serve). The
emission rides `KeyboardActionListener.onGlideProgress` (default no-op, same shape as
`onGlideInput`) → `LatinIME.onGlideProgress` → `SuggestionsController.onGlideProgress`, which
applies the lift's gates PLUS the suggestions master (the preview paints the suggestions
surface; with the master off it stays dark and the lift-commit stands alone, P7-6 unchanged).

**The preview never commits and is never tappable.** `applyGlideProgressResult` clears all
three tap bindings before painting ("what is painted is tappable" would otherwise let a tap
commit an old binding under new words); a tap mid-gesture is impossible anyway (the gliding
finger is down, and multi-touch cancels the glide fail-closed). An empty decode leaves the band
alone — one throttle window of the previous content reads better than a flicker to empty.

**Lift vs preview discrimination** is a single boolean (`glideLiftInFlight`), set BEFORE the
request is issued — a synchronously-answering test engine delivers inside the request call, so
token-identity bookkeeping set after the call would misfire (found by
`GlideEndToEndTest`, the whole class went red on the first cut). Stragglers older than the
latest request never reach the dispatch: the engine's `isCurrent` fails them upstream.

**Pins:** +5 controller e2e tests in `GlideEndToEndTest` (untappable preview, progress→lift
commit, master-off silence, half-typed gate, no learning counters moved) and +2 source-contract
tests in `GlideTouchIntegrationContractTest` (armed-branch emission order, throttle gates,
listener no-op + wiring, "the preview body never calls editor.commit*").

> **2026-09-29 — REVERTED by operator UX decision.** Suggestions appear only at finger lift
> again (the v3.2.0 behavior): nothing paints mid-gesture — the lift decodes and commits. The
> whole preview half of this section went with it: the `maybeEmitGlideProgress` throttle in
> `PointerTracker`, the `onGlideProgress` listener plumbing (`KeyboardActionListener` →
> `LatinIME` → `SuggestionsController`), the `glideLiftInFlight` discrimination flag,
> `applyGlideProgressResult`, and the preview tests (5 e2e + 2 contract + 1 device). The glide
> learning half (section 2) stays untouched.

## 2. Glide-triggered learning

A lift-committed word is announced as an accepted suggestion
(`CleanRunMachine.noteAcceptedSuggestion`): if it is a saved personal word, its usage counter
moves in memory and persists at the session boundary, exactly as the strip tap path already did
(the sink decides membership — dictionary and unknown words change nothing). A tap on a glide
ALTERNATIVE counts the alternative the same way. Pinned imprecision: the one-backspace
gesture-undo deletes the word but does NOT roll the counter back — the same rule the tap path
lives with (a bump survives a later backspace), which keeps the undo paths free of counter
arithmetic. The pair machine was already told (`trustPairBoundary`); nothing else changes.

**Pins:** +5 tests in `GlideEndToEndTest` (lift announces, alternative announces the
alternative, refused commit announces nothing, undo keeps the record, the master-off lift still
announces).

## 3. The four-cell strip

> **2026-09-29 — REVERTED to three cells by operator UX decision.** The contract half of this
> section is undone (`CELL_COUNT`/`MAX_RESULTS` back to 3, the seams back to three arguments,
> the pins back to the pre-wave values — eval next-word 547 → 471, the +1.75 pp knowingly given
> back). **The data half is NOT undone:** the Tatar bigram table stays packed at K = 4 — its
> fourth successor per head is unread headroom (`TatBigrPrefixIndex.MAX_RESULTS` = 3 never reads
> it), so a future four-cell revisit gets it for free, no repack needed. Glide learning
> (section 2) is untouched, `MAX_PERSONAL_BIGRAM_CELLS` stays 2, `STRIP_HEIGHT_DP` stays 44.

**Contract change.** `SuggestionStripState.CELL_COUNT` 3 → 4 (same 44dp height — quarters, not
a taller band), `CompositePrefixComputer.CELL_COUNT` 3 → 4, `TdictPrefixIndex.MAX_RESULTS` 3 → 4,
`TatBigrPrefixIndex.MAX_RESULTS` 3 → 4; the seam `StripSurface.showSuggestions` /
`setSpokenCellLabels` grew the fourth argument through the view, `InputView` and the `LatinIME`
adapter. The autocorrect preview keeps its two-cell decision layout (typed + emphasized
correction, the rest empty — a decision, not a ranking). `MAX_PERSONAL_BIGRAM_CELLS` stays 2:
the leave-room rule survives at four cells.

**The data half.** The Tatar bigram table is repacked at K = 4 (the Russian one never left it):
`rebuild_assets.py --only tatar`, 13 154 heads unchanged, pairs 38 874 → 51 484, compressed
79 574 → 104 028 B (+24 454 B — the same ~19 KB order P4 measured, plus the P5a head growth).
The dictionary is byte-identical; the Russian side is byte-identical (the `--only` snapshot
rule). Known drift unchanged at 155/0 (`--check --allow-known-drift` → ok).

**Measured value, realized:** the eval set's next-word hits moved **471 → 547** on the same
4 347 pairs (unconditional 10.8351 % → 12.5834 %, covered-conditional 12.8724 % → 14.9494 %) —
+1.75 pp against the +1.5643 pp the T7 measurement predicted from the smaller pre-P5a head set.
Every re-pinned 4th-cell value was checked to be the sensible chain output (4th stored successor
where the table grew one, otherwise the next form/fallback word): сәлам → …хатлары,
һәм → …ул, кил → …монда.

**Test migration:** 34 test files re-pinned/updated — every strip pin in the repo (the P4 note's
predicted cost), including the smoke: `scripts/emulator-smoke.sh` taps per-cell coordinates now
(cell centers at quarters), and the two tt probes were re-derived against the K = 4 table —
татар taps дәүләт from cell 2 (four stored successors fill the strip), and сәләм REPLACES сакчы
for the free-cell proof (the 2026-09-23 EXPAND-1 extra heads made сакчы a bigram head; the
"сакчы is no head" premise was silently stale since). The follow-up probe commits һәм from
сәләмә's pure-fallback band. Side catch, fixed on the way: the smoke's field-empty gate only
knew the ENGLISH setup hint — with the app defaulting to Tatar since 3.1.0 the hint text failed
the check on a clean field; all three locales' hint prefixes are now accepted.

## Gates and evidence (2026-09-27/28)

- JVM: **1 703 tests, 182 suites, 0 failures/errors/skipped** (`./gradlew test --rerun-tasks`).
- python pipeline: **507 tests, 16 files, 0 failing** (incl. the re-pinned
  `tests/rebuild_assets` argv for K = 4).
- Emulator smoke on `tt_suggest_a14` (wiped AVD): **21 PASS / 0 FAIL / 1 SKIP** (the en- skip is
  by design — no English dictionary ships), including all three re-derived strip probes.
- Instrumentation on the same AVD: `GlidePointerDeviceTest` **OK (4)** — the real PointerTracker
  drives armed-glide MOVEs, so the progress emission ran there; `GlideDeviceInstrumentationTest`
  **OK (3)** — live-geometry decode (сәләм top-1), decode p95 0.787 ms and cold index rebuild
  173.6 ms (x86_64 emulator numbers; NOT device-true — the POCO p95 was ~50 ms at P7-4);
  `EmojiIndexReloadInstrumentationTest` **OK (1)**.
- `rebuild_assets.py --check --allow-known-drift` → `"ok": true`.

**Device legs (POCO C71, 2026-09-28, evidence `build/device-uat-2026-09-28/`):** all closed on
hardware. Instrumentation: `GlidePointerDeviceTest` OK (5 — including the new
throttled-progress pin), `GlideDeviceInstrumentationTest` OK (3) with decode **p50 1.548 /
p95 3.083 ms** on the live 720×1640 geometry (the P7-4 reading of 50 ms is superseded by an
order of magnitude; live scoring's added decodes are ~2.5 % worker duty at the 120 ms
throttle), `EmojiIndexReloadInstrumentationTest` OK (1), `E3bComputeInstrumentationTest` OK (2)
(typo-tatar p95 3.182 ms). `GlideUiDeviceTest` passes on both methods — run PER METHOD; the
combined class run wedges in the second `prepareField` (pre-existing harness artifact, not this
wave). Interactive UAT: the four-cell NEXT_WORD band shows сәләмә|һәм|белән|да and the tap
commits сәләмә; татар's K = 4 row shows теле|дәүләт|телен|телендә and cell 2 commits дәүләт; a
slow background swipe caught the LIVE preview mid-gesture (trail + four candidate cells, field
untouched until the lift); a personal word added through the settings UI («цук», counter 1) was
committed by a straight-line glide and the personal-dictionary screen then showed **2 uses** —
glide-triggered learning proven on hardware. (The settings screens shoot black on screencap —
FLAG_SECURE working as intended; those steps are proven by the uiautomator dumps.)
