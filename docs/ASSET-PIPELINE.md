# Asset pipeline

The bundled data is built by the python scripts in `scripts/`. Never edit asset files by hand.
The scripts use only the standard library, give the same bytes for the same inputs, and exit
nonzero without writing output when an input or a result is invalid. The binary formats are
described in [ASSET-FORMATS.md](ASSET-FORMATS.md).

| Asset (`app/src/main/assets/`) | Built by | Inputs |
|---|---|---|
| `dictionaries/*.tdict.zlib` | `scripts/rebuild_assets.py` (runs `dict_accept.py pack`) | release 1.8.4 baseline dictionaries, `data/dictionary/dict-accept/`, Tatar word forms |
| `dictionaries/*_companion_v1.tcomp.zlib` | `scripts/rebuild_assets.py` (runs `companion_list_pack.py build`) | the committed dictionary of the same language |
| `dictionaries/*_sentstart_v1.txt` | `scripts/sentstart_pack.py` | Leipzig sentence corpora, the conversational training files, the bundled dictionary |
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
  the bonus weights.
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
  calibration set are built from these two files.
- Conversational training files `tt_conv_train90-sentences.txt` and
  `rus_conv_thinned60-sentences.txt`. `research/corpus/dl.sh` downloads the Tatoeba and
  OpenSubtitles dumps (Tatar Tatoeba: the weekly tatoeba.org export, which OPUS lags; the
  rest: OPUS), and `research/corpus/make_conv_train.py` turns them into deduplicated
  Leipzig-format files. The line-id filter is applied by hand (Tatar: `id % 10 != 1`; Russian:
  `id % 60 == 0`) and keeps the original ids, for example `awk -F'\t' '$1 % 60 == 0'`. Put the
  results in `--corpus-dir`. OpenSubtitles publishes no license grant for this text. The
  repository owner consciously accepts the legal risk of using and redistributing derived data
  without one; attribution and the requested link back do not grant permission. The Tatar rows
  with `id % 10 == 1` are not in
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
- `data/dictionary/`: the word review queues (`*-conv-review.tsv`, `*-query-review.tsv`) and
  `dict-accept/`, which holds the accepted and rejected words (`accepted-*.tsv`,
  `rejected-*.tsv`, written by `dict_accept.py select`), the conversational frequencies
  (`conv-freq-*.tsv`) and the extra-corpus bonus frequencies (`bonus-freq-tt.tsv`, written by
  `research/corpus/tt_merge_sim.py bonus-tsv`; `dict_accept.py pack` adds them to the merged
  frequency of composition words without changing the composition).
- `scripts/wordform_exceptions_tat.tsv`: exception rules for the Tatar word-form generator
  (`scripts/wordform_gen.py`). Its SHA-256 is pinned in `tests/wordform_gen/`.
- `scripts/bigram_extra_heads_tat.txt`: extra Tatar bigram heads (imperatives ranked below the
  head cutoff). The rule that produced the list is in the file header; do not add words by hand.
- `scripts/emoji_suggest_data.tsv`, `scripts/emoji_search_tt_extra.txt` and
  `scripts/emoji_search_tt_names.txt`: curated emoji data.

## `accept_wordset.py`

A candidate word set (a new review pool in `data/dictionary/*-conv-review.tsv`) is accepted on
evidence, not on the rule alone. `scripts/accept_wordset.py` runs the evidence steps in order,
each as a subprocess of the existing script; the runner measures nothing itself:

1. `dict_accept.py select --baseline <dir>`: the acceptance rule over the pool, rewriting
   `data/dictionary/dict-accept/accepted-*.tsv` and `rejected-*.tsv`.
2. `research/corpus/measure_accept.py <tag> <baseline> <corpora>`: the held-out coverage delta
   of the accepted set against the shipped baseline; also rewrites `conv-freq-*.tsv`.
3. `research/corpus/tt_gate_check.py` or `ru_gate_check.py`: the candidate dictionary and
   bigram table (both built outside the asset tree) against the committed assets on the pinned
   eval set — the coverage gate and the paired bootstrap CI95 of the chain top-3 delta. A
   rejected gate stops the run before the query audit: the candidate is not accepted.
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

## `rebuild_assets.py`

This is the single entry point for dictionaries and bigram tables. A table is a set of indices
into its dictionary, so the two are always rebuilt together.

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
   it.
2. **Dictionaries.** `dict_accept.py pack --write` merges the baseline, the accepted words and
   (for Tatar) the admitted forms. Frequencies are written plus conversational plus the bonus
   file, when it exists. The Tatar cutoff ranks entries by their stored frequency plus nine times
   their conversational frequency; this weight does not change the stored frequencies. Russian
   uses the stored frequency for cutoff selection. The packer keeps the top entries
   (`TATAR_DICTIONARY_TOP` for Tatar, 100 000 for Russian) and writes schema 2.
   The release APK size limit is the only limit on the dictionary size, so run
   `scripts/release_check.sh` after you change `TATAR_DICTIONARY_TOP`. The packer's raw size
   bound (`MAX_UNCOMPRESSED_BYTES`) equals the reader's safety bound `TdictFormat.MAX_RAW_SIZE`
   and is not a budget.
3. **Bigram tables.** `bigram_asset_pack.py pack --schema 3` is run against the new
   dictionaries with the `BIGRAMS` parameters (head count, successors per head, extra heads,
   training corpora). Change these parameters only together with a rebuild.
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
7. **Check.** The `--check` procedure runs over all six assets.

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
  `AutocorrectWideningCalibrationTest`.
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
