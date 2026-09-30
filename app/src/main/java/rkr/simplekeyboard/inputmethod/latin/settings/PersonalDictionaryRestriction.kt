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

package rkr.simplekeyboard.inputmethod.latin.settings

/**
 * The one-directional enterprise restriction of the personal dictionary. The generic branch of
 * `Settings.loadRestrictions` writes a policy value in either direction and `SettingsHostActivity`
 * disables the row of every active key; here that would let an administrator force the keyboard to
 * save typed words and lock the user out of turning it off. So the policy applies only when it
 * restricts:
 *  - policy `false` → `false` is written into preferences and the settings row is disabled;
 *  - policy `true`  → nothing is written and the row stays live; the choice stays with the user.
 *
 * Kept apart from `Settings` so both directions are covered by plain JVM tests
 * (`Settings.loadRestrictions` needs a `RestrictionsManager` and real `SharedPreferences`).
 */
object PersonalDictionaryRestriction {

    /**
     * True when a policy carrying [policyValue] may write the preference at all. Only the
     * restrictive direction writes; the permissive one writes nothing.
     */
    @JvmStatic
    fun writesPreference(policyValue: Boolean): Boolean = !policyValue

    /**
     * The value written when [writesPreference] allows it — always `false`. Stated as its own
     * function so no call site can pass the policy value through by accident.
     */
    @JvmStatic
    fun valueToWrite(): Boolean = false

    /**
     * The set stored in `Settings.ACTIVE_RESTRICTIONS`, which is what disables settings rows.
     *
     * Identical to [policyKeys] except for one case: a permissive personal-dictionary policy is
     * dropped, so the row stays enabled. [personalDictionaryPolicy] is null when the policy bundle
     * does not carry the key at all (an absent key must not be read as `false`).
     */
    @JvmStatic
    fun effectiveRestrictionKeys(
        policyKeys: Set<String>,
        personalDictionaryPolicy: Boolean?,
    ): Set<String> {
        if (personalDictionaryPolicy != true) return policyKeys
        if (!policyKeys.contains(Settings.PREF_PERSONAL_DICTIONARY)) return policyKeys
        return policyKeys.filterTo(LinkedHashSet()) { it != Settings.PREF_PERSONAL_DICTIONARY }
    }
}
