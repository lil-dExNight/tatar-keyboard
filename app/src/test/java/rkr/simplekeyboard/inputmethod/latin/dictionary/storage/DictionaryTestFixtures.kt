package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

internal data class TestArtifact(
    val spec: DictionaryArtifactSpec,
    val raw: ByteArray,
    val compressed: ByteArray,
)

internal object DictionaryTestFixtures {
    fun artifact(
        generation: Int = 1,
        entries: List<Pair<String, Long>> = listOf(
            "аб" to 30,
            "аба" to 20,
            "әби" to 10,
        ),
        dictionary: ByteArray? = null,
    ): TestArtifact {
        val raw = raw(entries)
        val compressed = compress(raw, dictionary)
        return TestArtifact(spec(generation, raw, compressed), raw, compressed)
    }

    /** Writes a schema-2 (front-coding, K = [TdictFormat.BLOCK_SIZE]) raw dictionary. */
    fun raw(entries: List<Pair<String, Long>>): ByteArray =
        rawFromBytes(entries.map { it.first.toByteArray(Charsets.UTF_8) to it.second })

    /** Same as [raw], but takes the stored word bytes as given, valid UTF-8 or not. */
    fun rawFromBytes(entries: List<Pair<ByteArray, Long>>): ByteArray {
        val encoded = entries.map { it.first }
        val entryCount = entries.size
        val blockCount = (entryCount + TdictFormat.BLOCK_SIZE - 1) / TdictFormat.BLOCK_SIZE
        val blockIndexOffset = 72
        val blocksOffset = blockIndexOffset + 4 * blockCount

        val blocks = ArrayList<ByteArray>(blockCount)
        val blockOffsets = ArrayList<Int>(blockCount)
        var cursor = 0
        var start = 0
        while (start < entryCount) {
            val chunk = encoded.subList(start, minOf(start + TdictFormat.BLOCK_SIZE, entryCount))
            val first = chunk[0]
            val block = ByteArrayOutputStream()
            block.write(first.size)
            block.write(first)
            for (word in chunk.drop(1)) {
                var prefix = 0
                val limit = minOf(first.size, word.size)
                while (prefix < limit && first[prefix] == word[prefix]) prefix++
                block.write(varint(prefix.toLong()))
                block.write(word.size - prefix)
                block.write(word, prefix, word.size - prefix)
            }
            for (index in start until start + chunk.size) {
                block.write(varint(entries[index].second))
            }
            blockOffsets += blocksOffset + cursor
            val blockBytes = block.toByteArray()
            blocks += blockBytes
            cursor += blockBytes.size
            start += chunk.size
        }
        val blocksSize = cursor
        val fileSize = blocksOffset + blocksSize
        val bytes = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("TATDICT\u0000".toByteArray(Charsets.US_ASCII))
        bytes.putShort(2)
        bytes.putShort(1)
        bytes.putShort(72)
        bytes.putShort(1)
        bytes.putInt(entryCount)
        bytes.putInt(blockCount)
        bytes.putInt(blockIndexOffset)
        bytes.putInt(blocksOffset)
        bytes.putInt(blocksSize)
        bytes.putInt(fileSize)
        bytes.put(ByteArray(32))
        blockOffsets.forEach(bytes::putInt)
        blocks.forEach(bytes::put)
        return refreshEmbeddedChecksum(bytes.array())
    }

    /** Enumerates the words of a schema-2 raw dictionary by decoding its blocks. */
    fun words(raw: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val entryCount = buffer.getInt(16)
        val blockCount = buffer.getInt(20)
        val blockIndexOffset = buffer.getInt(24)
        val words = ArrayList<String>(entryCount)
        for (block in 0 until blockCount) {
            var cursor = buffer.getInt(blockIndexOffset + block * 4)
            val inBlock = minOf(TdictFormat.BLOCK_SIZE, entryCount - block * TdictFormat.BLOCK_SIZE)
            val firstLength = raw[cursor].toInt() and 0xff
            cursor++
            val first = raw.copyOfRange(cursor, cursor + firstLength)
            cursor += firstLength
            words.add(String(first, Charsets.UTF_8))
            for (entry in 1 until inBlock) {
                var prefix = 0
                var shift = 0
                while (true) {
                    val byte = raw[cursor].toInt() and 0xff
                    cursor++
                    prefix = prefix or ((byte and 0x7f) shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                }
                val suffixLength = raw[cursor].toInt() and 0xff
                cursor++
                val word = ByteArray(prefix + suffixLength)
                System.arraycopy(first, 0, word, 0, prefix)
                System.arraycopy(raw, cursor, word, prefix, suffixLength)
                cursor += suffixLength
                words.add(String(word, Charsets.UTF_8))
            }
            // Skip the frequency varints; the word list is what callers want.
            repeat(inBlock) {
                while (raw[cursor].toInt() and 0x80 != 0) cursor++
                cursor++
            }
        }
        return words
    }

    /** Minimal-form base-128 varint of a non-negative value. */
    private fun varint(value: Long): ByteArray {
        var rest = value
        val out = ByteArrayOutputStream(5)
        while (true) {
            val byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
            if (rest != 0L) {
                out.write(byte or 0x80)
            } else {
                out.write(byte)
                return out.toByteArray()
            }
        }
    }

    fun spec(
        generation: Int,
        raw: ByteArray,
        compressed: ByteArray = compress(raw),
        expectedRawSize: Long = raw.size.toLong(),
        maxCompressedSize: Long = 600_000,
        maxRawSize: Long = 1_400_000,
        family: String = "tatar_top100k",
        languageTag: String = "tt_RU",
        storageDirectoryName: String = "dictionaries",
    ) = DictionaryArtifactSpec(
        family = family,
        languageTag = languageTag,
        storageDirectoryName = storageDirectoryName,
        generation = generation,
        assetPath = "fixture-$generation.zlib",
        expectedCompressedSize = minOf(compressed.size.toLong(), maxCompressedSize),
        expectedCompressedSha256 = sha256(compressed),
        expectedRawSize = expectedRawSize,
        expectedRawSha256 = sha256(raw),
        expectedEntryCount = ByteBuffer.wrap(raw, 16, 4)
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
        result.fill(0, 40, 72)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, 40)
        return result
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

internal open class TestDurableFileOps : DurableFileOps {
    val events = mutableListOf<String>()
    val deleted = mutableListOf<File>()

    override fun createNewFile(file: File): Boolean = file.createNewFile()

    override fun syncFile(fileDescriptor: FileDescriptor) {
        events += "file-fsync"
        fileDescriptor.sync()
    }

    override fun atomicRename(source: File, destination: File) {
        events += "rename"
        if (destination.exists() || !source.renameTo(destination)) {
            throw java.io.IOException("atomic rename failed")
        }
    }

    override fun syncDirectory(directory: File) {
        events += "directory-fsync"
    }

    override fun delete(file: File): Boolean {
        events += "delete:${file.name}"
        deleted += file
        return file.delete()
    }
}

internal class CountingAssetProvider(
    private val assets: MutableMap<Int, ByteArray>,
) : AssetInputProvider {
    var opens = 0

    override fun open(spec: DictionaryArtifactSpec): java.io.InputStream {
        opens++
        return (assets[spec.generation] ?: error("missing fixture")).inputStream()
    }
}
