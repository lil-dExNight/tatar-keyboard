# Release procedure

Version-independent steps for shipping `X.Y.Z` (versionCode `N`). Run everything from the
repository root on a clean checkout with JDK 17 and the Android SDK (pinned build-tools in
`scripts/build-tools-pin.sh`). Release history lives in `CHANGELOG.md` and the git tags.

On macOS, put GNU coreutils and bash ≥ 4 first in `PATH` (the scripts use `stat -c`,
`sha256sum` and `mapfile`): `export PATH="$(brew --prefix coreutils)/libexec/gnubin:$PATH"`.

## 1. Preflight

- [ ] `app/build.gradle`: bump `versionCode` to `N` and `versionName` to `"X.Y.Z"`.
- [ ] `CHANGELOG.md`: add `## [X.Y.Z] — YYYY-MM-DD` with user-facing entries.
- [ ] Store changelogs: `metadata/en-US/changelogs/N.txt`, `metadata/ru-RU/changelogs/N.txt`,
      `metadata/tt/changelogs/N.txt`. Plain language, the three locales say the same thing,
      each file ≤ 500 characters (F-Droid limit).
- [ ] If bundled dictionaries or bigram tables changed, they were rebuilt with
      `scripts/rebuild_assets.py` and the pinned sizes and SHA-256 were updated in the same change.
- [ ] `keystore.properties` and its keystore are present locally. Both are secrets: git-ignored,
      never committed or copied to CI. Keep an offline backup; losing the key breaks updates.
- [ ] The previous release APK is in `dist/` (local, git-ignored) as the delta baseline.
- [ ] `git status --porcelain` shows only the release changes.

## 2. Release gates

```sh
bash scripts/release_check.sh --full
```

`--full` runs `clean assembleRelease`, then the JVM and Python tests, `lintRelease`, the
no-INTERNET/backup gate, `rebuild_assets.py --check --allow-known-drift` and every artifact check
(size ceiling, asset pins, bundled asset sets, permissions, exported surface, secrets scan,
signature, version, store changelog, delta against `dist/`). Logs go to `build/release_check/`.

- [ ] The last line is `OVERALL|PASS|…`. Any `RESULT|FAIL|…` line blocks the release.

## 3. Pack and sign

```sh
bash scripts/release_pack.sh dist/tatar-keyboard-X.Y.Z.apk
```

The script builds unsigned (`-PskipReleaseSigning`), recompresses with `zipalign -z` (zopfli)
before signing, signs v2-only with the key from `keystore.properties` and verifies the signature.
It must run after step 2, because `--full` cleans `app/build/`.

- [ ] Keep the `RESULT|zopfli+signed|…` (size) and `RESULT|sha256|…` lines for the release record.
- [ ] Optional: pack again to `dist/.repro.apk`, `cmp` both files, delete the copy (CI job
      `reproducible` does the same on unsigned builds).
- [ ] The APK has `classes.dex` and `classes2.dex` (the startup layout from the tracked
      `app/src/main/generated/baselineProfiles/startup-prof.txt`; `release_check.sh` checks it as
      `artifact.dex_layout`).
- [ ] `app/src/release/generated/` is absent. It is git-ignored, and a profile there would still
      be merged in, so the APK would differ from a build of the tag.

### Reproducing a published APK

AGP writes the commit checked out at build time into `META-INF/version-control-info.textproto`.
The pack runs before the release commit, so that file names the release commit's parent. To
rebuild a published APK byte for byte, clone the repository (a `git worktree` has a `.git` file
instead of a directory, so AGP records no commit), check out the commit named in that file,
`git checkout vX.Y.Z -- .` to lay the tag's tree over it, add the untracked
`gradle/wrapper/gradle-wrapper.jar`, `local.properties` and `keystore.properties`, and run
`scripts/release_pack.sh`. Remove the copied secrets afterwards.

## 4. Verify the signed APK

```sh
bash scripts/release_check.sh --quick dist/tatar-keyboard-X.Y.Z.apk
bash scripts/check-no-internet.sh dist/tatar-keyboard-X.Y.Z.apk
```

- [ ] `release_check.sh --quick` ends with `OVERALL|PASS` (it skips the source gates, which
      step 2 already ran). Save its output as `dist/release-check-X.Y.Z.txt`.
- [ ] `check-no-internet.sh` exits 0 on both levels (manifest and `aapt2`).
- [ ] Device tests are not a release gate. If the release touches input, suggestions, glide typing
      or performance, run `scripts/emulator-smoke.sh` or `scripts/device-perf-ritual.sh`.

## 5. Commit and tag

- [ ] Commit the release changes (version, changelogs, `HANDOFF.md`); CI is green on that commit.
- [ ] Annotated tag on the release commit, pushed only when the maintainer decides to publish:

```sh
git tag -a vX.Y.Z -m "Tatar Keyboard X.Y.Z"
git push origin main vX.Y.Z
```

## 6. Publish

**GitHub Release** (web UI): tag `vX.Y.Z`, title `Tatar Keyboard X.Y.Z`, notes = the
`[X.Y.Z]` section of `CHANGELOG.md` followed by the filled release record (below). Attach
`dist/tatar-keyboard-X.Y.Z.apk` (the verified file, never a rebuild) and
`dist/release-check-X.Y.Z.txt`.
- [ ] Download the APK from the release page without signing in; its SHA-256 matches the record.

**IzzyOnDroid**: for the first inclusion, open an App Inclusion Request at
https://codeberg.org/IzzyOnDroid/repodata/issues/new/choose after checking the current
[App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/). Give the source
URL, the Apache-2.0 code license, the data licenses from `app/src/main/assets/*/NOTICE.txt`,
the build instructions and the public GitHub Release. Once the app is included, IzzyOnDroid
picks up new GitHub Release APKs itself.
- [ ] The new version appears in IzzyOnDroid and installs as an update over the previous one.

**F-Droid**: `metadata/` is the Fastlane layout F-Droid reads from the tagged commit (title,
descriptions, screenshots, `changelogs/N.txt`), so it must be final before tagging. F-Droid
builds from the tag with its own recipe; the reproducible build lets it publish with our signature.
- [ ] After the F-Droid build, the listing shows version `X.Y.Z` and the new changelog.

## Release record

Paste under the GitHub Release notes; it replaces a per-release audit document.

```
Release record — X.Y.Z (versionCode N), YYYY-MM-DD
APK: tatar-keyboard-X.Y.Z.apk, <size> B, SHA-256 <sha256>   (release_pack.sh RESULT lines)
Gates: release_check.sh --full and --quick OVERALL PASS (release-check-X.Y.Z.txt)
Changed archive entries vs <previous> (CRC-32; size delta from artifact.delta):
  <entry> — <why>
  ...
Bundled data assets: <unchanged | changed: dictionary/bigram/emoji file names>
On update: <nothing is re-inflated | these dictionaries/tables are re-inflated on first use>
Personal dictionary, learned pairs, learned emoji and settings: <untouched | migration note>
Device tests: <not run | emulator smoke / device ritual result, output directory>
Open items: <none | list>
```

```sh
crc() { unzip -v "$1" | awk 'NF == 8 && $7 ~ /^[0-9a-f]{8}$/ {print $8, $7}' | sort; }
diff <(crc dist/tatar-keyboard-PREV.apk) <(crc dist/tatar-keyboard-X.Y.Z.apk)
```

A changed `*.tdict.zlib` or `*.tatbigr.zlib` means devices re-inflate it on first use after the
update: the on-device file name contains the asset's SHA-256 (`finalFileName` in
`DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`).
