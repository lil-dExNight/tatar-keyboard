# APK-AUDIT-2.0.0 — artifact audit

Date: 2026-09-20. Object: `dist/tatar-keyboard-2.0.0.apk`.

## Artifact

| | |
|---|---|
| Size | **1 849 555 B** (ceiling 3 145 728 B, headroom 41.2 %) |
| SHA-256 | `6f51cb60be4e028fdf44f97000c899c4378b6d618d633a51ed0c32f43bf01e7c` |
| Version | 2.0.0 / versionCode 32 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`; unsigned 1 869 287 B, after zopfli −19 732 B |
| Permissions | exactly `android.permission.VIBRATE` |

**Build determinism note:** the signed size coincides byte-for-byte with the two
mission builds of the same day (TT-SUGGESTIONS `36d80c99…`, TT-TYPO-NEXT
`32cd873b…` — both 1 849 555 B); zopfli determinism makes equal compressed
sizes possible, and the SHA-256s all differ. This candidate adds only the
version bump on top of the TT-TYPO-NEXT content: the unsigned input is
1 869 287 B exactly as the Phase-C2 build, and the version strings fit the
same padded slots (`AndroidManifest.xml` 4 664 → 4 664 B, CRC changed).
Reproducibility of the unsigned pipeline itself remains the DEV-2 CI gate
(two `clean assembleRelease` runs compared with `cmp`); this audit ran the
pipeline once.

## What shipped

Two missions, both previously validated on the same content at versionCode 31
(missions ship unbumped by convention):

* **TT-SUGGESTIONS** (`docs/TT-SUGGESTIONS.md`): Tatar dictionary
  100 000 → 110 000 entries (9 052 corpus-attested word forms from the
  project's own paradigm generator + 948 conversational words, zero 1.8.4
  words displaced); word-form suggestions after a committed Tatar word +
  space; same-stem boost at complete-word prefixes (≥ 4 code points);
  sentence-start predictions (new 64-word asset `tatar_sentstart_v1.txt`).
* **TT-TYPO-NEXT** (`docs/TT-TYPO-NEXT.md`): next-word predictions right
  after a suggestion tap (Phase A); typo recovery on an otherwise empty
  strip via fuzzy edit class #4, Tatar engine only (Phase C2 — `сцләм` →
  `сәләм` in cell 1 by the 5th letter; Russian engine runs the DEFAULT
  policy, bit-identical to pre-mission behavior).

## Delta to 1.9.15

Archive entries: **247 → 248**. Per-entry comparison against
`dist/tatar-keyboard-1.9.15.apk` (uncompressed sizes, CRC32 identity):

| Entry | 1.9.15 | 2.0.0 | Verdict |
|---|---:|---:|---|
| `assets/dictionaries/tatar_top100k_v1.tdict.zlib` | 501 683 | 542 493 | **CHANGED (+40 810 B)** — 110 000 entries |
| `assets/bigrams/tatar_bigrams_v1.tatbigr.zlib` | 81 028 | 81 476 | **CHANGED (+448 B)** — re-bound to the new dictionary |
| `assets/dictionaries/tatar_sentstart_v1.txt` | — | 1 838 | **NEW** — 64 sentence-start records |
| `assets/dictionaries/NOTICE.txt` | 5 401 | 6 260 | CHANGED (+859 B) — word-form provenance |
| `assets/dexopt/baseline.prof` / `.profm` | 1 119 / 69 | 1 136 / 69 | regenerated for the new code |
| `classes.dex` / `classes2.dex` | 331 156 / 97 504 | 332 340 / 114 292 | mission code |
| `AndroidManifest.xml` | 4 664 | 4 664 | version lines only |
| `META-INF/version-control-info.textproto` | 120 | 120 | commit id |

**Byte-identical (CRC32-verified, 8 assets):** `russian_top100k_v1.tdict.zlib`,
`russian_bigrams_v1.tatbigr.zlib`, `bigrams/NOTICE.txt`, `emoji/NOTICE.txt`,
`emoji_search_v1.txt`, `emoji_set_v1.txt`, `emoji_skin_v1.txt`,
`emoji_suggest_v1.txt`. Every `res/` entry and `resources.arsc` are unchanged
(the release_check delta: res +0, arsc +0; assets +43 972 B, dex +17 972 B;
APK +53 336 B, +3.0 %).

Pin transition for the two changed pinned assets (compressed / raw):

| Asset | 1.9.15 | 2.0.0 |
|---|---|---|
| tt dictionary | 501 683 B, `cb34fe7d…918119` / raw 1 162 870 B | 542 493 B, `e653ef6e…fa96ed` / raw 1 276 289 B, `3634f021…2518` |
| tt bigram table | 81 028 B, `b1b92914…a087f` / raw 134 664 B | 81 476 B, `ce8169ae…e3c042` / raw 134 938 B, `a9157aea…038a0e`, linked to dict raw `3634f021…2518` |

## On-device consequence of updating from 1.9.15

Exactly two artifacts re-inflate **once** at first use: the Tatar dictionary
and the Tatar bigram table — their device file names carry the raw SHA-256,
and both hashes changed (the standard schema-2/schema-3 path, first exercised
at 1.9.9). Everything else is untouched: the Russian dictionary and table,
all emoji assets, the personal dictionary and settings survive the update.
Update-with-data-preserved was proven on hardware on this exact content: both
2026-09-20 device UATs (TT-SUGGESTIONS and TT-TYPO-NEXT Phase D, POCO C71)
installed the mission APK with `adb install -r` over the previous build —
`firstInstallTime` unchanged, the suggestions toggle survived, all scenario
rows PASS (evidence: `build/device-uat-2026-09-20/`).

## Gates

`scripts/release_check.sh --full dist/tatar-keyboard-2.0.0.apk` —
**13/13 PASS, OVERALL PASS** (the `--full` mode rebuilt the release from
clean first; everything below ran in this environment, no fallback to
`--quick` was needed):

| Check | Result |
|---|---|
| `build.assemble_release` | clean assembleRelease OK |
| `gates.gradle_test` | **1 235 tests in 134 files, 0 failures** (`--rerun-tasks`) |
| `gates.lint_release` | baseline, no new errors (21 tasks executed) |
| `gates.python_tests` | **474 tests in 15 files** |
| `gates.no_internet` | both levels (manifest + aapt2), backup whitelist closed |
| `artifact.size` | 1 849 555 B, headroom 41.2 % |
| `artifact.asset_pins` | 16 values across 4 assets match |
| `artifact.emoji_assets` | 5 files byte-identical to the tree |
| `artifact.permissions` | exactly `[VIBRATE]` |
| `artifact.signature` | `98ca6febfed6…` (release key) |
| `artifact.version` | 2.0.0 / 32 = `app/build.gradle` |
| `artifact.changelog` | `metadata/en-US/changelogs/32.txt` present (497 B; ru-RU 496 B, tt 486 B — all ≤ 500) |
| `artifact.delta` | previous 1.9.15 (vc 31), +53 336 B (+3.0 %) |

Run separately (not part of `release_check.sh`):
`python3 scripts/rebuild_assets.py --check --allow-known-drift` — `"ok": true`
(tt 3/0 and ru 2/0 known drift as pinned in `scripts/known_asset_drift.json`).

## Validation

Both missions were validated on the shipped content before the version bump:

* Device UAT on the POCO C71 (Android 15 Go): TT-SUGGESTIONS 20/20 rows PASS
  (`docs/TT-SUGGESTIONS.md`, DEVICE-UAT section) and TT-TYPO-NEXT 16/16 rows
  PASS (`docs/TT-TYPO-NEXT.md`, Phase D) — including the `сцләм` → `сәләм`
  ladder byte-identical to the JVM pins; evidence `build/device-uat-2026-09-20/`.
  Cold start medians 253–270 ms against the 400 ms budget.
* Emulator smoke `tt_suggest_a14`: 20 PASS / 0 FAIL / 1 SKIP (en by design),
  including the word-form tap probes and the tap-followup probe.
* The 2.0.0 candidate differs from those UAT'd builds only in the version
  strings (proven by the per-entry comparison above).

## Open

* TalkBack by ear, Direct Boot, the MIUI process killer and a real tablet
  remain human-only checks, as before.
* Cold-start / PSS numbers come from the mission UATs at versionCode 31; the
  version bump does not move them (manifest-only delta).
* The tt store locale carries changelog notes for versions 27–32 only.
