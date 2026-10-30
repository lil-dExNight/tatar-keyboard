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
 * Per-engine configuration of the typo-recovery pass: which edit classes run and whether the
 * same-length bonus applies. Injected per engine through [MappedDictionaryEngine.start]; the Tatar
 * engine runs [TATAR], the Russian engine runs [RUSSIAN], and an engine opened without an
 * explicit policy runs [DEFAULT].
 *
 * [editClasses] holds EDIT_CLASS_* values; their order is irrelevant. EDIT_CLASS_LONG_PRESS runs
 * on every typo-recovery activation; EDIT_CLASS_SUBSTITUTION engages the shared DL-1 tier
 * (substitution, deletion, insertion and transposition under one rank tag) on its gated domain —
 * see [TdictPrefixIndex.collectFuzzy].
 * [sameLengthBonus] ranks a candidate exactly as long as its variant before that variant's
 * continuations, so the corrected word itself comes before longer words that start with it.
 * [sameLengthBonusOnlyInDl1] leaves class #1 ranked by frequency while retaining that order in
 * the shared DL-1 tier.
 *
 * [autocorrectClasses] are the edit classes autocorrect may draw from. The default is class #1
 * alone, and the display classes never change it implicitly.
 */
class FuzzyEditPolicy(
    val editClasses: IntArray,
    val sameLengthBonus: Boolean,
    val autocorrectClasses: IntArray = intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
    val sameLengthBonusOnlyInDl1: Boolean = false,
) {
    init {
        require(editClasses.isNotEmpty()) { "a fuzzy policy needs at least one edit class" }
        require(autocorrectClasses.isNotEmpty()) { "a fuzzy policy needs an autocorrect class" }
        for (editClass in editClasses + autocorrectClasses) {
            require(
                editClass == TdictPrefixIndex.EDIT_CLASS_LONG_PRESS ||
                    editClass == TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ) { "unknown edit class $editClass" }
        }
    }

    companion object {
        /**
         * Edit class #1 (long-press partner) only, no same-length bonus. The fallback of an
         * engine opened without an explicit policy; no shipped engine runs it.
         */
        @JvmField
        val DEFAULT = FuzzyEditPolicy(intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS), false)

        /**
         * The Tatar configuration: class #1 on the always-on domain, the DL-1 tier (all four
         * distance-1 families, one rank tag) on the empty-exact >= 4-code-point domain, and the
         * same-length bonus. See [TdictPrefixIndex.collectFuzzy].
         */
        @JvmField
        val TATAR = FuzzyEditPolicy(
            intArrayOf(
                TdictPrefixIndex.EDIT_CLASS_LONG_PRESS,
                TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ),
            true,
        )

        /**
         * The Russian configuration: class #1 on the always-on domain and the DL-1 tier on the
         * empty-exact >= 4-code-point domain. The same-length bonus applies only inside the tier,
         * so class #1 outside that domain ranks exactly as under [DEFAULT].
         */
        @JvmField
        val RUSSIAN = FuzzyEditPolicy(
            intArrayOf(
                TdictPrefixIndex.EDIT_CLASS_LONG_PRESS,
                TdictPrefixIndex.EDIT_CLASS_SUBSTITUTION,
            ),
            true,
            sameLengthBonusOnlyInDl1 = true,
        )
    }
}
