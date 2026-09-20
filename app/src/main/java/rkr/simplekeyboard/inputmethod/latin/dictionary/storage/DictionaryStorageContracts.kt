package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import androidx.annotation.Keep
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
 * [family] and [storageDirectoryName] are what make the artifact multilingual. Both are literals
 * of the spec rather than values derived from [languageTag], and deliberately so: the Tatar
 * artifact shipped in 1.6.1 under the name `tatar_top100k-v…` in `<device-protected>/dictionaries`,
 * and a device that updates to a build with a second language must find that exact file where it
 * left it. A tidier scheme (one directory, one `<lang>_top100k` name for everyone) would rename a
 * file the user already has and pay for it with a needless 2.5 MB re-inflation — or, if the
 * rename were only partial, with a store that cannot find its own dictionary. Backward
 * compatibility wins; the family is the seam that lets a new language pick its own name without
 * touching the old one.
 *
 * Each family owns its OWN directory. The store's retention, temp-cleanup and lease bookkeeping
 * are all keyed by the canonical directory path
 * ([ProcessDictionaryStorageOwner]), so two families sharing one directory would share one lease
 * counter and the second language could never be activated while the first held a lease.
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
     * The next-word table of this language, or null when the language ships none.
     *
     * This field is what makes "which language is active" a question with ONE answer. A bigram
     * table is a separate artifact with a separate schema, validator and store — none of that
     * changes — but it is not a separate *language*, and giving it its own subtype-to-artifact
     * resolver would make two independent copies of one rule, which is exactly how a fix ends up
     * landing in one copy and silently not in the other. So the language registry stays single:
     * [ALL] lists the languages, [forSubtype] answers for both artifact kinds, and a language
     * whose table is missing says so here, in the open, instead of through a factory that returns
     * null for reasons the reader has to reconstruct.
     *
     * A table can therefore never exist for a language that has no dictionary, and the two can
     * never disagree about which language they belong to: the [init] block below requires the
     * language tags to match.
     */
    val bigrams: BigramArtifactSpec? = null,
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
         * D1a, shipped since 1.1.0. The family name and the directory are FROZEN: changing either
         * makes every device that already inflated this file inflate it again.
         *
         * Repacked 2026-08-24 for 1.9.1: 303 conversational forms displaced 303 of the least
         * frequent Leipzig forms. 1.9.0 took 226 of them under the machine rule of
         * `docs/DICT-ACCEPT.md`; the operator then read a sample of what that rule had turned
         * away, judged it ordinary Tatar and lifted the bar, so 1.9.1 takes the whole queue bar
         * five vowelless fragments — `docs/DICT-WIDEN.md`. The 100 000 entries and the family
         * are unchanged, so the file NAME still changes — it carries the raw SHA-256 — and a
         * device updating from 1.8.4 or 1.9.0 inflates the new file once and drops the old one
         * through the ordinary retention path.
         *
         * Repacked 2026-09-20 (TT-SUGGESTIONS P2, `docs/TT-SUGGESTIONS.md`): the entry cap moved
         * 100 000 → 110 000 — the largest measured size that keeps the compressed budget — so the
         * dictionary now also carries 9 052 corpus-attested word forms generated at build time by
         * the project's own paradigm generator (`scripts/wordform_gen.py`, frequency = corpus
         * count) plus 645 further conversational words that the old cap had cut. No 1.8.4 word
         * is displaced. The family, the schema and the file-name rule are unchanged.
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
        )

        /**
         * The Russian top-100k, packed 2026-08-21 by `scripts/dictionary_pack.py build
         * --language rus` from three Leipzig corpora — `docs/RUSSIAN-DICTIONARY.md` records the
         * sources, the alphabet decisions and every number below.
         *
         * Repacked 2026-08-24 for 1.9.1 by `scripts/dict_accept.py pack --write`: 32 833
         * conversational forms displaced 32 833 of the least frequent Leipzig forms. 1.9.0 took
         * 27 134 of them under the machine rule of `docs/DICT-ACCEPT.md`; the operator then read
         * a sample of what that rule had turned away, judged it ordinary Russian and lifted the
         * bar, so 1.9.1 takes the whole queue bar 417 formal fragments and one word the operator
         * named — `docs/DICT-WIDEN.md`. Leipzig is still the source of the other 67 167 and of
         * every frequency in them; the accepted forms carry their conversational counts.
         * `app/src/main/assets/dictionaries/NOTICE.txt` names the two collections those counts
         * come from.
         *
         * Its own family and its own directory, so the Tatar file already inflated on a device
         * updating from 1.6.1 is neither renamed, re-inflated, nor counted against this
         * language's retention budget.
         *
         * The words are checked against `TdictValidator`'s Tatar alphabet, which is a strict
         * SUPERSET of the Russian one — every Russian letter is a Tatar letter. That check is
         * therefore weaker for this artifact than for the Tatar one, and deliberately left as it
         * is: for a SHIPPED asset the exact-SHA-256 match below is the real guard, and the
         * alphabet check only ever backs it up against corruption that the checksum, the UTF-8
         * decode and the sort-order check would all have caught first.
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
        )

        /**
         * Every language the app ships, newest last. This list IS the answer to "which languages
         * have a dictionary" AND to "which of them also predict the next word": the suggestion
         * controller, both storage factories and the settings screen all resolve through
         * [forSubtype] rather than testing subtype identifiers of their own.
         */
        @JvmField
        val ALL: List<DictionaryArtifactSpec> = listOf(TATAR_TOP100K_V1, RUSSIAN_TOP100K_V1)

        /** The dictionary of [subtypeId], or null when that subtype ships none. */
        @JvmStatic
        fun forSubtype(subtypeId: String): DictionaryArtifactSpec? =
            ALL.firstOrNull { it.languageTag == subtypeId }

        /**
         * The next-word table of [subtypeId], or null when that subtype ships no table — either
         * because it ships no dictionary at all, or because its language has no table yet.
         *
         * Both cases leave NEXT_WORD answering an empty list, which is the fail-closed behaviour a
         * missing table has always had: silence, never another language's predictions. Which of
         * the two cases holds is readable off [ALL] — a language present with `bigrams == null`
         * has no table; a subtype absent from [ALL] has no dictionary either.
         */
        @JvmStatic
        fun bigramsForSubtype(subtypeId: String): BigramArtifactSpec? =
            forSubtype(subtypeId)?.bigrams
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
     * Atomically replaces [destination] with [source], REPLACING an existing destination.
     *
     * This is deliberately distinct from [atomicRename], which throws when the destination already
     * exists: D1b's staged-publication retention depends on that throwing behaviour, so its
     * semantics must not change. The personal store's whole-file write (E4a-2) needs the replacing
     * variant instead, because it rewrites the same file across a session.
     *
     * The production override in `AndroidDurableFileOps` uses POSIX `rename(2)`, which is atomic and
     * replaces in place. This default is only a JVM fallback for test doubles that do not override
     * it; it is never reached by the dictionary-asset store.
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
 * Catalog consumed by D1d. acquireLatestForActivation performs validation I/O and must run off
 * the UI thread. D1d may call it only after its executor is stopped and its reader count is zero.
 * The returned lease must remain open for the complete mapping/executor lifetime. There is no
 * live hot-swap operation: close an old lease only after that version has no readers.
 */
@Keep
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
@Keep
class DictionaryStorageController internal constructor(
    private val preparer: BackgroundDictionaryPreparer,
    private val catalog: PublishedDictionaryCatalog,
) : PublishedDictionaryCatalog {
    fun prepare(callback: (PreparationResult) -> Unit) = preparer.prepare(callback)

    override fun acquireLatestForActivation(): DictionaryFileLease? =
        catalog.acquireLatestForActivation()

    override fun cleanupReleasedVersions() = catalog.cleanupReleasedVersions()
}

@Keep
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
    // Schema 2 (SIZE-1, docs/SIZE-SCHEMA2.md): блоки front-coding по BLOCK_SIZE слов —
    // первое слово целиком (u8 длина), остальные как varint общего префикса с первым
    // словом блока + u8 суффикс; затем по varint-частоте на слово. K = 8 выбрано замером
    // 2026-09-01: минимум и по zlib-размеру, и по работе декода (≤ 7 слов на доступ).
    const val BLOCK_SIZE = 8
    const val MAX_WORD_BYTES = 128
    // Бюджеты пересмотрены под измеренное (татарский 501 683/1 162 870, русский
    // 539 948/1 151 323) с запасом, соразмерным schema 1.
    const val MAX_COMPRESSED_SIZE = 600_000L
    const val MAX_RAW_SIZE = 1_400_000L
    const val MAX_U32 = 0xffff_ffffL
}
