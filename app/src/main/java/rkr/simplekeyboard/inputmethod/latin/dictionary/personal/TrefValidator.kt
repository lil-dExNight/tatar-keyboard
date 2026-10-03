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

/**
 * Thrown by [TrefValidator] on any violation. The message is always a constant: it never contains
 * the offending pair, the file path or any other user text.
 */
class RefusedCorrectionsValidationException internal constructor(message: String) :
    Exception(message)

/**
 * A validated `.tref` file, ready to become an immutable snapshot. A plain class without a
 * generated `toString`, because it carries the user's text.
 */
class ValidatedRefusedCorrections internal constructor(
    /** Typed words in the normalized lookup form, in refusal order (the oldest first). */
    val typedWords: List<String>,
    /** The parallel replacements, in the normalized lookup form. */
    val replacements: List<String>,
    /** The parallel refusal counts (>= 1). */
    val refusalCounts: List<Int>,
    val subtypeTag: String,
) {
    val entryCount: Int
        get() = typedWords.size
}

/**
 * Strict validator for the `.tref` format, modelled on `TpersValidator`.
 *
 * Any violation throws [RefusedCorrectionsValidationException]; the reader turns that into an empty
 * refused list. Nothing here logs, and no message carries user text.
 *
 * Unlike the other personal files there is no ordering requirement and no alphabet check: the
 * record order is the eviction order (see `TrefFormat`), and a refused typed word can sit outside
 * the subtype alphabet — it is a word the dictionary wanted to correct, so it is off-dictionary by
 * construction.
 */
class TrefValidator {
    /**
     * Validates [file] against the [requestedSubtypeId] and returns the parsed pairs.
     *
     * @throws RefusedCorrectionsValidationException on any structural, checksum, subtype, UTF-8,
     *   content, length or duplicate violation.
     */
    fun validate(file: File, requestedSubtypeId: String): ValidatedRefusedCorrections {
        val length = file.length()
        if (length > TrefFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (length < TrefFormat.HEADER_SIZE) fail("file is shorter than its header")

        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) fail("file length changed during read")
        return validate(bytes, requestedSubtypeId)
    }

    /**
     * The in-memory counterpart of [validate]: the same checks over bytes already read. The backup
     * import validates an entry this way before it is allowed anywhere near the store.
     */
    fun validate(bytes: ByteArray, requestedSubtypeId: String): ValidatedRefusedCorrections {
        if (bytes.size.toLong() > TrefFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (bytes.size < TrefFormat.HEADER_SIZE) fail("file is shorter than its header")

        val header = ByteBuffer.wrap(bytes, 0, TrefFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(TrefFormat.MAGIC_SIZE)
        header.get(magic)
        if (!magic.contentEquals(TrefFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            fail("wrong magic")
        }
        val schemaId = header.short.toInt() and 0xffff
        val formatVersion = header.short.toInt() and 0xffff
        val headerSize = header.short.toInt() and 0xffff
        val checksumAlgorithm = header.short.toInt() and 0xffff
        val entryCount = header.int.toLong() and TrefFormat.MAX_U32
        val payloadSize = header.int.toLong() and TrefFormat.MAX_U32
        val subtypeTagBytes = ByteArray(TrefFormat.SUBTYPE_TAG_SIZE)
        header.get(subtypeTagBytes)
        val storedChecksum = ByteArray(TrefFormat.CHECKSUM_SIZE)
        header.get(storedChecksum)

        if (schemaId != TrefFormat.SCHEMA_ID) fail("unsupported schema id")
        if (formatVersion != TrefFormat.FORMAT_VERSION) fail("unsupported format version")
        if (headerSize != TrefFormat.HEADER_SIZE) fail("unexpected header size")
        if (checksumAlgorithm != TrefFormat.CHECKSUM_ALGORITHM_SHA256) {
            fail("unsupported checksum algorithm")
        }
        if (entryCount > TrefFormat.MAX_REFUSED_ENTRIES) fail("entry count limit exceeded")

        val subtypeTag = decodeSubtypeTag(subtypeTagBytes)
        if (subtypeTag != requestedSubtypeId) fail("subtype tag does not match the requested subtype")

        if (payloadSize != bytes.size.toLong() - TrefFormat.HEADER_SIZE) {
            fail("payload size does not match file")
        }

        if (!MessageDigest.isEqual(storedChecksum, digestWithChecksumZeroed(bytes))) {
            fail("checksum mismatch")
        }

        return parsePayload(bytes, entryCount.toInt(), subtypeTag)
    }

    private fun parsePayload(
        bytes: ByteArray,
        entryCount: Int,
        subtypeTag: String,
    ): ValidatedRefusedCorrections {
        val typedWords = ArrayList<String>(entryCount)
        val replacements = ArrayList<String>(entryCount)
        val refusalCounts = ArrayList<Int>(entryCount)
        val seen = HashSet<String>()

        val cursor = ByteBuffer.wrap(bytes, TrefFormat.HEADER_SIZE, bytes.size - TrefFormat.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until entryCount) {
            if (cursor.remaining() < TrefFormat.RECORD_HEADER_SIZE) fail("record header truncated")
            val typedByteLength = cursor.get().toInt() and 0xff
            val replacementByteLength = cursor.get().toInt() and 0xff
            val refusalCount = cursor.get().toInt() and 0xff
            if (typedByteLength < 1) fail("invalid typed word byte length")
            if (replacementByteLength < 1) fail("invalid replacement byte length")
            if (refusalCount < 1) fail("refusal count must be positive")
            if (cursor.remaining() < typedByteLength + replacementByteLength) {
                fail("record bytes truncated")
            }
            val encodedTyped = ByteArray(typedByteLength)
            cursor.get(encodedTyped)
            val encodedReplacement = ByteArray(replacementByteLength)
            cursor.get(encodedReplacement)

            val typedWord = decodeStrictUtf8(encodedTyped)
            if (!isWellFormedWord(typedWord)) fail("typed word is not a normalized word")
            val replacement = decodeStrictUtf8(encodedReplacement)
            if (!isWellFormedWord(replacement)) fail("replacement is not a normalized word")
            if (typedWord == replacement) fail("a correction never replaces a word with itself")
            if (!seen.add(typedWord + "\u0000" + replacement)) fail("duplicate pair")

            typedWords.add(typedWord)
            replacements.add(replacement)
            refusalCounts.add(refusalCount)
        }
        if (cursor.remaining() != 0) fail("trailing payload bytes")

        return ValidatedRefusedCorrections(
            typedWords = typedWords,
            replacements = replacements,
            refusalCounts = refusalCounts,
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
        copy.fill(0, TrefFormat.CHECKSUM_OFFSET, TrefFormat.CHECKSUM_OFFSET + TrefFormat.CHECKSUM_SIZE)
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
        fail("record is not valid UTF-8")
    }

    private fun fail(message: String): Nothing = throw RefusedCorrectionsValidationException(message)

    companion object {
        /**
         * The content rule of both words of a record: within the length bounds, already in the
         * normalized lookup form (NFC lowercase), a leading letter followed by letters and
         * combining marks — the shape the undo path can actually record.
         */
        fun isWellFormedWord(word: String): Boolean {
            val codePointCount = word.codePointCount(0, word.length)
            if (codePointCount < TrefFormat.MIN_WORD_CODE_POINTS ||
                codePointCount > TrefFormat.MAX_WORD_CODE_POINTS
            ) {
                return false
            }
            if (TatarWordUtils.normalizeForLookup(word) != word) return false
            var offset = 0
            var first = true
            while (offset < word.length) {
                val codePoint = word.codePointAt(offset)
                if (Character.isLetter(codePoint)) {
                    // A letter is always allowed.
                } else if (!first && isCombiningMark(codePoint)) {
                    // A combining mark may only continue a letter.
                } else {
                    return false
                }
                first = false
                offset += Character.charCount(codePoint)
            }
            return true
        }

        private fun isCombiningMark(codePoint: Int): Boolean {
            val type = Character.getType(codePoint)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }
    }
}
