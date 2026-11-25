package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes

/** One Tatar stem lexicon, tied to its dictionary and Apertium source. */
data class StemLexiconArtifactSpec(
    val family: String,
    val languageTag: String,
    val assetPath: String,
    val expectedCompressedSize: Long,
    val expectedCompressedSha256: String,
    val expectedRawSize: Long,
    val expectedRawSha256: String,
    val expectedStemCount: Long,
    val expectedDictionaryRawSha256: String,
    val expectedApertiumLexiconSha256: String,
    val schemaId: Int = TatStemFormat.SCHEMA_ID,
    val formatVersion: Int = TatStemFormat.FORMAT_VERSION,
    val maxRawSize: Long = TatStemFormat.MAX_RAW_SIZE,
) {
    init {
        require(FAMILY_PATTERN.matches(family))
        require(languageTag.isNotBlank())
        require(assetPath.isNotBlank())
        require(expectedCompressedSize in 1..TatStemFormat.MAX_COMPRESSED_SIZE)
        require(expectedRawSize in TatStemFormat.HEADER_SIZE.toLong()..maxRawSize)
        require(maxRawSize <= TatStemFormat.MAX_RAW_SIZE)
        require(expectedStemCount in 1..TatStemFormat.MAX_STEMS)
        require(expectedCompressedSha256.isSha256())
        require(expectedRawSha256.isSha256())
        require(expectedDictionaryRawSha256.isSha256())
        require(expectedApertiumLexiconSha256.isSha256())
    }

    companion object {
        private val FAMILY_PATTERN = Regex("[a-z][a-z0-9_]*")

        // The pins between the marker pairs are rewritten by scripts/rebuild_assets.py.
        @JvmField
        // <generated pins>
        val TATAR_STEMS_V1 = StemLexiconArtifactSpec(
            family = "tatar_stems",
            languageTag = PersonalSubtypes.TATAR_RU,
            assetPath = "dictionaries/tatar_stems_v1.tstem.zlib",
            expectedCompressedSize = 240_291,
            expectedCompressedSha256 =
                "fc12666fe28c9f7f56b1c56b960099e903ceab20c097bfb13d98b527f448671b",
            expectedRawSize = 642_469,
            expectedRawSha256 =
                "71edec7f58af2f6131c0af40ea1211cd7b181a97fbbe1ba376c8566c446c0453",
            expectedStemCount = 49_931,
            expectedDictionaryRawSha256 =
                "5e59cd687670b9331ff5473c50132c8b7550ab031f29c48f84adfab357ea4a94",
            expectedApertiumLexiconSha256 =
                "43438bc2710beae9472e1d5e09a2403793d0d059c48ff9a9e5c9100387716e73",
        )
        // </generated pins>
    }
}

/** Constants and flag bits of the TATSTEM schema-1 binary image. */
internal object TatStemFormat {
    const val MAGIC = "TATSTEM\u0000"
    const val SCHEMA_ID = 1
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 136
    const val CHECKSUM_ALGORITHM_SHA256 = 1
    const val BLOCK_SIZE = 8
    const val MAX_STEMS = 100_000L
    const val MAX_STEM_BYTES = 128
    const val MAX_STEM_CODE_POINTS = 64
    const val MAX_RAW_SIZE = 2L * 1024 * 1024
    const val MAX_COMPRESSED_SIZE = 600L * 1024

    const val STEM_COUNT_OFFSET = 16
    const val BLOCK_COUNT_OFFSET = 20
    const val BLOCK_INDEX_OFFSET_OFFSET = 24
    const val BLOCKS_OFFSET_OFFSET = 28
    const val BLOCKS_SIZE_OFFSET = 32
    const val FILE_SIZE_OFFSET = 36
    const val DICTIONARY_SHA_OFFSET = 40
    const val APERTIUM_SHA_OFFSET = 72
    const val CHECKSUM_OFFSET = 104
    const val CHECKSUM_SIZE = 32

    const val FLAG_NOUN = 1
    const val FLAG_VERB = 2
    const val FLAG_FRONT = 4
    const val FLAG_BACK = 8
    const val FLAG_NOUN_CHAIN = 16
    const val FLAG_VERB_CHAIN = 32
    const val FLAG_FORCE_BACK = 64
    const val FLAG_KNOWN = 127
}

/** A stem lexicon that failed validation; no image is returned to a caller. */
class StemLexiconValidationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

private fun String.isSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
