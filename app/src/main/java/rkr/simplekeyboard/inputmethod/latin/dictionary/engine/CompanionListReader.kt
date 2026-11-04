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
 * The validated contents of one cold-companion word list: the words in their stored (unsigned
 * UTF-8 byte) order. Android-free, so it is unit-testable on the plain JVM.
 *
 * [containsWord] answers an exact whole-word membership question with a binary search over the
 * stored order; an unlisted word answers false, so an unlisted word of the companion language
 * stays correctable exactly as before the list existed.
 */
class ValidatedCompanionList internal constructor(
    /** The words, in unsigned UTF-8 byte order. */
    val words: List<String>,
) {
    /** [words] as UTF-8 bytes, encoded once at load: a probe never re-encodes a stored word. */
    private val encodedWords: List<ByteArray> = words.map { it.toByteArray(Charsets.UTF_8) }

    fun containsWord(normalizedWord: String): Boolean {
        val query = normalizedWord.toByteArray(Charsets.UTF_8)
        if (query.isEmpty() || query.size > TatCompFormat.MAX_WORD_BYTES) return false
        var low = 0
        var high = encodedWords.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when (compareBytes(query, encodedWords[middle])) {
                0 -> return true
                -1 -> high = middle - 1
                else -> low = middle + 1
            }
        }
        return false
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        val limit = minOf(left.size, right.size)
        for (index in 0 until limit) {
            val order = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (order != 0) return Integer.signum(order)
        }
        return Integer.signum(left.size - right.size)
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
        ValidatedCompanionList(parseHeader(raw, spec))

    private fun parseHeader(
        raw: ByteArray,
        spec: CompanionListArtifactSpec,
    ): List<String> {
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

        val words = ArrayList<String>(wordCount.toInt())
        var cursor = TatCompFormat.HEADER_SIZE
        var previous: ByteArray? = null
        repeat(wordCount.toInt()) {
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
            val word = raw.copyOfRange(cursor, cursor + length)
            cursor += length
            if (previous != null && compareUnsigned(previous!!, word) >= 0) {
                throw CompanionListValidationException("words are not strictly ascending")
            }
            previous = word
            val text = try {
                String(word, Charsets.UTF_8)
            } catch (error: RuntimeException) {
                throw CompanionListValidationException("a stored word is not valid UTF-8", error)
            }
            words.add(text)
        }
        if (cursor != raw.size) {
            throw CompanionListValidationException("body holds more than the word count")
        }
        return words
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

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val limit = minOf(left.size, right.size)
        for (index in 0 until limit) {
            val order = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (order != 0) return Integer.signum(order)
        }
        return Integer.signum(left.size - right.size)
    }
}

