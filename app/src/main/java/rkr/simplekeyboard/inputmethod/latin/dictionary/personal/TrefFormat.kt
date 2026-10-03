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
 * The frozen `.tref` refused-corrections binary format: the (typed word → replacement) pairs whose
 * correction the user undid, per language. A correction whose pair is recorded here enough times
 * (see [RefusedCorrections.REFUSAL_THRESHOLD]) never fires again.
 *
 * It is its own schema rather than `.tpers`: a record carries no usage counters and no serials,
 * and the refusal count is a single byte. The header layout is the `.tpers` one (little-endian,
 * [HEADER_SIZE] = 72 bytes, the subtype tag at [SUBTYPE_TAG_OFFSET], SHA-256 over the whole file
 * with the checksum field zeroed):
 *
 * | offset | size | field                                                         |
 * |-------:|-----:|---------------------------------------------------------------|
 * |      0 |    8 | [MAGIC] `TATREF\0\0`                                            |
 * |      8 |    2 | schemaId u16 = [SCHEMA_ID]                                      |
 * |     10 |    2 | formatVersion u16 = [FORMAT_VERSION]                            |
 * |     12 |    2 | headerSize u16 = [HEADER_SIZE]                                  |
 * |     14 |    2 | checksumAlgorithm u16 = [CHECKSUM_ALGORITHM_SHA256]             |
 * |     16 |    4 | entryCount u32                                                  |
 * |     20 |    4 | payloadSize u32                                                 |
 * |     24 |   16 | subtypeTag: ASCII, NUL-padded ([SUBTYPE_TAG_SIZE] bytes)        |
 * |     40 |   32 | SHA-256 over the whole file with this field zeroed              |
 *
 * The payload is [entryCount] records in REFUSAL order (the oldest refusal first), with no
 * duplicate pairs. Unlike the other personal files the records are deliberately NOT sorted: the
 * order is the eviction order — past [MAX_REFUSED_ENTRIES] the front of the file goes first, and a
 * repeated refusal moves its pair to the back. Each record:
 *
 * | size | field                                                       |
 * |-----:|-------------------------------------------------------------|
 * |    1 | typedWordByteLength u8                                      |
 * |    1 | replacementByteLength u8                                    |
 * |    1 | refusalCount u8 (>= 1, capped at [RefusedCorrections.REFUSAL_THRESHOLD]) |
 * |    N | typed word bytes, UTF-8, NFC lowercase                       |
 * |    M | replacement bytes, UTF-8, NFC lowercase                      |
 */
internal object TrefFormat {
    const val MAGIC = "TATREF\u0000\u0000"

    const val SCHEMA_ID = 1
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 72
    const val CHECKSUM_ALGORITHM_SHA256 = 1

    const val MAGIC_SIZE = 8
    const val SCHEMA_ID_OFFSET = 8
    const val FORMAT_VERSION_OFFSET = 10
    const val HEADER_SIZE_OFFSET = 12
    const val CHECKSUM_ALGORITHM_OFFSET = 14
    const val ENTRY_COUNT_OFFSET = 16
    const val PAYLOAD_SIZE_OFFSET = 20
    const val SUBTYPE_TAG_OFFSET = 24
    const val SUBTYPE_TAG_SIZE = 16
    const val CHECKSUM_OFFSET = 40
    const val CHECKSUM_SIZE = 32

    /** Fixed record overhead: typedWordByteLength u8 + replacementByteLength u8 + refusalCount u8. */
    const val RECORD_HEADER_SIZE = 3

    /** Cap on records per subtype; the reader rejects a file over it, the store evicts past it. */
    const val MAX_REFUSED_ENTRIES = 500L

    /** Cap on the whole file; the reader rejects a larger file. */
    const val MAX_FILE_SIZE = 131_072L

    /** Inclusive code-point length bounds for both words of a record. */
    const val MIN_WORD_CODE_POINTS = 1
    const val MAX_WORD_CODE_POINTS = 24

    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name for a subtype, e.g. `personal-refused-tt_RU-s1-f1.tref`. The schema and
     * format version are part of the name, so a reader never opens a file from an incompatible build.
     */
    fun refusedCorrectionsFileName(subtypeTag: String): String =
        "personal-refused-$subtypeTag-s$SCHEMA_ID-f$FORMAT_VERSION.tref"
}
