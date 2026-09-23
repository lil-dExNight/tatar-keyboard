package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import androidx.annotation.Keep
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import java.io.Closeable
import java.util.Locale

/**
 * E5b: the shipped bigram-table asset, schema 3 (`TATBIGR\0`, cross-referenced into the linked
 * dictionary since SIZE-2, `docs/SIZE-SCHEMA3.md`) — a SEPARATE artifact kind from
 * [DictionaryArtifactSpec]'s schema 1, per PROPOSALS.md ("E5b. Отдельный файл и отдельная
 * схема"). [fileLanguageTag] parameterized storage "with language from the start" (contract
 * wording) back when only the Tatar table shipped; the second language duly added a second spec
 * rather than a second class.
 *
 * [fileLanguageTag] and [subtypeId] are NOT the same string and must not be merged. The first is
 * the short tag baked into the on-disk file name and FROZEN at `tt` for the Tatar table, which
 * devices inflated under that name in 1.6.0; the second is the IME subtype the table serves, and
 * for Tatar that identifier is `tt_RU`. Only [subtypeId] takes part in choosing a table, and
 * [DictionaryArtifactSpec] requires it to equal the language's own.
 *
 * [family] and [storageDirectoryName] complete that parameterization and carry exactly the
 * meaning they carry on [DictionaryArtifactSpec]: literals of the spec, never derived from
 * [fileLanguageTag], because the Tatar table shipped in 1.6.0 as `tatar_bigrams-tt-…` inside
 * `<device-protected>/bigrams` and a device updating to a build with a second language must find
 * that file where it left it. Each family owns its OWN directory for the same reason the
 * dictionaries do: `ProcessBigramStorageOwner` keys its lease bookkeeping by the canonical
 * directory path, so two families in one directory would share one lease counter and the second
 * language could never be activated while the first held a lease.
 *
 * Which subtype gets which table is NOT answered here. It is answered once, for both artifact
 * kinds together, by [DictionaryArtifactSpec.forSubtype] — see the note on
 * [DictionaryArtifactSpec.bigrams].
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
     * Raw SHA-256 of the TATDICT schema-2 dictionary this schema-3 table cross-references
     * (SIZE-2, `docs/SIZE-SCHEMA3.md`): heads and successes are indices into exactly that
     * dictionary, the table header names it, and both the validator and the runtime reader
     * refuse a table paired with any other dictionary.
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

        /**
         * The tt table packed by `scripts/bigram_asset_pack.py pack` at **H = 10 132**, K = 4,
         * with `--extra-heads scripts/bigram_extra_heads_tat.txt`. H and K are unchanged since
         * 2026-08-25 (docs/archive/bigrams/IMPERATIVE-HEADS.md: the cutoff keeps the 78 heads a
         * 10 000-cutoff repack would have dropped, the list promotes imperatives regardless of
         * rank).
         *
         * Repacked 2026-08-31 with the conversational admixture (`docs/CORPUS-CONVERSATIONAL-TT.md`,
         * corpus-conversational part B): training is the two Leipzig corpora plus a deduplicated
         * Tatar Tatoeba + OpenSubtitles input (lines with `id % 10 != 1`; the rest is the
         * conversational held-out). No thinning — the conversational mass is 3,7 % of the written
         * one. The extra-heads rule was extended the same day, before the run, from ranks
         * [10 000, 15 000) to [10 000, 40 000) with pairs required in the new mixed training:
         * 13 → **75** named words, and all six dossier imperatives beyond rank 15 000
         * (`шалтырат`, `сөйлә`, `утыр`, `җибәр`, `эшлә`, `укы`) are heads now.
         *
         * 10 204 heads = 10 129 (cutoff) + 75 (list): three frequency-selected `-гәнчә` converbs
         * still never head an in-vocabulary pair and are dropped rather than stored with an empty
         * range (docs/archive/bigrams/DICTIONARY-E5B.md, "Dropped heads"; recorded in
         * scripts/known_asset_drift.json). 594 of the 10 142 retained heads change the displayed
         * triple — genre permutation among live dictionary words, measured head-by-head in the
         * part-B report; the conversational data follows the terms recorded in
         * assets/bigrams/NOTICE.txt.
         *
         * Repacked 2026-09-01 to TATBIGR schema 3 (SIZE-2, `docs/SIZE-SCHEMA3.md`) from the
         * schema-2 asset, corpus-free and verified word-for-word identical (all 10 204 heads,
         * 40 734 pairs): word blobs are gone, heads and successes are varint indices into the
         * dictionary pinned by [expectedDictionaryRawSha256].
         *
         * Repacked 2026-09-20 (TT-SUGGESTIONS P2, `docs/TT-SUGGESTIONS.md`) against the
         * 110 000-entry dictionary: the head set is identical (admitted word forms enter far
         * below the H = 10 132 cutoff), the same three `-гәнчә` converbs stay pairless
         * (`scripts/known_asset_drift.json` keeps 3/0), and ten pairs whose successor was outside
         * the old dictionary now count — 40 734 → 40 735 pairs.
         *
         * Repacked 2026-09-23 (ROADMAP-P4 batch A, `docs/ROADMAP-P4.md`): T7 — successes per
         * head 4 → 3 (the rank-4 share measured at 2.08 % of covered eval pairs is latent for a
         * future 4-cell strip, not worth the bytes today), and P5a option (b) — the extra-heads
         * list expanded by the EXPAND-1 rule (+3 102 conversationally established words below the
         * frequency cutoff, `scripts/bigram_extra_heads_tat.txt` + `scripts/bigram_extra_heads_conv.py`).
         * Heads 10 204 → 13 154, pairs 40 735 → 38 874, compressed 81 476 → 79 574 B; eval
         * next-word coverage 75.3623 % → 84.1730 %, top-3 9.5468 % → 10.8351 %. 152 of the 3 177
         * address candidates stay pairless (their mates are out-of-dictionary) — the known-drift
         * record moves to 155/0 with the same three converbs inside.
         */
        @JvmField
        val TATAR_BIGRAMS_V1 = BigramArtifactSpec(
            family = "tatar_bigrams",
            generation = 1,
            fileLanguageTag = "tt",
            subtypeId = PersonalSubtypes.TATAR_RU,
            storageDirectoryName = "bigrams",
            assetPath = "bigrams/tatar_bigrams_v1.tatbigr.zlib",
            expectedCompressedSize = 79_574,
            expectedCompressedSha256 =
                "283661b4b9db87b2ba8b2d606bed5e2ebc8463a0954615a36502763b6fedb3f9",
            expectedRawSize = 135_889,
            expectedRawSha256 =
                "87af8ba35da0df92f6113fa1452fa7060ba335d8fe0a48aadaf93e0208825fd8",
            expectedDictionaryRawSha256 =
                "3634f021c056b90ab1eb042bf6bccfa1413d31af96993120e77bdcf843152518",
            expectedHeadCount = 13_154,
        )

        /**
         * The ru table packed by `scripts/bigram_asset_pack.py pack --language rus` at
         * H = 10 000 / **K = 4** — `docs/archive/bigrams/RUSSIAN-BIGRAMS.md` records the matrix
         * that chose H and the original K = 6; docs/archive/bigrams/BIGRAM-ADJACENCY.md records
         * the drop to K = 4.
         *
         * Repacked 2026-08-31 against the current shipped dictionary (4 195-head drift closed,
         * `docs/RUSSIAN-BIGRAMS-REPACK.md`) and **repacked again the same day with the
         * conversational admixture** (`docs/CORPUS-CONVERSATIONAL-RU.md`, corpus-conversational
         * part A): training is the three Leipzig corpora plus a deduplicated, 1/60-thinned
         * Tatoeba + OpenSubtitles input of exactly one Leipzig corpus mass. 57 of the 59
         * previously silent conversational top-10 000 words (`погоди`, `волнуйся`, …) are heads
         * now; `окей` and `берегись` still have no in-vocabulary pair in the thinned input and
         * are dropped rather than stored with an empty range — the same generator rule as the
         * Tatar `-гәнчә` converbs. The conversational data follows the terms recorded in
         * `assets/dictionaries/NOTICE.txt` (Tatoeba CC BY 2.0 FR; OpenSubtitles has NO license
         * grant — operator decision of 2026-08-24, `docs/archive/dictionary/CORPUS-OS.md`).
         *
         * Its own family and its own directory, so the Tatar table already inflated on a device
         * updating from 1.7.0 is neither renamed, re-inflated, nor sharing this language's lease
         * counter.
         *
         * Repacked 2026-09-01 to TATBIGR schema 3 (SIZE-2, `docs/SIZE-SCHEMA3.md`) from the
         * schema-2 asset, corpus-free and verified word-for-word identical (all 9 998 heads,
         * 39 949 pairs): word blobs are gone, heads and successes are varint indices into the
         * dictionary pinned by [expectedDictionaryRawSha256].
         */
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
 * What a validated bigram table looks like once published — deliberately NOT [PublishedDictionary]:
 * a flat word list and a head/success table are different shapes, and E5c's own contract
 * ("владелец состояния обязан знать вид результата") is exactly why this project does not fold
 * structurally different artifacts into one type just because both are files on disk.
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

@Keep
interface PublishedBigramTableCatalog {
    fun acquireLatestForActivation(): BigramTableLease?
    fun cleanupReleasedVersions()
}

@Keep
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
 * One lifecycle surface for background bigram-table preparation and safe activation — the exact
 * pair [DictionaryStorageController]/[BackgroundDictionaryPreparer] form for the main dictionary,
 * mirrored here rather than generalized: the two artifact kinds already deliberately don't share
 * a spec, validator, or store type (`docs/DICTIONARY-E5B.md`), and this is the last layer of that
 * same shape.
 */
@Keep
class BigramStorageController internal constructor(
    private val preparer: BackgroundBigramPreparer,
    private val catalog: PublishedBigramTableCatalog,
) : PublishedBigramTableCatalog {
    fun prepare(callback: (BigramPreparationResult) -> Unit) = preparer.prepare(callback)

    override fun acquireLatestForActivation(): BigramTableLease? =
        catalog.acquireLatestForActivation()

    override fun cleanupReleasedVersions() = catalog.cleanupReleasedVersions()
}

@Keep
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
