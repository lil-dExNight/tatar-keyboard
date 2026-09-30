/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import android.os.Debug
import android.test.InstrumentationTestCase
import android.util.Log
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Device comparison of dictionary I/O strategies over the same inflated Tatar dictionary: the
 * shipped read-only mmap (`MappedDictionaryEngine.FILE_MAPPER`) against a heap buffer
 * (`ByteBuffer.wrap(readBytes())`).
 *
 *  1. PSS (`Debug.getMemoryInfo`, "total/dalvik/other" kB): baseline → mmap loaded (every page
 *     touched) → released and GC'd → heap loaded. With mmap the dictionary should sit in
 *     evictable file-backed pages, not the dalvik heap. Logged only (device PSS is noisy), and
 *     measured first, before the other sections keep their own buffers alive.
 *  2. Load time: buffer acquisition (file read vs `FileChannel.map`) plus
 *     `TdictPrefixIndex.open`, which for mmap is also the first touch of every page. Without
 *     root there is no `drop_caches`, so later repetitions hit a warm page cache; rep 1 is
 *     logged separately as the closest cold value.
 *  3. Lookup p50/p95/max over the review prefixes and typo probes of
 *     [E3bComputeInstrumentationTest], both fuzzy policies with the neighbor table, for each
 *     strategy; p95 must stay within the same 5.0 ms bound.
 *
 * Run on an idle device with the screen on:
 *
 *     ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
 *     adb install -r app/build/outputs/apk/debug/app-debug.apk
 *     adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w \
 *       -e class rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIoStrategyInstrumentationTest \
 *       org.tatarkeyboard.ime.debug.test/android.test.InstrumentationTestRunner
 *     adb logcat -s DictionaryIoStrategy:I
 */
class DictionaryIoStrategyInstrumentationTest : InstrumentationTestCase() {

    fun testColdLoadLookupAndPssOfBothIoStrategies() {
        val context = instrumentation.targetContext
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val rawFile = File.createTempFile("o7-io-strategy-", ".tdict", context.cacheDir)
        try {
            context.assets.open(spec.assetPath).use { input ->
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(input, output, spec)
                }
            }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )

            // 1. PSS signature. Each arm lives inside its own non-inline function so the buffer
            // reference genuinely dies at return; the GC pass between arms is what the production
            // release path relies on as well (MappedDictionaryEngine drops the reference and the
            // mapping is reclaimed by the GC — there is no public force-unmap).
            settleGc()
            val baseline = memory()
            val withMmap = pssWithMappedIndex(rawFile, validated.rawSize, identity, spec)
            settleGc()
            val afterMmapRelease = memory()
            val withHeap = pssWithHeapIndex(rawFile, identity, spec)
            Log.i(
                TAG,
                "O7 device pss kB (total/dalvik/other) baseline=$baseline mmapLoaded=$withMmap " +
                    "afterMmapRelease=$afterMmapRelease heapLoaded=$withHeap " +
                    "rawBytes=${validated.rawSize}",
            )

            // 2. Cold-load arms, interleaved so thermal/scheduler drift hits both equally.
            val acquireHeap = LongArray(REPS)
            val openHeap = LongArray(REPS)
            val acquireMapped = LongArray(REPS)
            val openMapped = LongArray(REPS)
            var consumed = 0
            for (rep in 0 until REPS) {
                var started = System.nanoTime()
                val heapBuffer = ByteBuffer.wrap(rawFile.readBytes())
                acquireHeap[rep] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor openIndex(heapBuffer, identity, spec, null, null).entryCount
                openHeap[rep] = System.nanoTime() - started

                started = System.nanoTime()
                val mappedBuffer =
                    MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(rawFile, validated.rawSize)
                acquireMapped[rep] = System.nanoTime() - started
                started = System.nanoTime()
                consumed = consumed xor openIndex(mappedBuffer, identity, spec, null, null).entryCount
                openMapped[rep] = System.nanoTime() - started
            }
            Log.i(
                TAG,
                "O7 device cold-load rep1 acquireHeap=${fmt(acquireHeap[0])} ms " +
                    "openHeap=${fmt(openHeap[0])} ms acquireMmap=${fmt(acquireMapped[0])} ms " +
                    "openMmap=${fmt(openMapped[0])} ms",
            )
            acquireHeap.sort()
            openHeap.sort()
            acquireMapped.sort()
            openMapped.sort()
            Log.i(
                TAG,
                "O7 device cold-load warm-medians ($REPS reps, no drop_caches) " +
                    "acquireHeap=${fmt(median(acquireHeap))} ms openHeap=${fmt(median(openHeap))} ms " +
                    "acquireMmap=${fmt(median(acquireMapped))} ms " +
                    "openMmap=${fmt(median(openMapped))} ms consumed=$consumed",
            )

            // 3. Lookup latency: both policies, both strategies, identical workloads, with the
            // neighbor table engaged as in E3bComputeInstrumentationTest.
            val neighborTable = E3bComputeInstrumentationTest().offlineModelNeighborTable()
            val heapBytes = rawFile.readBytes()
            val mappedBytes =
                MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(rawFile, validated.rawSize)
            for ((policyName, policy) in E3bComputeInstrumentationTest.POLICIES) {
                val heapIndex =
                    openIndex(ByteBuffer.wrap(heapBytes), identity, spec, policy, neighborTable)
                val mappedIndex = openIndex(mappedBytes, identity, spec, policy, neighborTable)
                measure(heapIndex, "heap", policyName, E3bComputeInstrumentationTest.REVIEW_PREFIXES)
                measure(mappedIndex, "mmap", policyName, E3bComputeInstrumentationTest.REVIEW_PREFIXES)
                measure(heapIndex, "heap", "$policyName-typo", E3bComputeInstrumentationTest.TYPO_PROBES)
                measure(mappedIndex, "mmap", "$policyName-typo", E3bComputeInstrumentationTest.TYPO_PROBES)
            }
        } finally {
            rawFile.delete()
        }
    }

    private fun measure(
        index: TdictPrefixIndex,
        strategy: String,
        policyName: String,
        prefixesCp: List<String>,
    ) {
        val prefixes = prefixesCp.map { ImmutableUtf8Prefix.copyOf(it.toByteArray(Charsets.UTF_8)) }
        repeat(200) { index.lookup(prefixes[it % prefixes.size]) }
        val timings = LongArray(SAMPLES)
        var consumed = 0
        for (sample in timings.indices) {
            val prefix = prefixes[sample % prefixes.size]
            val started = System.nanoTime()
            val results = index.lookup(prefix)
            timings[sample] = System.nanoTime() - started
            consumed = consumed xor results.size
        }
        timings.sort()
        val p50 = timings[timings.size / 2] / 1_000_000.0
        val p95 = timings[ceil(timings.size * 0.95).toInt() - 1] / 1_000_000.0
        val max = timings.last() / 1_000_000.0
        Log.i(
            TAG,
            "O7 device compute strategy=$strategy policy=$policyName p50=${fmt(p50)} ms " +
                "p95=${fmt(p95)} ms max=${fmt(max)} ms samples=${timings.size} consumed=$consumed",
        )
        assertTrue(
            "O7 $strategy/$policyName p95=$p95 ms exceeds the conservative 5.0 ms bound " +
                "(the E3b fail-closed constant)",
            p95 <= 5.0,
        )
    }

    private fun pssWithMappedIndex(
        file: File,
        rawSize: Long,
        identity: DictionaryIdentity,
        spec: DictionaryArtifactSpec,
    ): String {
        val buffer = MappedDictionaryEngine.FILE_MAPPER.mapReadOnly(file, rawSize)
        val index = openIndex(buffer, identity, spec, null, null)
        if (index.entryCount <= 0) error("empty index")
        return memory()
    }

    private fun pssWithHeapIndex(
        file: File,
        identity: DictionaryIdentity,
        spec: DictionaryArtifactSpec,
    ): String {
        val index = openIndex(ByteBuffer.wrap(file.readBytes()), identity, spec, null, null)
        if (index.entryCount <= 0) error("empty index")
        return memory()
    }

    private fun openIndex(
        buffer: ByteBuffer,
        identity: DictionaryIdentity,
        spec: DictionaryArtifactSpec,
        policy: FuzzyEditPolicy?,
        neighbors: KeyNeighborTable?,
    ): TdictPrefixIndex {
        val index = requireNotNull(
            TdictPrefixIndex.open(
                buffer, identity, spec.expectedEntryCount, spec.expectedRawSize, null, policy,
            ),
        )
        if (neighbors != null) index.updateKeyNeighbors(neighbors)
        return index
    }

    private fun settleGc() {
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(150)
        }
    }

    /** "total/dalvik/other" PSS in kB — the split that shows anonymous heap vs file-backed pages. */
    private fun memory(): String {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return "${info.totalPss}/${info.dalvikPss}/${info.otherPss}"
    }

    private fun median(sorted: LongArray): Long = sorted[sorted.size / 2]

    private fun fmt(nanos: Long): String =
        String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private fun fmt(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.3f", value)

    companion object {
        private const val TAG = "DictionaryIoStrategy"
        private const val REPS = 11
        private const val SAMPLES = 1_000
    }
}
