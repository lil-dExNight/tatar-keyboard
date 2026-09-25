# APK-AUDIT-3.1.1 — artifact audit

Date: 2026-09-25 (evening). Object: `dist/tatar-keyboard-3.1.1.apk`.

This is the 3.1.0 content with ONE change: `resources.arsc` is stored uncompressed again. Read
`docs/APK-AUDIT-3.1.0.md` for what the release ships; this document records only the fix and the
numbers it moved.

## Artifact

| | |
|---|---|
| Size | **1 763 914 B** (ceiling 3 145 728 B, headroom 43.9 %) |
| SHA-256 | `941a1e208787d355bb91a858e0d953be76e137657bc63aa35b5640d2e938ad41` |
| Version | 3.1.1 / versionCode 38 |
| Signature | v2, `CN=Tatar Keyboard`, `98ca6feb…42ad` — the same key of the whole line |
| Build | `scripts/release_pack.sh`, run twice: byte-identical |
| Permissions | exactly `android.permission.VIBRATE` |

## Why 3.1.1 exists

3.1.0 could not be installed on Android 11 or newer:

```
Failure [-124: Failed parse during installPackageLI: Targeting R+ (version 30 and above) requires
the resources.arsc of installed APKs to be stored uncompressed and aligned on a 4-byte boundary]
```

The optimization wave's O2-1 item had repacked that table as DEFLATED for −73 728 archive bytes.
The platform mmaps it, so for `targetSdk` 30+ compression is fatal. The item was verified by
counting archive bytes and never by installing, and `zipalign -c 4` prints `OK - compressed` and
exits 0 in this exact case — so the packer's own check, the release ritual and
`release_check --full` 16/16 were all green on an artifact no phone would accept.

## Delta to 3.1.0

| Change | 3.1.0 | 3.1.1 | Verdict |
|---|---:|---:|---|
| `resources.arsc` storage | DEFLATED (25 270 B in archive) | **STORED** (97 044 B, offset 1 651 844, divisible by 4) | the fix |
| Signed APK | 1 694 282 | 1 763 914 | +69 632 B — the cost of the revert |
| Everything else | — | — | unchanged: same dex, same assets, same manifest, same 184 entries |

Versus the last installable release, 3.0.2 (1 846 564 B), 3.1.1 is still **−82 650 B (−4.5 %)**.

## Gates

| Gate | Result |
|---|---|
| python suites | 507 tests, 0 failing |
| `./gradlew test` | 1 670 tests, 180 suites, 0 failures |
| `./gradlew lintRelease` | 0 errors |
| `rebuild_assets.py --check` | `ok: true` |
| `release_pack.sh` × 2 | byte-identical |
| **`artifact.arsc_stored`** (new gate) | **PASS** — and FAIL on the 3.1.0 artifact, which is how it was validated |
| `check-no-internet.sh` | both levels OK |
| `release_check.sh --full` | **OVERALL PASS — 17/17** |
| install on POCO C71 (Android 15) | **Success**, SHA-256 of the installed `base.apk` equals the built artifact |
