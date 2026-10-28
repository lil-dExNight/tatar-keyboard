/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
 * The frozen `.tsent` sentence-initial overlay binary format: the few words the user actually
 * starts sentences with, one file per language.
 *
 * It is its own schema rather than `.tpers`: the overlay caps at a handful of entries and admits
 * one-code-point words («я» opens Russian sentences), both outside the `.tpers` bounds. The header
 * layout (little-endian, [HEADER_SIZE] = 72 bytes) and the checksum convention are the personal
 * stores' shared one (SHA-256 over the whole file with the checksum field zeroed):
 *
 * | offset | size | field                                                         |
 * |-------:|-----:|---------------------------------------------------------------|
 * |      0 |    8 | [MAGIC] `TATSENT\0`                                           |
 * |      8 |    2 | schemaId u16 = [SCHEMA_ID]                                    |
 * |     10 |    2 | formatVersion u16 = [FORMAT_VERSION]                          |
 * |     12 |    2 | headerSize u16 = [HEADER_SIZE]                                |
 * |     14 |    2 | checksumAlgorithm u16 = [CHECKSUM_ALGORITHM_SHA256]           |
 * |     16 |    4 | entryCount u32                                                |
 * |     20 |    4 | payloadSize u32                                               |
 * |     24 |   16 | subtypeTag: ASCII, NUL-padded ([SUBTYPE_TAG_SIZE] bytes)      |
 * |     40 |   32 | SHA-256 over the whole file with this field zeroed            |
 *
 * The payload is [entryCount] records in STRICTLY ASCENDING order of the NORMALIZED (NFC
 * lowercase) form of the word, with no duplicates by that same normalized form. Each record:
 *
 * | size | field                                          |
 * |-----:|------------------------------------------------|
 * |    1 | wordByteLength u8                              |
 * |    2 | usageCount u16 (>= 1)                          |
 * |    4 | lastUseSerial u32                              |
 * |    N | word bytes, UTF-8, stored in its ORIGINAL form |
 *
 * The strip order is not the record order: records are sorted by the normalized form, while the
 * strip shows the words by usage (count descending, then last-use serial descending).
 */
internal object TsentFormat {
    const val MAGIC = "TATSENT\u0000"

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

    /** Fixed record overhead: wordByteLength u8 + usageCount u16 + lastUseSerial u32. */
    const val RECORD_HEADER_SIZE = 7

    /** Cap on records; the reader rejects a file over it, the store evicts past it. */
    const val MAX_SENTSTART_ENTRIES = 8L

    /** Cap on the whole file; the reader rejects a larger file. */
    const val MAX_FILE_SIZE = 4_096L

    /** Inclusive code-point length bounds for the normalized form of an overlay word. */
    const val MIN_WORD_CODE_POINTS = 1
    const val MAX_WORD_CODE_POINTS = 24

    const val MAX_U16 = 0xffffL
    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name for a subtype, e.g. `sentstart-tt_RU-s1-f1.tsent`. The schema and
     * format version are part of the name, so a reader never opens a file from an incompatible
     * build.
     */
    fun sentStartFileName(subtypeTag: String): String =
        "sentstart-$subtypeTag-s$SCHEMA_ID-f$FORMAT_VERSION.tsent"
}
