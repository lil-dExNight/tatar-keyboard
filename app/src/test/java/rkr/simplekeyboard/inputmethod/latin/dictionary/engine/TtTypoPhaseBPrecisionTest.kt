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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * TT-TYPO-NEXT Phase B, gate G2 (precision; docs/TT-TYPO-NEXT-PLAN.md, written before measuring):
 * on a pinned set of >= 1 000 CORRECTLY-TYPED prefixes derived from the committed eval set
 * (`app/src/test/resources/tt_eval_sentences.txt`), the share whose top-3 receives a fuzzy
 * candidate — necessarily NOT a continuation of the typed prefix, since every fuzzy candidate
 * carries one edited letter — must stay <= 2 %.
 *
 * Derivation (deterministic): every distinct code-point prefix of length >= 3 of every unique
 * eval word — exactly the typing states where the fuzzy pass can fire at all
 * (MIN_FUZZY_PREFIX_CODE_POINTS = 3); shorter prefixes never engage it by construction. The fuzzy
 * pass fills only cells the exact pass left empty, so a fuzzy candidate in the top-3 is exactly
 * "the strip offered a word that does not continue what the user typed" — pollution.
 *
 * Both arms are measured on the production shape (P3 suffix rules + layout-derived neighbour
 * table): the shipped baseline ([FuzzyEditPolicy.DEFAULT], class #1 only) and the calibrated
 * Phase-B candidate (classes #1+#2 + same-length bonus — [FuzzyEditPolicy.TATAR] as calibrated in
 * Phase B; Phase C redefined TATAR to {1, 4}, so the arm is spelled out explicitly and the pinned
 * Phase-B numbers stay comparable). The counts are pinned
 * exactly; the verdict is printed, and the outcome is recorded in docs/TT-TYPO-NEXT.md.
 */
class TtTypoPhaseBPrecisionTest {

    @Test
    fun gateG2FuzzyPollutionOnCorrectlyTypedPrefixes() {
        val baseline = requireNotNull(defaultIndex)
        val candidate = requireNotNull(tatarIndex)
        assertTrue(
            "the derived prefix set must carry at least 1 000 states (pinned: $PIN_PREFIXES)",
            prefixes.size >= 1_000,
        )
        assertEquals(PIN_PREFIXES, prefixes.size)

        var baselinePolluted = 0
        var candidatePolluted = 0
        for (prefix in prefixes) {
            val query = ImmutableUtf8Prefix.copyOf(prefix.toByteArray(Charsets.UTF_8))
            if (baseline.lookup(query).size > baseline.lastExactCount) baselinePolluted++
            if (candidate.lookup(query).size > candidate.lastExactCount) candidatePolluted++
        }
        val baselinePct = baselinePolluted * 100.0 / prefixes.size
        val candidatePct = candidatePolluted * 100.0 / prefixes.size

        // Raw calibration line (grep target: "PhaseB precision").
        println(
            "PhaseB precision prefixes=${prefixes.size} " +
                "baseline(class1-only)=$baselinePolluted (${fmt(baselinePct)}%) " +
                "tatarPolicy(classes1+2+bonus)=$candidatePolluted (${fmt(candidatePct)}%) " +
                "gate<=2% verdict=${if (candidatePct <= 2.0) "PASS" else "ABOVE"}",
        )

        assertEquals(PIN_BASELINE_POLLUTED, baselinePolluted)
        assertEquals(PIN_CANDIDATE_POLLUTED, candidatePolluted)
    }

    private fun fmt(value: Double): String = "%.4f".format(java.util.Locale.ROOT, value)

    companion object {
        // Exact pins of the G2 measurement (2026-09-20, committed 110k asset + eval set,
        // device-true geometry); re-pin consciously when an input changes. The verdict was ABOVE
        // for the candidate (7.9337 % vs the 2 % gate) — and the class-#1-only baseline measures
        // 3.6268 %, above the same gate: the 2 % bar does not hold even for the pre-Phase-B
        // shipped behavior. Recorded, not tuned.
        private const val PIN_PREFIXES = 8_382
        private const val PIN_BASELINE_POLLUTED = 304
        private const val PIN_CANDIDATE_POLLUTED = 665

        private lateinit var prefixes: List<String>
        private var defaultIndex: TdictPrefixIndex? = null
        private var tatarIndex: TdictPrefixIndex? = null

        // The Phase-B candidate arm: classes #1+#2 + the same-length bonus (see the class doc).
        private val PHASE_B_CANDIDATE = FuzzyEditPolicy(
            intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS, TdictPrefixIndex.EDIT_CLASS_GEOMETRIC),
            true,
        )

        @JvmStatic
        @BeforeClass
        fun loadCommittedAssetsAndEvalSet() {
            val evalLines = locate(
                "src/test/resources/tt_eval_sentences.txt",
                "app/src/test/resources/tt_eval_sentences.txt",
            ).readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            val uniqueWords = evalLines.flatMap { it.split(" ") }.distinct()
            // Every distinct prefix of >= 3 code points of every unique eval word.
            prefixes = uniqueWords.flatMap { word ->
                val codePoints = word.codePoints().toArray()
                (3..codePoints.size).map { length ->
                    val builder = StringBuilder(length)
                    for (slot in 0 until length) builder.appendCodePoint(codePoints[slot])
                    builder.toString()
                }
            }.distinct()

            val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
            val asset = locate(
                "src/main/assets/${spec.assetPath}",
                "app/src/main/assets/${spec.assetPath}",
            )
            val rawFile = File.createTempFile("tt-typo-b-precision-", ".tdict")
            val raw: ByteArray
            try {
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(asset.inputStream(), output, spec)
                }
                TdictValidator().validateRaw(rawFile, spec)
                raw = rawFile.readBytes()
            } finally {
                rawFile.delete()
            }
            val identity = DictionaryIdentity(
                spec.generation, spec.schemaId, spec.formatVersion,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) },
            )
            defaultIndex = requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                    TatarSuffixRules, null,
                ),
            )
            tatarIndex = requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                    TatarSuffixRules, PHASE_B_CANDIDATE,
                ),
            )
            val table = E3bTestFixtures.tatarNeighborTable()
            defaultIndex!!.updateKeyNeighbors(table)
            tatarIndex!!.updateKeyNeighbors(table)
        }

        private fun locate(vararg paths: String): File =
            paths.map(::File).firstOrNull(File::isFile)
                ?: error("cannot locate committed eval test resource")
    }
}
