# AGENTS.md — guide for agent sessions

Offline Android keyboard with a Tatar layout. It is a fork of Simple Keyboard (itself based on
AOSP LatinIME): legacy Java code plus new Kotlin code. Application id `org.tatarkeyboard.ime`,
source namespace `rkr.simplekeyboard.inputmethod`. Work happens on branch `main`.

Current state: `HANDOFF.md`. Documentation index: `docs/README.md`. Code map: `docs/ARCHITECTURE.md`.

## Commands

Run everything from the repository root. On macOS the shell scripts need GNU coreutils and GNU
grep first in `PATH` (they use `stat -c`, `sha256sum` and `grep -P`):
`PATH="$(brew --prefix coreutils)/libexec/gnubin:$(brew --prefix grep)/libexec/gnubin:$PATH"`.

| Task | Command and note |
|---|---|
| JVM tests | `./gradlew test` (`--rerun-tasks` forces a full rerun); JUnit 4, no Robolectric. |
| Python tests | `for f in tests/*/test_*.py; do python3 "$f" \|\| exit 1; done`; plain `unittest` modules, pytest is not used. |
| Device tests | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r` both APKs, then `adb shell am instrument -w -e class <test class> org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner`; timing assertions need an idle device with the screen on, and `GlideUiDeviceTest` is calibrated for a 720x1640 screen and fails fast on any other. Full procedure: `docs/DEVICE-TEST-PLAN.md`. |
| Emulator smoke test | `bash scripts/emulator-smoke.sh [--avd tt_suggest_a14] [--apk <path>] [--no-boot] [--outdir build/emulator-smoke/]`; installs and selects the IME, runs the typing, suggestion and emoji probes and prints `RESULT\|…` lines. |
| Device performance check | `bash scripts/device-perf-ritual.sh [--serial <id>] [--pkg org.tatarkeyboard.ime.debug] [--legs cold,pss,frames,warm,touch] [--enable-suggestions-ui] [--outdir build/device-perf-ritual]`; measures cold start, PSS, frame times, janky frames, warm show and touch handling on a 720x1640 device, prints `over_budget=` on each budgeted line and restores the device state on exit; `--enable-suggestions-ui` turns suggestions on in a release build through its settings screen. |
| Lint | `./gradlew lintRelease`; `abortOnError` is on, baseline `app/lint-baseline.xml`, rule classification `app/lint.xml`. |
| Error Prone | Runs inside Java compilation (`compile*JavaWithJavac`, plugin `net.ltgt.errorprone`); all findings are warnings, so review new ones in the compiler output. |
| Release APK | `bash scripts/release_pack.sh [--no-sign] [<output.apk>]`; unsigned build, zopfli `zipalign` before signing, then v2 signing with the key from `keystore.properties` (plain `./gradlew assembleRelease` skips the zopfli step). |
| No-INTERNET and backup check | `bash scripts/check-no-internet.sh [<apk>]`; checks the source manifest, then the built APK (default: debug) with aapt2 for no INTERNET permission and no backup or device transfer. |
| Release checker | `bash scripts/release_check.sh [--quick\|--full\|--checks <list>] [<apk>]`; runs the repository gates plus artifact checks (size, pinned assets, permissions, single signer, version, store changelog, delta to `dist/`) and prints a `RESULT\|…` block; `--checks exported_surface,no_secrets` runs only those, as CI does on the unsigned release APK. |
| Asset consistency | `python3 scripts/rebuild_assets.py --check --allow-known-drift`; compares the bundled dictionaries and bigram tables with their pins without rebuilding. |
| Reproducible build | Two `./gradlew clean assembleRelease --no-build-cache` runs must give byte-identical APKs (CI job `reproducible`), so keep `dependenciesInfo { includeInApk = false }` in `app/build.gradle`. Other builds use the local build cache (`org.gradle.caching` in `gradle.properties`); the release scripts bypass it. |
| Dependency verification | When a key expires or rotates, or dependencies change: `GRADLE_USER_HOME=/tmp/<clean> ./gradlew --write-verification-metadata pgp,sha256 clean test lintRelease assembleRelease -PskipReleaseSigning`, then `./gradlew --export-keys` with the same home, and commit `gradle/verification-metadata.xml` with `gradle/verification-keyring.{gpg,keys}`; a failure on a cold cache means the artifact must be investigated, not re-pinned. |
| Baseline profile | `./gradlew :app:generateBaselineProfile` with a running emulator (`ANDROID_SERIAL` pins one); the run writes the baseline and the startup profile to the tracked `app/src/main/generated/baselineProfiles/` (`mergeIntoMain` in `app/build.gradle`), which every build reads; the startup profile gives the startup `classes.dex` plus `classes2.dex`. |

Device quirks:

- HyperOS silently switches the system keyboard when the package that owns the default IME is
  force-stopped. Do not force-stop it in test loops; the scripts use `run-as <pkg> kill -9`, or,
  for a release package, select another keyboard and then `am kill <pkg>`.
- The baseline profile generator refuses physical devices unless the instrumentation argument
  `ttAllowPhysicalDevice=true` is set.
- Emulator: `<sdk>/emulator/emulator -avd tt_suggest_a14 -no-window &`, `adb` in
  `<sdk>/platform-tools/`, where `<sdk>` is `sdk.dir` from `local.properties`.

## Mandatory gates

After any change to code or resources, all of these must pass:

- `./gradlew test`
- the python test loop above
- `./gradlew lintRelease`
- `bash scripts/check-no-internet.sh` on the source and on the built APK
- release APK size ≤ 3 145 728 B (checked by `scripts/release_check.sh`)
- `python3 scripts/text_hygiene_check.py`

## Hard constraints

- Zero third-party runtime dependencies. Build-time tools (lint, Error Prone, Macrobenchmark) are
  allowed but must not end up in the APK.
- No `android.permission.INTERNET`; CI checks the manifest and the built APKs.
- No NDK/C++. No Compose in the IME process. No Apple fonts, icons or sounds.
- Budgets: APK ≤ 3 MiB, cold start < 400 ms, zero allocations in the draw loop
  (`docs/PERF-BUDGETS.md`).
- Locales and layouts: tt, ru and en only (`resConfigs "tt", "ru", "en"`).

## Assets and pins

Bundled dictionaries (`*.tdict.zlib`) and bigram tables (`*.tatbigr.zlib`) are built by the
python pipeline, never by hand; `scripts/rebuild_assets.py` is the single entry point. Their
pinned size and SHA-256 live in `DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`;
emoji assets are pinned in the python tests under `tests/emoji_*`. An asset change
without recomputed pins fails the tests. Build, pins and known drift: `docs/ASSET-PIPELINE.md`;
binary formats: `docs/ASSET-FORMATS.md`.

## Documents and commits

- Git is the record. Documents describe the current state and are rewritten, not appended to; a
  finished plan or report is deleted in the same change (list it in `docs/HISTORY.md`).
- Live documents sit in `docs/`, indexed in `docs/README.md`; `HANDOFF.md` holds the current
  state of the project.
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
7. Canonical English everywhere except the root `README.md` (which stays bilingual): plain
   technical English, US spelling, the terms below, no calques from Russian. Tatar and Russian
   appear only as quoted language data (`сәләм`, `ә`) or proper names. Not covered: localized
   strings in `res/values-{ru,tt}/`, store texts in `metadata/{ru-RU,tt}/`, test data, and commit
   messages.
8. Upstream text stays: AOSP and Simple Keyboard license headers, javadoc and TODOs are not edited.
9. A document has an owner and a lifetime. A finished plan or report is deleted in the change that
   finishes it. Live documents carry no gate tables and no run logs.

### Terminology

The right column lists the Russian source terms (quoted language data), which should not be
translated literally.

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
  licensing reasons).
