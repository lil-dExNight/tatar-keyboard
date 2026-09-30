package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * One shipped dictionary asset.
 *
 * [family] and [storageDirectoryName] are literals, not derived from [languageTag]: after an
 * update a device must find an already inflated file under the same name and directory, or it
 * inflates the dictionary again.
 *
 * Each family owns its own directory. Retention, temp cleanup and leases are keyed by the canonical
 * directory path ([ProcessDictionaryStorageOwner]), so two families in one directory would share
 * one lease counter.
 */
data class DictionaryArtifactSpec(
    val family: String,
    val languageTag: String,
    val storageDirectoryName: String,
    val generation: Int,
    val assetPath: String,
    val expectedCompressedSize: Long,
    val expectedCompressedSha256: String,
    val expectedRawSize: Long,
    val expectedRawSha256: String,
    val expectedEntryCount: Long,
    /**
     * The next-word table of this language, or null when the language ships none. Keeping it here
     * leaves one language registry for both artifact kinds; [init] requires the language tags to
     * match.
     */
    val bigrams: BigramArtifactSpec? = null,
    /**
     * The sentence-start table asset of this language, or null when it ships none. It is a plain
     * text asset without a storage contract; its pins live in `tests/sentstart_pack/` and the
     * `*SentStartAssetTest` JVM tests.
     */
    val sentStartAssetPath: String? = null,
    /**
     * The lowercase alphabet the personal dictionary filters this language's words by, or null
     * when the language has no personal dictionary. `PersonalSubtypes.alphabetFor` reads this
     * field, so the language list lives only here; the alphabets are defined in `PersonalSubtypes`.
     */
    val personalAlphabet: Set<Int>? = null,
    val schemaId: Int = TdictFormat.SCHEMA_ID,
    val formatVersion: Int = TdictFormat.FORMAT_VERSION,
    val maxCompressedSize: Long = TdictFormat.MAX_COMPRESSED_SIZE,
    val maxRawSize: Long = TdictFormat.MAX_RAW_SIZE,
) {
    init {
        require(generation > 0)
        require(FAMILY_PATTERN.matches(family))
        require(languageTag.isNotBlank())
        require(FAMILY_PATTERN.matches(storageDirectoryName.replace('-', '_')))
        require(assetPath.isNotBlank())
        require(sentStartAssetPath == null || sentStartAssetPath.isNotBlank())
        require(expectedCompressedSize in 1..maxCompressedSize)
        require(expectedRawSize in TdictFormat.HEADER_SIZE.toLong()..maxRawSize)
        require(expectedEntryCount > 0)
        require(expectedCompressedSha256.isSha256())
        require(expectedRawSha256.isSha256())
        require(bigrams == null || bigrams.subtypeId == languageTag)
    }

    val finalFileName: String
        get() = String.format(
            Locale.ROOT,
            "%s-v%06d-s%d-f%d-%s.tdict",
            family,
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
        get() = Regex("${Regex.escape(family)}-v[0-9]{6}-s[0-9]+-f[0-9]+-[0-9a-f]{64}\\.tdict")

    companion object {
        private val FAMILY_PATTERN = Regex("[a-z][a-z0-9_]*")

        /**
         * The Tatar dictionary. The family name and the directory are frozen: changing either
         * makes every device that already inflated this file inflate it again.
         */
        @JvmField
        val TATAR_TOP100K_V1 = DictionaryArtifactSpec(
            family = "tatar_top100k",
            languageTag = PersonalSubtypes.TATAR_RU,
            storageDirectoryName = "dictionaries",
            generation = 1,
            assetPath = "dictionaries/tatar_top100k_v1.tdict.zlib",
            expectedCompressedSize = 542_493,
            expectedCompressedSha256 =
                "e653ef6ee9d88fd25cd7802e59bb57b954be80d9b7ea897c849be66919fa96ed",
            expectedRawSize = 1_276_289,
            expectedRawSha256 =
                "3634f021c056b90ab1eb042bf6bccfa1413d31af96993120e77bdcf843152518",
            expectedEntryCount = 110_000,
            bigrams = BigramArtifactSpec.TATAR_BIGRAMS_V1,
            sentStartAssetPath = "dictionaries/tatar_sentstart_v1.txt",
            personalAlphabet = PersonalSubtypes.TATAR_RU_ALPHABET,
        )

        /**
         * The Russian dictionary, in its own family and directory so the Tatar file is neither
         * renamed nor counted against this language's retention.
         *
         * `TdictValidator` checks the words against the Tatar alphabet, a superset of the Russian
         * one; for a shipped asset the exact SHA-256 match is the real guard.
         */
        @JvmField
        val RUSSIAN_TOP100K_V1 = DictionaryArtifactSpec(
            family = "russian_top100k",
            languageTag = PersonalSubtypes.RUSSIAN,
            storageDirectoryName = "dictionaries-ru",
            generation = 1,
            assetPath = "dictionaries/russian_top100k_v1.tdict.zlib",
            expectedCompressedSize = 539_948,
            expectedCompressedSha256 =
                "273f1a6928f74fdbd7f0fda4490978124b3748160ac283d0b51e8c913fea5fde",
            expectedRawSize = 1_151_323,
            expectedRawSha256 =
                "f05499a3b4c3c2811c6ee8e9084dfaf472951a289a20dde2ab8cb098cb5a15b2",
            expectedEntryCount = 100_000,
            bigrams = BigramArtifactSpec.RUSSIAN_BIGRAMS_V1,
            sentStartAssetPath = "dictionaries/russian_sentstart_v1.txt",
            personalAlphabet = PersonalSubtypes.RUSSIAN_ALPHABET,
        )

        /**
         * Every language the app ships, newest last. Callers resolve dictionaries and next-word
         * tables through [forSubtype] instead of testing subtype identifiers themselves.
         */
        @JvmField
        val ALL: List<DictionaryArtifactSpec> = listOf(TATAR_TOP100K_V1, RUSSIAN_TOP100K_V1)

        /** The dictionary of [subtypeId], or null when that subtype ships none. */
        @JvmStatic
        fun forSubtype(subtypeId: String): DictionaryArtifactSpec? =
            ALL.firstOrNull { it.languageTag == subtypeId }

        /**
         * The next-word table of [subtypeId], or null when the subtype ships no dictionary or its
         * language has no table. Then NEXT_WORD answers an empty list, never another language's.
         */
        @JvmStatic
        fun bigramsForSubtype(subtypeId: String): BigramArtifactSpec? =
            forSubtype(subtypeId)?.bigrams

        /**
         * The sentence-start table asset path of [subtypeId], or null when there is none; then the
         * sentence-start cell stays empty.
         */
        @JvmStatic
        fun sentStartAssetForSubtype(subtypeId: String): String? =
            forSubtype(subtypeId)?.sentStartAssetPath
    }
}

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

fun interface AssetInputProvider {
    fun open(spec: DictionaryArtifactSpec): InputStream
}

fun interface StorageClock {
    fun nowMillis(): Long
}

fun interface SpaceProbe {
    fun usableBytes(directory: File): Long
}

fun interface DeviceProtectedDirectoryProvider {
    fun dictionaryDirectory(): File
}

interface DurableFileOps {
    fun createNewFile(file: File): Boolean
    fun syncFile(fileDescriptor: FileDescriptor)
    fun atomicRename(source: File, destination: File)
    fun syncDirectory(directory: File)
    fun delete(file: File): Boolean

    /**
     * Atomically replaces [destination] with [source], replacing an existing destination.
     *
     * Unlike [atomicRename], which throws when the destination exists (staged publication relies on
     * that), this serves the personal store, which rewrites the same file. `AndroidDurableFileOps`
     * uses POSIX `rename(2)`; this default is a JVM fallback for test doubles.
     */
    fun atomicReplace(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            if (!(destination.delete() && source.renameTo(destination))) {
                throw IOException("atomic replace failed")
            }
        }
    }
}

data class PublishedDictionary(
    val generation: Int,
    val file: File,
    val rawSize: Long,
    val entryCount: Long,
    val schemaId: Int,
    val formatVersion: Int,
    val rawSha256: String,
)

enum class StorageFailure {
    INVALID_ASSET,
    NO_SPACE,
    IO,
    RETENTION_BLOCKED,
    EXECUTOR_REJECTED,
}

sealed class PreparationResult {
    data class Published(
        val dictionary: PublishedDictionary,
        val alreadyPresent: Boolean,
    ) : PreparationResult()

    data class Unavailable(val reason: StorageFailure) : PreparationResult()
}

/**
 * Catalog consumed by the dictionary engine. acquireLatestForActivation performs validation I/O and
 * must run off the UI thread. The engine may call it only after its executor is stopped and its
 * reader count is zero.
 * The returned lease must remain open for the complete mapping/executor lifetime. There is no
 * live hot-swap operation: close an old lease only after that version has no readers.
 */
interface PublishedDictionaryCatalog {
    /**
     * Returns the newest validated dictionary that is safe to activate.
     *
     * A lease represents the complete mapping/executor/reader lifetime. If a different
     * dictionary version is still leased by any store instance for this directory, this method
     * returns null. Callers must stop the old executor, wait for its readers, and close its lease
     * before retrying at the next safe lifecycle boundary.
     */
    fun acquireLatestForActivation(): DictionaryFileLease?
    fun cleanupReleasedVersions()
}

/**
 * One lifecycle surface for background preparation and safe dictionary activation.
 *
 * AndroidDictionaryStorageFactory may return multiple controller objects, for example after an
 * IME service recreation. Their stores still coordinate through process-wide state keyed by the
 * device-protected directory.
 */
class DictionaryStorageController internal constructor(
    private val preparer: BackgroundDictionaryPreparer,
    private val catalog: PublishedDictionaryCatalog,
) : PublishedDictionaryCatalog {
    fun prepare(callback: (PreparationResult) -> Unit) = preparer.prepare(callback)

    override fun acquireLatestForActivation(): DictionaryFileLease? =
        catalog.acquireLatestForActivation()

    override fun cleanupReleasedVersions() = catalog.cleanupReleasedVersions()
}

class DictionaryFileLease internal constructor(
    val dictionary: PublishedDictionary,
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

internal object TdictFormat {
    const val MAGIC = "TATDICT\u0000"
    const val SCHEMA_ID = 2
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 72
    const val CHECKSUM_OFFSET = 40
    const val CHECKSUM_SIZE = 32
    const val CHECKSUM_ALGORITHM_SHA256 = 1
    // Schema 2: front-coded blocks of BLOCK_SIZE words. The first word of a block is stored whole
    // (u8 length); every other word as a varint prefix length shared with the block's first word
    // plus a u8-length suffix; then one varint frequency per word. K = 8 gives the smallest zlib
    // size and bounded decode work (at most 7 words per access).
    const val BLOCK_SIZE = 8
    const val MAX_WORD_BYTES = 128
    // Size limits: the shipped assets plus headroom comparable to schema 1.
    const val MAX_COMPRESSED_SIZE = 600_000L
    const val MAX_RAW_SIZE = 1_400_000L
    const val MAX_U32 = 0xffff_ffffL
}
