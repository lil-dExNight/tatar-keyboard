# Performance budgets

One row per budget: the metric, the budget, and what enforces it. Measured values are not
recorded here; the enforcing test or script prints them on every run.

Reference device: POCO C71 (Android 15, HyperOS, 720×1640), a budget phone typical of the target
audience. Device tests and scripts are calibrated for it.

Enforcement kinds:

- **JVM test**: part of `./gradlew test`, runs in CI on the committed assets.
- **Device test**: instrumentation test in `app/src/androidTest`, run by hand on the reference
  device (see the instrumentation command in `AGENTS.md`); it fails when the budget is exceeded.
- **Device script**: `scripts/device-perf-ritual.sh` on the reference device. It prints
  `RESULT|…` lines; each budgeted line carries the budget and `over_budget=true|false`, which a
  person reviews (the line itself is not a failure).
- **Release check**: `scripts/release_check.sh`, run before every release.

## Budgets

| Metric | Budget | Enforced by | Where |
|---|---|---|---|
| Release APK size | ≤ 3 145 728 B (3 MiB) | Release check; CI | `APK_SIZE_LIMIT` in `scripts/release_check.sh` (check `artifact.size`); the size step in `.github/workflows/ci.yml` |
| Cold start (process start → first IME frame) | < 400 ms median on a release build | Device script, `cold` leg (`cold-start` line, `budget_ms=400 over_budget=…`) | `scripts/device-perf-ritual.sh --pkg org.tatarkeyboard.ime`; the default debug package is not comparable with the release budget (see below) |
| Warm show (process alive → keyboard shown) | < 150 ms median | Device script, `warm` leg (`warm-show` line, `budget_ms=150 over_budget=…`): ACTION_UP of the tap on Chrome's omnibox (atrace) → first IME frame (framestats) | `scripts/device-perf-ritual.sh` |
| Touch handling in our code (event → commit) | < 5 ms p95 | Device script, `touch` leg (`touch` line, `budget_ms=5 over_budget=…`): the framework's `deliverInputEvent` slice on the IME main thread (atrace) over the fixed 32-tap Tatar script, an upper bound of our handling | `scripts/device-perf-ritual.sh` |
| Frame time while typing | p95 within the 16.7 ms frame deadline | Device script, `frames` leg (`frames` line, `budget_ms=16.7 over_budget=…`; p50/p90/p95 over a fixed 32-tap Tatar script, 120 frames × 3 runs) | `scripts/device-perf-ritual.sh` |
| Janky frames while typing | ≤ 1 % of the IME window's frames per run | Device script, `frames` leg (`jank` line, `budget_pct=1.0 over_budget=…`; gfxinfo "Janky frames" of the InputMethod window over the same script) | `scripts/device-perf-ritual.sh` |
| Zero allocations in the draw loop | 0 per frame in our code | Device test (board blit and redraw, a held key painted over the blit, suggestion strip: exactly 0; the board press/release cycle is bounded by the platform's `StateListDrawable` floor); JVM tests for the other draw paths | `DrawAllocInstrumentationTest`; `KeyboardViewDrawLoopContractTest`, `GlideDecoderTest`, `GlideTrailTest`, `EmojiPanelStateTest`, `SuggestionStripDecorationContractTest`, `EmojiPanelSourceContractTest`, `EmojiRecentAndFlingSourceContractTest` |
| Resident memory (total PSS), release build | ≤ 69 000 kB in every scenario of the device script, suggestions on | Device script, `pss` leg (`pss` line, `budget_kb=69000 over_budget=…`; keyboard idle → after 50 words → emoji panel open → closed → 30 s idle), run with `--pkg org.tatarkeyboard.ime --enable-suggestions-ui` | `PSS_BUDGET_RELEASE_KB` in `scripts/device-perf-ritual.sh`. The ceiling is the highest scenario of three repeatable runs plus about 10 %, which covers the difference seen between sessions. The release manifest (`app/src/release/AndroidManifest.xml`) declares this ceiling, rounded up to whole MiB, as the `<memory-budget>` of the perceptible state (API 37.2+; the system reclaims pages above it and counts the cgroup charge, not PSS); `MemoryBudgetManifestSourceContractTest` keeps the two equal. Original design target: ≤ 30 MB shown, ≤ 15–20 MB 30 s after hiding; not enforced |
| Resident memory (total PSS), debug build | ≤ 114 000 kB in every scenario of the device script | Device script, `pss` leg (`pss` line, `budget_kb=114000 over_budget=…`) on the default debug package | `PSS_BUDGET_DEBUG_KB` in `scripts/device-perf-ritual.sh` |
| Glide decode p95 on device | ≤ 5 ms | Device test | `GlideDeviceInstrumentationTest` |
| Typo recovery lookup p95 on device | ≤ 3.5 ms; asserted at 5 ms to avoid flaky failures | Device test (typo probes, both fuzzy policies) | `E3bComputeInstrumentationTest`; the mmap read path is held to the same 5 ms bound by `DictionaryIoStrategyInstrumentationTest` |
| Word completion p95 on the host, real dictionary | ≤ 5 ms | JVM test | `RealDictionaryPrefixIndexTest` (plain, one-letter fan-out and Tatar fuzzy policy) |
| Request → hand-off to the UI thread p95 on the host | ≤ 16 ms | JVM test | `RealDictionaryPrefixIndexTest.requestToNonApplyingHandoffP95IsAtMostSixteenMilliseconds` |
| Next-word prediction p95 on the host, real assets | ≤ 5 ms | JVM test | `TtNextWordPredictP95Test` |
| Glide decode p95 on the host | ≤ 2 ms | JVM test, asserted only when `CI` is unset | `GlideRecoveryCalibrationTest` (`G2_P95_MS`); the device row above covers CI |
| Bundled file validation allocation | ≤ one raw copy plus a fixed margin (a bigram table also gets 4 B per pair) | JVM test | `ValidatorAllocationTest` |
| Resident glide word indexes | At most one across languages | JVM tests | `GlideIndexResidencySourceContractTest`; `MappedDictionaryEngineGlideTest.anIdleReleaseDropsTheIndexAndTheNextGlideRebuildsIt` |
| Idle memory release | Glide indexes, emoji search index and emoji suggestion table dropped 10 s after the keyboard hides, reloaded lazily | JVM tests; reload cost logged by a device test (not asserted) | `LatinIME.DELAY_DEALLOCATE_MEMORY_MILLIS`; `EmojiPanelAccessibilitySourceContractTest`; `EmojiIndexReloadInstrumentationTest` |

## Measurement rules

- **Hard timing limits belong on the device, not on shared CI runners.** Wall-clock asserts on
  shared runners fail at random. A host timing test is CI-safe only with a very large margin
  (such as next-word prediction against its 5 ms budget); otherwise it is skipped under `CI`, as
  the host glide decode test is.
- **Report median and p95 over a fixed script, with the sample size.** The device tests and the
  device script use fixed inputs and print their sample counts.
- **Idle device, screen on, USB power.** Record the battery level; discard thermally throttled runs
  and re-run instead of averaging them in.
- **Do not force-stop the package whose IME is selected.** HyperOS silently switches the default
  IME when that package is force-stopped. For a cold start, kill the process with
  `run-as <pkg> kill -9`: it keeps the IME selection and matches how the system reclaims memory.
  `ime set` starts the IME process immediately, so it must not run between the kill and the
  measured field focus. A release package refuses `run-as`: select another keyboard, `am kill`
  the package, raise that keyboard on the field, then `ime set` ours; the bind starts the process
  with the show request already pending, so the measured path is the same.
- **Read framestats columns by header name.** Android 15 inserts FrameTimeline columns, so fixed
  column indexes read the wrong field.
- **Emulator numbers are not device numbers.** The reference device is much slower than the
  emulator on cold start; an emulator result never closes a budget row.
- **Debug numbers are not release numbers.** A debug build measures roughly twice the release
  cold start and resident memory. A debug reading never closes or breaks a release budget.
