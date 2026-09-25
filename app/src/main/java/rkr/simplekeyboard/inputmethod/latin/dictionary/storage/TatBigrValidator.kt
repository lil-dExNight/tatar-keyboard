package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class BigramValidationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class ValidatedBigramTable(
    val rawSize: Long,
    val headCount: Long,
    val pairCount: Long,
    val successVocabularyCount: Long,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

/**
 * Strict validator for the TATBIGR schema-3 format (SIZE-2, `docs/SIZE-SCHEMA3.md` — the
 * cross-referenced layout that replaced schema 2's six sections on 2026-09-01). Mirrors
 * [TdictValidator]'s two-phase shape (inflate-with-digest, then validate-the-decompressed
 * structure). The structural half reads the whole raw file into memory — bounded by
 * [TatBigrFormat.MAX_RAW_SIZE] — because the varint streams of schema 3 walk far more naturally
 * over a byte array than over a seeking file.
 *
 * What this layer CANNOT check is the one thing schema 2 checked in-file: that an id is below
 * the vocabulary size. Schema 3's vocabulary is the linked dictionary, named by raw SHA-256 in
 * the header; the validator checks the header names exactly the dictionary
 * [BigramArtifactSpec.expectedDictionaryRawSha256] pins, and the index-range checks against the
 * real dictionary's entry count happen in `TatBigrPrefixIndex.open`, which has the dictionary.
 */
class TatBigrValidator {
    fun inflateAsset(
        source: java.io.InputStream,
        destination: OutputStream,
        spec: BigramArtifactSpec,
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
                    throw BigramValidationException("zlib preset dictionary is unsupported")
                }
                if (inflater.needsInput()) {
                    val count = input.read(inputBuffer)
                    if (count < 0) {
                        throw BigramValidationException("truncated zlib stream")
                    }
                    compressedSize += count
                    if (compressedSize > spec.maxCompressedSize) {
                        throw BigramValidationException("compressed size limit exceeded")
                    }
                    compressedDigest.update(inputBuffer, 0, count)
                    inflater.setInput(inputBuffer, 0, count)
                }

                val count = try {
                    inflater.inflate(outputBuffer)
                } catch (error: DataFormatException) {
                    throw BigramValidationException("invalid zlib stream", error)
                }
                if (count > 0) {
                    rawSize += count
                    if (rawSize > spec.maxRawSize) {
                        throw BigramValidationException("raw size limit exceeded")
                    }
                    output.write(outputBuffer, 0, count)
                } else if (!inflater.finished() && !inflater.needsInput() &&
                    !inflater.needsDictionary()
                ) {
                    throw BigramValidationException("zlib inflater made no progress")
                }
            }

            if (inflater.remaining != 0 || input.read() != -1) {
                throw BigramValidationException("trailing or concatenated zlib data")
            }
            output.flush()
        } finally {
            inflater.end()
        }

        val compressedSha = compressedDigest.digest().toHex()
        if (compressedSize != spec.expectedCompressedSize) {
            throw BigramValidationException("unexpected compressed size")
        }
        if (!constantTimeHexEquals(compressedSha, spec.expectedCompressedSha256)) {
            throw BigramValidationException("unexpected compressed SHA-256")
        }
        if (rawSize != spec.expectedRawSize) {
            throw BigramValidationException("unexpected raw size")
        }
        return InflatedAsset(compressedSize, compressedSha, rawSize)
    }

    fun validateRaw(file: File, spec: BigramArtifactSpec): ValidatedBigramTable {
        val length = file.length()
        if (length > spec.maxRawSize) {
            throw BigramValidationException("raw size limit exceeded")
        }
        if (length < TatBigrFormat.HEADER_SIZE) {
            throw BigramValidationException("raw table is shorter than its header")
        }
        if (length != spec.expectedRawSize) {
            throw BigramValidationException("unexpected raw size")
        }
        val raw = FileInputStream(file).use { it.readBytes() }

        val magic = raw.copyOfRange(0, 8)
        if (!magic.contentEquals(TatBigrFormat.MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw BigramValidationException("wrong bigram table magic")
        }
        val schemaId = u16(raw, 8)
        val formatVersion = u16(raw, 10)
        val headerSize = u16(raw, 12)
        val checksumAlgorithm = u16(raw, 14)
        if (schemaId != spec.schemaId || schemaId != TatBigrFormat.SCHEMA_ID) {
            throw BigramValidationException("unsupported schema id")
        }
        if (formatVersion != spec.formatVersion || formatVersion != TatBigrFormat.FORMAT_VERSION) {
            throw BigramValidationException("unsupported format version")
        }
        if (headerSize != TatBigrFormat.HEADER_SIZE) {
            throw BigramValidationException("unexpected header size")
        }
        if (checksumAlgorithm != TatBigrFormat.CHECKSUM_ALGORITHM_SHA256) {
            throw BigramValidationException("unsupported checksum algorithm")
        }

        val headCount = u32(raw, 16)
        val pairCount = u32(raw, 20)
        val blockCount = u32(raw, 24)
        val blockIndexOffset = u32(raw, 28)
        val headDeltasOffset = u32(raw, 32)
        val headDeltasSize = u32(raw, 36)
        val countsOffset = u32(raw, 40)
        val successIdsOffset = u32(raw, 44)
        val successIdsSize = u32(raw, 48)
        val declaredFileSize = u32(raw, 52)
        val dictionarySha = raw.copyOfRange(56, 88).toHex()

        if (headCount == 0L || headCount != spec.expectedHeadCount) {
            throw BigramValidationException("unexpected head count")
        }
        if (!constantTimeHexEquals(dictionarySha, spec.expectedDictionaryRawSha256)) {
            // The schema-3 link: the table is valid only with the exact dictionary it names.
            throw BigramValidationException("unexpected dictionary SHA-256")
        }
        for (index in 88 until 96) {
            if (raw[index].toInt() != 0) {
                throw BigramValidationException("reserved header bytes are not zero")
            }
        }

        val expectedBlockCount = (headCount + TatBigrFormat.HEAD_BLOCK_SIZE - 1) / TatBigrFormat.HEAD_BLOCK_SIZE
        val expectedBlockIndex = TatBigrFormat.HEADER_SIZE.toLong()
        val expectedHeadDeltas = expectedBlockIndex + 12 * blockCount
        val expectedCounts = expectedHeadDeltas + headDeltasSize
        val expectedSuccessIds = expectedCounts + headCount
        val expectedFileSize = expectedSuccessIds + successIdsSize
        if (blockCount != expectedBlockCount ||
            blockIndexOffset != expectedBlockIndex || headDeltasOffset != expectedHeadDeltas ||
            countsOffset != expectedCounts || successIdsOffset != expectedSuccessIds ||
            declaredFileSize != expectedFileSize || declaredFileSize != length
        ) {
            throw BigramValidationException("noncanonical section layout")
        }

        val digests = calculateDigests(file)
        if (!MessageDigest.isEqual(
                raw.copyOfRange(TatBigrFormat.CHECKSUM_OFFSET, TatBigrFormat.CHECKSUM_OFFSET + 32),
                digests.zeroedChecksum,
            )
        ) {
            throw BigramValidationException("SHA-256 checksum mismatch")
        }
        val rawSha = digests.fullChecksum.toHex()
        if (!constantTimeHexEquals(rawSha, spec.expectedRawSha256)) {
            throw BigramValidationException("unexpected raw SHA-256")
        }

        // Structural walk: block records strictly increasing, head deltas >= 1 and minimal-form,
        // counts >= 1 summing to pairCount, success ids minimal-form, every stream ending exactly
        // on its block boundary. Index upper bounds are checked against the real dictionary in
        // TatBigrPrefixIndex.open — see the class KDoc.
        var previousFirstIndex = -1L
        var previousDeltaOffset = 0L
        var previousSuccessOffset = 0L
        for (block in 0 until blockCount.toInt()) {
            val firstIndex = u32(raw, (blockIndexOffset + 12 * block))
            val deltaOffset = u32(raw, (blockIndexOffset + 12 * block + 4))
            val successOffset = u32(raw, (blockIndexOffset + 12 * block + 8))
            if (firstIndex <= previousFirstIndex) {
                throw BigramValidationException("block first indices are not strictly increasing")
            }
            if (deltaOffset > headDeltasSize || deltaOffset < previousDeltaOffset ||
                successOffset > successIdsSize || successOffset < previousSuccessOffset
            ) {
                throw BigramValidationException("block stream offsets are not canonical")
            }
            if (block == 0 && (deltaOffset != 0L || successOffset != 0L)) {
                throw BigramValidationException("the first block's stream offsets must be zero")
            }
            previousFirstIndex = firstIndex
            previousDeltaOffset = deltaOffset
            previousSuccessOffset = successOffset
        }

        var successCursor = successIdsOffset.toInt()
        var pairTotal = 0L
        var previousHeadIndex = -1L
        val distinctSuccesses = HashSet<Long>()
        for (block in 0 until blockCount.toInt()) {
            val first = block * TatBigrFormat.HEAD_BLOCK_SIZE
            val blockHeads = minOf(TatBigrFormat.HEAD_BLOCK_SIZE.toLong(), headCount - first).toInt()
            val deltaEnd = (headDeltasOffset + if (block + 1 < blockCount) {
                u32(raw, blockIndexOffset + 12 * (block + 1) + 4)
            } else {
                headDeltasSize
            }).toInt()
            if (successCursor.toLong() != successIdsOffset + u32(raw, blockIndexOffset + 12 * block + 8)) {
                throw BigramValidationException("block success stream does not start on its boundary")
            }

            var cursor = (headDeltasOffset + u32(raw, blockIndexOffset + 12 * block + 4)).toInt()
            var headIndex = u32(raw, blockIndexOffset + 12 * block)
            if (headIndex <= previousHeadIndex) {
                throw BigramValidationException("head indices are not strictly increasing")
            }
            for (position in 0 until blockHeads) {
                if (position > 0) {
                    val packed = decodeVarint(raw, cursor, deltaEnd)
                    cursor = (packed and 0xffff_ffffL).toInt()
                    val delta = packed ushr 32
                    if (delta < 1) {
                        throw BigramValidationException("head index delta is not positive")
                    }
                    headIndex += delta
                    if (headIndex <= previousHeadIndex) {
                        throw BigramValidationException("head indices are not strictly increasing")
                    }
                }
                previousHeadIndex = headIndex

                val count = raw[(countsOffset + first + position).toInt()].toInt() and 0xff
                if (count < 1) {
                    throw BigramValidationException("a head has an empty success range")
                }
                pairTotal += count
                repeat(count) {
                    val packed = decodeVarint(
                        raw, successCursor, (successIdsOffset + successIdsSize).toInt(),
                    )
                    successCursor = (packed and 0xffff_ffffL).toInt()
                    distinctSuccesses.add(packed ushr 32)
                }
            }
            if (cursor != deltaEnd) {
                throw BigramValidationException("block head delta stream does not end on its boundary")
            }
        }
        if (pairTotal != pairCount) {
            throw BigramValidationException("success counts do not add up to pairCount")
        }
        if (successCursor.toLong() != successIdsOffset + successIdsSize) {
            throw BigramValidationException("success id stream does not end exactly at its section end")
        }

        return ValidatedBigramTable(
            rawSize = length,
            headCount = headCount,
            pairCount = pairCount,
            successVocabularyCount = distinctSuccesses.size.toLong(),
            schemaId = schemaId,
            formatVersion = formatVersion,
            rawSha256 = rawSha,
        )
    }

    /** Canonical minimal-form u32 varint; returns (value shl 32) or nextOffset. */
    private fun decodeVarint(raw: ByteArray, offset: Int, limit: Int): Long {
        var cursor = offset
        var value = 0L
        var shift = 0
        var lastGroup = 0
        while (true) {
            if (cursor >= limit) throw BigramValidationException("truncated varint")
            val byte = raw[cursor].toInt() and 0xff
            cursor++
            if (shift == 28 && byte > 0x0f) throw BigramValidationException("varint exceeds u32")
            value = value or ((byte and 0x7f).toLong() shl shift)
            lastGroup = byte and 0x7f
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (shift > 0 && lastGroup == 0) throw BigramValidationException("overlong varint")
        return (value shl 32) or cursor.toLong()
    }

    private fun u16(raw: ByteArray, offset: Long): Int =
        (raw[offset.toInt()].toInt() and 0xff) or ((raw[offset.toInt() + 1].toInt() and 0xff) shl 8)

    private fun u32(raw: ByteArray, offset: Long): Long =
        (raw[offset.toInt()].toLong() and 0xff) or ((raw[offset.toInt() + 1].toLong() and 0xff) shl 8) or
            ((raw[offset.toInt() + 2].toLong() and 0xff) shl 16) or
            ((raw[offset.toInt() + 3].toLong() and 0xff) shl 24)

    private fun calculateDigests(file: File): Digests {
        val full = MessageDigest.getInstance("SHA-256")
        val zeroed = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(BUFFER_SIZE)
        var absoluteOffset = 0L
        FileInputStream(file).use { stream ->
            while (true) {
                val count = stream.read(bytes)
                if (count < 0) break
                full.update(bytes, 0, count)
                val copy = bytes.copyOf(count)
                val zeroStart = maxOf(0L, TatBigrFormat.CHECKSUM_OFFSET - absoluteOffset).toInt()
                val zeroEnd = minOf(
                    count.toLong(),
                    TatBigrFormat.CHECKSUM_OFFSET + TatBigrFormat.CHECKSUM_SIZE - absoluteOffset,
                ).toInt()
                if (zeroStart < zeroEnd) copy.fill(0, zeroStart, zeroEnd)
                zeroed.update(copy)
                absoluteOffset += count
            }
        }
        return Digests(full.digest(), zeroed.digest())
    }

    private data class Digests(val fullChecksum: ByteArray, val zeroedChecksum: ByteArray)

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
    }
}
