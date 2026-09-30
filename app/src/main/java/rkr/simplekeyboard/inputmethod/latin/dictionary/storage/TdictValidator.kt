package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class DictionaryValidationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class ValidatedDictionary(
    val rawSize: Long,
    val entryCount: Long,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

data class InflatedAsset(
    val compressedSize: Long,
    val compressedSha256: String,
    val rawSize: Long,
)

class TdictValidator {
    fun inflateAsset(
        source: java.io.InputStream,
        destination: OutputStream,
        spec: DictionaryArtifactSpec,
    ): InflatedAsset {
        val inflater = Inflater(false)
        val compressedDigest = MessageDigest.getInstance("SHA-256")
        val inputBuffer = ByteArray(BUFFER_SIZE)
        val outputBuffer = ByteArray(BUFFER_SIZE)
        var compressedSize = 0L
        var rawSize = 0L
        val input = BufferedInputStream(source, BUFFER_SIZE)
        val output = BufferedOutputStream(destination, BUFFER_SIZE)
        try {
            while (!inflater.finished()) {
                if (inflater.needsDictionary()) {
                    throw DictionaryValidationException("zlib preset dictionary is unsupported")
                }
                if (inflater.needsInput()) {
                    val count = input.read(inputBuffer)
                    if (count < 0) {
                        throw DictionaryValidationException("truncated zlib stream")
                    }
                    compressedSize += count
                    if (compressedSize > spec.maxCompressedSize) {
                        throw DictionaryValidationException("compressed size limit exceeded")
                    }
                    compressedDigest.update(inputBuffer, 0, count)
                    inflater.setInput(inputBuffer, 0, count)
                }

                val count = try {
                    inflater.inflate(outputBuffer)
                } catch (error: DataFormatException) {
                    throw DictionaryValidationException("invalid zlib stream", error)
                }
                if (count > 0) {
                    rawSize += count
                    if (rawSize > spec.maxRawSize) {
                        throw DictionaryValidationException("raw size limit exceeded")
                    }
                    output.write(outputBuffer, 0, count)
                } else if (!inflater.finished() && !inflater.needsInput() &&
                    !inflater.needsDictionary()
                ) {
                    throw DictionaryValidationException("zlib inflater made no progress")
                }
            }

            if (inflater.remaining != 0 || input.read() != -1) {
                throw DictionaryValidationException("trailing or concatenated zlib data")
            }
            output.flush()
        } finally {
            inflater.end()
        }

        val compressedSha = compressedDigest.digest().toHex()
        if (compressedSize != spec.expectedCompressedSize) {
            throw DictionaryValidationException("unexpected compressed size")
        }
        if (!constantTimeHexEquals(compressedSha, spec.expectedCompressedSha256)) {
            throw DictionaryValidationException("unexpected compressed SHA-256")
        }
        if (rawSize != spec.expectedRawSize) {
            throw DictionaryValidationException("unexpected raw size")
        }
        return InflatedAsset(compressedSize, compressedSha, rawSize)
    }

    fun validateRaw(file: File, spec: DictionaryArtifactSpec): ValidatedDictionary {
        val length = file.length()
        if (length > spec.maxRawSize) {
            throw DictionaryValidationException("raw size limit exceeded")
        }
        if (length < TdictFormat.HEADER_SIZE) {
            throw DictionaryValidationException("raw dictionary is shorter than its header")
        }
        if (length != spec.expectedRawSize) {
            throw DictionaryValidationException("unexpected raw size")
        }

        // The raw file is bounded by the size limit above, so validating from memory is simpler
        // and faster than a seek per word.
        val bytes = readExactly(file, length)
            ?: throw DictionaryValidationException("raw dictionary changed while reading")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buffer.get(magic)
        if (!magic.contentEquals(TdictFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            throw DictionaryValidationException("wrong dictionary magic")
        }
        val schemaId = buffer.short.toInt() and 0xffff
        val formatVersion = buffer.short.toInt() and 0xffff
        val headerSize = buffer.short.toInt() and 0xffff
        val checksumAlgorithm = buffer.short.toInt() and 0xffff
        val entryCount = buffer.int.toLong() and TdictFormat.MAX_U32
        val blockCount = buffer.int.toLong() and TdictFormat.MAX_U32
        val blockIndexOffset = buffer.int.toLong() and TdictFormat.MAX_U32
        val blocksOffset = buffer.int.toLong() and TdictFormat.MAX_U32
        val blocksSize = buffer.int.toLong() and TdictFormat.MAX_U32
        val declaredFileSize = buffer.int.toLong() and TdictFormat.MAX_U32
        val storedChecksum = ByteArray(TdictFormat.CHECKSUM_SIZE)
        buffer.get(storedChecksum)

        if (schemaId != spec.schemaId || schemaId != TdictFormat.SCHEMA_ID) {
            throw DictionaryValidationException("unsupported schema id")
        }
        if (formatVersion != spec.formatVersion ||
            formatVersion != TdictFormat.FORMAT_VERSION
        ) {
            throw DictionaryValidationException("unsupported format version")
        }
        if (headerSize != TdictFormat.HEADER_SIZE) {
            throw DictionaryValidationException("unexpected header size")
        }
        if (checksumAlgorithm != TdictFormat.CHECKSUM_ALGORITHM_SHA256) {
            throw DictionaryValidationException("unsupported checksum algorithm")
        }
        if (entryCount == 0L || entryCount != spec.expectedEntryCount) {
            throw DictionaryValidationException("unexpected entry count")
        }

        val expectedBlockCount = checkedAdd(
            entryCount,
            (TdictFormat.BLOCK_SIZE - 1).toLong(),
        ) / TdictFormat.BLOCK_SIZE
        val expectedBlocksOffset = checkedAdd(
            TdictFormat.HEADER_SIZE.toLong(),
            checkedMultiply(4L, blockCount),
        )
        val expectedFileSize = checkedAdd(expectedBlocksOffset, blocksSize)
        if (expectedBlocksOffset > TdictFormat.MAX_U32 ||
            expectedFileSize > TdictFormat.MAX_U32
        ) {
            throw DictionaryValidationException("section arithmetic exceeds u32")
        }
        if (blockCount != expectedBlockCount ||
            blockIndexOffset != TdictFormat.HEADER_SIZE.toLong() ||
            blocksOffset != expectedBlocksOffset ||
            declaredFileSize != expectedFileSize ||
            declaredFileSize != length
        ) {
            throw DictionaryValidationException("noncanonical section layout")
        }

        val zeroedChecksum = zeroedWindowDigest(
            bytes,
            TdictFormat.CHECKSUM_OFFSET,
            TdictFormat.CHECKSUM_SIZE,
        )
        if (!MessageDigest.isEqual(storedChecksum, zeroedChecksum)) {
            throw DictionaryValidationException("SHA-256 checksum mismatch")
        }
        val rawSha = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        if (!constantTimeHexEquals(rawSha, spec.expectedRawSha256)) {
            throw DictionaryValidationException("unexpected raw SHA-256")
        }

        val blockCountInt = blockCount.toInt()
        val entryCountInt = entryCount.toInt()
        val fileSize = declaredFileSize.toInt()
        val blockOffsets = IntArray(blockCountInt) { buffer.getInt(TdictFormat.HEADER_SIZE + it * 4) }
        if (blockOffsets[0] != blocksOffset.toInt()) {
            throw DictionaryValidationException("first block offset must equal the blocks offset")
        }
        for (index in 1 until blockCountInt) {
            if (blockOffsets[index] <= blockOffsets[index - 1] ||
                blockOffsets[index] >= fileSize
            ) {
                throw DictionaryValidationException("block offsets are not strictly increasing")
            }
        }

        // Two scratch words, swapped after each entry, instead of an array per word.
        var word = ByteArray(TdictFormat.MAX_WORD_BYTES)
        var previousWord = ByteArray(TdictFormat.MAX_WORD_BYTES)
        var previousLength = -1
        var wordIndex = 0
        for (block in 0 until blockCountInt) {
            val blockStart = blockOffsets[block]
            val blockEnd = if (block + 1 < blockCountInt) blockOffsets[block + 1] else fileSize
            val inBlock = minOf(
                TdictFormat.BLOCK_SIZE,
                entryCountInt - block * TdictFormat.BLOCK_SIZE,
            )
            var cursor = blockStart
            val firstLength = unsignedByte(bytes, cursor++)
            if (firstLength == 0 || firstLength > TdictFormat.MAX_WORD_BYTES ||
                cursor + firstLength > blockEnd
            ) {
                throw DictionaryValidationException("invalid first word of a block")
            }
            val firstStart = cursor
            cursor += firstLength

            for (entry in 0 until inBlock) {
                val wordLength: Int
                if (entry == 0) {
                    bytes.copyInto(word, 0, firstStart, firstStart + firstLength)
                    wordLength = firstLength
                } else {
                    val prefixLength = readCanonicalVarint(bytes, cursor, blockEnd)
                        .also { cursor = varintEnd }
                    if (prefixLength > firstLength) {
                        throw DictionaryValidationException("prefix longer than the first word")
                    }
                    if (cursor >= blockEnd) {
                        throw DictionaryValidationException("truncated block entry")
                    }
                    val suffixLength = unsignedByte(bytes, cursor++)
                    if (suffixLength == 0 ||
                        prefixLength + suffixLength > TdictFormat.MAX_WORD_BYTES ||
                        cursor + suffixLength > blockEnd
                    ) {
                        throw DictionaryValidationException("invalid block entry")
                    }
                    bytes.copyInto(word, 0, firstStart, firstStart + prefixLength)
                    bytes.copyInto(word, prefixLength, cursor, cursor + suffixLength)
                    wordLength = prefixLength + suffixLength
                    cursor += suffixLength
                }
                validateStoredWord(word, wordLength, wordIndex)
                if (previousLength >= 0) {
                    val order = compareUnsigned(previousWord, previousLength, word, wordLength)
                    if (order == 0) {
                        throw DictionaryValidationException("duplicate dictionary word")
                    }
                    if (order > 0) {
                        throw DictionaryValidationException("dictionary words are not sorted")
                    }
                }
                val swap = previousWord
                previousWord = word
                word = swap
                previousLength = wordLength
                wordIndex++
            }
            repeat(inBlock) {
                val frequency = readCanonicalVarint(bytes, cursor, blockEnd)
                    .also { cursor = varintEnd }
                if (frequency == 0) {
                    throw DictionaryValidationException("frequency must be positive")
                }
            }
            if (cursor != blockEnd) {
                throw DictionaryValidationException("trailing bytes in a block")
            }
        }

        return ValidatedDictionary(
            rawSize = length,
            entryCount = entryCount,
            schemaId = schemaId,
            formatVersion = formatVersion,
            rawSha256 = rawSha,
        )
    }

    /** Last canonical varint's end offset, set by [readCanonicalVarint]. */
    private var varintEnd = 0

    /** Reads a canonical (minimal-form) base-128 varint; sets [varintEnd] past it. */
    private fun readCanonicalVarint(bytes: ByteArray, offset: Int, limit: Int): Int {
        var value = 0
        var shift = 0
        var cursor = offset
        while (true) {
            if (cursor >= limit) {
                throw DictionaryValidationException("truncated varint")
            }
            val byte = bytes[cursor].toInt() and 0xff
            cursor++
            if (shift == 28 && byte > 0x0f) {
                throw DictionaryValidationException("varint exceeds u32")
            }
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        // Canonical form: no overlong encodings (a value that fits fewer bytes).
        var minimal = 1
        var rest = value ushr 7
        while (rest != 0) {
            minimal++
            rest = rest ushr 7
        }
        if (cursor - offset != minimal) {
            throw DictionaryValidationException("non-canonical (overlong) varint")
        }
        varintEnd = cursor
        return value
    }

    private fun unsignedByte(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    /**
     * Every alphabet letter is a precomposed lowercase code point below [ALPHABET_TABLE_SIZE], so
     * a word is canonical (strict UTF-8, NFC, lowercase, in the alphabet) exactly when it is a
     * sequence of 2-byte UTF-8 letters found in [ALPHABET]; NFC and case mapping leave such a word
     * unchanged, which `TdictValidatorTest` checks for every letter and letter pair.
     */
    private fun validateStoredWord(word: ByteArray, length: Int, index: Int) {
        if (length == 0 || length > MAX_CANONICAL_WORD_BYTES) {
            throw DictionaryValidationException("word $index: invalid word byte length")
        }
        var offset = 0
        while (offset < length) {
            val lead = word[offset].toInt() and 0xff
            if (lead < 0xc2 || lead > 0xdf || offset + 1 >= length) {
                throw DictionaryValidationException("word $index: not a Tatar alphabet letter")
            }
            val continuation = word[offset + 1].toInt() and 0xff
            if (continuation and 0xc0 != 0x80) {
                throw DictionaryValidationException("word $index: not valid UTF-8")
            }
            val codePoint = ((lead and 0x1f) shl 6) or (continuation and 0x3f)
            if (codePoint >= ALPHABET_TABLE_SIZE || !ALPHABET_TABLE[codePoint]) {
                throw DictionaryValidationException("word $index: outside the Tatar alphabet")
            }
            offset += 2
        }
    }

    private fun compareUnsigned(
        first: ByteArray,
        firstLength: Int,
        second: ByteArray,
        secondLength: Int,
    ): Int {
        val count = minOf(firstLength, secondLength)
        for (index in 0 until count) {
            val difference = (first[index].toInt() and 0xff) -
                (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return firstLength - secondLength
    }

    private fun checkedAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (error: ArithmeticException) {
        throw DictionaryValidationException("section arithmetic overflow", error)
    }

    private fun checkedMultiply(first: Long, second: Long): Long = try {
        Math.multiplyExact(first, second)
    } catch (error: ArithmeticException) {
        throw DictionaryValidationException("section arithmetic overflow", error)
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_CANONICAL_WORD_BYTES = 64 * 2
        private const val ALPHABET = "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя"
        private const val ALPHABET_TABLE_SIZE = 0x500
        private val ALPHABET_TABLE = BooleanArray(ALPHABET_TABLE_SIZE).also { table ->
            ALPHABET.forEach { letter -> table[letter.code] = true }
        }
    }
}

/**
 * Reads exactly [length] bytes of [file]; null when the file is shorter or longer, that is, when
 * it changed after its length was checked.
 */
internal fun readExactly(file: File, length: Long): ByteArray? {
    val bytes = ByteArray(length.toInt())
    FileInputStream(file).use { stream ->
        var offset = 0
        while (offset < bytes.size) {
            val count = stream.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            offset += count
        }
        if (stream.read() != -1) return null
    }
    return bytes
}

/** SHA-256 of [bytes] with the [size]-byte window at [offset] read as zeros, without a copy. */
internal fun zeroedWindowDigest(bytes: ByteArray, offset: Int, size: Int): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes, 0, offset)
    digest.update(ByteArray(size))
    digest.update(bytes, offset + size, bytes.size - offset - size)
    return digest.digest()
}

internal fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

internal fun constantTimeHexEquals(first: String, second: String): Boolean =
    MessageDigest.isEqual(
        first.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII),
        second.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII),
    )
