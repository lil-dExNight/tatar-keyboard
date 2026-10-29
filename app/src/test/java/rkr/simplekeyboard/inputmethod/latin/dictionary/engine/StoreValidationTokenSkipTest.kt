package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTableLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryFileLease
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedBigramTableCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.PublishedDictionaryCatalog
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StoreValidationToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * The validation-token skip of `TdictPrefixIndex.open` and `TatBigrPrefixIndex.open`. The probe
 * fixtures are valid in the header and section arithmetic but break a rule only the structural
 * walk checks, so a non-null open result proves the walk was skipped and a null proves it ran;
 * no timing is involved.
 */
class StoreValidationTokenSkipTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dictionaryOpenSkipsTheStructuralWalkOnAMatchingToken() {
        val raw = DictionaryTestFixtures.raw(OUT_OF_ORDER)
        val identity = EngineTestFixtures.identity
        // The modification time is the engine-side freshness gate; open never reads it.
        val token = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong(), 1_000)

        val skipped = TdictPrefixIndex.open(
            ByteBuffer.wrap(raw), identity, OUT_OF_ORDER.size.toLong(), raw.size.toLong(),
            null, null, 0, token,
        )
        val walked = TdictPrefixIndex.open(
            ByteBuffer.wrap(raw), identity, OUT_OF_ORDER.size.toLong(), raw.size.toLong(),
            null, null, 0,
        )

        assertNotNull(skipped)
        assertEquals(OUT_OF_ORDER.size, skipped!!.entryCount)
        assertNull("unsorted words must fail the full walk", walked)
    }

    @Test
    fun dictionaryOpenFallsBackToTheFullWalkOnAMismatchedToken() {
        val raw = DictionaryTestFixtures.raw(OUT_OF_ORDER)
        val identity = EngineTestFixtures.identity
        val wrongSha = StoreValidationToken.issue("b".repeat(64), raw.size.toLong(), 1_000)
        val wrongSize = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong() + 1, 1_000)

        for (token in listOf(wrongSha, wrongSize)) {
            assertNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, OUT_OF_ORDER.size.toLong(), raw.size.toLong(),
                    null, null, 0, token,
                ),
            )
        }
        // On valid content the fallback walk still opens the dictionary.
        val valid = DictionaryTestFixtures.raw(WORDS)
        assertNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(valid), identity, WORDS.size.toLong(), valid.size.toLong(),
                null, null, 0, wrongSha,
            ),
        )
    }

    @Test
    fun dictionaryOpenWithAMatchingTokenYieldsAUsableIndex() {
        val raw = DictionaryTestFixtures.raw(WORDS)
        val identity = EngineTestFixtures.identity
        val token = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong(), 1_000)

        val index = requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, WORDS.size.toLong(), raw.size.toLong(),
                null, null, 0, token,
            ),
        )

        assertTrue(index.containsWordCold("аба"))
        assertFalse(index.containsWordCold("абв"))
    }

    @Test
    fun bigramOpenSkipsTheStructuralWalkOnAMatchingToken() {
        val raw = zeroCountTable()
        val dictionary = tableDictionary()
        val identity = EngineTestFixtures.bigramIdentity
        val token = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong(), 1_000)

        val skipped = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), identity, dictionary, HEADS.size.toLong(), raw.size.toLong(),
            token,
        )
        val walked = TatBigrPrefixIndex.open(
            ByteBuffer.wrap(raw), identity, dictionary, HEADS.size.toLong(), raw.size.toLong(),
        )

        assertNotNull(skipped)
        assertNull("a zero successor count must fail the full walk", walked)
    }

    @Test
    fun bigramOpenFallsBackToTheFullWalkOnAMismatchedToken() {
        val raw = zeroCountTable()
        val dictionary = tableDictionary()
        val identity = EngineTestFixtures.bigramIdentity
        val wrongSha = StoreValidationToken.issue("c".repeat(64), raw.size.toLong(), 1_000)
        val wrongSize = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong() + 1, 1_000)

        for (token in listOf(wrongSha, wrongSize)) {
            assertNull(
                TatBigrPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, dictionary, HEADS.size.toLong(),
                    raw.size.toLong(), token,
                ),
            )
        }
        val valid = BigramTestFixtures.raw(HEADS, tableDictionaryWords())
        assertNotNull(
            TatBigrPrefixIndex.open(
                ByteBuffer.wrap(valid), identity, dictionary, HEADS.size.toLong(),
                valid.size.toLong(), wrongSha,
            ),
        )
    }

    @Test
    fun bigramOpenWithAMatchingTokenYieldsAUsableIndex() {
        val raw = BigramTestFixtures.raw(HEADS, tableDictionaryWords())
        val dictionary = tableDictionary()
        val identity = EngineTestFixtures.bigramIdentity
        val token = StoreValidationToken.issue(identity.rawSha256, raw.size.toLong(), 1_000)

        val index = requireNotNull(
            TatBigrPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, dictionary, HEADS.size.toLong(), raw.size.toLong(),
                token,
            ),
        )

        assertEquals(listOf("аба"), index.predict(utf8Prefix("аб")))
        assertEquals(emptyList<String>(), index.predict(utf8Prefix("әби")))
    }

    @Test
    fun engineStartSkipsTheStructuralWalkOnAMatchingLeaseToken() {
        val raw = DictionaryTestFixtures.raw(OUT_OF_ORDER)
        val file = temporaryFolder.newFile("dictionary.tdict").also { it.writeBytes(raw) }
        val sha = DictionaryTestFixtures.sha256(raw)
        val token = StoreValidationToken.issue(sha, raw.size.toLong(), file.lastModified())

        val engine = startEngine(file, raw, OUT_OF_ORDER.size, token)

        assertNotNull(engine)
        assertTrue(engine!!.destroy(1, TimeUnit.SECONDS))
    }

    @Test
    fun engineStartFallsBackToTheFullWalkOnAStaleLeaseToken() {
        val raw = DictionaryTestFixtures.raw(OUT_OF_ORDER)
        val file = temporaryFolder.newFile("dictionary.tdict").also { it.writeBytes(raw) }
        val sha = DictionaryTestFixtures.sha256(raw)
        val staleToken =
            StoreValidationToken.issue(sha, raw.size.toLong(), file.lastModified() - 60_000)

        assertNull(startEngine(file, raw, OUT_OF_ORDER.size, staleToken))
        assertNull("control: the fixture fails the full walk", startEngine(file, raw, OUT_OF_ORDER.size, null))
    }

    @Test
    fun bigramAttachSkipsTheStructuralWalkOnAMatchingLeaseToken() {
        val artifact = DictionaryTestFixtures.artifact(1, WORDS)
        val dictionaryFile =
            temporaryFolder.newFile("dictionary.tdict").also { it.writeBytes(artifact.raw) }
        val engine = requireNotNull(
            startEngine(dictionaryFile, artifact.raw, WORDS.size, null),
        )
        val tableRaw = zeroCountTable(artifact.spec.expectedRawSha256, WORDS.map { it.first })
        val tableFile = temporaryFolder.newFile("table.tatbigr").also { it.writeBytes(tableRaw) }
        val table = PublishedBigramTable(
            1, "tt", tableFile, tableRaw.size.toLong(), HEADS.size.toLong(),
            HEADS.sumOf { it.second.size }.toLong(),
            HEADS.flatMap { it.second }.toSet().size.toLong(), 3, 1,
            BigramTestFixtures.sha256(tableRaw),
        )
        val token =
            StoreValidationToken.issue(table.rawSha256, table.rawSize, tableFile.lastModified())
        val mapper = DictionaryMapper { file, _ -> ByteBuffer.wrap(file.readBytes()) }

        val withoutToken = engine.attachBigramSource(bigramCatalogOf(BigramTableLease(table) {}), mapper)
        val withToken =
            engine.attachBigramSource(bigramCatalogOf(BigramTableLease(table, token) {}), mapper)

        assertFalse("control: the fixture table fails the full walk", withoutToken)
        assertTrue(withToken)
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
    }

    private fun startEngine(
        file: File,
        raw: ByteArray,
        entryCount: Int,
        token: StoreValidationToken?,
    ): MappedDictionaryEngine? {
        val dictionary = PublishedDictionary(
            1, file, raw.size.toLong(), entryCount.toLong(), 2, 1,
            DictionaryTestFixtures.sha256(raw),
        )
        var lease: DictionaryFileLease? = DictionaryFileLease(dictionary, token) {}
        val catalog = object : PublishedDictionaryCatalog {
            override fun acquireLatestForActivation(): DictionaryFileLease? =
                lease.also { lease = null }

            override fun cleanupReleasedVersions() = Unit
        }
        return MappedDictionaryEngine.start(
            catalog,
            ResultHandoff {},
            executorFactory = { ManualEngineExecutor() },
            mapper = DictionaryMapper { f, _ -> ByteBuffer.wrap(f.readBytes()) },
        )
    }

    private fun bigramCatalogOf(lease: BigramTableLease): PublishedBigramTableCatalog {
        var held: BigramTableLease? = lease
        return object : PublishedBigramTableCatalog {
            override fun acquireLatestForActivation(): BigramTableLease? =
                held.also { held = null }

            override fun cleanupReleasedVersions() = Unit
        }
    }

    private fun utf8Prefix(word: String) =
        ImmutableUtf8Prefix.copyOf(word.toByteArray(Charsets.UTF_8))

    private fun tableDictionaryWords(): List<String> =
        BigramTestFixtures.defaultDictionaryWords(HEADS)

    private fun tableDictionary(): TdictPrefixIndex =
        EngineTestFixtures.index(tableDictionaryWords().map { it to 1L })

    /**
     * A table whose header and block records are intact but whose first successor count is zero:
     * only the stream walk rejects it. The header names [dictionarySha] as its dictionary.
     */
    private fun zeroCountTable(
        dictionarySha: String = BigramTestFixtures.DEFAULT_DICTIONARY_SHA,
        dictionaryWords: List<String> = tableDictionaryWords(),
    ): ByteArray {
        val raw = BigramTestFixtures.raw(HEADS, dictionaryWords, dictionarySha)
        val countsOffset = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        raw[countsOffset] = 0
        return raw
    }

    private companion object {
        // Header and sections are intact; the words are out of order, which only the walk checks.
        private val OUT_OF_ORDER = listOf("абв" to 30L, "аба" to 20L)
        private val WORDS = listOf("аб" to 31L, "аба" to 20L, "әби" to 10L)
        private val HEADS = listOf("аб" to listOf("аба"))
    }
}
