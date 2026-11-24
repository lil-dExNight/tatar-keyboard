package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import rkr.simplekeyboard.inputmethod.latin.morphology.MorphologyTestSupport
import rkr.simplekeyboard.inputmethod.latin.morphology.Utf8WordformAcceptor
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarChatSpellingRules

class TdictPrefixIndexChatSubstitutionTest {
    private val table = TatarLayoutTestFixtures.tatarNeighborTable()

    private fun index(
        entries: List<Pair<String, Long>>,
        policy: FuzzyEditPolicy = FuzzyEditPolicy.TATAR,
        withMorphology: Boolean = false,
    ): TdictPrefixIndex {
        val raw = DictionaryTestFixtures.raw(entries)
        val identity = DictionaryIdentity(1, 2, 1, "a".repeat(64))
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, entries.size.toLong(), raw.size.toLong(),
                fuzzyEditPolicy = policy,
                autocorrectMinCandidateFrequency = 0L,
                chatSubstitutions = TatarChatSpellingRules,
            ),
        ).also {
            it.updateKeyNeighbors(table)
            if (withMorphology) it.updateWordformAcceptor(MorphologyTestSupport.realAcceptor())
        }
    }

    private fun lookup(index: TdictPrefixIndex, prefix: String): List<String> =
        index.lookup(ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8)))

    @Test
    fun twoSubstitutionsRecoverABundledWordWithoutAutocorrectAdvice() {
        val index = index(listOf("рәхмәт" to 100L))
        val result = lookup(index, "рэхмэт")
        assertEquals(listOf("рәхмәт"), result)
        assertNull(
            "replacement=${index.lastAutocorrectAdvice?.replacement}",
            index.lastAutocorrectAdvice,
        )
        assertFalse(index.lastFuzzyOverBudget)
    }

    @Test
    fun theTierOutranksChatSubstitutionRegardlessOfFrequency() {
        val index = index(listOf("рэхэт" to 10_000L, "рәхмәт" to 100L))
        assertEquals(listOf("рэхэт", "рәхмәт"), lookup(index, "рэхмэт"))
    }

    @Test
    fun exactCandidatesKeepTheirPriority() {
        val index = index(listOf("рәхмәт" to 100L))
        assertEquals(listOf("рәхмәт"), lookup(index, "рәхм"))
    }

    @Test
    fun theRussianPolicyDoesNotEngageChatSubstitution() {
        val index = index(
            listOf("рәхмәт" to 100L),
            policy = FuzzyEditPolicy.RUSSIAN,
        )
        assertTrue("result=${lookup(index, "рэхмэт")}", lookup(index, "рэхмэт").isEmpty())
    }

    @Test
    fun morphologyCanAdmitAWholeVariantNotInTheDictionary() {
        val index = index(
            listOf("бәйрәм" to 100L),
            withMorphology = true,
        )
        val result = lookup(index, "бэйрэмне")
        assertTrue("morphology must accept the variant: $result", result.contains("бәйрәмне"))
        val bytes = "бәйрәмне".toByteArray(Charsets.UTF_8)
        assertEquals(0L, index.frequencyOf("бәйрәмне"))
        assertTrue(index.indexOfWord(bytes, bytes.size) < 0)
        assertEquals(0, index.lastExactCount)
        assertNull(index.lastAutocorrectAdvice)
    }

    @Test
    fun acceptorOnlyLookupAllocationMatchesBundledLookup() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        val bundled = index(listOf("бәйрәмне" to 100L))
        val acceptedBytes = "бәйрәмне".toByteArray(Charsets.UTF_8)
        val acceptedOnly = index(listOf("бәйрәм" to 100L)).also {
            it.updateWordformAcceptor(object : Utf8WordformAcceptor {
                override fun acceptsUtf8(normalizedWordUtf8: ByteArray, length: Int): Boolean {
                    if (length != acceptedBytes.size) return false
                    for (index in acceptedBytes.indices) {
                        if (normalizedWordUtf8[index] != acceptedBytes[index]) return false
                    }
                    return true
                }
            })
        }
        val prefix = ImmutableUtf8Prefix.copyOf("бэйрэмне".toByteArray(Charsets.UTF_8))
        val expected = listOf("бәйрәмне")
        assertEquals(expected, bundled.lookup(prefix))
        assertEquals(expected, acceptedOnly.lookup(prefix))

        repeat(50_000) {
            bundled.lookup(prefix)
            acceptedOnly.lookup(prefix)
        }
        val iterations = 100_000
        fun perLookupBytes(index: TdictPrefixIndex): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            repeat(iterations) { index.lookup(prefix) }
            return (threadBean.getThreadAllocatedBytes(threadId) - before) / iterations
        }
        val bundledBytes = perLookupBytes(bundled)
        val acceptedOnlyBytes = perLookupBytes(acceptedOnly)
        assertTrue(
            "bundled=$bundledBytes acceptedOnly=$acceptedOnlyBytes bytes/lookup",
            abs(acceptedOnlyBytes - bundledBytes) <= 8L,
        )
    }
}
