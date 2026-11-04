package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes

/**
 * One shipped cold-companion word list (`TATCOMP\0`, schema 1): the top-frequency words of a
 * language's bundled dictionary, consulted by the autocorrect companion guard while that
 * language's engine is still cold. The companion engine, once warm, answers from the full
 * dictionary; the list only closes the cold-start gap.
 *
 * The list is read whole into memory (it is the top of a frequency list, not the dictionary),
 * never inflated into device-protected storage, and never touched by the suggestion path: its
 * only reader is [CompanionListReader].
 */
data class CompanionListArtifactSpec(
    val family: String,
    val languageTag: String,
    val assetPath: String,
    val expectedCompressedSize: Long,
    val expectedCompressedSha256: String,
    val expectedRawSize: Long,
    val expectedRawSha256: String,
    val expectedWordCount: Long,
    val schemaId: Int = TatCompFormat.SCHEMA_ID,
    val formatVersion: Int = TatCompFormat.FORMAT_VERSION,
    val maxRawSize: Long = TatCompFormat.MAX_RAW_SIZE,
) {
    init {
        require(family.matches(FAMILY_PATTERN))
        require(languageTag.isNotBlank())
        require(assetPath.isNotBlank())
        require(expectedCompressedSize > 0)
        require(expectedRawSize in TatCompFormat.HEADER_SIZE.toLong()..maxRawSize)
        require(expectedWordCount > 0)
        require(expectedCompressedSha256.isSha256())
        require(expectedRawSha256.isSha256())
    }

    companion object {
        private val FAMILY_PATTERN = Regex("[a-z][a-z0-9_]*")

        // The pins between the marker pairs are rewritten by scripts/rebuild_assets.py.
        /**
         * The Tatar cold-companion list: the top 20 000 words of the shipped Tatar dictionary,
         * by stored frequency.
         */
        @JvmField
        // <generated pins>
        val TATAR_COMPANION_V1 = CompanionListArtifactSpec(
            family = "tatar_companion",
            languageTag = PersonalSubtypes.TATAR_RU,
            assetPath = "dictionaries/tatar_companion_v1.tcomp.zlib",
            expectedCompressedSize = 78_900,
            expectedCompressedSha256 =
                "2e8b37c62aee04545e52dd77d3c1105af582889afbbbee330abd6362b891973f",
            expectedRawSize = 318_546,
            expectedRawSha256 =
                "4b9c46ecb55bad177889bcd4220bf738b626911a2a6ad01e69bdaff5f6da5f48",
            expectedWordCount = 20_000,
        )
        // </generated pins>

        /**
         * The Russian cold-companion list: the top 20 000 words of the shipped Russian
         * dictionary, by stored frequency.
         */
        @JvmField
        // <generated pins>
        val RUSSIAN_COMPANION_V1 = CompanionListArtifactSpec(
            family = "russian_companion",
            languageTag = PersonalSubtypes.RUSSIAN,
            assetPath = "dictionaries/russian_companion_v1.tcomp.zlib",
            expectedCompressedSize = 76_096,
            expectedCompressedSha256 =
                "5e1f1d70d1c9982f9c2b827f6e99ca591f0c3165a0dcc4797298d065b208a318",
            expectedRawSize = 314_734,
            expectedRawSha256 =
                "67a0741c260657394415c91b6aa44f4cd3415a14da2e216258b200b739ebc70f",
            expectedWordCount = 20_000,
        )
        // </generated pins>

        /**
         * Every shipped cold-companion list. The companion guard resolves a language's list
         * through [forSubtype], so the registry, not a subtype string test, decides.
         */
        @JvmField
        val ALL: List<CompanionListArtifactSpec> =
            listOf(TATAR_COMPANION_V1, RUSSIAN_COMPANION_V1)

        /** The list of [subtypeId], or null when that subtype ships none. */
        @JvmStatic
        fun forSubtype(subtypeId: String): CompanionListArtifactSpec? =
            ALL.firstOrNull { it.languageTag == subtypeId }
    }
}

/** The shared header shape of every `TATCOMP\0` file; the reader checks each field. */
internal object TatCompFormat {
    const val MAGIC = "TATCOMP\u0000"
    const val SCHEMA_ID = 1
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 56
    const val CHECKSUM_OFFSET = 24
    const val CHECKSUM_SIZE = 32
    const val CHECKSUM_ALGORITHM_SHA256 = 1
    // A bound the packer also keeps; a malformed header cannot grow the buffer read past it.
    const val MAX_WORDS = 20_000
    const val MAX_WORD_BYTES = 128
    // A safety bound on the inflated file, not a size budget.
    const val MAX_RAW_SIZE = 1_048_576L
}

/** A cold-companion list that failed validation; nothing repairs data, nothing is published. */
class CompanionListValidationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }