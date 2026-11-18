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

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils

/**
 * Seek-bar value dialog for the View-based settings screens: a plain AlertDialog over
 * layout/seek_bar_dialog.xml with the value semantics of the former SeekBarDialogPreference
 * (progress↔value mapping, step clipping, OK/Cancel/Default buttons).
 */
object SeekBarDialogHelper {

    /** Reads, writes and formats the value behind one seek-bar row. */
    interface ValueProxy {
        fun readValue(key: String): Int
        fun readDefaultValue(key: String): Int
        fun writeValue(value: Int, key: String)
        fun writeDefaultValue(key: String)
        fun getValueText(value: Int): String
        fun feedbackValue(value: Int)
    }

    /** Describes one independently persisted slider in a multi-slider dialog. */
    data class Entry(
        val title: CharSequence,
        val key: String,
        val minValue: Int,
        val maxValue: Int,
        val stepValue: Int,
        val proxy: ValueProxy,
        val enabled: Boolean = true
    )

    /** Binds one Entry to its own inflated views and ValueProxy. */
    private class EntryBinding(
        private val entry: Entry,
        view: View
    ) {
        private val valueView = view.findViewById<TextView>(R.id.seek_bar_dialog_value)
        private val seekBar = view.findViewById<SeekBar>(R.id.seek_bar_dialog_bar)

        init {
            seekBar.max = entry.maxValue - entry.minValue
            seekBar.tag = entry.key
            seekBar.contentDescription = entry.title
            seekBar.isEnabled = entry.enabled
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueView.text = entry.proxy.getValueText(
                        clipValue(progress + entry.minValue))
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    entry.proxy.feedbackValue(clipValue(bar.progress + entry.minValue))
                }
            })

            val value = entry.proxy.readValue(entry.key)
            valueView.text = entry.proxy.getValueText(value)
            seekBar.progress = clipValue(value) - entry.minValue
        }

        fun writeValue() {
            if (entry.enabled) {
                entry.proxy.writeValue(clipValue(seekBar.progress + entry.minValue), entry.key)
            }
        }

        fun writeDefaultValue() {
            if (entry.enabled) {
                entry.proxy.writeDefaultValue(entry.key)
            }
        }

        private fun clipValue(value: Int): Int {
            val clipped = value.coerceIn(entry.minValue, entry.maxValue)
            return if (entry.stepValue <= 1) clipped else clipped - clipped % entry.stepValue
        }
    }

    fun show(
        activity: Activity,
        title: CharSequence,
        key: String,
        minValue: Int,
        maxValue: Int,
        stepValue: Int,
        proxy: ValueProxy,
        onValueChanged: () -> Unit
    ): AlertDialog = show(
        activity,
        title,
        listOf(
            Entry(
                title = title,
                key = key,
                minValue = minValue,
                maxValue = maxValue,
                stepValue = stepValue,
                proxy = proxy
            )
        ),
        onValueChanged
    )

    fun show(
        activity: Activity,
        dialogTitle: CharSequence,
        entries: List<Entry>,
        onValueChanged: () -> Unit
    ): AlertDialog {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bindings = entries.map { entry ->
            val view = activity.layoutInflater.inflate(
                R.layout.seek_bar_dialog, container, false) as LinearLayout
            if (entries.size > 1) {
                view.addView(TextView(activity).apply { text = entry.title }, 0)
            }
            container.addView(view)
            EntryBinding(entry, view)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                bindings.forEach { it.writeValue() }
                onValueChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.button_default) { _, _ ->
                bindings.forEach { it.writeDefaultValue() }
                onValueChanged()
            }
            .create()
        // Same rule as the rest of the app's dialogs: the layout's own rows already carry
        // android:filterTouchesWhenObscured, the window's decor view covers the platform's
        // button panel.
        DialogUtils.filterObscuredTouches(dialog)
        dialog.show()
        return dialog
    }
}
