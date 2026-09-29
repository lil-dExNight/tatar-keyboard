# PERF-BUDGETS — living performance-budget table

Created 2026-09-29 (item O1 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`).
One row per metric: the budget, the latest measured value (with date and
device), the status, and the gate or ritual that guards it. Update the
"latest measured" cell whenever a newer number lands; never delete history —
older values stay in the cited wave documents.

Reference device: POCO C71 (Xiaomi 25028PC03G, Android 15 Go, 2.7 GB RAM,
720×1640) — the budget hardware of the target audience. Host pins run in CI
on the committed assets.

## The table

| Metric | Budget | Latest measured | Status | Gate / ritual |
|---|---|---|---|---|
| Release APK size | ≤ 3 145 728 B (`BRIEF.md:24`) | **1 804 874 B** signed 3.4.0, 2026-09-28 (`docs/APK-AUDIT-3.4.0.md:3`) | **gated** | `scripts/release_check.sh:25` (`APK_SIZE_LIMIT`) → `artifact.size` (`scripts/release_check.sh:242`) |
| Cold start (process → first frame) | < 400 ms | release: median **300.6 ms** (verify) / 296.6 ms (speed-profile), 2026-09-04, POCO C71 (`docs/DEVICE-UAT-1.9.12.md:63`); `am start -W` medians 252/269 ms in the 3.0.0 wave 2026-09-24 (`docs/APK-AUDIT-3.0.0.md:136`) and 257 ms after the arsc-deflate wave 2026-09-25 (`docs/OPTIMIZE-2026-09-25.md:265`). 2026-09-29 ritual on the **debug** build (3.4.0 + the uncommitted wave): median **667.6 ms** (662.9–682.0, n=5; earlier same-day run 665.2) — debug-build scale, ~2.3× the release numbers, NOT budget-comparable; pre-wave debug A/B the same day: 675.9 ms → the wave itself is cold-start-neutral (`build/device-uat-2026-09-29/perf/`, `perf-base/`) | **scripted ritual** (O2 landed) | `scripts/device-perf-ritual.sh` leg A — run-as `kill -9` (NOT force-stop: HyperOS resets the selected IME, and `ime set` itself starts the IME process, which would poison the process-start timestamp) → Chrome omnibox focus (foreign package: the process provably starts as the IME) → /proc-stat field 22 → first FrameCompleted, the framestats column resolved **by header name** (on Android 15 FrameCompleted is column 17, not 14 — the 2026-09-04 script read column 14 = SyncStart, ~30 ms early on the cold first frame; the release history stays inside the budget either way); release-checkbox `docs/PUBLISH-CHECKLIST.md:1502` |
| Frame time (board draw) | 16.7 ms frame deadline; no written p50/p90 number | O2-2 release record 2026-09-25, POCO C71: p50 8.2 → **6.8 ms**, p90 ~11.4–12.4 ms (`docs/OPTIMIZE-2026-09-25.md:278`). 2026-09-29 ritual, O2-2 protocol on the **debug** build (3.4.0 + wave): p50 8.7–9.4 ms (median of medians 9.2), p90 14.6–15.9, p95 15.6–17.6, n=120 × 3 runs; pre-wave debug A/B same morning: p50 9.0/9.1/9.3 — **the wave's two draw-loop fixes (ArrayList walk, cached underline measure) are frame-neutral on hardware**; the ~2.3 ms offset from the O2-2 record is the debug-build class, not the wave. The release-scale p50 of the current tree is unmeasured (needs the release APK with suggestions on — the opt-in flow is not automated) | **scripted ritual** (O4 device leg done) | `scripts/device-perf-ritual.sh` leg C — `dumpsys gfxinfo` framestats of the `InputMethod` window, fixed 32-event Tatar script ("сәләм дөнья мин сине яратам дус ", 0.35 s per tap), last 120 frames × 3 runs, columns by header name; plus a field-content proof that the Tatar script actually landed |
| Glide decode p95 (device) | ≤ 5 ms | **3.125 ms**, 2026-09-28, POCO C71 (`docs/APK-AUDIT-3.4.0.md:50`); history: 3.083 (3.3.0 wave), 3.39 (P7 wave) | **gated (device)** | `app/src/androidTest/.../glide/GlideDeviceInstrumentationTest.kt:99` (`assertTrue p95 <= 5.0`) |
| Typo-probe compute p95 (device) | written ≤ 3.5 ms (`docs/TT-TYPO-NEXT.md:648`); asserted at 5.0 ms as a flake-safe bound | **3.228 ms**, 2026-09-28, POCO C71 (`docs/APK-AUDIT-3.4.0.md:51`); earlier record 3.306 ms, 2026-09-20 | **gated (device, conservative bound)** | `app/src/androidTest/.../engine/E3bComputeInstrumentationTest.kt:144-154` |
| Prefix compute p95 (host, real assets) | ≤ 5 ms | sub-millisecond on dev hosts; exact printout per run | **gated (CI-safe)** | `app/src/test/.../engine/RealDictionaryPrefixIndexTest.kt:58,83,119` |
| Request → non-applying handoff p95 (host) | ≤ 16 ms | per-run printout | **gated (CI-safe)** | `RealDictionaryPrefixIndexTest.kt:124` (`requestToNonApplyingHandoffP95IsAtMostSixteenMilliseconds`, assert at line 158); checklist `docs/PUBLISH-CHECKLIST.md:1503` |
| NEXT_WORD composite predict p95 (host, real assets) | ≤ 5 ms | median **0.032 ms**, p95 0.038 ms, 2026-09-25 (`docs/OPTIMIZE-2026-09-25.md:218`) — >100× headroom | **gated (CI-safe)** | `app/src/test/.../engine/TtNextWordPredictP95Test.kt:71` (assert `p95 <= 5_000_000 ns`) |
| Glide decode p95 (host, gate G2) | ≤ 2 ms | per-run printout | **host-only; CI-skipped since 2026-09-28** (shared-runner noise: p95 > 2 ms on a loaded GitHub runner while the same commit passed elsewhere) | `app/src/test/.../glide/GlideRecoveryCalibrationTest.kt:441-442` (asserted only when `System.getenv("CI") == null`, constant at line 669); the hardware budget stays fail-closed via the device row above |
| PSS (resident memory) | **≤ 114 000 kB** total PSS in any ritual scenario (debug scale — the ceiling the ritual enforces; set 2026-09-29: highest reading of the day 90 526 kB + ~25% headroom, rounded up). Release-scale context: the only valid release measurement stays the 1.9.12 peak 46 554 kB (same headroom ⇒ ≈59 000 kB) — the current tree's release PSS is unmeasured, and today's debug numbers sit over a much larger dictionary than 1.9.12's, so the two scales must not be mixed | 2026-09-29 ritual (debug, POCO C71): keyboard-idle 81 536, after-50-words (25 tt + 25 ru scripted) 90 526, **emoji-panel-open 85 043**, panel-closed 78 537, idle-30 s 76 801 kB — the idle-release path drops ~13.7 MB after 30 s hidden (`build/device-uat-2026-09-29/perf/meminfo-*.txt`); pre-wave debug A/B same shape (peak 84 738) → the wave is PSS-neutral. Release: peak **46 554 kB** (emoji panel open), 43 290 shown/idle, 35 120 after typing, 2026-09-04 (`docs/DEVICE-UAT-1.9.12.md:87`) | **ceiling set 2026-09-29; scripted ritual** | `scripts/device-perf-ritual.sh` leg B (`dumpsys meminfo` after each scenario: keyboard-idle → 50 words → panel open → panel closed → 30 s idle); checklist row `docs/PUBLISH-CHECKLIST.md:1498-1501` |
| Warm show (process alive) | < 150 ms (`research/03-optimizaciya-slabye-ustroystva.md:224`) | — | **never measured** | none |
| Touch-event handling (our code) | < 5 ms (`research/03-optimizaciya-slabye-ustroystva.md:225`) | — | **never measured** | none |
| Janky frames while typing | ~0 % (`research/03:226`); release-checkbox phrasing ≤ 1 % (`docs/PUBLISH-CHECKLIST.md:1505`) | — | **never measured on real hardware** (the archived 25.93 % was swiftshader, not a device) | none |
| Zero allocations in the draw loop | 0 (hard rule) | convention since the fork; per-path pins below | **partially gated** — behavioral pins cover the decoder, glide trail, emoji panel and strip; the board `KeyboardView.onDraw` has no pin yet (lands as plan item O3) | behavioral: `GlideDecoderTest.kt:192`, `GlideTrailTest.kt:146,179`, `EmojiPanelStateTest.kt:860`; source-contract: `SuggestionStripDecorationContractTest.kt:75`, `EmojiPanelSourceContractTest.kt:91`, `EmojiRecentAndFlingSourceContractTest.kt:102` |
| Resident glide word index | at most ONE across languages | pinned 2026-09-25 (C3 of `docs/ROADMAP-P8-PLAN.md`) | **gated (JVM source contract)** | `app/src/test/.../suggestions/GlideIndexResidencySourceContractTest.kt:24`; idle release (O2-3) pinned by `MappedDictionaryEngineGlideTest.anIdleReleaseDropsTheIndexAndTheNextGlideRebuildsIt`; rebuild cost measured on device: 730.8 ms cold / 1.1 ms warm (`docs/OPTIMIZE-2026-09-25.md:302`) |
| Emoji indices residency | released on idle (10 s after the keyboard hides), lazy reload | reload measured 2026-09-25, POCO C71: search index 216.0 ms, suggest table 202.1 ms (`docs/OPTIMIZE-2026-09-25.md:310-319`) | **gated (device instrumentation)** | `app/src/androidTest/.../emoji/EmojiIndexReloadInstrumentationTest.kt`; release chain pinned in `EmojiPanelAccessibilitySourceContractTest.kt:201-229` |

## Measurement discipline

- **Hard timing gates live on the real device, not on shared CI runners.**
  Wall-clock asserts on shared runners flake: on 2026-09-28 the G2 host gate
  (p95 ≤ 2 ms) failed on a loaded GitHub runner while the identical commit
  passed elsewhere — the gate is now asserted only when `CI` is unset, and
  the hardware budget is asserted fail-closed on the POCO instead
  (`GlideRecoveryCalibrationTest.kt:436-443`). A host timing pin is CI-safe
  only with ~100× headroom (e.g. the 0.032 ms median against the 5 ms
  NEXT_WORD budget).
- **Median and p95 over fixed scripts, sample size recorded.** Cold start:
  20 attempts. Frames: 32-event script, 120 frames × 3 runs. Device decode:
  1 000 samples. Host pins: 1 000–2 000 samples after an explicit warmup.
- **Idle device, screen on, USB power**, battery level recorded in the wave
  document. Discard thermal-throttled runs; re-run rather than average them
  in.
- **Never force-stop the IME before a typing measurement**: HyperOS silently
  resets the default IME when the owning package is force-stopped
  (`docs/OPTIMIZE-2026-09-25.md:251-254`).
- **Emulator numbers are not device numbers**: the POCO hardware measured
  2.4× heavier than the `tt_suggest_a14` AVD on cold start
  (`docs/DEVICE-UAT-1.9.12.md:70-72`). Emulator results are recorded as
  partial evidence and close no budget row.
- **Debug numbers are not release numbers** (found 2026-09-29): the debug
  build measures ~2.3× the release cold start (667.6 ms vs the 296–300 ms
  release medians) and roughly 1.8× the 1.9.12 release PSS peak. The ritual
  measures the debug build; release-scale rows keep their release-scale
  measurements, and a debug reading never closes or breaks a release budget.
- **Kill the process, don't force-stop it, for cold-start legs**
  (2026-09-29): `run-as <pkg> kill -9` leaves the selected IME untouched
  (HyperOS resets the default IME on force-stop of the selected package) and
  leaves the process start to the next field focus — `ime set` alone already
  starts the IME process, so any dance through it contaminates the
  process-start timestamp. `kill -9` is also the realistic trigger (LMK).
- **Parse framestats columns by header name**: the Android 15 dump inserts
  FrameTimeline columns (FrameCompleted = column 17; the pre-FrameTimeline
  layout had it at 14, which today reads SyncStart).
