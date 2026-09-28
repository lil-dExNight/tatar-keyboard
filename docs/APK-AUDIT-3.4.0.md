# APK-AUDIT 3.4.0 (versionCode 41) — 2026-09-28

Artifact: `dist/tatar-keyboard-3.4.0.apk`, **1 804 874 B**, SHA-256
`366a9105a7b9cd9bae0256a49188cd7a7e314202577441c4b9c63f766b569859`. Built by
`scripts/release_pack.sh` (unsigned → zipalign -z → apksigner v2-only),
certificate `98ca6feb…42ad` (release key, single signer), `resources.arsc`
STORED (102 528 B). The pack was run a second time to `dist/.repro-3.4.0.apk`
and `cmp` proved the two byte-identical (same SHA-256); the repro copy was
deleted after the check. `release_check.sh --full` — **OVERALL PASS 17/17**.

## Per-entry comparison with 3.3.0 (CRC32)

184 entries in both archives; 166 are byte-identical; exactly 18 changed; none
added, none removed:

| Entry | Why |
|---|---|
| `AndroidManifest.xml` | versionCode 40 → 41, versionName 3.3.0 → 3.4.0 |
| `classes.dex` (380 624 → 393 988 B), `classes2.dex` (85 448 → 93 760 B) | the wave's code: learned word→emoji store and UI, single IME-switch executor, strip recents path, emoji-index cap raise |
| `resources.arsc` (97 044 → 102 528 B, STORED in both) | new strings for the learned-emoji management UI in three locales, plus shifted resource IDs |
| 11 `res/*.xml` layouts (identical uncompressed sizes, new CRCs) | recompiled against the shifted resource IDs; no layout added or removed |
| `assets/dexopt/baseline.prof` (1 196 → 1 246 B), `assets/dexopt/baseline.profm` | regenerated baseline/startup profiles (3 282 → 3 430 rules) after the generator fix |
| `META-INF/version-control-info.textproto` | commit bookkeeping |

**All 13 shipped data assets are byte-identical to 3.3.0 (CRC32-verified):**
both dictionaries (`tatar_top100k_v1.tdict.zlib` 542 493 B,
`russian_top100k_v1.tdict.zlib` 539 948 B), both bigram tables
(`tatar_bigrams_v1.tatbigr.zlib` 104 028 B, `russian_bigrams_v1.tatbigr.zlib`
63 312 B), both sentence-start tables, all five emoji assets — including
`emoji_suggest_v1.txt` (the 4 096 → 8 192 raise is index headroom; the shipped
table is unchanged at 3 976 records) — and both NOTICE files. On update from
3.3.0 the device **re-inflates NOTHING**: no asset hash changed, and the
personal dictionary, learned pairs, learned emoji pairs and settings are
untouched.

## Run gates (release_check --full)

JVM **1 826 tests / 190 suites / 0 failures**, python **507/16**, lint baseline
clean, no-internet at both levels, asset pins 16/16, emoji assets 5/5, tree
assets 8/8, `artifact.critical_resources` 59/59, `resources.arsc` STORED at a
4-aligned offset, permissions exactly [VIBRATE], signature one signer
(`98ca6feb…42ad`), version 3.4.0/41, changelog `41.txt` in place (360 B),
APK delta vs 3.3.0 **+12 288 B (+0.7 %)**, size headroom 42.6 %.

## Device legs

Ran on the wave's tree (commit `88c1c6d7`, code-identical to the release — the
release commit touches only the version, changelogs and docs), POCO C71,
2026-09-28: `GlideUiDeviceTest` whole class 2/2 (no wedge),
`GlideDeviceInstrumentationTest` 3/3 (decode p95 3.125 ms assert green),
`E3bComputeInstrumentationTest` 2/2 (typo-tatar p95 3.228 ms assert green);
evidence `build/device-uat-2026-09-28/`. Emulator smoke on `tt_suggest_a14`:
22 PASS / 0 FAIL / 1 SKIP including the new `learned-emoji-tt` probe (learned
☀️ overrides the static 🌅 in the tail cell on the third round); evidence
`build/emulator-smoke-emoji-learn/`.
