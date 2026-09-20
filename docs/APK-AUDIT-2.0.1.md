# APK-AUDIT-2.0.1 — artifact audit

Date: 2026-09-21. Object: `dist/tatar-keyboard-2.0.1.apk`.

## Artifact

| | |
|---|---|
| Size | **1 849 555 B** (ceiling 3 145 728 B, headroom 41.2 %) |
| SHA-256 | `53cb4c2709c09fd30e8bf553e36f821f722480bce3da2373289997658a8c02a4` |
| Version | 2.0.1 / versionCode 33 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`; unsigned 1 869 779 B, after zopfli −20 224 B |
| Permissions | exactly `android.permission.VIBRATE` |

**Build determinism note:** the signed size coincides byte-for-byte with 2.0.0
and with both TT-NEXTWORD-FILL mission builds (all 1 849 555 B) — zopfli
determinism makes equal compressed sizes possible, and the SHA-256s all
differ. The unsigned input is 1 869 779 B, byte-size identical to the
mission's Phase-D measurement (the unsigned pack is reproducible per commit).
This audit ran the pipeline once; reproducibility of the unsigned pipeline
remains the DEV-2 CI gate (two `clean assembleRelease` runs compared with
`cmp`).

## What shipped

One user-facing change over 2.0.0 — **TT-NEXTWORD-FILL**
(`docs/TT-NEXTWORD-FILL.md`): after a committed word, the strip cells left
free by next-word predictions and word forms fill with the language's global
top-frequency words (Tatar pool: һәм, белән, да, бу, дә, дип, ул, өчен;
Russian: я, не, в, и, что, ты, на, это — computed from each engine's own
shipped dictionary at engine start). Bigram successors, word forms and the
emoji tail keep priority and are never displaced; the committed word and
already-shown words are never re-offered. Origin: the operator's 2.0.0 bug
report (after accepting `сәләм` the strip offered only `сәләмә`; after
`сәләмә` it went empty). Eval: strip-empty-after-word 33.30 % → 0.0000 % on
the pinned eval set, all other `TtSuggestEvalTest` metrics unchanged.

## Delta to 2.0.0

Archive entries: **248 → 248** — nothing added, nothing removed. Per-entry
comparison against `dist/tatar-keyboard-2.0.0.apk` (CRC32 identity,
uncompressed sizes):

| Entry | 2.0.0 | 2.0.1 | Verdict |
|---|---:|---:|---|
| `classes.dex` | 332 340 | 332 532 | CHANGED (+192 B) — mission code |
| `classes2.dex` | 114 292 | 114 892 | CHANGED (+600 B) — mission code |
| `assets/dexopt/baseline.prof` | 1 136 | 1 136 | regenerated for the new code (+0 B) |
| `AndroidManifest.xml` | 4 664 | 4 664 | version lines only (+0 B) |
| `META-INF/version-control-info.textproto` | 120 | 120 | embedded git HEAD (+0 B) |

**All 13 shipped data assets are byte-identical (CRC32-verified):** both
dictionaries (`tatar_top100k_v1.tdict.zlib`, `russian_top100k_v1.tdict.zlib`),
both bigram tables (`tatar_bigrams_v1.tatbigr.zlib`,
`russian_bigrams_v1.tatbigr.zlib`), the sentence-start table
(`tatar_sentstart_v1.txt`), all three NOTICE files and all five emoji assets
— the fallback is computed from the shipped dictionary at runtime and ships
no new data. `resources.arsc` and every one of the 225 `res/` entries are
byte-identical as well. This is a code-only release. (Same-tree check:
`git diff v2.0.0..HEAD -- app/src/main/assets/` is empty.)

Component totals (uncompressed, from the release_check delta gate): assets
+0, arsc +0, res +0, other +0, dex +792 B; **APK +0 B (+0.0 %)** — the dex
growth fits inside the existing zip slack after zopfli.

## On-device consequence of updating from 2.0.0

**Nothing re-inflates.** No dictionary or bigram-table hash changed and no
asset was added, so the device-side inflated files (named by raw SHA-256)
stay valid and are reused as-is; `baseline.prof` is read from the APK by the
store/installer path and is never inflated. Personal dictionary, settings and
learned words are untouched. Update-with-data-preserved for this exact
content was proven on hardware: the 2026-09-21 device UAT installed the
mission APK with `adb install -r` over the 2.0.0 install on the POCO C71 —
data kept, full scenario table PASS (evidence
`build/device-uat-2026-09-21/`).

## Gates

`scripts/release_check.sh --full dist/tatar-keyboard-2.0.1.apk` —
**13/13 PASS, OVERALL PASS** (the `--full` mode rebuilt the release from
clean first; no fallback to `--quick` needed):

| Check | Result |
|---|---|
| `build.assemble_release` | clean assembleRelease OK |
| `gates.gradle_test` | **1 257 tests in 136 files, 0 failures** (`--rerun-tasks`) |
| `gates.lint_release` | baseline, no new errors (21 tasks executed) |
| `gates.python_tests` | **474 tests in 15 files** |
| `gates.no_internet` | both levels (manifest + aapt2), backup whitelist closed |
| `artifact.size` | 1 849 555 B, headroom 41.2 % |
| `artifact.asset_pins` | 16 values across 4 assets match |
| `artifact.emoji_assets` | 5 files byte-identical to the tree |
| `artifact.permissions` | exactly `[VIBRATE]` |
| `artifact.signature` | `98ca6febfed6…` (release key) |
| `artifact.version` | 2.0.1 / 33 = `app/build.gradle` |
| `artifact.changelog` | `metadata/en-US/changelogs/33.txt` present (348 B; ru-RU 432 B, tt 425 B — all ≤ 500) |
| `artifact.delta` | previous 2.0.0 (vc 32), +0 B (+0.0 %) |

Run separately (not part of `release_check.sh`):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`
(tt 3/0 and ru 2/0 known drift as pinned in `scripts/known_asset_drift.json`).

## Validation

The shipped content was verified on device before the version bump (mission
builds carry 2.0.0/32 by convention; the 2.0.1 candidate differs from the
UAT'd build only in the version strings):

* Device UAT on the POCO C71 (Android 15 Go), 2026-09-21 — the operator's
  exact scenario chain: `сцләм` → `сәләм` (cell 1) → tap → **сәләмә · һәм ·
  белән** → tap һәм → 3 cells → commit сәләмә → **һәм · белән · да** (the
  pinned pure-fallback band); 15 regression rows PASS (bigram priority,
  word-form priority, same-stem boost, emoji tail, sentence start, ru
  fallback `тюлень` → я · не · в, rotation, 193-char paragraph
  byte-identical). Cold start medians 251 / 273 ms against the 400 ms
  budget; crash buffer empty. Evidence: `build/device-uat-2026-09-21/`;
  report `docs/TT-NEXTWORD-FILL.md` ("Device UAT 2026-09-21").
* Emulator fallback evidence of 2026-09-20 (phone was off USB that day):
  `build/device-uat-2026-09-20/emulator-fallback/`, 12 rows PASS — including
  the recorded as-design finding that the two-edit typo `сэлэм` is
  unreachable by the single-edit fuzzy classes.

## Open

* TalkBack by ear, Direct Boot, the MIUI process killer and a real tablet
  remain human-only checks, as before.
* Cold-start / PSS numbers come from the mission UAT at versionCode 32; the
  version bump does not move them (manifest-only delta to the UAT'd build).
* The tt store locale carries changelog notes for versions 27–33 only.
* TT-NEXTWORD-FILL Phase E (independent re-verification by a fresh agent) is
  the orchestrator's item, not blocking the release per the operator's
  decision.
