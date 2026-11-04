package rkr.simplekeyboard.inputmethod.latin.golden

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures

/**
 * The committed keys-*.tsv pair (src/test/resources/golden) is the canonical key geometry the
 * golden exporters and the iOS parity suite build from. It is derived from the committed layout
 * resources by [KeyGeometryTsv]; these tests pin the bytes (SHA-256), prove the derivation
 * reproduces them, and tie them to the glide fixture, the repo's pinned reference geometry.
 *
 * After a deliberate layout change, regenerate with
 *
 *   KEYS_TSV_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*KeyGeometryTsvTest*' --rerun
 *
 * copy the two files over src/test/resources/golden/ and update the pins below.
 */
class KeyGeometryTsvTest {

    /** The derivation from the layout resources reproduces the committed files exactly. */
    @Test
    fun theCommittedTsvMatchesTheLayoutResourcesByteForByte() {
        for (lang in LANGS) {
            val committed = file(lang).readText(Charsets.UTF_8)
            assertEquals(
                "keys-$lang.tsv is stale — regenerate it (see the class doc) and update the pins",
                KeyGeometryTsv.tsv(lang), committed,
            )
        }
    }

    @Test
    fun theCommittedTsvMatchesItsSha256Pin() {
        val expected = mapOf("tt" to KEYS_TT_SHA256, "ru" to KEYS_RU_SHA256)
        for (lang in LANGS) {
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(file(lang).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("keys-$lang.tsv changed; update the pin with the regeneration", expected[lang], sha)
        }
    }

    /**
     * The TSV rectangles and more-keys are the glide fixture's, the reference geometry
     * GlideRecoveryCalibrationTest pins against the gesture generator. The iOS tool's own
     * output is not in this repo, so parity with it cannot be proven here; the fixture is.
     */
    @Test
    fun theCommittedTsvCarriesTheGlideFixtureGeometry() {
        for (lang in LANGS) {
            val expected = if (lang == "tt") GlideTestFixtures.tatarRawKeys()
                else GlideTestFixtures.russianRawKeys()
            val actual = KeyGeometryTsv.derive(lang)
            assertEquals("keys-$lang.tsv key count", expected.size, actual.size)
            for ((key, fixture) in actual.zip(expected)) {
                assertEquals("code point", fixture.codePoint, key.codePoint)
                assertEquals("${fixture.codePoint} left", fixture.left, key.left)
                assertEquals("${fixture.codePoint} top", fixture.top, key.top)
                assertEquals("${fixture.codePoint} right", fixture.right, key.right)
                assertEquals("${fixture.codePoint} bottom", fixture.bottom, key.bottom)
                assertTrue(
                    "${fixture.codePoint} more-keys",
                    fixture.moreKeyCodePoints.contentEquals(key.moreKeyCodePoints),
                )
            }
        }
    }

    /** The regeneration writer; inert unless KEYS_TSV_OUT names a directory. */
    @Test
    fun exportWhenAsked() {
        val outName = System.getenv("KEYS_TSV_OUT")
        assumeTrue("KEYS_TSV_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "KEYS_TSV_OUT must be an existing directory" }
        for (lang in LANGS) {
            File(out, "keys-$lang.tsv").writeText(KeyGeometryTsv.tsv(lang), Charsets.UTF_8)
        }
    }

    private fun file(lang: String): File =
        listOf(
            File("src/test/resources/golden/keys-$lang.tsv"),
            File("app/src/test/resources/golden/keys-$lang.tsv"),
        ).firstOrNull(File::isFile) ?: error("cannot locate keys-$lang.tsv")

    private companion object {
        val LANGS = listOf("tt", "ru")
        const val KEYS_TT_SHA256 = "15c76e5c2a8e5f2d493888b1752d81c3c7c719d9d44ae4839eeafafb517b3278"
        const val KEYS_RU_SHA256 = "5f86194ae59126da3a444e8188fb74f19eb6216a29b1b224679cc4b3c0b8e3bd"
    }
}
