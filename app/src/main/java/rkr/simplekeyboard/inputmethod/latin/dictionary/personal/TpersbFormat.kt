/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

/**
 * The frozen `.tpersb` personal-bigram binary format (P1 of Phase 2, docs/ROADMAP-P2.md).
 *
 * A personal bigram is one observed word pair (context word, successor word) the user committed
 * cleanly at least the learn-threshold number of times. The format is a deliberate sibling of
 * `.tpers` ([TpersFormat]) rather than an extension of it: the words file is keyed by ONE word, the
 * bigrams file by an ORDERED PAIR, and a reader written for one must never even open the other —
 * which is why the magic, the extension and the file name all differ while the header layout, the
 * checksum convention (SHA-256 over the whole file with the checksum field zeroed) and the
 * subtype tag stay identical.
 *
 * Header layout (little-endian, [HEADER_SIZE] = 72 bytes):
 *
 * | offset | size | field                                                         |
 * |-------:|-----:|---------------------------------------------------------------|
 * |      0 |    8 | [MAGIC] `TATPERSB`                                            |
 * |      8 |    2 | schemaId u16 = [SCHEMA_ID]                                     |
 * |     10 |    2 | formatVersion u16 = [FORMAT_VERSION]                          |
 * |     12 |    2 | headerSize u16 = [HEADER_SIZE]                                 |
 * |     14 |    2 | checksumAlgorithm u16 = [CHECKSUM_ALGORITHM_SHA256]            |
 * |     16 |    4 | pairCount u32                                                 |
 * |     20 |    4 | payloadSize u32                                               |
 * |     24 |   16 | subtypeTag: ASCII, NUL-padded ([SUBTYPE_TAG_SIZE] bytes)      |
 * |     40 |   32 | SHA-256 over the whole file with this field zeroed            |
 *
 * The payload is [pairCount] records in STRICTLY ASCENDING order of the pair key — first the
 * NORMALIZED (NFC lowercase) context form, then the NORMALIZED successor form, both compared as
 * unsigned UTF-8 bytes — with no duplicates by that same pair key. Each record:
 *
 * | size | field                                                              |
 * |-----:|----------------------------------------------------------------------|
 * |    1 | contextByteLength u8                                                 |
 * |    1 | successorByteLength u8                                               |
 * |    2 | usageCount u16 (>= 0; accepted-prediction taps)                     |
 * |    2 | frequencyCount u16 (>= 1; clean typed observations)                 |
 * |    4 | lastUseSerial u32                                                   |
 * |    N | context bytes, UTF-8, stored in the NORMALIZED form                 |
 * |    M | successor bytes, UTF-8, stored in the ORIGINAL (as-typed) form      |
 *
 * The context is stored in its normalized form only: it is a lookup key, never a displayed
 * string — a prediction is looked up by the normalized form of the committed word, so keeping the
 * user's casing of it would cost bytes and buy nothing. The successor keeps the raw form for the
 * same reason the words store keeps it: the cell shows what the user actually wrote («Гүзәл» with
 * its capital), while sorting, dedup and the duplicate rule against the static table all run on
 * the normalized form.
 *
 * The two counters are the two halves of the pinned ranking (usage descending, then frequency
 * descending): [usageCount] grows only when the user ACCEPTS the pair's suggestion from the band,
 * [frequencyCount] only when the pair is typed out cleanly again. Both are u16 and saturate.
 */
internal object TpersbFormat {
    const val MAGIC = "TATPERSB"

    const val SCHEMA_ID = 1
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 72
    const val CHECKSUM_ALGORITHM_SHA256 = 1

    const val MAGIC_SIZE = 8
    const val SCHEMA_ID_OFFSET = 8
    const val FORMAT_VERSION_OFFSET = 10
    const val HEADER_SIZE_OFFSET = 12
    const val CHECKSUM_ALGORITHM_OFFSET = 14
    const val PAIR_COUNT_OFFSET = 16
    const val PAYLOAD_SIZE_OFFSET = 20
    const val SUBTYPE_TAG_OFFSET = 24
    const val SUBTYPE_TAG_SIZE = 16
    const val CHECKSUM_OFFSET = 40
    const val CHECKSUM_SIZE = 32

    /**
     * Fixed record overhead: contextByteLength u8 + successorByteLength u8 + usageCount u16 +
     * frequencyCount u16 + lastUseSerial u32.
     */
    const val RECORD_HEADER_SIZE = 10

    /** Cap on pairs per subtype (P1 limit; enforced fail-closed by the reader from day one). */
    const val MAX_PERSONAL_BIGRAM_PAIRS = 1_000L

    /** Cap on the whole file, 64 KiB (P1 limit; enforced by the reader from day one). */
    const val MAX_FILE_SIZE = 65_536L

    /**
     * Inclusive code-point length bounds for the normalized form of EACH word of a pair. Unlike
     * the words store (3..24) the floor is 1: a one-letter context or successor is a legitimate
     * pair member («а» as a conjunction is an ordinary Tatar word), and the dictionary-membership
     * gate on the context — not a length floor — is what keeps junk out.
     */
    const val MIN_WORD_CODE_POINTS = 1
    const val MAX_WORD_CODE_POINTS = 24

    const val MAX_U16 = 0xffffL
    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name for a subtype, e.g. `personal-bigrams-tt_RU-s1-f1.tpersb`. A sibling
     * of the words file in the same `personal/` directory; the schema and format version are woven
     * into the name so a file written by an incompatible build is never even opened for the wrong
     * reader.
     */
    fun personalBigramsFileName(subtypeTag: String): String =
        "personal-bigrams-$subtypeTag-s$SCHEMA_ID-f$FORMAT_VERSION.tpersb"
}
