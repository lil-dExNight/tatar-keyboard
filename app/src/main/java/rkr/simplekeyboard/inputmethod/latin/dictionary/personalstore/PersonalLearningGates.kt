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
 * The pure conjunction behind `LatinIME.mayLearnPersonalWords`, the one predicate every learning
 * write path consults before touching a store. `LatinIME` computes the Android-state inputs; the
 * decision lives here so plain JVM tests cover every factor.
 *
 * The factors, in the order the predicate's KDoc names them:
 *  1. [suggestionsEligible] — which already carries the field allowing suggestions, the subtype,
 *     `IME_FLAG_NO_PERSONALIZED_LEARNING` and the null-editorInfo case;
 *  2. [personalDictionaryOn] — the personal dictionary setting;
 *  3. [userUnlocked] — the device unlocked at least once since boot (a missing `UserManager`
 *     means locked, never open);
 *  4. [postalAddressField] — the postal-address exclusion;
 *  5. [incognito] — the learning pause: while on, no new write reaches any personal store or its
 *     pending counters. The read side does not check it, so saved entries keep appearing.
 */
object PersonalLearningGates {

    /**
     * True only when every factor permits a write. With [incognito] on, no completion, acceptance
     * bump or flush reaches a store, pending counters included. Turning the pause off resumes
     * learning; nothing from the paused period is learned retroactively.
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
