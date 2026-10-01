# Asset pipeline

The bundled data is built by the python scripts in `scripts/`. Never edit asset files by hand.
The scripts use only the standard library, give the same bytes for the same inputs, and exit
nonzero without writing output when an input or a result is invalid. The binary formats are
described in [ASSET-FORMATS.md](ASSET-FORMATS.md).

| Asset (`app/src/main/assets/`) | Built by | Inputs |
|---|---|---|
| `dictionaries/*.tdict.zlib` | `scripts/rebuild_assets.py` (runs `dict_accept.py pack`) | release 1.8.4 baseline dictionaries, `data/dictionary/dict-accept/`, Tatar word forms |
| `bigrams/*.tatbigr.zlib` | `scripts/rebuild_assets.py` (runs `bigram_asset_pack.py pack`) | Leipzig sentence corpora, conversational training files, the fresh dictionaries |
| `dictionaries/*_sentstart_v1.txt` | `scripts/sentstart_pack.py` | Leipzig sentence corpora, the bundled dictionary |
| `emoji/emoji_set_v1.txt`, `emoji_skin_v1.txt` | `scripts/emoji_pack.py`, `scripts/emoji_skin_pack.py` | Unicode Emoji 15.1 `emoji-test.txt` |
| `emoji/emoji_search_v1.txt` | `scripts/emoji_search_pack.py` | CLDR 44 annotations (ru, en), `scripts/emoji_search_tt_extra.txt` |
| `emoji/emoji_suggest_v1.txt` | `scripts/emoji_suggest_pack.py` | `scripts/emoji_suggest_data.tsv` |

## Inputs

These inputs are not in the repository:

- **Baseline dictionaries.** The two release 1.8.4 dictionary assets are the only valid starting
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
- **Leipzig corpora** (CC BY 4.0), from https://wortschatz.uni-leipzig.de/en/download. The
  rebuild reads the `*-sentences.txt` files named in `BIGRAMS[].train` and the Tatar
  `*-words.txt` files named in `WORDFORM_FREQUENCY_SOURCES` (both are in `rebuild_assets.py`).
  They are expected in `--corpus-dir`, which defaults to `~/corpora-leipzig` for a rebuild.
  `tat_news_2015_1M` is the held-out evaluation set and must not be used to build shipped data.
- **Conversational training files** `tt_conv_train90-sentences.txt` and
  `rus_conv_thinned60-sentences.txt`. `research/corpus/dl.sh` downloads the OPUS Tatoeba and
  OpenSubtitles dumps, and `research/corpus/make_conv_train.py` turns them into deduplicated
  Leipzig-format files. The line-id filter is applied by hand (Tatar: `id % 10 != 1`, which
  keeps the rest as held-out data; Russian: `id % 60 == 0`) and keeps the original ids, for
  example `awk -F'\t' '$1 % 60 == 0'`. Put the results in `--corpus-dir`.
- **Emoji sources**: `emoji-test.txt` (Unicode Emoji 15.1) and the CLDR 44 ru/en annotation
  files, saved in one `--cldr-dir` as `ru.xml`, `en.xml`, `derived-ru.xml` and
  `derived-en.xml`. The packers pin their SHA-256.

These inputs are in the repository:

- `data/corpus-manifest.json`: the size and SHA-256 of every corpus file the rebuild reads
  (the Leipzig and conversational files above), with a short `source` note. Its entry set must equal the files named in `BIGRAMS[].train` and
  `WORDFORM_FREQUENCY_SOURCES`. When a corpus is replaced on purpose, update its entry in the
  same change as the rebuilt assets.
- `data/dictionary/`: the word review queues (`*-conv-review.tsv`, `*-query-review.tsv`) and
  `dict-accept/`, which holds the accepted and rejected words (`accepted-*.tsv`,
  `rejected-*.tsv`, written by `dict_accept.py select`) and the conversational frequencies
  (`conv-freq-*.tsv`).
- `scripts/wordform_exceptions_tat.tsv`: exception rules for the Tatar word-form generator
  (`scripts/wordform_gen.py`). Its SHA-256 is pinned in `tests/wordform_gen/`.
- `scripts/bigram_extra_heads_tat.txt`: extra Tatar bigram heads (imperatives ranked below the
  head cutoff). The rule that produced the list is in the file header; do not add words by hand.
- `scripts/emoji_suggest_data.tsv` and `scripts/emoji_search_tt_extra.txt`: curated emoji data.

## `rebuild_assets.py`

This is the single entry point for dictionaries and bigram tables. A table is a set of indices
into its dictionary, so the two are always rebuilt together.

### Full rebuild

```
python3 scripts/rebuild_assets.py --baseline /tmp/baseline-1.8.4 \
    [--corpus-dir DIR] [--work-dir DIR] [--allow-known-drift]
```

The script first lists every missing input and stops if there are any. Then it compares the
corpus files of the selected languages with `data/corpus-manifest.json` (size first, then
SHA-256) and stops with exit code 1, before writing anything, if one differs. Then it runs:

1. **Tatar word forms.** `wordform_gen.py` generates forms for every stem in the baseline plus
   the accepted words. A form is admitted when it occurs in the frequency sources (the two
   Leipzig `*-words.txt` files plus `conv-freq-tt.tsv`), and its count becomes its frequency.
   The result is written to `<work-dir>/wordforms-admitted-tt.tsv`, with a JSON report next to
   it.
2. **Dictionaries.** `dict_accept.py pack --write` merges the baseline, the accepted words and
   (for Tatar) the admitted forms. Frequencies are written plus conversational. It keeps the top
   entries (`TATAR_DICTIONARY_TOP` for Tatar, 100 000 for Russian) and writes schema 2.
   `TATAR_DICTIONARY_TOP` is the largest cutoff that fits the size limits; measure again before
   you change it.
3. **Bigram tables.** `bigram_asset_pack.py pack --schema 3` is run against the new
   dictionaries with the `BIGRAMS` parameters (head count, successors per head, extra heads,
   training corpora). Change these parameters only together with a rebuild.
4. **Pins.** Each asset is measured, and its pins are rewritten in
   `DictionaryStorageContracts.kt` and `BigramStorageContracts.kt`. There is one atomic write
   per file, and the values are read back afterwards.
5. **Check.** The `--check` procedure runs over all four assets.

The raw tables and the JSON reports go to `--work-dir`, which defaults to
`build/rebuild_assets/`.

### One language: `--only tatar|russian`

`--only` rebuilds the dictionary, the bigram table and the pins of one language, and needs only
that language's inputs. The SHA-256 of the other language's assets and its contract pins are
recorded before the run and compared afterwards. If anything changed, the run fails. The final
check still covers all four assets. `--only` cannot be combined with `--check`.

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
  (the top heads by frequency plus the extra heads).

Each asset gets a verdict: `ok`, `mismatch`, `missing`, `drift`, `known-drift` or
`stale-known-drift`. The corpus section gets `not-checked` (no `--corpus-dir`), `ok`, `missing`
or `mismatch`, with a verdict per file. The JSON report goes to stdout and a one-line summary
per asset to stderr.
Exit codes: 0 consistent, 1 mismatch, 2 missing input or unparsable contract or manifest.

The check runs as part of the python suite (`tests/rebuild_assets` runs it against the real
tree) and in `scripts/release_check.sh` unless `--quick` is given.

## Known drift

A difference between a table's heads and the heads the packer would select is drift.
`--allow-known-drift [FILE]` accepts drift only if the counts match an entry in
`scripts/known_asset_drift.json` exactly. The entry is keyed by the asset path under `assets/`
and has the fields `missing_top_heads`, `unexpected_heads` and `reason`. A different count, an
entry whose drift has disappeared, or an entry for an unknown asset fails the check.

The current entries come from a rule of the generator, not from stale data. A top head that has
no successor inside the dictionary is dropped, because the format does not allow empty success
ranges. Russian: two conversational words have no pair in the thinned conversational input.
Tatar: a few `-гәнчә` gerunds and some extra-head candidates have partner words outside the
dictionary. When a rebuild changes these counts, update the file and its `reason`.

## Pins outside the storage contracts

`rebuild_assets.py` rewrites only the two storage contracts. After a Tatar dictionary rebuild,
you also need to update these by hand:

- `EXPECTED_ASSET_SHA256` and `EXPECTED_RAW_SHA256` in `scripts/typo_pack.py`
  (`glide_pack.py` reuses them);
- the recorded typo-set and glide-set identities in `tests/typo_pack/`, `tests/glide_pack/` and
  the JVM calibration tests that regenerate these sets;
- the sentence-start tables, if a word left the dictionary (`*SentStartAssetTest` checks that
  every word is in it).

Emoji and sentence-start assets have no storage contract. Their SHA-256 and count pins are in
`tests/<packer>/` and in the JVM asset tests, so any change to the data needs new pins there.

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
    --output app/src/main/assets/emoji/emoji_search_v1.txt
python3 scripts/emoji_suggest_pack.py build --data scripts/emoji_suggest_data.tsv \
    --panel-asset app/src/main/assets/emoji/emoji_set_v1.txt \
    --output app/src/main/assets/emoji/emoji_suggest_v1.txt
python3 scripts/sentstart_pack.py build --language tat \
    --sentences CORPUS/tat_mixed_2015_1M-sentences.txt CORPUS/tat_web_2018_1M-sentences.txt \
    --dictionary app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib \
    --output app/src/main/assets/dictionaries/tatar_sentstart_v1.txt
```

For the Russian sentence-start table, use `--language rus` with the three Russian corpora listed
in the table's header.

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
for f in tests/*/test_*.py; do python3 "$f" || exit 1; done
```

Tests are in `tests/<script>/test_<script>.py` and use plain `unittest`; pytest is not used. CI
runs the same loop.

## After a rebuild

1. `python3 scripts/rebuild_assets.py --check --allow-known-drift` passes.
2. Update the pins outside the contracts (above) and `known_asset_drift.json` if needed.
3. Run the gates from `AGENTS.md`: JVM tests, python tests, `lintRelease`, `check-no-internet.sh`,
   and the release APK size limit.
4. Update the `NOTICE.txt` next to the asset if its sources changed.
