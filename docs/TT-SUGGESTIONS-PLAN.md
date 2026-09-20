# TT-SUGGESTIONS — plan: Tatar word prediction, suggestions, and word forms

Status: approved work plan. Execution order is P0 → P5; each phase has its own
"Done when" and gates. Numbers measured during execution go to the mission
report `docs/TT-SUGGESTIONS.md` (created in P5); this plan is not rewritten,
only annotated with dated footnotes.

## DONE WHEN (mission level)

1. The Tatar dictionary ships attested inflected word forms produced by the
   project's own build-time paradigm generator (P1), with pins recalculated and
   the bigram table repacked (P2).
2. After a committed Tatar word + space, the strip offers inflections of that
   word (e.g. `татар` → `татарлар`, `татарча`) in free cells after bigram
   successors (P3).
3. When the typed Tatar prefix is itself a complete dictionary word, same-stem
   continuations outrank unrelated longer words in the strip (P3).
4. Sentence-start predictions are shown where the strip used to be empty
   (field start, after sentence-ending punctuation) (P4).
5. An evaluation harness reports before/after metrics on a pinned held-out
   Tatar eval set, and no metric regresses (P0, P5).
6. All gates pass: `./gradlew test`, all python suites, `lintRelease`,
   `check-no-internet.sh` (source + APK), release APK ≤ 3 145 728 bytes,
   `rebuild_assets.py --check --allow-known-drift`, `release_check.sh --quick` (P5).

## Research summary (evidence base)

### Current state (code audit, 2026-09-19)

- The Tatar dictionary is a flat list of 100 000 word forms with absolute
  frequencies (TATDICT schema 2). Inflected forms exist as independent entries
  (`татарлар` 12 085, `татарча` 9 093), but there is no lemma grouping, no
  morphology anywhere.
- Prefix suggestions: exact top-3 by frequency; fuzzy class #1 (long-press
  substitutions) fills empty cells only. Result: prefix `татар` yields
  `татарстан/татарстанда/татарстанның` (toponyms), not grammatical forms.
- Next-word: static bigram table (10 204 heads, K=4, show 3), one-word
  context, only after word + U+0020 spaces. Nothing after punctuation or at
  field start.
- Autocorrect: single class-#1 candidate with freq ≥ 403, word ≥ 4 code
  points, opt-in toggle. Personal dictionary: words only, max one cell.
- Hard contracts: 3 strip cells, zero allocations on lookup, p95 lookup ≤ 5 ms,
  cold start < 400 ms, fail-closed everywhere, APK ≤ 3 145 728 bytes.

### Tatar morphology (Wikipedia, apertium-tat lexc, Burbiel 2018)

- Agglutinative; ~45 % of corpus tokens carry an inflectional suffix.
- Vowel harmony: back {а ы о у} / front {ә е и ө ү}, decided by the last stem
  syllable. Archiphonemes: A=а/ә, I=ы/е, G=г/к (к after voiceless), D=д/т
  (т after voiceless), L=л/н (н after nasals м/н/ҥ).
- Noun paradigm: plural -LAr; cases: gen -nIn, dat -GA, acc -nI, loc -DA,
  abl -DAn; possessives 1sg -Im, 2sg -Iŋ, 3sg -I/-sI, 1pl -IbIz, 2pl -IgIz,
  3pl -LArI; after 3sg possessive the case forms are special (acc -n,
  dat -nA, loc -nDA, abl -nnAn).
- Verb paradigm (priority forms): present -A, neg -mAy, past -DI, past -GAn,
  future -Ir, future -AčAk, conditional -sA, imperative (bare stem, 2pl
  -(I)GIz), gerunds -Ip, -GAč, -GAnčI, neg -mAyčA, participles -GAn, -UčI,
  -AsI, verbal noun -U, intention -mAkčI, negation -mA- infix.
- Derivational (frequent, productive): -čA (language/manner: татарча), -lIk,
  -lI, -sIz, -čI, -dAş, -rAk.
- Exceptions need manual tables: stem-final п→б / к→г voicing before vocalic
  possessives (китап→китабы but китапка), irregular verbs (дию→диген,
  уку→укый), pronouns (мин/син/ул/бу).
- Corpus priorities (measured on ~896k tt tokens): dative ~6.7 % > locative
  ~4.7 % > accusative ~4.0 % > genitive ~2.0 % > ablative ~1.8 %; verbs:
  -DI ~2.4 % > -GAn ~1.7 % > -Ip ~1.4 %; derivational -lIk ~1.0 %,
  -čA ~0.7 %.

### Resources and licenses

- Leipzig downloadable corpora: CC BY 4.0 (attribution already in NOTICE.txt);
  `tat_mixed_2015_1M` and `tat_web_2018_1M` download fine from
  downloads.wortschatz-leipzig.de (~190 MB each). Web scraping is CC BY-NC —
  forbidden.
- apertium-tat: GPL-3.0 — build-time filter/validator only, never a data
  source for shipped assets. giellalt/lang-tat: LGPL — same; their README
  offers alternative licensing (operator-level action, see External items).
- kaikki.org Tatar: 1 515 full paradigms, CC BY-SA 4.0 — dev-time validation
  of the generator, nothing shipped or committed.
- Mozilla Common Voice tt: CC0 — optional future corpus addition.
- No open-source IME ships morphological word-form suggestions; Turkish and
  Finnish in AOSP are solved with extended word-form lists. Precomputed forms
  in the dictionary is the industry state of practice.

### Key decision

Generate word forms at build time with the project's own python paradigm
generator; admit a generated form only if attested in the corpus (frequency =
corpus count); ship in the existing TATDICT schema 2. Runtime suggestions of
word forms use a small Kotlin suffix table + dictionary lookups — no new
binary format, no runtime morphology engine.

## P0 — Evaluation harness and baseline

Files: `scripts/suggest_eval.py` (new), `tests/suggest_eval/` (new python
suite), eval corpus builder `scripts/make_eval_set.py` (new), committed eval
set `app/src/test/resources/tt_eval_sentences.txt` (new, ≤ 1 000 lines),
JVM `TtSuggestEvalTest` (new).

Tasks:
1. `make_eval_set.py`: build a Tatar eval set from research/corpus Tatoeba tt
   (CC BY 2.0 FR), excluding every line present in `tt_conv_train90`
   (deterministic, documented); pin line count and SHA-256 in the test suite.
2. `suggest_eval.py`: corpus-stat metrics — word coverage, next-word hit rate
   of the bigram table, share of inflected forms.
3. JVM `TtSuggestEvalTest`: drives the real engine over the eval set —
   prefix top-3 completion rate at keystroke fractions, next-word top-3 hit
   rate, strip-empty rate at sentence start. Prints `EVAL|metric|value` lines;
   asserts calibrated thresholds. Runs in the normal `./gradlew test`.
4. Record all baseline numbers in `docs/TT-SUGGESTIONS.md` (skeleton created
   here, filled through the mission).

Done when: baseline numbers are measured and committed; eval suites are green.

## P1 — Build-time word-form generator

Files: `scripts/wordform_gen.py` (new), `scripts/wordform_exceptions_tat.tsv`
(new, manual), `tests/wordform_gen/` (new suite),
`scripts/wordform_kaikki_check.py` (new, optional, network, dev-time only).

Tasks:
1. Harmony engine: back/front classification by last stem syllable; G/D/L
  assimilation; suffix inventory = P0 inflectional (plural, 6 cases,
   possessives, post-3sg cases; verb forms listed above) + derivational
   -čA/-lIk/-lI/-sIz/-čI/-rAk/-dAş.
2. Paradigm generation for noun-like and verb-like stems; exception tables for
   п→б/к→г voicing, irregular verbs, pronouns.
3. Stem extraction from the dictionary itself: a bare dictionary word is a
   stem candidate; suffix-stripping analysis groups forms under stems;
   ambiguous stems stay untouched (fail-closed).
4. Admission: a generated form enters the candidate list only if attested in
   the Leipzig tt corpora or the conversational input; frequency = corpus
   count. Russian loanwords follow the same harmony rules (совет+ларга).
5. Hand-written golden paradigm tests (own data, no license issues);
   `wordform_kaikki_check.py` validates against kaikki.org paradigms on
   demand (network, never in gates).

Done when: generator covered by python tests; kaikki spot-check agreement
recorded; candidate form list builds deterministically.

> Footnote 2026-09-20 (P1 executed): two morphology details above were amended
> against kaikki.org conjugation tables — the simple future is not a single
> -Ir (monosyllabic consonant stems take -ар/-әр: язар, китәр; -ыр/-ер is a
> small lexical class plus polysyllabic stems), and «диген» was a typo for
> дигән (дию needs no exception row). Also: the ablative is -нAn after nasals
> (урманнан), and су→суы turned out to be a regular rule (у/ү-final
> possessives), not an exception. Details and numbers: docs/TT-SUGGESTIONS.md,
> P1 section.

## P2 — Dictionary expansion and asset rebuild

Files: `scripts/rebuild_assets.py` (extend), `scripts/dictionary_pack.py`
(budget check only), pins in `DictionaryStorageContracts.kt` /
`BigramStorageContracts.kt` (rewritten by the tool), `scripts/typo_pack.py`
output (regenerated), `tests/rebuild_assets/` (extend).

Tasks:
1. Download Leipzig `tat_mixed_2015_1M` + `tat_web_2018_1M` to
   `~/corpora-leipzig/` (CC BY); rebuild `tt_conv_train90` per the documented
   recipe if absent.
2. Add `--only tatar` mode to `rebuild_assets.py` (tt dictionary + tt bigrams
   only; ru assets and pins untouched; `--check` still verifies all four
   assets). Python tests for the new mode.
3. Wire `wordform_gen.py` into the rebuild as a stage before
   `dictionary_pack.py build`: merged list = current entries + admitted
   generated forms; total capped to fit the schema-2 budget
   (≤ 600 000 bytes compressed; raise only by measured need, APK headroom
   is ~1.3 MB).
4. Rebuild tt dictionary + tt bigram table, recalculate pins, regenerate typo
   packs, re-verify `known_asset_drift.json` numbers (update if drift moved).
5. Recalibrate affected pinned numbers in JVM tests (E3 recovery calibration,
   prefix audit fixtures) and record old → new values in the mission report.

Done when: `rebuild_assets.py --check --allow-known-drift` passes; JVM asset
contract tests pass with new pins; dictionary size stays within budget; word
coverage on the eval set improves over the P0 baseline.

> Footnote 2026-09-20 (P2 executed): the compressed-size selection cap was
> tightened to 580 000 bytes (the brief's number; the format budget stays
> 600 000), N chosen by measurement = 110 000 (9 052 forms + 948 conversational
> words enter, zero displaced). Russian assets stayed byte-identical under the
> new `--only tatar` mode. One surprise, measured: at N = 100 000 NOT A SINGLE
> admitted form enters — every new form's two-corpus count sits below the old
> cutoff, so raising N was load-bearing, not optional. Numbers and every
> recalibration: docs/TT-SUGGESTIONS.md, P2 section.

## P3 — Runtime word-form suggestions

Files: `latin/suggestions/TatarSuffixRules.kt` (new),
`latin/dictionary/engine/TdictPrefixIndex.kt` (add exact frequency lookup),
`latin/suggestions/SuggestionsController.kt` (wire-in),
`latin/suggestions/CompositePrefixComputer.kt` (merge rules), JVM tests
(new + extended `SuggestionsControllerTest`, `CompositePrefixComputerTest`).

Tasks:
1. `TatarSuffixRules.kt`: zero-allocation suffix matcher/generator mirroring
   the P1 inventory (inflectional + -čA derivational set); harmony by last
   stem vowel; static tables, no regex.
2. `TdictPrefixIndex.frequencyOf(word)`: exact dictionary lookup reusing the
   existing block search; covered by unit tests incl. allocation-free check.
3. After-word forms (next-word slot): on empty prefix after word + space,
   generate inflection candidates of the previous word, keep those present in
   the dictionary, rank by frequency, fill strip cells left free by bigram
   successors. Bigram successors keep priority; forms never displace them.
4. Same-stem boost (prefix slot): when the typed prefix is itself a complete
   dictionary word, candidates equal to prefix + known suffix rank before
   unrelated continuations; frequency order preserved inside each group;
   typed word itself stays excluded. This consciously amends the frozen
   ranking contract — new tests pin the amended behavior.
5. Personal-dictionary and emoji-cell merge rules unchanged; forms yield to
   personal words exactly like dictionary words do.
6. Settings: new behavior rides the existing Tatar suggestions toggle; no new
   preference unless the eval shows a need.

Done when: `татар` + space offers `татарлар`-type forms in the strip; prefix
`татар` ranks `татарлар/татарча`-type continuations above `татарстан*`;
eval metrics in `TtSuggestEvalTest` improve and are re-pinned; zero-allocation
and p95 lookup budgets stay green.

> Footnote 2026-09-20 (P3 executed): the wire-in landed one layer lower than the
> plan's file list guessed — `SuggestionsController` needed no change at all: the
> merge that owns ranking and holds dictionary access is `CompositePrefixComputer`,
> and the controller treats the merged NEXT_WORD list as an ordinary one (emoji
> tail, companion fill, tap paths untouched). The boost is gated at ≥ 4 code-point
> prefixes: the first cut engaged on any complete word and cost the cp1–cp3
> completion proxies (64/301/741 → 29/235/685) for a 57.58 % → 69.99 % same-stem
> gain; the gate recovers the proxies in full and keeps same-stem at 62.82 %
> (988 → 1 078 of 1 716). Both variants are pinned in docs/TT-SUGGESTIONS.md, P3
> section. Budgets held: boost-engaged p95 0.010 ms, zero added allocation.

## P4 — Sentence-start predictions

Files: `scripts/sentstart_pack.py` (new), `tests/sentstart_pack/` (new),
`app/src/main/assets/dictionaries/tatar_sentstart_v1.txt` (new, plain text,
pinned like emoji assets), `SuggestionsController.kt` +
`TatarWordUtils.extractNextWordContext` (extend), JVM tests.

Tasks:
1. Build top sentence-initial Tatar words (rank ~64) from Leipzig tt first
   tokens; plain-text asset `word<TAB>freq`, pinned in python tests and a JVM
   asset test (same discipline as emoji assets).
2. Runtime: when the context has no previous word (field start, after
   sentence-ending punctuation + space), show sentence-start suggestions.
   Amends the "no prediction after punctuation" contract deliberately, with
   tests.
3. Russian slot unchanged (no asset, no behavior change).

Done when: strip offers suggestions at field start and after `. ` in Tatar;
strip-empty-at-sentence-start metric in the eval test drops to ~0; all pins
green.

> Footnote 2026-09-20 (P4 executed): the wire-in landed in the controller, not
> the engine — the sentence-start table is a static per-language asset like the
> emoji-suggest table, so it rides the same lazy-load seam
> (`AssetSentStartPreparation`, mirroring `AssetEmojiSuggestPreparation`) and
> the band is painted synchronously in the NEXT_WORD request path with NO engine
> request issued; that is what makes "a sentence boundary resets context" true
> by construction (no bigram successors, no P3 forms, no companion query). The
> detection is a new pure function `TatarWordUtils.isSentenceStartContext`
> (exactly `.`/`!`/`?`/`…` + U+0020 spaces, or a proven field start), the frozen
> `extractNextWordContext` is untouched, and the tap commits through the
> existing E5d predicted-word path, whose production implementation learned one
> case: an empty bound context is valid only while the live position is still a
> sentence start. Numbers and the contract amendment: docs/TT-SUGGESTIONS.md,
> P4 section.

## P5 — Validation, smoke, mission docs

Files: `scripts/emulator-smoke.sh` (extend), `docs/TT-SUGGESTIONS.md`
(mission report), `HANDOFF.md`, `CHANGELOG.md`, `docs/README.md`.

Tasks:
1. Extend emulator smoke: type `татар` + space, assert word-form suggestions
   on the strip (evidence in outdir).
2. Full gates: `./gradlew test --rerun-tasks`, all python suites,
   `lintRelease`, `check-no-internet.sh` on source and release APK, release
   APK size, `release_check.sh --quick`.
3. Before/after eval table in `docs/TT-SUGGESTIONS.md`; asset size deltas;
   any recalibrated numbers with reasons.
4. `HANDOFF.md` new entry on top; `CHANGELOG.md` entry; `docs/README.md`
   index line. Version bump + release are the operator's call.

Done when: every gate passes, the mission report carries measured numbers,
and the docs stack is consistent.

## External items (operator, not blocking)

- Relicensing letters to giellalt/lang-tat and/or apertium-tat authors for a
  build-time data license (13–20k stems with paradigm classes).
- Mozilla Common Voice tt (CC0) as an additional conversational corpus.
- On-device UAT of the new suggestions (POCO C71), TalkBack, Telegram.

## Risks

- Overgeneration: mitigated by the corpus-attestation filter in P1 and by
  budget caps in P2.
- Ranking regressions from the same-stem boost: gated by the P0/P3 eval
  metrics; rollback = one merge-rule revert in `CompositePrefixComputer`.
- Asset size growth: dictionary budget enforced fail-closed; APK limit checked
  in CI and `release_check.sh`.
- License contamination: no GPL/LGPL data in shipped assets; kaikki used only
  as a dev-time validator; Tatoeba eval set is CC BY with attribution.
