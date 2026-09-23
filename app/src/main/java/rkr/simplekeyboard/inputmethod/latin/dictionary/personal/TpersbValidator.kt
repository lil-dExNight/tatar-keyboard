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

import androidx.annotation.Keep
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * A validated `.tpersb` file, ready to become an immutable snapshot. Parallel arrays in the
 * on-disk (pair-key ascending) order. Not a `data class`: it carries the user's words and an
 * auto-generated `toString` would print them on the first interpolation.
 */
@Keep
class ValidatedPersonalBigrams internal constructor(
    /** Context words in the NORMALIZED form (the only form the format stores for them). */
    val contexts: List<String>,
    /** Successor words in their ORIGINAL on-disk form (what is shown and inserted). */
    val successorRawForms: List<String>,
    /** The parallel NFC lowercase successor forms used for sorting, dedup and comparison. */
    val successorNormalizedForms: List<String>,
    /** Parallel accepted-prediction counters (>= 0). */
    val usageCounts: IntArray,
    /** Parallel clean-observation counters (>= 1). */
    val frequencyCounts: IntArray,
    /** Parallel monotonic last-use serials. */
    val lastUseSerials: LongArray,
    val subtypeTag: String,
) {
    val pairCount: Int
        get() = contexts.size
}

/**
 * Fail-closed validator for the `.tpersb` format, modelled on [TpersValidator].
 *
 * Every check is explicitly attributed to the CONTEXT or the SUCCESSOR half of a record, mirroring
 * the frozen contract. Any violation throws [PersonalDictionaryValidationException] — shared with
 * the words store on purpose: it carries a constant message and no user text, which is the whole
 * contract of the type. The reader turns a violation into an empty personal-bigram store. Nothing
 * here logs, and no message carries user text.
 */
@Keep
class TpersbValidator {
    /**
     * Validates [file] against the [requestedSubtypeId] and returns the parsed, ordered pairs.
     *
     * @throws PersonalDictionaryValidationException on any structural, checksum, subtype, UTF-8,
     *   casing, alphabet, length, ordering or duplicate violation.
     */
    fun validate(file: File, requestedSubtypeId: String): ValidatedPersonalBigrams {
        val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId)
            ?: fail("subtype has no declared alphabet")

        val length = file.length()
        if (length > TpersbFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (length < TpersbFormat.HEADER_SIZE) fail("file is shorter than its header")

        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) fail("file length changed during read")

        val header = ByteBuffer.wrap(bytes, 0, TpersbFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(TpersbFormat.MAGIC_SIZE)
        header.get(magic)
        if (!magic.contentEquals(TpersbFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            fail("wrong magic")
        }
        val schemaId = header.short.toInt() and 0xffff
        val formatVersion = header.short.toInt() and 0xffff
        val headerSize = header.short.toInt() and 0xffff
        val checksumAlgorithm = header.short.toInt() and 0xffff
        val pairCount = header.int.toLong() and TpersbFormat.MAX_U32
        val payloadSize = header.int.toLong() and TpersbFormat.MAX_U32
        val subtypeTagBytes = ByteArray(TpersbFormat.SUBTYPE_TAG_SIZE)
        header.get(subtypeTagBytes)
        val storedChecksum = ByteArray(TpersbFormat.CHECKSUM_SIZE)
        header.get(storedChecksum)

        if (schemaId != TpersbFormat.SCHEMA_ID) fail("unsupported schema id")
        if (formatVersion != TpersbFormat.FORMAT_VERSION) fail("unsupported format version")
        if (headerSize != TpersbFormat.HEADER_SIZE) fail("unexpected header size")
        if (checksumAlgorithm != TpersbFormat.CHECKSUM_ALGORITHM_SHA256) {
            fail("unsupported checksum algorithm")
        }
        if (pairCount > TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS) fail("pair count limit exceeded")

        val subtypeTag = decodeSubtypeTag(subtypeTagBytes)
        if (subtypeTag != requestedSubtypeId) fail("subtype tag does not match the requested subtype")

        if (payloadSize != length - TpersbFormat.HEADER_SIZE) fail("payload size does not match file")

        if (!MessageDigest.isEqual(storedChecksum, digestWithChecksumZeroed(bytes))) {
            fail("checksum mismatch")
        }

        return parsePayload(bytes, pairCount.toInt(), alphabet, subtypeTag)
    }

    private fun parsePayload(
        bytes: ByteArray,
        pairCount: Int,
        alphabet: Set<Int>,
        subtypeTag: String,
    ): ValidatedPersonalBigrams {
        val contexts = ArrayList<String>(pairCount)
        val successorRawForms = ArrayList<String>(pairCount)
        val successorNormalizedForms = ArrayList<String>(pairCount)
        val usageCounts = IntArray(pairCount)
        val frequencyCounts = IntArray(pairCount)
        val lastUseSerials = LongArray(pairCount)

        val cursor = ByteBuffer
            .wrap(bytes, TpersbFormat.HEADER_SIZE, bytes.size - TpersbFormat.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        var previousContextBytes: ByteArray? = null
        var previousSuccessorBytes: ByteArray? = null
        for (index in 0 until pairCount) {
            if (cursor.remaining() < TpersbFormat.RECORD_HEADER_SIZE) fail("record header truncated")
            val contextByteLength = cursor.get().toInt() and 0xff
            val successorByteLength = cursor.get().toInt() and 0xff
            val usageCount = cursor.short.toInt() and 0xffff
            val frequencyCount = cursor.short.toInt() and 0xffff
            val lastUseSerial = cursor.int.toLong() and TpersbFormat.MAX_U32
            if (contextByteLength < 1) fail("invalid context byte length")
            if (successorByteLength < 1) fail("invalid successor byte length")
            if (frequencyCount < 1) fail("frequency count must be positive")
            if (cursor.remaining() < contextByteLength + successorByteLength) {
                fail("word bytes truncated")
            }
            val contextBytes = ByteArray(contextByteLength)
            cursor.get(contextBytes)
            val successorBytes = ByteArray(successorByteLength)
            cursor.get(successorBytes)

            // CONTEXT half: stored normalized, so it must BE its own NFC lowercase form.
            val context = decodeStrictUtf8(contextBytes)
            checkNormalizedWord(context, alphabet)

            // SUCCESSOR half: raw form stored; casing must not be MIXED, the normalized form must
            // pass the same content checks as the context.
            val successorRaw = decodeStrictUtf8(successorBytes)
            if (TatarWordUtils.classifyCasing(successorRaw) == TatarWordUtils.PrefixCasing.MIXED) {
                fail("successor casing is mixed")
            }
            val successorNormalized = Normalizer.normalize(successorRaw, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
            checkNormalizedWord(successorNormalized, alphabet)

            // The order key is the PAIR, compared member by member: context first, successor on a
            // tie. Comparing the concatenation instead would call («аб», «вг») and («абв», «г»)
            // equal — the byte boundary between the two words is part of the key.
            val previousContext = previousContextBytes
            if (previousContext != null && previousSuccessorBytes != null) {
                val contextOrder = compareUnsigned(previousContext, contextBytes)
                val order = if (contextOrder != 0) {
                    contextOrder
                } else {
                    compareUnsigned(previousSuccessorBytes, successorBytes)
                }
                if (order == 0) fail("duplicate pair")
                if (order > 0) fail("pairs are not sorted")
            }
            previousContextBytes = contextBytes
            previousSuccessorBytes = successorBytes

            contexts.add(context)
            successorRawForms.add(successorRaw)
            successorNormalizedForms.add(successorNormalized)
            usageCounts[index] = usageCount
            frequencyCounts[index] = frequencyCount
            lastUseSerials[index] = lastUseSerial
        }
        if (cursor.remaining() != 0) fail("trailing payload bytes")

        return ValidatedPersonalBigrams(
            contexts = contexts,
            successorRawForms = successorRawForms,
            successorNormalizedForms = successorNormalizedForms,
            usageCounts = usageCounts,
            frequencyCounts = frequencyCounts,
            lastUseSerials = lastUseSerials,
            subtypeTag = subtypeTag,
        )
    }

    /**
     * The content checks every NORMALIZED word of a pair passes: it is its own NFC lowercase form,
     * its length is within the frozen bounds, no combining mark is left after NFC, and every code
     * point belongs to the subtype alphabet.
     */
    private fun checkNormalizedWord(normalized: String, alphabet: Set<Int>) {
        if (Normalizer.normalize(normalized, Normalizer.Form.NFC) != normalized ||
            normalized.lowercase(Locale.ROOT) != normalized
        ) {
            fail("word is not in the normalized form")
        }
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount < TpersbFormat.MIN_WORD_CODE_POINTS ||
            codePointCount > TpersbFormat.MAX_WORD_CODE_POINTS
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
            0, TpersbFormat.CHECKSUM_OFFSET, TpersbFormat.CHECKSUM_OFFSET + TpersbFormat.CHECKSUM_SIZE,
        )
        return MessageDigest.getInstance("SHA-256").digest(copy)
    }

    private fun decodeStrictUtf8(encoded: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    } catch (_: Exception) {
        // Constant message: never echo the bytes that failed to decode.
        fail("word is not valid UTF-8")
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
