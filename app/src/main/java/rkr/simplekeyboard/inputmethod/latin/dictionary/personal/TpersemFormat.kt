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
 * The frozen `.tpersem` personal-emoji binary format: one learned (word, emoji) co-occurrence per
 * record — the word the user committed, the emoji cluster they keep inserting after it («хәйерле
 * иртә» → ☀️). This is a deliberate sibling of `.tpersb` ([TpersbFormat]) rather than an extension
 * of it: the pairs file is keyed by an ORDERED PAIR OF WORDS, the emoji file by a (word, emoji
 * cluster) pair, and a reader written for one must never even open the other — which is why the
 * magic, the extension and the file name all differ while the header layout, the checksum
 * convention (SHA-256 over the whole file with the checksum field zeroed) and the subtype tag stay
 * identical.
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
 * The word is stored in its normalized form only, exactly like the pairs store's context half: it
 * is a lookup key — the suggestion is looked up by the normalized form of the committed word — and
 * the settings list shows it in that same form, so keeping the user's casing of it would cost
 * bytes and buy nothing. The emoji has no casing to lose: a cluster is stored exactly as it was
 * picked.
 *
 * The two counters are the two halves of the pinned ranking (usage descending, then frequency
 * descending, then the key's own ascending order): [usageCount] grows only when the user ACCEPTS
 * the learned emoji from the strip, [frequencyCount] only when the co-usage is observed again.
 * Both are u16 and saturate.
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

    /** Cap on (word, emoji) entries per subtype; enforced fail-closed by the reader from day one. */
    const val MAX_PERSONAL_EMOJI_ENTRIES = 500L

    /** Cap on the whole file, 64 KiB; enforced by the reader from day one. */
    const val MAX_FILE_SIZE = 65_536L

    /**
     * Inclusive code-point length bounds for the normalized form of the word — the same window the
     * pairs store pins ([TpersbFormat.MIN_WORD_CODE_POINTS]/[TpersbFormat.MAX_WORD_CODE_POINTS]):
     * a one-letter word («а») is a legitimate thing to attach an emoji to.
     */
    const val MIN_WORD_CODE_POINTS = 1
    const val MAX_WORD_CODE_POINTS = 24

    /**
     * Cap on the emoji cluster in UTF-16 units (`String.length`), mirroring the 32 of
     * `EmojiTextUtils.MAX_CLUSTER_CHARS`: the largest cluster the keyboard ever handles as one
     * unit (the editor's backspace) is the largest one worth learning.
     */
    const val MAX_EMOJI_CLUSTER_CHARS = 32

    const val MAX_U16 = 0xffffL
    const val MAX_U32 = 0xffff_ffffL

    /**
     * The on-disk file name for a subtype, e.g. `personal-emoji-tt_RU-s1-f1.tpersem`. A sibling of
     * the words and pairs files in the same `personal/` directory; the schema and format version
     * are woven into the name so a file written by an incompatible build is never even opened for
     * the wrong reader.
     */
    fun personalEmojiFileName(subtypeTag: String): String =
        "personal-emoji-$subtypeTag-s$SCHEMA_ID-f$FORMAT_VERSION.tpersem"
}
