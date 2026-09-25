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
 * Thrown by [TpersValidator] on any violation. The message is ALWAYS a constant: it never
 * interpolates the offending word, the file path or any other user text (privacy contract of the
 * personal package). Not a Kotlin `data class`, so no synthesised `toString` can leak a field.
 */
class PersonalDictionaryValidationException internal constructor(message: String) :
    Exception(message)

/**
 * A validated `.tpers` file, ready to become an immutable snapshot. Not a `data class`: it carries
 * the user's words and an auto-generated `toString` would print them on the first interpolation.
 */
class ValidatedPersonalDictionary internal constructor(
    /** Words in their ORIGINAL on-disk form, in normalized-ascending order. */
    val rawForms: List<String>,
    /** The parallel NFC lowercase forms used for sorting, dedup, filters and search. */
    val normalizedForms: List<String>,
    /** Parallel usage counters (>= 1). */
    val usageCounts: IntArray,
    /** Parallel monotonic last-use serials. */
    val lastUseSerials: LongArray,
    val subtypeTag: String,
) {
    val entryCount: Int
        get() = rawForms.size
}

/**
 * Fail-closed validator for the `.tpers` format, modelled on `TdictValidator`.
 *
 * Every check is explicitly attributed to the RAW or the NORMALIZED form of a record, mirroring the
 * frozen contract. Any violation throws [PersonalDictionaryValidationException]; the reader turns
 * that into an empty personal dictionary. Nothing here logs, and no message carries user text.
 */
class TpersValidator {
    /**
     * Validates [file] against the [requestedSubtypeId] and returns the parsed, ordered entries.
     *
     * @throws PersonalDictionaryValidationException on any structural, checksum, subtype, UTF-8,
     *   casing, alphabet, length, ordering or duplicate violation.
     */
    fun validate(file: File, requestedSubtypeId: String): ValidatedPersonalDictionary {
        val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId)
            ?: fail("subtype has no declared alphabet")

        val length = file.length()
        if (length > TpersFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (length < TpersFormat.HEADER_SIZE) fail("file is shorter than its header")

        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) fail("file length changed during read")

        val header = ByteBuffer.wrap(bytes, 0, TpersFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(TpersFormat.MAGIC_SIZE)
        header.get(magic)
        if (!magic.contentEquals(TpersFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            fail("wrong magic")
        }
        val schemaId = header.short.toInt() and 0xffff
        val formatVersion = header.short.toInt() and 0xffff
        val headerSize = header.short.toInt() and 0xffff
        val checksumAlgorithm = header.short.toInt() and 0xffff
        val entryCount = header.int.toLong() and TpersFormat.MAX_U32
        val payloadSize = header.int.toLong() and TpersFormat.MAX_U32
        val subtypeTagBytes = ByteArray(TpersFormat.SUBTYPE_TAG_SIZE)
        header.get(subtypeTagBytes)
        val storedChecksum = ByteArray(TpersFormat.CHECKSUM_SIZE)
        header.get(storedChecksum)

        if (schemaId != TpersFormat.SCHEMA_ID) fail("unsupported schema id")
        if (formatVersion != TpersFormat.FORMAT_VERSION) fail("unsupported format version")
        if (headerSize != TpersFormat.HEADER_SIZE) fail("unexpected header size")
        if (checksumAlgorithm != TpersFormat.CHECKSUM_ALGORITHM_SHA256) {
            fail("unsupported checksum algorithm")
        }
        if (entryCount > TpersFormat.MAX_PERSONAL_ENTRIES) fail("entry count limit exceeded")

        val subtypeTag = decodeSubtypeTag(subtypeTagBytes)
        if (subtypeTag != requestedSubtypeId) fail("subtype tag does not match the requested subtype")

        if (payloadSize != length - TpersFormat.HEADER_SIZE) fail("payload size does not match file")

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
    ): ValidatedPersonalDictionary {
        val rawForms = ArrayList<String>(entryCount)
        val normalizedForms = ArrayList<String>(entryCount)
        val usageCounts = IntArray(entryCount)
        val lastUseSerials = LongArray(entryCount)

        val cursor = ByteBuffer.wrap(bytes, TpersFormat.HEADER_SIZE, bytes.size - TpersFormat.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        var previousNormalizedBytes: ByteArray? = null
        for (index in 0 until entryCount) {
            if (cursor.remaining() < TpersFormat.RECORD_HEADER_SIZE) fail("record header truncated")
            val wordByteLength = cursor.get().toInt() and 0xff
            val usageCount = cursor.short.toInt() and 0xffff
            val lastUseSerial = cursor.int.toLong() and TpersFormat.MAX_U32
            if (usageCount < 1) fail("usage count must be positive")
            if (wordByteLength < 1) fail("invalid word byte length")
            if (cursor.remaining() < wordByteLength) fail("word bytes truncated")
            val encoded = ByteArray(wordByteLength)
            cursor.get(encoded)

            // RAW form: strict UTF-8 and casing must not be MIXED.
            val rawWord = decodeStrictUtf8(encoded)
            if (TatarWordUtils.classifyCasing(rawWord) == TatarWordUtils.PrefixCasing.MIXED) {
                fail("word casing is mixed")
            }

            // NORMALIZED form: length, no leftover combining marks, alphabet, ordering, dedup.
            val normalized = Normalizer.normalize(rawWord, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            val codePointCount = normalized.codePointCount(0, normalized.length)
            if (codePointCount < TpersFormat.MIN_WORD_CODE_POINTS ||
                codePointCount > TpersFormat.MAX_WORD_CODE_POINTS
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

            val normalizedBytes = normalized.toByteArray(StandardCharsets.UTF_8)
            previousNormalizedBytes?.let { previous ->
                val order = compareUnsigned(previous, normalizedBytes)
                if (order == 0) fail("duplicate normalized word")
                if (order > 0) fail("normalized words are not sorted")
            }
            previousNormalizedBytes = normalizedBytes

            rawForms.add(rawWord)
            normalizedForms.add(normalized)
            usageCounts[index] = usageCount
            lastUseSerials[index] = lastUseSerial
        }
        if (cursor.remaining() != 0) fail("trailing payload bytes")

        return ValidatedPersonalDictionary(
            rawForms = rawForms,
            normalizedForms = normalizedForms,
            usageCounts = usageCounts,
            lastUseSerials = lastUseSerials,
            subtypeTag = subtypeTag,
        )
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
        copy.fill(0, TpersFormat.CHECKSUM_OFFSET, TpersFormat.CHECKSUM_OFFSET + TpersFormat.CHECKSUM_SIZE)
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
