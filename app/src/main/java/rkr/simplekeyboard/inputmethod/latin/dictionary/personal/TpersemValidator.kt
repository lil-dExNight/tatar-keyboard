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

import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * A validated `.tpersem` file, ready to become an immutable snapshot. Parallel arrays in the
 * on-disk (entry-key ascending) order. See [ValidatedPersonalDictionary].
 */
class ValidatedPersonalEmoji internal constructor(
    /** Words in the NORMALIZED form (the only form the format stores for them). */
    val words: List<String>,
    /** Emoji clusters in their RAW form — the only form an emoji has. */
    val emojiClusters: List<String>,
    /** Parallel accepted-suggestion counters (>= 0). */
    val usageCounts: IntArray,
    /** Parallel co-usage observation counters (>= 1). */
    val frequencyCounts: IntArray,
    /** Parallel monotonic last-use serials. */
    val lastUseSerials: LongArray,
    val subtypeTag: String,
) {
    val entryCount: Int
        get() = words.size
}

/**
 * Strict validator for the `.tpersem` format; see [TpersbValidator].
 *
 * The word half gets the same checks as a pair context. The emoji half is the raw cluster: at most
 * [TpersemFormat.MAX_EMOJI_CLUSTER_CHARS] UTF-16 units, and the whole string must be one cluster by
 * [EmojiTextUtils.trailingEmojiClusterLength], the ruler backspace deletes by. Violations throw the
 * shared [PersonalDictionaryValidationException]; the reader turns them into an empty emoji store.
 */
class TpersemValidator {
    /**
     * Validates [file] against the [requestedSubtypeId] and returns the parsed, ordered entries.
     *
     * @throws PersonalDictionaryValidationException on any structural, checksum, subtype, UTF-8,
     *   alphabet, length, cluster-shape, ordering or duplicate violation.
     */
    fun validate(file: File, requestedSubtypeId: String): ValidatedPersonalEmoji {
        val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId)
            ?: fail("subtype has no declared alphabet")

        val length = file.length()
        if (length > TpersemFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (length < TpersemFormat.HEADER_SIZE) fail("file is shorter than its header")

        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) fail("file length changed during read")

        val header = ByteBuffer.wrap(bytes, 0, TpersemFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(TpersemFormat.MAGIC_SIZE)
        header.get(magic)
        if (!magic.contentEquals(TpersemFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            fail("wrong magic")
        }
        val schemaId = header.short.toInt() and 0xffff
        val formatVersion = header.short.toInt() and 0xffff
        val headerSize = header.short.toInt() and 0xffff
        val checksumAlgorithm = header.short.toInt() and 0xffff
        val entryCount = header.int.toLong() and TpersemFormat.MAX_U32
        val payloadSize = header.int.toLong() and TpersemFormat.MAX_U32
        val subtypeTagBytes = ByteArray(TpersemFormat.SUBTYPE_TAG_SIZE)
        header.get(subtypeTagBytes)
        val storedChecksum = ByteArray(TpersemFormat.CHECKSUM_SIZE)
        header.get(storedChecksum)

        if (schemaId != TpersemFormat.SCHEMA_ID) fail("unsupported schema id")
        if (formatVersion != TpersemFormat.FORMAT_VERSION) fail("unsupported format version")
        if (headerSize != TpersemFormat.HEADER_SIZE) fail("unexpected header size")
        if (checksumAlgorithm != TpersemFormat.CHECKSUM_ALGORITHM_SHA256) {
            fail("unsupported checksum algorithm")
        }
        if (entryCount > TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES) fail("entry count limit exceeded")

        val subtypeTag = decodeSubtypeTag(subtypeTagBytes)
        if (subtypeTag != requestedSubtypeId) fail("subtype tag does not match the requested subtype")

        if (payloadSize != length - TpersemFormat.HEADER_SIZE) fail("payload size does not match file")

        if (!MessageDigest.isEqual(storedChecksum, digestWithChecksumZeroed(bytes))) {
            fail("checksum mismatch")
        }

        return parsePayload(bytes, entryCount.toInt(), alphabet, subtypeTag)
    }

    private fun parsePayload(
        bytes: ByteArray,
        entryCount: Int,
        alphabet: Set<Int>,
        subtypeTag: String,
    ): ValidatedPersonalEmoji {
        val words = ArrayList<String>(entryCount)
        val emojiClusters = ArrayList<String>(entryCount)
        val usageCounts = IntArray(entryCount)
        val frequencyCounts = IntArray(entryCount)
        val lastUseSerials = LongArray(entryCount)

        val cursor = ByteBuffer
            .wrap(bytes, TpersemFormat.HEADER_SIZE, bytes.size - TpersemFormat.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        var previousWordBytes: ByteArray? = null
        var previousEmojiBytes: ByteArray? = null
        for (index in 0 until entryCount) {
            if (cursor.remaining() < TpersemFormat.RECORD_HEADER_SIZE) fail("record header truncated")
            val wordByteLength = cursor.get().toInt() and 0xff
            val emojiByteLength = cursor.get().toInt() and 0xff
            val usageCount = cursor.short.toInt() and 0xffff
            val frequencyCount = cursor.short.toInt() and 0xffff
            val lastUseSerial = cursor.int.toLong() and TpersemFormat.MAX_U32
            if (wordByteLength < 1) fail("invalid word byte length")
            if (emojiByteLength < 1) fail("invalid emoji byte length")
            if (frequencyCount < 1) fail("frequency count must be positive")
            if (cursor.remaining() < wordByteLength + emojiByteLength) {
                fail("record bytes truncated")
            }
            val wordBytes = ByteArray(wordByteLength)
            cursor.get(wordBytes)
            val emojiBytes = ByteArray(emojiByteLength)
            cursor.get(emojiBytes)

            // WORD half: stored normalized, so it must BE its own NFC lowercase form; the same
            // checks as a pair context.
            val word = decodeStrictUtf8(wordBytes, "word is not valid UTF-8")
            checkNormalizedWord(word, alphabet)

            // EMOJI half: the raw cluster; non-empty is already guaranteed by the byte length.
            val emoji = decodeStrictUtf8(emojiBytes, "emoji is not valid UTF-8")
            checkEmojiCluster(emoji)

            // The order key is the (word, emoji) pair, compared member by member (word first,
            // emoji on a tie), each as unsigned UTF-8 bytes; see TpersbValidator.
            val previousWord = previousWordBytes
            if (previousWord != null && previousEmojiBytes != null) {
                val wordOrder = compareUnsigned(previousWord, wordBytes)
                val order = if (wordOrder != 0) {
                    wordOrder
                } else {
                    compareUnsigned(previousEmojiBytes, emojiBytes)
                }
                if (order == 0) fail("duplicate entry")
                if (order > 0) fail("entries are not sorted")
            }
            previousWordBytes = wordBytes
            previousEmojiBytes = emojiBytes

            words.add(word)
            emojiClusters.add(emoji)
            usageCounts[index] = usageCount
            frequencyCounts[index] = frequencyCount
            lastUseSerials[index] = lastUseSerial
        }
        if (cursor.remaining() != 0) fail("trailing payload bytes")

        return ValidatedPersonalEmoji(
            words = words,
            emojiClusters = emojiClusters,
            usageCounts = usageCounts,
            frequencyCounts = frequencyCounts,
            lastUseSerials = lastUseSerials,
            subtypeTag = subtypeTag,
        )
    }

    /** Content checks for the normalized word; the same as [TpersbValidator]'s word checks. */
    private fun checkNormalizedWord(normalized: String, alphabet: Set<Int>) {
        if (Normalizer.normalize(normalized, Normalizer.Form.NFC) != normalized ||
            normalized.lowercase(Locale.ROOT) != normalized
        ) {
            fail("word is not in the normalized form")
        }
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount < TpersemFormat.MIN_WORD_CODE_POINTS ||
            codePointCount > TpersemFormat.MAX_WORD_CODE_POINTS
        ) {
            fail("normalized word length out of bounds")
        }
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (isCombiningMark(codePoint)) fail("combining mark remains after NFC")
            if (codePoint !in alphabet) fail("word is outside the subtype alphabet")
            offset += Character.charCount(codePoint)
        }
    }

    /**
     * The emoji half of a record: at most [TpersemFormat.MAX_EMOJI_CLUSTER_CHARS] UTF-16 units, and
     * one cluster, i.e. [EmojiTextUtils.trailingEmojiClusterLength] consumes the whole string.
     */
    private fun checkEmojiCluster(emoji: String) {
        if (emoji.length > TpersemFormat.MAX_EMOJI_CLUSTER_CHARS) {
            fail("emoji cluster length out of bounds")
        }
        if (EmojiTextUtils.trailingEmojiClusterLength(emoji) != emoji.length) {
            fail("emoji is not a single emoji cluster")
        }
    }

    private fun decodeSubtypeTag(tagBytes: ByteArray): String {
        var end = tagBytes.size
        while (end > 0 && tagBytes[end - 1].toInt() == 0) end--
        for (index in 0 until end) {
            val value = tagBytes[index].toInt() and 0xff
            // ASCII, no NUL inside the meaningful part.
            if (value == 0 || value > 0x7f) fail("subtype tag is not printable ASCII")
        }
        return String(tagBytes, 0, end, StandardCharsets.US_ASCII)
    }

    private fun digestWithChecksumZeroed(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        copy.fill(
            0, TpersemFormat.CHECKSUM_OFFSET,
            TpersemFormat.CHECKSUM_OFFSET + TpersemFormat.CHECKSUM_SIZE,
        )
        return MessageDigest.getInstance("SHA-256").digest(copy)
    }

    private fun decodeStrictUtf8(encoded: ByteArray, failureMessage: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    } catch (_: Exception) {
        // Constant message: never echo the bytes that failed to decode.
        fail(failureMessage)
    }

    private fun isCombiningMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        val count = minOf(first.size, second.size)
        for (index in 0 until count) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    private fun fail(message: String): Nothing = throw PersonalDictionaryValidationException(message)
}
