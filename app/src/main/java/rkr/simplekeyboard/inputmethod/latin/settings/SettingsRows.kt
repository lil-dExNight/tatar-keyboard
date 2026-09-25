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

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rkr.simplekeyboard.inputmethod.R

/**
 * The row builders of the settings screens (T2 part 3, docs/ROADMAP-P6.md): every iOS-style row
 * the screens are built from — link, text, action, switch, value — plus the card scaffolding that
 * lays them out ([addSectionHeader], [addCard]) and the two small shared predicates
 * ([setRowEnabled], [isRestricted], [dp]).
 *
 * They are `internal` extension functions on [SettingsHostActivity], moved verbatim out of the
 * activity, so every call site in the screen builders kept its exact text: the source-contract
 * tests pin that text to `SettingsHostActivity.kt` (e.g. `switchRow(Settings.PREF_SHOW_EMOJI_KEY,
 * true`), and a pure move may not touch it. What the moved functions reach — `prefs`,
 * `contentView`, `currentDialog`, `restrictionKeys`, the companion constants — is `internal` on
 * the activity for the same mechanical reason parts 1–2 record.
 */
internal fun SettingsHostActivity.inflateRow(
    layoutRes: Int,
    title: CharSequence,
    summary: CharSequence?,
): View {
    val row = layoutInflater.inflate(layoutRes, contentView, false)
    row.findViewById<TextView>(R.id.row_title).text = title
    if (!summary.isNullOrEmpty()) {
        row.findViewById<TextView>(R.id.row_summary)?.apply {
            text = summary
            visibility = View.VISIBLE
        }
    }
    return row
}

internal fun SettingsHostActivity.inflateRow(layoutRes: Int, titleRes: Int, summaryRes: Int): View =
        inflateRow(layoutRes, getString(titleRes),
                if (summaryRes != 0) getString(summaryRes) else null)

/** Link row with dynamic texts (language rows on the Languages screen). */
internal fun SettingsHostActivity.linkRow(title: CharSequence, summary: CharSequence?,
                      onClick: () -> Unit): View {
    val row = inflateRow(R.layout.row_link, title, summary)
    rowClick(row) { onClick() }
    return row
}

internal fun SettingsHostActivity.linkRow(titleRes: Int, summaryRes: Int = 0, restrictionKey: String? = null,
                      onClick: () -> Unit): View {
    val row = inflateRow(R.layout.row_link, titleRes, summaryRes)
    rowClick(row) { onClick() }
    if (isRestricted(restrictionKey)) {
        setRowEnabled(row, false)
    }
    return row
}

/** Non-interactive text cell: a paragraph inside a card, with no chevron and no tap target. */
internal fun SettingsHostActivity.textRow(text: CharSequence): View {
    val row = inflateRow(R.layout.row_link, text, null)
    row.findViewById<View>(R.id.row_chevron).visibility = View.GONE
    row.isClickable = false
    row.isFocusable = false
    row.foreground = null
    return row
}

/**
 * Action row (iOS "button cell"): accent-colored title, no chevron —
 * it opens a dialog on the same screen instead of navigating.
 */
internal fun SettingsHostActivity.actionRow(titleRes: Int, onClick: () -> Unit): View {
    val row = inflateRow(R.layout.row_link, titleRes, 0)
    row.findViewById<TextView>(R.id.row_title).setTextColor(getColor(R.color.app_accent))
    row.findViewById<View>(R.id.row_chevron).visibility = View.GONE
    rowClick(row) { onClick() }
    return row
}

internal fun SettingsHostActivity.switchRow(key: String, defaultValue: Boolean, titleRes: Int, summaryRes: Int,
                      onCheckedChanged: ((Boolean) -> Unit)? = null): View {
    val row = switchRowRaw(getString(titleRes),
            if (summaryRes != 0) getString(summaryRes) else null,
            prefs.getBoolean(key, defaultValue)) { checked ->
        prefs.edit().putBoolean(key, checked).apply()
        onCheckedChanged?.invoke(checked)
        true
    }
    if (isRestricted(key)) {
        setRowEnabled(row, false)
    }
    return row
}

/**
 * Backing-store-agnostic switch row: [onToggle] applies the change and
 * returns whether it took effect — on false the switch is silently
 * reverted (the subtype rows need this when an add/remove fails).
 */
internal fun SettingsHostActivity.switchRowRaw(title: CharSequence, summary: CharSequence?,
                         initialChecked: Boolean, onToggle: (Boolean) -> Boolean): View {
    val row = inflateRow(R.layout.row_switch, title, summary)
    val switchView = row.findViewById<Switch>(R.id.row_switch)
    switchView.isChecked = initialChecked
    switchView.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(button: CompoundButton, checked: Boolean) {
            if (!onToggle(checked)) {
                button.setOnCheckedChangeListener(null)
                button.isChecked = !checked
                button.setOnCheckedChangeListener(this)
            }
        }
    })
    // The whole row is one tap target and one TalkBack node that
    // presents itself as the switch it toggles.
    rowClick(row) { switchView.toggle() }
    row.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View,
                                                       info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = switchView.isChecked
            // F15a: a soft-disabled row is still tappable (it explains itself), but TalkBack
            // must call it what it is.
            if (rowIsSoftDisabled(host)) {
                info.isEnabled = false
            }
        }
    }
    return row
}

internal fun SettingsHostActivity.valueRow(key: String, titleRes: Int, minValue: Int, maxValue: Int, stepValue: Int,
                     proxy: SeekBarDialogHelper.ValueProxy): View {
    val row = inflateRow(R.layout.row_value, titleRes, 0)
    val valueView = row.findViewById<TextView>(R.id.row_value)
    valueView.text = proxy.getValueText(proxy.readValue(key))
    rowClick(row) {
        currentDialog?.dismiss()
        currentDialog = SeekBarDialogHelper.show(this, getString(titleRes), key,
                minValue, maxValue, stepValue, proxy) {
            valueView.text = proxy.getValueText(proxy.readValue(key))
        }
    }
    if (isRestricted(key)) {
        setRowEnabled(row, false)
    }
    return row
}

/**
 * Uppercase 13sp section header above a card (iOS grouped-list header).
 * The card that follows should pass spacedFromPrevious = false to
 * [addCard] — the header carries the vertical spacing itself.
 */
internal fun SettingsHostActivity.addSectionHeader(text: CharSequence) {
    // The 4-arg constructor applies AppText.SectionHeader as defStyleRes.
    val header = TextView(this, null, 0, R.style.AppText_SectionHeader)
    header.text = text
    header.setPaddingRelative(dp(16), 0, dp(16), 0)
    val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(20)
    params.bottomMargin = dp(6)
    header.layoutParams = params
    contentView.addView(header)
}

/**
 * Appends a card group to the content column: per-position rounded
 * backgrounds (wave D drawables) and a 1px inset hairline between rows,
 * none after the last. [spacedFromPrevious] is turned off when a
 * section header directly above already provides the gap.
 */
internal fun SettingsHostActivity.addCard(rows: List<View>, spacedFromPrevious: Boolean = true) {
    rows.forEachIndexed { index, row ->
        row.background = getDrawable(when {
            rows.size == 1 -> R.drawable.app_card_bg
            index == 0 -> R.drawable.app_card_top
            index == rows.size - 1 -> R.drawable.app_card_bottom
            else -> R.drawable.app_card_middle
        })
        if (index == 0) {
            if (spacedFromPrevious) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
            }
        } else {
            contentView.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1)
                background = getDrawable(R.drawable.app_row_divider)
            })
        }
        contentView.addView(row)
    }
}

/**
 * F15(a) of `docs/AUDIT-2026-09-24-FIXES.md`, closed in stage C of `docs/ROADMAP-P8-PLAN.md`:
 * a row that depends on a switch that is off used to swallow the tap in silence — the user got
 * no hint why nothing happened. A soft-disabled row now keeps its tap target and answers with a
 * short explanation; the SWITCH inside it stays disabled, so the tap can never toggle anything.
 *
 * [reasonRes] is the explanation. Zero keeps the old hard-disabled behaviour (used where the
 * reason is not a switch the user can flip, e.g. the last remaining language).
 */
internal fun SettingsHostActivity.setRowEnabled(row: View, enabled: Boolean, reasonRes: Int = 0) {
    val softDisabled = !enabled && reasonRes != 0
    row.setTag(R.id.tag_row_disabled_reason, if (softDisabled) reasonRes else null)
    // A soft-disabled row must stay enabled to receive the tap that shows the explanation; its
    // accessibility node still reports "disabled" (see rowClick).
    row.isEnabled = enabled || softDisabled
    // Dim the row contents, not the row itself: the row carries the card
    // background segment, and fading it would punch a hole in the card.
    if (row is ViewGroup) {
        val childAlpha = if (enabled) 1f else SettingsHostActivity.DISABLED_ALPHA
        for (i in 0 until row.childCount) {
            row.getChildAt(i).alpha = childAlpha
        }
    } else {
        row.alpha = if (enabled) 1f else SettingsHostActivity.DISABLED_ALPHA
    }
    row.findViewById<Switch>(R.id.row_switch)?.isEnabled = enabled
}

/**
 * Installs a row's tap action through the soft-disabled gate (F15a): while the row carries a
 * disabled reason, the tap shows that reason instead of running [action].
 */
internal fun SettingsHostActivity.rowClick(row: View, action: () -> Unit) {
    row.setOnClickListener {
        val reason = row.getTag(R.id.tag_row_disabled_reason) as? Int
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        } else {
            action()
        }
    }
}

/**
 * F15a: picks the explanation for a dimmed row. An MDM restriction is not something the user can
 * flip, so it gets its own wording; otherwise the reason is the switch the row depends on.
 */
internal fun disabledReason(restricted: Boolean, dependencyReasonRes: Int): Int =
        if (restricted) R.string.row_locked_by_admin else dependencyReasonRes

/** True while the row is soft-disabled — the a11y delegates report it as a disabled node. */
internal fun rowIsSoftDisabled(row: View): Boolean =
        row.getTag(R.id.tag_row_disabled_reason) != null

internal fun SettingsHostActivity.isRestricted(key: String?): Boolean {
    return key != null && restrictionKeys.contains(key)
}

internal fun SettingsHostActivity.dp(value: Int): Int {
    return Math.round(value * resources.displayMetrics.density)
}
