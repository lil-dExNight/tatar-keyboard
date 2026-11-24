package rkr.simplekeyboard.inputmethod.latin.morphology

import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TatarMorphologicalAcceptorCalibrationTest {
    @Test
    fun fixedRealAssetLookupSampleHasGenerousLatencyBound() {
        val acceptor = MorphologyTestSupport.realAcceptor()
        var consumed = 0
        repeat(WARMUP_ROUNDS) {
            for (index in FORMS.indices) if (acceptor.accepts(FORMS[index])) consumed++
        }
        val timings = LongArray(TIMING_SAMPLES)
        for (sample in timings.indices) {
            val form = FORMS[sample % FORMS.size]
            val started = System.nanoTime()
            if (acceptor.accepts(form)) consumed++
            timings[sample] = System.nanoTime() - started
        }
        timings.sort()
        val median = timings[timings.size / 2]
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "tatar morphology lookup median=${milliseconds(median)} ms p95=${milliseconds(p95)} ms " +
                "consumed=$consumed",
        )
        assertTrue("Lookup p95=${milliseconds(p95)} ms", p95 <= 5_000_000L)
    }

    @Test
    fun acceptedAndRejectedSteadyStateLookupsDoNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val threadBean = bean!!
        threadBean.isThreadAllocatedMemoryEnabled = true
        val acceptor = MorphologyTestSupport.realAcceptor()
        val threadId = Thread.currentThread().id
        var consumed = 0

        fun measure(form: String, iterations: Int): Long {
            val before = threadBean.getThreadAllocatedBytes(threadId)
            for (index in 0 until iterations) if (acceptor.accepts(form)) consumed++
            return threadBean.getThreadAllocatedBytes(threadId) - before
        }

        measure(ACCEPTED_CHAIN, ALLOCATION_WARMUP)
        measure(REJECTED_CHAIN, ALLOCATION_WARMUP)
        for ((name, form) in listOf("accepted" to ACCEPTED_CHAIN, "rejected" to REJECTED_CHAIN)) {
            var minimum = Long.MAX_VALUE
            repeat(ALLOCATION_ROUNDS) {
                minimum = minOf(minimum, measure(form, ALLOCATION_SAMPLES))
            }
            println("tatar morphology allocation $name=$minimum bytes per $ALLOCATION_SAMPLES lookups")
            assertEquals("$name steady-state allocation", 0L, minimum)
        }
        assertTrue("Accepted-chain workload was not consumed", consumed > 0)
    }

    @Test
    fun repeatedOpenTimingHasOnlyAGenerousBound() {
        val file = MorphologyTestSupport.locate("app/src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib")
        assertTrue(file.isFile)
        file.inputStream().use { TatarMorphologicalAcceptor.open(it) }
        val timings = LongArray(OPEN_REPETITIONS)
        var consumed = 0L
        for (repetition in timings.indices) {
            val started = System.nanoTime()
            file.inputStream().use {
                consumed = consumed xor TatarMorphologicalAcceptor.open(it).retainedByteEstimate
            }
            timings[repetition] = System.nanoTime() - started
        }
        timings.sort()
        val median = timings[timings.size / 2]
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1]
        println(
            "tatar morphology open median=${milliseconds(median)} ms p95=${milliseconds(p95)} ms " +
                "consumed=$consumed",
        )
        assertTrue("Open p95=${milliseconds(p95)} ms", p95 <= 2_000_000_000L)
    }

    private fun milliseconds(nanos: Long): String =
        String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private companion object {
        const val WARMUP_ROUNDS = 2_000
        const val TIMING_SAMPLES = 4_000
        const val ALLOCATION_WARMUP = 5_000
        const val ALLOCATION_SAMPLES = 10_000
        const val ALLOCATION_ROUNDS = 5
        const val OPEN_REPETITIONS = 11
        const val ACCEPTED_CHAIN = "ачытларыгызны"
        const val REJECTED_CHAIN = "ачытларыгызныа"
        val FORMS = arrayOf(
            "ачыт", "ачытлар", "ачытында", "ачытларыгызны", "ачытмынмы", "ачытлык", "ачытыйк",
            "ачытаммы", "ачыттыгыз", "ачытканнар", "ачытыр", "ачытачаксың", "ачытсак", "ачытып",
            "ачыткач", "ачытканчы", "ачытмыйча", "ачыткан", "ачытучы", "ачытасы", "ачыту",
            "ачытырга", "ачытмакчы", "ачытларыгызныа", "ачытмынмыа", "ачытмыйчаа", "ачытыргаа",
            "ачытсакк", "ачытп", "ачыттачак", "ачытмынмыя",
        )
    }
}
