package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import com.sun.management.ThreadMXBean
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Allocation ceiling of raw validation on the bundled files: one in-memory copy of the raw file,
 * plus the distinct-success scratch for a bigram table, plus a fixed margin. Prints the median
 * time and bytes of [RUNS] runs after [WARMUP_RUNS] warm-up runs.
 */
class ValidatorAllocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dictionaryValidationAllocatesAtMostOneRawCopy() {
        val failures = ArrayList<String>()
        for (spec in DictionaryArtifactSpec.ALL) {
            val raw = inflate(spec.assetPath) { input, output ->
                TdictValidator().inflateAsset(input, output, spec)
            }
            val validator = TdictValidator()
            val (millis, bytes) = measure { validator.validateRaw(raw, spec) }
            val ceiling = raw.length() + MARGIN_BYTES
            println("VALIDATE|tdict|${spec.family}|raw=${raw.length()}|median_ms=$millis|bytes=$bytes")
            if (bytes > ceiling) failures += "${spec.family}: $bytes B > $ceiling B"
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    @Test
    fun bigramValidationAllocatesAtMostOneRawCopyAndTheSuccessScratch() {
        val failures = ArrayList<String>()
        for (spec in listOf(BigramArtifactSpec.TATAR_BIGRAMS_V1, BigramArtifactSpec.RUSSIAN_BIGRAMS_V1)) {
            val raw = inflate(spec.assetPath) { input, output ->
                TatBigrValidator().inflateAsset(input, output, spec)
            }
            val validator = TatBigrValidator()
            val validated = validator.validateRaw(raw, spec)
            val pairCount = validated.pairCount
            val (millis, bytes) = measure { validator.validateRaw(raw, spec) }
            val ceiling = raw.length() + 4 * pairCount + MARGIN_BYTES
            println(
                "VALIDATE|tatbigr|${spec.family}|raw=${raw.length()}|median_ms=$millis|bytes=$bytes" +
                    "|distinct=${validated.successVocabularyCount}",
            )
            if (bytes > ceiling) failures += "${spec.family}: $bytes B > $ceiling B"
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }

    private fun inflate(
        assetPath: String,
        inflater: (java.io.InputStream, java.io.OutputStream) -> Unit,
    ): File {
        val asset = listOf(File("src/main/assets/$assetPath"), File("app/src/main/assets/$assetPath"))
            .firstOrNull(File::isFile)
            ?: error("cannot locate $assetPath from ${File(".").absolutePath}")
        val raw = temporaryFolder.newFile()
        raw.outputStream().use { output -> asset.inputStream().use { inflater(it, output) } }
        return raw
    }

    /** Median wall time in milliseconds and median allocated bytes of [RUNS] calls. */
    private fun measure(block: () -> Unit): Pair<Double, Long> {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(WARMUP_RUNS) { block() }
        val nanos = LongArray(RUNS)
        val bytes = LongArray(RUNS)
        for (run in 0 until RUNS) {
            val allocatedBefore = bean.getThreadAllocatedBytes(threadId)
            val start = System.nanoTime()
            block()
            nanos[run] = System.nanoTime() - start
            bytes[run] = bean.getThreadAllocatedBytes(threadId) - allocatedBefore
        }
        nanos.sort()
        bytes.sort()
        return nanos[RUNS / 2] / 1_000_000.0 to bytes[RUNS / 2]
    }

    private companion object {
        const val WARMUP_RUNS = 10
        const val RUNS = 9
        const val MARGIN_BYTES = 256L * 1024L
    }
}
