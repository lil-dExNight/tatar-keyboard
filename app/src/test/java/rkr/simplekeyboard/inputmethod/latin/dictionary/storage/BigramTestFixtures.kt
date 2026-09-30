package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

internal data class TestBigramArtifact(
    val spec: BigramArtifactSpec,
    val raw: ByteArray,
    val compressed: ByteArray,
)

/**
 * Builds fixture TATBIGR schema-3 files from a head -> ordered-successors list plus the word
 * list of the dictionary the table references, as [DictionaryTestFixtures] does for the
 * dictionary. Callers list heads in code-point order (the validator requires strictly increasing
 * dictionary indices; this builder does not re-sort). The default dictionary is exactly the
 * heads and successors involved, sorted, identified by [DEFAULT_DICTIONARY_SHA], the same
 * fixture identity as `EngineTestFixtures`, so fixture indexes open fixture tables without
 * pinning real hashes.
 */
internal object BigramTestFixtures {
    val DEFAULT_DICTIONARY_SHA: String = "a".repeat(64)

    fun artifact(
        generation: Int = 1,
        fileLanguageTag: String = "tt",
        headsToSuccesses: List<Pair<String, List<String>>> = listOf(
            "аб" to listOf("аба", "әби"),
            "аба" to listOf("әби"),
        ),
        family: String = "tatar_bigrams",
        storageDirectoryName: String = "bigrams",
    ): TestBigramArtifact {
        val raw = raw(headsToSuccesses)
        val compressed = compress(raw)
        return TestBigramArtifact(
            spec(
                generation,
                fileLanguageTag,
                raw,
                compressed,
                family = family,
                storageDirectoryName = storageDirectoryName,
            ),
            raw,
            compressed,
        )
    }

    fun raw(
        headsToSuccesses: List<Pair<String, List<String>>>,
        dictionaryWords: List<String> = defaultDictionaryWords(headsToSuccesses),
        dictionaryRawSha256: String = DEFAULT_DICTIONARY_SHA,
    ): ByteArray {
        val wordIndex = dictionaryWords.withIndex().associate { (index, word) -> word to index }
        val headIndices = headsToSuccesses.map { wordIndex.getValue(it.first) }

        val headCount = headsToSuccesses.size
        val blockCount = (headCount + 63) / 64
        val pairCount = headsToSuccesses.sumOf { it.second.size }

        val headDeltas = java.io.ByteArrayOutputStream()
        val successIds = java.io.ByteArrayOutputStream()
        val counts = ByteArray(headCount)
        val blockRecords = ArrayList<Triple<Int, Int, Int>>(blockCount)
        for (block in 0 until blockCount) {
            val first = block * 64
            val last = minOf(first + 64, headCount)
            blockRecords += Triple(headIndices[first], headDeltas.size(), successIds.size())
            for (position in first + 1 until last) {
                headDeltas.write(varint(headIndices[position] - headIndices[position - 1]))
            }
            for (position in first until last) {
                val successes = headsToSuccesses[position].second
                counts[position] = successes.size.toByte()
                for (word in successes) successIds.write(varint(wordIndex.getValue(word)))
            }
        }
        val headDeltaBytes = headDeltas.toByteArray()
        val successIdBytes = successIds.toByteArray()

        val blockIndexOffset = 128
        val headDeltasOffset = blockIndexOffset + 12 * blockCount
        val countsOffset = headDeltasOffset + headDeltaBytes.size
        val successIdsOffset = countsOffset + headCount
        val fileSize = successIdsOffset + successIdBytes.size

        val bytes = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("TATBIGR\u0000".toByteArray(Charsets.US_ASCII))
        bytes.putShort(3) // schemaId
        bytes.putShort(1) // formatVersion
        bytes.putShort(128) // headerSize
        bytes.putShort(1) // checksumAlgorithm
        bytes.putInt(headCount)
        bytes.putInt(pairCount)
        bytes.putInt(blockCount)
        bytes.putInt(blockIndexOffset)
        bytes.putInt(headDeltasOffset)
        bytes.putInt(headDeltaBytes.size)
        bytes.putInt(countsOffset)
        bytes.putInt(successIdsOffset)
        bytes.putInt(successIdBytes.size)
        bytes.putInt(fileSize)
        bytes.put(dictionaryRawSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        bytes.put(ByteArray(8)) // reserved
        bytes.put(ByteArray(32)) // checksum, filled by refreshEmbeddedChecksum
        blockRecords.forEach { (firstIndex, deltaOffset, successOffset) ->
            bytes.putInt(firstIndex)
            bytes.putInt(deltaOffset)
            bytes.putInt(successOffset)
        }
        bytes.put(headDeltaBytes)
        bytes.put(counts)
        bytes.put(successIdBytes)
        return refreshEmbeddedChecksum(bytes.array())
    }

    fun defaultDictionaryWords(headsToSuccesses: List<Pair<String, List<String>>>): List<String> =
        (headsToSuccesses.map { it.first } + headsToSuccesses.flatMap { it.second })
            .toSortedSet().toList()

    fun spec(
        generation: Int,
        fileLanguageTag: String,
        raw: ByteArray,
        compressed: ByteArray = compress(raw),
        expectedRawSize: Long = raw.size.toLong(),
        expectedDictionaryRawSha256: String = DEFAULT_DICTIONARY_SHA,
        maxCompressedSize: Long = 250_000,
        maxRawSize: Long = 1_048_576,
        family: String = "tatar_bigrams",
        storageDirectoryName: String = "bigrams",
    ) = BigramArtifactSpec(
        family = family,
        generation = generation,
        fileLanguageTag = fileLanguageTag,
        subtypeId = fileLanguageTag,
        storageDirectoryName = storageDirectoryName,
        assetPath = "fixture-$fileLanguageTag-$generation.zlib",
        expectedCompressedSize = minOf(compressed.size.toLong(), maxCompressedSize),
        expectedCompressedSha256 = sha256(compressed),
        expectedRawSize = expectedRawSize,
        expectedRawSha256 = sha256(raw),
        expectedDictionaryRawSha256 = expectedDictionaryRawSha256,
        expectedHeadCount = ByteBuffer.wrap(raw, 16, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL,
        maxCompressedSize = maxCompressedSize,
        maxRawSize = maxRawSize,
    )

    fun compress(raw: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater(9, false)
        if (dictionary != null) deflater.setDictionary(dictionary)
        deflater.setInput(raw)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, 96, 128)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, 96)
        return result
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Canonical minimal-form u32 varint, the same encoding dictionary_pack writes. */
    fun varint(value: Int): ByteArray {
        var rest = value.toLong() and 0xffff_ffffL
        val output = ByteArrayOutputStream()
        while (true) {
            val byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
            if (rest != 0L) {
                output.write(byte or 0x80)
            } else {
                output.write(byte)
                return output.toByteArray()
            }
        }
    }
}

internal class CountingBigramAssetProvider(
    private val assets: MutableMap<Int, ByteArray>,
) : BigramAssetInputProvider {
    var opens = 0

    override fun open(spec: BigramArtifactSpec): java.io.InputStream {
        opens++
        return (assets[spec.generation] ?: error("missing fixture")).inputStream()
    }
}
