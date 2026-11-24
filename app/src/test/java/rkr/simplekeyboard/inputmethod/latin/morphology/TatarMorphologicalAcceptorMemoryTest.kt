package rkr.simplekeyboard.inputmethod.latin.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemTestEntry
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemValidator

class TatarMorphologicalAcceptorMemoryTest {
    @Test
    fun realAssetRetainedEstimateStaysBelowTwoMiB() {
        val acceptor = MorphologyTestSupport.realAcceptor()
        println("tatar morphology retainedBytes=${acceptor.retainedByteEstimate}")
        assertTrue(
            "Retained acceptor bytes: ${acceptor.retainedByteEstimate}",
            acceptor.retainedByteEstimate <= 2L * 1024 * 1024,
        )
    }

    @Test
    fun retainedEstimateIncludesLexicalArraysAndStrings() {
        val entries = listOf(MorphologyTestSupport.entry("дус"))
        val plain = MorphologyTestSupport.acceptor(entries, emptyArray())
        val exception = LexicalException(
            "дус",
            possessiveBase = "ду",
            pronouns = arrayOf("дусам", "дусаң"),
            overrides = mapOf("noun.p3" to "дусы"),
        )
        val withException = MorphologyTestSupport.acceptor(entries, arrayOf(exception))
        assertTrue(
            "Exception payload is not counted",
            withException.retainedByteEstimate > plain.retainedByteEstimate,
        )
    }

    @Test
    fun halfAndFullSyntheticImagesDifferOnlyByTheirRawPayload() {
        val half = load(800)
        val full = load(1_600)
        val halfRaw = half.first.raw.size.toLong()
        val fullRaw = full.first.raw.size.toLong()
        assertTrue(fullRaw > halfRaw)
        assertEquals(
            "Fixed scratch must not grow with the stem count",
            fullRaw - halfRaw,
            full.second.retainedByteEstimate - half.second.retainedByteEstimate,
        )
        assertEquals(
            half.second.retainedByteEstimate - halfRaw,
            full.second.retainedByteEstimate - fullRaw,
        )
    }

    private fun load(count: Int): Pair<rkr.simplekeyboard.inputmethod.latin.dictionary.storage.ValidatedStemLexicon, TatarMorphologicalAcceptor> {
        val entries = ArrayList<StemTestEntry>(count)
        for (index in 0 until count) {
            var value = index
            val word = buildString {
                append('б')
                repeat(4) {
                    append(CONSONANTS[value % CONSONANTS.length])
                    value /= CONSONANTS.length
                }
                append('а')
            }
            entries.add(MorphologyTestSupport.entry(word))
        }
        val artifact = StemLexiconTestFixtures.artifact(entries.sortedBy { it.word })
        val validated = TatStemValidator().inflateAsset(artifact.compressed.inputStream(), artifact.spec)
        return validated to TatarMorphologicalAcceptor(validated, artifact.spec)
    }

    private companion object {
        const val CONSONANTS = "бвгдҗклмнрст"
    }
}
