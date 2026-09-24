# APK-AUDIT-3.0.2 — artifact audit

Date: 2026-09-25. Object: `dist/tatar-keyboard-3.0.2.apk`.

## Artifact

| | |
|---|---|
| Size | **1 846 564 B** (ceiling 3 145 728 B, headroom 41.3 %) |
| SHA-256 | `a50bb51f8a1afb4a63cbb56f6d83edf7bd54e633a1c96f17c7201a0aa4e6027d` |
| Version | 3.0.2 / versionCode 36 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`; unsigned 1 863 612 B, after zopfli −17 048 B |
| Permissions | exactly `android.permission.VIBRATE` |

**Build determinism note:** the signed size coincides byte-for-byte with the
P7-7 mission build recorded in `docs/ROADMAP-P7.md` (1 846 564 B at version
3.0.1/35 — the version strings are the same length); the SHA-256s differ
(version strings + embedded git HEAD). One pipeline run for this candidate;
reproducibility of the unsigned pipeline remains the DEV-2 CI gate.

## What shipped

The post-3.0.1 glide polish (`docs/ROADMAP-P7.md`, sections P7-5 / P7-6 /
P7-7; CHANGELOG `[3.0.2]`):

* **Resting-finger tolerance:** the glide detection window and speed check
  start at the first real movement, not at touch-down (field report
  2026-09-24 — hold-then-swipe used to never fire); a real long-press panel
  still wins.
* **Lift-commit:** a finished swipe types its best word at once (no strip
  tap); the other candidates stay in the strip and swap the word in place;
  one backspace right after the lift undoes the whole committed word; a
  no-decode gesture commits nothing.
* **No auto-space after a glide** (contract change, operator's call): a bare
  word is inserted; only a glide directly after another word prepends exactly
  one space — chained glides produce «сәләм дөнья» with nothing hanging at
  the end, and undoing a chain step removes its joining space too.
  Suggestion/prediction taps keep their auto-space (pinned).
* **Glide works with the suggestions toggle OFF** (Gboard parity): the
  gesture no longer answers the suggestions master switch, and the geometry
  push no longer rides the suggestions eligibility. Plus the fix for the
  «word ? » position (space before punctuation), which used to look dead
  mid-sentence.
* **Trail and key feedback:** a fading trail (~300 ms tail, fade-out 250 ms
  at lift) follows the finger, the key under the fingertip lights up —
  graphics only; with glide off the touch path is unchanged.

## Delta to 3.0.1

Archive entries: **194 → 194** — nothing added, nothing removed. Per-entry
comparison against `dist/tatar-keyboard-3.0.1.apk` (CRC32 identity,
uncompressed sizes):

| Entry | 3.0.1 | 3.0.2 | Verdict |
|---|---:|---:|---|
| `classes.dex` / `classes2.dex` | 391 080 / 111 256 | 393 148 / 113 096 | CHANGED (+3 908 B) — the glide polish code |
| `assets/dexopt/baseline.prof` / `.profm` | 1 256 / 69 | 1 261 / 69 | regenerated for the new code |
| `resources.arsc` | 99 792 | 99 944 | +152 B — the trail attrs/config entries (attrs +2, config +3, theme +1 line; no new strings) |
| ~100 × `res/*.xml` | same sizes | recompiled | resource-ID churn from the new arsc entries — every file unchanged in size, no behavioral change |
| `AndroidManifest.xml` | 4 664 | 4 664 | version lines only |
| `META-INF/version-control-info.textproto` | 120 | 120 | embedded git HEAD |

**Byte-identical (CRC32-verified):** all 13 shipped data assets — both
dictionaries, both bigram tables, both sentence-start tables, all three
NOTICE files and all five emoji assets. Only the build-derived
`baseline.prof`/`profm` differ under `assets/`. (Same-tree check:
`git diff v3.0.1..HEAD -- app/src/main/assets/` is empty.)

Component totals (uncompressed, from the release_check delta gate): assets
+5, arsc +152, dex +3 908, res +0, other +0; **APK +4 096 B (+0.2 %)**.

## On-device consequence of updating from 3.0.1

**Nothing re-inflates.** No dictionary or bigram-table hash changed (all four
raw SHA-256s identical), so the inflated device files stay valid; the changed
`baseline.prof` is read from the APK at install and never inflates. Personal
words, learned pairs, settings and both toggles are untouched. The glide
polish itself is touch-path code — no storage format moves.

## Gates

`scripts/release_check.sh --full dist/tatar-keyboard-3.0.2.apk` —
**13/13 PASS, OVERALL PASS** (the `--full` mode rebuilt the release from
clean first; no fallback to `--quick` needed):

| Check | Result |
|---|---|
| `build.assemble_release` | clean assembleRelease OK |
| `gates.gradle_test` | **1 580 tests in 165 files, 0 failures** (`--rerun-tasks`) |
| `gates.lint_release` | baseline, no new errors (21 tasks executed) |
| `gates.python_tests` | **507 tests in 16 files** |
| `gates.no_internet` | both levels (manifest + aapt2), backup whitelist closed |
| `artifact.size` | 1 846 564 B, headroom 41.3 % |
| `artifact.asset_pins` | 16 values across 4 assets match |
| `artifact.emoji_assets` | 5 files byte-identical to the tree |
| `artifact.permissions` | exactly `[VIBRATE]` |
| `artifact.signature` | `98ca6febfed6…` (release key) |
| `artifact.version` | 3.0.2 / 36 = `app/build.gradle` |
| `artifact.changelog` | `metadata/en-US/changelogs/36.txt` present (334 B; ru-RU 456 B, tt 486 B — all ≤ 500) |
| `artifact.delta` | previous 3.0.1 (vc 35), +4 096 B (+0.2 %) |

Run separately (not part of `release_check.sh`):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`
(tt 155/0 and ru 2/0 known drift as pinned in `scripts/known_asset_drift.json`).

## Validation

The glide polish shipped device-verified (`docs/ROADMAP-P7.md` §P7-5/P7-6/
P7-7, POCO C71; evidence `build/device-uat-2026-09-25/p7-7/`): lift-commit,
in-place alternatives, chain joining and chain undo driven on hardware
(readbacks `u00-readbacks.txt`), plus the resting-finger fix and the
suggestions-off independence. One environment note recorded there: the
device lay in landscape with the vendor overlay periodically restoring
`accelerometer_rotation=1` — the session additionally proved the fail-closed
glide refusal over an unfinished typed word; the device state was restored
afterwards. JVM pins: `GlideEndToEndTest` (22, incl. the chain/undo pins),
`GlideTrailTest` (11), the touch-integration and trail contract suites, and
the androidTest `GlideUiDeviceTest` live drive. The candidate differs from
the UAT'd build only in the version strings.

## Open

* TalkBack by ear, Direct Boot, the MIUI process killer and a real tablet
  remain human-only checks, as before.
* The tt store locale carries changelog notes for versions 27–36 only.
