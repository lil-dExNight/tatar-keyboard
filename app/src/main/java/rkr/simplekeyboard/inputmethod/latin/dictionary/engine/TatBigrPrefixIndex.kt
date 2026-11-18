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

/** Two-word NEXT_WORD lookup; an empty answer falls back to the one-word table. */
internal fun interface TwoWordNextWordComputer {
    fun predict(
        normalizedPreviousContextWordUtf8: ImmutableUtf8Prefix,
        normalizedContextWordUtf8: ImmutableUtf8Prefix,
    ): List<String>
}

private data class TrigramLayout(
    val contextCount: Int,
    val pairCount: Int,
    val blockCount: Int,
    val blockIndexOffset: Int,
    val deltasOffset: Int,
    val deltasSize: Int,
    val countsOffset: Int,
    val successIdsOffset: Int,
    val successIdsSize: Int,
)

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
     * Writes the context word's packed successor entry indices into [sink] — at most
     * [TatBigrPrefixIndex.MAX_CONTEXT_SUCCESSORS] of them, or [sink]'s size when smaller — and
     * returns their count; 0 when the word heads no entry or the read fails.
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
 * dictionary: [predict] answers at most [MAX_PREDICT_RESULTS] strings, [collectSuccessorIds]
 * writes at most [MAX_CONTEXT_SUCCESSORS] ids, both in packing order (count descending, ties by
 * code point ascending). No re-ranking happens here.
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
    private val trigram: TrigramLayout? = null,
) : NextWordComputer, PackedSuccessorSource, TwoWordNextWordComputer {

    private val queryScratch = ByteArray(MAX_WORD_BYTES)
    private val previousQueryScratch = ByteArray(MAX_WORD_BYTES)
    private var varintValue = 0
    private var varintLongValue = 0L
    private var varintNext = 0
    // Worker-confined results of [resolveHeadSuccessors]: the stream position of the resolved
    // head's packed successor ids and that head's stored count. Valid until the next resolution.
    private var headSuccessCursor = 0
    private var headStoredCount = 0
    private var trigramSuccessCursor = 0
    private var trigramStoredCount = 0

    override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
        if (!resolveHeadSuccessors(normalizedContextWordUtf8)) return emptyList()
        return try {
            val count = minOf(MAX_PREDICT_RESULTS, headStoredCount)
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

    override fun predict(
        normalizedPreviousContextWordUtf8: ImmutableUtf8Prefix,
        normalizedContextWordUtf8: ImmutableUtf8Prefix,
    ): List<String> {
        if (trigram == null || !resolveTrigramSuccessors(
                normalizedPreviousContextWordUtf8, normalizedContextWordUtf8
            )
        ) {
            return emptyList()
        }
        return try {
            val count = minOf(MAX_PREDICT_RESULTS, trigramStoredCount)
            var successCursor = trigramSuccessCursor
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

    private fun resolveTrigramSuccessors(
        previous: ImmutableUtf8Prefix,
        current: ImmutableUtf8Prefix,
    ): Boolean {
        val layout = trigram ?: return false
        val previousLength = previous.byteCount
        val currentLength = current.byteCount
        if (previousLength == 0 || previousLength > MAX_WORD_BYTES ||
            currentLength == 0 || currentLength > MAX_WORD_BYTES ||
            !isValidUtf8Scalar(previous) || !isValidUtf8Scalar(current)
        ) {
            return false
        }
        return try {
            for (offset in 0 until previousLength) {
                previousQueryScratch[offset] = previous.byteAt(offset).toByte()
            }
            for (offset in 0 until currentLength) {
                queryScratch[offset] = current.byteAt(offset).toByte()
            }
            val first = dictionary.indexOfWord(previousQueryScratch, previousLength)
            if (first < 0) return false
            val second = dictionary.indexOfWord(queryScratch, currentLength)
            if (second < 0) return false
            val query = first.toLong() * dictionary.entryCount + second

            var low = 0
            var high = layout.blockCount
            while (low < high) {
                val middle = (low + high) ushr 1
                if (trigramBlockFirstKey(middle) <= query) low = middle + 1 else high = middle
            }
            val block = low - 1
            if (block < 0) return false
            val firstContext = block * TRIGRAM_BLOCK_SIZE
            val blockContexts = minOf(TRIGRAM_BLOCK_SIZE, layout.contextCount - firstContext)
            var cursor = layout.deltasOffset + trigramBlockOffset(block, 8)
            var context = trigramBlockFirstKey(block)
            var found = -1
            for (position in 0 until blockContexts) {
                if (position > 0) {
                    decodeVarint64(cursor)
                    cursor = varintNext
                    context += varintLongValue
                }
                if (context == query) {
                    found = position
                    break
                }
                if (context > query) return false
            }
            if (found < 0) return false
            val contextIndex = firstContext + found
            var successCursor = layout.successIdsOffset + trigramBlockOffset(block, 12)
            for (previousContext in firstContext until contextIndex) {
                repeat(trigramCountAt(previousContext)) {
                    decodeVarint(successCursor)
                    successCursor = varintNext
                }
            }
            trigramSuccessCursor = successCursor
            trigramStoredCount = trigramCountAt(contextIndex)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun trigramBlockFirstKey(block: Int): Long {
        val layout = requireNotNull(trigram)
        val record = layout.blockIndexOffset + block * TRIGRAM_BLOCK_RECORD_BYTES
        return u32At(record) * dictionary.entryCount + u32At(record + 4)
    }

    private fun trigramBlockOffset(block: Int, relative: Int): Int =
        u32At(requireNotNull(trigram).blockIndexOffset + block * TRIGRAM_BLOCK_RECORD_BYTES + relative)
            .toInt()

    private fun trigramCountAt(context: Int): Int =
        bytes.get(requireNotNull(trigram).countsOffset + context).toInt() and 0xff

    private fun u32At(offset: Int): Long = bytes.getInt(offset).toLong() and MAX_U32

    private fun decodeVarint64(offset: Int) {
        var cursor = offset
        var value = 0L
        var shift = 0
        var lastGroup = 0
        while (true) {
            if (shift > 56) throw IllegalArgumentException("varint exceeds u64")
            val byte = bytes.get(cursor++).toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            lastGroup = byte and 0x7f
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (shift > 0 && lastGroup == 0) throw IllegalArgumentException("overlong varint")
        varintLongValue = value
        varintNext = cursor
    }

    private fun validateTrigramStreams(layout: TrigramLayout) {
        var successCursor = layout.successIdsOffset
        var pairTotal = 0
        var previousKey = -1L
        for (block in 0 until layout.blockCount) {
            val first = block * TRIGRAM_BLOCK_SIZE
            val blockContexts = minOf(TRIGRAM_BLOCK_SIZE, layout.contextCount - first)
            val deltaEnd = layout.deltasOffset + if (block + 1 < layout.blockCount) {
                trigramBlockOffset(block + 1, 8)
            } else {
                layout.deltasSize
            }
            require(successCursor == layout.successIdsOffset + trigramBlockOffset(block, 12))
            var cursor = layout.deltasOffset + trigramBlockOffset(block, 8)
            var context = trigramBlockFirstKey(block)
            for (position in 0 until blockContexts) {
                if (position > 0) {
                    decodeVarint64(cursor)
                    require(varintNext <= deltaEnd && varintLongValue >= 1)
                    cursor = varintNext
                    context += varintLongValue
                }
                require(context > previousKey)
                previousKey = context
                val count = trigramCountAt(first + position)
                require(count >= 1)
                pairTotal += count
                require(pairTotal <= layout.pairCount)
                repeat(count) {
                    decodeVarint(successCursor)
                    successCursor = varintNext
                    require(successCursor <= layout.successIdsOffset + layout.successIdsSize)
                    require(varintValue.toLong() and MAX_U32 < dictionary.entryCount)
                }
            }
            require(cursor == deltaEnd)
        }
        require(pairTotal == layout.pairCount)
        require(successCursor == layout.successIdsOffset + layout.successIdsSize)
    }

    override fun collectSuccessorIds(
        normalizedContextWordUtf8: ImmutableUtf8Prefix,
        sink: IntArray,
    ): Int {
        if (!resolveHeadSuccessors(normalizedContextWordUtf8)) return 0
        return try {
            val count = minOf(minOf(MAX_CONTEXT_SUCCESSORS, headStoredCount), sink.size)
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
        private const val TRIGRAM_BLOCK_SIZE = 64
        private const val TRIGRAM_BLOCK_RECORD_BYTES = 16
        private const val TRIGRAM_EXTENSION_OFFSET = 128
        private const val TRIGRAM_RESERVED_OFFSET = 176
        // One per strip cell (SuggestionStripState.CELL_COUNT): the NEXT_WORD display serves at
        // most this many of a head's successors, whatever the table stores.
        internal const val MAX_PREDICT_RESULTS = 4

        // The packed successor ids the context boost reads per head. Wider than the display
        // read: the exact pass may be armed with more successors than the strip has cells.
        internal const val MAX_CONTEXT_SUCCESSORS = 8
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
            require(identity.schemaId == 3 || identity.schemaId == 4)
            require(identity.formatVersion == 1)
            val headerSize = if (identity.schemaId == 4) 224 else HEADER_SIZE
            require(expectedHeadCount in 1..Int.MAX_VALUE.toLong())
            require(expectedRawSize in headerSize.toLong()..Int.MAX_VALUE.toLong())
            require(source.limit().toLong() == expectedRawSize)

            val duplicate = source.asReadOnlyBuffer()
            duplicate.position(0)
            val buffer = duplicate.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.limit() >= headerSize)
            for (index in MAGIC.indices) require(buffer.get(index) == MAGIC[index])
            require(u16(buffer, 8) == identity.schemaId)
            require(u16(buffer, 10) == identity.formatVersion)
            require(u16(buffer, 12) == headerSize)
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
            val expectedBlockIndex = headerSize.toLong()
            val expectedHeadDeltas = expectedBlockIndex + BLOCK_RECORD_BYTES * blockCount
            val expectedCounts = expectedHeadDeltas + headDeltasSize
            val expectedSuccessIds = expectedCounts + headCount
            val bigramEnd = expectedSuccessIds + successIdsSize
            require(blockIndexOffset == expectedBlockIndex)
            require(headDeltasOffset == expectedHeadDeltas)
            require(countsOffset == expectedCounts)
            require(successIdsOffset == expectedSuccessIds)
            require(fileSize == expectedRawSize)
            require(identity.schemaId == 4 || fileSize == bigramEnd)
            require(bigramEnd <= Int.MAX_VALUE)
            require(pairCount <= Int.MAX_VALUE)

            val trigram = if (identity.schemaId == 4) {
                for (offset in TRIGRAM_RESERVED_OFFSET until headerSize) {
                    require(buffer.get(offset).toInt() == 0)
                }
                val contextCount = u32(buffer, TRIGRAM_EXTENSION_OFFSET)
                val trigramPairCount = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 4)
                val trigramBlockCount = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 8)
                val trigramBlocks = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 12)
                val trigramDeltas = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 16)
                val trigramDeltasSize = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 20)
                val trigramCounts = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 24)
                val trigramSuccesses = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 28)
                val trigramSuccessesSize = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 32)
                val dictionaryWordCount = u32(buffer, TRIGRAM_EXTENSION_OFFSET + 36)
                require(contextCount in 1..Int.MAX_VALUE.toLong())
                require(trigramPairCount in 1..Int.MAX_VALUE.toLong())
                require(dictionaryWordCount == dictionary.entryCount.toLong())
                require(trigramBlockCount == (contextCount + TRIGRAM_BLOCK_SIZE - 1) / TRIGRAM_BLOCK_SIZE)
                require(trigramBlocks == bigramEnd)
                require(trigramDeltas == trigramBlocks + TRIGRAM_BLOCK_RECORD_BYTES * trigramBlockCount)
                require(trigramCounts == trigramDeltas + trigramDeltasSize)
                require(trigramSuccesses == trigramCounts + contextCount)
                require(trigramSuccesses + trigramSuccessesSize == fileSize)
                require(trigramPairCount <= trigramSuccessesSize)
                TrigramLayout(
                    contextCount.toInt(), trigramPairCount.toInt(), trigramBlockCount.toInt(),
                    trigramBlocks.toInt(), trigramDeltas.toInt(), trigramDeltasSize.toInt(),
                    trigramCounts.toInt(), trigramSuccesses.toInt(), trigramSuccessesSize.toInt(),
                )
            } else {
                null
            }

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
                trigram,
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
            if (trigram != null) {
                var previousFirstKey = -1L
                var previousDelta = 0
                var previousSuccess = 0
                for (block in 0 until trigram.blockCount) {
                    val firstKey = index.trigramBlockFirstKey(block)
                    val firstId = u32(
                        buffer, trigram.blockIndexOffset + block * TRIGRAM_BLOCK_RECORD_BYTES
                    )
                    val secondId = u32(
                        buffer, trigram.blockIndexOffset + block * TRIGRAM_BLOCK_RECORD_BYTES + 4
                    )
                    val delta = index.trigramBlockOffset(block, 8)
                    val success = index.trigramBlockOffset(block, 12)
                    require(firstId < dictionary.entryCount && secondId < dictionary.entryCount)
                    require(firstKey > previousFirstKey)
                    require(delta in previousDelta..trigram.deltasSize)
                    require(success in previousSuccess..trigram.successIdsSize)
                    if (block == 0) require(delta == 0 && success == 0)
                    previousFirstKey = firstKey
                    previousDelta = delta
                    previousSuccess = success
                }
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
                if (trigram != null) index.validateTrigramStreams(trigram)
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
