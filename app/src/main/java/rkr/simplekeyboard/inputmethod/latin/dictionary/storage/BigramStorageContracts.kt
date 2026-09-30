package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import java.io.Closeable
import java.util.Locale

/**
 * One shipped bigram table (`TATBIGR\0`, schema 3), an artifact kind separate from
 * [DictionaryArtifactSpec].
 *
 * [fileLanguageTag] is the short tag in the on-disk file name, frozen at `tt` for the Tatar table;
 * [subtypeId] is the IME subtype the table serves (`tt_RU`). Only [subtypeId] selects a table.
 * [family] and [storageDirectoryName] are literals with the same meaning as on
 * [DictionaryArtifactSpec]: each family owns its own directory. Which subtype gets which table is
 * decided by [DictionaryArtifactSpec.forSubtype].
 */
data class BigramArtifactSpec(
    val family: String,
    val generation: Int,
    val fileLanguageTag: String,
    val subtypeId: String,
    val storageDirectoryName: String,
    val assetPath: String,
    val expectedCompressedSize: Long,
    val expectedCompressedSha256: String,
    val expectedRawSize: Long,
    val expectedRawSha256: String,
    /**
     * Raw SHA-256 of the TATDICT dictionary this table indexes into. Heads and successors are
     * indices into exactly that dictionary; the validator and the reader refuse any other pairing.
     */
    val expectedDictionaryRawSha256: String,
    val expectedHeadCount: Long,
    val schemaId: Int = TatBigrFormat.SCHEMA_ID,
    val formatVersion: Int = TatBigrFormat.FORMAT_VERSION,
    val maxCompressedSize: Long = TatBigrFormat.MAX_COMPRESSED_SIZE,
    val maxRawSize: Long = TatBigrFormat.MAX_RAW_SIZE,
) {
    init {
        require(generation > 0)
        require(FAMILY_PATTERN.matches(family))
        require(FILE_LANGUAGE_TAG_PATTERN.matches(fileLanguageTag))
        require(subtypeId.isNotBlank())
        require(FAMILY_PATTERN.matches(storageDirectoryName.replace('-', '_')))
        require(assetPath.isNotBlank())
        require(expectedCompressedSize in 1..maxCompressedSize)
        require(expectedRawSize in TatBigrFormat.HEADER_SIZE.toLong()..maxRawSize)
        require(expectedHeadCount > 0)
        require(expectedCompressedSha256.isBigramSha256())
        require(expectedRawSha256.isBigramSha256())
        require(expectedDictionaryRawSha256.isBigramSha256())
    }

    val finalFileName: String
        get() = String.format(
            Locale.ROOT,
            "%s-%s-v%06d-s%d-f%d-%s.tatbigr",
            family,
            fileLanguageTag,
            generation,
            schemaId,
            formatVersion,
            expectedRawSha256.lowercase(),
        )

    /** The temp-file prefix of this family; never shared with another family's directory. */
    val temporaryFilePrefix: String
        get() = ".$family-"

    /** Matches exactly the final files this family owns, and nothing else. */
    val finalFilePattern: Regex
        get() = Regex(
            "${Regex.escape(family)}-[a-z]{2,3}-v[0-9]{6}-s[0-9]+-f[0-9]+-[0-9a-f]{64}\\.tatbigr",
        )

    companion object {
        private val FAMILY_PATTERN = Regex("[a-z][a-z0-9_]*")

        /** Exactly what [finalFilePattern] accepts in the file name's language position. */
        private val FILE_LANGUAGE_TAG_PATTERN = Regex("[a-z]{2,3}")

        /** The Tatar bigram table; its pinned sizes, SHA-256 values and head count follow. */
        @JvmField
        val TATAR_BIGRAMS_V1 = BigramArtifactSpec(
            family = "tatar_bigrams",
            generation = 1,
            fileLanguageTag = "tt",
            subtypeId = PersonalSubtypes.TATAR_RU,
            storageDirectoryName = "bigrams",
            assetPath = "bigrams/tatar_bigrams_v1.tatbigr.zlib",
            expectedCompressedSize = 104_028,
            expectedCompressedSha256 =
                "2c892ce51129d28f13dc8b3ca32e37cef41a3f4e4ae0d1157cbbc8a3824544de",
            expectedRawSize = 170_471,
            expectedRawSha256 =
                "2264136bdb1a9f8095c7efa364eb18e2e7eaa7b05986962956bbe890bacedbbe",
            expectedDictionaryRawSha256 =
                "3634f021c056b90ab1eb042bf6bccfa1413d31af96993120e77bdcf843152518",
            expectedHeadCount = 13_154,
        )

        /** The Russian bigram table, in its own family and directory; pins follow. */
        @JvmField
        val RUSSIAN_BIGRAMS_V1 = BigramArtifactSpec(
            family = "russian_bigrams",
            generation = 1,
            fileLanguageTag = "ru",
            subtypeId = PersonalSubtypes.RUSSIAN,
            storageDirectoryName = "bigrams-ru",
            assetPath = "bigrams/russian_bigrams_v1.tatbigr.zlib",
            expectedCompressedSize = 63_312,
            expectedCompressedSha256 =
                "aa25f6292f4d645c6c1fbb52a2744dfdfcf923f835cb1e42c04dac22250ae817",
            expectedRawSize = 131_662,
            expectedRawSha256 =
                "20a228481952097b0001a057bb4615aba1b60ba2bf0becd3cdc27538532fb667",
            expectedDictionaryRawSha256 =
                "f05499a3b4c3c2811c6ee8e9084dfaf472951a289a20dde2ab8cb098cb5a15b2",
            expectedHeadCount = 9_998,
        )
    }
}

private fun String.isBigramSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * A validated, published bigram table. Separate from [PublishedDictionary] because a word list and
 * a head/successor table have different shapes, and the state owner must know which one it holds.
 */
data class PublishedBigramTable(
    val generation: Int,
    val fileLanguageTag: String,
    val file: java.io.File,
    val rawSize: Long,
    val headCount: Long,
    val pairCount: Long,
    val successVocabularyCount: Long,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

sealed class BigramPreparationResult {
    data class Published(
        val table: PublishedBigramTable,
        val alreadyPresent: Boolean,
    ) : BigramPreparationResult()

    data class Unavailable(val reason: StorageFailure) : BigramPreparationResult()
}

interface PublishedBigramTableCatalog {
    fun acquireLatestForActivation(): BigramTableLease?
    fun cleanupReleasedVersions()
}

class BigramTableLease internal constructor(
    val table: PublishedBigramTable,
    private val release: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}

/**
 * One lifecycle surface for background bigram-table preparation and safe activation; the bigram
 * counterpart of [DictionaryStorageController].
 */
class BigramStorageController internal constructor(
    private val preparer: BackgroundBigramPreparer,
    private val catalog: PublishedBigramTableCatalog,
) : PublishedBigramTableCatalog {
    fun prepare(callback: (BigramPreparationResult) -> Unit) = preparer.prepare(callback)

    override fun acquireLatestForActivation(): BigramTableLease? =
        catalog.acquireLatestForActivation()

    override fun cleanupReleasedVersions() = catalog.cleanupReleasedVersions()
}

class BackgroundBigramPreparer(
    private val executor: java.util.concurrent.Executor,
    private val store: AtomicBigramStore,
    private val artifact: BigramArtifactSpec,
) {
    fun prepare(callback: (BigramPreparationResult) -> Unit) {
        val taskStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            executor.execute {
                taskStarted.set(true)
                callback(store.ensurePublished(artifact))
            }
        } catch (error: RuntimeException) {
            if (taskStarted.get()) throw error
            callback(BigramPreparationResult.Unavailable(StorageFailure.EXECUTOR_REJECTED))
        }
    }
}

internal object TatBigrFormat {
    const val MAGIC = "TATBIGR\u0000"
    const val SCHEMA_ID = 3
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 128
    const val CHECKSUM_OFFSET = 96
    const val CHECKSUM_SIZE = 32
    const val CHECKSUM_ALGORITHM_SHA256 = 1
    const val HEAD_BLOCK_SIZE = 64
    const val MAX_COMPRESSED_SIZE = 250_000L
    const val MAX_RAW_SIZE = 1_048_576L
    const val MAX_U32 = 0xffff_ffffL
}
