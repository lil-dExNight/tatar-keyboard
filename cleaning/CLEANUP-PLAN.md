# Text and repository cleanup — work plan

Status: approved, 2026-09-30 (decisions in Section 4). Baseline: `main` at `842fb4ca`, release 3.6.0 (versionCode 43).
Scope: the Android repository only. The iOS port is out of scope, except that nothing it depends on
(the golden exporters) may be broken.
Inputs: the 21 area reports in `cleaning/01…21-*.md` and the summary `cleaning/00-SUMMARY.md`.

## 1. Goal

Make the repository readable by someone who was not part of the "mission" history:

- code comments say what the code does and why, in one or two sentences;
- every comment and every document is in canonical English; the only exception is the root
  `README.md` (see rule 7);
- live documentation is short and current;
- the git tree holds source, build inputs and a small set of living docs — not run evidence,
  screenshots, or generated reports;
- nothing in the tree claims something the code no longer does.

Behavior of the app must not change. The only exceptions are the dead-code removals in Phase 4,
each of which is gated on an explicit decision and keeps all tests green.

## 2. Baseline metrics

Measured on `842fb4ca` with the commands in Appendix C.

| Metric | Baseline | Target |
|---|---|---|
| `docs/*.md` references in `app/src/main` comments | 312 | 0 |
| … in `app/src/test`, `app/src/androidTest`, `baselineprofile` | 190 | 0 |
| … in `scripts/`, `research/corpus/` | 90 | 0 (paths that code actually opens are exempt) |
| … in `app/src/main/res` | 18 | 0 |
| Referenced doc paths that no longer exist | 20 | 0 |
| Dated footnotes (`20YY-MM-DD`) in `app/src/main` | 202 | 0 (data pins exempt) |
| … in tests | 200 | 0 (data pins exempt) |
| … in `scripts/`, `research/corpus/` | 121 | 0 (data pins exempt) |
| `fail-closed` occurrences (code + docs) | 424 | used only where it describes actual failure behavior |
| Top-level `docs/*.md` files | 79 | ≤ 10 |
| Tracked files under `docs/` | 651 (≈ 46 MB) | ≤ 20 files, < 1 MB |
| Tracked PNG / TSV under `docs/` | 213 / 215 | 0 / 0 |
| `HANDOFF.md` | 2 254 lines | ≤ 60 lines |
| `docs/PUBLISH-CHECKLIST.md` | 1 939 lines | ≤ 120 lines |
| `docs/README.md` (docs index) | 515 lines | ≤ 60 lines |
| `CHANGELOG.md` | 915 lines, two languages | English, user-facing entries only |
| Comment lines containing Cyrillic, `app/src/main/java` | 239 | only quoted language data (Tatar/Russian example words) |
| … in `app/src/test`, `app/src/androidTest`, `baselineprofile` | 428 | same |
| … XML comments in `app/src/main/res` | 101 | 0 |
| … `#` lines in `scripts/`, `research/corpus/`, `tests/`, `.github/` | 786 | same as main |
| Tracked `*.md` files other than root `README.md` containing Cyrillic | 153 files / 27 640 lines | 0 files, except quoted language data |

Removing files from the tree does not shrink `.git`. History rewriting is explicitly out of scope.

## 3. Writing rules (target state)

These rules drive every phase and are added to `AGENTS.md` in Phase 7.

1. **A comment explains the code, not its history.** No dates, no release numbers, no audit or
   finding numbers, no mission, phase or item codes (`F3`, `S8`, `P7-2`, `E5c`, `O7`, `T2`,
   `SIZE-3`…), no "operator", "agent", "handoff", "uncommitted". History belongs in git.
2. **No links from code to `docs/*.md`.** If a rule matters, state it in the comment. The exceptions
   are paths the code actually reads at runtime or build time.
3. **Length.** Class docs ≤ 8 lines, member docs ≤ 3 lines, unless the text documents a binary
   format, an algorithm, or a platform quirk that cannot be read from the code.
4. **One explanation, one place.** Duplicates are replaced by `See [Canonical]`.
5. **No numbers that drift.** No test counts, APK sizes, SHA-256 values, p95 timings or rule counts
   in prose. Real pins live in constants and assertions.
6. **Plain wording.** No rhetoric ("earned its keep", "centerpiece", "not a promise but a
   verifiable property"). "Fail-closed" only where it literally describes failure behavior.
7. **Language: canonical English everywhere, except the root `README.md`.** This covers:
   - all comments, javadoc/KDoc, docstrings and XML comments in code, tests, scripts, build files,
     CI workflows, resources and the manifest;
   - every Markdown document, including `AGENTS.md`, `BRIEF.md`, `HANDOFF.md`, `CHANGELOG.md`,
     `PRIVACY.md`, `SECURITY.md` and everything under `docs/`.

   "Canonical English" means plain technical English with US spelling (matching the upstream AOSP
   code), consistent terminology (Appendix D) and no literal calques from Russian. Tatar and Russian
   words may appear only as quoted language data (`сәләм`, `ә`) or as proper names.

   Not covered by this rule: the root `README.md` (keeps its current bilingual form), localized UI
   strings in `res/values-{ru,tt}/`, localized store listings and changelogs in `metadata/{ru-RU,tt}/`,
   and test data. Commit messages are not documents either; the current `AGENTS.md` rule (Russian)
   stays unless the operator changes it separately.
8. **Upstream text stays.** AOSP / Simple Keyboard license headers, javadoc and TODOs are not edited
   by this plan.
9. **Documents have an owner and a lifetime.** A plan or report that is finished is deleted or
   archived in the same change that finishes it. Live documents carry no gate tables and no run logs.

## 4. Decisions

All eight decisions were taken by the operator on 2026-09-30.

| ID | Question | Decision |
|---|---|---|
| D1 | The `AGENTS.md` rule "history and finished reports are not rewritten" | **Dropped.** Git is the record. Rule 9 replaces it. |
| D2 | Fate of closed reports, plans, audits and `docs/archive/` | **Delete.** History stays in git. `docs/HISTORY.md` lists each removed document with a one-line description and the last commit that contains it (Appendix B). |
| D3 | Golden-vector exporters `app/src/test/.../latin/golden/` (feed the iOS parity suite) | **Keep.** Clean their comments only (WP5.3). |
| D4 | Fuzzy edit classes #2/#3 (`generateGeometricVariants`, `generateTranspositionVariants`, geometric part of `KeyNeighborTable`), used by no shipped policy | **Delete**, together with the `collectFuzzy` branches and their tests (WP4.3). |
| D5a | Class #5 generator in `scripts/typo_pack.py` | **Delete** (WP4.4). |
| D5b | Phase B tests `TtTypoPhaseBCalibrationTest`, `TtTypoPhaseBPrecisionTest` | **Delete** (WP4.4). After D4 they have nothing to measure. |
| D5c | `scripts/suggest_eval.py`, `scripts/wordform_kaikki_check.py`, `scripts/bigram_extra_heads_conv.py` | **Keep**, with docstrings shortened (WP5.4). |
| D6 | Language of comments and documents | **Canonical English for all comments and documents, except the root `README.md`** (rule 7). Consequence: `PRIVACY.md` loses its Russian half. The app links to it (`privacy_policy_url` in `res/values/strings-appname.xml`), so Russian- and Tatar-speaking users will read the policy in English. A localized privacy summary can live in the store listings in `metadata/` if needed. |
| D7 | Published store changelogs `metadata/*/changelogs/*.txt` with developer jargon | **Rewrite all of them** in plain user-facing language (WP6.10). The `ru-RU` and `tt` files stay in their languages; they are localized store content, not documentation. |
| D8 | `research/*.md` (pre-implementation research, 2026-07-18) | **Delete**, after moving the facts still cited by live docs into `BRIEF.md` and `PERF-BUDGETS.md`: the performance budgets (from `03`), the palette and style limits (from `04`), and the Tatar letter frequency behind the fifth row (from `05`). The `research/corpus/` scripts stay. |

## 5. Constraints and hazards

These are verified facts that shape the order of work. Several of them correct the area reports.

**H1. Source-contract tests that cut files at comment text.** Most source-contract tests pin code
identifiers and are safe for comment edits. The exceptions:

| Test | Delimiter in `app/src/main` |
|---|---|
| `RichInputConnectionRobustnessContractTest:273` | `/**\n * 2026-09-25 audit, F1` in `RichInputConnection.java` |
| `InputConnectionBinderContractTest:131` | `/**\n * 2026-09-25 audit, F10` in `RichInputConnection.java` |
| `SuggestionStripSourceContractTest` (`commitPathRefusesToReplaceAWordTheCursorSitsInside`) | comment text `Do not log the returned value` in `RichInputConnection.java` (lines 549, 573) |
| `RichInputConnectionRobustnessContractTest:275` | `Set the selection` (upstream javadoc) |
| `CommitPathConnectionContractTest:55,69`, `InputConnectionBinderContractTest:180,199`, `BatchEditPairingContractTest:150`, 3 tests on `Allocation-free suffix test` | first lines of five javadoc blocks in `InputLogic.java` |

Rule: in the first commit of Phase 3, re-anchor these tests on code (method signatures). Only after
that may the comments change.

**H2. Tokens that must never appear in comments.** Tests scan whole files for absence:
`measureText(` and `HashSet` in `KeyboardView.java`; `Log.`, `println`, `System.out`, `java.net.` in
`latin/emoji/*.kt`; `RecentEmojiStore`, `RecentEmojiList`, `noBackupFilesDir`, `deserialize`,
`currentRecents` in `SettingsHostActivity.kt`; `data class`, `createDeviceProtectedStorageContext`,
`android.util.Log`, `DeviceProtectedDirectoryProvider` in `personalstore/`; the string
`androidx.customview:customview` in `app/build.gradle`.

**H3. Load-bearing files inside `docs/archive/`.** Report 11 calls the archive TSVs safe to delete.
That is wrong for these files:

| File | Read by |
|---|---|
| `docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv` | `E3aRecoveryCalibrationTest`, `E3bRecoveryCalibrationTest`, `RealDictionaryPrefixIndexTest`, `DictionaryIoStrategyCalibrationTest` |
| `docs/archive/dictionary/dict-accept/accepted-{ru,tt}.tsv`, `conv-freq-{ru,tt}.tsv` | `scripts/dict_accept.py`, imported by `scripts/rebuild_assets.py` (dictionary rebuild input) |
| `docs/archive/dictionary/DICTIONARY-{RU,TT}-CONV-REVIEW.tsv` | `scripts/dict_accept.py`, `scripts/review_batches.py`, `research/corpus/run_review.sh` |
| `docs/archive/dictionary/review-batches/` | default output directory of `scripts/review_batches.py` |
| `docs/corpus-conversational/evidence/apply_rule_tt.py` | cited as the generator in the header of `scripts/bigram_extra_heads_tat.txt:26` |

These move to a data directory before any deletion (WP2.1).

**H4. `CHANGELOG.md` is not a release gate.** `release_check.sh` checks
`metadata/en-US/changelogs/<versionCode>.txt`, not `CHANGELOG.md`. Report 03 says otherwise.
Rewriting `CHANGELOG.md` has no gate risk.

**H5. 20 doc paths cited in code are already broken.** They point to reports that were moved to
`docs/archive/` earlier (e.g. `docs/DICTIONARY-E3.md`, `docs/SYMBOL-KEY-EDGE-FIX.md`,
`docs/CORPUS-OS.md`). Removing doc links from code (Phase 3/5) has to happen before closed docs are
deleted (Phase 6), or the number of broken links grows from 20 to several hundred.

**H6. Comment edits change line numbers.** Release dex output may differ, so byte-identity of the
APK is not a valid check. Use tests, lint and an APK-size check (Section 7).

**H7. `startup-prof.txt` is byte-identical to `baseline-prof.txt`.** Both are consumed by AGP. Keep
both; fix only the `AGENTS.md` sentence that claims they differ.

**H8. Translating to English is safe for source-contract tests, with one caveat.** No test found
pins Russian comment text in `app/src/main`. Tests with Cyrillic literals check localized resources
or rendered UI (for example, `PersonalDictionaryScreenSourceContractTest:208`), and those resources
are exempt. Some tests do the opposite and scan for the absence of Cyrillic
(`E3bEngineSourceContractTest` for `dictionary/engine/`, `GlideSourceContractTest` for `glide/`), so
translation only helps them. The caveat: data files with a comment header whose SHA-256 is pinned
(for example `scripts/wordform_exceptions_tat.tsv`) need the pin recomputed when the header is
translated. Run `rebuild_assets.py --check` after each such edit.

## 6. Phases and work packages

Each work package (WP) is one reviewable commit or a small series. The operator commits and uses
Russian commit messages, as the project already does.

### Phase 0 — Preparation

**WP0.1 Branch and baseline.** Create a working branch. Run the full gate set (Section 7) and
save the output and the Appendix C metrics as `cleaning/baseline.txt`.
Done when: gates are green on the branch and the baseline numbers are recorded.

**WP0.2 Decisions.** Done: D1–D8 are recorded in Section 4.

### Phase 1 — Factual corrections (P1, small, no risk)

**WP1.1 False statements in code.**

| Location | Fix |
|---|---|
| `keyboard/KeyboardActionListener.java:88` | drop "P7-3 wires the real receiver… nothing commits until then"; the receiver is live |
| `latin/suggestions/GlideKeyGeometryBuilder.kt:30` | drop "nothing calls this yet" (called from `LatinIME.java:1606`) |
| `latin/dictionary/personalstore/AndroidPersonalDictionaryStorage.kt:44` | drop "Dormant… nothing in the live IME constructs this" |
| `latin/InputAttributes.java:60-63` | the javadoc about the "floating gesture preview" sits on `mInputType` and does not describe it; remove it |
| `latin/glide/GlideGestureDecider.kt:124` | remove the dangling KDoc with no member |
| `keyboard/KeyboardSwitcher.java:63` | replace the Cyrillic "Р-1" with a plain description |
| `scripts/rebuild_assets.py:353` | "four-cell strip is back" → the 4 is successors per head, the strip has 3 cells |
| `scripts/bigram_asset_pack.py:2` | "schema-2 packer" → "schema-3 packer" |

**WP1.2 False statements in docs.**

| Location | Fix |
|---|---|
| `PRIVACY.md:30,107` | bigram table size 135 889 → 170 471 bytes (pin in `BigramStorageContracts.kt:157`) |
| `AGENTS.md:11` vs `:110` | remove both test counts (rule 5) |
| `AGENTS.md` (baseline-profile row) | drop the claim that the startup profile has rules the baseline lacks |
| `BRIEF.md:17` | compileSdk 36 → 37 |
| `BRIEF.md` "not in MVP" section | swipe input is marked "excluded from plans" but ships as glide typing; correct it |
| `HANDOFF.md:1` | "UNCOMMITTED" for a released wave (superseded by WP6.1, fix now anyway) |
| `docs/README.md:328,353,367` | three "Uncommitted" labels for work released in 2.0.0/2.0.1 |
| `docs/THREAT-MODEL.md:3-4,106,109,111,112`, §6 | "planned / lands as S2–S8" → implemented |
| `docs/PERF-BUDGETS.md:17` | APK size row tied to 3.4.0; restate without a version-bound number |
| `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md:242` | leaked foreign path `/home/tarchok/...` (the file is deleted in Phase 6; remove the path now if Phase 6 is delayed) |

**WP1.3 Duplicate and orphan files.** Delete `images/screenshot-0.png` (identical to
`metadata/en-US/images/phoneScreenshots/1.png`), `icons/play_feature.png` (identical to
`metadata/en-US/images/featureGraphic.png`), `research/corpus/tt_corpora.json` and
`research/corpus/ru_corpora.json` (0 references).

Done when: every row is fixed, and `grep` confirms the removed files have no references. Gates: JVM + python.

### Phase 2 — Repository hygiene

**WP2.1 Relocate load-bearing data out of `docs/`.** Create `data/` at the repository root:

```
data/dictionary/d1a-query-review.tsv          ← DICTIONARY-D1A-QUERY-REVIEW.tsv
data/dictionary/conv-review-{ru,tt}.tsv       ← DICTIONARY-{RU,TT}-CONV-REVIEW.tsv
data/dictionary/dict-accept/…                 ← dict-accept/{accepted,conv-freq}-{ru,tt}.tsv
build/review-batches/                         ← new default output of review_batches.py (generated, not tracked)
```

Executed with two deviations: the files are named `data/dictionary/{tt,ru}-query-review.tsv` and
`{tt,ru}-conv-review.tsv`; `apply_rule_tt.py` was not kept, because it depends on two more one-off
evidence scripts and on corpora outside git. The rule it implemented is now written out in the
header of `scripts/bigram_extra_heads_tat.txt`, and the script is recoverable from git. The python
tests that read archived provenance documents now check `NOTICE.txt` instead.

Update paths in the 4 JVM tests (replace the double `File(...) ?: File("../...")` lookup with one
helper), in `scripts/dict_accept.py`, `scripts/review_batches.py`, `scripts/rebuild_assets.py`,
`research/corpus/{run_review.sh,make_review.py,measure_accept.py}` and in the header of
`scripts/bigram_extra_heads_tat.txt`.
Done when: `grep -rn "docs/" scripts research tests app/src/test` finds no path that code opens,
`python3 scripts/rebuild_assets.py --check --allow-known-drift` passes, and the JVM and python
suites are green.
Risk: medium. It touches the asset pipeline, but the change is path-only.

**WP2.2 Remove run evidence and generated reports.** Delete from the tree:
- every `docs/**/evidence/` directory, `docs/cleanup/`, `docs/release-1.9.12/`, the PNGs in
  `docs/russian-bigrams-repack/`;
- all tracked `docs/**/*.generated.json` (19 files, including three under `lang-priority` marked
  "deliberately committed" — under D2 = delete they go with their report);
- all PNG/TSV/TXT/PY/SH/JSON left under `docs/archive/` after WP2.1.

Extend `.gitignore` with `docs/**/*.png`, `docs/**/*.generated.json`, `docs/**/evidence/` and point
evidence output to `build/` (already the convention of the device scripts).
Done when: `git ls-files docs | grep -vE '\.md$'` is empty, and gates are green.

### Phase 3 — Production code comments (`app/src/main`)

Apply the Section 3 rules. Work order follows risk: the H1 anchors go first.

**WP3.0 Re-anchor source-contract tests.** Change the delimiters listed in H1 to code-based anchors
(method signatures or the next declaration). Do not touch comment text in this commit.
Done when: JVM tests are green, and `rg -n '"/\*\*.*20[0-9]{2}-' app/src/test` plus a search for
the `InputLogic` javadoc first lines return nothing used as a delimiter.

**WP3.1 Keyboard layer** — `keyboard/`, `keyboard/internal/`, `compat/`, `event/`,
`accessibility/` (report 13, ≈ 50 findings). Mission prefixes and dates, the duplicated
`KeyboardTextsTable` header (one language), fork notes in `compat/` shortened to "Forked from
androidx X; reason".

**WP3.2 IME core** — `latin/*.java|kt`, `inputlogic/`, `common/`, `utils/`, `setup/` (report 14).
49 dated footnotes, ≈ 129 mission codes, 32 doc links. Collapse the repeated paragraphs:
"beginBatchEdit() refreshes the connection…" (×4, `InputLogic`), "a dialog rather than a Toast…"
(×7, `LatinIME`), "// S8 (see the class javadoc)…" (×11, `RichInputConnection`; state it once in
the class doc). Remove the git hash `25c1ae28` (`InputLogic:660`), "operator" (`AppLocale.kt:32`)
and "(T2 split, part 2 of 3)" in 4 helpers.

**WP3.3 Suggestions and engine** — `latin/suggestions/`, `latin/dictionary/engine/` (report 15;
comments ≈ 38 % of lines; `SuggestionSurfaces.kt` 71 %, `AutocorrectAdvice.kt` 79 %). About 150 doc
links (38 in `SuggestionsController.kt`). Remove device-witness and calibration logs from comments
(`TdictPrefixIndex.kt:190`, `FuzzyEditPolicy`, `AutocorrectAdvice`). Merge "one of the six
events…" (×4). Unify language (`RevertWindow.kt:80`, `KeyNeighborTable.kt:23`,
`SuggestionStripView.kt:603`).

**WP3.4 Storage, personal stores, glide** — `dictionary/storage/`, `personal/`, `personalstore/`,
`glide/` (report 16). Replace the repack histories in `DictionaryStorageContracts.kt` and
`BigramStorageContracts.kt` with one line each; the numbers stay in the `expected*` fields. The
triple Personal{Dictionary,Bigram,Emoji}* comments: full text in the word-store file, `See [X]` in
the other two. The "NOT a Kotlin data class… first interpolation" mantra (≈ 13 places) shrinks to
one line (respect H2).

**WP3.5 Emoji, settings, resources, manifest** — `latin/emoji/`, `latin/settings/`,
`app/src/main/res/**`, `AndroidManifest.xml` (report 17). Class docs of `SettingsHostActivity`
(42 lines), `EmojiSearchView`, `EmojiPanelState`, `EmojiSuggestIndex` shrink to ≤ 8 lines. Remove
memory numbers and the "re-measurement ritual", test names in production comments, and product
versions in prose. Remove the "(T5)" machine-translation marker in the tt strings. UI strings
themselves need no change.

Done for every WP3.x when the area has 0 `docs/*.md` links, 0 dated footnotes and 0 mission codes
(allowlist in Appendix C), no comment contains Cyrillic except quoted language data (rule 7), no
H2 token was introduced, and gates are green.

Translation hot spots in `app/src/main` (239 comment lines with Cyrillic; some are legitimate
Tatar examples):
- Russian passages inside English comments: `RevertWindow.kt:80`, `KeyNeighborTable.kt:23`,
  `SuggestionStripView.kt:603`, the "Р-1/Р-2/Р-3" tags (`KeyboardSwitcher`, `EmojiPanelView`,
  `EmojiSearchView`);
- Russian quotations from old proposals in `TatarWordUtils.kt`, `CompositePrefixComputer.kt`,
  `GlideGestureDecider.kt`, `AtomicBigramStore.kt`, `BigramStorageContracts.kt`, `TdictFormat.kt`;
- the Russian block duplicating the English javadoc in `KeyboardTextsTable.java:28`;
- XML comments in `res/` (101 lines, mostly `values*/`, `xml/`, `drawable/`). Only the comments
  change: the `<string>` values in `values-ru/` and `values-tt/` stay.

### Phase 4 — Dead code

**WP4.1 Unsupported layouts.** Remove `SubtypeLocaleUtils.LAYOUT_ARABIC…LAYOUT_URDU`,
`LAYOUT_EAST_SLAVIC`, and the 18 `case` branches in `InputLogic.layoutUsesAutoCaps` (it reduces to
`return true`). Before deleting, check whether the `east_slavic` migration in
`SubtypePreferenceUtils.java:78` still serves users upgrading from pre-3b versions. If it does,
keep the migration and drop only the constants it does not need.

**WP4.2 `estimatedFileSize()`** in `PersonalEntries`, `PersonalBigramEntries` and
`PersonalEmojiEntries`: never called in production, and the KDoc misstates its role. Remove the
methods and their two test usages.

**WP4.3 Fuzzy classes #2/#3 (D4).** Remove the generators, the geometric neighbor tables and the
`EDIT_CLASS_*` branches in `TdictPrefixIndex.collectFuzzy`, plus the tests that pin them. Keep
classes #1 and #4 untouched.

**WP4.4 Unshipped measurement code (D5).** Remove `typo_pack.py` class #5 (lines ≈ 825–880,
1044–1049, `choices`) and `TtTypoPhaseBCalibrationTest.kt` / `TtTypoPhaseBPrecisionTest.kt`. Check
`InputAttributes.mInputTypeNoAutoCorrect` and `mApplicationSpecifiedCompletionOn` for remaining
readers and remove them if unused.

Done when: JVM + python gates are green, `lintRelease` shows no new warnings, and
`rebuild_assets.py --check` passes (typo sets for classes 1–4 are unchanged).

### Phase 5 — Tests, scripts, build files

**WP5.1 JVM tests** (reports 18, 19). Class headers of `*ContractTest` shrink to "what is pinned
and why it is checked from source". Remove the re-pin histories in eval and calibration tests
(`TtSuggestEvalTest`, `TtTypoPhaseCCalibrationTest`, `TatBigrValidatorTest`). The five
fuzz-test preambles become `See [SeededFuzzHarness]`. Remove "The operator decided on 2026-08-24"
(`DataSourcesScreenSourceContractTest.kt:24-45`) and count pins in prose. Names such as
`ResultHandoff` and `handoffCount` are real API names and stay.

**WP5.2 androidTest and baselineprofile.** Same rules; the CUJ narrative in the profile generator
shrinks to a short list of the journeys it runs.

**WP5.3 Golden exporters (D3).** Comments only: remove agent/"OD-5" text and doc links; keep one
line saying they are inert unless `*_GOLDEN_OUT` is set and that they feed the iOS parity suite.

**WP5.4 Scripts** (report 20). Module docstrings ≤ 8 lines ("what / input / output / when it
fails") for `bigram_asset_pack.py`, `rebuild_assets.py`, `glide_pack.py`, `dict_accept.py`,
`make_eval_set.py`, `typo_pack.py`. Keep the linguistic core of `wordform_gen.py`. Remove
boilerplate ("only the Python standard library" ×9, "data, not code" ×6, the "fail-closed…"
paragraph ×6). All docstrings and `#` comments are in English. Several files are Russian today
(`rebuild_assets.py`, `schema2_equivalence_check.py`, `schema3_equivalence_check.py`,
`dict_accept.py`, `dict_accept_check.py`, `review_batches.py`, and the comments of
`dictionary_pack.py`). The same applies to data-file comment headers (`bigram_extra_heads_tat.txt`,
`wordform_exceptions_tat.tsv`, `known_asset_drift.json` `reason` fields; see H8 for pinned SHAs).
The `.sh` headers (`emulator-smoke.sh` has 29 dated notes, `release_check.sh` 12) become a short
English usage block. Messages the scripts print are output, not comments. Translating them is
recommended for consistency but optional; if translated, update the tests that pin them (e.g. the
string `вычитано` in `tests/review_batches`). They must not contain mission codes in any case.

**WP5.5 Python tests and build configuration** (report 21). `app/lint.xml`: the 52-line comment
becomes one line per suppressed check. `ci.yml` (≈ 45 % comments) and `app/build.gradle` (≈ 40 %):
keep only the non-obvious why (action SHA pinning, reproducible-build flag, error-prone wiring).
Also `gradle.properties` and `.gitignore` (its comments are Russian; translate them). In
`tests/dict_accept` and `tests/review_batches` the word "operator" is a domain term (the dictionary
curator); rename it to "curator" or leave it, but do not treat it as an agent artifact.

**WP5.6 `research/corpus/` scripts.** Remove "the operator" and mission codes from comments, and
translate the Russian docstrings (`measure_accept.py` and others) to English.

Done when: repository-wide metrics in Appendix C meet the Section 2 targets for tests and scripts,
no comment contains Cyrillic except quoted language data (428 such lines in tests and 786 in
scripts today, including legitimate Tatar examples), and gates are green.

### Phase 6 — Documentation

Target layout after Phase 6. Every file is in canonical English except the root `README.md`.

```
README.md            user-facing overview (bilingual as today; the only exemption from rule 7)
PRIVACY.md           privacy policy (English only; the Russian half is removed, see D6)
SECURITY.md          vulnerability reporting + re-audit cadence
CHANGELOG.md         Keep a Changelog, English, user-facing
AGENTS.md            how to build, test, release; hard constraints; writing rules
HANDOFF.md           current state, one screen
BRIEF.md             product vision and fixed decisions (absorbs the surviving research facts)
docs/README.md       one line per document
docs/ARCHITECTURE.md input pipeline, suggestion engine, threads, stores (new, ≤ 200 lines)
docs/ASSET-FORMATS.md TATDICT schema 2, TATBIGR schema 3, personal store formats (new)
docs/ASSET-PIPELINE.md how rebuild_assets.py works, pins, known drift (new, from AGENTS.md)
docs/THREAT-MODEL.md
docs/PERF-BUDGETS.md
docs/PUBLISH-CHECKLIST.md
docs/BACKLOG.md      open items only
```

**WP6.1 HANDOFF.md.** Replace it with a single current-state entry (≤ 60 lines): version, what
is in flight, open operator actions, known risks, where to look next. Lines 47–2254 go (they
exist in git and CHANGELOG). Rule: rewritten, never appended to.

**WP6.2 CHANGELOG.md.** Keep a Changelog format, English, sections Added / Changed / Fixed /
Security / Removed. Remove the signing-key hash (×14) and "no INTERNET" boilerplate (×28); state
both once in the header as project invariants. Drop `developer-facing` entries, test counts, byte
deltas, rule counts, mission codes and doc links. Older entries may be condensed to 1–3 lines per
version.

**WP6.3 PUBLISH-CHECKLIST.md.** One version-independent procedure (≤ 120 lines): preflight,
`release_check.sh --full`, `release_pack.sh`, store upload per channel, tag. Remove the
≈ 460 lines of "Retargeted" blocks and ≈ 900 lines of per-version evidence.

**WP6.4 Release audit.** Replace per-release `APK-AUDIT-*.md` files with the `release_check.sh`
output plus a ≈ 15-line template (entries changed, assets re-inflated on update, device evidence
pointer). Keep only the current one as `docs/APK-AUDIT.md` (overwritten each release) or drop it
entirely if `release_check.sh --full` output is attached to the GitHub release.

**WP6.5 New and refreshed live docs.**
- `docs/ASSET-FORMATS.md` from the "Format" sections of `SIZE-SCHEMA2.md`, `SIZE-SCHEMA3.md` and the
  personal-store format headers (`TpersFormat`, `TpersbFormat`, `TpersemFormat`).
- `docs/ASSET-PIPELINE.md` from the asset section of `AGENTS.md`.
- `docs/ARCHITECTURE.md` from the architecture overview produced during the project study (input
  path, `SuggestionsController` threading and token discipline, engines, stores, glide).
- `THREAT-MODEL.md`: statuses current, mission codes resolved to plain names, the risk register kept.
- `PERF-BUDGETS.md`: one row per budget with gate or ritual; no release-bound numbers.
- `BACKLOG.md`: only the open section D of `BACKLOG-2026-09-28.md`.

**WP6.6 Remove closed documents (D2).** Precondition: Phases 3 and 5 are done (H5). Delete:
- plans and reports: `ROADMAP*.md`, `GLIDE-*.md`, `RESTRUCTURE*.md`, `DEV-PLAN.md`,
  `LEFTOVERS-PLAN-*.md`, `APPLE-UX-*.md`, `ERRORPRONE-TRIAGE.md`, `TT-*.md`, `NEXTWORD-RACE.md`,
  `CORPUS-CONVERSATIONAL-*.md`, `RUSSIAN-BIGRAMS-REPACK.md`, `EMOJI-*.md`, `emoji-suggest/`,
  `SIZE-*.md`, `TABLET-ENTER.md`, `RESEARCH-FIXES.md`, `DEVICE-*.md`, `CLEANUP.md`;
- audits: `APK-AUDIT-*.md` (all but the WP6.4 choice), `AUDIT-*.md`, `SECURITY-AUDIT-*.md`,
  `FINAL-AUDIT-*.md`, `OPTIMIZE-*.md`, `IC-BINDER-AUDIT-*.md`;
- `docs/archive/` in full (after WP2.1);
- `research/*.md` (D8), after moving the cited facts.

Replace `docs/archive/` with the one-page `docs/HISTORY.md` from Appendix B: document name, one-line
purpose, and the last commit that contains it (`git log -1 --format=%h -- <path>`).
Done when: `docs/` matches the target layout, `grep -rn "docs/" --include=*.md .` has no dead link
(checked by the script in WP7.2), and the `ERRORPRONE-TRIAGE` and `APPLE-UX` references from code
are gone (Phase 3).

**WP6.7 Root docs.**
- `README.md`: remove "this is not a promise but a verifiable property" (line 39) and similar;
  state the fact. `README.md` stays bilingual (rule 7 exemption), but its English parts follow the
  Appendix D terms.
- `SECURITY.md:42,62-63`: same.
- `PRIVACY.md:40,47,54,66,124`: remove "earns its place" ×3 and "stated rather than left unsaid".
- `AGENTS.md`: see WP7.1.
- `BRIEF.md`: remove the paragraph about the history of a documentation error (line 24); absorb the
  surviving research facts (D8).
- `metadata/`: see WP6.10 (D7).

**WP6.8 docs index.** Rewrite `docs/README.md` in English as one line per document, with no status
narratives.

**WP6.9 Translate the surviving documents.** Every document that survives Phase 6 is in canonical
English (rule 7, Appendix D terminology). Most rewrites in WP6.1–6.8 already produce English text.
The remaining translation work:

| Document | Today | Action |
|---|---|---|
| `AGENTS.md` | Russian | translate while doing WP7.1 |
| `BRIEF.md` | Russian | translate; merge the surviving research facts (D8) |
| `PRIVACY.md` | English + Russian halves | keep the English half, check it covers everything the Russian half says (the two have drifted, e.g. the bigram size at lines 30/107), then remove the Russian half |
| `CHANGELOG.md` | 2.0.0–3.6.0 English, 1.0.0–1.9.15 Russian | translate the Russian entries while condensing them (WP6.2) |
| `HANDOFF.md`, `docs/README.md`, `docs/PUBLISH-CHECKLIST.md`, `docs/BACKLOG.md` | Russian or mixed | written in English by WP6.1, 6.3, 6.5, 6.8 |
| `docs/THREAT-MODEL.md`, `docs/PERF-BUDGETS.md`, `SECURITY.md` | English | edit for Appendix D terms only |
| New docs (`ARCHITECTURE`, `ASSET-FORMATS`, `ASSET-PIPELINE`, `HISTORY`) | — | written in English |

Done when: `git ls-files '*.md' | grep -v '^README.md$'` lists no file with Cyrillic outside quoted
language data (checked by WP7.2). Have a second reader (a person or a separate agent) review each
translated document for calques and terminology drift.

**WP6.10 Store changelogs (D7).** Rewrite every `metadata/{en-US,ru-RU,tt}/changelogs/*.txt` in
plain user-facing language: what changed for the person typing, in one to three short lines. No
timings, byte counts, test counts, internal component names ("debug tracers", "strip seams"),
mission codes or "unchanged to the byte" phrasing. Work one versionCode at a time, so the three
locales say the same thing, and keep each file within the store limit (500 characters for F-Droid).
There are 42 versionCodes × 3 locales = 126 files. Start with the ones flagged in report 01 (18–23,
33, 42) and then go through the rest.
Done when: every changelog is rewritten and the three locales match for each versionCode, and
`release_check.sh --quick` still finds `metadata/en-US/changelogs/43.txt`.

### Phase 7 — Guardrails and close-out

**WP7.1 `AGENTS.md`.**
- Translate the file to English.
- Add the Section 3 writing rules, including rule 7 (canonical English except the root `README.md`),
  and the Appendix D terminology table.
- Replace the "history is not rewritten" rule (D1).
- Move the asset-pipeline prose to `docs/ASSET-PIPELINE.md`.
- Remove test counts, mission codes and dated notes from the command table. Each row becomes a
  command plus one sentence; device-specific notes move to the scripts' `--help`.

**WP7.2 Automated hygiene check.** Add `scripts/text_hygiene_check.py` (stdlib only) and a
unittest in `tests/text_hygiene_check/`. Run it in CI next to `check-no-internet.sh`. It fails on:
- `docs/…\.md` in comments under `app/src`, `scripts`, `tests`, `research`, `baselineprofile`;
- dated footnotes (`\b20\d\d-\d\d-\d\d\b`) in comments under the same roots;
- `\b(operator|UNCOMMITTED|handoff)\b` in `app/src/main` comments (the identifiers `ResultHandoff`
  and `TimedHandoff` are code, not comments);
- markdown links to non-existent files in `*.md`;
- tracked files under `docs/` that are not `.md`;
- Cyrillic in comments (all roots above, plus `res/**/*.xml` comments, `*.gradle`, `.github/`,
  `.gitignore`) and in any tracked `*.md` except the root `README.md`. Quoted language data is
  allowed: Cyrillic inside backticks or quotes, or at most two consecutive Cyrillic words. The
  checker ignores `<string>` values, `metadata/`, `app/src/main/assets/` and test string literals.

It uses an explicit allowlist file for real data pins (dates and SHAs that are data).
Done when: CI runs it, and it passes on the cleaned tree and fails on a planted violation.

**WP7.3 Close-out.** Re-run Appendix C metrics and the full gate set, write the before/after
table into `HANDOFF.md`, and delete `cleaning/` (its reports describe the pre-cleanup tree).

## 7. Verification

Run after every WP unless the WP says otherwise:

```
./gradlew test --rerun-tasks
for f in tests/*/test_*.py; do python3 "$f" || exit 1; done
./gradlew lintRelease
python3 scripts/rebuild_assets.py --check --allow-known-drift
bash scripts/check-no-internet.sh
```

After Phases 3, 4 and 7, also run:

```
./gradlew assembleRelease -PskipReleaseSigning      # size ≤ 3 145 728 B, delta vs baseline ≤ 2 KB for comment-only phases
bash scripts/check-no-internet.sh app/build/outputs/apk/release/app-release-unsigned.apk
bash scripts/release_check.sh --quick
```

After Phase 4 (behavioral code removed), also run the emulator smoke
(`bash scripts/emulator-smoke.sh`) and compare its `RESULT|` lines with the baseline.

Comment-only phases must not change the JVM test count. Phase 4 may reduce it only by the tests
explicitly listed in WP4.2–WP4.4.

## 8. Sequencing and effort

```
Phase 0 ─► Phase 1 ─► Phase 2 ─► WP3.0 ─► WP3.1…3.5 (parallel by area) ─► Phase 4
                                   └──► Phase 5 (parallel with 3.x after WP3.0)
Phase 3 + Phase 5 done ─► Phase 6 ─► Phase 7
```

Effort estimates for one person with agent assistance:

| Phase | Estimate |
|---|---|
| 0 | 0.5 h |
| 1 | 2 h |
| 2 | 3 h |
| 3 | 2.5–3.5 days (including comment translation) |
| 4 | 0.5–1 day |
| 5 | 2 days (including comment translation) |
| 6 | 3–3.5 days (including WP6.9 translation and WP6.10 store changelogs) |
| 7 | 0.5 day |

The work splits cleanly by area. WP3.1–3.5 and WP5.1–5.6 can each go to a separate agent after
WP3.0. Each agent runs the gates for its area and does not edit files outside it.

## 9. Out of scope

- Refactoring `SuggestionsController.kt`, `LatinIME.java` or the triple personal-store stack (the
  size of these classes is a design question, not a text question).
- Rewriting git history or shrinking `.git`.
- Changing UI strings, layouts, assets, or anything that alters the APK beyond Phase 4.
- Localized content that is not documentation: `res/values-{ru,tt}/` strings, the store listings and
  changelogs in `metadata/{ru-RU,tt}/`, and test data. These stay in their languages.
- The language of commit messages.
- The iOS repository.

## Appendix A — Report index

| Report | Area | Phase / WP |
|---|---|---|
| 01 | root docs, metadata, images, icons | 1.2, 1.3, 6.7 |
| 02 | `HANDOFF.md` | 6.1 |
| 03 | `CHANGELOG.md` | 6.2 |
| 04 | `PUBLISH-CHECKLIST.md`, docs index, `CLEANUP.md` | 6.3, 6.8 |
| 05 | roadmaps, glide docs | 6.6 |
| 06 | restructure, dev plan, backlog, leftovers, Apple UX, error-prone | 6.5, 6.6 |
| 07 | TT missions, corpus, Russian bigrams | 2.2, 6.6 |
| 08 | emoji, size, tablet, device docs | 2.2, 6.5, 6.6 |
| 09 | APK audits | 6.4, 6.6 |
| 10 | audits, security, optimize, threat model, perf budgets | 1.2, 6.5, 6.6 |
| 11 | `docs/archive/` (see H3 correction) | 2.1, 2.2, 6.6 |
| 12 | `research/` | 1.3, 5.6, 6.6 |
| 13 | keyboard layer code | 3.1 |
| 14 | IME core code | 3.0, 3.2, 4.1 |
| 15 | suggestions + engine code | 3.3, 4.3 |
| 16 | storage, personal, glide code | 3.4, 4.2 |
| 17 | emoji, settings, resources | 3.5 |
| 18 | engine-side JVM tests | 3.0, 4.4, 5.1 |
| 19 | other tests, androidTest, baselineprofile, golden (see D3) | 3.0, 5.1–5.3 |
| 20 | scripts | 4.4, 5.4 |
| 21 | build, CI, repository hygiene | 2.2, 5.5 |

## Appendix B — `docs/HISTORY.md` template

```markdown
# Document history

Documents removed in the 2026-10 cleanup. Each is recoverable with
`git show <commit>:<path>`.

| Path | What it was | Last commit |
|---|---|---|
| docs/ROADMAP-P7.md | Phase 7 report: glide typing | abcdef12 |
| … | … | … |
```

Generate it with:

```
for p in <removed paths>; do printf '| %s | | %s |\n' "$p" "$(git log -1 --format=%h -- "$p")"; done
```

## Appendix C — Metric commands

```
M=app/src/main/java; T="app/src/test app/src/androidTest baselineprofile/src"; S="scripts research/corpus"
c(){ grep -rEo "$1" $2 2>/dev/null | wc -l; }
c 'docs/[A-Za-z0-9/_.-]+\.md' "$M"            # doc links, main
c 'docs/[A-Za-z0-9/_.-]+\.md' "$T"            # doc links, tests
c 'docs/[A-Za-z0-9/_.-]+\.md' "$S"            # doc links, scripts
c 'docs/[A-Za-z0-9/_.-]+\.md' app/src/main/res
c '20[0-9]{2}-[01][0-9]-[0-3][0-9]' "$M"      # dated notes (review hits: data pins are allowlisted)
grep -rniEo 'fail-closed' $M $T $S docs *.md | wc -l
ls docs/*.md | wc -l; git ls-files docs | wc -l
git ls-files docs | grep -vE '\.md$' | wc -l  # non-markdown files under docs
wc -l HANDOFF.md CHANGELOG.md docs/PUBLISH-CHECKLIST.md docs/README.md
# broken doc links cited from code:
grep -rhEo 'docs/[A-Za-z0-9/_.-]+\.md' app/src scripts research/corpus baselineprofile/src \
  | sort -u | while read p; do [ -e "$p" ] || echo "MISSING $p"; done
# Cyrillic in comments and documents (rule 7; review hits for quoted language data):
cy='[А-Яа-яЁёӘәӨөҮүҖҗҢңҺһ]'
grep -rnE "^\s*(//|\*|/\*).*$cy" app/src/main/java | wc -l
grep -rnE "^\s*(//|\*|/\*).*$cy" app/src/test app/src/androidTest baselineprofile/src | wc -l
grep -rnE "<!--.*$cy|^\s[^<]*$cy.*-->" app/src/main/res | grep -v '<string' | wc -l
grep -rnE "^\s*#.*$cy" scripts research/corpus tests .github | wc -l
git ls-files '*.md' | grep -v '^README.md$' | while read f; do grep -qE "$cy" "$f" && echo "$f"; done | wc -l
```

Mission-code allowlist (tokens that look like codes but are not): Android API names (`API 24`),
Unicode code points (`U+04D9`), schema identifiers when they name a binary format
(`TATDICT schema 2`), key codes, and hex colors.

## Appendix D — Terminology

Use these terms in all comments and documents. The right column lists the Russian source terms (quoted language data,
hence the backticks), which should not be translated literally.

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
