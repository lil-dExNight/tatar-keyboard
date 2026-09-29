package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.nio.ByteBuffer

class TatBigrPrefixIndexTest {
    @Test
    fun exactMatchReturnsSuccessesInPackingOrder() {
        val index = EngineTestFixtures.bigramIndex(
            listOf("аб" to listOf("аба", "юл"), "юл" to listOf("өй")),
        )

        assertEquals(listOf("аба", "юл"), predict(index, "аб"))
    }

    @Test
    fun onlyExactMatchNoPrefixBehaviour() {
        val index = EngineTestFixtures.bigramIndex(listOf("аба" to listOf("өй")))

        // "аб" is a strict prefix of the only head "аба" — and is not even a dictionary word here,
        // so the schema-3 read misses twice over: no exact dictionary entry, then no head.
        assertTrue(predict(index, "аб").isEmpty())
        assertTrue(predict(index, "абалар").isEmpty())
    }

    @Test
    fun contextWordAbsentFromTheDictionaryReturnsEmpty() {
        // Schema 3 resolves the context word through the linked dictionary FIRST: a word the
        // dictionary does not contain can never be a head, so the read is an immediate miss.
        val index = EngineTestFixtures.bigramIndex(listOf("аб" to listOf("аба")))

        assertTrue(predict(index, "юл").isEmpty())
    }

    @Test
    fun headInDictionaryButNotInTableReturnsEmpty() {
        // "юл" IS in the fixture dictionary (as a success of "аб") but is not a head — the block
        // scan walks past its dictionary index without finding it among the heads.
        val index = EngineTestFixtures.bigramIndex(listOf("аб" to listOf("юл")))

        assertTrue(predict(index, "юл").isEmpty())
        assertEquals(listOf("юл"), predict(index, "аб"))
    }

    @Test
    fun moreThanThreeStoredSuccessesAreCappedToThree() {
        val index = EngineTestFixtures.bigramIndex(
            listOf("аб" to listOf("а", "б", "в", "г", "д", "е")),
        )

        assertEquals(listOf("а", "б", "в"), predict(index, "аб"))
    }

    @Test
    fun emptyOversizedAndInvalidUtf8ContextWordsReturnEmpty() {
        val index = EngineTestFixtures.bigramIndex(listOf("аб" to listOf("аба")))

        assertTrue(predict(index, "").isEmpty())
        assertTrue(index.predict(ImmutableUtf8Prefix.copyOf(ByteArray(129) { 1 })).isEmpty())
        assertTrue(index.predict(ImmutableUtf8Prefix.copyOf(byteArrayOf(0xff.toByte()))).isEmpty())
    }

    @Test
    fun openRejectsIdentityMismatch() {
        val heads = listOf("аб" to listOf("аба"))
        val raw = BigramTestFixtures.raw(heads)
        val dictionary = fixtureDictionary(heads)
        val wrongSchema = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw),
            EngineTestFixtures.bigramIdentity.copy(schemaId = 2),
            dictionary,
            1,
            raw.size.toLong(),
        )
        assertNull(wrongSchema)

        val wrongHeadCount = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw),
            EngineTestFixtures.bigramIdentity,
            dictionary,
            2,
            raw.size.toLong(),
        )
        assertNull(wrongHeadCount)

        val truncated = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw.copyOf(raw.size - 1)),
            EngineTestFixtures.bigramIdentity,
            dictionary,
            1,
            raw.size.toLong(),
        )
        assertNull(truncated)
    }

    @Test
    fun openRejectsADictionaryOtherThanTheLinkedOne() {
        // The schema-3 link: the header names the dictionary's raw SHA-256, and open() refuses a
        // table paired with any other dictionary — fail closed, exactly like the validator.
        val heads = listOf("аб" to listOf("аба"))
        val raw = BigramTestFixtures.raw(heads)
        val dictionaryWords = BigramTestFixtures.defaultDictionaryWords(heads)
        val foreignDictionary = requireNotNull(
            DictionaryTestFixtures.raw(dictionaryWords.map { it to 1L }).let { rawDict ->
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawDict),
                    DictionaryIdentity(1, 2, 1, "c".repeat(64)),
                    dictionaryWords.size.toLong(),
                    rawDict.size.toLong(),
                )
            },
        )

        assertNull(
            TatBigrPrefixIndex.open(
                ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, foreignDictionary,
                1, raw.size.toLong(),
            ),
        )
    }

    @Test
    fun openRejectsBlockFirstIndexBeyondTheDictionary() {
        // open() re-validates the invariants its own reads depend on rather than trusting that
        // TatBigrValidator was the only thing ever standing between this buffer and disk — this
        // corrupts the body (not the header) so only that specific check can catch it.
        val heads = listOf("аб" to listOf("аба"), "юл" to listOf("өй"))
        val raw = BigramTestFixtures.raw(heads)
        writeU32(raw, 128, 50) // the only block's first dictionary index; the dictionary has 4 words

        val index = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, fixtureDictionary(heads),
            2, raw.size.toLong(),
        )

        assertNull(index)
    }

    @Test
    fun openRejectsZeroSuccessCount() {
        // The exact shape scripts/bigram_asset_pack.py refuses to ever produce (a head with zero
        // successes is dropped, not stored with an empty range) — the reader must refuse it too.
        val heads = listOf("аб" to listOf("аба"))
        val raw = BigramTestFixtures.raw(heads)
        raw[readU32(raw, 40)] = 0 // countsOffset: the only head's success count

        val index = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, fixtureDictionary(heads),
            1, raw.size.toLong(),
        )

        assertNull(index)
    }

    @Test
    fun openRejectsSuccessIdAtOrBeyondDictionarySize() {
        val heads = listOf("аб" to listOf("аба"))
        val raw = BigramTestFixtures.raw(heads)
        raw[readU32(raw, 44)] = 5 // successIdsOffset: the dictionary has 2 words; 5 is far beyond

        val index = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, fixtureDictionary(heads),
            1, raw.size.toLong(),
        )

        assertNull(index)
    }

    @Test
    fun openRejectsEmptySuccessRange() {
        val heads = listOf("аб" to listOf("аба"), "юл" to emptyList())
        val raw = BigramTestFixtures.raw(heads)

        val index = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), EngineTestFixtures.bigramIdentity, fixtureDictionary(heads),
            2, raw.size.toLong(),
        )

        assertNull(index)
    }

    private fun readU32(raw: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(raw, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

    private fun writeU32(raw: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(raw, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    @Test
    fun acceptsCommittedTatarBigramAssetAndFindsARealHead() {
        val spec = rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramArtifactSpec
            .TATAR_BIGRAMS_V1
        val asset = locateCommittedAsset(
            "src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
            "app/src/main/assets/bigrams/tatar_bigrams_v1.tatbigr.zlib",
        ).readBytes()
        val decompressed = java.io.ByteArrayOutputStream(spec.expectedRawSize.toInt()).also { output ->
            rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatBigrValidator()
                .inflateAsset(asset.inputStream(), output, spec)
        }.toByteArray()
        val identity = BigramTableIdentity(
            spec.generation, spec.fileLanguageTag, spec.schemaId, spec.formatVersion, spec.expectedRawSha256,
        )
        val index = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(decompressed),
            identity,
            committedTatarDictionary(),
            spec.expectedHeadCount,
            decompressed.size.toLong(),
        )
        assertTrue(index != null)
        // "мин" (I/me) is an extremely common Tatar word and, if present as a head at all, must
        // return at most MAX_RESULTS successes without throwing.
        val results = index!!.predict(ImmutableUtf8Prefix.copyOf("мин".toByteArray(Charsets.UTF_8)))
        assertTrue(results.size <= TatBigrPrefixIndex.MAX_RESULTS)
        // The imperative "кил" is an extra-list head (docs/archive/bigrams/IMPERATIVE-HEADS.md);
        // its successor row is pinned by the schema-3 equivalence check — the reader must serve
        // it. The shipped table is K = 4 (stays so after the 2026-09-29 three-cell revert): the
        // 4th stored successor (монда) is the unread headroom, MAX_RESULTS caps the row at three.
        assertEquals(
            listOf("дә", "әле", "һәм"),
            index.predict(ImmutableUtf8Prefix.copyOf("кил".toByteArray(Charsets.UTF_8))),
        )
    }

    private fun predict(index: TatBigrPrefixIndex, word: String): List<String> =
        index.predict(ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8)))

    /** A real [TdictPrefixIndex] over the fixture dictionary, with the identity the table names. */
    private fun fixtureDictionary(
        heads: List<Pair<String, List<String>>>,
    ): TdictPrefixIndex {
        val words = BigramTestFixtures.defaultDictionaryWords(heads)
        val rawDict = DictionaryTestFixtures.raw(words.map { it to 1L })
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(rawDict),
                EngineTestFixtures.identity,
                words.size.toLong(),
                rawDict.size.toLong(),
            ),
        )
    }

    companion object {
        private var dictionary: TdictPrefixIndex? = null

        private fun committedTatarDictionary(): TdictPrefixIndex {
            dictionary?.let { return it }
            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val asset = locateCommittedAsset(
                "src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
                "app/src/main/assets/dictionaries/tatar_top100k_v1.tdict.zlib",
            )
            val rawFile = java.io.File.createTempFile("e5c-real-dict-", ".tdict")
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                val validated = TdictValidator().validateRaw(rawFile, spec)
                val identity = DictionaryIdentity(
                    spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
                )
                val index = TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()), identity,
                    validated.entryCount, validated.rawSize,
                )
                check(index != null)
                dictionary = index
                return index
            } finally {
                rawFile.delete()
            }
        }

        private fun locateCommittedAsset(vararg paths: String): java.io.File =
            paths.map { java.io.File(it) }.firstOrNull(java.io.File::isFile)
                ?: error("cannot locate committed asset from ${java.io.File(".").absolutePath}")
    }
}
