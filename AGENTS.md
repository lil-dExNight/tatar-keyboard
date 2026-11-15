# Repository guide

Offline Android keyboard with a Tatar layout. It is a fork of Simple Keyboard (itself based on
AOSP LatinIME): legacy Java code plus new Kotlin code. Application id `org.tatarkeyboard.ime`,
source namespace `rkr.simplekeyboard.inputmethod`, work on branch `main`. Documentation index:
`docs/README.md`. Code map: `docs/ARCHITECTURE.md`.

## Commands

Run everything from the repository root. On macOS the shell scripts need GNU coreutils and GNU
grep first in `PATH` (they use `stat -c`, `sha256sum` and `grep -P`):
`PATH="$(brew --prefix coreutils)/libexec/gnubin:$(brew --prefix grep)/libexec/gnubin:$PATH"`.

| Task | Command and note |
|---|---|
| JVM tests | `./gradlew test` (`--rerun-tasks` forces a full rerun); JUnit 4, no Robolectric. `*CalibrationTest` suites are excluded from `test` and run in `./gradlew calibrationTest`; CI and `scripts/release_check.sh` run `test calibrationTest` together. |
| Python tests | `bash scripts/run_python_tests.sh` (one `unittest` module per process, in parallel; `TT_PYTHON_TEST_JOBS` sets the width); pytest is not used. |
| Device tests | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r` both APKs, then `adb shell am instrument -w -e class <test class> org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner`; timing assertions need an idle device with the screen on. `GlideUiDeviceTest` finds its keys on the live keyboard view; only its latency arm is calibrated to the 720x1640 reference screen and skips anywhere else. |
| Emulator smoke test | `bash scripts/emulator-smoke.sh [--avd tt_suggest_a14] [--apk <path>] [--no-boot] [--outdir build/emulator-smoke/]`; installs and selects the IME, runs the typing, suggestion and emoji probes and prints `RESULT\|…` lines; on a debuggable package it writes the suggestions pref through `run-as`, while on a release APK the suggestion probes rely on suggestions being on by default and the learned-emoji probe is skipped. Typed tails and committed spaces are polled to confirmation, and NEXT_WORD probes wait for the expected band against `scripts/emulator-smoke-refs/<sdk>/` reference crops before tapping. |
| Device performance check | `bash scripts/device-perf-ritual.sh [--serial <id>] [--pkg org.tatarkeyboard.ime.debug] [--legs cold,pss,frames,warm,touch,suggest,first-suggestion,first-glide] [--enable-suggestions-ui] [--strict] [--verify-report <result.txt>] [--outdir build/device-perf-ritual]`; measures cold start, PSS, frame times, janky frames, warm show, touch handling, suggestion round trips, cold first suggestions and cold/warm on-screen glide latency on a 720x1640 device, prints `over_budget=` on each budgeted line and restores the device state on exit; the default is advisory (budgets land as INFO lines), while `--strict` — the form a release run uses — turns every `over_budget=true` line, every SKIP and every skipped sample into FAIL, and the closing `RESULT\|SUMMARY` line reports the counts with `p0_signoff=ready` only for a strict run without any of them; measurement-only lines (the PSS smaps anon/file split) stay INFO in both modes; first-glide uses the installed debug androidTest APK and reports SKIP when no runner targets the package; the opt-in legs battery,uimode,fontscale (idle power accounting, night-mode and font-scale probes) run only when listed in `--legs`; `--enable-suggestions-ui` turns suggestions on in a release build through its settings screen; `--verify-report` validates an existing strict report on the host (`scripts/release_perf_report.py`), with no adb or device; validation requires complete PSS and jank evidence — all four PSS scenarios (keyboard-idle, after-50-words, emoji-panel-open, idle-30s), each with its own `over_budget=` verdict, and a budgeted jank line for every measured frames run. Key coordinates are computed from the layout XMLs for the 720x1640 reference screen, and `tests/script_safety/test_device_key_coordinates.py` pins them against `rows_tatar.xml` for both `device-perf-ritual.sh` and `device-netstats-proof.sh`, so a layout change cannot silently retarget the probes; the extra row's eight keys (ә ө ү җ ң һ ё ъ) sit at x=45/135/225/315/405/495/585/675, y=1110 on that screen. |
| Lint | `./gradlew lintRelease`; `abortOnError` is on, baseline `app/lint-baseline.xml`, rules classified in `app/lint.xml`. |
| Error Prone | Runs inside Java compilation (`compile*JavaWithJavac`, plugin `net.ltgt.errorprone`); all findings are warnings, so review new ones in the compiler output. |
| Release APK | `bash scripts/release_pack.sh [--no-sign] [--allow-dirty] [<output.apk>]`; unsigned build, zopfli `zipalign` before signing, then v2 signing with the key from `keystore.properties` (plain `./gradlew assembleRelease` skips the zopfli step). It refuses a tree whose build inputs differ from HEAD (`scripts/release-build-inputs.sh`), so commit first; `--allow-dirty` makes a measurement build that is never released (`artifact.freshness` rejects it only while the tree is still dirty). |
| No-INTERNET and backup check | `bash scripts/check-no-internet.sh [<apk>]`; checks the source manifest, then the built APK (default: debug) with aapt2 for a missing INTERNET permission and no backup or device transfer. |
| Release checker | `bash scripts/release_check.sh [--quick\|--full\|--checks <list>] [<apk>]`; runs the repository gates (JVM, lint, Python, no-INTERNET, asset consistency, text hygiene) plus artifact checks (size, pinned assets, dex profile rules, permissions, single signer, version, localized store changelogs, delta to `dist/`) and prints a `RESULT\|…` block; `--checks <list>` runs only the listed artifact checks (version, store changelog and delta run only without `--checks`); `--full` rebuilds from scratch first and prints a reminder to run `bash scripts/device-perf-ritual.sh --strict` on hardware, since performance budgets are not part of the checker; CI runs `size,asset_pins,emoji_assets,tree_assets,exported_surface,no_secrets,permissions,critical_resources,no_reflection,arsc_stored,dex_layout,profiles,eval_parity` on the unsigned release APK, while signature and freshness stay local; `artifact.freshness` needs a clean tree and an APK built from HEAD or from an ancestor with no build-input change since; the changelog check requires a non-empty `metadata/<locale>/changelogs/<versionCode>.txt` for each of tt, ru-RU and en-US at the version in `app/build.gradle`. |
| Asset consistency | `python3 scripts/rebuild_assets.py --check --allow-known-drift`; compares the bundled dictionaries and bigram tables with their pins without rebuilding; CI runs it as its own step. |
| Provenance check | `python3 scripts/provenance.py`; validates that every file under `app/src/main/assets` and `app/src/test/resources` has exactly one shard in `data/provenance.d/` (revision, size, SHA-256, license, source role, split rule, generation command, path) and that each shard is fresh; missing, duplicate, malformed or stale shards fail. Runs in the python suite. |
| Evaluation splits check | `python3 scripts/evaluation_splits.py data/evaluation/splits-tt.json`; checks the train/development/final assignments and rejects normalized collisions across sentence and bigram-training sources and inside word tables (frequencies, dictionary admissions). The python suite also runs the committed declaration against the live tree. |
| Emulator release validation | `bash scripts/emulator-release-validation.sh --candidate <apk> [--previous <apk>] [--debug-apk <apk>] [--androidtest-apk <apk>] --avd <api=name>... [--plan] [--outdir build/emulator-release-validation]`; one `--avd` mapping per API from minSdk through targetSdk — the script refuses to present a partial matrix as complete release evidence. Per AVD: a signed upgrade from the previous release, the emulator smoke run, the on-device backup/restore and field-switch tests, and a corrupt-Tatar-dictionary quarantine probe. Use disposable 1080x2280 AVDs, because the smoke scenario's key coordinates are calibrated for that display. The debug and androidTest APKs count as candidate evidence only for the same build — the debug package is the candidate id plus a suffix, the androidTest package the debug id plus `.test`, and both carry the candidate's version (the AGP-unversioned instrumentation form is accepted); a mismatch aborts before any instrumentation PASS. Inside the smoke leg a SKIP on the critical `suggest-tt`/`suggest-ru` pixel-delta probes is a FAIL, not a pass; the English suggestion SKIP and the run-as-limited probes stay documented boundaries. `--plan` validates and prints the matrix without adb. Direct Boot before first unlock, lock-screen quick reply and TalkBack stay physical-device evidence: they are explicit SKIP lines and make the overall result INCOMPLETE even when every emulator leg passes. |
| Reproducible build | Two `./gradlew clean assembleRelease --no-build-cache` runs must give byte-identical APKs (CI job `reproducible`), so keep `dependenciesInfo { includeInApk = false }` in `app/build.gradle`. Other builds use the local build cache (`org.gradle.caching` in `gradle.properties`); the release scripts bypass it. |
| Dependency verification | When a key expires or rotates, or dependencies change: `GRADLE_USER_HOME=/tmp/<clean> ./gradlew --write-verification-metadata pgp,sha256 clean test lintRelease assembleRelease -PskipReleaseSigning`, then `./gradlew --export-keys` with the same home, and commit `gradle/verification-metadata.xml` with `gradle/verification-keyring.{gpg,keys}`; a failure on a cold cache means the artifact must be investigated, not re-pinned. |
| Key expiry check | `python3 scripts/key_expiry_check.py`; every trusted key of the verification metadata must be present in the committed keyring with a valid signature date — expired, or expiring within the warning window, fails. The nightly workflow runs it before the cold-cache build, so a key about to break a cold-cache build is caught while there is still time to rotate it. |
| Glide benchmark | `python3 scripts/glide-benchmark.py {make-synthetic,evaluate,freeze,compare}`; replays recorded or synthetic glides through the production JVM decoder (offline Gradle bridge, no device). `make-synthetic` writes the deterministic host fixture, `evaluate --input <records.jsonl> [--manifest <dataset.json> --split development\|final] --output <report.json>` reports per-record outcomes (unmappable targets, endpoint and length pruning, rejection reasons, visible top-3 as the user metric and engine top-4 as diagnostic) with no timestamps or host latencies. Each report's `source_identity` fingerprints what was decoded and by what: `decoder_fingerprint` over every production decoder source (decoder, inventory, prefix index, autocorrect policy, storage contracts and validator), `dictionary_fingerprint` over the bundled dictionaries and `geometry_fingerprint` over the concrete key geometries. Records without recorded geometry decode against the committed reference fixture (`research/glide-evaluation/fixtures/reference-geometry.json`), whose concrete geometry — not a sentinel — is hashed the same way, so a layout change invalidates old reports, and a batch that mixes implicit-reference and foreign recorded geometry for one layout is refused. `freeze` pins whole collection sessions/devices into a development/final manifest (no shared session or device ids; freeze before any tuning) and `compare` diffes two reports on identical ordered inputs and refuses differing dataset, geometry or dictionary fingerprints. Collection rules: `research/glide-evaluation/collection-protocol.md`. Runs in the python suite. |
| CI | `.github/workflows/ci.yml` on every push and pull request (JVM and python tests, lint, asset pins, text hygiene, debug, androidTest and baseline-profile module builds, release APK and the keystore-free release checks) and `.github/workflows/nightly.yml` on a weekly schedule (the key expiry gate, then one cold-cache `./gradlew clean assembleRelease -PskipReleaseSigning` with a fresh `GRADLE_USER_HOME`); every action is pinned to a full-length commit SHA. |
| Baseline profile | `ANDROID_SERIAL=<emulator-serial> ./gradlew :app:generateBaselineProfile` with a running API 34 emulator; the two collection passes write both tracked files to `app/src/main/generated/baselineProfiles/` (`mergeIntoMain` in `app/build.gradle`), which every build reads — the minimal startup journey feeds both `startup-prof.txt` (the dex layout input) and `baseline-prof.txt`, the full journey set feeds `baseline-prof.txt` only, so the startup rule set stays a strict subset of the baseline one (`scripts/check_profiles.py` gates both). |

Device quirks:

- HyperOS silently switches the system keyboard when the package that owns the default IME is
  force-stopped. Do not force-stop it in test loops; the scripts use `run-as <pkg> kill -9`, or,
  for a release package, select another keyboard, go home (`SetupActivity` runs in the IME
  process, and `am kill` spares a process with a visible activity), then `am kill <pkg>`.
- The baseline profile generator refuses physical devices unless the instrumentation argument
  `ttAllowPhysicalDevice=true` is set.
- Emulator: `<sdk>/emulator/emulator -avd tt_suggest_a14 -no-window &`, `adb` in
  `<sdk>/platform-tools/`, where `<sdk>` is `sdk.dir` from `local.properties`.

## Mandatory gates

After any change to code or resources, all of these must pass:

- `./gradlew test calibrationTest`
- `bash scripts/run_python_tests.sh`
- `./gradlew lintRelease`
- `bash scripts/check-no-internet.sh` on the source and on the built APK
- release APK size within `APK_SIZE_LIMIT` in `scripts/release_check.sh`
- `python3 scripts/text_hygiene_check.py`

## Hard constraints

- Zero third-party runtime dependencies. Build-time tools (lint, Error Prone, Macrobenchmark) are
  allowed but must not end up in the APK.
- No `android.permission.INTERNET`; CI checks the manifest and the built APKs.
- No NDK/C++. No Compose in the IME process. No proprietary fonts, icons or sounds from other platforms.
- Locales and layouts: tt, ru and en only (`resConfigs "tt", "ru", "en"`).
- Out of scope: voice input and clipboard history.

## Assets and pins

Bundled dictionaries (`*.tdict.zlib`), bigram tables (`*.tatbigr.zlib`) and cold-companion word
lists (`*.tcomp.zlib`) are built by the python pipeline, never by hand;
`scripts/rebuild_assets.py` is the single entry point. Pinned size and SHA-256 live in
`DictionaryStorageContracts.kt`, `BigramStorageContracts.kt` and
`CompanionListStorageContracts.kt`; emoji assets are pinned in the python tests under
`tests/emoji_*`. An asset change without recomputed pins fails the tests.
`docs/ASSET-PIPELINE.md` covers the build, the pins and known drift; `docs/ASSET-FORMATS.md`
covers the binary formats.

## Documents and commits

- Git is the record. Documents describe the current state and are rewritten, not appended to; a
  finished plan or report is deleted in the same change.
- The repository is public: it holds no plans, task lists, release paperwork or store-submission
  notes. Live documents sit in `docs/`, indexed in `docs/README.md`.
- Commits are written only by the operator, in Russian, split by meaning. No co-author trailers.
- Push, external tags and publishing happen only on the operator's explicit command.
- `dist/` is local and never committed. `keystore.properties` and the release `.jks` are secrets
  and never enter git.

## Writing rules

These apply to all comments (code, tests, scripts, build files, CI, resources, manifest) and to all
Markdown documents.

1. A comment explains the code, not its history: no dates, release numbers, audit or finding
   numbers, mission/phase/item codes, no workflow words (`agent`, `handoff`, `uncommitted`) and
   no mention of who made a change. History belongs in git.
2. No links from code to `docs/*.md`; state the rule in the comment. The exception is a path the
   code reads at runtime or build time.
3. Length: class docs ≤ 8 lines, member docs ≤ 3 lines, unless the text documents a binary format,
   an algorithm or a platform quirk that cannot be read from the code.
4. One explanation, one place. Replace duplicates with `See [Canonical]`.
5. No numbers that drift in prose: no test counts, APK sizes, SHA-256 values, p95 timings or rule
   counts. Real pins live in constants and assertions.
6. Plain wording, no rhetoric. "Fail-closed" only where it literally describes failure behavior.
7. Canonical English everywhere except the root `README.md` (which stays bilingual) and
   `PRIVACY.ru.md` (the Russian privacy policy for the RuStore listing): plain technical English,
   US spelling, the terms below, no calques from Russian. Tatar and Russian appear only as quoted
   language data (`сәләм`, `ә`) or proper names. Not covered: localized strings in
   `res/values-{ru,tt}/`, store texts in `metadata/{ru-RU,tt}/`, test data, and commit messages.
8. Upstream text stays: AOSP and Simple Keyboard license headers, javadoc and TODOs are not edited.
9. A document has an owner and a lifetime. A finished plan or report is deleted in the change that
   finishes it. Live documents carry no gate tables and no run logs.

### Terminology

The Russian source terms in the right column are quoted language data; do not translate them
literally.

| Use | Avoid | Russian source term |
|---|---|---|
| suggestion strip | suggestion band, strip band | `полоса подсказок` |
| cell (of the strip) | slot, box | `ячейка` |
| typed-word cell | keep-typed cell | `ячейка «оставить как есть»` |
| word completion | prefix suggestion | `подсказка по префиксу` |
| next-word prediction | successor suggestion | `предсказание следующего слова` |
| word form | wordform, paradigm form | `словоформа` |
| autocorrect / autocorrection | auto-replacement | `автозамена` |
| undo autocorrect | revert window | `откат автозамены` |
| typo recovery; edit class | fuzzy class, typo class | `исправление опечаток`; `класс правок` |
| glide typing | swipe input, gesture typing | `набор свайпом` |
| personal dictionary | user dictionary, personal store | `личный словарь` |
| learned word pairs | personal bigrams | `личные пары` |
| learned emoji | personal emoji co-usage | `выученные эмодзи` |
| pause learning (incognito) | incognito mode | `пауза обучения / инкогнито` |
| quarantined file | quarantine card | `карантин` |
| emoji panel | emoji keyboard | `панель эмодзи` |
| layout | keyboard layout set (except in code identifiers) | `раскладка` |
| language (tt, ru, en) | subtype (except in code identifiers) | `язык / subtype` |
| bundled dictionary / bigram table | asset (outside the pipeline context) | `словарь / таблица биграмм` |
| pinned size and SHA-256 | pin (unexplained) | `пин` |
| release gate / check | gate (unexplained), ritual | `гейт, ритуал` |
| device test | UAT on hardware, device leg | `девайсная проверка` |
| fails without writing output | fail-closed (when only this is meant) | fail-closed |

## Code structure

Sources are under `app/src/main/java/rkr/simplekeyboard/inputmethod/`.

- `latin/LatinIME.java`: the `InputMethodService`, entry point of the IME.
- `keyboard/` (Java): keyboard view, `PointerTracker`, `KeyDetector`.
- `latin/suggestions/`, `latin/dictionary/`, `latin/glide/`, `latin/emoji/` (Kotlin): suggestion
  strip, bundled and personal dictionaries, glide typing, emoji panel.
- `latin/settings/`: settings screens.
- `app/src/test`: JVM tests. `app/src/androidTest`: device tests. `baselineprofile/`: profile
  generator.
- `scripts/`: python asset pipeline (stdlib only) and release scripts; `tests/`: their unittests.
  `research/corpus/`: corpus measurement scripts and manifests (OPUS data is not committed for
  licensing reasons). `research/corpus-sources/`: licensed-corpus fetch and comparison tooling;
  `research/glide-evaluation/`: the gesture collection protocol, frozen benchmark manifests and
  reports.
