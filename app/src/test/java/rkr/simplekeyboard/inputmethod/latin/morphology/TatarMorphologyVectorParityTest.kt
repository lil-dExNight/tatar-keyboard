package rkr.simplekeyboard.inputmethod.latin.morphology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every committed vector must agree with the real pinned acceptor image. Comments and a small
 * key=value metadata header are skipped; data rows are verdict TAB form TAB stem TAB label/reason.
 */
class TatarMorphologyVectorParityTest {
    @Test
    fun everyVectorAgreesWithTheRealAsset() {
        val vectors = readVectors()
        assertTrue("No acceptance vectors were found", vectors.isNotEmpty())
        var accepted = 0
        var rejected = 0
        val acceptor = MorphologyTestSupport.realAcceptor()
        for (vector in vectors) {
            val verdict = acceptor.accepts(vector.form)
            assertTrue(vector.toString(), verdict == vector.accepted)
            if (vector.accepted) accepted++ else rejected++
        }
        assertTrue("Expected both accepted and rejected vectors", accepted > 0 && rejected > 0)
    }

    private fun readVectors(): List<MorphologyVector> {
        val file = MorphologyTestSupport.locate("app/src/test/resources/tatar_morphology_vectors_v1.tsv")
        assertTrue("Missing vector file: $file", file.isFile)
        val result = ArrayList<MorphologyVector>()
        for (line in file.readLines()) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            if (!text.contains('\t')) {
                // A key=value metadata row has no data columns.
                continue
            }
            val fields = text.split('\t')
            assertEquals("Malformed vector row: $text", 4, fields.size)
            val verdict = when (fields[0]) {
                "A" -> true
                "R" -> false
                else -> throw AssertionError("Unknown vector verdict in $text")
            }
            result.add(MorphologyVector(fields[2], fields[3], fields[1], verdict))
        }
        return result
    }

    private data class MorphologyVector(
        val stem: String,
        val label: String,
        val form: String,
        val accepted: Boolean,
    ) {
        override fun toString(): String = "$stem/$label/$form accepted=$accepted"
    }
}
