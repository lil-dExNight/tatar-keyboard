# APK-AUDIT-3.1.0 — artifact audit

Date: 2026-09-25. Object: `dist/tatar-keyboard-3.1.0.apk`.

## Artifact

| | |
|---|---|
| Size | **1 694 282 B** (ceiling 3 145 728 B, headroom 46.1 %) |
| SHA-256 | `f847e53e8635fa99354ba531fddb2032cd20dca1e4f861bc910fbda98f85b98e` |
| Version | 3.1.0 / versionCode 37 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`, run **twice**: both runs byte-identical (`cmp` clean, same SHA-256) |
| Permissions | exactly `android.permission.VIBRATE` |
| Archive entries | 184 (3.0.2 had 194) |

## What shipped

Two waves that had accumulated on top of 3.0.2, plus the four post-3.0.2
commits, released together (CHANGELOG `[3.1.0]`):

* **Optimization wave** (`docs/OPTIMIZE-2026-09-25.md`, O1 SAFE + O2 measured):
  dead resources removed, lazy default in `readKeyboardColor`, startup profile
  regenerated, duplicate binary search and duplicate strip rebuild removed,
  `GlidePath` made lazy, trail fade cadence 33 ms, emoji fling stopped on hide,
  long-press and marker timers cleaned up; then the measured half — the resource
  table deflated, hardware partial redraw, glide index released on idle, the
  launcher icon vectorized, and `androidx.customview` removed together with the
  whole androidx transitive set (its accessibility helper now an in-tree fork
  under `compat/`). Measured on a POCO C71: frame 8.2 → 6.8 ms, cold start
  275 → 257 ms.
* **Security/robustness audit fixes** (`docs/SECURITY-AUDIT-2026-09-25.md` and
  `-FIXES.md`, waves A and B): editor text cache cleared on every session
  boundary and never re-read in password fields, the paste rule in
  `CleanRunMachine` (a fresh word's first observation may carry ≤ 2 UTF-16
  units), FLAG_SECURE on the three personal-content dialogs, the 0×0 keyboard
  view guard, strip publication ordering, plus F1–F17 (huge-paste threshold,
  sticky `mCursorMoved`, cache growth cap, `SurroundingText` validation,
  batch-edit pairing, dead-editor guards, inverted selection, cache race,
  reload coalescing, `getUnicodeSteps` hygiene, tracker-queue dedup, non-finite
  glide points, emoji index cap, strip cell NaN guard).
* **Pipeline hardening**: per-entry byte verification and duplicate-name refusal
  in `release_pack.sh`, single-signer enforcement and `artifact.tree_assets` in
  `release_check.sh`, `distributionSha256Sum` pinned, no artifact upload on
  `pull_request` CI runs.
* **The four product commits**: the glide doubled-letter fix (a doubled word now
  scores only against its looped ideal path), the white-gray glide trail, and
  the app's own screens defaulting to Tatar.
* **Apple-UX stages A and B** (`docs/ROADMAP-P8-PLAN.md`): three shift states,
  balloon-only letter feedback, the droplet key-preview balloon, the accent
  action Return, the iOS-coloured alternatives panel, the 44dp strip with inset
  hairlines and a rounded pressed cell, the settings screen transition, the
  restyled dialogs, the deeper key shadow, the re-pinned functional grey, the
  opaque spacebar label, and haptics that respect the system switch.
* **Stage C**: dimmed settings rows explain themselves; only one language's
  glide index stays resident; the emoji-suggestion table is filtered while it is
  parsed instead of being built twice.
* **Stage D** (developer-facing, nothing in the APK): CI actions pinned to
  commit SHAs, the packer's build-tools version pinned,
  `gradle/verification-metadata.xml` added, the pack pipeline brought under the
  CI reproducibility gate via `--no-sign`, and `gradlew` restored to the
  official 9.6.0 script.

## Delta to 3.0.2

Archive entries **194 → 184**. Uncompressed payload 2 492 938 → 2 394 898 B
(−98 040 B); signed APK −152 282 B (−8.2 %).

| Change | 3.0.2 | 3.1.0 | Verdict |
|---|---:|---:|---|
| `classes.dex` | 393 148 | 378 420 | −14 728 B — androidx gone, dead code removed, the stage-A/B/C code added back |
| `classes2.dex` | 113 096 | 83 008 | −30 088 B — same cause |
| `resources.arsc` | 99 944 (**stored**) | 97 044 (**deflated → 25 270**) | O2-1: the table is now compressed in the archive; −2 900 B raw, −74 674 B in the archive (the stage-A/B entries — the new colours, the action attr, the ids and strings — grew it back a little) |
| `AndroidManifest.xml` | 4 664 | 4 508 | −156 B — the androidx-era entries are gone |
| 10 × `res/*.webp` | 62 602 total | — | REMOVED: the five-density `ic_launcher_foreground` + `ic_launcher_monochrome` raster layers |
| 4 × `res/*.xml` | — | — | ADDED: the two vector launcher layers, the new shift-on icon (M1) and the dialog card (S2) |
| 4 × `META-INF/androidx.*.version` | 24 | — | REMOVED: no androidx artifacts left to mark |
| `assets/dexopt/baseline.prof` / `.profm` | 1 261 / 69 | 1 147 / 57 | regenerated after the androidx removal |
| 142 × `res/*.xml` | mostly same sizes | recompiled | resource-ID churn from the arsc rewrite, plus the stage-A/B theme and key-style edits |
| 31 entries | — | — | byte-identical, including all eight dictionary/bigram assets and all five emoji assets |

**The shipped data is untouched:** every `assets/dictionaries/*`,
`assets/bigrams/*` and `assets/emoji/*` entry has the same CRC32 in both
artifacts — the only changed `assets/` entries are the two regenerated dexopt
profiles. Confirmed independently by `release_check.sh`'s `artifact.asset_pins`
(16 pinned values), `artifact.emoji_assets` (5 files) and `artifact.tree_assets`
(8 files).

## Gates

| Gate | Result |
|---|---|
| python suites | **507 tests, 16 files, 0 failing** |
| `./gradlew test --rerun-tasks` | **1 670 tests, 180 suites, 0 failures/errors/skipped** |
| `./gradlew lintRelease` | 0 errors, 5 live warnings, 28 filtered by the baseline |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` × 2 | byte-identical, SHA-256 `f847e53e…5b98e`, 1 694 282 B |
| `check-no-internet.sh` (packed APK) | both levels OK, backup closed as a whitelist |
| `release_check.sh --full` | **OVERALL PASS — 16/16**, delta vs 3.0.2 −152 282 B (−8.2 %) |

## Open

The tag, the push and the store publication are the operator's (AGENTS.md); the
commit split proposed for this release is recorded in the `HANDOFF.md` entry of
the same day. Device UAT of the wave on the POCO C71 is pending the phone.
