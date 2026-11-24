package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** A fully validated raw image; its sections remain available to a stem reader. */
class ValidatedStemLexicon internal constructor(
    internal val raw: ByteArray,
    val stemCount: Int,
    val blockCount: Int,
    val blockIndexOffset: Int,
    val blocksOffset: Int,
)

/** Bounded zlib inflation and full structural validation for TATSTEM schema 1. */
class TatStemValidator {
    fun inflateAsset(
        source: InputStream,
        spec: StemLexiconArtifactSpec,
    ): ValidatedStemLexicon {
        val compressed = readCompressed(source, spec.expectedCompressedSize)
        val compressedSha = MessageDigest.getInstance("SHA-256").digest(compressed).toHex()
        if (!constantTimeHexEquals(compressedSha, spec.expectedCompressedSha256)) {
            throw StemLexiconValidationException("unexpected compressed SHA-256")
        }
        return validateRaw(inflate(compressed, spec), spec)
    }

    /** Checks pins, source identities, checksum, every block, word and metadata flag. */
    fun validateRaw(
        raw: ByteArray,
        spec: StemLexiconArtifactSpec,
    ): ValidatedStemLexicon {
        if (raw.size > spec.maxRawSize || raw.size > TatStemFormat.MAX_RAW_SIZE) {
            throw StemLexiconValidationException("raw size limit exceeded")
        }
        if (raw.size < TatStemFormat.HEADER_SIZE) {
            throw StemLexiconValidationException("raw lexicon is shorter than its header")
        }
        if (raw.size.toLong() != spec.expectedRawSize) {
            throw StemLexiconValidationException("unexpected raw size")
        }
        val header = decodeHeader(raw, spec)
        val storedChecksum = raw.copyOfRange(
            TatStemFormat.CHECKSUM_OFFSET, TatStemFormat.HEADER_SIZE
        )
        val expectedChecksum = zeroedWindowDigest(
            raw, TatStemFormat.CHECKSUM_OFFSET, TatStemFormat.CHECKSUM_SIZE
        )
        if (!MessageDigest.isEqual(storedChecksum, expectedChecksum)) {
            throw StemLexiconValidationException("stem lexicon checksum mismatch")
        }
        val rawSha = MessageDigest.getInstance("SHA-256").digest(raw).toHex()
        if (!constantTimeHexEquals(rawSha, spec.expectedRawSha256)) {
            throw StemLexiconValidationException("unexpected raw SHA-256")
        }
        validateSourceIdentity(raw, TatStemFormat.DICTIONARY_SHA_OFFSET,
            spec.expectedDictionaryRawSha256, "dictionary")
        validateSourceIdentity(raw, TatStemFormat.APERTIUM_SHA_OFFSET,
            spec.expectedApertiumLexiconSha256, "Apertium")
        walkBlocks(raw, header)
        return ValidatedStemLexicon(
            raw, header.stemCount.toInt(), header.blockCount.toInt(),
            TatStemFormat.HEADER_SIZE, header.blocksOffset.toInt(),
        )
    }

    private fun decodeHeader(
        raw: ByteArray,
        spec: StemLexiconArtifactSpec,
    ): Header {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buffer.get(magic)
        if (!magic.contentEquals(TatStemFormat.MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw StemLexiconValidationException("wrong stem lexicon magic")
        }
        val schema = buffer.short.toInt() and 0xffff
        val version = buffer.short.toInt() and 0xffff
        val headerSize = buffer.short.toInt() and 0xffff
        val checksumAlgorithm = buffer.short.toInt() and 0xffff
        if (schema != TatStemFormat.SCHEMA_ID || schema != spec.schemaId) {
            throw StemLexiconValidationException("unsupported schema id")
        }
        if (version != TatStemFormat.FORMAT_VERSION || version != spec.formatVersion) {
            throw StemLexiconValidationException("unsupported format version")
        }
        if (headerSize != TatStemFormat.HEADER_SIZE ||
            checksumAlgorithm != TatStemFormat.CHECKSUM_ALGORITHM_SHA256
        ) {
            throw StemLexiconValidationException("unsupported stem lexicon header")
        }
        val stemCount = buffer.u32(TatStemFormat.STEM_COUNT_OFFSET)
        val blockCount = buffer.u32(TatStemFormat.BLOCK_COUNT_OFFSET)
        val blockIndexOffset = buffer.u32(TatStemFormat.BLOCK_INDEX_OFFSET_OFFSET)
        val blocksOffset = buffer.u32(TatStemFormat.BLOCKS_OFFSET_OFFSET)
        val blocksSize = buffer.u32(TatStemFormat.BLOCKS_SIZE_OFFSET)
        val fileSize = buffer.u32(TatStemFormat.FILE_SIZE_OFFSET)
        if (stemCount == 0L || stemCount > TatStemFormat.MAX_STEMS ||
            stemCount != spec.expectedStemCount
        ) {
            throw StemLexiconValidationException("unexpected stem count")
        }
        val expectedBlocks = (stemCount + TatStemFormat.BLOCK_SIZE - 1) /
            TatStemFormat.BLOCK_SIZE
        val expectedBlocksOffset = TatStemFormat.HEADER_SIZE + 4 * expectedBlocks
        if (blockCount != expectedBlocks ||
            blockIndexOffset != TatStemFormat.HEADER_SIZE.toLong() ||
            blocksOffset != expectedBlocksOffset || blocksSize == 0L ||
            blocksOffset + blocksSize != fileSize || fileSize != raw.size.toLong()
        ) {
            throw StemLexiconValidationException("noncanonical stem lexicon sections")
        }
        return Header(stemCount, blockCount, blocksOffset)
    }

    private fun validateSourceIdentity(
        raw: ByteArray, offset: Int, expected: String, name: String,
    ) {
        val actual = raw.copyOfRange(offset, offset + 32).toHex()
        if (!constantTimeHexEquals(actual, expected)) {
            throw StemLexiconValidationException("unexpected $name source identity")
        }
    }

    private fun walkBlocks(raw: ByteArray, header: Header) {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        var previousOffset = -1
        var word = ByteArray(TatStemFormat.MAX_STEM_BYTES)
        var previousWord = ByteArray(TatStemFormat.MAX_STEM_BYTES)
        var previousLength = -1
        val codePoints = IntArray(TatStemFormat.MAX_STEM_CODE_POINTS)
        var decodedCount = 0
        for (block in 0 until header.blockCount.toInt()) {
            val blockStart = buffer.getInt(
                TatStemFormat.HEADER_SIZE + block * 4
            )
            if (blockStart < 0 || blockStart <= previousOffset ||
                blockStart >= raw.size
            ) {
                throw StemLexiconValidationException("block offsets are not strictly increasing")
            }
            if (block == 0 && blockStart != header.blocksOffset.toInt()) {
                throw StemLexiconValidationException("first block is outside the block section")
            }
            previousOffset = blockStart
            val next = if (block + 1 < header.blockCount.toInt()) {
                buffer.getInt(TatStemFormat.HEADER_SIZE + (block + 1) * 4)
            } else {
                raw.size
            }
            if (next <= blockStart || next > raw.size) {
                throw StemLexiconValidationException("invalid block boundary")
            }
            val inBlock = minOf(
                TatStemFormat.BLOCK_SIZE,
                header.stemCount.toInt() - block * TatStemFormat.BLOCK_SIZE,
            )
            var cursor = blockStart
            val firstLength = raw[cursor++].toInt() and 0xff
            if (firstLength == 0 || firstLength > TatStemFormat.MAX_STEM_BYTES ||
                cursor + firstLength > next
            ) {
                throw StemLexiconValidationException("invalid block-head stem")
            }
            val firstStart = cursor
            cursor += firstLength
            for (item in 0 until inBlock) {
                val wordLength: Int
                if (item == 0) {
                    raw.copyInto(word, 0, firstStart, firstStart + firstLength)
                    wordLength = firstLength
                } else {
                    val prefix = readCanonicalVarint(raw, cursor, next)
                    cursor = varintEnd
                    if (prefix > firstLength || cursor >= next) {
                        throw StemLexiconValidationException("invalid front-coded prefix")
                    }
                    val suffixLength = raw[cursor++].toInt() and 0xff
                    if (suffixLength == 0 ||
                        prefix + suffixLength > TatStemFormat.MAX_STEM_BYTES ||
                        cursor + suffixLength > next
                    ) {
                        throw StemLexiconValidationException("invalid front-coded suffix")
                    }
                    raw.copyInto(word, 0, firstStart, firstStart + prefix)
                    raw.copyInto(word, prefix, cursor, cursor + suffixLength)
                    cursor += suffixLength
                    wordLength = prefix + suffixLength
                }
                if (cursor >= next) {
                    throw StemLexiconValidationException("missing stem flags")
                }
                val flags = raw[cursor++].toInt() and 0xff
                val codePointCount = decodeWord(word, wordLength, codePoints)
                validateFlags(codePoints, codePointCount, flags)
                if (previousLength >= 0 &&
                    compareUnsigned(previousWord, previousLength, word, wordLength) >= 0
                ) {
                    throw StemLexiconValidationException("stems are not strictly increasing")
                }
                val swap = previousWord
                previousWord = word
                word = swap
                previousLength = wordLength
                decodedCount++
            }
            if (cursor != next) {
                throw StemLexiconValidationException("trailing bytes in a stem block")
            }
        }
        if (decodedCount != header.stemCount.toInt()) {
            throw StemLexiconValidationException("stem count does not match the blocks")
        }
    }

    /** Strict UTF-8 plus the precomposed lowercase Tatar alphabet and hyphen rule. */
    private fun decodeWord(word: ByteArray, length: Int, codePoints: IntArray): Int {
        if (length == 0 || length > TatStemFormat.MAX_STEM_BYTES) {
            throw StemLexiconValidationException("invalid stem byte length")
        }
        var offset = 0
        var count = 0
        var previousWasLetter = false
        fun append(codePoint: Int) {
            if (count >= codePoints.size) {
                throw StemLexiconValidationException("stem is too long")
            }
            codePoints[count++] = codePoint
        }
        while (offset < length) {
            val lead = word[offset].toInt() and 0xff
            if (lead == HYPHEN) {
                if (!previousWasLetter || offset + 2 >= length) {
                    throw StemLexiconValidationException("hyphen must join two letters")
                }
                val nextLead = word[offset + 1].toInt() and 0xff
                val nextContinuation = word[offset + 2].toInt() and 0xff
                if (nextLead < 0xc2 || nextLead > 0xdf ||
                    nextContinuation and 0xc0 != 0x80
                ) {
                    throw StemLexiconValidationException("invalid UTF-8 after hyphen")
                }
                val next = ((nextLead and 0x1f) shl 6) or (nextContinuation and 0x3f)
                if (!isAlphabet(next)) {
                    throw StemLexiconValidationException("hyphen must join two letters")
                }
                append(HYPHEN)
                append(next)
                offset += 3
                previousWasLetter = true
            } else {
                if (lead < 0xc2 || lead > 0xdf || offset + 1 >= length) {
                    throw StemLexiconValidationException("stem is outside the Tatar alphabet")
                }
                val continuation = word[offset + 1].toInt() and 0xff
                if (continuation and 0xc0 != 0x80) {
                    throw StemLexiconValidationException("stem is not valid UTF-8")
                }
                val codePoint = ((lead and 0x1f) shl 6) or (continuation and 0x3f)
                if (!isAlphabet(codePoint)) {
                    throw StemLexiconValidationException("stem is outside the Tatar alphabet")
                }
                append(codePoint)
                offset += 2
                previousWasLetter = true
            }
        }
        if (count < 2) {
            throw StemLexiconValidationException("stem is too short")
        }
        return count
    }

    private fun validateFlags(codePoints: IntArray, count: Int, flags: Int) {
        if (flags == 0 || flags and TatStemFormat.FLAG_KNOWN.inv() != 0) {
            throw StemLexiconValidationException("stem flags contain reserved bits")
        }
        val categories = flags and (TatStemFormat.FLAG_NOUN or TatStemFormat.FLAG_VERB)
        if (categories == 0) {
            throw StemLexiconValidationException("stem has no part of speech")
        }
        if (flags and TatStemFormat.FLAG_NOUN_CHAIN != 0 &&
            flags and TatStemFormat.FLAG_NOUN == 0 ||
            flags and TatStemFormat.FLAG_VERB_CHAIN != 0 &&
            flags and TatStemFormat.FLAG_VERB == 0
        ) {
            throw StemLexiconValidationException("continuation lacks its part of speech")
        }
        val harmony = flags and (TatStemFormat.FLAG_FRONT or TatStemFormat.FLAG_BACK)
        if (harmony != TatStemFormat.FLAG_FRONT && harmony != TatStemFormat.FLAG_BACK) {
            throw StemLexiconValidationException("stem must have exactly one harmony class")
        }
        val forced = flags and TatStemFormat.FLAG_FORCE_BACK != 0
        if (forced && (harmony != TatStemFormat.FLAG_BACK ||
                flags and (TatStemFormat.FLAG_NOUN_CHAIN or TatStemFormat.FLAG_VERB_CHAIN) == 0)
        ) {
            throw StemLexiconValidationException("invalid forced back harmony")
        }
        if (!forced && harmony != naturalHarmony(codePoints, count)) {
            throw StemLexiconValidationException("harmony differs from the stem spelling")
        }
    }

    private fun naturalHarmony(codePoints: IntArray, count: Int): Int {
        if (codePoints[count - 1] == 'ь'.code) return TatStemFormat.FLAG_FRONT
        for (index in count - 1 downTo 0) {
            val char = Char(codePoints[index])
            if (char in "уүюя" && index > 0 &&
                Char(codePoints[index - 1]) in VOWELS
            ) {
                continue
            }
            if (char in BACK_VOWELS) return TatStemFormat.FLAG_BACK
            if (char in FRONT_VOWELS) return TatStemFormat.FLAG_FRONT
        }
        throw StemLexiconValidationException("stem has no harmony vowel")
    }

    private fun inflate(compressed: ByteArray, spec: StemLexiconArtifactSpec): ByteArray {
        val output = ByteArray(spec.expectedRawSize.toInt())
        val inflater = Inflater(false)
        try {
            inflater.setInput(compressed)
            var offset = 0
            val probe = ByteArray(1)
            while (!inflater.finished()) {
                if (inflater.needsDictionary()) {
                    throw StemLexiconValidationException("zlib preset dictionary is unsupported")
                }
                if (inflater.needsInput()) {
                    throw StemLexiconValidationException("truncated zlib stream")
                }
                val count = try {
                    if (offset < output.size) {
                        inflater.inflate(output, offset, output.size - offset)
                    } else {
                        inflater.inflate(probe)
                    }
                } catch (error: DataFormatException) {
                    throw StemLexiconValidationException("invalid zlib stream", error)
                }
                if (count > 0) {
                    if (offset == output.size) {
                        throw StemLexiconValidationException("unexpected raw size")
                    }
                    offset += count
                } else if (!inflater.finished() && !inflater.needsInput() &&
                    !inflater.needsDictionary()
                ) {
                    throw StemLexiconValidationException("zlib inflater made no progress")
                }
            }
            if (inflater.remaining != 0) {
                throw StemLexiconValidationException("trailing or concatenated zlib data")
            }
            if (offset != output.size) {
                throw StemLexiconValidationException("unexpected raw size")
            }
            return output
        } finally {
            inflater.end()
        }
    }

    private fun readCompressed(input: InputStream, expectedSize: Long): ByteArray {
        try {
            val buffer = ByteArray(expectedSize.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) break
                offset += count
            }
            if (offset != buffer.size || input.read() != -1) {
                throw StemLexiconValidationException("unexpected compressed size")
            }
            return buffer
        } catch (error: StemLexiconValidationException) {
            throw error
        } catch (error: Exception) {
            throw StemLexiconValidationException("unreadable asset stream", error)
        }
    }

    private fun readCanonicalVarint(raw: ByteArray, offset: Int, limit: Int): Int {
        var value = 0
        var shift = 0
        var cursor = offset
        while (true) {
            if (cursor >= limit) {
                throw StemLexiconValidationException("truncated varint")
            }
            val byte = raw[cursor++].toInt() and 0xff
            if (shift == 28 && byte > 0x0f) {
                throw StemLexiconValidationException("varint exceeds u32")
            }
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        var minimal = 1
        var rest = value ushr 7
        while (rest != 0) {
            minimal++
            rest = rest ushr 7
        }
        if (cursor - offset != minimal) {
            throw StemLexiconValidationException("non-minimal varint")
        }
        varintEnd = cursor
        return value
    }

    private fun compareUnsigned(
        first: ByteArray, firstLength: Int, second: ByteArray, secondLength: Int,
    ): Int {
        val count = minOf(firstLength, secondLength)
        for (index in 0 until count) {
            val difference = (first[index].toInt() and 0xff) -
                (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return firstLength - secondLength
    }

    private fun isAlphabet(codePoint: Int): Boolean =
        codePoint < ALPHABET_TABLE.size && ALPHABET_TABLE[codePoint]

    private fun ByteBuffer.u32(offset: Int): Long =
        getInt(offset).toLong() and 0xffff_ffffL

    private class Header(val stemCount: Long, val blockCount: Long, val blocksOffset: Long)

    private var varintEnd = 0

    companion object {
        private const val HYPHEN = 0x2d
        private const val BACK_VOWELS = "аыоуюя"
        private const val FRONT_VOWELS = "әеиөүэ"
        private const val VOWELS = "аыоуюяәеиөүэ"
        private const val ALPHABET = "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя"
        private val ALPHABET_TABLE = BooleanArray(0x500).also { table ->
            ALPHABET.forEach { letter -> table[letter.code] = true }
        }
    }
}
