package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

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
 * Predicts up to three successor words for an exact, already-normalized context word — the E5c
 * read side of the TATBIGR table (`docs/DICTIONARY-E5B.md`; schema 3 since SIZE-2,
 * `docs/SIZE-SCHEMA3.md`).
 *
 * Kept as a SEPARATE interface from [PrefixComputer] rather than widening its `lookup` signature:
 * PROPOSALS.md ("E5c. Вид запроса") says the owner of state must know which kind of result it
 * holds so it can choose the right commit path, and a bare `List<String>` cannot carry that on
 * its own — the same reasoning [ClassifiedPrefixComputer] and [KeyNeighborSink] already apply by
 * staying separate interfaces instead of reshaping the frozen one.
 */
internal fun interface NextWordComputer {
    fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String>
}

/**
 * The slice of the shipped dictionary a TATBIGR schema-3 table needs: exact-word index lookup and
 * word-by-index resolution (SIZE-2, `docs/SIZE-SCHEMA3.md`). Schema 3 stores NO words of its own —
 * heads and successes are indices into the linked dictionary, valid only against the dictionary
 * whose raw SHA-256 the table header names. [TdictPrefixIndex] is the only production
 * implementation; both methods are worker-confined exactly like the rest of that class (a bigram
 * predict runs on the same serialized engine worker as prefix lookup).
 */
internal interface BigramDictionary {
    val entryCount: Int
    val rawSha256: String

    /** Index of the exact word, or -1 when the dictionary does not contain it. */
    fun indexOfWord(query: ByteArray, queryLength: Int): Int

    fun wordAt(index: Int): String
}

/**
 * Zero-allocation reader for one mapped TATBIGR schema-3 file, mirroring [TdictPrefixIndex] in
 * every way that matters: [open] re-validates the structural invariants a lookup depends on
 * (canonical section arithmetic, strictly increasing head indices, minimal varints, stream
 * boundaries, the dictionary link) instead of trusting that `TatBigrValidator` was the only
 * thing ever standing between this buffer and disk; [predict] allocates nothing except the
 * decoded result strings themselves.
 *
 * The read is: resolve the context word to its dictionary index (one exact binary search in the
 * dictionary), binary search the head-block index for the last block whose first dictionary index
 * is ≤ the query's, stream-decode the block's delta-varint head indices (≤ [HEAD_BLOCK_SIZE] - 1
 * varints) to find the head, then skip/decode u8-counted varint success ids inside the same block
 * and resolve them through the dictionary — at most `min(3, count)` strings, in the packing order
 * the generator already fixed (count descending, tie code-point ascending). No re-ranking happens
 * here.
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
) : NextWordComputer {

    private val queryScratch = ByteArray(MAX_WORD_BYTES)
    private var varintValue = 0
    private var varintNext = 0

    override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
        val length = normalizedContextWordUtf8.byteCount
        if (length == 0 || length > MAX_WORD_BYTES || !isValidUtf8Scalar(normalizedContextWordUtf8)) {
            return emptyList()
        }
        return try {
            for (offset in 0 until length) {
                queryScratch[offset] = normalizedContextWordUtf8.byteAt(offset).toByte()
            }
            val queryIndex = dictionary.indexOfWord(queryScratch, length)
            if (queryIndex < 0) return emptyList()

            // Last block whose first head index is <= the query's: heads are a subset of the
            // dictionary in the same ascending order, so dictionary indices compare, not strings.
            var low = 0
            var high = blockCount
            while (low < high) {
                val middle = (low + high) ushr 1
                if (blockFirstIndex(middle) <= queryIndex) low = middle + 1 else high = middle
            }
            val block = low - 1
            if (block < 0) return emptyList()

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
                if (index > queryIndex) return emptyList()
            }
            if (found < 0) return emptyList()
            val head = first + found

            var successCursor = successIdsOffset + blockSuccessOffset(block)
            for (previous in first until head) {
                repeat(countAt(previous)) {
                    decodeVarint(successCursor)
                    successCursor = varintNext
                }
            }
            val count = minOf(MAX_RESULTS, countAt(head))
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
        // Three strip cells again (2026-09-29 revert, SuggestionStripState.CELL_COUNT): the read
        // caps at three even though the shipped Tatar table is packed at K = 4 — its fourth
        // successor is unread headroom, free for a future four-cell revisit.
        internal const val MAX_RESULTS = 3
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

            // Re-validate every invariant the reads above depend on for correctness (not merely
            // for memory safety) — the same defensive posture as TdictPrefixIndex.open, applied
            // to the cross-referenced layout: block records, the whole head delta stream, the
            // counts and every success id, checked against the real dictionary's entry count.
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
