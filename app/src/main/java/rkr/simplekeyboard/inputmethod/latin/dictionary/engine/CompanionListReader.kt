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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.CompanionListValidationException
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatCompFormat
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * A validated cold-companion list in unsigned UTF-8 byte order. One byte array and one offset
 * array back both membership and [words]; decoded strings are created only when [words] is read.
 */
class ValidatedCompanionList internal constructor(
    private val wordBytes: ByteArray,
    private val wordStarts: IntArray,
) {
    internal constructor(words: List<String>) : this(packWords(words))

    private constructor(packed: PackedWords) : this(packed.bytes, packed.starts)

    /** The decoded words, in stored order; access does not retain the decoded strings. */
    val words: List<String> = object : AbstractList<String>() {
        override val size: Int get() = wordStarts.size - 1

        override fun get(index: Int): String {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException(index.toString())
            return String(
                wordBytes, wordStarts[index], wordStarts[index + 1] - wordStarts[index],
                Charsets.UTF_8,
            )
        }
    }

    /** Heap payload retained by this list, excluding the two array headers and the decoded view. */
    val retainedByteEstimate: Long
        get() = wordBytes.size.toLong() + wordStarts.size * Int.SIZE_BYTES

    fun containsWord(normalizedWord: String): Boolean {
        val query = normalizedWord.toByteArray(Charsets.UTF_8)
        if (query.isEmpty() || query.size > TatCompFormat.MAX_WORD_BYTES) return false
        var low = 0
        var high = wordStarts.size - 2
        while (low <= high) {
            val middle = (low + high) ushr 1
            when (compareToStored(query, middle)) {
                0 -> return true
                -1 -> high = middle - 1
                else -> low = middle + 1
            }
        }
        return false
    }

    private fun compareToStored(query: ByteArray, wordIndex: Int): Int {
        val start = wordStarts[wordIndex]
        val length = wordStarts[wordIndex + 1] - start
        val limit = minOf(query.size, length)
        for (index in 0 until limit) {
            val order = (query[index].toInt() and 0xff) -
                (wordBytes[start + index].toInt() and 0xff)
            if (order != 0) return Integer.signum(order)
        }
        return Integer.signum(query.size - length)
    }

    private class PackedWords(val bytes: ByteArray, val starts: IntArray)

    companion object {
        private fun packWords(words: List<String>): PackedWords {
            val encoded = Array(words.size) { words[it].toByteArray(Charsets.UTF_8) }
            val bytes = ByteArray(encoded.sumOf { it.size })
            val starts = IntArray(words.size + 1)
            var cursor = 0
            for (index in encoded.indices) {
                val word = encoded[index]
                word.copyInto(bytes, cursor)
                cursor += word.size
                starts[index + 1] = cursor
            }
            return PackedWords(bytes, starts)
        }
    }
}

/**
 * Reads and validates one cold-companion list asset, following the storage validators' rules:
 * every header field is checked against [spec] and the file's own header, the header checksum
 * is verified, and any violation throws [CompanionListValidationException] — nothing repairs
 * data, and the caller's fail-closed rule turns a rejected list into no protection, never into
 * a suppressed correction.
 */
class CompanionListReader {
    /**
     * Inflates and validates the asset bytes of [spec] into a [ValidatedCompanionList].
     *
     * @throws CompanionListValidationException on any structural, size, SHA-256, checksum,
     *   ordering or duplicate violation.
     */
    fun read(input: InputStream, spec: CompanionListArtifactSpec): ValidatedCompanionList {
        val compressed = readExactly(input, spec)
        if (compressed.size.toLong() != spec.expectedCompressedSize) {
            throw CompanionListValidationException("unexpected compressed size")
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(compressed)
            .joinToString("") { "%02x".format(it) }
        if (!sha.equals(spec.expectedCompressedSha256, ignoreCase = true)) {
            throw CompanionListValidationException("unexpected compressed SHA-256")
        }
        val raw = inflate(compressed, spec)
        if (raw.size.toLong() != spec.expectedRawSize) {
            throw CompanionListValidationException("unexpected raw size")
        }
        return validateRaw(raw, spec)
    }

    private fun inflate(compressed: ByteArray, spec: CompanionListArtifactSpec): ByteArray {
        val inflater = Inflater(false)
        try {
            inflater.setInput(compressed)
            // The pinned raw size bounds the buffer; a stream that tries to inflate past it is
            // rejected, so a corrupt asset cannot make this read allocate without limit.
            val output = ByteArray(
                spec.expectedRawSize.coerceAtMost(TatCompFormat.MAX_RAW_SIZE).toInt())
            val count = try {
                inflater.inflate(output)
            } catch (error: java.util.zip.DataFormatException) {
                throw CompanionListValidationException("invalid zlib stream", error)
            }
            if (!inflater.finished()) {
                throw CompanionListValidationException("truncated or oversized zlib stream")
            }
            if (inflater.remaining != 0) {
                throw CompanionListValidationException("trailing or concatenated zlib data")
            }
            return output.copyOf(count)
        } finally {
            inflater.end()
        }
    }

    /** The in-memory counterpart of [read]: the same checks over bytes already inflated. */
    fun validateRaw(raw: ByteArray, spec: CompanionListArtifactSpec): ValidatedCompanionList =
        parseHeader(raw, spec)

    private fun parseHeader(
        raw: ByteArray,
        spec: CompanionListArtifactSpec,
    ): ValidatedCompanionList {
        if (raw.size > TatCompFormat.MAX_RAW_SIZE.toInt()) {
            throw CompanionListValidationException("raw size limit exceeded")
        }
        if (raw.size < TatCompFormat.HEADER_SIZE) {
            throw CompanionListValidationException("raw list is shorter than its header")
        }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buffer.get(magic)
        if (String(magic, Charsets.US_ASCII) != TatCompFormat.MAGIC) {
            throw CompanionListValidationException("magic is not TATCOMP\\0")
        }
        val schemaId = buffer.short.toInt() and 0xffff
        if (schemaId != TatCompFormat.SCHEMA_ID) {
            throw CompanionListValidationException("unsupported schema id")
        }
        if (schemaId != spec.schemaId) {
            throw CompanionListValidationException("schema id differs from the contract")
        }
        val formatVersion = buffer.short.toInt() and 0xffff
        if (formatVersion != TatCompFormat.FORMAT_VERSION) {
            throw CompanionListValidationException("unsupported format version")
        }
        if (formatVersion != spec.formatVersion) {
            throw CompanionListValidationException("format version differs from the contract")
        }
        val headerSize = buffer.short.toInt() and 0xffff
        if (headerSize != TatCompFormat.HEADER_SIZE) {
            throw CompanionListValidationException("unsupported header size")
        }
        val checksumAlgorithm = buffer.short.toInt() and 0xffff
        if (checksumAlgorithm != TatCompFormat.CHECKSUM_ALGORITHM_SHA256) {
            throw CompanionListValidationException("unsupported checksum algorithm")
        }
        val wordCount = buffer.int.toLong() and 0xffff_ffffL
        if (wordCount != spec.expectedWordCount) {
            throw CompanionListValidationException("unexpected word count")
        }
        if (wordCount > TatCompFormat.MAX_WORDS) {
            throw CompanionListValidationException("word count over the bound")
        }
        val bodySize = buffer.int.toLong() and 0xffff_ffffL
        if (TatCompFormat.HEADER_SIZE + bodySize != raw.size.toLong()) {
            throw CompanionListValidationException("body size does not close the file")
        }
        val checksum = ByteArray(TatCompFormat.CHECKSUM_SIZE)
        System.arraycopy(
            raw, TatCompFormat.CHECKSUM_OFFSET, checksum, 0, TatCompFormat.CHECKSUM_SIZE,
        )
        val zeroed = raw.copyOf()
        zeroed.fill(0, TatCompFormat.CHECKSUM_OFFSET,
            TatCompFormat.CHECKSUM_OFFSET + TatCompFormat.CHECKSUM_SIZE)
        if (!MessageDigest.getInstance("SHA-256").digest(zeroed).contentEquals(checksum)) {
            throw CompanionListValidationException("header checksum mismatch")
        }
        if (wordCount > bodySize) {
            throw CompanionListValidationException("body ends before the word count is met")
        }

        val count = wordCount.toInt()
        val wordBytes = ByteArray(bodySize.toInt() - count)
        val wordStarts = IntArray(count + 1)
        var cursor = TatCompFormat.HEADER_SIZE
        var writeCursor = 0
        var previousStart = -1
        var previousLength = 0
        repeat(count) { index ->
            if (cursor >= raw.size) {
                throw CompanionListValidationException("body ends before the word count is met")
            }
            val length = raw[cursor].toInt() and 0xff
            cursor++
            if (length == 0) {
                throw CompanionListValidationException("a stored word is empty")
            }
            if (length > TatCompFormat.MAX_WORD_BYTES) {
                throw CompanionListValidationException("a stored word is over the length bound")
            }
            if (cursor + length > raw.size) {
                throw CompanionListValidationException("a stored word runs past the body")
            }
            if (previousStart >= 0 &&
                compareUnsigned(raw, previousStart, previousLength, raw, cursor, length) >= 0
            ) {
                throw CompanionListValidationException("words are not strictly ascending")
            }
            raw.copyInto(wordBytes, writeCursor, cursor, cursor + length)
            previousStart = cursor
            previousLength = length
            cursor += length
            writeCursor += length
            wordStarts[index + 1] = writeCursor
        }
        if (cursor != raw.size) {
            throw CompanionListValidationException("body holds more than the word count")
        }
        return ValidatedCompanionList(wordBytes, wordStarts)
    }

    private fun readExactly(input: InputStream, spec: CompanionListArtifactSpec): ByteArray =
        try {
            // At most one byte past the pinned size: the size check in [read] then rejects an
            // oversized stream without the whole stream ever being buffered.
            val limit = (spec.expectedCompressedSize + 1)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val buffer = ByteArray(limit)
            var offset = 0
            while (offset < limit) {
                val count = input.read(buffer, offset, limit - offset)
                if (count < 0) break
                offset += count
            }
            buffer.copyOf(offset)
        } catch (error: Exception) {
            throw CompanionListValidationException("unreadable asset stream", error)
        }

    private fun compareUnsigned(
        left: ByteArray,
        leftStart: Int,
        leftLength: Int,
        right: ByteArray,
        rightStart: Int,
        rightLength: Int,
    ): Int {
        val limit = minOf(leftLength, rightLength)
        for (index in 0 until limit) {
            val order = (left[leftStart + index].toInt() and 0xff) -
                (right[rightStart + index].toInt() and 0xff)
            if (order != 0) return Integer.signum(order)
        }
        return Integer.signum(leftLength - rightLength)
    }
}
