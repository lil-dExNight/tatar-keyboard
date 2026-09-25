package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal-dictionary glide candidates at the decoder level (docs/GLIDE-PERSONAL.md): a
 * learned word decodes on its own gesture, a clear dictionary gesture keeps its dictionary
 * verdict, and the dictionary's relative order survives the personal tail. All decodes run the
 * real [GlideDecoder] over the fixture Tatar geometry; the inventory side is the
 * [ListGlideInventory] fixture plus a [CompositeGlideInventory] wrap.
 */
class GlideDecoderPersonalTest {

    private val geometry = GlideTestFixtures.tatarGeometry()

    // Code-point sorted, like the shipped pipeline's output; nothing here shares a start/end
    // key bucket with the personal words below.
    private val baseWords = listOf(
        "бала" to 60L,
        "китап" to 200L,
        "сәләм" to 100L,
    )

    private fun base() = ListGlideInventory(baseWords)

    private fun baseMembership() = baseWords.map { it.first }.toSet()::contains

    private fun decode(decoder: GlideDecoder, word: String): List<String> {
        val result = GlideResult()
        val count = decoder.decode(
            requireNotNull(GlideTestFixtures.idealPath(word, geometry)), result,
        )
        return (0 until count).map { result.words[it]!! }
    }

    @Test
    fun aPersonalOnlyWordDecodesTop1OnItsIdealPath() {
        // "сәлинә" shares no extremity bucket with any base word, so it is the only candidate.
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("сәлинә" to 7), baseMembership(),
        )
        val decoder = GlideDecoder(geometry, composite)

        assertEquals(listOf("сәлинә"), decode(decoder, "сәлинә"))
    }

    @Test
    fun theResultCarriesTheSavedCasing() {
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("Сәлинә" to 7), baseMembership(),
        )
        val decoder = GlideDecoder(geometry, composite)

        // The shape is the normalized word's; the result string is the user's own spelling.
        assertEquals(listOf("Сәлинә"), decode(decoder, "сәлинә"))
    }

    @Test
    fun anUnrelatedHighUsagePersonalWordDoesNotHijackAClearDictionaryGesture() {
        // The personal word's synthetic frequency ties the base maximum (its usage is the
        // snapshot's maximum) — and still the clear dictionary gesture decodes the dictionary
        // word alone: the personal word is not even an extremity survivor.
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("сәлинә" to 100), baseMembership(),
        )
        val decoder = GlideDecoder(geometry, composite)

        val words = decode(decoder, "сәләм")
        assertEquals("сәләм", words.first())
        assertTrue("сәлинә" !in words)
    }

    @Test
    fun aClosePersonalWordRidesTheResultsWithoutPassingTheClearDictionaryWord() {
        // "бапа" is one key away from "бала" (а/п are neighbors on their row) and its usage is
        // 1 against the snapshot's 100: a synthetic frequency of max(1, 200/100) = 2, so the
        // frequency channel cannot lift it past the exactly-matched dictionary word.
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("бапа" to 1, "йорт" to 100),
            baseMembership(),
        )
        val decoder = GlideDecoder(geometry, composite)

        val words = decode(decoder, "бала")
        assertEquals("бала", words.first())
        assertTrue("бапа must ride the results, was $words", "бапа" in words)
    }

    @Test
    fun theDictionaryRelativeOrderIsUnchangedWithAPersonalTail() {
        val plain = decode(GlideDecoder(geometry, base()), "сәләм")
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("сәлинә" to 100), baseMembership(),
        )
        val withPersonal = decode(GlideDecoder(geometry, composite), "сәләм")

        // The personal tail may add cells of its own, but filtering it back out must reproduce
        // the dictionary-only verdict exactly — order included.
        assertEquals(plain, withPersonal.filter { it in baseWords.map { word -> word.first } })
        assertEquals(plain, withPersonal)
    }

    @Test
    fun decodingIsDeterministicAcrossRepeatsAndRebuilds() {
        val composite = CompositeGlideInventory(
            base(), GlideTestFixtures.personalDictionary("сәлинә" to 7, "бапа" to 3),
            baseMembership(),
        )
        val decoder = GlideDecoder(geometry, composite)

        val first = decode(decoder, "бала")
        assertEquals(first, decode(decoder, "бала"))
        // A fresh decoder over the same composite rebuilds the word index identically.
        assertEquals(first, decode(GlideDecoder(geometry, composite), "бала"))
    }
}
