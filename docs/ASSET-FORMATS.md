# Asset and store formats

This reference covers the binary files the keyboard reads: the bundled dictionaries (TATDICT
schema 2), the bundled bigram and combined bigram/trigram tables (TATBIGR schemas 3 and 4), the
Tatar stem lexicon (TATSTEM schema 1), and the personal-dictionary files (TATPERS, TATPERSB,
TATPERSE, TATREF). The plain-text assets are
summarized at the end. For how
the bundled files are built, see [ASSET-PIPELINE.md](ASSET-PIPELINE.md).

The code is the authority. The format constants are in `TdictFormat`
(`DictionaryStorageContracts.kt`), `TatBigrFormat` (`BigramStorageContracts.kt`), `TatStemFormat`
(`StemLexiconStorageContracts.kt`), and `TpersFormat`, `TpersbFormat`, `TpersemFormat`,
`TrefFormat` (`dictionary/personal/`). All code
paths below are relative to `app/src/main/java/rkr/simplekeyboard/inputmethod/latin/`.

## Common conventions

- All integers are unsigned little-endian. `u8`/`u16`/`u32` are fixed width.
- A varint is an unsigned base-128 integer (low 7 bits first, high bit set on every byte except
  the last) that fits in u32 unless the format explicitly says u64. Readers accept only the
  minimal encoding: an overlong varint, or a fifth byte above `0x0f` for u32, is an error.
- Words are UTF-8, NFC, lowercase (`Locale.ROOT`). Word order is unsigned byte order of the UTF-8
  encoding, which is the same as code-point order. A stored word may contain U+002D only
  between two alphabet letters; U+2010, U+2013, U+2014 and apostrophes are never part of a word.
- The checksum field holds the SHA-256 of the whole file, computed with the field itself set to
  zero. `checksumAlgorithm = 1` means SHA-256, and no other value is accepted.
- Every header begins with the same 16 bytes: magic (8 bytes of ASCII) at offset 0, then the
  u16 fields schemaId at 8, formatVersion at 10, headerSize at 12 and checksumAlgorithm at 14.
- A validator rejects a file at the first violation, and nothing repairs data. If a bundled file
  fails validation, its consumer does not receive an image. A personal file that fails validation
  is quarantined (see below).

## TATDICT schema 2: bundled dictionaries

Assets: `assets/dictionaries/{tatar,russian}_top100k_v1.tdict.zlib`. The `top100k` in the name
is frozen for compatibility; the real entry count is pinned in `expectedEntryCount`. Constants:
`SCHEMA_ID = 2`, `FORMAT_VERSION = 1`, `HEADER_SIZE = 72`, `BLOCK_SIZE = 8`,
`MAX_WORD_BYTES = 128`, and `MAX_RAW_SIZE`. `MAX_RAW_SIZE` bounds the inflated file and the
in-memory validation buffer, to keep the reader from allocating without limit; it is not a
budget the packer aims for. Inflation is bounded by the pinned compressed size (see
Validation below), so the compressed size needs no separate cap.

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
no preset dictionary and no trailing or concatenated data. It stops as soon as the input exceeds
the pinned compressed size or the output exceeds `MAX_RAW_SIZE`. The compressed size, compressed
SHA-256 and raw size must equal the pins. The full validation tier then requires:

- the file length equals the pinned raw size, and the header constants equal the spec;
- entryCount is non-zero and equals the pin, and every section field has the value in the
  table above (overflow-checked, within u32);
- the checksum matches, and the SHA-256 of the whole file equals the pinned raw SHA-256.

The pinned-content tier, `verifyPinnedContent`, checks the same bounds, header constants and
section arithmetic and then verifies the pinned raw SHA-256 in one streaming pass; the pinned
match authenticates the embedded checksum, so that field is not re-verified there.

The full tier, `validateRaw`, additionally walks the file: the first block offset equals
blocksOffset, block offsets strictly increase within the file; `firstLength` is 1..128,
`prefixLength` ≤ `firstLength`, `suffixLength` ≥ 1, prefix + suffix ≤ 128 bytes, and every
varint is minimal; every word is strict UTF-8, 1..64 code points, NFC lowercase, in the Tatar
alphabet (a superset of the Russian alphabet, so the Russian file passes the same check), and its
only non-letters are U+002D characters that each sit between two alphabet letters;
words strictly increase, so there are no duplicates, and every frequency is > 0; each block ends
exactly where the next one starts, and the last block ends at end of file. A file whose size and
SHA-256 match the pinned artifact is exactly the file the full walk accepted at publication, so
the walk adds value only on never-before-seen bytes.

The runtime reader (`engine/TdictPrefixIndex.kt`, `open`) checks the header and the section
arithmetic again on the memory-mapped file, and walks every entry unless the store hands it a
validation token for the same content (`storage/StoreValidationToken.kt`). The token proves either
full structural validation or pinned-content verification of the same raw content in this process.
The reader always rechecks the header and section arithmetic; with a matching token, it skips the
structural walk.

**On the device.** The raw file is inflated into a temp file, fsynced, validated in full, and
atomically renamed into device-protected storage under `files/dictionaries/` (Tatar) or
`files/dictionaries-ru/` (Russian). A process start that finds the final file already present
re-verifies it by the pinned-content tier; any mismatch deletes the file and republishes, which
validates fresh bytes in full. The final name is
`<family>-v<generation:6>-s<schemaId>-f<formatVersion>-<rawSha256>.tdict`, so a new schema or new
content produces a new name. After an update the asset is inflated once, and retention deletes
the old file after its last lease is closed. There is no migration code.

## TATBIGR schema 3: bundled bigram tables

Asset: `assets/bigrams/russian_bigrams_v1.tatbigr.zlib`. The Tatar schema-4 table below extends
this layout. Constants: `SCHEMA_ID = 3`, `FORMAT_VERSION = 1`, `HEADER_SIZE = 128`,
`CHECKSUM_OFFSET = 96`, `HEAD_BLOCK_SIZE = 64`.

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
with the input bounded by `TatBigrFormat.MAX_COMPRESSED_SIZE` and the output by
`TatBigrFormat.MAX_RAW_SIZE`. The full validation tier requires:

- the header constants, headCount (non-zero, equal to the pin) and the linked dictionary SHA-256
  equal the spec, and the reserved bytes are zero;
- every section offset has its required value, and fileSize equals the file length;
- the checksum matches, and the SHA-256 of the whole file equals the pinned raw SHA-256.

The pinned-content tier, `verifyPinnedContent`, checks the same header, dictionary-link and
section arithmetic and verifies the pinned raw SHA-256 in one streaming pass; the pinned match
authenticates the embedded checksum, so that field is not re-verified there.

The full tier, `validateRaw`, additionally walks the streams: block `firstDictIndex` values
strictly increase; stream offsets never decrease and stay inside their sections; the first
block's offsets are 0; head indices strictly increase, every varint is minimal, and every count
is ≥ 1; each delta stream ends exactly where the next block's stream starts, each success stream
starts at its record's offset, the counts sum to pairCount, and the success stream ends exactly
at the end of its section. See the TATDICT section for when the store uses which tier.

The validator does not have the dictionary, so it cannot check upper bounds on indices.
`engine/TatBigrPrefixIndex.kt` (`open`) checks the header again, requires the linked SHA-256 to
equal the raw SHA-256 of the dictionary that is actually loaded, and requires every head and
success index to be below that dictionary's entryCount. A store validation token for the same
content (see the TATDICT section above) skips that stream walk; the header and
dictionary-link checks always run. If the table does not match the dictionary, the language runs
without it: next-word prediction has no table successors and answers from learned word pairs and
the most frequent dictionary words only, word completion loses the previous-word boost, and
typing is not affected. On the device, tables are staged, named and retained the same way as
dictionaries, including the pinned-content re-verification of an already published file, in
`files/bigrams/` or `files/bigrams-ru/`, with the name
`<family>-<fileLanguageTag>-v<generation:6>-s3-f1-<rawSha256>.tatbigr`.

## TATBIGR schema 4: bundled Tatar bigram and trigram table

Asset: `assets/bigrams/tatar_bigrams_v2.tatbigr.zlib`. Constants: `SCHEMA_ID_V4 = 4`,
`FORMAT_VERSION = 1`, `HEADER_SIZE_V4 = 224`, `TRIGRAM_BLOCK_SIZE = 64`. The checksum and linked
dictionary SHA-256 keep their schema-3 offsets. The one-word sections keep their schema-3 layout,
shifted from offset 128 to 224; the schema-3 `fileSize` field covers the whole combined file.

The header extension starts at offset 128:

| Offset | Size | Field |
|---:|---:|---|
| 128 / 132 / 136 | 4 each | trigram contextCount / pairCount / blockCount |
| 140 / 144 | 4 each | trigram blockIndexOffset / deltasOffset |
| 148 | 4 | trigram deltasSize |
| 152 / 156 | 4 each | trigram countsOffset / successIdsOffset |
| 160 | 4 | trigram successIdsSize |
| 164 | 4 | linked dictionary word count |
| 168 / 172 | 4 each | interpolation beta in millionths / minimum context total |
| 176 | 48 | reserved, zero |

Four trigram sections follow the one-word success ids, with no padding:

1. Context block index: `blockCount` records of 16 bytes,
   `{u32 firstDictIndexA, u32 firstDictIndexB, u32 contextDeltaOffset, u32 successOffset}`.
   A context key is `dictIndexA * dictionaryWordCount + dictIndexB`; block records are ordered by
   that key, and the two stream offsets are relative to their sections.
2. Context deltas: for each block, (blockContexts − 1) minimal u64 varint deltas, each ≥ 1. The
   first context of a block comes from the two dictionary indices in its record.
3. Success counts: `contextCount` × u8, each in 1..8.
4. Success ids: `pairCount` minimal u32 varint dictionary indices, grouped by context in the
   packer's interpolated ranking order.

The full validator and runtime opener check both the one-word and two-word section arithmetic,
block ordering, minimal varints, counts and dictionary bounds. A two-word lookup resolves A and B
through the linked dictionary, binary-searches the context blocks and decodes at most one block.
It returns the stored successors without re-ranking. An empty or unsupported two-word answer
falls back to the one-word chain. The published file name uses `s4-f1` and the new generation and
raw SHA-256, so devices retain no migration code.

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
  combining mark remains after NFC, and every code point is in the language's alphabet or is a
  U+002D between two alphabet letters;
  (`PersonalSubtypes`);
- records strictly increase by key (the normalized word, the (context, successor) pair, or the
  (word, emoji) pair, compared as unsigned UTF-8 bytes), so there are no duplicates;
- a `.tpersem` emoji is at most 32 UTF-16 units and exactly one emoji cluster
  (`EmojiTextUtils.trailingEmojiClusterLength`); no bytes follow the last record.

Error messages are constants and never contain user text. A write goes to a `.tmp` file that is
then atomically renamed over the target. A file that fails validation is moved to the language's
single quarantine slot, `<file name>.quarantine`, and the store starts empty. The quarantined file
can later be restored or discarded. A file that cannot be read at all (an I/O error, as opposed
to content that fails validation) stays where it is and is never overwritten; the store is
not loaded until a later open reads it. The same directory also holds the pending learning
counters (salted, truncated word hashes), their salt and the quarantine notice flags. Those
files belong to `personalstore/` and are not covered here.

## Refused-correction files: TATREF

`personal-refused-<tag>-s1-f1.tref` (magic `TATREF\0\0`) holds the corrections the user undid, one
file per language in the same `noBackupFilesDir/personal/` directory, with the same 72-byte header
(subtype tag included), at most 500 entries and 131 072 bytes. A record is `typedWordByteLength u8`,
`replacementByteLength u8`, `refusalCount u8` (≥ 1, capped at the persistence threshold), then both
words as UTF-8 in the normalized lookup form (NFC lowercase). A pair suppresses its correction once
its count reaches the threshold.

Unlike the other personal files, the records are not sorted: the file order is the refusal order
(oldest first), because that order is the eviction order; past the cap the front of the file goes
first, and a repeated refusal moves its pair to the back. Validation (`personal/TrefValidator.kt`)
runs the same header, checksum, subtype-tag and strict-UTF-8 checks, rejects duplicates, self-pairs
and over-length words, but checks no alphabet (a refused typed word is off-dictionary by
construction) and no ordering. The store has no pending counters and no salt, and its quarantine is
silent: no screen lists the refused pairs.

## Sentence-initial overlay files: TATSENT

`sentstart-<tag>-s1-f1.tsent` (magic `TATSENT\0`) holds the few words the user starts
sentences with, one file per language in the same `noBackupFilesDir/personal/` directory, with the
same 72-byte header (subtype tag included) and the same record layout as `.tpers`
(`wordByteLength u8`, `usageCount u16`, `lastUseSerial u32`, the word as entered). The cap is 8
entries and 4 096 bytes, and the normalized length bounds are 1..24 code points; sentence starts
admit one-letter words («я» opens Russian sentences). Records are sorted by the normalized form;
the strip order is by usage count, then last-use serial.

Validation (`personal/TsentValidator.kt`) is the `.tpers` one with these bounds. Learning follows
the personal dictionary's pending-counter discipline (`PendingCounters`): a word enters
plaintext only after the shared learn threshold of sentence-initial commits, and the pending hashes
and salt live in the store's own `pending-sentstart-<tag>-s1-f1.bin` and `salt-sentstart-<tag>-s1-f1.bin`. A
file that fails validation is moved to `<file name>.quarantine` with no notice: no screen lists
the overlay, and the overlay relearns. The files do not classify into a backup kind, so an export
never carries them.

## TATCOMP schema 1: cold-companion word lists

Assets: `assets/dictionaries/{tatar,russian}_companion_v1.tcomp.zlib`, one per shipped language.
The list is the top-frequency words of that language's bundled dictionary — the exact list, not a
probabilistic filter, because a false positive would suppress a legitimate correction. The
autocorrect companion guard consults it while the other language's engine is cold (see
`SuggestionsController.companionLexiconFor`); it is never read on the suggestion path.
Constants: `TatCompFormat` in `storage/CompanionListStorageContracts.kt`.

The file is a 56-byte header followed by `wordCount` word rows, then the whole file is
zlib-compressed:

| Offset | Size | Field | Required value |
|---:|---:|---|---|
| 0 | 8 | magic `TATCOMP\0` | |
| 8 / 10 / 12 / 14 | 2 each | schemaId / formatVersion / headerSize / checksumAlgorithm | 1 / 1 / 56 / 1 |
| 16 | 4 | wordCount | the pinned `expectedWordCount` |
| 20 | 4 | bodySize | `HEADER_SIZE + bodySize` = file length |
| 24 | 32 | SHA-256 checksum | of the whole file with this field zeroed |

Every word row is a u8 byte length followed by the UTF-8 bytes of an NFC lowercase word. The rows
are strictly ascending in unsigned UTF-8 byte order, so a membership probe is a binary search.

Validation (`engine/CompanionListReader.kt`) follows the same fail-closed rule as the dictionary
validator: every header field is checked against the contract, compressed size and SHA-256 and raw
size and SHA-256 are pinned, the embedded checksum must verify, and row lengths, body bounds and
strict unsigned-byte ordering are walked. Any violation throws. A rejected list protects nothing —
an unlisted word stays correctable, exactly as before the list existed. Inflation is bounded by the
pinned raw size, so a corrupt stream cannot allocate without limit.

The runtime walk deliberately does not decode every stored word. UTF-8, NFC, alphabet and lowercase
canonicality are established at generation time: `scripts/companion_list_pack.py` selects words
from a fully validated TATDICT, and the compressed and raw content pins bind the runtime to that
exact output. Structure and a self-consistent checksum alone do not establish canonicality, and an
unpinned or pin-mismatched malformed list is not a supported word source.

The list is read whole into memory once per process (it is the top of a frequency list, not the
dictionary), never copied into device-protected storage. Strings are decoded only if the `words`
view is read; membership probes compare bytes.

## TATSTEM schema 1: incremental Tatar stem lexicon

Asset: `assets/dictionaries/tatar_stems_v1.tstem.zlib`. The lexicon contains stems from
Apertium-tat, unambiguous bundled-dictionary word-form groups and the word-form review table.
Every stem already shipped as a dictionary form is excluded. Bare-stem acceptance and suffix
chains are separate: only Apertium-attested stems carry continuation flags. Constants:
`BLOCK_SIZE = 8`, `MAX_STEM_BYTES = 128`, `MAX_STEM_CODE_POINTS = 64`, `MAX_STEMS`, `MAX_RAW_SIZE`
and `MAX_COMPRESSED_SIZE` in `TatStemFormat`.

The 136-byte header uses the common conventions:

| Offset | Size | Field | Required value |
|---:|---:|---|---|
| 0 | 8 | magic `TATSTEM\0` | |
| 8 / 10 / 12 / 14 | 2 each | schemaId / formatVersion / headerSize / checksumAlgorithm | 1 / 1 / 136 / 1 |
| 16 | 4 | stemCount | the pinned `expectedStemCount` |
| 20 | 4 | blockCount | ⌈stemCount / 8⌉ |
| 24 | 4 | blockIndexOffset | 136 |
| 28 | 4 | blocksOffset | 136 + 4 · blockCount |
| 32 | 4 | blocksSize | |
| 36 | 4 | fileSize | blocksOffset + blocksSize = file length |
| 40 | 32 | dictionaryRawSha256 | raw SHA-256 of the linked Tatar dictionary |
| 72 | 32 | apertiumLexiconSha256 | SHA-256 of the pinned Apertium-tat lexc source |
| 104 | 32 | checksum | SHA-256 with this field set to zero |

Two sections follow without padding:

1. Block index: `blockCount` × u32 absolute block offsets. The first equals blocksOffset; later
   offsets strictly increase.
2. Front-coded blocks of at most eight entries. The first entry is `u8 firstLength`,
   `firstWord[firstLength]`, `u8 flags`. Each later entry is `varint prefixLength`,
   `u8 suffixLength`, `suffix[suffixLength]`, `u8 flags`. The prefix shares bytes with the
   block's first word; suffixLength is nonzero, and prefix + suffix is at most 128 bytes.

Words strictly increase in unsigned UTF-8 byte order. They are strict UTF-8, NFC lowercase,
2..64 code points, in the Tatar alphabet, with U+002D only between letters, and have a harmony
vowel. The flags byte is:

| Bit | Value | Meaning |
|---:|---:|---|
| 0 | 1 | noun category |
| 1 | 2 | verb category |
| 2 | 4 | front harmony |
| 3 | 8 | back harmony |
| 4 | 16 | noun suffix chains attested by Apertium |
| 5 | 32 | verb suffix chains attested by Apertium |
| 6 | 64 | Apertium `-RUS` forces back harmony |
| 7 | 128 | reserved, zero |

At least one category and exactly one harmony bit are required. A chain bit requires its matching
category. Without forced back harmony, the harmony bit equals the generator's last-syllable rule;
with it, back harmony and at least one chain bit are required.

Validation (`storage/TatStemValidator.kt`) inflates exactly one zlib stream under the compressed
and raw bounds, then checks all header fields, both source identities, the embedded checksum and
the compressed/raw pins. The full walk checks block boundaries, minimal varints, front-coded
lengths, strict word shape and ordering, flags and exact section closure. Any violation throws
`StemLexiconValidationException`. The validated image retains only the raw byte array and section
metadata; no decoded-stem object trie is built. It is not published to a device file.

## Plain-text assets

These assets are UTF-8 with LF line endings. Their readers are fail-closed: a malformed line is
skipped, and an unreadable file gives an empty table. They have no storage contract.

| Asset | Line format | Built by | Read by |
|---|---|---|---|
| `emoji/emoji_set_v1.txt` | `#<category>` header (ASCII name), then one sequence per line | `scripts/emoji_pack.py` | `emoji/EmojiSet.kt` |
| `emoji/emoji_skin_v1.txt` | `sequence<TAB>prefix<TAB>suffix`; toned = prefix + modifier + suffix | `scripts/emoji_skin_pack.py` | `emoji/EmojiSkinTones.kt` |
| `emoji/emoji_search_v1.txt` | `sequence<TAB>Russian name<TAB>keywords (space-separated, ru/en/tt)<TAB>Tatar name` | `scripts/emoji_search_pack.py` | `emoji/EmojiSearchIndex.kt` |
| `emoji/emoji_suggest_v1.txt` | `language<TAB>normalized word<TAB>emoji`, sorted by (language, word) | `scripts/emoji_suggest_pack.py` | `emoji/EmojiSuggestIndex.kt` |
| `dictionaries/*_sentstart_v1.txt` | `#` header, then `word<TAB>count`, count descending, then word | `scripts/sentstart_pack.py` | `suggestions/SentStartIndex.kt` |
| `dictionaries/tatar_proper_names_v1.txt` | `#` header, then `key<TAB>display` sorted by key; the key is the NFC lowercase dictionary word, the display lowers to it | `scripts/proper_name_pack.py` | `suggestions/ProperNameIndex.kt` |

The skin, search and suggest tables may only use sequences from `emoji_set_v1.txt`. Each
sentence-start word is in the bundled dictionary of its language. Sources and licenses are listed
in the `NOTICE.txt` in each asset directory.

## Where the pins live

| Asset | Pinned values | Location |
|---|---|---|
| dictionaries | compressed size and SHA-256, raw size and SHA-256, entry count | `DictionaryArtifactSpec.TATAR_TOP100K_V1` / `RUSSIAN_TOP100K_V1`, `storage/DictionaryStorageContracts.kt` |
| bigram tables | the same values, with head count instead of entry count, plus the linked dictionary's raw SHA-256 | `BigramArtifactSpec.TATAR_BIGRAMS_V2` / `RUSSIAN_BIGRAMS_V1`, `storage/BigramStorageContracts.kt` |
| cold-companion lists | compressed size and SHA-256, raw size and SHA-256, word count | `CompanionListArtifactSpec.TATAR_COMPANION_V1` / `RUSSIAN_COMPANION_V1`, `storage/CompanionListStorageContracts.kt` |
| stem lexicon | the same values, stem count, linked dictionary raw SHA-256 and Apertium lexc SHA-256 | `StemLexiconArtifactSpec.TATAR_STEMS_V1`, `storage/StemLexiconStorageContracts.kt` |
| plain-text assets | SHA-256 and line or record counts | `tests/<packer>/test_*.py` and the JVM asset and emoji tests |

`scripts/rebuild_assets.py` rewrites the storage-contract pins, and its `--check` mode compares
them with the assets. `scripts/release_check.sh` checks them again against the APK.
