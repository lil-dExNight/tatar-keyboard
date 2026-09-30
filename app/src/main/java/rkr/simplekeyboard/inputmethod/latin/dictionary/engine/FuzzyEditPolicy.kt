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
 * engine runs [TATAR], every other engine runs [DEFAULT].
 *
 * [editClasses] holds EDIT_CLASS_* values; their order is irrelevant, because
 * [TdictPrefixIndex.collectFuzzy] always runs and ranks the classes in numeric order.
 * [sameLengthBonus] ranks a candidate exactly as long as the typed prefix before its own
 * continuations within its edit class, so the corrected word itself comes before longer words
 * that start with it.
 *
 * [autocorrectClasses] are the edit classes autocorrect may draw from. The default is class #1
 * alone, and the display classes never change it implicitly.
 */
class FuzzyEditPolicy(
    val editClasses: IntArray,
    val sameLengthBonus: Boolean,
    val autocorrectClasses: IntArray = intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS),
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
         * Edit class #1 (long-press partner) only, no same-length bonus. Every engine without an
         * explicit policy, the Russian one included, runs this.
         */
        @JvmField
        val DEFAULT = FuzzyEditPolicy(intArrayOf(TdictPrefixIndex.EDIT_CLASS_LONG_PRESS), false)

        /**
         * The Tatar configuration: class #1 always, class #4 (probe-first full single substitution,
         * only on an empty exact pass at >= 4 code points, see [TdictPrefixIndex.collectFuzzy]) and
         * the same-length bonus.
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
