# Optimization + security audit plan — 2026-09-29

Basis: three-track research (external optimization practices for offline
Android IMEs, external security-audit methodology for IMEs, internal
coverage/gap analysis of this tree). Each item has a verification clause;
gates are preferred over rituals, fail-closed over observational. Everything
stays inside the hard rules: zero third-party runtime dependencies, no
INTERNET permission, APK ≤ 3 145 728 B, cold start < 400 ms, zero allocations
in the draw loop.

Already covered by the tree (do not redo): R8 full mode + shrinkResources +
optimized resource shrinking with the `artifact.critical_resources` gate (59
reflectively-loaded resources pinned), reproducible builds in CI, baseline +
startup profiles with an emulator-pinned generator, exact-permissions gate
(`[VIBRATE]`), no-INTERNET gate at two levels, backup closed as a whitelist
(cloud + device-transfer), PGP+sha256 dependency verification with a committed
keyring, fail-closed binary validators with corruption-class tests, personal
stores in `noBackupFilesDir` with salted pending hashes, single clipboard read
point (paste key only), editor-cache hygiene with password-field gating,
host p95 pins, device p95 asserts (E3b, glide), `directBootAware` with
unlock-gated credential storage (a deliberate feature — stays, documented in
the threat model).

## Part O — optimization

**O1. Living performance-budget doc.** `docs/PERF-BUDGETS.md`: every budget
with its current measured value, status (gated / manual-ritual / never
measured), and the gate or ritual that guards it (cold start < 400 ms; frame
p50/p90; decode/typo p95; PSS ceiling; warm show; touch latency; zero-alloc
draw loop; APK size). Records which host timing pins are CI-safe and why (the
G2 glide gate is CI-skipped since 2026-09-28 — runner noise; the others carry
>100× headroom).
Verification: the doc exists, every row names a gate or a ritual, README index
line. Effort: ~1 h.

**2026-09-29:** done. `docs/PERF-BUDGETS.md` landed — the living table
carries, per metric: the budget, the latest measured value with date and
device, the status (gated / scripted-ritual / never-measured), and the gate or
ritual guarding it. The PSS ceiling row is set from the same day's
re-measurement (114 000 kB, debug scale — O2's numbers), the CI-safety split
of the host timing pins is recorded (the G2 glide gate CI-skipped since
2026-09-28 — runner noise; the rest carry >100× headroom), and the README
index line lands with the wave.

**O2. PSS ceiling + scripted device measurement leg.** The BRIEF promised a
PSS ceiling after the first valid measurement (46.5 MB peak, 1.9.12) and never
set it. Set the ceiling from the 2026-09-29 re-measurement; add
`scripts/device-perf-ritual.sh` running the fixed scenario on the POCO C71:
cold start (the existing /proc-stat method), PSS after {keyboard open, 50 words
typed tt+ru, emoji panel opened+closed, 30 s idle-release}, frame stats
(`dumpsys gfxinfo` — the O2-2 protocol: 32-event script, 120 frames ×3), with
machine-readable RESULT lines. Wire the script into `docs/PUBLISH-CHECKLIST.md`
where the cold-start checkbox already exists.
Verification: run on the POCO, numbers land in the doc; ceiling set with the
recorded method. Effort: ~2 h.

**2026-09-29:** device leg done. `scripts/device-perf-ritual.sh` landed and ran
end-to-end three times green on the POCO (exit 0, `RESULT|…` lines; evidence
`build/device-uat-2026-09-29/perf/` + the pre-wave A/B in `perf-base/`). The
PSS ceiling is set in `docs/PERF-BUDGETS.md` (114 000 kB, debug scale);
checklist rows wired. Two method findings are recorded in the script header and
the budget doc: `run-as kill -9` replaces force-stop for cold-start legs
(`ime set` alone starts the IME process and would poison the timestamp), and
framestats columns must be resolved by header name (Android 15 moved
FrameCompleted to column 17).

**O3. Zero-alloc gate for the board draw loop.** `KeyboardView.onDraw` is the
hottest loop and the only one without an allocation pin. Add an
instrumentation probe (androidTest, debug builds) that wraps a scripted
key-press burst with `Debug.startAllocCounting()`/`getGlobalAllocCount()` and
asserts zero deltas in the draw path, alongside the existing p95 asserts.
Verification: probe green on the POCO; a deliberate `Rect()` in onDraw fails
it (negative test, reverted). Effort: ~2 h.

**2026-09-29:** done, green on the POCO. `DrawAllocInstrumentationTest`
(androidTest, legacy runner) replays the press → invalidate → redraw sequence
synchronously on the live IME views under per-thread allocation counting:
strip redraws, blits and same-state redraws measure exactly ZERO and assert at
zero; the full board cycle measured 18 allocations over 9 draws, attributed by
the step breakdown to 2 per StateListDrawable state toggle on the shared key
background (reproduced on a bare drawable with no view — the platform's state
resolution, provoked by a semantically required state change), so the board
window asserts the documented toggle-floor bound while every per-frame path
our code controls stays at zero. Negative test run: a planted `Rect()` in
`KeyboardView.onDraw` failed the gate, then reverted.

**O4. Dirty-rect + text-measure audit of the three Canvas views.**
`KeyboardView`, `SuggestionStripView`, `EmojiPanelView`: verify presses
invalidate only the touched rect (`invalidate(Rect)` + `quickReject`), and
label widths/`measureText` results are cached at load, not measured per frame.
Fix what's missing.
Verification: frame p50 on the POCO not worse than the 6.8 ms O2-2 record
(same protocol); unit/contract pins for any changed invalidation logic.
Effort: ~2–3 h.

**2026-09-29:** host leg done; the device p50 leg stays with the wave-3 POCO
session. Verdicts: `KeyboardView` — key presses already invalidated the touched
rect (`invalidateKey` → `invalidate(x, y, …)`, fed by `MainKeyboardView`
press/release), the offscreen buffer redraws only invalidated keys and the
frame is one clipped `drawBitmap`; FIXED the one remaining per-press-frame
allocation — the dirty-key walk iterated a `HashSet`, allocating an iterator
on every press frame (now an `ArrayList` walked by index, duplicate-guarded,
same value-equality semantics since `Key` overrides `equals`/`hashCode`).
Label geometry is cache-backed (`TypefaceUtils` static caches); the only
uncached width read is gated behind `needsAutoXScale()` (enter/phone keys,
per press of those keys, not per frame). `SuggestionStripView` — invalidation
is view-scoped and the view IS the 44dp band (per-cell rects would re-record
the same display list under HW accel; press-move invalidation is already
state-change-gated); FIXED the per-frame `emphasisTextPaint.measureText` of
the autocorrect-preview underline — measured once in
`rebuildDisplaySuggestions()` into `emphasisTextWidthPx` alongside the
ellipsize it must agree with. `EmojiPanelView`/`EmojiPanelDrawing` — CLEAN:
full-view invalidate is correct for a scrolling surface, the content loop is
viewport-bounded, fling frames ride `postInvalidateOnAnimation()`, the one
text measurement (`backWidthPx`) happens once at construction, all font
metrics land in `onSizeChanged`. Pins: new
`KeyboardViewDrawLoopContractTest` (6 tests), two new tests in each of
`EmojiPanelSourceContractTest` and `SuggestionStripSourceContractTest` (the
latter's emphasis pin updated to the cached width).

**2026-09-29 (device leg):** the wave's two draw-loop fixes are frame-neutral
on hardware. Ritual leg C (O2-2 protocol, debug build): wave p50 8.7–9.4 ms
across 3×120 frames vs pre-wave base p50 9.0/9.1/9.3 the same morning — no
regression from either fix. The numeric offset from the 6.8 ms O2-2 record is
the debug-build class (the record is a release-build number); a release-scale
frame pass of the current tree stays open — it needs the release APK with
suggestions on, and the opt-in flow is not automated yet.

**O5. Trace instrumentation (dev-time measurement spine).** `android.os.Trace`
sections (platform API, zero-dep) around: process start → `onCreateInputView`,
keyboard load, suggestion lookup, emoji panel open. `<profileable
android:shell="true"/>` in the release manifest. Bump dev-time macrobenchmark
to ≥ 1.5.0. This makes the existing p95/cold-start numbers explainable via
Perfetto and readies `TraceSectionMetric` assertions.
Verification: a Perfetto capture on the POCO shows the sections; lint/gates
green; no runtime deps added (manifest attribute is framework-side).
Effort: ~2 h.

**2026-09-29 (device leg):** Perfetto capture on the POCO shows all six
sections — `TT#onCreate` (94.5 ms), `TT#createInputView` (50.1 ms),
`TT#loadKeyboard` (×4), `TT#emojiPanel` (5.6 ms), `TT#emojiPanelView` (5.4 ms),
and the async `TT#suggestLookup` (106 slices, 7.8–58.8 ms round trips) — via
`linux.ftrace` + `atrace_apps`, converted with trace_processor.
Evidence: `build/device-uat-2026-09-29/perfetto/` (trace, config, capture
script, `sections.txt`). Gotcha recorded in the capture script: the typing leg
must run on a tt/ru field — the Chrome omnibox is a URL field and never issues
lookups, and the en layout has no dictionary.

**O6. InputConnection binder audit.** Every IC call is a binder transaction;
the platform docs name binder stalls a top UI-thread stall source. Audit
`InputLogic`/`RichInputConnection` call sites: batch multi-step commits under
`beginBatchEdit/endBatchEdit`, no IC calls from draw code, EditorInfo-derived
state cached. One `adb shell am trace-ipc` session as evidence.
Verification: findings recorded; any batching gaps fixed + pinned; the
trace-ipc transcript archived under `build/`. Effort: ~1–2 h.

**2026-09-29:** done, verdict CLEAN — no batching or staleness gap found (the
2026-09-25 F-wave had closed them all); the deliverable is the inventory
proof. Every IC call kind (eleven) is count-anchored in the new
`InputConnectionBinderContractTest` (11 pins) — a new call site, a new batch,
or an IC reference from the draw layer goes red. Full report with the
per-question verdicts: `docs/IC-BINDER-AUDIT-2026-09-29.md`. The one-shot
`am trace-ipc` transcript leg did not run; the count-anchored pins carry the
guarantee continuously instead of for one captured session.

**O7. Dictionary mmap experiment (decision-gated).** The dictionaries inflate
to device files on first run; `FileChannel.map()` on the inflated file gives
file-backed paging (clean pages evicted first under pressure, off the
anonymous heap). Prototype for the schema-2 dictionary reader behind the
existing p95 gates; ship only if: cold read p95 does not regress > 5 % on the
POCO AND steady-state PSS improves measurably. Otherwise record the numbers
and reject, like previous measured rejections. (`noCompress` is NOT needed —
the runtime reads the inflated file, not the APK asset.)
Verification: the experiment doc with both numbers either way; gates green if
shipped. Effort: ~3–4 h.

**2026-09-29:** verdict — ALREADY SHIPPED, validated with numbers; the heap
alternative is the rejected arm. The item's premise was inverted in the tree
as found: the schema-2 reader has never held a heap copy in production —
`MappedDictionaryEngine.FILE_MAPPER` has mapped the inflated device file
read-only since 2026-07-23 (commit 7f22698e), the `DictionaryMapper` seam has
admitted the alternative from the same day, and
`MappedDictionaryEngineTest.repeatedReadOnlyMmapLifecycleDoesNotRetainFileDescriptorsOrLeases`
pins the mapper's `MappedByteBuffer` + read-only types. The experiment
therefore ran in its decision direction — shipped mmap vs the heap
`ByteBuffer.wrap(readBytes())` arm — with zero production diff. Read path:
zlib asset → `TdictValidator.inflateAsset` (one streaming pass, pinned
SHA-256) → versioned file in the device-protected store →
`FileChannel.map(READ_ONLY)` once per engine start → per-keystroke absolute
`ByteBuffer.get` reads off the mapping (block index at header+72, front-coded
K=8 blocks after; per-index heap residency is ~5 KB of scratch, the
dictionary bytes never touch the heap). Host numbers
(`DictionaryIoStrategyCalibrationTest`, 2 000 samples after 500 warmup,
TATAR policy + neighbour table): review prefixes p95 heap 0.124 ms vs mmap
0.017 ms, typo probes p95 0.088 vs 0.087 ms, per-lookup allocation exactly
equal (622 B both arms, review workload), cold-load medians on a warm page
cache acquire 0.121 vs 0.020 ms and the `open()` structural pass 4.661 vs
4.699 ms (~+0.8 %, inside the ±5 % clause); lookup results byte-identical
over the 22 review prefixes + 5 typo probes, `containsWordCold`, and the full
`forEachWordCold` walk. The heap arm's cost is analytic: +1 276 289 B of
anonymous heap for the Tatar dictionary (+1 151 323 B more with Russian
active) that the shipped mapping keeps in evictable file-backed pages —
~2.4 MB against the 46.5 MB peak-PSS record, the measurable win the gate asks
for. Device leg (cold-read witness + on-device PSS split) is prepared as
`app/src/androidTest/.../engine/DictionaryIoStrategyInstrumentationTest.kt`
(compile-verified, both arms held to the E3b 5.0 ms fail-closed bound; run
commands in its KDoc) and stays with the wave-3 POCO session — the POCO was
attached with no active instrumentation, but session ownership could not be
ruled out and the decision does not wait on it. Note for that session: E3b's
recorded device gates were measured on a HEAP buffer, so the probe is also
the first device measurement of the production read path.

**2026-09-29 (device witness + fix):** the wave-3 POCO leg ran and caught the
shipped arm OVER its bound — mmap/tatar-typo p95 **5.188 ms** vs the 5.0 ms
fail-closed gate (heap arm 3.559 ms). The deficit was per-byte absolute
`MappedByteBuffer.get`, not page faults (`open()`'s structural pass pre-touches
every page). Fix in `TdictPrefixIndex.kt`: the active front-coding block is
bulk-fetched with ONE relative get off a private duplicate view into two
worker-confined scratches (≤ 1 107 B per block) and parsed at array speed; the
mapping and its evictable file-backed residency are untouched. After:
mmap/tatar-typo p95 **1.723 ms** — mmap ≤ heap on every arm (heap 1.860 ms;
review arms 0.116–0.241 vs 0.106–0.225 ms), host p95 0.017 → 0.009 ms,
per-lookup allocation unchanged (622 B), lookup results byte-identical, E3b
green on-device at 1.653 ms. Decision clause satisfied: mmap stays shipped,
the heap arm stays rejected. Evidence:
`build/device-uat-2026-09-29/mmap-witness/` (before/after SUMMARY.md).

**O8. `<memory-budget>` manifest probe (API 37.2, toolchain permitting).**
Android 17 QPR2's `<memory-budget>` names an active IME as the canonical
perceptible-state example. Try it under the current compileSdk; if aapt
rejects the element, defer with a dated note (unknown-element tolerance is
platform-side, build-side must accept it first). Numbers come from O2's PSS
measurement.
Verification: builds + installs on the POCO (API 35/36 ignores it) and the
element is present in the merged manifest, OR the deferral note with the aapt
error verbatim. Effort: ~0.5 h.

**2026-09-29:** deferred-with-error. Probe under the pinned toolchain (AGP
9.2.1 with aapt2 `9.2.1-15009934`, build-tools 37.0.0, compileSdk 37 /
platform android-37.0): `./gradlew :app:assembleDebug` fails at
`:app:processDebugResources` with, verbatim,
`ERROR: /home/tarchok/Projects/tatar-keyboard/app/src/main/AndroidManifest.xml:32:9-45: AAPT: error: unexpected element <memory-budget> found in <manifest><application>.`
Syntax tried was taken from the canonical doc
(https://developer.android.com/topic/performance/memory/app-memory-budgets):
`<memory-budget android:maxMb="48" />` plus `android:state="perceptible"` /
`"background"` clauses under `<application>` (full attribute schema confirmed
in the android-37.2 platform's `attrs_manifest.xml`:
`AndroidManifestMemoryBudget` = maxMb/state/feature/
additionalBytesPerDisplayPixel/additionalMbPerDensity). Isolation tests with a
minimal manifest show the element whitelist is baked into the aapt2 binary,
not the linked platform: build-tools-37.0.0 aapt2 rejects the element even
against the android-37.2 android.jar, while the aapt2 artifacts of AGP 9.3.3
(`9.3.3-15703166` — the same aapt2 build ships in every stable AGP 9.3.x) and
9.4.1 (`9.4.1-15978811`) REJECT only the attributes on android-37.0
(`attribute android:maxMb not found`) and ACCEPT the full element against
android-37.2 (link exit 0; `dump xmltree` shows `E: memory-budget` with
`maxMb` attr id 0x010106be in the compiled manifest).
`platforms;android-37.2` is already downloadable from the SDK repository; the
newest stable AGP today is 9.4.1 (newest build-tools still 37.0.0). Unblock
condition: AGP ≥ 9.3.0 AND compileSdk on platform android-37.2 — a project
toolchain decision, not this probe. Manifest fully reverted (git-clean);
debug build green after revert; device leg not run (nothing to install).
This deferral note is the item's **final status**; the unblock condition is
parked with the operator as a toolchain decision.

## Part S — security audit

**S1. Threat model + standing risk register.** `docs/THREAT-MODEL.md`
(English): assets in sensitivity order (keystroke stream; learned vocabulary;
clipboard; SurroundingText/ExtractedText; the offline claim itself), trust
boundaries (host apps over InputConnection, lock screen/direct boot, backup
channels, overlays, accessibility), attacker classes, the deliberate
`directBootAware` posture with its reasoning, OWASP MASVS group mapping
(STORAGE/PLATFORM/CODE/PRIVACY high; NETWORK = prove-absent; RESILIENCE via
reproducibility), and the aggregated residual-risk register from all four
prior audits (single living table). Include the incident-record rationale
(Citizen Lab 2024, CVE-2015-4641/4642 ancestry, ai.type/GO Keyboard/Kika
lessons) as the "why these controls" section.
Verification: the doc exists; the register covers every residual risk from
`docs/SECURITY-AUDIT-2026-09-25.md`, `docs/AUDIT-2026-09-24.md`,
`docs/FINAL-AUDIT-2026-09-02.md`; README index line. Effort: ~2 h.

**2026-09-29:** done. `docs/THREAT-MODEL.md` landed (canonical English, 9
sections): the scope premise (an offline IME inverts the audit — NETWORK
collapses to prove-absent), the assets in sensitivity order, trust boundaries,
attacker classes, the controls map, the deliberate postures (`directBootAware`
kept, with its reasoning), the OWASP MASVS group mapping, the standing
residual-risk register aggregating the prior audits (31 rows), and the
incident record (Citizen Lab 2024, CVE-2015-4641/4642 ancestry,
ai.type/GO Keyboard/Kika) as the "why these controls" section.

**S2. Exported-surface gate.** `release_check.sh` gains
`artifact.exported_surface`: `aapt2 dump xmltree --manifest` (or
`dump badging`) on the APK; the exported component + intent-filter set must
equal the golden set (IME service with BIND_INPUT_METHOD, SetupActivity
launcher, SettingsActivity); any drift fails.
Verification: positive run on the packed APK; negative run with a hand-added
exported receiver fails. Effort: ~1 h.

**2026-09-29:** done. `release_check.sh` gained `artifact.exported_surface`:
`aapt2 dump xmltree` on the APK; the exported component + intent-filter set
must equal the golden set in BOTH directions (any unexpected exported
component fails, any missing expected one fails), and the IME service is
pinned `exported="false"` behind BIND_INPUT_METHOD. Green in the wave's final
`release_check.sh --quick` 12/12.

**S3. Log-safety gate.** Source-contract test (the project's idiom): no
`Log.` call in the input-pipeline packages (`keyboard/`, `latin/suggestions/`,
`latin/dictionary/`, `latin/emoji/`) may reference composing/committed text
variables — implemented as a package-scan pinning the allowed log call sites
(grep-level, fail-closed on new sites so each addition is a conscious review).
Verification: red when a `Log.d(..., word)`-style line is planted, green after
revert; suite green. Effort: ~1 h.

**2026-09-29:** done. `LogSafetySourceContractTest` (4 tests) package-scans the
input-pipeline sources and pins the reviewed `Log.` call-site set fail-closed —
a new site, or any statement mentioning a text-carrying name, goes red; the
debug flags guarding the pipeline logs are pinned disabled. The debug tracers
in `PointerTracker`/`KeyboardState` are de-texted: key labels and
`Constants.printableCode` out of the log lines, coordinates and
functional-key booleans stay.

**S4. Secrets scan gate.** `release_check.sh` gains `artifact.no_secrets`:
the repo tree and the APK are scanned for the known secret filenames
(`*.jks`, `keystore.properties`, key-material PEM blocks, token patterns);
zero hits outside the gitignored paths. Cheap, in the same fail-closed idiom.
Verification: planted dummy `*.jks` fails the gate; clean tree passes.
Effort: ~1 h.

**2026-09-29:** done. `release_check.sh` gained `artifact.no_secrets`: the
tracked repo tree (`git ls-files`, so the gitignored local keystore never
self-trips) and the APK entries are scanned for the known secret filenames
(`*.jks`, `*.keystore`, `keystore.properties`, `*.pem`, `*.p12`), private-key
PEM blocks and token patterns — a hit fails the pack. Green in the final
`--quick` 12/12.

**S5. Parser fuzzing (seeded, zero-dep).** JVM tests running deterministic
seeded mutation loops over all five binary readers (`TdictValidator`,
`TatBigrValidator`, `TpersValidator`, `TpersbValidator`, `TpersemValidator`):
bit flips, truncations, size-field inflation, header corruption — properties:
never an uncaught non-validation exception, never an allocation beyond the
declared caps, always fail-closed (quarantine/empty). Fixed seeds, bounded
iterations, part of the standard `./gradlew test` run.
Verification: suite green; any crash found becomes a pinned regression test
before the fix lands. Effort: ~3–4 h.

**2026-09-29:** done. `SeededFuzzHarness` (stdlib `java.util.Random` only — a
seed plus an iteration index reproduces the exact failing byte image) plus the
five suites `TdictValidatorFuzzTest`, `TatBigrValidatorFuzzTest`,
`TpersValidatorFuzzTest`, `TpersbValidatorFuzzTest`,
`TpersemValidatorFuzzTest`: 32 tests, **61 500 seeded iterations** across bit
flips, truncations, size-field inflation and garbage. Asserted on every input:
only the format's own validation exception (never an uncaught throwable,
never an OOM), allocations inside the declared caps, fail-closed. Zero
validator defects found; the suites run in the standard `./gradlew test`.

**S6. EditorInfo privacy matrix.** Explicit JVM matrix tests: {password,
visible-password, web-password, TYPE_NULL, IME_FLAG_NO_PERSONALIZED_LEARNING,
normal} × {keystroke learning, pair learning, emoji learning, editor cache}
assert zero writes to personal stores and no suggestion persistence for the
protected variants. Extends (does not duplicate) the existing
`PersonalLearningGates`/`EditorTextCachePrivacy` pins — first map what is
already covered, fill only the holes.
Verification: matrix green; the coverage map recorded in the test's KDoc.
Effort: ~2 h.

**2026-09-29:** done. `EditorInfoPrivacyMatrixTest` (12 tests) runs the
explicit matrix — password, visible-password, web-password, TYPE_NULL,
IME_FLAG_NO_PERSONALIZED_LEARNING and the normal control row against word
learning, pair learning, emoji learning, recents, strip population and cache
re-read: every protected variant writes nothing and re-reads nothing, and the
control row opens every column so the matrix cannot be vacuously green. No
behavioral hole found; the coverage map against the pre-existing
`PersonalLearningGates`/`EditorTextCachePrivacy` pins is in the test's KDoc.

**S7. Runtime no-traffic demonstration.** The offline claim currently rests on
manifest-level proof (strong but static). Add the dynamic artifact: scripted
typing session on the POCO with before/after `dumpsys netstats detail` UID
counters for the app — assert zero rx/tx delta; archive the transcript under
`build/`. Documented in `docs/THREAT-MODEL.md` as the NETWORK-prove-absent
evidence.
Verification: transcript shows 0 bytes delta; the run is repeatable via a
script. Effort: ~1 h.

**2026-09-29:** landed. POCO, debug build, uid 10248: `dumpsys netstats detail`
before/after a scripted ~2-minute mixed session (tt/ru typing, suggestion
accepts, emoji panel + search) — rx 0→0, tx 0→0, no traffic buckets for the
UID in either snapshot (`build/device-uat-2026-09-29/netstats/`). Evidence
level: netstats UID counters (`/proc/net/xt_qtaguid/stats` no longer exists on
this kernel; the BPF `mUidCounterSetMap` entry that may appear for a
foreground UID carries no byte counters and is not traffic).

**2026-09-29 (addendum):** the repeatability gap is closed — the proof is now
committed as `scripts/device-netstats-proof.sh` (the wiped manual
`run-session.sh` replaced); re-run on the POCO (uid 10248, 120 s session):
`RESULT|PASS|netstats.zero-traffic` — rx 0→0, tx 0→0, zero buckets on both
sides, corroborated by the eBPF `mAppUidStatsMap` delta 0; fresh evidence in
`build/device-uat-2026-09-29/netstats/`.

**S8. Hostile-host robustness tests.** The host app owns the InputConnection;
audit + test the hostile shapes: oversized `getTextBeforeCursor` counts,
throwing IC methods, null/malformed SurroundingText, rapid
start/finish-input churn. JVM-level with fakes where the harness allows;
extend the F-wave validation guards if a hole is found.
Verification: new tests green; holes fixed + pinned, or "no hole" recorded
per shape. Effort: ~2 h.

**2026-09-29:** done — three real holes found and fixed, the rest recorded
clean per shape. `HostileHostRobustnessTest` (12 tests) drives the real
`RichInputConnection` through the Android-free seams plus a fake
`InputConnection` injected into `mIC`; `HostileHostSuggestionChurnTest`
(2 tests) covers the suggestion side of the churn shape on the real
`SuggestionsController`; the structural half is pinned in
`RichInputConnectionRobustnessContractTest` (+2 pins) and
`BatchEditPairingContractTest` (the F5 "no catch" pin re-anchored to the
new doctrine). HOLED+FIXED: (1) host payloads past the 1 024-char window —
`getTextBeforeCursor`/`getSurroundingText` answers and the EditorInfo
parcel; the requested count is only a hint — were stored verbatim,
re-inflating the F3-bounded cache to binder-parcel size and making every
later append a full-length copy; the reload writers now keep the window
tail (before-cursor) / head (after-cursor), the selection stays verbatim
by design (span consistency), and the text-start provenance is measured
on the host's answer. (2) An editor call throwing RuntimeException across
the binder propagated uncaught and killed the IME process; all eleven
editor-call sites in `RichInputConnection` now degrade inside
`catch (final RuntimeException e)` — silent per the F6 idiom, the cache
follows the intended edit, the F8/F10 reload re-syncs it, and the F5
batch pairing is untouched (every end still sits in its finally).
InputLogic needed no change: the wrapper is its only path to the editor
(O6). (3) Host-reported negative selection indexes survived
`updateSelection`'s inversion-only normalization — (5, −1) became a
phantom selection `deleteSelectedText` would have edited from; any
negative component now fails closed to INVALID_CURSOR_POSITION. CLEAN:
null/malformed SurroundingText (F4 held; the null-text and Int.MAX_VALUE
pins were added), the 1 MB payload handling (bounded post-fix; substring
copies, no retention of the giant answer), rapid start/finish churn (the
controller's session stamps held under a 4 000-step interleaved storm —
a request issued before finishInput never paints after it, and a fresh
session works end to end). Full JVM suite green (1 927 tests, +16).

**S9. SECURITY.md + re-audit ritual.** Root `SECURITY.md` (English): supported
versions, reporting channel (GitHub private vulnerability reporting),
severity language for a keyboard (confirmed keystroke egress = critical,
embargo + point release), the re-audit cadence (every release: gate suite +
delta review of input pipeline/stores/parsers/manifest/build changes; full
pass per calendar half-year or on Android-platform behavior changes;
event-driven on LatinIME-lineage CVEs from the Android Security Bulletins).
Verification: file exists; the ritual matches what `release_check.sh` actually
runs. Effort: ~1 h.

**2026-09-29:** done. Root `SECURITY.md` landed: supported-versions policy (the
latest release only — the fix ships as the next point release), reporting
through GitHub private vulnerability reporting (never a public issue),
keyboard-calibrated severities (confirmed keystroke egress = critical: embargo
+ immediate point release), and the re-audit cadence — every release runs the
gate suite plus a delta review of the input pipeline/stores/parsers/
manifest/build, a full pass per calendar half-year or on platform behavior
changes, event-driven on LatinIME-lineage CVEs from the Android Security
Bulletins.

## Sequencing

Wave 1 (parallel, disjoint files): S1, S2, S3, S4, O1, O8-probe.
Wave 2 (parallel): S5, S6, O3, O4, O6.
Wave 3 (device legs together on the POCO): O2 (sets the ceiling S-side needs
nowhere, but O8's numbers), S7, O5's Perfetto capture; then O8 decision.
Wave 4: O7 experiment (decision-gated); S9; final gates + docs.

## Explicitly not doing (recorded with reasons)

- zstd/brotli for dictionary payloads — no SDK decoder; a Java decoder
  violates the zero-runtime-dep rule (research 1.6).
- Jazzer/MobSF/JankStats/LeakCanary as runtime or required tooling — Jazzer is
  dev-time-only and replaced here by seeded stdlib fuzzing (S5); MobSF optional
  one-shot later; JankStats/LeakCanary are runtime deps.
- `<provider>`-free CI assert — zero-value: the manifest gate (S2) covers the
  component set strictly stronger.
- Dropping `directBootAware` — deliberate product feature with unlock gating;
  documented in the threat model instead.
- Firebase Test Lab perf CI — no device farm; hard perf gates stay on the
  pinned POCO ritual, matching the documented best practice of
  real-device-only benchmarking.
