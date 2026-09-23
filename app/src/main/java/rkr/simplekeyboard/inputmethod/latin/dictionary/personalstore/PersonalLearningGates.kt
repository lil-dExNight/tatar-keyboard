/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

/**
 * The pure conjunction behind `LatinIME.mayLearnPersonalWords` — the ONE predicate every learning
 * write path (words and pairs alike) consults before touching a store.
 *
 * The factors are Android state (an eligibility computation, preferences, `UserManager`, an
 * `EditorInfo`); the conjunction of them is not. It lives here, apart from `LatinIME`, precisely
 * so every factor — including the U8 incognito pause — is covered by plain JVM tests, in the
 * established style of `PersonalDictionaryRestriction`: the class that needs a device computes
 * the inputs, the decision itself is arithmetic and is tested as arithmetic.
 *
 * The factors, in the order the predicate's KDoc names them:
 *  1. [suggestionsEligible] — which already carries the field allowing suggestions, the subtype,
 *     `IME_FLAG_NO_PERSONALIZED_LEARNING` and the null-editorInfo case;
 *  2. [personalDictionaryOn] — the personal dictionary setting;
 *  3. [userUnlocked] — the device unlocked at least once since boot (a missing `UserManager`
 *     means locked, never open);
 *  4. [postalAddressField] — the postal-address exclusion;
 *  5. [incognito] — the U8 pause: while ON, NO new write reaches either personal store or its
 *     pending counters. It gates writes only; what is already saved keeps surfacing, because the
 *     read side never consults this factor.
 */
object PersonalLearningGates {

    /**
     * True only when every factor permits a write. [incognito] sits last and vetoes everything:
     * with the pause on, no completion, no acceptance bump and no flush reaches a store — the
     * pending counters included, because the only writer of a pending hash is the completion
     * event the sinks gate with this same predicate. Turning the pause off resumes learning with
     * the counters exactly as they were left; nothing from the paused period exists anywhere, so
     * nothing can be made up.
     */
    @JvmStatic
    fun mayLearn(
        suggestionsEligible: Boolean,
        personalDictionaryOn: Boolean,
        userUnlocked: Boolean,
        postalAddressField: Boolean,
        incognito: Boolean,
    ): Boolean =
        suggestionsEligible && personalDictionaryOn && userUnlocked && !postalAddressField && !incognito
}
