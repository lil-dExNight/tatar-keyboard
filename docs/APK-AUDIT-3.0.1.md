# APK-AUDIT-3.0.1 — artifact audit

Date: 2026-09-24. Object: `dist/tatar-keyboard-3.0.1.apk`.

**3.0.1 supersedes 3.0.0.** The `v3.0.0` tag (`b8de3916`) exists, but no
GitHub Release was ever created for it and none will be — the audit of the
3.0.0 tree found a release blocker (H1, the in-app privacy/license links
pointed at the old repository owner), and the fix wave ships as 3.0.1. The
tag stays as history, exactly like 1.9.13/1.9.14 (both shipped inside
1.9.15). Everything 3.0.0 carries is inside 3.0.1.

## Artifact

| | |
|---|---|
| Size | **1 842 468 B** (ceiling 3 145 728 B, headroom 41.4 %) |
| SHA-256 | `4ce547973bc60cd743abfcbf126841e481ee9999ee5cf7da773de952a8f430de` |
| Version | 3.0.1 / versionCode 35 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`; unsigned 1 860 968 B, after zopfli −18 500 B |
| Permissions | exactly `android.permission.VIBRATE` |

**Build determinism note:** the signed size coincides byte-for-byte with
3.0.0 (both 1 842 468 B) — zopfli determinism makes equal compressed sizes
possible; the SHA-256s differ. The F1 wave recorded an unsigned 1 860 976 B
on the same code content; this candidate's unsigned input is 1 860 968 B
(−8 B — zip-level slack, the contents are pin-verified below). One pipeline
run for this candidate; reproducibility of the unsigned pipeline remains the
DEV-2 CI gate.

## What shipped

The 3.0.0 post-release audit fix wave (`docs/AUDIT-2026-09-24.md` — the
findings table; `docs/AUDIT-2026-09-24-FIXES.md` — the code half), landed as
two parallel waves (F1 code + DOCS/DATA/STRINGS), uncommitted on top of the
v3.0.0 tag at release-prep time:

* **H1 (release blocker):** the in-app privacy/license links pointed at the
  old repository owner (`dExNight`); they now open `lil-dExNight`, and the
  OpenSubtitles link is https.
* **F1/F2 (both MAJOR):** the autocorrect-undo path gained the missing
  `isConnected` guard; accepting a saved personal word from the strip now
  actually updates its usage counters (the event had no production caller).
* **F3:** the baseline profile covers glide typing (3 071 → 3 240 rules).
* Correctness minors: fresh key geometry before a language switch is
  announced; pair boundary re-armed after an autocorrection; the onTap
  branch guard; per-language quarantine notices; the duplicate pending-file
  read removed; MDM restriction titles unswapped; the recents medium read
  gained a size cap; the three LongFloatConversion warnings cast away.
* Strings/docs accuracy: tt `%1$d-се` orthography, the ru plural on the
  layouts row, both NOTICE files brought up to the shipped table shape
  (13 154 heads, K = 3), `PRIVACY.md` 1.6, emoji search/skin pins added to
  the python suites.

## Delta to 3.0.0

Archive entries: **194 → 194** — nothing added, nothing removed; 11 entries
changed. Per-entry comparison against `dist/tatar-keyboard-3.0.0.apk`
(CRC32 identity, uncompressed sizes):

| Entry | 3.0.0 | 3.0.1 | Verdict |
|---|---:|---:|---|
| `classes.dex` | 377 864 | 391 080 | CHANGED (+13 216 B) — the fix-wave code |
| `classes2.dex` | 126 284 | 111 256 | CHANGED (−15 028 B) — R8 re-layout, net dex −1 812 B |
| `assets/dictionaries/NOTICE.txt` | 6 878 | 7 353 | CHANGED (+475 B) — admission sources / arithmetic |
| `assets/bigrams/NOTICE.txt` | 4 943 | 5 257 | CHANGED (+314 B) — 13 154 heads, K = 3, eval thinning |
| `assets/dexopt/baseline.prof` / `.profm` | 1 265 / 79 | 1 256 / 69 | regenerated — now covers glide (3 240 rules) |
| `res/rt.xml` | 696 | 740 | CHANGED (+44 B) — the corrected restriction titles |
| `res/Kt.xml` | 3 688 | 3 688 | recompiled (+0 B) — string-reference shift |
| `resources.arsc` | 99 784 | 99 792 | +8 B — the strings fixes (links, orthography, plural) |
| `AndroidManifest.xml` | 4 664 | 4 664 | version lines only |
| `META-INF/version-control-info.textproto` | 120 | 120 | embedded git HEAD: 3.0.0 was packed pre-commit (`1b07f1c5`), this build embeds the v3.0.0 release commit `b8de3916` |

**Byte-identical (CRC32-verified):** all four pinned binary assets —
`tatar_top100k_v1.tdict.zlib`, `russian_top100k_v1.tdict.zlib`,
`tatar_bigrams_v1.tatbigr.zlib`, `russian_bigrams_v1.tatbigr.zlib` — plus
both sentstart tables, all five emoji assets and `emoji/NOTICE.txt`
(13 of 15 asset entries unchanged; only the two text NOTICEs differ, and
both are read from the APK, never inflated). 167 of 170 res/arsc entries
unchanged. (Same-tree check: the fix wave touches no `*.tdict.zlib` /
`*.tatbigr.zlib` in `git status`.)

Component totals (uncompressed, from the release_check delta gate): assets
+770, arsc +8, dex −1 812, res +44, other +0; **APK +0 B (+0.0 %)**.

## On-device consequence of updating from 3.0.0

**Nothing re-inflates.** No dictionary or bigram-table hash changed (all four
raw SHA-256s identical), so the inflated device files stay valid; the changed
NOTICE files and the baseline profile are read from the APK and never
inflated. Personal words, learned pairs, settings and preferences are
untouched — the fix wave changes no storage format (the F2 counters fix only
starts *writing* the usage bumps that were always meant to happen; the
session-end flush path is unchanged).

## Gates

`scripts/release_check.sh --full dist/tatar-keyboard-3.0.1.apk` —
**13/13 PASS, OVERALL PASS** (the `--full` mode rebuilt the release from
clean first; no fallback to `--quick` needed):

| Check | Result |
|---|---|
| `build.assemble_release` | clean assembleRelease OK |
| `gates.gradle_test` | **1 543 tests in 163 files, 0 failures** (`--rerun-tasks`) |
| `gates.lint_release` | baseline, no new errors (21 tasks executed) |
| `gates.python_tests` | **507 tests in 16 files** |
| `gates.no_internet` | both levels (manifest + aapt2), backup whitelist closed |
| `artifact.size` | 1 842 468 B, headroom 41.4 % |
| `artifact.asset_pins` | 16 values across 4 assets match |
| `artifact.emoji_assets` | 5 files byte-identical to the tree |
| `artifact.permissions` | exactly `[VIBRATE]` |
| `artifact.signature` | `98ca6febfed6…` (release key) |
| `artifact.version` | 3.0.1 / 35 = `app/build.gradle` |
| `artifact.changelog` | `metadata/en-US/changelogs/35.txt` present (349 B; ru-RU 494 B, tt 499 B — all ≤ 500) |
| `artifact.delta` | previous 3.0.0 (vc 34), +0 B (+0.0 %) |

Run separately (not part of `release_check.sh`):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`
(tt 155/0 and ru 2/0 known drift as pinned in `scripts/known_asset_drift.json`).

## Validation

The fix waves' own verification (host, 2026-09-24): every finding's pin is a
JVM/python test named per finding in `docs/AUDIT-2026-09-24-FIXES.md` (the
two mid-wave reds and their resolutions are recorded there); the
audit verdict per area is PASS in `docs/AUDIT-2026-09-24.md`. The residual
risks (accepted/parked) are listed in the audit's own section — light-theme
contrast, PointerTracker statics, the ~5.8 MB worst-case glide memory, the
exported SettingsActivity, metadata logging. The candidate differs from the
waves' verified tree only by the version strings and the release-prep
documents.

## Open

* TalkBack by ear, Direct Boot, the MIUI process killer and a real tablet
  remain human-only checks, as before.
* An on-device interactive pass of the fixes (the link opening, the undo
  guard on hardware) is the operator's pre-publish option; the pins cover
  the behavior in JVM.
* The tt store locale carries changelog notes for versions 27–35 only.
