# ROADMAP-P1 — phase 1 engine batch report: P3a, P3b, P4, T5

Status: the four engine items of `docs/ROADMAP.md` Phase 1 implemented 2026-09-22; gates
green; device UAT is separate work. The hygiene batch (T3, T4, T6) followed the same day —
see the second half of this report; T1 (baseline profile) remains. Each section names the
design, the files, the pins and the numbers. No commits — the operator commits.

Baseline before the batch: JVM 1 257 tests, python 474 tests, release 2.0.1
(unsigned APK 1 869 779 B after TT-NEXTWORD-FILL).

## P3a — sentence-start capitalization (done 2026-09-22)

### What was recorded as the limitation

TT-SUGGESTIONS P4 showed and committed sentence-start suggestions exactly as the table
stores them (NFC lowercase) — a deliberate v1 limitation, documented in
`docs/TT-SUGGESTIONS.md` (P4, "Casing").

### Design

Casing is applied at the DISPLAY boundary of the sentence-start slot, exactly the way
`applyPrefixResult` re-applies the typed prefix's casing to prefix candidates — never in
the asset, never in a lookup: `SuggestionsController.requestSentenceStart`
(`app/src/main/.../latin/suggestions/SuggestionsController.kt`) maps the source words
through `TatarWordUtils.applyCasing(word, PrefixCasing.INITIAL_CAPS)` before `showBand`.
`INITIAL_CAPS` is invariant-locale `substring(0,1).uppercase() + substring(1)` — a 1:1 map
for every Cyrillic letter, Tatar-specific ones included (no locale-sensitive casing like
Turkish i/I exists in either alphabet). The tap path needs nothing: the strip hands the
displayed string back verbatim, so `commitPredictedWord` inserts the capitalized form —
pinned by the tap test. The table itself, the eval metric and every dictionary lookup stay
lowercase by construction.

The rule is unconditional for the slot: a sentence start (proven field start or
sentence-final punctuation + space) always shows capitals. There is no shift-state seam in
the controller; the slot's own definition IS the capitalization signal, which is why the
roadmap's "shift-state seam" wording resolved to the display-boundary rule.

### Pins

`SuggestionsControllerSentStartTest`: every band assertion now expects the capitalized
forms (`CAPITALIZED_TABLE`); `theCellsAreShownAndCommittedCapitalized` pins that a tap on
`Ул` commits `("" to "Ул")` — shown and inserted with the capital.

## P3b — Russian sentence-start table (done 2026-09-22)

### Corpus inputs (all downloaded 2026-09-22 into `~/corpora-leipzig/`, HEAD-verified first)

| Corpus | URL (downloads.wortschatz-leipzig.de/corpora/) | tar.gz bytes | sentences rows |
|---|---|---:|---:|
| rus_news_2022_1M | `rus_news_2022_1M.tar.gz` | 227 399 081 | 1 000 000 |
| rus_news_2019_1M | `rus_news_2019_1M.tar.gz` | 223 242 871 | 1 000 000 |
| rus_wikipedia_2021_1M | `rus_wikipedia_2021_1M.tar.gz` | 214 162 416 | 1 000 000 |

Same Leipzig Corpora Collection terms as the Tatar corpora (CC BY 4.0; the NOTICE beside
the dictionaries already covers the collection; a `russian_sentstart_v1.txt` section was
added next to the others in `app/src/main/assets/dictionaries/NOTICE.txt`).

### Pipeline

`scripts/sentstart_pack.py` gained `--language {tat,rus}` (default `tat`): the alphabet
(`dictionary_coverage.RUSSIAN_ALPHABET`), the dictionary decode language and the header
block are per language; the `tat` path is byte-identical to before (the Tatar asset
rebuilt during development compared equal to the committed one). Build:

```
python3 scripts/sentstart_pack.py build --language rus \
  --sentences ~/corpora-leipzig/rus_news_2022_1M-sentences.txt \
              ~/corpora-leipzig/rus_news_2019_1M-sentences.txt \
              ~/corpora-leipzig/rus_wikipedia_2021_1M-sentences.txt \
  --dictionary app/src/main/assets/dictionaries/russian_top100k_v1.tdict.zlib \
  --output app/src/main/assets/dictionaries/russian_sentstart_v1.txt
```

Result: 3 000 000 sentence rows, 2 922 916 first tokens accepted, 77 084 dropped
(digits/Latin/punctuation-only), filtered against the shipped 100 000-word Russian
dictionary (the strict filter, same as tt). `russian_sentstart_v1.txt`: **64 records,
1 745 bytes, SHA-256 `ffab114d…83f86d`**, top-10: в (349 125), по (81 782), на (69 779),
он (43 589), это (42 802), с (39 182), но (36 697), как (34 330), а (34 177),
после (32 953); table floor 4 843.

### Runtime wiring (no language string checks)

The artifact registry decides per language, the same seam the bigram table already rides:
`DictionaryArtifactSpec` gained `sentStartAssetPath: String?` (both shipped specs carry
one) plus `DictionaryArtifactSpec.sentStartAssetForSubtype(subtypeId)`
(`app/src/main/.../dictionary/storage/DictionaryStorageContracts.kt`). The production
controller factory resolves through it; `AssetSentStartPreparation` now takes the asset
path as a parameter (`SentStartSources.kt`). The controller state went per language:
`sentStartSources`/`sentStartPreparations`/`sentStartPreparationRequested` are maps keyed
by subtype — one lazy load per language per process, so a Tatar-only user never reads the
Russian table. A language whose factory answers null (no table) is re-asked cheaply per
sentence-start moment and never marks the requested flag — a deliberate difference from a
FAILED load, which is terminal. `onSentStartReady` stores the source for ITS language even
when the user has switched away, and fills only when that language is active and the live
position is still a sentence start. The en layout ships no dictionary at all, so nothing
there changes by construction (eligibility is false).

Capitalization from P3a applies to the Russian slot identically (ru field start →
`В · По · На`, pinned).

### Pins

- python (`tests/sentstart_pack/`): `RussianLanguageTest` (ru normalization with ё kept /
  ә dropped, ru header, ru CLI build, the tat default header unchanged),
  `CommittedRussianAssetTest` (SHA-256 + 64 records, shape, sorting, `normalize_word`
  under the Russian alphabet, membership in the shipped Russian dictionary, attribution).
- JVM: `RussianSentStartAssetTest` (mirrors `TatarSentStartAssetTest` pin for pin, the
  membership check against the real Russian dictionary through `TdictPrefixIndex`);
  `SuggestionsControllerSentStartTest`: `theRussianFieldStartOffersTheRussianTableCapitalized`,
  `eachLanguageLoadsItsOwnTableExactlyOnce`, `aSubtypeWithoutATableStaysSilentAndLoadsNothing`,
  `aLoadForALanguageTheUserLeftDoesNotPaintItsBand`.

## P4 — predictions after non-final punctuation (done 2026-09-22)

### The amendment

The frozen E5d contract answered nothing after ANY punctuation. Amended deliberately and
narrowly (`TatarWordUtils.extractNextWordContext`, the shared extraction both the request
path and the tap path use): when the character before the trailing U+0020 run is a
NON-final punctuation mark — exactly `,` `;` `:` — the context is the word before the
punctuation run (`сүз, ` → `сүз`). Everything downstream is unchanged: the ordinary
NEXT_WORD request carries the extracted word, so bigram successors > after-word forms >
fallback apply as-is, the emoji tail works off the same word, and the tap re-derives the
live context with the very same function. Sentence-final `.` `!` `?` `…` keep the
sentence-start behavior exclusively — the two detectors are complementary by construction
(pinned disjoint).

Rules, all pinned in `TatarWordUtilsTest`:

- `, ` alone (nothing before) → "" — no context, and not a sentence start either;
- `сүз.., ` → "" — a run mixing final and non-final punctuation has no word before the
  non-final run (fail-closed);
- `сүз,` with no trailing space → "" — the prediction moment is the space after the
  comma, like everywhere else;
- the index-0 cache guard is the same as the plain-word path's: `сүз, ` is trusted only
  with cache-start provenance;
- digits are not words: `5, ` → "" (the existing `extractTrailingWord` letter rule);
- Russian parity: the extraction is language-agnostic (`слово, ` → `слово`).

End-to-end at the controller (`SuggestionsControllerSentStartTest`, the fake editor
running the REAL extraction): `сүз, ` issues exactly one NEXT_WORD request for `сүз` and
never touches the sentence-start table; `, ` and `сүз.., ` paint nothing and load nothing;
`сүз. ` still paints the sentence-start band through the real detector; `слово, ` on the
Russian slot asks the Russian engine for `слово`.

`docs/archive/PROPOSALS.md` is untouched; this section plus the pins are the record.

## T5 — one subtype source of truth (done 2026-09-22)

### The mirror that died

`PersonalSubtypes.alphabetFor` carried a hand-maintained `when (subtypeId)` mirroring the
artifact registry's language list — adding a language would have meant editing two lists.
`LatinIME` already resolved eligibility through `DictionaryArtifactSpec.forSubtype`; the
personal side did not.

### Design

Each `DictionaryArtifactSpec` now carries its `personalAlphabet: Set<Int>?` (the registry
entries reference the alphabet SETS, which stay defined once in `PersonalSubtypes` next to
their semantics KDocs), and `PersonalSubtypes.alphabetFor` is a pure lookup:
`DictionaryArtifactSpec.forSubtype(subtypeId)?.personalAlphabet`
(`app/src/main/.../dictionary/personal/PersonalSubtypes.kt`,
`.../dictionary/storage/DictionaryStorageContracts.kt`). No init cycle: the sets are
object properties of `PersonalSubtypes`, the spec init triggers that object's init, and
`PersonalSubtypes` touches the registry only inside the (lazy) function. Behavior is
identical by construction and pinned: same instances (`assertSame`), same sizes (39/33),
same marker letters, same probe answers (`en_US`, `ru_RU` unsupported).

### Pins

`PersonalSubtypeRegistryContractTest` (new): the runtime agreement — every registry
language is personal-supported with its own spec's alphabet instance, every probe
unsupported; the pre-T5 alphabets exactly (sizes + marker letters); the source shape —
`PersonalSubtypes.kt` holds no `when (subtypeId)` and no `TATAR_RU ->`/`RUSSIAN ->`
branches and delegates to `DictionaryArtifactSpec.forSubtype(subtypeId)?.personalAlphabet`;
and each registry entry names its `personalAlphabet = PersonalSubtypes.*` assignment in
`DictionaryStorageContracts.kt`. The pre-existing `PersonalSubtypeSeamTest` is unchanged
and green — the "behavior identical" half of the pin.

## Files touched

Main:

- `latin/suggestions/SuggestionsController.kt` — per-language sentstart state + factory
  `(executor, subtypeId)`; display-boundary capitalization (P3a); `EditorSurface` KDocs.
- `latin/suggestions/SentStartSources.kt` — `AssetSentStartPreparation(context, executor,
  assetPath)`; per-language KDoc.
- `latin/suggestions/TatarWordUtils.kt` — the non-final-punctuation context extraction +
  `isNonFinalPunctuation`; KDoc amendments (P4).
- `latin/dictionary/storage/DictionaryStorageContracts.kt` — `sentStartAssetPath` +
  `personalAlphabet` on the spec; `sentStartAssetForSubtype`; both entries populated.
- `latin/dictionary/personal/PersonalSubtypes.kt` — `alphabetFor` is a registry lookup;
  alphabet sets public; KDoc rewrite (T5).

Assets/scripts:

- `app/src/main/assets/dictionaries/russian_sentstart_v1.txt` — NEW (64 records).
- `app/src/main/assets/dictionaries/NOTICE.txt` — `russian_sentstart_v1.txt` section.
- `scripts/sentstart_pack.py` — `--language {tat,rus}`; per-language headers/alphabet.

Tests:

- `tests/sentstart_pack/test_sentstart_pack.py` — +10 (RussianLanguageTest,
  CommittedRussianAssetTest).
- `SuggestionsControllerSentStartTest` — rewritten per-language, 18 → 28 tests.
- `RussianSentStartAssetTest` — NEW, 7.
- `TatarWordUtilsTest` — +4 (the P4 extraction pins), 63 → 67.
- `PersonalSubtypeRegistryContractTest` — NEW, 4.

## Gates (2026-09-22, all on the final tree)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** (474 → 484) |
| `./gradlew test --rerun-tasks` | **1 282 tests, 0 failures / 0 errors** (1 257 → 1 282, +25) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` (binary assets untouched — only a new txt added; no objection) |
| `assembleRelease -PskipReleaseSigning` | unsigned APK **1 871 473 B** ≤ 3 145 728 (+1 694 over 1 869 779: the compressed asset and the code) |
| `check-no-internet.sh` (the release APK) | both levels OK |

## Deviations and notes

- The roadmap's P3a wording mentioned a "shift-state seam"; the slot-inherent rule (every
  sentence start capitalizes) shipped instead — the controller carries no shift state and
  the slot definition is itself the signal. Recorded here as the decision.
- `rebuild_assets.py --check` needed no change and raised no objection: it pins the four
  binary assets by name; the new text asset is outside its scope by design (the
  emoji-asset discipline — pins live in the python/JVM contracts).
- Corpus downloads live in `~/corpora-leipzig/` (not committed, CC BY); the
  `*-sentences.txt` of the three ru corpora were extracted beside the tt ones.
- `docs/TT-SUGGESTIONS.md` keeps its P4 section as the historical record; a dated footnote
  there points at this report for the casing and the ru table.


---

# Hygiene batch report: T3, T4, T6 (done 2026-09-22)

The three hygiene items of `docs/ROADMAP.md` Phase 1 on top of the engine batch above.
T1 (baseline profile) stays open.

## T3 — release keystore moved out of the repo root

`tatar-keyboard-release.jks` (untracked, never read or printed — black box) moved:

- old: `<repo>/tatar-keyboard-release.jks` (untracked, 4 264 B, mode 600);
- new: `~/.tatar-keyboard/tatar-keyboard-release.jks` — the directory created with mode
  700, the file keeps mode 600. Outside the repo, so no gitignore rule is even needed
  (the repo's `*.jks` / `keystore.properties` ignore lines stay as a safety net).

`keystore.properties` (untracked): only the `storeFile=` line's path token was replaced
with the new absolute path; the file still holds exactly the four keys
(storeFile/storePassword/keyAlias/keyPassword), mode 600. Its contents were never
printed or logged — the edit went through `sed` on the single `^storeFile=` line and
count-only greps.

Script/gradle audit for hardcoded path assumptions: none found, no changes needed.

- `app/build.gradle:38` — `storeFile file(keystoreProps['storeFile'])`: an absolute path
  passes through `file()` unchanged.
- `scripts/release_pack.sh:76-80` — reads `storeFile` from `keystore.properties`;
  relative paths are prefixed with `app/`, absolute paths (`/*`) pass through.
- `scripts/release_check.sh` — never touches the keystore; it pins the certificate
  SHA-256 (`RELEASE_CERT_SHA256`) on the built APK instead.
- CI (`.github/workflows/ci.yml`) — builds without `keystore.properties` by design.

Verification (before the T4 deletions, same tree as the engine batch):

- `./gradlew assembleRelease` (the signed path, no `-PskipReleaseSigning`) —
  BUILD SUCCESSFUL, `app-release.apk` 1 875 569 B;
- `apksigner verify --print-certs` on it — signer SHA-256
  `98ca6feb…42ad`, identical to `RELEASE_CERT_SHA256` in `scripts/release_check.sh:29`;
- end-to-end re-proof through `bash scripts/release_pack.sh` — see the gates table
  below (OVERALL PASS, same certificate).

## T4 — unused legacy layouts removed

### The premise check (what "unused" actually meant)

The six families were NOT dangling XML: `R.array.predefined_layouts`
(`app/src/main/res/values/donottranslate.xml`) still named all nine generic layouts, and
`SubtypeLocaleUtils.SubtypeBuilder.addGenericLayouts` offers every entry as a selectable
subtype **for English (US)** — surfaced in the settings UI
(`SettingsHostActivity.buildLanguageDetailScreen`, a switch row per subtype). The runtime
resolution rides `KeyboardLayoutSet.getXmlId` → `Resources.getIdentifier(name)` →
`keyboard_layout_set_<name>.xml`. So the deletion is a deliberate contract amendment,
not dead-code sweeping: English loses the BEPO / AZERTY / Dvorak / Colemak / Workman /
PC options (QWERTY stays the default, QWERTZ and ABC stay — out of this item's scope).

Upgrade safety for a user who had enabled English+Dvorak: `createSubtypesFromPref`
drops entries whose layout no longer resolves (`getSubtype` → null), and
`SubtypeList.reload` re-adds the missing defaults and persists the migrated pref —
fail-safe, no crash, no dangling selection. `KeyboardSwitcher` holds no hardcoded layout
names; tt/ru/en resolve through their own untouched layout sets
(tatar/russian/qwerty), symbols through each set's elements.

### What was changed

- `app/src/main/res/values/donottranslate.xml` — `predefined_layouts` 9 → 3 items
  (`qwerty, qwertz, abc`), `predefined_layout_display_names` 9 → 3 (`QWERTY, QWERTZ,
  ABC`), index correspondence kept.
- Deleted 55 layout files (each verified referenced only from within the deleted
  cluster before removal — repo-wide grep, `app/src` + scripts/tests):
  - `res/xml/`: 6 `keyboard_layout_set_{azerty,bepo,colemak,dvorak,pcqwerty,workman}.xml`,
    6 `kbd_*.xml`, 6 `rows_*.xml`, 15 `rowkeys_{azerty,bepo,colemak,dvorak,workman}{1,2,3}.xml`,
    `rowkeys_pcqwerty{1,2,3,4}.xml` + `rowkeys_pcqwerty1_shift.xml`,
    `keys_dvorak_123.xml`, `keys_pcqwerty{2_right3,3_right2,4_right3}.xml`,
    `row_pcqwerty5.xml` (43 total);
  - `res/xml-sw600dp/`: 5 `rows_*.xml` (no bepo tablet variant exists),
    `rowkeys_dvorak3.xml`, `rowkeys_pcqwerty1.xml`, 3 `keys_pcqwerty*_right*.xml`,
    `keys_dvorak_123.xml`, `row_pcqwerty5.xml` (12 total).
- Removed the dvorak-only `<case latin:keyboardLayoutSet="dvorak">` blocks from the
  shared `key_comma.xml` / `key_period.xml` (both `xml/` and `xml-sw600dp/`): dead
  branches once no selectable layout set is named `dvorak`; first-match switch, so the
  surviving cases are untouched semantically.
- `SubtypeLocaleUtils.java` — two comments updated to the new array contents
  (no code change).

Tests/pins: none referenced the deleted files — `RowkeysSyncTest` pins only
tatar↔russian rowkeys pairs, `SpaceKeyLayoutTest` only the qwerty/cyrillic space rows,
`KeyboardTextsTable.java` serves locales (DEFAULT/en/ru), not layout sets (its
manual-sync header was read before touching anything; no edit needed). Test counts
unchanged: JVM 1 282, python 484 — no AGENTS.md counter update.

### APK size delta (unsigned, `-PskipReleaseSigning`)

| | bytes |
|---|---:|
| before (engine-batch tree) | 1 871 473 |
| after T4 | 1 828 948 |
| **delta** | **−42 525** |

(`shrinkResources` could not strip these before: the runtime `getIdentifier` lookup
keeps them reachable for the static shrinker.)

## T6 — `.temp/` decision

`.temp/` (operator's local drafts: `deck/`, a pitch PDF) is NOT deleted — physical
deletion is the operator's call. The directory is added to `.gitignore` so the working
tree is clean (`git check-ignore` confirms; `git status` no longer lists it).

## Gates for the combined tree (2026-09-22, engine batch + T3/T4/T6)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | 15 files, 484 tests, 0 failing files |
| `./gradlew test --rerun-tasks` | 1 282 tests, 0 failures / 0 errors |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `assembleRelease -PskipReleaseSigning` | unsigned APK 1 828 948 B ≤ 3 145 728 |
| `check-no-internet.sh` (the release APK) | both levels OK |
| `release_pack.sh` (T3 end-to-end proof) | exit 0; unsigned 1 828 948 B → zopfli+signed 1 809 700 B; cert `98ca6feb…42ad` |

## Deviations and notes (hygiene batch)

- T4's roadmap premise "unused" was imprecise: the six families were reachable as
  English (US) alternative layouts. Removed deliberately per the mission (locales are
  tt/ru/en only); the settings screen for English now offers QWERTY / QWERTZ / ABC.
  QWERTZ and ABC are equally non-tt/ru/en layouts but were outside the named scope —
  flagged here for a possible follow-up decision, not touched.
- `key_period.xml` still carries a `bengali_bijoy` `<case>` — an upstream remnant whose
  layout set was already cut in phase 3b; dead but outside T4's named scope, noted for
  the same follow-up.
- `.temp/` physical deletion left to the operator (T6 records the gitignore half only).
- Historical docs (`HANDOFF.md`, `docs/RESTRUCTURE*.md`) still mention the jks at the
  repo root — history is not rewritten; this section is the new state of record.

---

# Final block: T1 (baseline profile) + full gates + device UAT (done 2026-09-22)

## T1 — baseline profile regenerated

`./gradlew :app:generateReleaseBaselineProfile` on `tt_suggest_a14` (the same AVD every
cold-start measurement uses). One environmental finding, recorded per the mission's rule:
the connected-test matrix ran on BOTH attached devices; on the physical POCO C71 the
generator fails with `The save profile broadcast was not received` (MIUI blocks the
profile-save broadcast — a known Xiaomi behavior, not an app defect; the emulator run
itself passes). The documented single-target precondition was restored with
`ANDROID_SERIAL=emulator-5554` (the connected-test matrix honors it) — no workarounds,
the same gradle task, BUILD SUCCESSFUL.

Generated `app/src/release/generated/baselineProfiles/baseline-prof.txt` (transient,
gitignored) promoted to the committed `app/src/main/baseline-prof.txt` per the module's
documented flow. Numbers before → after:

| Metric | Before | After |
|---|---:|---:|
| lines | 2 721 | 2 900 |
| rules (`^HS/S/H/L`) | 2 278 | **2 433** (+155) |
| HSPL / L / PL / HPL | — | 1 869 / 560 / 468 / 4 |
| diff lines (+/−) | — | +322 / −143 (465 total) |
| new-feature rules present (SentStart*, FallbackWords*, topFrequentWords) | 0 | **39** — incl. `CompositePrefixComputer(...FallbackWords)` ctor, `GlobalTopFrequencyFallbackFactory`, `TdictPrefixIndex.topFrequentWords` (HPL) |

The new hot paths of TT-SUGGESTIONS P3a/P4 and TT-NEXTWORD-FILL (and the phase-1 engine
batch) are captured; `startup-prof.txt` untouched. Device cold start below confirms the
400 ms invariant holds.

## Full gates (2026-09-22, final tree)

| Gate | Result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **15 files, 484 tests, 0 failing files** |
| `./gradlew test --rerun-tasks` | **1 282 tests, 0 failures / 0 errors / 0 skipped** (138 suites) |
| `./gradlew lintRelease` | BUILD SUCCESSFUL |
| `rebuild_assets.py --check --allow-known-drift` | `"ok": true` |
| `release_pack.sh` | unsigned **1 826 984 B** (−1 964 vs the T4 tree — the fresh profile compiles slightly smaller net) → signed zopfli **1 809 700 B** ≤ 3 145 728, SHA-256 **`6a7880a1090077356c236b4cd9e0482fd4e15c9f46b3e4f68579f3977c568d9d`**, v2-only, cert `98ca6feb…42ad` |
| `check-no-internet.sh` (the signed APK) | both levels OK |
| `release_check.sh --quick` | **8/8 artifact checks PASS** — version 2.0.1/33 matches `app/build.gradle`; changelog gate passes on the existing `metadata/en-US/changelogs/33.txt` (348 B, from the 2.0.1 release — it checks the metadata file, not the CHANGELOG.md top section); delta vs 2.0.0 −39 855 B (−2.2 %) |

## Device UAT (POCO C71, 720×1640, Android 15 Go, SDK 35, DARK theme)

APK installed with `adb install -r` over 2.0.0/32 (data preserved). The device-default
IME at session start was **Gboard** (the user had switched back) — ours was
enabled/selected for the UAT and **Gboard restored at the end**. Found and restored
faithfully: **the "Word suggestions" toggle was OFF** (the user had turned it off between
sessions — the first probe showed a strip-less keyboard and prefix typing painted
nothing); it was switched ON for the UAT and **set back OFF at the end** — the operator
should re-enable it when wanted (settings → Preferences → Word suggestions).
Evidence: `build/device-uat-2026-09-22/` (44 files).

### Phase-1 features (task a)

| # | Scenario | Result | Evidence |
|---|---|---|---|
| A1 | tt empty field → sentence-start **capitalized**: **Бу · Ул · Ә** (was lowercase) | PASS | `03-strip.png` |
| A2 | tt after `. ` (letter directly before the period) → **Бу · Ул · Ә** capitalized | PASS | `05-strip.png` |
| A3 | ru empty field → **В · По · На** capitalized (the new ru sentstart table, top-3 of the pinned 64) | PASS | `06-strip.png` |
| A4 | tt after-comma: `татар, ` → **теле · дәүләт · телен** (the word's bigram successors; mid-sentence, correctly lowercase) | PASS | `04-strip.png` |
| A5 | ru comma parity: `майор, ` → **полиции · и · внутренней** (matches the offline table read) | PASS | `08-strip.png` |

### Regression core (task b)

| # | Scenario | Result | Evidence |
|---|---|---|---|
| B1 | `сцләм` → **сәләм** in cell 1 (typo class #4) | PASS | `12-strip.png` |
| B2 | tap `сәләм` → immediately **сәләмә · һәм · белән** (tap-followup + fallback fill) | PASS | `13-strip.png` |
| B3 | `сакчы`+space → сакчысы · сакчылар · сакчысын (forms, no displacement) | PASS | `14-strip.png` |
| B4 | `татар` prefix → татарлар · татарча · татарлары (same-stem boost) | PASS | `15-strip.png` |
| B5 | `татар`+space → теле · дәүләт · телен (bigrams only) | PASS | `16-strip.png` |
| B6 | `сәлам`+space → биреп · белән · 👋 (emoji tail kept) | PASS | `17-strip.png` |
| B7 | ru `майор` → майора · майором · майору (unchanged) | PASS | `07-strip.png` |
| B8 | ru `тюлень`+space → я · не · в (ru fallback) | PASS | `09-strip.png` |
| B9 | en basic typing (strip hidden by design); globe cycling | PASS | `10-en-hi.png` |
| B10 | Emoji panel: long-press comma → grid tap commits 😀 → АБВ back | PASS | `11-emoji-panel.png`, field readback |
| B11 | Symbols `?123` (digit committed) + `#+=` + ABC back | PASS | `18-symbols.png`, `19-symbols2.png` |
| B12 | Rotation: landscape rebuild (navbar aside, no overlap), back to portrait | PASS | `20-landscape.png`, `21-portrait-back.png` |
| B13 | Sustained Tatar paragraph: 193 chars in 87 s — byte-identical to the source | PASS | `22-paragraph-field.txt` |

### Cold start (task c) and stability (task d)

| Entry point | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| SetupActivity | 252 | 257 | 257 | **257 ms** |
| SettingsActivity | 270 | 273 | 268 | **270 ms** |

Both medians < 400 ms (previous: 251/273). Note the sideloaded APK runs `[status=verify]`
— the fresh baseline profile is NOT applied by the runtime here; Play installs would not
be slower. Crash buffer EMPTY after the whole cycle; full logcat: no FATAL EXCEPTION /
ANR for the package (only the known MIUI WindowManager `dispatchAppVisibility`
W-warnings at the cold-start force-stop instants). Evidence: `23-coldstart.txt`,
`24-logcat-crash.txt` (0 lines), `25-logcat-full.txt`.

## DONE-WHEN audit (Phase 1 of `docs/ROADMAP.md`)

| Item | Verdict | Evidence |
|---|---|---|
| P3a sentence-start capitalization | **done** | pins (this report, engine batch) + device A1/A2 (Бу · Ул · Ә at field start and after `. `) |
| P3b Russian sentence-start table | **done** | asset + pins + device A3 (В · По · На) |
| P4 predictions after non-final punctuation | **done** | contract amendment + pins + device A4/A5 (tt татар, → теле·дәүләт·телен; ru майор, → полиции·и·внутренней) |
| T3 keystore out of the repo root | **done** | hygiene section; this session's `release_pack.sh` signed through `~/.tatar-keyboard/` (cert `98ca6feb…42ad`) |
| T4 legacy layouts removed | **done** | hygiene section (55 files, −42 525 B); UAT layouts unaffected (tt/ru/en + symbols all exercised) |
| T5 single subtype source of truth | **done** | hygiene section (`PersonalSubtypeRegistryContractTest` green in the 1 282) |
| T6 `.temp/` decision | **done** | `.gitignore` covers it; `git status` clean of it |
| T1 baseline profile regenerated | **done** | this section (2 278 → 2 433 rules, new hot paths present; cold start 257/270 ms < 400) |

**Phase 1 complete.** What remains: the operator's commit/release decision (2.1.0), and
the roadmap's Phase 2+. The baseline-profile generation's MIUI caveat is recorded above
(generate with only the emulator attached, or `ANDROID_SERIAL=<emulator>`).
