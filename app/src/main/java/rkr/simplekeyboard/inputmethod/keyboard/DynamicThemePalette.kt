// Copyright (C) 2026 Tatar Keyboard contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package rkr.simplekeyboard.inputmethod.keyboard

import android.content.Context
import android.content.res.Resources
import android.os.Build

/**
 * The role table of the dynamic theme and the single gate that decides whether the framework
 * system colors (the Material You tonal palette) may drive it. The table mirrors
 * values-v31/colors-dynamic.xml and values-night-v31/colors-dynamic.xml slot by slot — the
 * contract test pins all three equal. Slots missing from the table (shadow, dimmed hint inks)
 * are fixed translucents on every tier: the framework palette carries no translucent roles.
 */
object DynamicThemePalette {

    /** One dyn_* color slot mapped to its framework system color role per uiMode. */
    data class RoleMapping(val slot: String, val lightRole: String, val darkRole: String)

    // One entry per line: the contract test parses this table with a per-line regex.
    private val ROLES = listOf(
        RoleMapping("dyn_keyboard_background", "system_neutral2_100", "system_neutral2_800"),
        RoleMapping("dyn_key_normal", "system_neutral1_10", "system_neutral2_600"),
        RoleMapping("dyn_key_normal_pressed", "system_neutral2_200", "system_neutral2_500"),
        RoleMapping("dyn_key_functional", "system_neutral2_300", "system_neutral2_700"),
        RoleMapping("dyn_key_functional_pressed", "system_neutral1_10", "system_neutral2_500"),
        RoleMapping("dyn_key_checked", "system_neutral2_400", "system_neutral2_500"),
        RoleMapping("dyn_key_sticky_on", "system_neutral1_10", "system_neutral2_600"),
        RoleMapping("dyn_key_spacebar", "system_neutral1_10", "system_neutral2_600"),
        RoleMapping("dyn_key_spacebar_pressed", "system_neutral2_200", "system_neutral2_500"),
        RoleMapping("dyn_popup_key_pressed", "system_accent1_600", "system_accent1_100"),
        RoleMapping("dyn_key_text_color", "system_neutral1_900", "system_neutral1_0"),
        RoleMapping("dyn_key_functional_text_color", "system_neutral1_900", "system_neutral1_0"),
        RoleMapping("dyn_key_action_fill", "system_accent1_600", "system_accent1_100"),
        RoleMapping("dyn_key_action_text_color", "system_accent1_0", "system_neutral1_900"),
        RoleMapping("dyn_suggestion_emphasis", "system_accent1_600", "system_accent1_100"),
        RoleMapping("dyn_glide_trail", "system_accent1_600", "system_accent1_100"),
    )

    // The answer is process-constant (framework colors cannot appear or disappear without a
    // process restart), while the check runs on every keyboard-theme refresh.
    private var resolvable: Boolean? = null

    /**
     * True only where the dynamic theme may paint from the framework palette: API 31 and up
     * (the system_* colors do not exist below) and every mapped role of both uiModes actually
     * resolves to a color here. Any failure means the caller picks the default theme style
     * wholesale — a role that resolves halfway never reaches the screen.
     */
    @JvmStatic
    fun isResolvable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        resolvable?.let { return it }
        val result = ROLES.all { mapping ->
            resolves(context, mapping.lightRole) && resolves(context, mapping.darkRole)
        }
        resolvable = result
        return result
    }

    private fun resolves(context: Context, role: String): Boolean {
        val id = context.resources.getIdentifier(role, "color", "android")
        if (id == 0) {
            return false
        }
        return try {
            context.getColor(id)
            true
        } catch (e: Resources.NotFoundException) {
            false
        }
    }
}
