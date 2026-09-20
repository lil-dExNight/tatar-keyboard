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

/**
 * TT-TYPO-NEXT Phases B/C (docs/TT-TYPO-NEXT.md): the per-engine configuration of the fuzzy
 * suggestion pass — WHICH edit classes run and whether the same-length bonus applies. Before
 * Phase B this was a single global constant ([TdictPrefixIndex]-era SHIPPED_FUZZY_EDIT_CLASSES,
 * class #1 only); the choice is now injected per engine through the same seam as the P3 suffix
 * rules ([MappedDictionaryEngine.start]).
 *
 * Calibration history (docs/TT-TYPO-NEXT.md): the Phase-B candidate (classes #1+#2, geometric
 * neighbour) failed gate G1 (2026-09-20) and was never wired. The Phase-C candidate [TATAR] —
 * class #1 always plus the probe-first full single substitution (class #4) gated on an EMPTY
 * exact pass at >= 4 code points — failed its first measurement round the same day (G3-C: the
 * naive probe path cost 31.6 ms p95 on the reference device), and after the Phase-C2 probe
 * engineering (no-cache probe search + per-position range narrowing, device p95 3.3 ms) PASSED
 * the corrected gates G1-C2 (+27.2 pp same-set lift), G2-C2 (21.1 % activation, structurally
 * capped) and G3-C2. It is wired to the Tatar engine by LatinIME; every other engine runs
 * [DEFAULT].
 *
 * [editClasses] carries the EDIT_CLASS_* values of the classes the pass runs; their order inside
 * the array is irrelevant — [TdictPrefixIndex.collectFuzzy] always runs and ranks the classes in
 * their fixed numeric order. [sameLengthBonus] makes a fuzzy candidate that is exactly as long as
 * the typed prefix rank before its own continuations within its edit class (frequency order is
 * preserved otherwise); it is what puts the correction itself — "сәләм" — above "сәләмәтлек".
 *
 * The class set never reorders the exact level and never reaches autocorrect: the D3 pass pins
 * class #1 by its own contract regardless of this policy.
 */
class FuzzyEditPolicy(
    val editClasses: IntArray,
    val sameLengthBonus: Boolean,
) {
    init {
        require(editClasses.isNotEmpty()) { "a fuzzy policy needs at least one edit class" }
        for (editClass in editClasses) {
            require(
                editClass == TdictPrefixIndex.EDIT_CLASS_LONG_PRESS ||
                    editClass == TdictPrefixIndex.EDIT_CLASS_GEOMETRIC ||
                    editClass == TdictPrefixIndex.EDIT_CLASS_TRANSPOSITION ||
                    editClass == TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ) { "unknown edit class $editClass" }
        }
    }

    companion object {
        /**
         * Exactly the pre-Phase-B shipped behavior: edit class #1 (long-press partner) only, no
         * same-length bonus. Every engine without an explicit policy — the Russian one included —
         * runs this, so its behavior is bit-identical to what shipped before Phase B.
         */
        @JvmField
        val DEFAULT = FuzzyEditPolicy(intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS), false)

        /**
         * The shipped Tatar configuration (since Phase C2, 2026-09-20): class #1 (long-press
         * partner, always) plus class #4 (probe-first full single substitution — fires only on an
         * empty exact pass at >= 4 code points, see [TdictPrefixIndex.collectFuzzy]) and the
         * same-length bonus. Class #2 stays out per the Phase-B G1 verdict; class #3 was never
         * re-calibrated.
         */
        @JvmField
        val TATAR = FuzzyEditPolicy(
            intArrayOf(
                TdictPrefixIndex.EDIT_CLASS_LONG_PRESS,
                TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ),
            true,
        )
    }
}
