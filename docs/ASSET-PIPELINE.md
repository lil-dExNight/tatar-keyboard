# Asset pipeline

The bundled data is built by the python scripts in `scripts/`. Never edit asset files by hand.
The scripts use only the standard library, give the same bytes for the same inputs, and exit
nonzero without writing output when an input or a result is invalid. The binary formats are
described in [ASSET-FORMATS.md](ASSET-FORMATS.md).

## Clean checkouts and rebuild preflights

Every tracked asset and test resource is required in every environment; `python3
scripts/provenance.py` validates those first. The raw corpus dumps under `research/corpus/` are
the only optional inputs: a clean checkout with no `.git` directory and no local corpora passes
source validation, and each declared dump is size- and SHA-256-verified whenever it is present,
reported by name when it is not.

The required raw set does not come from the shards. The inventory is read from the acquisition-pin
registry (`research/corpus-sources/build_provenance.py`, `CORPUS_DUMPS`, committed as
`data/provenance.d/corpus-dump-*.json`), and validation requires the declared raw shard set to
equal that registry in both modes, so a deleted or extra shard fails instead of silently dropping
a requirement.

Every command that rebuilds assets runs the strict
preflight (`scripts/provenance.py validate_raw_corpora`): the declaration match plus presence and
hash match for every declared dump, before any output is written. `scripts/rebuild_assets.py`
calls it in the rebuild path and exits 2 on failure; `--check` never demands raw corpora, so the
offline consistency check stays runnable in a clean checkout. `provenance.py --require-raw-corpora`
exposes the same strictness on the command line. The final-set builders verify their exclusion
inputs against provenance or inventory pins through the shared inventory (below) before any
candidate is selected.

| Asset (`app/src/main/assets/`) | Built by | Inputs |
|---|---|---|
| `dictionaries/*.tdict.zlib` | `scripts/rebuild_assets.py` (runs `dict_accept.py pack`) | release 1.8.4 baseline dictionaries, `data/dictionary/dict-accept/`, Tatar word forms |
| `dictionaries/*_companion_v1.tcomp.zlib` | `scripts/rebuild_assets.py` (runs `companion_list_pack.py build`) | the committed dictionary of the same language |
| `dictionaries/*_sentstart_v1.txt` | `scripts/sentstart_pack.py` | Leipzig sentence corpora, the conversational training files, the bundled dictionary |
| `dictionaries/tatar_proper_names_v1.txt` | `scripts/rebuild_assets.py` (runs `proper_name_pack.py`) | `data/dictionary/tatar-proper-name-review.tsv`, `data/dictionary/tatar-compound-review.tsv` |
| `emoji/emoji_set_v1.txt`, `emoji_skin_v1.txt` | `scripts/emoji_pack.py`, `scripts/emoji_skin_pack.py` | Unicode Emoji 15.1 `emoji-test.txt` |
| `emoji/emoji_search_v1.txt` | `scripts/emoji_search_pack.py` | CLDR 44 annotations (ru, en), `scripts/emoji_search_tt_extra.txt`, `scripts/emoji_search_tt_names.txt` |
| `emoji/emoji_suggest_v1.txt` | `scripts/emoji_suggest_pack.py` | `scripts/emoji_suggest_data.tsv` |

## Inputs

These inputs are not in the repository:

- Baseline dictionaries. The two release 1.8.4 dictionary assets are the only valid starting
  point for a rebuild. `dict_accept.py` checks them against `BASELINE_SHA256` and stops if they
  do not match, because rebuilding on top of an already rebuilt asset would add the
  conversational frequencies a second time. Extract them into one directory, keeping their file
  names:

  ```
  mkdir -p /tmp/baseline-1.8.4
  for f in tatar_top100k_v1 russian_top100k_v1; do
    git show 6306014a:app/src/main/assets/dictionaries/$f.tdict.zlib > /tmp/baseline-1.8.4/$f.tdict.zlib
  done
  ```

  (`6306014a` is the commit that set version 1.8.4.)
- Leipzig corpora (CC BY 4.0), from https://wortschatz.uni-leipzig.de/en/download. The
  rebuild reads the `*-sentences.txt` files named in `BIGRAMS[].train` and the Tatar
  `*-words.txt` files named in `WORDFORM_FREQUENCY_SOURCES` (both are in `rebuild_assets.py`).
  They are expected in `--corpus-dir`, which defaults to `~/corpora-leipzig` for a rebuild.
  The rebuild does not read `tat_news_2015_1M`, but it is not held out: the release 1.8.4
  baseline dictionaries carry its word frequencies (see `NOTICE.txt`), and
  `research/corpus/tt_merge_sim.py` uses its sentences as a development set when it selects
  the bonus weights. The independent trigram final-set builder also expects that corpus's
  sentences file in `--corpus-dir`, solely to remove its lines from the evaluation candidates.
- Held-out evaluation sets. `app/src/test/resources/tt_eval_sentences.txt` (built by
  `scripts/make_eval_set.py`) is the held-out Tatar set: Tatoeba sentences from the
  conversational rows with `id % 10 == 1`, minus every sentence of the Leipzig training corpora.
  Its sentences are in no bigram, sentence-start or tuning input; `tt_merge_sim.py` reads it
  only to remove its lines from the development set. Its word counts are held out the same
  way: `research/corpus/stream.py` holds out the `id % 10 == 1` slice for Tatar
  (`HELD_RESIDUE`), so the conversational frequency table `conv-freq-tt.tsv` is counted over
  the eval-line complement. `ru_eval_sentences.txt` (`scripts/make_ru_eval_set.py`) is the
  Russian counterpart: normalized-content exclusion removes every eval sentence found in any
  current Russian training or tuning input, including the thinned conversational stream and
  every corpus in `BIGRAMS[].train` (`rus_news_2023_1M` and `rus_news_2024_1M` included).
  `conv-freq-ru.tsv` excludes counts contributed by eval lines; the same words can still have
  counts from other training lines. The suggestion evaluations, the typo eval sets and the glide
  calibration set are built from these two files. The Tatar side also has a frozen final
  evaluation set, `app/src/test/resources/tt_common_voice_final.txt` (CC0): Tatar Common Voice
  dev-split sentences extracted by `research/corpus/acquire_common_voice_final.py
  --mirror-text` from the hash-verified mirror text, checked for independence against the
  conversational train split, the Leipzig training sentences and the development sets, and
  frozen before any tuning. A separate frozen final set,
  `app/src/test/resources/tt_trigram_common_voice_final.txt`, is extracted from the Common Voice
  test split by `research/corpus/acquire_trigram_common_voice_final.py`. Its builder excludes the
  Leipzig training corpora, the historical `tat_news_2015_1M` tuning input, the full
  conversational stream, both existing development and final sets, and the other Common Voice
  splits; the news exclusion input is pinned in the output provenance. The held-out sets keep
  their roles: the Tatoeba-based one is the development and regression input, and the Common
  Voice sets are final evaluations. None may be trained or tuned on. Split assignments and
  normalized collisions across sentences, word-frequency tables, dictionary admissions and bigram
  training are declared in `data/evaluation/splits-tt.json` and `data/evaluation/splits-ru.json`
  and checked by `python3 scripts/evaluation_splits.py <declaration>` (a cross-split
  normalized-sentence leak is an error; a word table may not hold two rows that normalize to the
  same word).
- Conversational training files `tt_conv_train90-sentences.txt` and
  `rus_conv_thinned60-sentences.txt`. `research/corpus/dl.sh` downloads the Tatoeba and
  OpenSubtitles dumps (Tatar Tatoeba: the weekly tatoeba.org export, which OPUS lags; the
  rest: OPUS), and `research/corpus/make_conv_train.py` turns them into deduplicated
  Leipzig-format files. The line-id filter is applied by hand (Tatar: `id % 10 != 1`; Russian:
  `id % 60 == 0`) and keeps the original ids, for example `awk -F'\t' '$1 % 60 == 0'`. Put the
  results in `--corpus-dir`. The Tatar rows with `id % 10 == 1` are not in
  `tt_conv_train90-sentences.txt`, but `tt_merge_sim.py` tuned the bonus weights on them (minus
  the eval set lines), so of these rows only the eval set above is held out of every input.
- Emoji sources: `emoji-test.txt` (Unicode Emoji 15.1) and the CLDR 44 ru/en annotation
  files, saved in one `--cldr-dir` as `ru.xml`, `en.xml`, `derived-ru.xml` and
  `derived-en.xml`. The packers pin their SHA-256.

These inputs are in the repository:

- `data/corpus-manifest.json`: the size and SHA-256 of every corpus file the rebuild reads
  (the Leipzig and conversational files above), with a short `source` note. Its entry set must equal the files named in `BIGRAMS[].train` and
  `WORDFORM_FREQUENCY_SOURCES`. When you replace a corpus, update its entry in the
  same change as the rebuilt assets.
- `data/dictionary/`: the word review queues (`*-conv-review.tsv`, `*-query-review.tsv`), the
  evidence and review tables (`tatar-wordform-review.tsv`: per-paradigm-cell corpus attestation
  and admission decisions for the audited Tatar paradigms; `tatar-language-eval-review.tsv`:
  native-review evaluation cases for conversational text, autocorrect safety, proper names,
  casing, hyphens and apostrophes), and
  `dict-accept/`, which holds the accepted and rejected words (`accepted-*.tsv`,
  `rejected-*.tsv`, written by `dict_accept.py select`), the conversational frequencies
  (`conv-freq-*.tsv`) and the extra-corpus bonus frequencies (`bonus-freq-tt.tsv`, written by
  `research/corpus/tt_merge_sim.py bonus-tsv`; `dict_accept.py pack` adds them to the merged
  frequency of composition words without changing the composition).
- `data/provenance.d/`: one JSON shard per generated asset and evaluation file. Each shard pins
  an immutable revision, byte size, SHA-256, license, source role, split rule, generation
  command and repository-relative path; `python3 scripts/provenance.py` fails on a missing,
  duplicate, malformed or stale shard, and the python suite runs it against the live tree. The
  raw-dump shards (`corpus-dump-*.json`) also map each dump to its acquisition pin (the exact
  bytes `dl.sh` fetches) and to the converted-corpus keys of `data/corpus-manifest.json`; all
  present dumps are validated before a shard is written or removed.
- `data/evaluation/splits-tt.json`: the train/development/final split declarations checked by
  `scripts/evaluation_splits.py` (see the held-out evaluation sets above). The python suite runs
  the committed declaration against the live tree, so the shipped development and final sets and
  the word tables are covered, not only a fixture. The Russian counterpart
  `data/evaluation/splits-ru.json` declares the Russian sentence, valid-OOV, typo-target,
  admission and frequency sources plus all six Russian bigram-training inputs; the Tatar
  declaration names its three bigram-training inputs. `optional: true` is accepted only for
  external `${CORPUS_DIR}/` sources, so a tracked artifact cannot be marked optional, and
  required-coverage configuration and leak classification are separate: leak checks always use
  the constant development/final semantics.
- `data/evaluation/final-exclusions-tt.json` with `scripts/final_evaluation_exclusions.py`: the
  shared exclusion inventory both final-set builders apply. It covers the historical
  `tat_news_2015_1M` tuning input, the other final set, every Common Voice split, every TatSC
  split, QED, TED2020 and the extra Tatar Tatoeba snapshot. Every ordinary exclusion input is
  verified against a corpus-provenance or inventory pin (size and SHA-256) before it is parsed,
  and the full conversational stream is derived from the pinned recipe rows of
  `make_eval_set.py` rather than read as an unpinned file; a missing, drifted or unpinned input
  fails the builder before any candidate is selected.
- `data/corpus-sources/`: the licensed-corpus comparison for Tatar (TatSC under CC BY 4.0 and
  Common Voice under CC0, alongside the current OpenSubtitles/Tatoeba input):
  `ATTRIBUTION.txt` with the license and upstream of each candidate, `source-pins.json` with
  the fetched revisions, and the comparison and recommendation reports
  (`comparison.json`, `integration-recommendation.json`) written by
  `research/corpus-sources/compare_sources.py` after `research/corpus-sources/fetch_sources.py`.
  The fetch step verifies the pinned immutable dataset revision against upstream, every TatSC
  rows request carries that revision, and the page cache under `build/corpus-sources/pages/` is
  bound to it and to the SHA-256 of the exact response bytes and of the normalized page content:
  an edited or unverifiable cached page is refetched instead of trusted, and only byte-verified
  pages feed the comparison.
  The current recommendation is to defer integration (keep the shipped pipeline): no candidate
  combination was a drop-in improvement on every fixed domain. Rerun the comparison for new
  numbers; the recommendation record is the authority, not figures copied into documents.
- `scripts/wordform_exceptions_tat.tsv`: exception rules for the Tatar word-form generator
  (`scripts/wordform_gen.py`): lexical harmony, reviewed Russian loans, syncopated possessive
  bases, suppletive pronouns and per-label overrides. Final-consonant voicing, soft-sign spelling
  and the glide/converb allomorphs are generator rules, restricted by the lexical rows where
  listed. Its SHA-256 is pinned in
  `tests/wordform_gen/`.
- `scripts/bigram_extra_heads_tat.txt`: extra Tatar bigram heads (imperatives ranked below the
  head cutoff). The rule that produced the list is in the file header; do not add words by hand.
- `scripts/emoji_suggest_data.tsv`, `scripts/emoji_search_tt_extra.txt` and
  `scripts/emoji_search_tt_names.txt`: curated emoji data. The Tatar names file carries the
  reviewed spoken Tatar name of an emoji; a name is added only through native review, and the
  emoji whose primary alias would be ambiguous stays unnamed instead of getting an invented
  label (the python emoji tests pin the unnamed set).

## `accept_wordset.py`

A candidate word set (a new review pool in `data/dictionary/*-conv-review.tsv`) is accepted on
evidence, not on the rule alone. `scripts/accept_wordset.py` runs the evidence steps in order,
each as a subprocess of the existing script; the runner measures nothing itself:

1. `dict_accept.py select --baseline <dir>`: the admission rule over the pool, rewriting
   `data/dictionary/dict-accept/accepted-*.tsv` and `rejected-*.tsv`. A word is admitted
   automatically only on independent evidence (seen in both OpenSubtitles and Tatoeba, already
   shipped, or — Russian only — a Tatoeba-attested form of a shipped stem) with capitalization
   evidence below the conservative ceiling; subtitle-only candidates never pass automatically.
   Every other admission is an explicit operator-widened review: the queue row needs
   `approved=yes`, `reviewer`, `review_date` (ISO date) and `note`; rows with `approved=no` are
   rejected as reviewed, and rows without the full metadata are rejected as review-required.
2. `research/corpus/measure_accept.py <tag> <baseline> <corpora>`: the held-out coverage delta
   of the accepted set against the shipped baseline; also rewrites `conv-freq-*.tsv`.
3. `research/corpus/tt_gate_check.py` or `ru_gate_check.py`: the candidate dictionary and
   bigram table (both built outside the asset tree) against the committed assets on the pinned
   eval set — the coverage gate and the paired bootstrap CI95 of the chain's visible top-3
   delta (the strip shows three cells; the engine's fourth candidate is diagnostic only and
   never reported as top-3). A rejected gate stops the run before the query audit: the
   candidate is not accepted.
4. `dictionary_pack.py query-audit --asset <candidate> --queries <manual query list>`: the
   candidate's rows, saved for the review into `data/dictionary/*-query-review.tsv`.

```
python3 scripts/accept_wordset.py --source <name> --language tat|rus --baseline <dir> \
    --corpus <file>... --candidate-dict <file> --candidate-bigram <file> [--work-dir <dir>]
```

Each step's report is saved under the work dir (default
`research/corpus/out/accept_wordset/<source>/`). Exit codes: 0 the gate passed, 1 a step
failed, 2 bad arguments or a missing input, 3 the gate rejected the candidate. When every step
passes, the runner prints the re-pin checklist with the file names; the list under "A rebuild
leaves the following to you" below stays the authority for what it prints.

## Measurement contracts

The common metric and split rules are:

- Visible top-3 is the user-facing metric: the strip shows three cells by default. The engine
  composes four candidates, and the fourth is diagnostic only — it must never be reported as a
  visible top-3 hit. `scripts/evaluation_metrics.py` owns the accounting: `paired-ci
  INPUT.json` compares per-record hit and denominator arrays of two arms, `check-report
  REPORT.json` validates a gate report's top-k roles, denominators and pairing metadata, and
  recomputes every `pct` and the delta from hits and denominators, so a stale or hand-edited
  rate fails; unequal series lengths and hits above a denominator are rejected.
- Gate reports validated by `scripts/evaluation_metrics.py` use paired bootstrap CI95: two arms
  are compared on the same records, the bootstrap draws the same record indices for both arms,
  and every reported rate carries its denominator. A quality decrease is never accepted by moving
  pins.
- `scripts/glide-benchmark.py compare` requires identical ordered record fingerprints and reports
  paired 2×2 counts plus deterministic metric deltas, without a confidence interval.
- Evaluations run on the split that matches their role (development or final; see the split
  declarations above), never on training inputs and never tuned on the final set.

## `rebuild_assets.py`

This is the single entry point for dictionaries and bigram or combined bigram/trigram tables.
A table is a set of indices into its dictionary, so the two are always rebuilt together.

### Full rebuild

```
python3 scripts/rebuild_assets.py --baseline /tmp/baseline-1.8.4 \
    [--corpus-dir DIR] [--work-dir DIR] [--allow-known-drift]
```

The script first lists every missing input and stops if there are any. It then compares the
corpus files of the selected languages with `data/corpus-manifest.json` (size first, then
SHA-256) and stops with exit code 1, before writing anything, if one differs. Then it runs:

1. **Tatar word forms.** `wordform_gen.py` generates forms for every stem in the baseline plus
   the accepted words. A form is admitted when it occurs in the frequency sources (the two
   Leipzig `*-words.txt` files plus `conv-freq-tt.tsv`), and its count becomes its frequency.
   The result is written to `<work-dir>/wordforms-admitted-tt.tsv`, with a JSON report next to
   it. The rebuild then reads the complete `operator-widened` approvals from
   `tatar-wordform-review.tsv`, merges their evidence-backed frequencies, and writes their words
   to `<work-dir>/wordforms-required-tt.txt`. An `operator-widened` row with `approved=no`
   removes its word from the generated forms and is written to
   `<work-dir>/wordforms-rejected-tt.txt`; the packer's `--exclude-entries` option keeps that
   rejection out of the baseline, accepted queue and preserved words too.
   Reviewed compound words come from `data/dictionary/tatar-compound-review.tsv`: the rebuild
   writes their frequencies and the required list, and passes them as additional
   `--extra-entries`/`--require-entries` files. Only that file admits a hyphenated word; corpus
   tokenization keeps rejecting a hyphen outside the reviewed dictionary vocabulary. A compound
   is stored lowercase with U+002D only between letters; a proper compound carries its display
   form in the proper-name asset instead. The apostrophe review admits no word: U+0027 and
   U+2019 stay word boundaries in tokenization and never enter a dictionary, bigram, personal
   store or display asset.

2. **Dictionaries.** `dict_accept.py pack --write` merges the baseline, the accepted words and
   (for Tatar) the admitted forms. Frequencies are written plus conversational plus the bonus
   file, when it exists. The Tatar cutoff ranks entries by their stored frequency plus nine times
   their conversational frequency; this weight does not change the stored frequencies. Russian
   uses the stored frequency for cutoff selection. The packer keeps the top entries
   (`TATAR_DICTIONARY_TOP` for Tatar, 100 000 for Russian), then appends any reviewed required form
   still below that cutoff, and writes schema 2. The rebuild also passes the words of the asset
   being replaced, so a word that already ships is appended with its stored frequency instead of
   being removed; a quality change never drops lexical coverage by moving a cutoff.
   The release APK size limit is the only limit on the dictionary size, so run
   `scripts/release_check.sh` after you change `TATAR_DICTIONARY_TOP`. The packer's raw size
   bound (`MAX_UNCOMPRESSED_BYTES`) equals the reader's safety bound `TdictFormat.MAX_RAW_SIZE`
   and is not a budget.
3. **Bigram and trigram tables.** `bigram_asset_pack.py pack --schema 3` is run against the new
   dictionaries with the `BIGRAMS` parameters (head count, successors per head, extra heads,
   training corpora). For Tatar, that image is an intermediate: `trigram_asset_pack.py pack`
   counts the same training corpora, selects two-word contexts, ranks their successors by
   Jelinek-Mercer interpolation with the full selected-head bigram distributions, and appends the
   trigram sections to produce `tatar_bigrams_v2.tatbigr.zlib` (schema 4). Its configuration is
   fixed in the packer and reads no evaluation file. Change any of these parameters only together
   with a rebuild.
3b. **Proper-name display.** `proper_name_pack.py build` writes
   `dictionaries/tatar_proper_names_v1.txt` from the proper-name review and the proper-display rows
   of the compound review. Every key must be in the rebuilt Tatar dictionary; a key that does not
   ship fails the rebuild. The asset is plain text: a `#` header, then `key<TAB>display` rows
   sorted by key.
4. **Cold-companion lists.** `companion_list_pack.py build` derives each selected language's
   list from the dictionary of step 2 alone: the top 20 000 words by stored frequency, no corpus
   and no baseline input. The words are an exact list, never a probabilistic filter, because a
   false positive would suppress a legitimate correction.
5. **Pins.** Each asset is measured, and its pins are rewritten in
   `DictionaryStorageContracts.kt`, `BigramStorageContracts.kt` and
   `CompanionListStorageContracts.kt`, between the `// <generated pins>` marker pairs of each
   spec block. There is one atomic write per file, and the values are read back afterwards.
6. **Calibration pins.** The typo-recovery and glide calibration sets are deterministic
   functions of the Tatar dictionary, so their identities change with it. The rebuild
   regenerates the sets and rewrites the pinned identities in `tests/typo_pack/`,
   `tests/glide_pack/` and the JVM calibration tests (`LongPressRecoveryCalibrationTest`,
   `TtFullSubstitutionCalibrationTest`, `GlideRecoveryCalibrationTest`, `GlideGoldenExportTest`).
   Only this step writes them; `tests/rebuild_assets/` computes the same pins and compares
   them with the tree (`calibration_pin_updates`) without writing.
7. **Check.** The `--check` procedure runs over every shipped asset, including the proper-name
   asset and every reviewed compound against the dictionary.

Both bigram rebuilds pass `--recover-punctuation`: Unicode punctuation is stripped at token
edges before word normalization, punctuation left inside a token still rejects it, and
sentence-terminal edges remain pair boundaries. The pack report counts recovered tokens and
pairs by punctuation class.

The raw tables and the JSON reports go to `--work-dir`, which defaults to
`build/rebuild_assets/`.

### One language: `--only tatar|russian`

`--only` rebuilds the dictionary, the bigram table, the cold-companion list and the pins of one
language, and needs only that language's inputs. The SHA-256 of the other language's assets and
its contract pins are recorded before the run and compared afterwards. If anything changed, the
run fails. The final check still covers all six assets. `--only` cannot be combined with
`--check`.

### Consistency check: `--check`

```
python3 scripts/rebuild_assets.py --check --allow-known-drift
```

This mode rebuilds and writes nothing, and needs no corpora. It checks that:

- `data/corpus-manifest.json` is well formed and has exactly one entry per corpus file the
  rebuild reads; with `--corpus-dir DIR`, the files in `DIR` also match their entries;
- each asset's compressed and raw size, both SHA-256 values, the entry or head count, and the
  bigram table's link to its dictionary match the contract;
- no bigram head is outside its dictionary;
- the heads of each table equal the heads the packer would select from the current dictionary
  (the top heads by frequency plus the extra heads);
- the words of each cold-companion list equal the top words the packer would select from the
  current dictionary.

Each asset gets a verdict: `ok`, `mismatch`, `missing`, `drift`, `known-drift` or
`stale-known-drift`. The corpus section gets `not-checked` (no `--corpus-dir`), `ok`, `missing`
or `mismatch`, with a verdict per file. The JSON report goes to stdout and a one-line summary
per asset to stderr.
Exit codes: 0 consistent, 1 mismatch, 2 missing input or unparsable contract or manifest.

The check runs as part of the python suite (`tests/rebuild_assets` runs it against the real
tree), as its own CI step, and in `scripts/release_check.sh` unless `--quick` or `--checks` is given.

## Known drift

A difference between a table's heads and the heads the packer would select is drift.
`--allow-known-drift [FILE]` accepts drift only if the counts match an entry in
`scripts/known_asset_drift.json` exactly. The entry is keyed by the asset path under `assets/`
and has the fields `missing_top_heads`, `unexpected_heads` and `reason`. A different count, an
entry whose drift has disappeared, or an entry for an unknown asset fails the check.

The current entries come from a rule of the generator, not from stale data. A top head that has
no successor inside the dictionary is dropped, because the format does not allow empty success
ranges. Russian currently has no known-drift entry: every selected head has an in-dictionary
successor under the current training inputs. Tatar: two top-cutoff heads promoted by the
extra-corpus merge, and extra-head candidates — frequent conversational question and predicative
forms — whose partner words are outside the shipped dictionary. When a rebuild changes these
counts, update the file and its `reason`.

## Pins outside the storage contracts

The storage contracts are the single source of truth for the dictionary, bigram and
cold-companion pins. These readers re-derive nothing after a rebuild:

- `scripts/typo_pack.py` and `scripts/typo_eval_pack.py` read the dictionary pins from
  `DictionaryStorageContracts.kt`; `glide_pack.py` reuses the Tatar ones.
- `TdictValidatorTest`, `TatBigrValidatorTest` and `tests/dictionary_pack/` compare the
  committed assets with the contract pins and with the file headers.
- The rebuild rewrites the contract `fallbackWords` with the dictionary pins (step 5) and the
  typo-set and glide-set identities (step 6).

A rebuild leaves the following to you. Each one fails its test until it is updated:

- Sentence-start tables. Rebuild them with `sentstart_pack.py` when a word left the
  dictionary (`*SentStartAssetTest` checks that every word is in it), then update the record
  count and SHA-256 in `tests/sentstart_pack/`, `TatarSentStartAssetTest` and
  `RussianSentStartAssetTest`.
- Query-review tables. `data/dictionary/tt-query-review.tsv` and `ru-query-review.tsv`
  record the top candidates of the committed dictionaries for the queries in
  `tests/dictionary_pack/fixtures/manual_{tatar,russian}_queries.txt`. Print the new candidates
  with `dictionary_pack.py query-audit --asset <asset> --queries <queries>` (add
  `--language rus` for Russian), review them, write them into the table with the review date,
  and set `TATAR_REVIEW_DATE` or `RUSSIAN_REVIEW_DATE` in `tests/dictionary_pack/` to that
  date. `RealDictionaryPrefixIndexTest` compares live lookups with the recorded candidates;
  it, `LongPressRecoveryCalibrationTest` and `DictionaryIoStrategyCalibrationTest` also require
  the Tatar table to keep its row count.
- Extra bigram heads. The ranks behind `scripts/bigram_extra_heads_tat.txt` depend on the
  Tatar dictionary, and the regenerator reads the committed one. After a rebuild that changed
  the dictionary, run `scripts/bigram_extra_heads_regen.py --corpus-dir <dir>`, rebuild the
  Tatar side again (`--only tatar`) so the bigram table is packed with the new list, then
  update the word count and sample words in `ShippedExtraHeadListTest`
  (`tests/bigram_asset_pack/`), which also checks that no listed word is reached by the cutoff.
- Typo eval sets. The mutated and valid-OOV sets of `scripts/typo_eval_pack.py` are built
  over the dictionary. Regenerate the committed sets in `app/src/test/resources/` and update
  their row, byte and class counts and SHA-256 in `tests/typo_eval_pack/`,
  `TypoMutatedEvalTest`, `TtDl1TierCalibrationTest`, `RuTypoMutatedEvalTest` and
  `AutocorrectWideningCalibrationTest`. A reviewed safety word admitted to the dictionary must
  leave `PROPOSED_VALID_OOV_WORDS`; the correctly typed-word and whole-dictionary suites then
  check that it is never replaced.
- Measured results. Hit, coverage, recall and probe counts measured on the committed assets
  are pinned in `TtSuggestEvalTest`, `RuSuggestEvalTest`, `PersonalizationEvalTest`,
  `tests/personalization_eval/`, `TypoMutatedEvalTest`, `RuTypoMutatedEvalTest`,
  `TtDl1TierCalibrationTest`, `TtFullSubstitutionCalibrationTest`,
  `AutocorrectWideningCalibrationTest` and `GlideRecoveryCalibrationTest`. Re-pin them from a reviewed measurement; the thresholds
  these tests gate on do not move with the pins.
- Autocorrect floors. `AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT` and
  `MIN_CANDIDATE_FREQUENCY_RU` must equal the frequency of a fixed-rank word;
  `AutocorrectFloorPinTest` reports the new value.
- Expected words. Some tests assert concrete words of the committed assets: the
  top-frequency lists in `TdictPrefixIndexTopFrequentWordsTest` and `TtNextWordFillE2ETest`, the
  next-word rows in `TatBigrPrefixIndexTest`, the completions in
  `TdictPrefixIndexSameStemBoostTest` and `CursorMoveBandTest`, the dictionary membership cases
  in `EmptyPrefixProbeEquivalenceTest`, the Russian prefixes «при» to «привет» that must fill
  four cells in `ShippedIndexPrefixCoverageTest`, and the decoded words in `GlideEndToEndTest` and
  `GlideDecoderHostGeometryReuseTest`.
- Head drift. `scripts/known_asset_drift.json` and the drift counts asserted in
  `tests/rebuild_assets/`.

Emoji assets have no storage contract. Their SHA-256 and count pins are in `tests/<packer>/` and
in the JVM emoji tests, so any change to the data needs new pins there.

## Other assets

Each packer has a `build` subcommand. Run it from the repository root:

```
python3 scripts/emoji_pack.py build --input emoji-test.txt \
    --output app/src/main/assets/emoji/emoji_set_v1.txt
python3 scripts/emoji_skin_pack.py build --input emoji-test.txt \
    --panel-asset app/src/main/assets/emoji/emoji_set_v1.txt \
    --output app/src/main/assets/emoji/emoji_skin_v1.txt
python3 scripts/emoji_search_pack.py build --cldr-dir CLDR \
    --panel-asset app/src/main/assets/emoji/emoji_set_v1.txt \
    --tt-extra scripts/emoji_search_tt_extra.txt \
    --tt-names scripts/emoji_search_tt_names.txt \
    --output app/src/main/assets/emoji/emoji_search_v1.txt
python3 scripts/emoji_suggest_pack.py build --data scripts/emoji_suggest_data.tsv \
    --panel-asset app/src/main/assets/emoji/emoji_set_v1.txt \
    --output app/src/main/assets/emoji/emoji_suggest_v1.txt
python3 scripts/sentstart_pack.py build --language tat \
    --sentences CORPUS/tat_mixed_2015_1M-sentences.txt CORPUS/tat_web_2018_1M-sentences.txt \
    CORPUS/tt_conv_train90-sentences.txt CORPUS/tt_conv_train90-sentences.txt \
    CORPUS/tt_conv_train90-sentences.txt CORPUS/tt_conv_train90-sentences.txt \
    CORPUS/tt_conv_train90-sentences.txt CORPUS/tt_conv_train90-sentences.txt \
    CORPUS/tt_conv_train90-sentences.txt CORPUS/tt_conv_train90-sentences.txt \
    CORPUS/tt_conv_train90-sentences.txt CORPUS/tt_conv_train90-sentences.txt \
    --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
    --output app/src/main/assets/dictionaries/tatar_sentstart_v1.txt
```

The Tatar command lists `tt_conv_train90-sentences.txt` ten times to apply the same conversational
corpus weight as the Tatar bigram table. For the Russian sentence-start table, use `--language rus`
with the three Russian Leipzig corpora plus `rus_conv_thinned60-sentences.txt` once, as listed in
the table's header.

The cold-companion lists need no corpus and no baseline, only the committed dictionary:

```
python3 scripts/companion_list_pack.py build \
    --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
    --language tat --top 20000 \
    --output app/src/main/assets/dictionaries/tatar_companion_v1.tcomp.zlib
```

For the Russian list, use `--language rus` with `russian_top100k_v1.tdict.zlib`. The rebuild
runs the same command itself (step 4), so this manual form is only for inspection.

## Equivalence checks

The pipeline can still write the previous schemas: `dictionary_pack.py build|repack --schema 1`
and `bigram_asset_pack.py pack --schema 2`. `bigram_asset_pack.py repack` converts a schema 2
table to schema 3 without corpora. These writers exist for golden tests and for proving that a
format change is lossless:

```
python3 scripts/schema2_equivalence_check.py --v1 V1.tdict.zlib --v2 V2.tdict.zlib --language tat
python3 scripts/schema3_equivalence_check.py --v2 V2.tatbigr.zlib --v3 V3.tatbigr.zlib \
    --dictionary DICT.tdict.zlib --language tat
```

Each script parses both files completely and compares them. It also runs an independent model of
the Kotlin reader: every distinct prefix of 1 to 5 characters for dictionaries, and every head plus
a sample of non-heads for tables. Exit codes: 0 equivalent, 1 mismatch, 2 missing input.

## Tests

```
bash scripts/run_python_tests.sh
```

Tests are in `tests/<script>/test_<script>.py` and use plain `unittest`; pytest is not used. CI
runs the same runner.

## After a rebuild

1. `python3 scripts/rebuild_assets.py --check --allow-known-drift` passes.
2. Update `known_asset_drift.json` if the head drift changed.
3. Run the gates from `AGENTS.md`: JVM tests, python tests, `lintRelease`, `check-no-internet.sh`,
   and the release APK size limit.
4. Update the `NOTICE.txt` next to the asset if its sources changed.
