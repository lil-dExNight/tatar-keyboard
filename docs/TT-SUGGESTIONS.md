# TT-SUGGESTIONS — mission report: Tatar word prediction, suggestions, word forms

Status: complete. Phase P0 (evaluation harness + baseline) complete 2026-09-19;
P1 (build-time word-form generator), P2 (dictionary expansion + asset rebuild),
P3 (runtime word-form suggestions), P4 (sentence-start predictions) and P5
(validation, emulator smoke, docs) complete 2026-09-20 — every gate green, the
mission DONE WHEN audited in the P5 section. The whole changeset is
uncommitted and awaits the operator's commit/release decision (no version
bump). The plan is `docs/TT-SUGGESTIONS-PLAN.md`.

## DONE WHEN (mission level, from the plan)

1. The Tatar dictionary ships attested inflected word forms produced by the project's
   own build-time paradigm generator (P1), with pins recalculated and the bigram table
   repacked (P2).
2. After a committed Tatar word + space, the strip offers inflections of that word
   (e.g. `татар` → `татарлар`, `татарча`) in free cells after bigram successors (P3).
3. When the typed Tatar prefix is itself a complete dictionary word, same-stem
   continuations outrank unrelated longer words in the strip (P3).
4. Sentence-start predictions are shown where the strip used to be empty (field start,
   after sentence-ending punctuation) (P4).
5. An evaluation harness reports before/after metrics on a pinned held-out Tatar eval
   set, and no metric regresses (P0, P5).
6. All gates pass: `./gradlew test`, all python suites, `lintRelease`,
   `check-no-internet.sh` (source + APK), release APK ≤ 3 145 728 bytes,
   `rebuild_assets.py --check --allow-known-drift`, `release_check.sh --quick` (P5).

## P0 — evaluation harness and baseline (done)

### What exists

* `scripts/make_eval_set.py` — deterministic builder of the pinned eval set
  `app/src/test/resources/tt_eval_sentences.txt` (1 000 lines, 64 962 bytes, SHA-256
  `d2ff0db52983028d352bbad464006f8c9552fc16b95d4cb5bdc2f724c8c0f619`).
* `tests/suggest_eval/test_suggest_eval.py` — pins the eval file (byte SHA-256, line
  count, format contract), proves builder determinism (two rebuilds into temp dirs are
  byte-identical to the committed file) and the held-out property (no eval line appears
  in the reconstructed train90, at normalized-sentence level).
* `scripts/suggest_eval.py` — python corpus-stat harness over the eval set vs the
  shipped assets; prints `EVAL|metric|value` lines.
* `app/src/test/.../dictionary/engine/TtSuggestEvalTest.kt` — JVM harness driving the
  real shipped indexes (TdictPrefixIndex, TatBigrPrefixIndex) over the eval set; prints
  `EVAL|metric|value` lines, asserts exact pinned counts.

### Eval-set provenance (exactly reproducible)

The eval set is the Tatoeba-origin slice of the conversational held-out the shipped
Tatar bigram table was never trained on:

* `research/corpus/make_conv_train.py` re-run locally over
  `Tatoeba-v2026-07-08.tt.txt.gz` + `OpenSubtitles-v2024.tt.txt.gz` reproduces the
  documented stream **byte for byte**: 218 552 rows, 12 619 164 bytes, SHA-256
  `8420ec0a3c7f329094989ece7cee396ee0dfe987e5e131f91424d7e2e5826b49` — identical to the
  figures recorded in `docs/CORPUS-CONVERSATIONAL-TT.md`. The builder verifies this
  digest on every run and fails closed on mismatch; train90 membership is therefore the
  documented rule, not an approximation.
* Tatoeba-origin rows are the first 92 888 of the stream (Tatoeba converts first);
  held-out rows are `id % 10 == 1` → 9 289 Tatoeba candidates.
* Normalization mirrors the dictionary pipeline: hugging punctuation stripped
  (`dict_tokens` rule), every token through `dictionary_coverage.normalize_word` (NFC,
  lowercase, Tatar alphabet filter), tokens that fail are dropped; sentences kept at
  3..12 surviving words. Dropped at this stage: 2 696 with no surviving tokens, 719
  outside the word bounds, **56 normalized train90 collisions** (case/punctuation
  variants of trained sentences — excluded conservatively), 0 normalized duplicates.
* From 5 818 candidates a deterministic sample of 1 000 is taken: hash order by
  SHA-256 of `seed 20260919 + line` (pinned seed; version-independent, unlike
  `random.sample`), then code-point sorted. No wall-clock fields — rebuilds are
  byte-identical.
* Intermediates (`tt_conv-sentences.txt`) live in `build/tt-suggestions/`, gitignored.

### Licensing

The eval set is derived from Tatoeba (CC BY 2.0 FR); attribution is the header comment
of `tt_eval_sentences.txt` itself. `app/src/main/assets/dictionaries/NOTICE.txt` already
covers Tatoeba explicitly (license, terms URL, citation condition), as does
`app/src/main/assets/bigrams/NOTICE.txt`. The file sits under `app/src/test/resources/`
— the unit-test classpath only — so it is **not packaged into the APK**, and the NOTICE
statement "no sentence from either collection is stored in the app" stays true. No
OpenSubtitles sentence is reproduced anywhere in the repository.

### Baseline numbers (2026-09-19, both harnesses)

Python harness, `python3 scripts/suggest_eval.py`:

| metric | value |
|---|---:|
| `EVAL\|eval_lines` | 1000 |
| `EVAL\|eval_tokens` | 5347 |
| `EVAL\|eval_unique_words` | 2670 |
| `EVAL\|dict_word_coverage_tokens_pct` | 93.4917 |
| `EVAL\|dict_word_coverage_types_pct` | 87.2285 |
| `EVAL\|inflected_token_share_pct` | 45.1281 |
| `EVAL\|bigram_pairs_total` | 4347 |
| `EVAL\|bigram_head_coverage_pct` | 75.3623 |
| `EVAL\|nextword_top3_hit_pct` | 9.5468 |
| `EVAL\|nextword_top3_hit_covered_pct` | 12.6679 |

The inflection heuristic (multi-letter surface suffixes, longest match, stem ≥ 2 code
points) is a documented lower bound; its 45.13 % lands next to the ~45 % corpus figure
in the plan's morphology research — a sanity signal, not a calibration target.

JVM harness, `./gradlew :app:testDebugUnitTest --tests '*TtSuggestEvalTest*'`
(exact pinned counts in the test; prefix completion is over the 2 670 unique words):

| metric | value |
|---|---:|
| `EVAL\|prefix_cp1_words` / `..._hits` | 2670 / 64 |
| `EVAL\|prefix_top3_cp1_pct` | 2.3970 |
| `EVAL\|prefix_cp2_words` / `..._hits` | 2668 / 301 |
| `EVAL\|prefix_top3_cp2_pct` | 11.2819 |
| `EVAL\|prefix_cp3_words` / `..._hits` | 2626 / 741 |
| `EVAL\|prefix_top3_cp3_pct` | 28.2178 |
| `EVAL\|nextword_pairs` | 4347 |
| `EVAL\|nextword_head_covered` | 3276 |
| `EVAL\|nextword_top3_hits` | 415 |
| `EVAL\|bigram_head_coverage_pct` | 75.3623 |
| `EVAL\|nextword_top3_hit_pct` | 9.5468 |
| `EVAL\|nextword_top3_hit_covered_pct` | 12.6679 |
| `EVAL\|strip_empty_sentence_start_pct` | 100.0000 |

The two harnesses agree to the fourth decimal on every shared metric (head coverage,
next-word hit rates) — the python reader and the runtime index read the same table the
same way. `strip_empty_sentence_start_pct` is pinned at 100.0000 as the pre-P4
contract: at a sentence start there is no prefix and no context word, so the strip is
always empty; P4 will drive it toward 0 and re-pin.

Reading of the baseline: one-letter prefixes almost never complete (2.40 %), three
letters complete a bit over a quarter of words (28.22 %), and the static bigram table
predicts the next word in 9.55 % of adjacencies (12.67 % when the head is covered at
all — coverage is 75.36 %). These are the numbers P2–P4 must move.

### Reproduce

```
# Eval set (needs the licensed corpus inputs under research/corpus/, gitignored):
python3 scripts/make_eval_set.py          # rebuilds app/src/test/resources/tt_eval_sentences.txt

# Python harness:
python3 scripts/suggest_eval.py           # prints EVAL|... lines

# JVM harness:
./gradlew :app:testDebugUnitTest --tests '*TtSuggestEvalTest*'

# Contract tests:
python3 tests/suggest_eval/test_suggest_eval.py
```

## P1 — build-time word-form generator (done 2026-09-20)

### What exists

* `scripts/wordform_gen.py` — the generator. Suffix patterns are Cyrillic literals plus
  archiphonemes (`A` а/ә, `I` ы/е, `U` у/ү, `Y` ый/и, `G` г/к, `D` д/т, `L` л/н,
  `T` д/т/н for the ablative) with a `(consonant~vowel)` alternation. Noun paradigm:
  plural, six cases, six possessives, the special post-3sg-possessive cases
  (баласы+н/на/нда/ыннан) and plural+case combos — 21 forms. Verb paradigm: imperative
  2sg/2pl, present and -DI past with 1sg/2sg/3sg/2pl/3pl (plus their -мA- negatives),
  3sg of -GAn/-Ir/-AčAk/-sA/future-negative, gerunds -Ip/-GAč/-GAnčI/-мыйча,
  participles -GAn/-UčI/-AsI, masdar -U, intention -mAkčI — 38 forms. Derivational:
  -čA/-lIk/-lI/-sIz/-čI/-dAş/-rAk — 7 forms. CLI: `paradigm STEM [--pos …]`,
  `candidates` (sorted unique `form<TAB>stem<TAB>label` rows for every listed word,
  atomic write, JSON stats), `group` (suffix-stripping stem analysis with generator
  round-trip validation; multi-stem analyses are ambiguous and skipped fail-closed).
* `scripts/wordform_exceptions_tat.tsv` — 26 rows (3 voicing, 7 pronoun, 16 override),
  format documented in its header, loaded fail-closed, pinned by SHA-256 in the tests.
* `tests/wordform_gen/test_wordform_gen.py` — 58 tests: full golden paradigms
  (татар/өй/яз), harmony/archiphoneme/assimilation units, exception behavior and
  fail-closed loading, candidate/group determinism, CLI smoke.
* `scripts/wordform_kaikki_check.py` — optional dev-time validator (network on first
  run, cache in `build/`, never in gates; kaikki.org data is CC BY-SA 4.0 and stays out
  of every committed or shipped artifact).

### Design decisions beyond the plan (all verified against kaikki.org tables)

* Vowel sets extended: э is front, я/ю are back; a final у/ү/ю/я right after another
  vowel is a diphthong glide and does not decide harmony (эшләү front, дию front).
* Russian loans cannot be told from native front stems by surface rules (совет vs
  исем), so mixed-harmony and marker-letter stems are generated in BOTH harmony
  variants (советларга and советләргә); P2's attestation filter keeps the real one.
* Vowel-final possessives split by final vowel: и/у/ү-final stems take full endings
  (әбием, суым, суыбыз), other vowel-final stems bare ones (абам, аракым); 3sg is
  bare -ы/-е after у/ү (суы — the planned су exception row turned out regular and was
  removed), -сы/-се elsewhere (абасы, әбисе, аракысы).
* Simple future is lexically split, not a single -Ir: monosyllabic consonant stems
  take -ар/-әр (язар, китәр, керәр), longer consonant stems -ыр/-ер (әлсерер,
  сакланыр), vowel stems -р (эшләр), monosyllabic vowel stems -яр (дияр). **This
  amends the plan's `future -Ir` and the golden `яз→языр`: kaikki conjugation tables
  give язар/язармын (языр does not occur there), китәр, атар.** The -ыр/-ер
  monosyllable class is exception rows (бар, бул, ал, кал, тул, йөр).
* Ablative is three-way: -нан/-нән after nasals (урманнан, таңнан, моңнан), -тан/-тән
  after voiceless, -дан/-дән elsewhere; locative and the -DI past keep д after nasals
  (урманда, минде). Not in the plan's suffix list; caught by the kaikki check.
* Negative present is -мый/-ми (front и: китми, not *-мәй), negative gerund
  -мыйча/-мичә (китмичә) — the plan wrote -mAy/-mAyčA.
* дию needs no exception: ди contracts regularly (ди, диде, дигән, дип, дию, дияр);
  the plan's «диген» is a typo for дигән. уку is genuinely suppletive: укы- for
  present/past (укый, укыды) but ук- before future/negative suffixes (укар, укачак,
  укмый, укмас, укмады, укмаган, укмыйча) plus masdar уку — 8 override rows.
  кара→кара (present) and ю→юу (masdar) are the other verb overrides.
* -AsI is generated for consonant stems only (язасы, киләсе); vowel-stem shapes are
  not standard. -lIk/-lI do not assimilate in Tatar (дуслык, китаплык); -dAş does
  (юлдаш, дусташ); -čA has only ча/чә.
* Stem extraction got a CLI: `group` (the plan described the feature but named no
  entry point).

### Exception table (26 rows)

voicing: китап→китаб, төп→төб, балык→балыг · pronouns: мин, син, ул, бу, без, сез,
алар (мин→минем/миңа/мине/миндә/миннән; бу→моның/моңа/моны/монда/моннан — the plan's
«бұңар» rejected) · overrides: кара present; the six future rows; the eight-row укы
cluster; ю masdar. Extending the unseeded voicing class (see below) is an operator
decision: the table ships data, so it must not be copied from kaikki (CC BY-SA 4.0) —
the plan allows kaikki only as a dev-time validator.

### Kaikki agreement (2026-09-20, kaikki.org-dictionary-Tatar.jsonl)

| metric | value |
|---|---:|
| kaikki noun headwords with any forms / with full declension tables | 837 / 179 |
| in-scope kaikki forms (P1 labels) | 3913 |
| covered by the generator | 3535 |
| recall | **90.34 %** |
| six plain cases / plural cases | 97.8 % / 97.6 % per label |
| possessives p1/p2/p1pl/p2pl and post-3sg cases | 80.7 % per label |
| noun.p3 / noun.p3pl | 89.5 % |
| extra generated forms on shared labels | 973 / 4210 (23.11 %) |

The 378 missed forms are fully accounted for: 290 belong to 29 unseeded п→б/к→г
voicing stems (акчарлак, аргамак, дуслык, мәктәп, цирк…), 46 are back-harmony
variants inside the kaikki entries for ел/чәч (our front forms match the literary
norm), 42 come from two broken kaikki templates (юл → «юлдн», кала → «кала»).
Unexplained generator-side misses: 0. The 23 % "extra" is mostly the dual-harmony
loan variants by design; P2 removes unattested forms.

### Reproduce

```
python3 tests/wordform_gen/test_wordform_gen.py          # 58 tests
python3 scripts/wordform_gen.py paradigm татар           # full paradigm
python3 scripts/wordform_gen.py candidates --words W.tsv \
    --exceptions scripts/wordform_exceptions_tat.tsv --out OUT.tsv
python3 scripts/wordform_gen.py group --words W.tsv \
    --exceptions scripts/wordform_exceptions_tat.tsv --out GROUPS.tsv
python3 scripts/wordform_kaikki_check.py --download      # dev-time, network
```

## P2 — dictionary expansion and asset rebuild (done 2026-09-20)

### What exists

* `scripts/rebuild_assets.py --only {tatar,russian}` — one-sided rebuild: the selected
  side's dictionary (Tatar via the new word-form stage), bigram table, and pins; the
  other side's inputs are not required, and its assets and contract pin blocks are
  verified byte-identical by a SHA-256 snapshot taken before the first step and checked
  after the pin write. The final check still verifies all four assets. `--only` is
  rejected together with `--check`.
* `build_admitted_wordforms` (in `rebuild_assets.py`) — the word-form stage between the
  word-list build and the pack. Stems: the full pre-cutoff dictionary composition
  (1.8.4 shipped ∪ accepted queue). Candidates: `wordform_gen.generate_all` per stem.
  Admission: a candidate enters only if attested in the pipeline's frequency sources —
  the Leipzig `*-words.txt` of the same two corpora that train the Tatar bigram table,
  plus the committed `conv-freq-tt.tsv` — with frequency = corpus count. A form already
  in the composition is a no-op (keeps its frequency). Deterministic (sorted stems,
  atomic writes, no clock fields) and fail-closed (a broken exceptions table or corpus
  row aborts the rebuild).
* `scripts/dict_accept.py` — per-language `--only` on `pack`, plus `--extra-entries TSV`
  and `--top N` (both require `--only`); extra entries are validated fail-closed
  (canonical normalization, positive u32, no duplicates) and a form already in the
  composition is a no-op. The shipped Tatar top-N lives in `rebuild_assets.py` as
  `TATAR_DICTIONARY_TOP` next to the bigram H/K parameters.
* New tests: `tests/rebuild_assets/test_rebuild_assets.py` gained 9 (canned
  dict-accept argv, `--only` input gating per side, untouched-side snapshot, word-form
  stage admission/merge/determinism/fail-closed on a synthetic tree).
* `scripts/typo_pack.py` re-pinned to the new asset; the three typo sets regenerated.

### Inputs (all verified against their documented digests)

* Leipzig `tat_mixed_2015_1M` + `tat_web_2018_1M` downloaded to `~/corpora-leipzig/`;
  the extracted `*-words.txt` match the D1A-documented SHA-256s (`b4577bcc…`,
  `3caac1a7…`).
* `build/tt-suggestions/tt_conv-sentences.txt` matches the documented stream exactly
  (218 552 rows, SHA-256 `8420ec0a…6b49`); `tt_conv_train90-sentences.txt` derived from
  it by the documented `id % 10 != 1` rule (196 696 rows) into `~/corpora-leipzig/`.
* 1.8.4 baseline assets from the 1.8.4 release commit (`6306014a`; the brief's tag name
  `4ca191a7` does not resolve in this clone — the content is what matters and both files
  match the `BASELINE_SHA256` pins in `dict_accept.py`, which is what those pins are
  for).

### Admission stage numbers

103 484 stems (245 without a harmony vowel skipped), 37 771 dual-harmony stems,
9 110 711 generated (form, stem, label) rows, 89 399 rows already in the composition,
8 868 456 rows unattested → **123 191 distinct forms admitted** (TSV 2 834 884 bytes,
SHA-256 `9f51f1007c29e86ace083d87f6123811d8e94bc45aac1591a6b848c9764a3ef6`, kept in
`build/rebuild_assets/wordforms-admitted-tt.tsv`).

`tat_news_2015_1M` is deliberately NOT an attestation source: it is the frozen written
held-out of the bigram measurements (E5a) and stays out of shipping-data decisions. The
consequence is measurable and accepted: admitted forms carry two-corpus counts (plus the
conversational file), so a form whose three-corpus count is ≥ 10 sits in the 1.8.4
dictionary already, and every NEW admitted form enters with frequency ≤ 9.

### Choosing N (the entry cap)

`dict_accept.py pack --only tat --extra-entries …` in measure mode:

| N | compressed bytes | raw bytes | forms entered | conv words entered | 1.8.4 words displaced |
|---:|---:|---:|---:|---:|---:|
| 100 000 | 501 683 | 1 162 870 | 0 | 303 | 303 |
| 110 000 | 542 493 | 1 276 289 | 9 052 | 948 | 0 |
| 115 000 | 560 813 | 1 332 382 | 13 786 | 1 214 | 0 |
| 118 000 | 572 528 | 1 364 514 | 16 575 | 1 425 | 0 |
| 120 000 | **580 805** | 1 387 348 | 18 409 | 1 591 | 0 |

Caps: compressed ≤ 580 000 (format budget 600 000), raw ≤ 1 400 000. N = 100 000 admits
NOTHING (every new form's frequency is below the old cutoff — measured, not assumed).
**N = 110 000 chosen**: largest measured size inside both caps; 120 000 breaks the
compressed cap by 805 bytes. The parameter lives in `rebuild_assets.py` as
`TATAR_DICTIONARY_TOP` with this table referenced.

### The rebuilt Tatar dictionary

110 000 entries = 100 000 baseline (1.8.4 Leipzig) + 948 accepted conversational words
(303 could enter at the old cap) + **9 052 admitted generated forms**; zero 1.8.4 words
displaced. 542 493 bytes compressed (was 501 683, +40 810), raw 1 276 289 (was
1 162 870). Asset SHA-256 `e653ef6e…fa96ed`, raw SHA-256 `3634f021…2518`.

### The repacked Tatar bigram table

Schema 3 re-bound to the new dictionary raw SHA-256 (header link + pin). Head set
**identical** (10 204 heads): the new entries all sit below the H = 10 132 frequency
cutoff, and the same three pairless `-гәнчә` converbs are dropped — drift stays 3/0,
`scripts/known_asset_drift.json` keeps its numbers (reason re-dated for the P2
re-verification). Successors now resolve 10 words the old dictionary did not carry, so
10 heads changed their visible lists (e.g. `бервакытта` gains `бернәрсәдә`); pairs
40 734 → 40 735, success vocabulary 6 502 → 6 508, raw 134 664 → 134 938, compressed
81 028 → 81 476.

Russian side: both assets byte-identical to HEAD (`git status` under
`app/src/main/assets` shows only the two Tatar files), their pin blocks untouched —
also enforced inside the rebuild by the `--only` snapshot guard.

### Recalibrations (old → new)

| Where | What | Old | New |
|---|---|---|---|
| `typo_pack.py` pins + smoke tests | asset/raw SHA-256, entry count | `cb34fe7d…`/`922d14f2…`, 100 000 | `e653ef6e…`/`3634f021…`, 110 000 |
| typo set class #1 (E3a + `tests/typo_pack`) | size, SHA-256 | 87 360, `da186d8e…` | 96 118, `1bf09f40…` |
| typo set class #2 (E3b + `tests/typo_pack`) | size, SHA-256 | 99 654, `55139280…` | 109 649, `89ef2646…` |
| typo set class #3 (E3b + `tests/typo_pack`) | size, SHA-256 | 99 642, `48254141…` | 109 637, `539a701a…` |
| E3a recovery@3 (printed, not gated) | class #1 | 7.2835 % of 87 360 | 6.6959 % of 96 118 (12.0914 % over the 53 228 prefixes where the fuzzy pass fires) |
| E3b recovery@3 (printed, not gated) | combined | 2.2331 % of 286 681 | 2.0526 % of 315 404; class #2 0.0381 → 0.0347 %, class #3 0.0000 → 0.0000 %; verdict BELOW before and after |
| `AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY` | rank-10 000 frequency | 403 | **411** (`чиновниклар`; rank 20 000 = 153 `хәмзин`) |
| `TdictValidatorTest` provenance | entries / raw size | 100 000 / 1 162 870 | 110 000 / 1 276 289 |
| `TatBigrValidatorTest` provenance | heads / raw / pairs | 10 204 / 134 664 / 40 734 | 10 204 / 134 938 / 40 735 |
| `tests/dict_accept` live-tree rule | tat after-numbers | 100 000 entries, added = displaced | 110 000 entries, +10 000 added / 0 displaced (test renamed) |
| `tests/dictionary_pack` committed-asset test | expected count | 100 000 | 110 000 |

About the 403 → 411 threshold: the P2 rebuild did NOT move rank 10 000 — the old and new
artifacts both have `чиновниклар` (411) there, because every P2 addition sits at
frequency ≤ 10. The quoted 403/149 were the 1.8.4 measurements; the 1.9.0/1.9.1
conversational repacks moved the rank to 411/153 without the contract-mandated
re-measurement. P2 re-measures per the contract and the constant now tells the truth
about the artifact (the boundary values in `TdictPrefixIndexAutocorrectTest` and
`AutocorrectControllerTest` moved 402/403 → 410/411 with it).

NOT re-pinned, measured unchanged:

* The D1A 22-prefix query review (`docs/archive/dictionary/DICTIONARY-D1A-QUERY-REVIEW.tsv`):
  the new top-3 audit on the rebuilt asset is row-for-row identical, so
  `TATAR_REVIEW_DATE` stays 2026-08-24 and `RealDictionaryPrefixIndexTest` keeps passing
  unmodified. New entries can only fill a prefix that had fewer than three completions —
  they all sit at or below the old cutoff band (≤ 10), so no recorded incumbent moved.
* `TtSuggestEvalTest` exact counters: prefix hits 64/301/741 at cp1/2/3, next-word
  3 276 covered / 415 hits of 4 347 pairs — identical on the new assets (asserts green,
  no re-pin).
* `scripts/known_asset_drift.json` numbers: tt 3/0, ru 2/0 — same words, same rule.

### Eval coverage before → after

Python harness (`scripts/suggest_eval.py`):

| metric | baseline (P0) | after P2 |
|---|---:|---:|
| `dict_word_coverage_tokens_pct` | 93.4917 | **94.3146** (+0.8229 pp) |
| `dict_word_coverage_types_pct` | 87.2285 | **88.8764** (+1.6479 pp) |
| `inflected_token_share_pct` | 45.1281 | 45.1281 (eval-text property) |
| `bigram_head_coverage_pct` | 75.3623 | 75.3623 |
| `nextword_top3_hit_pct` | 9.5468 | 9.5468 |
| `nextword_top3_hit_covered_pct` | 12.6679 | 12.6679 |

JVM harness (`TtSuggestEvalTest`): identical to baseline on every counter (see above).
The next-word metrics are flat because the head set is identical and none of the 10
re-resolved successor lists belongs to a head that occurs in the eval pairs; the metric
P2 was meant to move — dictionary coverage — moved. Prefix top-3 completion is a
top-of-distribution metric and does not see bottom-of-ranking additions; moving it is
P3's job (same-stem boost), not P2's.

### Gates after P2

* `python3 scripts/rebuild_assets.py --check --allow-known-drift` — green (tt 3/0 and
  ru 2/0 known, all four pin sets match).
* Python suites: **421 tests, 0 failures** (14 files; 412 → 421: +9 for the `--only`
  and word-form-stage contracts).
* `./gradlew test --rerun-tasks` — **1 109 tests, 0 failures**.
* `./gradlew lintRelease` — green; `assembleRelease -PskipReleaseSigning` — green,
  unsigned APK **1 858 375 bytes** ≤ 3 145 728 (was 1 816 951 at 1.9.15, +41 424 ≈ the
  +40 810 dictionary and +448 table growth).
* `bash scripts/check-no-internet.sh` on the release APK — both levels OK.
* Rebuild determinism: the standalone measurement pack and the `rebuild_assets.py --only
  tatar` run produced byte-identical assets (same SHA-256s).

### Reproduce

```
# inputs (licensed, not committed):
#   ~/corpora-leipzig/{tat_mixed_2015_1M,tat_web_2018_1M}-{sentences,words}.txt
#   ~/corpora-leipzig/tt_conv_train90-sentences.txt   (recipe: docs/CORPUS-CONVERSATIONAL-TT.md)
#   build/tt-suggestions/shipped-1.8.4/               (git show <1.8.4 commit>; pinned in dict_accept.py)
python3 scripts/rebuild_assets.py --only tatar --baseline build/tt-suggestions/shipped-1.8.4
for c in 1 2 3; do python3 scripts/typo_pack.py build \
  --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
  --layout-dir app/src/main/res/xml --output /tmp/class$c.tsv --edit-class $c; done
```

## P3 — runtime word-form suggestions (done 2026-09-20)

### What exists

* `latin/suggestions/TatarSuffixRules.kt` — the runtime suffix machinery. A fixed table of **167
  concrete suffix forms**, grouped in review order by paradigm slot (plural, five oblique cases,
  possessives, post-3sg cases, verb tenses with their person composites, gerunds, participles,
  masdar, seven derivational suffixes), sorted once at class init with a fail-closed
  strictly-increasing check. Two surfaces:
  - `isInflectedContinuation(...)` — zero-allocation membership: a binary search comparing a
    suffix against the candidate's remainder read straight off the mapped buffer as one or two
    contiguous byte ranges (a schema-2 word is a shared prefix of its block's first word plus a
    suffix of its own, so the remainder never needs materializing).
  - `generateForms(stem, out, maxOut)` — the bounded after-word generation: plural + 5 cases +
    3sg possessive + its 4 special cases + present/past/future 3sg + -ып gerund + -GAn + masdar +
    -чA — 18 forms per harmony variant, at most two variants (mixed-harmony and Russian-marker
    stems), so ≤ 36 candidates, capped by `maxOut`. Runs once per committed word on the engine
    worker, never on the lookup hot path; its small bounded allocations are deliberate.
* `TdictPrefixIndex.frequencyOf` — exact whole-word frequency reusing the block search: one
  `lowerBound` + equality + a cached-block frequency read. 0 is the absent sentinel (schema-2
  frequencies are strictly positive). The byte-level form allocates nothing (pinned by a
  ThreadMXBean test, same pattern as the fuzzy pass's).
* **Same-stem boost (prefix slot).** `TdictPrefixIndex` takes an `InflectedSuffixTable` per
  engine (`TatarSuffixRules` for Tatar, null for Russian). When the typed prefix is itself a
  complete dictionary word of **at least four code points**
  (`MIN_SAME_STEM_BOOST_PREFIX_CODE_POINTS`, mirroring `AutocorrectPolicy.MIN_WORD_CODE_POINTS`) —
  detected without an extra search, `lowerBound` has already landed on the entry — `collectExact`
  runs dual-track in one pass: candidates whose remainder is a table suffix rank before unrelated
  continuations, the frozen (frequency desc, code-point asc) order holds inside each group, the
  typed word itself stays excluded, and the candidate COUNT is unchanged (min(range, 3)), so the
  fuzzy fill, E4c learning and autocorrect all see the same shape. `scanBlockRange` now also
  reports each candidate's remainder piecewise and carries two take-masks; with no table, a
  non-word prefix or a short complete word the pass is byte-identical to the frozen D1 behavior
  (pinned). The length gate is the 2026-09-20 refinement — short complete words are common
  mid-typing states whose inflections must not displace unrelated continuations; see the dated
  footnote below for the measured both-variants comparison.
* **After-word forms (NEXT_WORD slot).** `CompositePrefixComputer.predict` appends
  `AfterWordForms` forms — generated by the rules, filtered to dictionary words via
  `frequencyOf`, ranked by frequency — into the cells the bigram successors leave free. Bigram
  successors keep priority and are never displaced or duplicated; a broken forms source fails
  closed to the bigram list. Crucially, forms are NOT offered while no bigram source is attached:
  the NEXTWORD-RACE repair re-requests only while the band holds no active-language word, and a
  forms-only band painted from "not attached yet" would suppress it — hiding the bigram
  successors that outrank forms until the next keystroke.
* **Wire-in.** `MappedDictionaryEngine.start` gains `suffixTable`/`afterWordFormsFactory`;
  `MappedEngineHandle.start` gains one `suffixRules: TatarSuffixRules?`; LatinIME passes the
  object only when the artifact's language tag is `tt_RU` (the registry entry decides, robust
  across repacks of the family). `SuggestionsController` is deliberately UNCHANGED: the merge
  point that owns ranking and has dictionary access is the composite computer, and downstream the
  merged list is an ordinary NEXT_WORD list — sessions, tap paths, the emoji tail pin, the
  companion-language fill and the personal-dictionary merge all behave exactly as before (their
  test suites pass unmodified).

### Consistency with P1 (`scripts/wordform_gen.py`)

The table is the set of single-suffix remainders the P1 paradigms attach to a stem, cross-checked
by extraction over representative stems. Deliberate differences, all absorbed by the
dictionary-presence filter: suffix CHAINS are not listed (татарларның reaches the boost through
its intermediate word — татарлар by «лар» at prefix татар, татарларның by «ның» at prefix
татарлар); the past-tense 1pl -к composites (яздык) are listed although P1 emits no 1pl persons;
-сың/-сең cover the и-final stems whose contracted present base IS the stem (ди+сең); the
п→б/к→г voicing exceptions, suppletive pronouns and per-stem verb overrides are not replicated
(китап generates китапы — absent — while the real китабы is not generated, so neither is
offered; pinned in `TatarAfterWordFormsTest`).

### Contract amendment (the frozen D1 ranking)

Amended for exactly one case: the typed prefix is a complete dictionary word of at least four
code points in an engine that carries a suffix table. `docs/archive/PROPOSALS.md` is NOT edited;
this section plus the pins in `TdictPrefixIndexSameStemBoostTest` are the record. Rollback is one
line at the LatinIME seam (pass null) — the whole boost is inert without an injected table.

### Measured behavior (real shipped assets)

| prefix | before (frozen D1) | after (gated boost) |
|---|---|---|
| татар (5 cp) | татарстан, татарстанда, татарстанның | **татарлар, татарча, татарлары** |
| су (2 cp) | сум, сугыш, сумга | unchanged (below the gate) |
| өй (2 cp) | өйрәнү, өйдә, өйрәнергә | unchanged (below the gate) |
| кит (3 cp) | китте, киткән, китап | unchanged (below the gate) |
| тата (not a word) | татар, татарстан, татарстанда | unchanged (pinned) |
| бар (3 cp) | барлык, бара, бары | unchanged (below the gate) |

Russian: the engine never carries a table, so e.g. prefix майор stays `майора, майором, майору`
(pinned). Counter-proof that the injection is the only differentiator: the same asset opened
WITH the Tatar table reorders майор to `майора, майору, майором` (майор+у — у is a Tatar suffix
form — jumps over the unrelated майором).

After-word: татар + space offers the bigram successors first, then the forms — the
controller-level test drives the real engine and shows `белән, татарлар, татарча`, and a tap on a
form cell commits through the NEXT_WORD insertion path. (The after-word forms act on COMMITTED
words; the prefix-length gate does not apply to them.)

### Eval old → new (TtSuggestEvalTest; committed assets, pinned eval set)

| metric | P2 baseline | P3 |
|---|---:|---:|
| prefix_top3_cp1 hits (of 2 670) | 64 (2.3970 %) | 64 (2.3970 %) |
| prefix_top3_cp2 hits (of 2 668) | 301 (11.2819 %) | 301 (11.2819 %) |
| prefix_top3_cp3 hits (of 2 626) | 741 (28.2178 %) | 741 (28.2178 %) |
| same-stem top-3 at stem-length prefix (new) | 988 of 1 716 (57.5758 %) | **1 078 (62.8205 %)** |
| nextword pairs / covered / hits | 4 347 / 3 276 / 415 | unchanged |
| strip_empty_sentence_start_pct | 100.0000 | 100.0000 (P4 moves it) |

The honest read: 1 716 unique eval words decompose as stem+table-suffix with the stem in the
dictionary, and for them the gated boost lifts top-3-at-stem from 57.6 % to 62.8 % (+5.2 pp) at
zero cost to the cp1–cp3 proxies — the gate exempts every prefix those metrics type. The
22-prefix D1A audit is untouched: `RealDictionaryPrefixIndexTest` opens the index without a
table, which IS the frozen behavior.

> Footnote 2026-09-20 (gate refinement): the first P3 cut engaged the boost on ANY complete-word
> prefix. Measured both ways on the pinned eval set: ungated, cp1/cp2/cp3 fell 64/301/741 →
> 29/235/685 while same-stem reached 1 201 (69.9883 %); gated at ≥ 4 code points, cp1–cp3 are
> byte-identical to the baseline and same-stem keeps 988 → 1 078. The gate keeps 42 % of the
> boost's gain for none of the regression — kept, per the plan's "eval metrics improve" intent.
> The ungated numbers stay here as the documented evidence for the decision.

### Perf and budgets

* Existing p95 harness (22 audit prefixes): median 0.004 ms, p95 0.006 ms; one-letter к fanout
  p95 0.114 ms — unchanged path.
* Boost engaged over six complete-word ≥ 4-code-point prefixes on the real Tatar asset (new
  `TdictPrefixIndexSameStemBoostTest` perf test, gated variant): median 0.006 ms, **p95 0.010 ms**;
  the ungated variant of the same harness measured median 0.027 ms, p95 0.086 ms — both are
  orders of magnitude under the 5 ms budget.
* Zero-allocation: the engaged boost allocates within 8 B/lookup of the frozen pass on identical
  result lists (A/B pinned); `frequencyOf` byte form ≤ 8 B/call present or absent.
* JVM: **1 152 tests, 0 failures** (1 109 → 1 152: +43, incl. the gate pins). Python: 421 tests
  (14 files), 0 failures — untouched. `lintRelease` green;
  `rebuild_assets.py --check --allow-known-drift` green (no asset changes in P3).

### Recalibrations (old → new)

| Where | What | Old | New |
|---|---|---|---|
| `TtSuggestEvalTest` | prefix hits cp1/cp2/cp3 | 64 / 301 / 741 | unchanged (the gate exempts 1–3-cp prefixes) |
| `TtSuggestEvalTest` | same-stem words / hits / control | — | 1 716 / 1 078 / 988 (new pins) |

NOT re-pinned, verified unchanged: the D1A 22-prefix audit (rules-free index), the next-word
eval counters, the autocorrect frequency boundary tests, both emoji-suggest suites, the language
priority and companion-fill suites.

## P4 — sentence-start predictions (done 2026-09-20)

### What exists

* `scripts/sentstart_pack.py` — the packer. Counts the sentence-INITIAL tokens of the two
  Leipzig tt corpora (`tat_mixed_2015_1M`, `tat_web_2018_1M`; the same corpora that train
  the Tatar bigram table), normalized exactly like the dictionary pipeline (hugging
  punctuation stripped by the `dict_tokens` rule, then
  `dictionary_coverage.normalize_word`: NFC, lowercase, Tatar alphabet filter), filtered to
  the words the SHIPPED Tatar dictionary carries — decoded with the `dictionary_pack`
  reader APIs, deliberately the stricter choice over the Leipzig `*-words.txt` lists: the
  table can never offer a word the keyboard does not itself know, and the runtime contract
  "every sentence-start cell is a dictionary word" is true by construction. Top 64 by
  (count desc, word asc), atomic write, fail-closed, no wall-clock fields; CLI mirrors the
  emoji pack scripts (`build --sentences … --dictionary … --output … [--top N]`, JSON
  stats, exit 2 fail-closed / 4 guardrail).
* `app/src/main/assets/dictionaries/tatar_sentstart_v1.txt` — the asset: a `#` header with
  the Leipzig CC BY 4.0 attribution and the provenance, then 64 `word<TAB>freq` rows,
  1 838 bytes, SHA-256 `f84e8207…c22216`. Built 2026-09-20 from 2 000 000 corpus sentences
  (1 866 945 first tokens accepted, 133 055 dropped by normalization — digits, Latin,
  punctuation-only). Top-10: бу (72 870), ул (45 080), ә (36 362), бүген (30 538),
  аның (26 859), шулай (24 127), алар (22 610), әлеге (21 298), әмма (18 443),
  шул (17 449). The frequency floor of the table is 3 984 (нәтиҗәдә).
* `latin/suggestions/SentStartIndex.kt` — the immutable reader (file order IS the ranking;
  fail-closed parse, `EMPTY` on any unusable input) plus the `SentStartSource` seam;
  `latin/suggestions/SentStartSources.kt` — the lazy one-per-process background load
  (`AssetSentStartPreparation`), the exact shape of the emoji-suggest loading seam: a user
  who never opens a Tatar field at a sentence start never reads the asset.
* `TatarWordUtils.isSentenceStartContext(text, cacheReachedTextStart)` — the detector, a
  new pure function next to `extractNextWordContext` (which is untouched). True at a proven
  field start (empty or all-spaces cache with the start-of-text provenance of
  docs/NEXTWORD-RACE.md) and after a maximal run of exactly `.`/`!`/`?`/`…` followed by one
  or more U+0020 — with a letter before the run (`5. ` is a number, not a sentence end).
  Closing quotes/brackets after the period are an accepted miss: over-matching offers
  sentence starts mid-sentence, the worse direction. Allocation-free, bounded by the cache
  size, pinned in `TatarWordUtilsTest`.
* Wire-in (`SuggestionsController`): the NEXT_WORD request path, on an empty context word,
  now tries the sentence-start band FIRST. The paint is synchronous — the table is static,
  so no engine request, no token, no callback — and it is stamped `NO_SESSION`, the exact
  protection `clearToReservedBand` buys, so no in-flight engine result (the word before the
  period above all) can land on top of it. The band binds the EMPTY context — the one value
  the NEXT_WORD path never binds — and taps commit through the existing E5d predicted-word
  editor path. `EditorSurface.isAtSentenceStart()` is the new seam (default false: every
  pre-P4 surface keeps compiling with no behavior change); LatinIME wires it to the
  detector over the live cache, production load factory included.
* `InputLogic.commitPredictedWord` learned one case: an empty bound context is valid only
  while the LIVE position is still a sentence start (the detector re-runs at tap time) —
  without it an empty expected context would match any context-free position and a stale
  tap would edit there.
* Tests: `tests/sentstart_pack/test_sentstart_pack.py` (34: the packer contract on
  synthetic fixtures — tokenization, the dictionary filter, ranking, guardrails,
  fail-closed writes — plus the committed-asset pins), `TatarSentStartAssetTest` (the
  pinned asset: SHA-256, 64 records, sorting, Tatar alphabet, membership in the real
  shipped dictionary, the NOTICE credit, the production reader against the committed
  bytes), `SentStartIndexTest` (the fail-closed reader), `SuggestionsControllerSentStartTest`
  (18: the band at field start and after `. `, the suppression evidence — zero engine
  requests at a sentence start —, the Russian slot unchanged and never loading the table,
  tap/stale-tap, lazy load once-per-process, failed/deferred loads, the gates).

### Contract amendment (the frozen "no prediction after punctuation")

Amended deliberately and narrowly. The amendment is a DETECTOR plus a new answer source,
not a relaxation of `extractNextWordContext`: the bigram table is still never consulted
after punctuation (a sentence boundary resets the context — sentence start suppresses
bigram successors AND the P3 after-word forms for the slot by construction, since no engine
request is issued at all), the separator rule of E5d is byte-identical, and the emoji tail
cell, the companion fill (its query would be the empty context, which the fill rejects
itself) and the personal merge are untouched. The Russian slot ships no table and is
byte-identical to before. `docs/archive/PROPOSALS.md` is NOT edited; this section and the
pins in `SuggestionsControllerSentStartTest`/`TatarWordUtilsTest` are the record.

Casing: the cells are shown and inserted exactly as the table stores them (NFC lowercase) —
the NEXT_WORD display rule, applied to the new slot. Sentence-initial capitalization would
need the shift state plumbed into the controller, which no seam carries today; recorded as
a deliberate v1 limitation, not an oversight.

### Eval old → new (TtSuggestEvalTest; committed assets, pinned eval set)

| metric | P3 | P4 |
|---|---:|---:|
| strip_empty_sentence_start_pct | 100.0000 | **0.0000** |
| sentstart_top3_hits (of 1 000 eval sentences; new) | — | **123** |
| sentstart_top3_hit_pct (new) | — | **12.3000** |

All other counters byte-identical to P3 (prefix 64/301/741, same-stem 1 078/1 716 with the
988 control, next-word 3 276 covered / 415 hits of 4 347 pairs). The honest read of 12.3 %:
the table is trained on Leipzig news/web while the eval set is Tatoeba-conversational, so
this is a cross-domain lower bound; the strip-empty metric is the one the phase exists to
move, and it moved to zero — every sentence start now shows the top of the table.

### Gates after P4

* `./gradlew test --rerun-tasks` — **1 191 tests, 0 failures** (1 152 → 1 191: +39).
* Python suites: **455 tests, 0 failures** (421 → 455: +34; the suite count 14 → 15 files).
* `./gradlew lintRelease` — green; `python3 scripts/rebuild_assets.py --check
  --allow-known-drift` — green (no binary asset changes in P4).
* `assembleRelease -PskipReleaseSigning` — unsigned APK **1 867 899 bytes** ≤ 3 145 728
  (was 1 858 375 after P2, +9 524 ≈ the 1 838-byte asset plus the new code).
* `bash scripts/check-no-internet.sh` on the release APK — both levels OK.
* `scripts/release_check.sh` needed NO change: its binary-asset gate pins the four
  `.tdict.zlib`/`.tatbigr.zlib` files by name and its tree-vs-APK set comparison is scoped
  to `assets/emoji/` only, so a new text asset under `assets/dictionaries/` trips nothing.
  Checked, not extended — a wider set comparison for `assets/dictionaries/` would also pin
  NOTICE.txt churn and is a separate decision.

### Reproduce

```
# inputs (licensed, not committed):
#   ~/corpora-leipzig/{tat_mixed_2015_1M,tat_web_2018_1M}-sentences.txt
python3 scripts/sentstart_pack.py build \
  --sentences ~/corpora-leipzig/tat_mixed_2015_1M-sentences.txt \
              ~/corpora-leipzig/tat_web_2018_1M-sentences.txt \
  --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
  --output app/src/main/assets/dictionaries/tatar_sentstart_v1.txt
python3 tests/sentstart_pack/test_sentstart_pack.py
./gradlew :app:testDebugUnitTest --tests '*SentStart*' --tests '*TtSuggestEvalTest*'
```

## P5 — validation, smoke, mission docs (done 2026-09-20)

### Emulator smoke (extended)

`scripts/emulator-smoke.sh` gained a word-form block on the Tatar layout (after the
subtype cycle returns to tt, before the emoji panel): two tap-and-read probes. The IME
window is invisible to uiautomator, so a probe types a word + space, screenshots the
strip, taps the middle suggestion cell (x 0.5 of the equal-thirds strip,
`SuggestionStripState`; y 0.598 calibrated against the 40 dp strip = 110 px at density
440, measured span 1308–1418 px on 1080×2280) and reads the try-it field. Typing uses a
full letter→key map derived from `rows_tatar.xml` (`tt_word_coords`), consistent with
the pre-existing `TT_MIN`/`PROBE_KEY` calibration.

Two probes, chosen by measuring the shipped assets, not assumed:

* `wordform-tt-татар` — **PASS**: cell 2 committed **дәүләт**. The pinned schema-3 table
  carries THREE successors for татар (теле, дәүләт, телен — re-measured on the shipped
  assets this day), so no strip cell is free and the inflected forms never show for this
  word: bigram successors keep priority, forms fill only free cells
  (`CompositePrefixComputer`: `bigrams.size >= CELL_COUNT`). The probe pins exactly that
  contract on-device: дәүләт or a татар form passes, anything else fails. NOTE — this
  deviates from the plan's literal expectation (`татар татарлар`): the P3 JVM
  end-to-end test that showed `[белән, татарлар, татарча]` drives a one-successor
  fixture, not the shipped table. The forms themselves are proven by the second probe.
* `wordform-tt-сакчы` — **PASS**: cell 2 committed **сакчылар**. сакчы is a dictionary
  word (freq 397) the bigram table does NOT carry as a head, so all three cells are
  free and fill with its forms, frequency-ranked — the strip showed
  `сакчысы · сакчылар · сакчысын` (evidence `smoke-wordform-sakcy.png`, matching the
  offline ranking 457 > 286 > 31 exactly), and the tap committed through the
  predicted-word path. This is the on-device proof of the P3 after-word forms.

Full run (debug APK, tt_suggest_a14, booted and killed by the script):
**20 PASS / 0 FAIL / 1 SKIP** (the en skip is by design — no en dictionary), evidence in
`build/emulator-smoke/`. All pre-existing scenarios kept working unchanged.

One latent script defect fixed on the way: on a clean AVD (or with `-no-snapshot-save`
discarding every session) the prefs file does not exist when the smoke reads it, and
`run-as cat` failing under `set -euo pipefail` killed the script after `ime-id` with no
RESULT line — the documented missing-file fallback was unreachable. Fixed with `|| true`
on that read (comment in place); no check semantics changed.

### Gates (P5 final, all 2026-09-20)

| gate | result |
|---|---|
| python suites (`for f in tests/*/test_*.py`) | **455 tests, 0 failures** (15 files; 1 pre-existing skip in emoji_pack) |
| `./gradlew test --rerun-tasks` | **1 191 tests, 0 failures** (128 suites) |
| `./gradlew lintRelease` (full rerun) | green — baseline: 0 errors, 31 documented warnings |
| `rebuild_assets.py --check --allow-known-drift` | ok — tt 3/0 and ru 2/0 known drift as pinned, all four pin sets match |
| `release_pack.sh` | signed APK **1 849 555 B** ≤ 3 145 728 (headroom 41.2 %), SHA-256 `36d80c99de1138c475cdf1b394950ef765d47138351c556b187d4d03455ee619`, v2-only, cert `98ca6feb…42ad`; unsigned input 1 867 899 B |
| `check-no-internet.sh` (release APK) | both levels OK — source manifest + built APK; backup whitelist closed |
| `release_check.sh --quick` (release APK) | **OVERALL PASS** — 8 artifact checks PASS (size, asset pins 16/16, emoji assets, permissions [VIBRATE], signature, version 1.9.15/31 unchanged, changelog, delta vs 1.9.14 +53 336 B); the 4 gate checks are SKIP by `--quick` and ran separately above |
| emulator smoke | 20 PASS / 0 FAIL / 1 SKIP (above) |

### Consolidated before → after (P0 baseline → final)

Python harness (`scripts/suggest_eval.py`, pinned eval set):

| metric | P0 baseline | final | delta |
|---|---:|---:|---|
| `dict_word_coverage_tokens_pct` | 93.4917 | **94.3146** | +0.8229 pp (P2) |
| `dict_word_coverage_types_pct` | 87.2285 | **88.8764** | +1.6479 pp (P2) |
| `inflected_token_share_pct` | 45.1281 | 45.1281 | eval-text property |
| `bigram_head_coverage_pct` | 75.3623 | 75.3623 | identical head set |
| `nextword_top3_hit_pct` | 9.5468 | 9.5468 | head set identical |
| `nextword_top3_hit_covered_pct` | 12.6679 | 12.6679 | head set identical |

JVM harness (`TtSuggestEvalTest`, pinned counts, green in the 1 191):

| metric | P0 baseline | final | delta |
|---|---:|---:|---|
| prefix top-3 cp1 / cp2 / cp3 hits | 64 / 301 / 741 | 64 / 301 / 741 | unchanged (the ≥4-code-point gate exempts these prefixes) |
| same-stem top-3 at stem-length prefix | 988 of 1 716 (57.5758 %) | **1 078 (62.8205 %)** | +5.24 pp (P3) |
| next-word pairs / covered / hits | 4 347 / 3 276 / 415 | unchanged | — |
| `strip_empty_sentence_start_pct` | 100.0000 | **0.0000** | (P4) |
| `sentstart_top3_hit_pct` (new in P4) | — | **12.3000** (123 of 1 000) | cross-domain lower bound |

No pre-existing metric regressed; every movement is a phase's target metric.

### Asset and APK sizes

| artifact | before (1.9.15) | after | delta |
|---|---:|---:|---:|
| tt dictionary (compressed / raw, entries) | 501 683 / 1 162 870, 100 000 | 542 493 / 1 276 289, **110 000** | +40 810 B |
| tt bigrams (compressed / raw, pairs) | 81 028 / 134 664, 40 734 | 81 476 / 134 938, 40 735 | +448 B |
| `tatar_sentstart_v1.txt` (new, 64 records) | — | 1 838 | new |
| ru dictionary + ru bigrams | unchanged | byte-identical | 0 |
| unsigned release APK | 1 816 951 | 1 867 899 | +50 948 B |
| signed release APK (zopfli) | 1 796 219 | **1 849 555** | +53 336 B (+3.0 %) |

### Recalibrations in P5

None. Every pin recalibration happened in P2 (asset contracts, typo packs, the 403 → 411
autocorrect threshold, validator provenance) and P3/P4 (eval pins) and is listed in its
own section; P5 changed no asset, no pin, no test threshold.

### Mission DONE WHEN audit (the plan's list, evidence per item)

1. *Dictionary ships attested generated forms, pins recalculated, bigram table repacked* —
   **done**: P1 generator (58 python tests, kaikki recall 90.34 %, 123 191 admitted forms
   staged) + P2 rebuild (110 000 entries incl. 9 052 generated forms, pins rewritten by
   the tool, schema-3 table re-bound to the new dictionary hash, drift re-verified 3/0).
2. *After a committed Tatar word + space, inflections in free cells* — **done**: JVM
   end-to-end (`committedTatarWordPlusSpaceOffersItsInflectionsAfterTheBigramSuccessors`:
   белән, татарлар, татарча + tap commit) and on-device smoke `wordform-tt-сакчы`
   (сакчылар committed from cell 2); successor priority pinned on-device by
   `wordform-tt-татар` (дәүләт).
3. *Same-stem continuations outrank unrelated longer words at a complete-word prefix* —
   **done**: татар → татарлар, татарча, татарлары (was татарстан*); eval same-stem
   57.5758 → 62.8205 % with the cp1–cp3 proxies byte-identical (gated at ≥ 4 code
   points; both variants measured, P3 footnote).
4. *Sentence-start predictions where the strip was empty* — **done**: field start and
   after `. ` paint the 64-word table synchronously; `strip_empty_sentence_start_pct`
   100.0000 → 0.0000, `sentstart_top3_hit_pct` 12.3000; Russian slot byte-identical.
5. *Eval harness with before/after metrics on a pinned held-out set, no regression* —
   **done**: P0 harness (python + JVM, cross-agreeing to 4 decimals) + the consolidated
   table above; every pre-existing metric flat, target metrics improved.
6. *All gates pass* — **done**: the P5 gate table above (python 455/0, JVM 1 191/0,
   lint, asset pins, no-INTERNET both levels, release APK 1 849 555 B ≤ 3 145 728,
   `release_check.sh --quick` OVERALL PASS, emulator smoke 20/0/1).

### Left for the operator (not blocking)

Commit decision (the changeset is intentionally uncommitted), version bump and release
(`release_pack.sh` artifact ready), the external items of the plan (relicensing letters,
Common Voice corpus), on-device UAT of the new suggestions on the POCO C71, TalkBack.

> Footnote 2026-09-20: the POCO C71 on-device UAT is **done** — see the DEVICE-UAT
> section below; all 20 checklist rows PASS, cold start 261/270 ms median, no bugs found.
> Remaining from the list above: the commit/release decision, the external plan items,
> TalkBack.

## DEVICE-UAT 2026-09-20 — on-device validation of the TT-SUGGESTIONS build (POCO C71)

Full UAT cycle of the signed release APK `app-release-zopfli.apk` (SHA-256
`36d80c99de1138c475cdf1b394950ef765d47138351c556b187d4d03455ee619`, verified against
the P5 pin before install; reports versionName 1.9.15 / versionCode 31) on the physical
POCO C71 (Xiaomi 25028PC03G `serenity`, Android 15 Go SDK 35, 720×1640 @ 320 dpi,
three-button navigation — the same device as `docs/DEVICE-UAT-1.9.12.md`).

Install: clean `adb install -r` (the app was NOT installed on the device beforehand, so
no signature or personal-dictionary concern applied; `firstInstallTime == lastUpdateTime`).
Suggestions were opted in through the app's own UI (release package is not debuggable, so
the emulator smoke's `run-as` pref path does not apply): SetupActivity → Open settings →
Preferences → "Word suggestions" switch (`04-suggestions-toggled.xml`).

Method notes: uiautomator does not see the IME window, so the strip was proven two ways —
screenshot crops read by the operator (pixel evidence), and tap-the-cell-then-read-the-field
(the tap-and-read pattern of the P5 emulator probes). All key coordinates were recalibrated
for 720×1640 from screenshots of this build (the emulator-smoke 1080×2280 fractions do NOT
transfer): tt extra row y=1110, rows y=1206/1301/1396, bottom row y=1491; ru/en rows
y=1150/1264/1377; strip ≈ y 980–1060 with three equal-thirds cells at x=120/360/600. With
the keyboard up the try-it field leaves the visible hierarchy (adjustResize shrinks the
window to ~980 px) — the setup screen must be scrolled down first, then the field sits at
[48,699][672,812]. Evidence: `build/device-uat-2026-09-20/` (gitignored build dir).

### Checklist

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Install, enable, select IME; SetupActivity in focus | PASS | `install.log`, `01-setup-activity.png` |
| 2 | tt layout, fifth row ә ө ү җ ң һ; typing `сәлам` | PASS | `09-tt-salam-typed.png` |
| 3 | tt prefix suggestions with same-stem boost: `сәлам` → сәламе · сәламнәр · сәламнәре | PASS | `09-tt-salam-strip.png` |
| 4 | **(b)** same-stem boost: `татар` prefix → татарлар · татарча · татарлары, NO татарстан* | PASS | `10-tt-tatar-prefix-strip.png` |
| 5 | **(c)** `татар`+space → bigram successors теле · дәүләт · телен, forms absent (contract); cell-2 tap commits `дәүләт ` | PASS | `11-tt-tatar-space-strip.png`, `12-tt-tatar-cell2-committed.png` |
| 6 | **(a)** `сакчы`+space → сакчысы · сакчылар · сакчысын; cell-2 tap commits `сакчы сакчылар ` | PASS | `13-tt-sakcy-space-strip.png`, `14-tt-sakcy-cell2-committed.png` |
| 7 | **(d)** sentence start: empty field → бу · ул · ә; after `. ` → бу · ул · ә (twice, incl. after double-space period); cell-2 tap commits `ул ` | PASS | `05b-keyboard-now.png`, `42-sentstart-fieldstart-strip.png`, `15-tt-sentstart-after-period-strip.png`, `32-double-space-strip.png` |
| 8 | **(e)** ru `майор` → майора · майором · майору (frequency order, no Tatar-style boost) | PASS | `17-ru-mayor-strip.png` |
| 9 | ru baseline: `прив` → привет · привести · привело | PASS | `18-ru-priv-strip.png` |
| 10 | Language switching globe tt→ru→en→tt (identified per layout) | PASS | `16-after-globe1.png`, `19-globe-a.png`, `19-globe-b.png`, `30-on-tt.png` |
| 11 | en layout: `hi` typed, strip hidden (no en dictionary, by design) | PASS | `20-en-hi-strip.png` |
| 12 | Symbols `?123` (digit committed) and `#+=` layer; ABC back | PASS | `21-symbols.png`, `22-symbols2.png` |
| 13 | Emoji panel: long-press comma opens it (АБВ/backspace above the navbar — the 1.9.12 Д-1 fix holds on this Android 15 device), grid tap commits 😀, АБВ returns to letters | PASS | `24-emoji-panel.png`, `25-emoji-committed.png`, `26-after-abv.png` |
| 14 | Emoji-suggest tail cell: `сәлам`+space → биреп · белән · 👋 | PASS | `33-emoji-suggest-salam-strip.png` |
| 15 | Long-press alternate: а → popup Ә, commits ә (single-moreKey panel anchored at the finger — release on the anchor cell selects it; same mechanism the comma→emoji long-press relies on; matches the 1.9.12 UAT) | PASS | `35-longpress-hold-zoom.png` |
| 16 | Manual shift + auto double-space→period: `Аб`+␣␣ → `Аб. ` | PASS | `32-double-space.png` |
| 17 | Backspace repeat: 1.5 s hold deleted ~25 chars | PASS | field readback in session log |
| 18 | Rotation: landscape rebuild (navbar to the side, no overlap), back to portrait | PASS | `27-landscape.png`, `28-portrait-back.png` |
| 19 | Sustained Tatar paragraph typing, 193 chars in 86 s with live suggestions — every character landed in order, zero drops, no autocorrect corruption, no jank or crash | PASS | `31-paragraph.png`, `31-paragraph-field.txt` |
| 20 | Crash/stability: `logcat -b crash` EMPTY after the whole cycle; full logcat has no FATAL EXCEPTION / ANR for the package (only MIUI `WindowManager dispatchAppVisibility` W-warnings at my own force-stop instants) | PASS | `50-logcat-crash.txt` (0 lines), `51-logcat-full.txt` |

### Cold start (force-stop → `am start -W`, TotalTime, ms)

| Entry point | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| SetupActivity (launcher) | 281 | 261 | 239 | **261** |
| SettingsActivity (exported legacy) | 270 | 256 | 274 | **270** |

Budget < 400 ms holds with 30 %+ headroom (comparable to the 300.6 ms framestats-based
measurement at 1.9.13). NOTE: `SettingsHostActivity` (the modern settings UI reached from
SetupActivity) is `android:exported="false"` — `am start` on it fails with
SecurityException (`41-amstart-raw.txt`), so the two exported entry points were measured
instead. NOTE 2: this is a sideloaded APK — `dumpsys package dexopt` shows
`[status=verify] [reason=install]`, i.e. the baseline profile is NOT applied; a Play
install would not be slower.

### Bugs found

**None.** Every TT-SUGGESTIONS scenario (a–e) and every reused 1.9.12 baseline scenario
passed on the first run; no code changes were needed, so the repo gates were not re-run in
this session (nothing to validate — the working tree is untouched).

Two observations that are NOT defects, recorded for the operator:

* The first probe typed into the try-it field while it was scrolled out of the visible
  hierarchy — keystrokes land in the focused editor regardless of visibility; a
  coordinate race during keyboard re-raise produced one stray `ө` in a scratch field.
  Harness artefact, not an app defect.
* Stationary long-press on a key with a single more-key (а) commits that alternate (ә)
  without an explicit panel pick — the panel is anchored at the finger and the anchor
  cell is what the release hits (`MoreKeysKeyboardView.showMoreKeysPanel` origin math).
  This is the same long-standing behavior the comma→emoji long-press depends on and
  matches the 1.9.12 UAT's PASS for "а → ә".
