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

package rkr.simplekeyboard.inputmethod.latin.settings

import android.app.AlertDialog
import android.view.View
import android.widget.Switch
import java.util.Locale
import java.util.TreeSet
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.latin.common.LocaleUtils
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsHostActivity.Screen
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils
import rkr.simplekeyboard.inputmethod.latin.utils.SubtypeLocaleUtils

/**
 * The two languages screens of [SettingsHostActivity] (T2 part 3, docs/ROADMAP-P6.md): "Keyboard
 * languages" and the per-language layouts screen, ported 1:1 from LanguagesSettingsFragment and
 * SingleLanguageSettingsFragment in the wave-S2 redesign, moved here verbatim from the activity.
 *
 * They are `internal` extension functions on the activity, so the `showScreen` dispatch and every
 * row-builder call kept its exact text. What they reach — `richImm`, `detailLocale`,
 * `currentDialog`, `navigateTo`, `showScreen`, the [SettingsHostActivity.Screen] enum — is
 * `internal` on the activity for the same mechanical reason parts 1–2 record.
 */

/**
 * "Keyboard languages": a card with one row per enabled language
 * (summary lists its enabled layouts), then an actions card with
 * "Add language" and — with more than one language — "Remove language",
 * both opening the same multi-choice dialogs the legacy screen used.
 */
internal fun SettingsHostActivity.buildLanguagesScreen() {
    val comparator = LocaleUtils.LocaleComparator()
    val usedLocales = TreeSet<Locale>(comparator)
    for (subtype in richImm.getEnabledSubtypes(false)) {
        usedLocales.add(subtype.localeObject)
    }
    val unusedLocales = TreeSet<Locale>(comparator)
    for (localeString in SubtypeLocaleUtils.getSupportedLocales()) {
        val locale = LocaleUtils.constructLocaleFromString(localeString)
        if (!usedLocales.contains(locale)) {
            unusedLocales.add(locale)
        }
    }

    val usedValues = usedLocales.map { LocaleUtils.getLocaleString(it) }
    val unusedValues = unusedLocales.map { LocaleUtils.getLocaleString(it) }

    addSectionHeader(getString(R.string.user_languages))
    addCard(usedValues.map { localeString ->
        val layoutNames = richImm.getEnabledSubtypesForLocale(localeString)
                .joinToString(", ") { it.layoutDisplayName }
        linkRow(LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(localeString),
                layoutNames) {
            detailLocale = localeString
            navigateTo(Screen.LANGUAGE_DETAIL)
        }
    }, spacedFromPrevious = false)

    val actions = ArrayList<View>()
    // The row hides when there is nothing to add (2026-09-24 audit, finding 15b): the picker
    // would otherwise open on an empty list — a dialog with no items and a dead OK.
    if (unusedValues.isNotEmpty()) {
        actions.add(actionRow(R.string.add_language) {
            showLocalePickerDialog(unusedValues, R.string.add_language, R.string.add,
                    allowAllChecked = true) { checkedValues ->
                // Enable the default layout for all of the checked languages.
                for (localeString in checkedValues) {
                    richImm.addSubtype(
                            SubtypeLocaleUtils.getDefaultSubtype(localeString, resources))
                }
            }
        })
    }
    if (usedValues.size > 1) {
        actions.add(actionRow(R.string.remove_language) {
            showLocalePickerDialog(usedValues, R.string.remove_language, R.string.remove,
                    allowAllChecked = false) { checkedValues ->
                // Disable all of the layouts of the checked languages.
                for (localeString in checkedValues) {
                    for (subtype in richImm.getEnabledSubtypesForLocale(localeString)) {
                        richImm.removeSubtype(subtype)
                    }
                }
            }
        })
    }
    if (actions.isNotEmpty()) {
        addCard(actions)
    }
}

/**
 * Multi-choice language dialog shared by add/remove, ported from
 * LanguagesSettingsFragment.showMultiChoiceDialog: the positive button
 * is only enabled while at least one item is checked and — unless
 * [allowAllChecked] — at least one is unchecked (removing every
 * language at once must stay impossible). On accept the checked locale
 * strings go to [onAccept] and the screen is rebuilt.
 */
internal fun SettingsHostActivity.showLocalePickerDialog(localeValues: List<String>, titleRes: Int,
                                   positiveButtonRes: Int, allowAllChecked: Boolean,
                                   onAccept: (List<String>) -> Unit) {
    val names = localeValues.map {
        LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(it) as CharSequence
    }.toTypedArray()
    val checkedItems = BooleanArray(localeValues.size)
    currentDialog?.dismiss()
    val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMultiChoiceItems(names, checkedItems) { dialogInterface, _, _ ->
                var hasCheckedItem = false
                var hasUncheckedItem = false
                for (itemChecked in checkedItems) {
                    if (itemChecked) hasCheckedItem = true else hasUncheckedItem = true
                }
                (dialogInterface as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
                        .isEnabled = hasCheckedItem && (hasUncheckedItem || allowAllChecked)
            }
            .setPositiveButton(positiveButtonRes) { _, _ ->
                onAccept(localeValues.filterIndexed { index, _ -> checkedItems[index] })
                // Refresh the list of enabled languages (legacy buildContent).
                showScreen(Screen.LANGUAGES)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    DialogUtils.filterObscuredTouches(dialog)
    dialog.show()
    // Disable the positive button since nothing is checked by default.
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
    currentDialog = dialog
}

/**
 * Layouts of one language: a switch row per available layout. The last
 * enabled layout's row is locked so a language can never lose all of
 * its layouts — SingleLanguageSettingsFragment's invariant.
 */
internal fun SettingsHostActivity.buildLanguageDetailScreen(locale: String) {
    addSectionHeader(getString(R.string.generic_language_layouts,
            LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(locale)))

    val enabledSubtypes = richImm.getEnabledSubtypes(false)
    val subtypes = SubtypeLocaleUtils.getSubtypes(locale, resources)
    val rows = ArrayList<View>()
    val switches = ArrayList<Switch>()

    fun updateLastLayoutLock() {
        val checkedCount = switches.count { it.isChecked }
        switches.forEachIndexed { index, switchView ->
            setRowEnabled(rows[index], !(checkedCount == 1 && switchView.isChecked))
        }
    }

    for (subtype in subtypes) {
        val row = switchRowRaw(subtype.layoutDisplayName, null,
                enabledSubtypes.contains(subtype)) { checked ->
            val applied = if (checked) {
                richImm.addSubtype(subtype)
            } else {
                richImm.removeSubtype(subtype)
            }
            if (applied) {
                updateLastLayoutLock()
            }
            applied
        }
        rows.add(row)
        switches.add(row.findViewById(R.id.row_switch))
    }
    addCard(rows, spacedFromPrevious = false)
    updateLastLayoutLock()
}
