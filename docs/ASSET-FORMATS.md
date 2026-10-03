# Asset and store formats

Reference for the binary files the keyboard reads: the bundled dictionaries (TATDICT schema 2),
the bundled bigram tables (TATBIGR schema 3), and the personal-dictionary files (TATPERS,
TATPERSB, TATPERSE, TATREF). The plain-text assets are summarized at the end. How the bundled files
are built is described in [ASSET-PIPELINE.md](ASSET-PIPELINE.md).

The code is the authority. The format constants are in `TdictFormat`
(`DictionaryStorageContracts.kt`), `TatBigrFormat` (`BigramStorageContracts.kt`), and
`TpersFormat`, `TpersbFormat`, `TpersemFormat`, `TrefFormat` (`dictionary/personal/`). All code
paths below are relative to `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/`.

## Common conventions

- All integers are unsigned little-endian. `u8`/`u16`/`u32` are fixed width.
- A varint is an unsigned base-128 integer (low 7 bits first, high bit set on every byte except
  the last) that fits in u32. Readers accept only the minimal encoding: an overlong varint, or a
  fifth byte above `0x0f`, is an error.
- Words are UTF-8, NFC, lowercase (`Locale.ROOT`). Word order is unsigned byte order of the UTF-8
  encoding, which is the same as code-point order.
- The checksum field holds the SHA-256 of the whole file, computed with the field itself set to
  zero. `checksumAlgorithm = 1` means SHA-256, and no other value is accepted.
- Every header begins with the same 16 bytes: magic (8 bytes of ASCII) at offset 0, then the
  u16 fields schemaId at 8, formatVersion at 10, headerSize at 12 and checksumAlgorithm at 14.
- A validator rejects a file at the first violation, and nothing repairs data. If a bundled file
  fails validation, it is not published and its language has no suggestions from it. A personal
  file that fails validation is quarantined (see below).

## TATDICT schema 2: bundled dictionaries

Assets: `assets/dictionaries/{tatar,russian}_top100k_v1.tdict.zlib`. The `top100k` in the name
is frozen for compatibility; the real entry count is pinned in `expectedEntryCount`. Constants:
`SCHEMA_ID = 2`, `FORMAT_VERSION = 1`, `HEADER_SIZE = 72`, `BLOCK_SIZE = 8`,
`MAX_WORD_BYTES = 128`, and the size limits `MAX_COMPRESSED_SIZE` and `MAX_RAW_SIZE`.

| Offset | Size | Field | Required value |
|---:|---:|---|---|
| 0 | 8 | magic `TATDICT\0` | |
| 8 / 10 / 12 / 14 | 2 each | schemaId / formatVersion / headerSize / checksumAlgorithm | 2 / 1 / 72 / 1 |
| 16 | 4 | entryCount | the pinned `expectedEntryCount` |
| 20 | 4 | blockCount | ⌈entryCount / 8⌉ |
| 24 | 4 | blockIndexOffset | 72 |
| 28 | 4 | blocksOffset | 72 + 4 · blockCount |
| 32 | 4 | blocksSize | |
| 36 | 4 | fileSize | blocksOffset + blocksSize = file length |
| 40 | 32 | SHA-256 checksum | |

The body has two parts:

1. Block index: `blockCount` × u32, the absolute file offset of each block. Lookups binary
   search on the first word of each block.
2. Blocks of 8 entries each (the last block may be shorter). Every entry is front-coded against
   the block's first word:

```
u8 firstLength, firstWord[firstLength]            entry 0, stored whole
(n - 1) × { varint prefixLength,                  bytes shared with the block's first word
            u8 suffixLength, suffix[suffixLength] }
n × varint frequency                              one per entry, in entry order
```

The shared prefix is counted in bytes. The common prefix of two valid UTF-8 strings always ends
on a code-point boundary, so it never splits a character. Entry `i` is in block `i / 8` at
position `i % 8`. The bigram tables depend on this index-based access.

**Validation** (`storage/TdictValidator.kt`). `inflateAsset` accepts a single zlib stream with
no preset dictionary and no trailing or concatenated data, within the size limits. The
compressed size, compressed SHA-256 and raw size must equal the pins. `validateRaw` then
requires:

- the file length equals the pinned raw size, and the header constants equal the spec;
- entryCount is non-zero and equals the pin, and every section field has the value in the
  table above (overflow-checked, within u32);
- the checksum matches, and the SHA-256 of the whole file equals the pinned raw SHA-256;
- the first block offset equals blocksOffset, and block offsets strictly increase within the
  file;
- `firstLength` is 1..128, `prefixLength` ≤ `firstLength`, `suffixLength` ≥ 1,
  prefix + suffix ≤ 128 bytes, and every varint is minimal;
- every word is strict UTF-8, 1..64 code points, NFC lowercase, and in the Tatar alphabet (a
  superset of the Russian alphabet, so the Russian file passes the same check);
- words strictly increase, so there are no duplicates, and every frequency is > 0;
- each block ends exactly where the next one starts, and the last block ends at end of file.

The runtime reader (`engine/TdictPrefixIndex.kt`, `open`) checks the header and the section
arithmetic again on the memory-mapped file.

**On the device.** The raw file is inflated into a temp file, fsynced, validated, and atomically
renamed into device-protected storage under `files/dictionaries/` (Tatar) or
`files/dictionaries-ru/` (Russian). The final name is
`<family>-v<generation:6>-s<schemaId>-f<formatVersion>-<rawSha256>.tdict`, so a new schema or new
content produces a new name. After an update the asset is inflated once, and retention deletes
the old file after its last lease is closed. There is no migration code.

## TATBIGR schema 3: bundled bigram tables

Assets: `assets/bigrams/{tatar,russian}_bigrams_v1.tatbigr.zlib`. Constants: `SCHEMA_ID = 3`,
`FORMAT_VERSION = 1`, `HEADER_SIZE = 128`, `CHECKSUM_OFFSET = 96`, `HEAD_BLOCK_SIZE = 64`.

A table stores no words. Heads (context words) and successors are indices into the dictionary of
the same language, and the header names that dictionary by its raw SHA-256. A table is valid only
together with exactly that dictionary.

| Offset | Size | Field | Required value |
|---:|---:|---|---|
| 0 | 8 | magic `TATBIGR\0` | |
| 8 / 10 / 12 / 14 | 2 each | schemaId / formatVersion / headerSize / checksumAlgorithm | 3 / 1 / 128 / 1 |
| 16 | 4 | headCount | the pinned `expectedHeadCount` |
| 20 | 4 | pairCount | sum of all success counts |
| 24 | 4 | headBlockCount | ⌈headCount / 64⌉ |
| 28 | 4 | blockIndexOffset | 128 |
| 32 | 4 | headDeltasOffset | 128 + 12 · headBlockCount |
| 36 | 4 | headDeltasSize | |
| 40 | 4 | countsOffset | headDeltasOffset + headDeltasSize |
| 44 | 4 | successIdsOffset | countsOffset + headCount |
| 48 | 4 | successIdsSize | |
| 52 | 4 | fileSize | successIdsOffset + successIdsSize = file length |
| 56 | 32 | raw SHA-256 of the linked dictionary | the pinned `expectedDictionaryRawSha256` |
| 88 | 8 | reserved | zero |
| 96 | 32 | SHA-256 checksum | |

Four sections follow the header, with no padding between them:

1. Head block index: `headBlockCount` records of 12 bytes,
   `{u32 firstDictIndex, u32 headDeltaOffset, u32 successOffset}`. The offsets are relative to
   sections 2 and 4. There is one record per 64 heads, and lookups binary search on
   `firstDictIndex`.
2. Head deltas: for each block, (blockHeads − 1) varint deltas, each ≥ 1. The first head of a
   block is the absolute index in its record. Heads are in code-point order, like the dictionary,
   so their indices strictly increase.
3. Success counts: `headCount` × u8, each ≥ 1. The packer drops heads that have no successors
   and writes at most its `successes_per_head` successors per head.
4. Success ids: `pairCount` varint dictionary indices, grouped by head, each group in packing
   order (count descending, then code point ascending). The reader returns them in this order
   and does not re-rank them.

**Validation** (`storage/TatBigrValidator.kt`). Inflation follows the same rules as for TATDICT,
using the TatBigr size limits. Then:

- the header constants, headCount (non-zero, equal to the pin) and the linked dictionary SHA-256
  equal the spec, and the reserved bytes are zero;
- every section offset has its required value, and fileSize equals the file length;
- the checksum matches, and the SHA-256 of the whole file equals the pinned raw SHA-256;
- block `firstDictIndex` values strictly increase; stream offsets never decrease and stay inside
  their sections; the first block's offsets are 0;
- head indices strictly increase, every varint is minimal, and every count is ≥ 1;
- each delta stream ends exactly where the next block's stream starts, each success stream
  starts at its record's offset, the counts sum to pairCount, and the success stream ends
  exactly at the end of its section.

The validator does not have the dictionary, so it cannot check upper bounds on indices.
`engine/TatBigrPrefixIndex.kt` (`open`) checks the header again, requires the linked SHA-256 to
equal the raw SHA-256 of the dictionary that is actually loaded, and requires every head and
success index to be below that dictionary's entryCount. If the table does not match the
dictionary, next-word prediction for that language stays empty and typing is not affected.
On the device, tables are staged, named and retained the same way as dictionaries, in
`files/bigrams/` or `files/bigrams-ru/`, with the name
`<family>-<fileLanguageTag>-v<generation:6>-s3-f1-<rawSha256>.tatbigr`.

## Personal dictionary files: TATPERS, TATPERSB, TATPERSE

The keyboard writes these files on the device, one per language, in `noBackupFilesDir/personal/`
(credential-protected storage, excluded from backup). They have no zlib wrapper and no pins.
`<tag>` is the subtype identifier (`tt_RU`, `ru`). The schema and format version are part of the
file name, so a build never opens a file in an incompatible format.

| File | Magic | Contents | Max entries | Max file size |
|---|---|---|---:|---:|
| `personal-<tag>-s1-f1.tpers` | `TATPERS\0` | personal dictionary words | 2 000 | 131 072 B |
| `personal-bigrams-<tag>-s1-f1.tpersb` | `TATPERSB` | learned word pairs | 1 000 | 65 536 B |
| `personal-emoji-<tag>-s1-f1.tpersem` | `TATPERSE` | learned emoji | 500 | 65 536 B |

All three files use the same 72-byte header:

| Offset | Size | Field |
|---:|---:|---|
| 0–15 | 16 | common header: schemaId 1, formatVersion 1, headerSize 72, checksumAlgorithm 1 |
| 16 | 4 | entryCount (pairCount in `.tpersb`) |
| 20 | 4 | payloadSize = file length − 72 |
| 24 | 16 | subtypeTag: ASCII, NUL-padded |
| 40 | 32 | SHA-256 checksum |

Records (fixed part: 7 bytes in `.tpers`, 10 bytes in the other two):

| Size | `.tpers` | `.tpersb` | `.tpersem` |
|---:|---|---|---|
| 1 | wordByteLength (≥ 1) | contextByteLength (≥ 1) | wordByteLength (≥ 1) |
| 1 | — | successorByteLength (≥ 1) | emojiByteLength (≥ 1) |
| 2 | usageCount (≥ 1) | usageCount (accepted predictions) | usageCount (accepted suggestions) |
| 2 | — | frequencyCount (≥ 1, typed) | frequencyCount (≥ 1, co-used) |
| 4 | lastUseSerial | lastUseSerial | lastUseSerial |
| N | word, as entered | context word, normalized | word, normalized |
| M | — | successor, as typed | emoji cluster, as picked |

Counters are u16 and saturate. Learned pairs rank by usage, then frequency. Learned emoji rank
by usage, then frequency, then key.

**Validation** (`personal/Tpers*Validator.kt`):

- the file is between 72 bytes and the size cap; the header constants match; entryCount is at
  most the entry cap; subtypeTag is printable ASCII and equals the requested subtype;
  payloadSize equals file length − 72; the checksum matches;
- every word is strict UTF-8. A stored normalized form must already be NFC lowercase. A stored
  original form (the `.tpers` word or the `.tpersb` successor) may not have mixed casing;
- the normalized length is 3..24 code points in `.tpers` and 1..24 in the other two files, no
  combining mark remains after NFC, and every code point is in the language's alphabet
  (`PersonalSubtypes`);
- records strictly increase by key (the normalized word, the (context, successor) pair, or the
  (word, emoji) pair, compared as unsigned UTF-8 bytes), so there are no duplicates;
- a `.tpersem` emoji is at most 32 UTF-16 units and exactly one emoji cluster
  (`EmojiTextUtils.trailingEmojiClusterLength`); no bytes follow the last record.

Error messages are constants and never contain user text. A write goes to a `.tmp` file that is
then atomically renamed over the target. A file that fails validation is moved to the language's
single quarantine slot, `<file name>.quarantine`, and the store starts empty. The quarantined file
can later be restored or discarded. The same directory also holds the pending learning counters
(salted, truncated word hashes), their salt and the quarantine notice flags. Those files belong to
`personalstore/` and are not covered here.

## Refused-correction files: TATREF

`personal-refused-<tag>-s1-f1.tref` (magic `TATREF\0\0`) holds the corrections the user undid, one
file per language in the same `noBackupFilesDir/personal/` directory, with the same 72-byte header
(subtype tag included), at most 500 entries and 131 072 bytes. A record is `typedWordByteLength u8`,
`replacementByteLength u8`, `refusalCount u8` (≥ 1, capped at the persistence threshold), then both
words as UTF-8 in the normalized lookup form (NFC lowercase). A pair suppresses its correction once
its count reaches the threshold.

Unlike the other personal files the records are NOT sorted: the file order is the refusal order
(oldest first), because that order is the eviction order — past the cap the front of the file goes
first, and a repeated refusal moves its pair to the back. Validation (`personal/TrefValidator.kt`)
runs the same header, checksum, subtype-tag and strict-UTF-8 checks, rejects duplicates, self-pairs
and over-length words, but checks no alphabet (a refused typed word is off-dictionary by
construction) and no ordering. The store has no pending counters and no salt, and its quarantine is
silent: no screen lists the refused pairs.

## Plain-text assets

These assets are UTF-8 with LF line endings. Their readers are fail-closed: a malformed line is
skipped, and an unreadable file gives an empty table. They have no storage contract.

| Asset | Line format | Built by | Read by |
|---|---|---|---|
| `emoji/emoji_set_v1.txt` | `#<category>` header (ASCII name), then one sequence per line | `scripts/emoji_pack.py` | `emoji/EmojiSet.kt` |
| `emoji/emoji_skin_v1.txt` | `sequence<TAB>prefix<TAB>suffix`; toned = prefix + modifier + suffix | `scripts/emoji_skin_pack.py` | `emoji/EmojiSkinTones.kt` |
| `emoji/emoji_search_v1.txt` | `sequence<TAB>Russian name<TAB>keywords` (space-separated, ru/en/tt) | `scripts/emoji_search_pack.py` | `emoji/EmojiSearchIndex.kt` |
| `emoji/emoji_suggest_v1.txt` | `language<TAB>normalized word<TAB>emoji`, sorted by (language, word) | `scripts/emoji_suggest_pack.py` | `emoji/EmojiSuggestIndex.kt` |
| `dictionaries/*_sentstart_v1.txt` | `#` header, then `word<TAB>count`, count descending, then word | `scripts/sentstart_pack.py` | `suggestions/SentStartIndex.kt` |

The skin, search and suggest tables may only use sequences from `emoji_set_v1.txt`. Each
sentence-start word is in the bundled dictionary of its language. Sources and licenses are listed
in the `NOTICE.txt` in each asset directory.

## Where the pins live

| Asset | Pinned values | Location |
|---|---|---|
| dictionaries | compressed size and SHA-256, raw size and SHA-256, entry count | `DictionaryArtifactSpec.TATAR_TOP100K_V1` / `RUSSIAN_TOP100K_V1`, `storage/DictionaryStorageContracts.kt` |
| bigram tables | the same values, with head count instead of entry count, plus the linked dictionary's raw SHA-256 | `BigramArtifactSpec.TATAR_BIGRAMS_V1` / `RUSSIAN_BIGRAMS_V1`, `storage/BigramStorageContracts.kt` |
| plain-text assets | SHA-256 and line or record counts | `tests/<packer>/test_*.py` and the JVM asset and emoji tests |

`scripts/rebuild_assets.py` rewrites the storage-contract pins, and its `--check` mode compares
them with the assets. `scripts/release_check.sh` checks them again against the APK.
