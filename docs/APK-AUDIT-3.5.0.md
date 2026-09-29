# APK-AUDIT 3.5.0 (versionCode 42) — 2026-09-29

Artifact: `dist/tatar-keyboard-3.5.0.apk`, **1 804 874 B**, SHA-256
`0e4b99f97bc895f57a1657815e752941429817dc734bb9d6988ca2d01e7fab96`. Built by
`scripts/release_pack.sh` (unsigned → zipalign -z → apksigner v2-only),
certificate `98ca6feb…42ad` (release key, single signer), `resources.arsc`
STORED (103 212 B). The pack was run a second time to `dist/.repro-3.5.0.apk`
and `cmp` proved the two byte-identical (same SHA-256); the repro copy was
deleted after the check. `release_check.sh --full` — **OVERALL PASS 19/19**.

## Per-entry comparison with 3.4.0 (CRC32)

184 entries in both archives; 171 are byte-identical; exactly 13 changed; none
added, none removed:

| Entry | Why |
|---|---|
| `AndroidManifest.xml` (4 508 → 4 640 B uncompressed) | versionCode 41 → 42, versionName 3.4.0 → 3.5.0, plus `<profileable android:shell="true"/>` for the Perfetto sections |
| `classes.dex` (393 988 → 389 540 B), `classes2.dex` (93 760 → 95 988 B) | the wave's code: emoji-panel space rework + panel-height setting, mmap bulk dictionary reads, draw-path allocation fixes, hostile-host hardening of the editor connection, de-texted debug tracers, the dead class-#5 recovery machinery deleted |
| `resources.arsc` (102 528 → 103 212 B, STORED in both) | new strings for the emoji-panel-height setting in three locales (plus its managed-restriction entry), shifted resource IDs |
| 5 `res/*.xml` layouts | `Kt.xml` genuinely changed (3 688 → 3 860 B — the emoji panel: the search row out, the 🔍 tab cell in); four recompiled against the shifted resource IDs at identical uncompressed sizes |
| `assets/dexopt/baseline.prof` (1 246 → 1 227 B), `assets/dexopt/baseline.profm` | regenerated baseline/startup profiles (3 430 → 3 431 rules) |
| `META-INF/version-control-info.textproto` | commit bookkeeping — points at the wave HEAD `2b4e0fd6`; the release commit by definition cannot embed its own hash |

**All 13 shipped data assets are byte-identical to 3.4.0 (CRC32-verified):**
both dictionaries (`tatar_top100k_v1.tdict.zlib` 542 493 B,
`russian_top100k_v1.tdict.zlib` 539 948 B), both bigram tables
(`tatar_bigrams_v1.tatbigr.zlib` 104 028 B, `russian_bigrams_v1.tatbigr.zlib`
63 312 B), both sentence-start tables, all five emoji assets and both NOTICE
files. On update from 3.4.0 the device **re-inflates NOTHING**: no asset hash
changed, and the personal dictionary, learned pairs, learned emoji pairs and
settings are untouched.

## Run gates (release_check --full)

JVM **1 927 tests / 202 suites / 0 failures**, python **507/16**, lint baseline
clean, no-internet at both levels, asset pins 16/16, emoji assets 5/5, tree
assets 8/8, `artifact.critical_resources` 59/59, `resources.arsc` STORED at a
4-aligned offset, permissions exactly [VIBRATE], `artifact.exported_surface`
against the golden set (new gate), `artifact.no_secrets` over the tree and the
APK (new gate), signature one signer (`98ca6feb…42ad`), version 3.5.0/42,
changelog `42.txt` in place (335 B), APK delta vs 3.4.0 **0 B (0.0 %)**, size
headroom 42.6 %.

## Device legs

Ran on the wave's tree (commit `2b4e0fd6`, code-identical to the release — the
release commit touches only the version, changelogs and docs), POCO C71,
2026-09-29: three green runs of `scripts/device-perf-ritual.sh` (PSS ceiling
set at 114 000 kB debug scale against a 90 526 kB peak; debug cold start
667.6 ms median), `DrawAllocInstrumentationTest` — **zero own allocations** in
the board/strip draw paths, the Perfetto capture showing all six `TT#`
sections, the netstats no-traffic proof (UID counters rx 0→0 / tx 0→0 over a
scripted mixed session), and the mmap before/after witness (typo path p95
5.188 → 1.723 ms; E3b green on-device at 1.653 ms). Evidence
`build/device-uat-2026-09-29/`.
