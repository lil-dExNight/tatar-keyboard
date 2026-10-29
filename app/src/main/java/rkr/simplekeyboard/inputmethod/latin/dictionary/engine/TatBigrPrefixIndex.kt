package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StoreValidationToken
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BigramTableIdentity(
    val generation: Int,
    val languageTag: String,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

/**
 * Predicts up to four next words for an exact, already-normalized context word (the read side of
 * the TATBIGR bigram table).
 *
 * A separate interface from [PrefixComputer] rather than a wider `lookup` signature: the state
 * owner must know which kind of result it holds to choose the right commit path, and a bare
 * `List<String>` cannot carry that. [ClassifiedPrefixComputer] and [KeyNeighborSink] stay
 * separate for the same reason.
 */
internal fun interface NextWordComputer {
    fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String>
}

/**
 * The part of the bundled dictionary a TATBIGR schema-3 table needs: exact-word index lookup and
 * word-by-index resolution. Schema 3 stores no words of its own: heads and successors are indices
 * into the linked dictionary, valid only against the dictionary whose raw SHA-256 the table header
 * names. [TdictPrefixIndex] is the only production implementation; both methods are
 * worker-confined like the rest of that class.
 */
internal interface BigramDictionary {
    val entryCount: Int
    val rawSha256: String

    /** Index of the exact word, or -1 when the dictionary does not contain it. */
    fun indexOfWord(query: ByteArray, queryLength: Int): Int

    fun wordAt(index: Int): String
}

/**
 * The packed bigram successors of a context word as raw dictionary entry indices, for the prefix
 * path's context boost: the membership test there compares entry indices, so the ids are never
 * resolved to strings. The ids are written into the caller's [sink] in packing order; nothing is
 * allocated. Worker-confined like [NextWordComputer.predict].
 */
internal fun interface PackedSuccessorSource {
    /**
     * Writes the context word's packed successor entry indices into [sink], at most
     * [TatBigrPrefixIndex.MAX_RESULTS], and returns their count; 0 when the word heads no entry
     * or the read fails.
     */
    fun collectSuccessorIds(normalizedContextWordUtf8: ImmutableUtf8Prefix, sink: IntArray): Int
}

/**
 * Zero-allocation reader for one mapped TATBIGR schema-3 file, like [TdictPrefixIndex]: [open]
 * re-validates the structural invariants a lookup depends on (section arithmetic, strictly
 * increasing head indices, minimal varints, stream boundaries, the dictionary link) instead of
 * trusting `TatBigrValidator`, and skips the stream walk when a store validation token for the
 * same content matches (see [StoreValidationToken]); [predict] allocates only the result strings.
 *
 * The read: resolve the context word to its dictionary index (one binary search), binary search
 * the head-block index for the last block whose first index is ≤ the query's, stream-decode the
 * block's delta-varint head indices (≤ [HEAD_BLOCK_SIZE] - 1 varints) to find the head, then
 * skip/decode the u8-counted varint successor ids in the same block and resolve them through the
 * dictionary: at most [MAX_RESULTS] strings, in packing order (count descending, ties by code
 * point ascending). No re-ranking happens here.
 */
internal class TatBigrPrefixIndex private constructor(
    private val bytes: ByteBuffer,
    val identity: BigramTableIdentity,
    private val dictionary: BigramDictionary,
    private val headCount: Int,
    private val blockCount: Int,
    private val blockIndexOffset: Int,
    private val headDeltasOffset: Int,
    private val countsOffset: Int,
    private val successIdsOffset: Int,
) : NextWordComputer, PackedSuccessorSource {

    private val queryScratch = ByteArray(MAX_WORD_BYTES)
    private var varintValue = 0
    private var varintNext = 0
    // Worker-confined results of [resolveHeadSuccessors]: the stream position of the resolved
    // head's packed successor ids and that head's stored count. Valid until the next resolution.
    private var headSuccessCursor = 0
    private var headStoredCount = 0

    override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
        if (!resolveHeadSuccessors(normalizedContextWordUtf8)) return emptyList()
        return try {
            val count = minOf(MAX_RESULTS, headStoredCount)
            var successCursor = headSuccessCursor
            ArrayList<String>(count).also { result ->
                repeat(count) {
                    decodeVarint(successCursor)
                    successCursor = varintNext
                    result += dictionary.wordAt(varintValue)
                }
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    override fun collectSuccessorIds(
        normalizedContextWordUtf8: ImmutableUtf8Prefix,
        sink: IntArray,
    ): Int {
        if (!resolveHeadSuccessors(normalizedContextWordUtf8)) return 0
        return try {
            val count = minOf(minOf(MAX_RESULTS, headStoredCount), sink.size)
            var successCursor = headSuccessCursor
            for (slot in 0 until count) {
                decodeVarint(successCursor)
                successCursor = varintNext
                sink[slot] = varintValue
            }
            count
        } catch (_: RuntimeException) {
            0
        }
    }

    /**
     * The shared walk of [predict] and [collectSuccessorIds]: resolves the context word to its
     * head and positions [headSuccessCursor]/[headStoredCount] at its packed successor ids.
     * False when the word heads no entry, and on any failure (a broken table answers empty).
     */
    private fun resolveHeadSuccessors(normalizedContextWordUtf8: ImmutableUtf8Prefix): Boolean {
        val length = normalizedContextWordUtf8.byteCount
        if (length == 0 || length > MAX_WORD_BYTES || !isValidUtf8Scalar(normalizedContextWordUtf8)) {
            return false
        }
        return try {
            for (offset in 0 until length) {
                queryScratch[offset] = normalizedContextWordUtf8.byteAt(offset).toByte()
            }
            val queryIndex = dictionary.indexOfWord(queryScratch, length)
            if (queryIndex < 0) return false

            // Last block whose first head index is <= the query's: heads are a subset of the
            // dictionary in the same ascending order, so dictionary indices compare, not strings.
            var low = 0
            var high = blockCount
            while (low < high) {
                val middle = (low + high) ushr 1
                if (blockFirstIndex(middle) <= queryIndex) low = middle + 1 else high = middle
            }
            val block = low - 1
            if (block < 0) return false

            val first = block * HEAD_BLOCK_SIZE
            val blockHeads = minOf(HEAD_BLOCK_SIZE, headCount - first)
            var cursor = headDeltasOffset + blockDeltaOffset(block)
            var index = blockFirstIndex(block)
            var found = -1
            for (position in 0 until blockHeads) {
                if (position > 0) {
                    decodeVarint(cursor)
                    cursor = varintNext
                    index += varintValue
                }
                if (index == queryIndex) {
                    found = position
                    break
                }
                if (index > queryIndex) return false
            }
            if (found < 0) return false
            val head = first + found

            var successCursor = successIdsOffset + blockSuccessOffset(block)
            for (previous in first until head) {
                repeat(countAt(previous)) {
                    decodeVarint(successCursor)
                    successCursor = varintNext
                }
            }
            headSuccessCursor = successCursor
            headStoredCount = countAt(head)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun blockFirstIndex(block: Int): Int =
        bytes.getInt(blockIndexOffset + block * BLOCK_RECORD_BYTES)

    private fun blockDeltaOffset(block: Int): Int =
        bytes.getInt(blockIndexOffset + block * BLOCK_RECORD_BYTES + U32_BYTES)

    private fun blockSuccessOffset(block: Int): Int =
        bytes.getInt(blockIndexOffset + block * BLOCK_RECORD_BYTES + 2 * U32_BYTES)

    private fun countAt(head: Int): Int = bytes.get(countsOffset + head).toInt() and 0xff

    /** Canonical minimal-form u32 varint; the answer lands in [varintValue]/[varintNext]. */
    private fun decodeVarint(offset: Int) {
        var cursor = offset
        var value = 0
        var shift = 0
        var lastGroup = 0
        while (true) {
            val byte = bytes.get(cursor).toInt() and 0xff
            cursor++
            if (shift == 28 && byte > 0x0f) throw IllegalArgumentException("varint exceeds u32")
            value = value or ((byte and 0x7f) shl shift)
            lastGroup = byte and 0x7f
            if (byte and 0x80 == 0) break
            shift += 7
        }
        // Canonical form: a multi-byte varint may not end in a zero group.
        if (shift > 0 && lastGroup == 0) throw IllegalArgumentException("overlong varint")
        varintValue = value
        varintNext = cursor
    }

    companion object {
        private const val HEADER_SIZE = 128
        private const val CHECKSUM_ALGORITHM_SHA256 = 1
        private const val DICTIONARY_SHA_OFFSET = 56
        private const val RESERVED_OFFSET = 88
        private const val SHA256_BYTES = 32
        private const val U32_BYTES = 4
        private const val BLOCK_RECORD_BYTES = 12
        private const val HEAD_BLOCK_SIZE = 64
        // One per strip cell (SuggestionStripState.CELL_COUNT). The Tatar table is packed with up
        // to four successors per head; all four are read.
        internal const val MAX_RESULTS = 4
        internal const val MAX_WORD_BYTES = 128
        private const val MAX_U32 = 0xffff_ffffL
        private const val HEX_DIGITS = "0123456789abcdef"
        private val MAGIC = "TATBIGR\u0000".toByteArray(Charsets.US_ASCII)

        fun open(
            source: ByteBuffer,
            identity: BigramTableIdentity,
            dictionary: BigramDictionary,
            expectedHeadCount: Long,
            expectedRawSize: Long,
            // A live store token for exactly this content skips the stream walk below; the header
            // and block-record checks always run. See [StoreValidationToken].
            validationToken: StoreValidationToken? = null,
        ): TatBigrPrefixIndex? = try {
            require(identity.generation > 0)
            require(identity.schemaId == 3)
            require(identity.formatVersion == 1)
            require(expectedHeadCount in 1..Int.MAX_VALUE.toLong())
            require(expectedRawSize in HEADER_SIZE.toLong()..Int.MAX_VALUE.toLong())
            require(source.limit().toLong() == expectedRawSize)

            val duplicate = source.asReadOnlyBuffer()
            duplicate.position(0)
            val buffer = duplicate.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= HEADER_SIZE)
            for (index in MAGIC.indices) require(buffer.get(index) == MAGIC[index])
            require(u16(buffer, 8) == identity.schemaId)
            require(u16(buffer, 10) == identity.formatVersion)
            require(u16(buffer, 12) == HEADER_SIZE)
            require(u16(buffer, 14) == CHECKSUM_ALGORITHM_SHA256)

            val headCount = u32(buffer, 16)
            val pairCount = u32(buffer, 20)
            val blockCount = u32(buffer, 24)
            val blockIndexOffset = u32(buffer, 28)
            val headDeltasOffset = u32(buffer, 32)
            val headDeltasSize = u32(buffer, 36)
            val countsOffset = u32(buffer, 40)
            val successIdsOffset = u32(buffer, 44)
            val successIdsSize = u32(buffer, 48)
            val fileSize = u32(buffer, 52)

            // The dictionary link: the table names the exact dictionary it was packed against,
            // and only that dictionary may resolve its indices.
            val namedDictionarySha = StringBuilder(SHA256_BYTES * 2)
            for (index in 0 until SHA256_BYTES) {
                val byte = buffer.get(DICTIONARY_SHA_OFFSET + index).toInt() and 0xff
                namedDictionarySha.append(HEX_DIGITS[byte ushr 4]).append(HEX_DIGITS[byte and 0xf])
            }
            require(namedDictionarySha.toString() == dictionary.rawSha256.lowercase())
            for (index in 0 until 8) require(buffer.get(RESERVED_OFFSET + index).toInt() == 0)

            require(headCount == expectedHeadCount)
            require(blockCount == (headCount + HEAD_BLOCK_SIZE - 1L) / HEAD_BLOCK_SIZE)
            val expectedBlockIndex = HEADER_SIZE.toLong()
            val expectedHeadDeltas = expectedBlockIndex + BLOCK_RECORD_BYTES * blockCount
            val expectedCounts = expectedHeadDeltas + headDeltasSize
            val expectedSuccessIds = expectedCounts + headCount
            val expectedFileSize = expectedSuccessIds + successIdsSize
            require(blockIndexOffset == expectedBlockIndex)
            require(headDeltasOffset == expectedHeadDeltas)
            require(countsOffset == expectedCounts)
            require(successIdsOffset == expectedSuccessIds)
            require(fileSize == expectedFileSize && fileSize == expectedRawSize)
            require(expectedFileSize <= Int.MAX_VALUE)
            require(pairCount <= Int.MAX_VALUE)

            val index = TatBigrPrefixIndex(
                buffer,
                identity,
                dictionary,
                headCount.toInt(),
                blockCount.toInt(),
                blockIndexOffset.toInt(),
                headDeltasOffset.toInt(),
                countsOffset.toInt(),
                successIdsOffset.toInt(),
            )

            val skipStructuralWalk =
                StoreValidationToken.matches(validationToken, identity.rawSha256, expectedRawSize)
            // The block records, checked as in TdictPrefixIndex.open: cheap, always re-run.
            var previousFirstIndex = -1L
            var previousDeltaOffset = 0L
            var previousSuccessOffset = 0L
            for (block in 0 until index.blockCount) {
                val firstIndex = index.blockFirstIndex(block).toLong() and MAX_U32
                val deltaOffset = index.blockDeltaOffset(block).toLong() and MAX_U32
                val successOffset = index.blockSuccessOffset(block).toLong() and MAX_U32
                require(firstIndex < dictionary.entryCount)
                require(firstIndex > previousFirstIndex)
                require(deltaOffset <= headDeltasSize && deltaOffset >= previousDeltaOffset)
                require(successOffset <= successIdsSize && successOffset >= previousSuccessOffset)
                if (block == 0) require(deltaOffset == 0L && successOffset == 0L)
                previousFirstIndex = firstIndex
                previousDeltaOffset = deltaOffset
                previousSuccessOffset = successOffset
            }

            // The stream walk: the whole head delta stream, the counts and every successor id,
            // checked against the dictionary's entry count. A matched token stands for the
            // store's validation of the same content, which already proved all of it.
            if (!skipStructuralWalk) {
                var successCursor = successIdsOffset.toInt()
                var pairTotal = 0L
                var previousHeadIndex = -1
                for (block in 0 until index.blockCount) {
                    val first = block * HEAD_BLOCK_SIZE
                    val blockHeads = minOf(HEAD_BLOCK_SIZE, index.headCount - first)
                    val deltaEnd = index.headDeltasOffset + (
                        if (block + 1 < index.blockCount) index.blockDeltaOffset(block + 1)
                        else headDeltasSize.toInt()
                        )
                    require(successCursor.toLong() == successIdsOffset + index.blockSuccessOffset(block))

                    var cursor = index.headDeltasOffset + index.blockDeltaOffset(block)
                    var headIndex = index.blockFirstIndex(block).toLong() and MAX_U32
                    require(headIndex > previousHeadIndex.toLong() && headIndex < dictionary.entryCount)
                    for (position in 0 until blockHeads) {
                        if (position > 0) {
                            index.decodeVarint(cursor)
                            require(index.varintNext <= deltaEnd)
                            val delta = index.varintValue.toLong() and MAX_U32
                            require(delta >= 1)
                            cursor = index.varintNext
                            headIndex += delta
                            require(headIndex < dictionary.entryCount)
                            require(headIndex > previousHeadIndex.toLong())
                        }
                        previousHeadIndex = headIndex.toInt()

                        val count = index.countAt(first + position)
                        require(count >= 1)
                        pairTotal += count
                        repeat(count) {
                            index.decodeVarint(successCursor)
                            successCursor = index.varintNext
                            require(index.varintValue.toLong() and MAX_U32 < dictionary.entryCount)
                        }
                    }
                    require(cursor == deltaEnd)
                }
                require(pairTotal == pairCount)
                require(successCursor.toLong() == successIdsOffset + successIdsSize)
            }

            index
        } catch (_: RuntimeException) {
            null
        }

        private fun u16(buffer: ByteBuffer, offset: Int): Int =
            buffer.getShort(offset).toInt() and 0xffff

        private fun u32(buffer: ByteBuffer, offset: Int): Long =
            buffer.getInt(offset).toLong() and MAX_U32
    }
}
