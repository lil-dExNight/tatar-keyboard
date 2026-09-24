# APK-AUDIT-3.0.0 — artifact audit

Date: 2026-09-24. Object: `dist/tatar-keyboard-3.0.0.apk`.

## Artifact

| | |
|---|---|
| Size | **1 842 468 B** (ceiling 3 145 728 B, headroom 41.4 %) |
| SHA-256 | `e4217cfa2395aa4d421ef9ae7a7c0f3e5c20e896914bb159420b45af43b499cd` |
| Version | 3.0.0 / versionCode 34 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`; unsigned 1 863 324 B, after zopfli −20 856 B |
| Permissions | exactly `android.permission.VIBRATE` |

**Build determinism note:** the unsigned input is byte-size identical to the
P7 verifier's twice-reproduced build (1 863 324 B — the unsigned pack is
reproducible per commit); the signed size coincides with the mission's
recorded 1 842 468 B, the SHA-256 differs (the version strings and the
embedded git HEAD). This audit ran the pipeline once; reproducibility of the
unsigned pipeline remains the DEV-2 CI gate.

## What shipped

Seven phases of the roadmap (`docs/ROADMAP.md`, reports `docs/ROADMAP-P1.md`
… `docs/ROADMAP-P7.md`), all previously gated on the same content at
versionCode 33 (phases ship unbumped by convention):

* **P1** — engine batch: sentence-start predictions capitalized
  (shift-aware casing), the same feature **for Russian** (new corpus-built
  table `russian_sentstart_v1.txt`), and predictions after a comma (and `;`
  `:`) — the post-word band works after clause punctuation, exactly as after
  a space.
* **P2** — personalization: on-device word-pair learning (salted-hash
  pending counters, plaintext only for earned pairs), the Saved words
  management screen for words AND pairs, and incognito mode.
* **P3** — autocorrect preview: the strip shows the fix before it applies
  (typed word left, correction emphasized center; refusable, one-backspace
  undo).
* **P4** — prediction depth: the expanded Tatar bigram table (heads
  10 204 → **13 154**, repacked to K = 3); a two-edit typo tier was measured
  and rejected by its gates, NOT shipped (`70bb4901`).
* **P5** — keyboard height presets (Compact 85 % / Default 100 % / Tall
  115 %) replacing the inherited slider.
* **P6** — god-object restructure (no behavior change) + baseline profile
  regenerated (2 278 → 2 433 rules).
* **P7** — glide (swipe) typing, Tatar and Russian, on by default; SHARK2
  decoder, on-device only.

## Delta to 2.0.1

Archive entries: **248 → 194** (1 added, 55 removed, 74 changed). Per-entry
comparison against `dist/tatar-keyboard-2.0.1.apk` (CRC32 identity,
uncompressed sizes):

| Entry | 2.0.1 | 3.0.0 | Verdict |
|---|---:|---:|---|
| `assets/bigrams/tatar_bigrams_v1.tatbigr.zlib` | 81 476 | 79 574 | **CHANGED (−1 902 B)** — heads 10 204 → 13 154, K 4 → 3 |
| `assets/dictionaries/russian_sentstart_v1.txt` | — | 1 745 | **NEW** — 64 Russian sentence-start records |
| `assets/dictionaries/NOTICE.txt` | 6 260 | 6 878 | CHANGED (+618 B) — the ru-sentstart Leipzig attribution |
| `assets/dexopt/baseline.prof` / `.profm` | 1 136 / 69 | 1 265 / 79 | regenerated (2 433 rules) |
| `classes.dex` / `classes2.dex` | 332 532 / 114 892 | 377 864 / 126 284 | seven phases of code (+56 724 B) |
| `resources.arsc` | 94 128 | 99 784 | +5 656 B — new UI strings (3 locales) |
| `AndroidManifest.xml` | 4 664 | 4 664 | version lines only |
| `META-INF/version-control-info.textproto` | 120 | 120 | embedded git HEAD |
| 55 × `res/*.xml` (legacy layouts) | — | removed | **REMOVED** — unused layout resources (English keeps QWERTY/QWERTZ/ABC) |
| 66 × other `res/*.xml` | same sizes | recompiled | resource-table churn after the removals (four shrank ~270–340 B); no behavioral change |

**Byte-identical (CRC32-verified, 10 assets):** `tatar_top100k_v1.tdict.zlib`,
`russian_top100k_v1.tdict.zlib`, `russian_bigrams_v1.tatbigr.zlib`,
`tatar_sentstart_v1.txt`, `bigrams/NOTICE.txt`, `emoji/NOTICE.txt` and all
four remaining emoji assets. (Same-tree check: `git diff v2.0.1..HEAD --
app/src/main/assets/` touches exactly the three files above.)

Pin transition for the one changed pinned asset (compressed / raw / heads):

| Asset | 2.0.1 | 3.0.0 |
|---|---|---|
| tt bigram table | 81 476 B, `ce8169ae…e3c042` / raw 134 938 B, `a9157aea…038a0e` / 10 204 heads | 79 574 B, `283661b4…edb3f9` / raw 135 889 B, `87af8ba3…825fd8` / **13 154 heads**, K = 3, linked to the unchanged dict raw `3634f021…2518` |

Component totals (uncompressed, from the release_check delta gate): assets
+600, arsc +5 656, dex +56 724, res −114 820, other +0; **APK −7 087 B
(−0.4 %)** — the removed legacy layouts outweigh the new code: seven feature
phases ship in an APK *smaller* than 2.0.1.

## On-device consequence of updating from 2.0.1

Exactly **one** artifact re-inflates **once** at first use: the Tatar bigram
table (its device file name carries the raw SHA-256, which changed). Both
dictionaries are byte-identical, so no dictionary re-inflation; the new
`russian_sentstart_v1.txt` is a text asset read straight from the APK (like
the emoji assets and the Tatar table of the same family) and is never
inflated. Personal dictionary, learned pairs, settings and the saved keyboard
height preference are untouched (the height presets read the same stored
value). Update-with-data-preserved for the per-phase content was exercised
throughout the roadmap on the POCO C71 (`adb install -r` chains; reports in
`docs/ROADMAP-P*.md`).

## Gates

`scripts/release_check.sh --full dist/tatar-keyboard-3.0.0.apk` —
**13/13 PASS, OVERALL PASS** (the `--full` mode rebuilt the release from
clean first; no fallback to `--quick` needed):

| Check | Result |
|---|---|
| `build.assemble_release` | clean assembleRelease OK |
| `gates.gradle_test` | **1 534 tests in 162 files, 0 failures** (`--rerun-tasks`) |
| `gates.lint_release` | baseline, no new errors (21 tasks executed) |
| `gates.python_tests` | **505 tests in 16 files** |
| `gates.no_internet` | both levels (manifest + aapt2), backup whitelist closed |
| `artifact.size` | 1 842 468 B, headroom 41.4 % |
| `artifact.asset_pins` | 16 values across 4 assets match (incl. the re-pinned 13 154-head table) |
| `artifact.emoji_assets` | 5 files byte-identical to the tree |
| `artifact.permissions` | exactly `[VIBRATE]` |
| `artifact.signature` | `98ca6febfed6…` (release key) |
| `artifact.version` | 3.0.0 / 34 = `app/build.gradle` |
| `artifact.changelog` | `metadata/en-US/changelogs/34.txt` present (475 B; ru-RU 462 B, tt 428 B — all ≤ 500) |
| `artifact.delta` | previous 2.0.1 (vc 33), −7 087 B (−0.4 %) |

Run separately (not part of `release_check.sh`):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`
(tt 155/0 and ru 2/0 known drift as pinned in `scripts/known_asset_drift.json`
— the tt number grew with the expanded head set and was re-pinned by the
phase-4 mission).

## Validation

Per-phase gates and measurements are in `docs/ROADMAP-P1.md` …
`docs/ROADMAP-P7.md`. For the final tree (2026-09-24, POCO C71, Android 15
Go; evidence `build/device-uat-2026-09-24/`):

* Glide decoder on the live keyboard through the production path:
  post-optimization **p50 1.621 ms / p95 3.388 ms ≤ 5 ms** device gate
  (`GlideDeviceInstrumentationTest`, 1 000 samples, raw logcat saved).
* Cold start (force-stop → `am start -W`): SetupActivity median **252 ms**,
  SettingsActivity **269 ms** — both well under the 400 ms budget; crash
  buffer empty, no FATAL/ANR.
* The interactive device drive was BLOCKED by the phone owner actively using
  the device (IME flipped to Gboard mid-session — recorded honestly in
  ROADMAP-P7 §P7-4); the interactive half ran on the emulator instead
  (`build/device-uat-2026-09-24/p7/`, 36 files): glide gesture → `сәләм` in
  the strip, tap commits + chain predictions, ru glide, tap-typing
  regression with glide ON, space-swipe not eaten, toggle OFF = legacy
  behavior — G1–G9 PASS.

## Open

* TalkBack by ear, Direct Boot, the MIUI process killer and a real tablet
  remain human-only checks, as before.
* An on-device interactive glide session on the owner's terms is the one
  scenario family that ran on the emulator only (see Validation).
* The tt store locale carries changelog notes for versions 27–34 only.
* CHANGELOG `[3.0.0]` "What stayed the same" lists bigram tables as
  unchanged — imprecise for the Tatar table (it changed: 13 154 heads, K=3);
  the same section's own bullet and this audit carry the correct fact. Left
  as written per the release-freeze rule; the operator may amend before
  committing.
