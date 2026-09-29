# APK-AUDIT 3.6.0 (versionCode 43) — 2026-09-29

Artifact: `dist/tatar-keyboard-3.6.0.apk`, **1 804 874 B**, SHA-256
`df32bc31ec83f8e2cecc85c2b0b87042987ed2f7c441055cd89b6659dba132b1`. Built by
`scripts/release_pack.sh` (unsigned → zipalign -z → apksigner v2-only),
certificate `98ca6feb…42ad` (release key, single signer), `resources.arsc`
STORED (103 212 B, byte-identical to 3.5.0). The pack was run a second time to
`dist/.repro-3.6.0.apk` and `cmp` proved the two byte-identical (same
SHA-256); the repro copy was deleted after the check. `release_check.sh --full`
— **OVERALL PASS 19/19**.

## Per-entry comparison with 3.5.0 (CRC32)

184 entries in both archives; 178 are byte-identical; exactly 6 changed; none
added, none removed:

| Entry | Why |
|---|---|
| `AndroidManifest.xml` (4 640 → 4 640 B uncompressed) | versionCode 42 → 43, versionName 3.5.0 → 3.6.0 — equal-length strings, so the compiled manifest keeps its size |
| `classes.dex` (389 540 → 389 348 B), `classes2.dex` (95 988 → 94 980 B) | the wave's code, and only the wave's code: the four-cell strip machinery reverted to three cells (state, view, seams, both engines' MAX_RESULTS) and the live mid-gesture glide preview removed (lift-only suggestions) |
| `assets/dexopt/baseline.prof` (1 227 → 1 234 B), `assets/dexopt/baseline.profm` | baseline/startup profiles regenerated on the wave tree (3 431 → 3 451 rules; the run also picked up the LookupTracer rules the earlier hand-prune had missed) |
| `META-INF/version-control-info.textproto` | commit bookkeeping — points at the wave HEAD `22b226ef`; the release commit by definition cannot embed its own hash |

No `res/*.xml` layout changed and `resources.arsc` is byte-identical — the
strip cell count and the glide preview live in code, not in resources, so the
revert touched no resource at all.

**All 13 shipped data assets are byte-identical to 3.5.0 (CRC32-verified):**
both dictionaries (`tatar_top100k_v1.tdict.zlib` 542 493 B,
`russian_top100k_v1.tdict.zlib` 539 948 B), both bigram tables
(`tatar_bigrams_v1.tatbigr.zlib` 104 028 B — still packed at K = 4, its fourth
successor now simply unused headroom, `russian_bigrams_v1.tatbigr.zlib`
63 312 B), both sentence-start tables, all five emoji assets and both NOTICE
files. On update from 3.5.0 the device **re-inflates NOTHING**: no asset hash
changed, and the personal dictionary, learned pairs, learned emoji pairs and
settings are untouched.

## Run gates (release_check --full)

JVM **1 920 tests / 202 suites / 0 failures**, python **507/16**, lint baseline
clean, no-internet at both levels, asset pins 16/16, emoji assets 5/5, tree
assets 8/8, `artifact.critical_resources` 59/59, `resources.arsc` STORED at a
4-aligned offset (1 684 548), permissions exactly [VIBRATE],
`artifact.exported_surface` against the golden set, `artifact.no_secrets` over
the tree (1 527 tracked files) and the APK (184 entries) — 0 findings,
signature one signer (`98ca6feb…42ad`), version 3.6.0/43, changelog `43.txt`
in place (232 B), APK delta vs 3.5.0 **0 B (0.0 %)** — uncompressed content
−1 193 B (dex −1 200, assets +7 from the regenerated profile, arsc/res/other
+0), size headroom 42.6 %.

## Device legs

Ran on the wave's tree (commit `22b226ef`, code-identical to the release — the
release commit touches only the version, changelogs and docs): emulator smoke
**23 PASS / 0 FAIL / 1 SKIP** with the re-derived three-cell probes (the
pixel-delta leg SKIPped for want of ImageMagick on the host); POCO C71
instrumentation green — GlideUiDeviceTest 2/2, DrawAllocInstrumentationTest
3/3 (zero own allocations in the draw paths), GlidePointerDeviceTest 4/4; the
pixel-level proof of the preview removal — the strip region byte-identical
mid-gesture — is in `build/device-uat-2026-09-29/revert/interactive/`.
