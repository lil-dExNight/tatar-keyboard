# Optimization research

Size, cold start, memory, runtime, battery, and the build loop. Unlike the other research
documents, this phase measured the shipped artifact and the code directly (the release APK in
`dist/`, dexdump/profgen against the R8 mapping, JUnit XML sums, daemon logs). Proof rules:
`research/measurement-framework.md`.

## Size

Measured composition of the 3.8.0 APK: dictionaries ~60%, dex ~13%, bigram tables ~9%,
resources ~6%, `resources.arsc` ~5.7% (must stay STORED — not compressible), emoji tables
~4.3%, ZIP/signing overhead ~1.5%. The launcher icon is ~34 KB of `res/`.

Measured headroom: re-compressing the asset payloads with zopfli at pack time saves ~55 KB
(−3.0%) — the only adoptable compression gain, since the runtime inflates only zlib/deflate
(zstd/brotli would save ~150 KB more but are undecodable by `java.util.zip` — the quantified
cost of the zero-dependency rule). Trained preset dictionaries are worthless here (front-
coded payloads are already self-similar) and the validators reject them anyway. Dropping
launcher-icon densities saves ~28 KB at a brand-presentation cost on API 24–25.

**Verdict: reclaiming size is low-value now.** The budget has ~42.5% headroom and recent
releases moved +0.2%. The binding constraint on dictionary *quality* is the self-imposed
format cap (`MAX_COMPRESSED_SIZE` in the packer — both dictionaries sit at ~90% of it), not
the APK budget. If suggestion quality ever wants more entries, raising the format cap is
affordable; the real check then is raw size against the PSS ceiling. Direct the optimization
program at cold-start/PSS/touch margins instead. Take the zopfli option only while the
pipeline change stays cheap (a build-tools bump becomes an asset-changing event — the pin
mechanism absorbs it).

## Cold start

Audit findings (measured against the shipped APK):

- The dex split works: 285 classes in `classes.dex` (profile + R8-adjacent), 135 in
  `classes2.dex`. The shipped binary profile reproduces from the tracked text profile —
  correctly R8-translated.
- Only ~40% of profile classes resolve in the shipped dex — structurally expected (R8
  inlines, R classes vanish); the *trend* of that ratio is the staleness signal, not the
  value.
- The startup and baseline profiles are byte-identical — one generator call puts all five
  journeys (typing, suggestions, glide, emoji panel) into the startup profile.
- Profiles were hand-edited at least once — an unguarded failure mode: a typo'd signature
  silently becomes a dead rule.
- **API 24–27 get no profile benefit at all** without androidx.profileinstaller (which the
  zero-dependency rule forbids) — an accepted limitation worth one documented line.
- Non-Play channels may defer install-time dexopt to overnight — worth a device probe
  (`dumpsys package | grep dexopt` right after an F-Droid/adb install).
- The 3.8.0 cold-start median was never recorded — every option below is relative to an
  unknown headroom. Record it (the ritual prints it; the release record should keep it).

Options, ranked:

1. **CompilationMode A/B benchmark in the baselineprofile module** (Partial(require) vs
   None): when Partial ≈ None, the profile is stale. The stale-profile tripwire; dev-only.
2. **Profile coverage audit per release**: archive the R8 startup diagnostic, and script the
   static resolved-class-ratio check; plus a Perfetto capture diff against the startup
   profile (the release build is already profileable, the TT# spans exist).
3. **Verify profile rules in a gate** (`profgen validate` + resolved-ratio threshold) — kills
   the hand-edit failure mode without banning regeneration-time judgment.
4. **Split a minimal startup journey** (process start → keyboard shown → one word) from the
   full profile; A/B on device via the cold leg with alternating installs. Bounded benefit —
   everything already fits one dex.
5. **Overlap the keyboard XML parse with the IMS bind** — the largest app-side CPU block on
   the first-frame path; measure the TT#loadKeyboard span first, thread-safety review before
   code.

## Memory (PSS)

Platform facts (API 37.2): the `<memory-budget>` charge is anonymous + file-backed pages
(graphics excluded); over-budget processes are forced to drop file pages or swap, with ANR at
the limit. Our mmap'd dictionaries are the cheapest possible memory in this regime. The
kernel's fault-around pulls ~64 KB per early fault until its 32-miss stopper — and our
`open()` walks every block, so **the whole dictionary is faulted in at engine start**
(~2.7 MB for both languages after one mixed tt/ru session). `Os.madvise` is public only from
API 37.1 — not on the reference device.

Options, ranked:

1. **Observability first**: the pss leg saves `/proc/<pid>/smaps_rollup` (the anon/file split
   the budget cares about) and prints the meminfo category table it already dumps; a
   mincore-based residency counter in the I/O-strategy instrumentation test.
2. **Streamed structural validation**: run the `open()` walks over a sequential read window,
   keeping mmap purely for random-access lookups. Estimated −1.5…−2.4 MB PSS after mixed
   sessions; also removes speculative flash I/O at engine start.
3. **`onTrimMemory` → run the existing `deallocateMemory()`** on UI_HIDDEN/MODERATE — system-
   driven drops instead of only the 10 s timer; better kill-ranking between sessions.
4. **Lazy bigram attach** (first next-word request, not engine start) — wins short sessions,
   not the measured peak.
5. **Future-facing (37.1/37.2+)**: `MADV_RANDOM` on mappings; runtime budget below the
   manifest ceiling with an over-budget listener driving early eviction.
6. **Emoji-suggest table heap shape** (LinkedHashMap → flat arrays) only if observability
   shows the emoji scenario is binding.

## Runtime (beyond the draw loop)

The per-keystroke chain, reconstructed from code: ~30–45 objects and ~6–10 KB of young
garbage per keystroke, and ~3 binder transactions (commitText out, `updateSelection` in, a
`getSurroundingText` reload out-and-back). The reload fires on every selection update while
shown; coalescing caps it during fast typing, so the waste concentrates at normal pace. The
largest single garbage source is the up-to-1024-char cache concat per keystroke. The glide
decoder is at its algorithmic floor (fail-fast bounds; one decode per gesture). **The strip
round trip (`TT#suggestLookup`) has no budget and no device leg — the largest unbudgeted
latency surface.**

Options, ranked:

1. **Budget the suggestion round trip on device** (parse the existing atrace slices over the
   32-tap script; a plausible pre-registered gate: p95 ≤ 32 ms on the reference device).
   Lands before the rest so their effects are visible.
2. **Rate-limit self-caused reloads** (per-keystroke `getSurroundingText` → at most every
   ~250–500 ms when the update matches the expected selection; external moves keep per-event
   reload). Watch the correctness surface: the reload is the silent-drift detector.
3. **Char-window cache** instead of per-keystroke String concat (−~2 KB/keystroke at a full
   window); tighten the burst-allocation gate to the new measured value.
4. **Fast-path `normalizeForLookup`** (single scan; skip NFC/lowercase for precomposed
   lowercase words) with a property test against the old path over the pinned word lists.
5. Deferred: reusable lookup-byte buffer; first-lookup page warming (`MappedByteBuffer.load`
   is unused — log-only first).
6. Rejected: removing the worker hop (page faults on the UI thread — violates the project's
   own rule); thread consolidation (~100–200 KB against a passing ceiling, adds blocking
   risk); pooling Event/InputTransaction (below measurement noise).

## Battery

The audit found **no battery bug and no structural gap**: no wakelocks, alarms, jobs,
sensors, clipboard listeners, or periodic scheduling anywhere; all timers are gesture-scoped
one-shots; all animations are bounded and cancel on hide; the idle release is doze-safe by
construction. The known bug classes in peer keyboards (FlorisBoard's recomposition drain,
AnySoftKeyboard's "runs in background" reputation) are absent by construction. The gap is
measurement, not code.

Options, ranked:

1. **A battery leg** (sibling to `device-netstats-proof.sh`): Protocol A — hidden-idle drain
   (raise once, hide, `battery unplug`, batterystats reset, ≥120 s, assert wake-lock/sensor/
   alarm counts = 0 and CPU delta ≈ 0; `/proc/<pid>/stat` utime delta as the independent
   counter); Protocol B — active-session CPU per scripted session. Never interleaved with the
   USB-power perf legs.
2. **Stuck-gesture assertion** (hold backspace, release, assert CPU delta ≈ 0 and the 10 s
   deallocate fired) — kills the "repeat never stops" class while healthy.
3. **Doze conformance** folded into Protocol A (force-idle → zero wakeups → normal typing
   after unforce).
4. One-time power-rail probe on the reference device; document the absence so nobody
   re-litigates "why not real µW". Macrobenchmark `PowerMetric.Type.Battery` deferred (coarse
   resolution, high setup cost).
5. Product note: on OLED phones the light panel is real display-power drain — a theme-level
   question, already covered by the theme decisions in `research/ui.md` (no AMOLED variant;
   dynamic color is the lever).

## Build loop (one-developer efficiency)

Measured: a no-change gate re-run costs ~2 s; `lintAnalyzeRelease` 32 s; the JVM test leg
49.5 s of which **three calibration suites are 86%** (the slowest single class sets the fork
floor — more forks buy <15%); the python loop 16.5 s (three files are 76%); the CI
reproducible job runs four serial clean builds. A once-observed 77 s first-invocation-of-the-
day correlates with single-build daemon eviction (unproven; capture daemon state if it
recurs).

Options, ranked:

1. **Split calibration suites out of the default test leg** (`test` ≈ 13–15 s; full set wired
   into CI and `release_check.sh` in the same change — the quality gates must not stop
   running per push/release).
2. **CI reproducible job: 4 clean builds → 2** — the pack-determinism leg reuses one unsigned
   build (pack twice from identical bytes; the byte-compare proof of the build itself is
   unchanged).
3. **Parallelize the python test loop** (`xargs -P`): 16.5 → ~6 s.
4. Within-suite parallelism for the calibration eval loops (keeps the gates in the default
   loop; fixed seeds make it deterministic) — the alternative to option 1 if per-edit gates
   are preferred.
5. Merge the CI build job's four Gradle invocations into one; parallelize release_check's
   non-Gradle gates against the first Gradle leg (never two Gradle runs concurrently).
6. Rejected with reasons recorded: `org.gradle.parallel` (one dominant module), higher
   `maxParallelForks` (the floor is one class), `nonTransitiveRClass`, removing the
   baselineprofile module or dependency verification (both verified zero-cost or
   load-bearing).

## Risks and open questions

- No on-device numbers live in the repo for touch p95/frames/lookup round trip — several
  effects above are inferred from code; the observability options (PSS split, round-trip leg)
  land first precisely to fix that.
- The memory-budget doc and our manifest comment disagree on whether a perceptible budget
  constrains the process in background; the 37.2 implementation isn't publicly readable yet —
  verify on a 37.2 emulator before relying on either reading.
- The pss leg measures with our own SetupActivity as host — conservative; a foreign-host
  variant would isolate the IME's true steady state if tighter budgets are ever wanted.
- Hand-edited profiles vs regeneration discipline needs an operator policy line (verify-in-
  gate is the technical half).
