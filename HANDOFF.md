# Current state

This file is rewritten, not appended to; history lives in git.

## Release

The current release is **3.8.0** (versionCode 45). It ships:

- glide spacing: a leading space after punctuation, a closing quote or a typed word, a space
  before a letter typed right after a glided word, and sentence caps after a period;
- glide aliases for `ъ` and `ё`, and doubled letters without a loop when the word has no
  single-letter twin;
- refused-glide feedback (a tick);
- long-press digits on the top row;
- the double-space period only in general text fields;
- the BACK-and-refocus glide fix;
- haptics without per-press allocation;
- the pressed key drawn over the cached board;
- the startup dex layout from the tracked profiles;
- a release-only `<memory-budget>`.

The bundled dictionaries, bigram tables and emoji data are unchanged from 3.7.0, so an update
re-inflates nothing. User-facing notes are in `CHANGELOG.md` and `metadata/*/changelogs/45.txt`.
The English GitHub Release notes with the filled release record are in
`dist/release-notes-3.8.0.md` on the packing machine.

The signed APK was packed on the tree of the release commit before that commit existed, so its
`META-INF/version-control-info.textproto` names the parent commit. To rebuild it byte for byte,
see "Reproducing a published APK" in `docs/PUBLISH-CHECKLIST.md`.

## State of `main`

`main` is the 3.8.0 release; nothing is unreleased. Code-level notes on what 3.8.0 changed:

- Glide commit: `InputLogic.commitGlideWord` takes the trailing word and cursor captured at the
  gesture, refuses a stale or unknown cache and prepends the space
  (`TatarWordUtils.glideNeedsLeadingSpace`). The phantom space is `InputLogic.mPhantomSpaceCursor`.
  A refusal ticks; the decoded words stay in the strip only when the text changed.
- Cache: a hide that keeps the input session keeps the expected selection
  (`RichInputConnection.clearTextCaches`), so the next show re-reads the text from the editor.
- Glide geometry: `GlideKeyGeometry.ALIAS_BASES` and the alias tables;
  `GlideWordIndex.isTwinlessAt`. The glide calibration set is re-pinned.
- Layout: a `<switch>` on `showNumberRow` in `rowkeys_{tatar,russian}1.xml`;
  `scripts/typo_pack.py` reads its `<default>` branch.
- Double-space period: `InputAttributes.mIsGeneralTextInput`.
- Feedback: one `HandlerThread` with prebuilt `Runnable`s in `AudioAndHapticFeedbackManager`.
- Drawing: `KeyboardView` keeps released keys in the board bitmap and paints pressed keys on top
  (`onDrawPressedKeys`, `invalidatePressState`).
- Build:
  - AGP 9.4.1, compileSdk on android-37.2;
  - profiles in `app/src/main/generated/baselineProfiles/` (`mergeIntoMain`);
  - `<memory-budget>` in `app/src/release/AndroidManifest.xml`, kept equal to the release PSS
    ceiling by `MemoryBudgetManifestSourceContractTest`;
  - `artifact.dex_layout` in `scripts/release_check.sh`;
  - the exported-surface and no-secrets checks in CI;
  - the corpus SHA-256 manifest `data/corpus-manifest.json`.
- Device script: the release PSS ceiling and the `warm`, `touch` and janky-frame legs of
  `scripts/device-perf-ritual.sh`; `--enable-suggestions-ui` turns suggestions on in a release
  build through its settings screen.

Also true of the current tree:

- The golden vectors of `GlideGoldenExportTest` (geometry with aliases, word-index digests, set
  identities, decodes) and the context `"ул китте\n"` of the suggestion exporter changed;
  re-export them when the parity suite on the other platform is next synced.
- Typo recovery as shipped: the Tatar engine runs edit classes #1 (long-press partner) and #4
  (single substitution); the Russian engine runs class #1 only.

Verified for 3.8.0:

- `release_check.sh --full` and `--quick` on the signed APK, `check-no-internet.sh` on the signed
  APK, two byte-identical packs, and `text_hygiene_check.py`.
- On the reference device:
  - the instrumentation tests;
  - stages 3–5 of `docs/DEVICE-TEST-PLAN.md` and zero network traffic;
  - release cold start against 3.7.0 (the split dex layout is not slower);
  - PSS, frame time, janky frames, warm show and touch handling within their budgets;
  - one vibration per press;
  - the lock-screen quick reply and gesture navigation.
- On an API 28 emulator and an emulator tablet profile: the smoke test.

Not checked on a device: the long-press popup border and the emoji-panel title inset (the device
disconnected before their final screenshots).

## State of the `improvement` branch

The branch holds the measurement foundation of the improvement program (`docs/ROADMAP.md`
section 2), all gates green:

- Eval harness: the full next-word chain is measured (bigrams + after-word forms + fallback)
  with top-1/top-3 rates, sentence-level bootstrap CIs, lemma stratification and a
  keystroke-savings simulator, cross-pinned between `scripts/suggest_eval.py` (chain mirror in
  `scripts/suggest_chain.py`) and `TtSuggestEvalTest`. The Tatar eval set is decontaminated
  against all training corpora (`scripts/make_eval_set.py` verifies the Leipzig inputs against
  `data/corpus-manifest.json`); the Russian held-out set exists (`scripts/make_ru_eval_set.py`,
  `RuSuggestEvalTest`). The typo-mutated held-out set (`scripts/typo_eval_pack.py`,
  `TypoMutatedEvalTest`) pins the per-class recovery baseline and a zero autocorrect
  false-trigger rate.
- Glide: the synthetic generator is recalibrated against the FUTO real-gesture corpus
  (`research/corpus/futo_glide_analysis.py`; data stays outside git), all three pin sites
  updated; `FutoRealGestureDiagnosticTest` (inert without `FUTO_EVAL_FILE`) decodes the real
  validation slice at the published anchor's accuracy class.
- Device observability: `scripts/device-perf-ritual.sh` gained the `suggest` leg (round-trip
  budget in `docs/PERF-BUDGETS.md`), the pss anon/file split, and the opt-in `battery`,
  `uimode` and `fontscale` legs. None of them has run on hardware yet (`docs/ROADMAP.md`
  section 3).
- Dev loop: calibration suites run in `./gradlew calibrationTest` (CI and
  `scripts/release_check.sh` run `test calibrationTest` together); the python tests run in
  parallel via `scripts/run_python_tests.sh`; the CI reproducible job packs one unsigned build
  twice (`release_pack.sh --from-apk`).
- The training corpora the asset pipeline expects in `~/corpora-leipzig` (Leipzig tt/ru and
  both conv-train streams) are present and manifest-verified on this machine.

## Open release steps

These are manual and have not been confirmed as done:

- **3.7.0:** the GitHub Release, the store upload with `changelogs/44.txt` and the IzzyOnDroid
  note, if still pending.
- **3.8.0:**
  - operator commits, then the tag `v3.8.0` (`docs/PUBLISH-CHECKLIST.md`, step 5);
  - the **GitHub Release** through the web UI: tag `v3.8.0`, title `Tatar Keyboard 3.8.0`, notes
    from `dist/release-notes-3.8.0.md`; attach `dist/tatar-keyboard-3.8.0.apk` and
    `dist/release-check-3.8.0.txt` from the packing machine, not a rebuild;
  - the **store upload** with `metadata/{en-US,ru-RU,tt}/changelogs/45.txt`.

## Known risks and open items

See `docs/ROADMAP.md`: the improvement program built from `research/README.md`, and the
device checks that need a person or hardware not at hand (live Direct Boot, Telegram,
TalkBack by ear, tablet hardware). The glide context rerank decision is closed (threshold
confirmed; see `docs/BACKLOG.md`).

## Where to look next

- `docs/README.md` — index of all documents.
- `docs/ROADMAP.md` — mandatory development plan, in order.
- `docs/ARCHITECTURE.md` — input path, suggestion engine, threads and stores.
- `AGENTS.md` — build, test and release commands, hard constraints.
