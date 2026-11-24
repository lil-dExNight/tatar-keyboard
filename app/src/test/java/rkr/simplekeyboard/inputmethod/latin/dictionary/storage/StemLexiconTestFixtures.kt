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

package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal data class StemTestEntry(val word: String, val flags: Int)
internal data class StemTestArtifact(
    val spec: StemLexiconArtifactSpec,
    val raw: ByteArray,
    val compressed: ByteArray,
)

internal object StemLexiconTestFixtures {
    fun artifact(
        entries: List<StemTestEntry> = defaultEntries(40),
        maxRawSize: Long = 1_048_576,
        overlongPrefixes: Boolean = false,
    ): StemTestArtifact {
        val raw = raw(entries, overlongPrefixes)
        val compressed = DictionaryTestFixtures.compress(raw)
        return StemTestArtifact(spec(raw, compressed, maxRawSize), raw, compressed)
    }

    fun defaultEntries(count: Int): List<StemTestEntry> {
        val consonants = listOf('б', 'в', 'г', 'д', 'ж', 'җ', 'к', 'л')
        require(count <= consonants.size * consonants.size)
        val result = ArrayList<StemTestEntry>(count)
        var value = 0
        while (result.size < count) {
            val word = "${consonants[value and 7]}${consonants[(value ushr 3) and 7]}а"
            result += StemTestEntry(word, flags(word))
            value++
        }
        return result.sortedBy { it.word }
    }

    fun flags(
        word: String,
        noun: Boolean = true,
        verb: Boolean = false,
        chains: Boolean = true,
        forceBack: Boolean = false,
    ): Int {
        var result = (if (noun) TatStemFormat.FLAG_NOUN else 0) or
            (if (verb) TatStemFormat.FLAG_VERB else 0)
        if (chains) {
            result = result or (if (noun) TatStemFormat.FLAG_NOUN_CHAIN else 0) or
                (if (verb) TatStemFormat.FLAG_VERB_CHAIN else 0)
        }
        val back = forceBack || BACK_VOWELS.any(word::contains)
        result = result or if (back) TatStemFormat.FLAG_BACK else TatStemFormat.FLAG_FRONT
        if (forceBack) result = result or TatStemFormat.FLAG_FORCE_BACK
        return result
    }

    fun raw(entries: List<StemTestEntry>, overlongPrefixes: Boolean = false): ByteArray =
        rawFromBytes(entries.map { it.word.toByteArray(Charsets.UTF_8) to it.flags },
            overlongPrefixes)

    fun rawFromBytes(
        entries: List<Pair<ByteArray, Int>>,
        overlongPrefixes: Boolean = false,
    ): ByteArray {
        val count = entries.size
        val blockCount = (count + TatStemFormat.BLOCK_SIZE - 1) / TatStemFormat.BLOCK_SIZE
        val blocksOffset = TatStemFormat.HEADER_SIZE + 4 * blockCount
        val blocks = ArrayList<ByteArray>(blockCount)
        val offsets = ArrayList<Int>(blockCount)
        var cursor = blocksOffset
        for (start in entries.indices step TatStemFormat.BLOCK_SIZE) {
            val chunk = entries.subList(start, minOf(start + TatStemFormat.BLOCK_SIZE, count))
            val first = chunk[0].first
            val block = ByteArrayOutputStream()
            block.write(first.size)
            block.write(first)
            block.write(chunk[0].second)
            for ((word, flags) in chunk.drop(1)) {
                var prefix = 0
                val limit = minOf(first.size, word.size)
                while (prefix < limit && first[prefix] == word[prefix]) prefix++
                val encodedPrefix = varint(prefix)
                if (overlongPrefixes) block.write(0x80)
                block.write(encodedPrefix)
                block.write(word.size - prefix)
                block.write(word, prefix, word.size - prefix)
                block.write(flags)
            }
            val bytes = block.toByteArray()
            offsets += cursor
            blocks += bytes
            cursor += bytes.size
        }
        val output = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN)
        output.put(TatStemFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        output.putShort(TatStemFormat.SCHEMA_ID.toShort())
        output.putShort(TatStemFormat.FORMAT_VERSION.toShort())
        output.putShort(TatStemFormat.HEADER_SIZE.toShort())
        output.putShort(TatStemFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        output.putInt(count)
        output.putInt(blockCount)
        output.putInt(TatStemFormat.HEADER_SIZE)
        output.putInt(blocksOffset)
        output.putInt(cursor - blocksOffset)
        output.putInt(cursor)
        output.put(hex(DICTIONARY_SHA))
        output.put(hex(APERTIUM_SHA))
        output.put(ByteArray(TatStemFormat.CHECKSUM_SIZE))
        offsets.forEach(output::putInt)
        blocks.forEach(output::put)
        return refreshEmbeddedChecksum(output.array())
    }

    fun spec(
        raw: ByteArray,
        compressed: ByteArray = DictionaryTestFixtures.compress(raw),
        maxRawSize: Long = 1_048_576,
    ): StemLexiconArtifactSpec {
        val count = if (raw.size >= TatStemFormat.HEADER_SIZE) {
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(TatStemFormat.STEM_COUNT_OFFSET).toLong() and 0xffff_ffffL
        } else {
            1
        }
        return StemLexiconArtifactSpec(
            family = "tatar_stems",
            languageTag = "tt_RU",
            assetPath = "fixture.tstem.zlib",
            expectedCompressedSize = compressed.size.toLong(),
            expectedCompressedSha256 = DictionaryTestFixtures.sha256(compressed),
            expectedRawSize = raw.size.toLong(),
            expectedRawSha256 = DictionaryTestFixtures.sha256(raw),
            expectedStemCount = if (count in 1..TatStemFormat.MAX_STEMS) count else 1,
            expectedDictionaryRawSha256 = DICTIONARY_SHA,
            expectedApertiumLexiconSha256 = APERTIUM_SHA,
            maxRawSize = maxRawSize,
        )
    }

    fun refreshEmbeddedChecksum(input: ByteArray): ByteArray {
        val result = input.copyOf()
        result.fill(0, TatStemFormat.CHECKSUM_OFFSET, TatStemFormat.HEADER_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(result)
        checksum.copyInto(result, TatStemFormat.CHECKSUM_OFFSET)
        return result
    }

    fun sha256(bytes: ByteArray): String = DictionaryTestFixtures.sha256(bytes)

    private fun varint(value: Int): ByteArray {
        var rest = value
        val out = ByteArrayOutputStream(5)
        while (true) {
            val byte = rest and 0x7f
            rest = rest ushr 7
            out.write(byte or if (rest == 0) 0 else 0x80)
            if (rest == 0) return out.toByteArray()
        }
    }

    private fun hex(value: String): ByteArray =
        ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val BACK_VOWELS = "аыоуюя"
    private const val DICTIONARY_SHA =
        "41586468b3cf1acb58637e05d153d14f10d3297d7a5744adbadc6112a561c8f0"
    private const val APERTIUM_SHA =
        "43438bc2710beae9472e1d5e09a2403793d0d059c48ff9a9e5c9100387716e73"
}
