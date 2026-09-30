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
 * The frozen `.tpersem` learned-emoji binary format: one (word, emoji cluster) pair per record,
 * the word the user committed and the emoji they keep inserting after it («хәйерле иртә» → ☀️). A
 * sibling of [TpersbFormat]: the magic, extension and file name differ so a reader never opens the other
 * kind, while the header layout, checksum convention and subtype tag are the same.
 *
 * Header layout (little-endian, [HEADER_SIZE] = 72 bytes):
 *
 * | offset | size | field                                                         |
 * |-------:|-----:|---------------------------------------------------------------|
 * |      0 |    8 | [MAGIC] `TATPERSE`                                            |
 * |      8 |    2 | schemaId u16 = [SCHEMA_ID]                                     |
 * |     10 |    2 | formatVersion u16 = [FORMAT_VERSION]                          |
 * |     12 |    2 | headerSize u16 = [HEADER_SIZE]                                 |
 * |     14 |    2 | checksumAlgorithm u16 = [CHECKSUM_ALGORITHM_SHA256]            |
 * |     16 |    4 | entryCount u32                                                |
 * |     20 |    4 | payloadSize u32                                               |
 * |     24 |   16 | subtypeTag: ASCII, NUL-padded ([SUBTYPE_TAG_SIZE] bytes)      |
 * |     40 |   32 | SHA-256 over the whole file with this field zeroed            |
 *
 * The payload is [entryCount] records in STRICTLY ASCENDING order of the entry key — first the
 * NORMALIZED (NFC lowercase) word, then the emoji cluster, both compared as unsigned UTF-8
 * bytes — with no duplicates by that same key. Each record:
 *
 * | size | field                                                              |
 * |-----:|----------------------------------------------------------------------|
 * |    1 | wordByteLength u8                                                    |
 * |    1 | emojiByteLength u8                                                   |
 * |    2 | usageCount u16 (>= 0; accepted-suggestion taps)                     |
 * |    2 | frequencyCount u16 (>= 1; clean co-usage observations)              |
 * |    4 | lastUseSerial u32                                                   |
 * |    N | word bytes, UTF-8, stored in the NORMALIZED form                    |
 * |    M | emoji bytes, UTF-8, stored as the RAW cluster (its only form)       |
 *
 * The word is stored normalized only, like the pair context: it is a lookup key and the settings
 * list shows it that way. The emoji cluster is stored exactly as it was picked.
 *
 * Ranking is usage descending, frequency descending, then key ascending. usageCount grows when the
 * user accepts the learned emoji from the strip, frequencyCount when the co-usage is observed
 * again. Both are u16 and saturate.
 */
internal object TpersemFormat {
    const val MAGIC = "TATPERSE"

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

    /**
     * Fixed record overhead: wordByteLength u8 + emojiByteLength u8 + usageCount u16 +
     * frequencyCount u16 + lastUseSerial u32.
     */
    const val RECORD_HEADER_SIZE = 10

    /** Cap on (word, emoji) entries per subtype; the reader rejects a file over it. */
    const val MAX_PERSONAL_EMOJI_ENTRIES = 500L

    /** Cap on the whole file; the reader rejects a larger file. */
    const val MAX_FILE_SIZE = 65_536L

    /**
     * Inclusive code-point length bounds for the normalized word, the same window as
     * [TpersbFormat.MIN_WORD_CODE_POINTS]..[TpersbFormat.MAX_WORD_CODE_POINTS].
     */
    const val MIN_WORD_CODE_POINTS = 1
    const val MAX_WORD_CODE_POINTS = 24

    /**
     * Cap on the emoji cluster in UTF-16 units (`String.length`), equal to
     * `EmojiTextUtils.MAX_CLUSTER_CHARS`, the largest cluster the keyboard handles as one unit.
     */
    const val MAX_EMOJI_CLUSTER_CHARS = 32

    const val MAX_U16 = 0xffffL
    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name for a subtype, e.g. `personal-emoji-tt_RU-s1-f1.tpersem`, next to the
     * word and pair files. See [TpersFormat.personalFileName].
     */
    fun personalEmojiFileName(subtypeTag: String): String =
        "personal-emoji-$subtypeTag-s$SCHEMA_ID-f$FORMAT_VERSION.tpersem"
}
