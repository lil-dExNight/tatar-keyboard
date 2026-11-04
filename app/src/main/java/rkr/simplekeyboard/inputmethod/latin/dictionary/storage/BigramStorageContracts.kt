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

        // The pins between the marker pairs are rewritten by scripts/rebuild_assets.py.
        /** The Tatar bigram table; its pinned sizes, SHA-256 values and head count follow. */
        @JvmField
        // <generated pins>
        val TATAR_BIGRAMS_V1 = BigramArtifactSpec(
            family = "tatar_bigrams",
            generation = 1,
            fileLanguageTag = "tt",
            subtypeId = PersonalSubtypes.TATAR_RU,
            storageDirectoryName = "bigrams",
            assetPath = "bigrams/tatar_bigrams_v1.tatbigr.zlib",
            expectedCompressedSize = 212_223,
            expectedCompressedSha256 =
                "c5d07358e2a1f5c0f18cd05f1218a33af157f6ba1588c72e225e18949b1c6004",
            expectedRawSize = 306_632,
            expectedRawSha256 =
                "f56240733a15881ebd3dc4a295e7655bfafd81073ca6b8f19974f102d0c17a8b",
            expectedDictionaryRawSha256 =
                "811d7f8c571c6b4dfb7e66b05b2688037eeb02eb3c857c8eba9b1ee9d85819af",
            expectedHeadCount = 12_906,
        )
        // </generated pins>

        /** The Russian bigram table, in its own family and directory; pins follow. */
        @JvmField
        // <generated pins>
        val RUSSIAN_BIGRAMS_V1 = BigramArtifactSpec(
            family = "russian_bigrams",
            generation = 1,
            fileLanguageTag = "ru",
            subtypeId = PersonalSubtypes.RUSSIAN,
            storageDirectoryName = "bigrams-ru",
            assetPath = "bigrams/russian_bigrams_v1.tatbigr.zlib",
            expectedCompressedSize = 134_274,
            expectedCompressedSha256 =
                "11dcdd1e751f6e5455ac643f09ca432b59802cfd4d1191a574a4a537b467f610",
            expectedRawSize = 242_234,
            expectedRawSha256 =
                "a4a2c15d886df27b83d19e6d2554cd62a092edee5ac8b1aa14207596d19c46ed",
            expectedDictionaryRawSha256 =
                "f05499a3b4c3c2811c6ee8e9084dfaf472951a289a20dde2ab8cb098cb5a15b2",
            expectedHeadCount = 10_000,
        )
        // </generated pins>
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
    /**
     * Proof the store validated [table]'s content in full or verified it against the pinned
     * content in this process; null from a hand-built catalog. See [StoreValidationToken].
     */
    val validationToken: StoreValidationToken? = null,
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
