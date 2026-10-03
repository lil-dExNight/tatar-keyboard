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
 * The frozen `.tcut` text-shortcut binary format: the user's own (shortcut → expansion) pairs.
 *
 * It is its own schema rather than `.tpers`: a shortcut pair carries no usage counters, no serials
 * and no subtype tag (one list serves every layout), so the records and the header differ.
 *
 * Header layout (little-endian, [HEADER_SIZE] = 72 bytes), the same checksum convention as
 * `TpersFormat` (SHA-256 over the whole file with the checksum field zeroed):
 *
 * | offset | size | field                                                         |
 * |-------:|-----:|---------------------------------------------------------------|
 * |      0 |    8 | [MAGIC] `TATCUT\0\0`                                            |
 * |      8 |    2 | schemaId u16 = [SCHEMA_ID]                                      |
 * |     10 |    2 | formatVersion u16 = [FORMAT_VERSION]                            |
 * |     12 |    2 | headerSize u16 = [HEADER_SIZE]                                  |
 * |     14 |    2 | checksumAlgorithm u16 = [CHECKSUM_ALGORITHM_SHA256]             |
 * |     16 |    4 | entryCount u32                                                  |
 * |     20 |    4 | payloadSize u32                                                 |
 * |     24 |   16 | reserved, zeroed                                                |
 * |     40 |   32 | SHA-256 over the whole file with this field zeroed              |
 *
 * The payload is [entryCount] records in STRICTLY ASCENDING order of the shortcut's UTF-8 bytes
 * (unsigned), with no duplicate shortcuts. Each record:
 *
 * | size | field                                          |
 * |-----:|------------------------------------------------|
 * |    1 | shortcutByteLength u8                          |
 * |    2 | expansionByteLength u16                        |
 * |    N | shortcut bytes, UTF-8, NFC                     |
 * |    M | expansion bytes, UTF-8, verbatim               |
 */
internal object TcutFormat {
    const val MAGIC = "TATCUT\u0000\u0000"

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
    const val RESERVED_OFFSET = 24
    const val RESERVED_SIZE = 16
    const val CHECKSUM_OFFSET = 40
    const val CHECKSUM_SIZE = 32

    /** Fixed record overhead: shortcutByteLength u8 + expansionByteLength u16. */
    const val RECORD_HEADER_SIZE = 3

    /** Cap on records; the reader rejects a file over it, the store refuses the add past it. */
    const val MAX_SHORTCUT_ENTRIES = 500L

    /** Cap on the whole file; the reader rejects a larger file. */
    const val MAX_FILE_SIZE = 65_536L

    /** Inclusive code-point length bounds for a shortcut. */
    const val MIN_SHORTCUT_CODE_POINTS = 1
    const val MAX_SHORTCUT_CODE_POINTS = 32

    /** Inclusive code-point length bounds for an expansion. */
    const val MIN_EXPANSION_CODE_POINTS = 1
    const val MAX_EXPANSION_CODE_POINTS = 140

    const val MAX_U16 = 0xffffL
    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name. The schema and format version are part of the name, so a reader never
     * opens a file from an incompatible build. No subtype tag: the list is shared by all layouts.
     */
    fun shortcutsFileName(): String = "shortcuts-s$SCHEMA_ID-f$FORMAT_VERSION.tcut"
}
