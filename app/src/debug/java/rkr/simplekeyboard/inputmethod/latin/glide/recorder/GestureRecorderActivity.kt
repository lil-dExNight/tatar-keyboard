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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The recorder's control screen: the opt-in dialog and the arm/disarm, export and discard
 * controls. Debug builds only, never exported.
 *
 * Arming is impossible without the dialog: [GestureRecorderController.grantConsentAndArm] is the
 * only arming path, and it is called only from the dialog's accept button here. The strings are
 * inlined English on purpose — this screen is a lab tool and ships in no locale.
 */
class GestureRecorderActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var prompt: TextView
    private lateinit var armButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GestureRecorderController.init(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        prompt = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        armButton = Button(this).apply {
            setOnClickListener {
                if (GestureRecorderController.isArmed()) {
                    GestureRecorderController.disarm()
                    refresh()
                } else {
                    showOptInDialog()
                }
            }
        }
        val exportButton = Button(this).apply {
            text = "Export records (share sheet)"
            setOnClickListener { GestureRecorderController.shareRecords(this@GestureRecorderActivity) }
        }
        val discardButton = Button(this).apply {
            text = "Discard all records"
            setOnClickListener {
                GestureRecorderController.discardAll()
                refresh()
            }
        }
        column.addView(status)
        column.addView(prompt)
        column.addView(armButton)
        column.addView(exportButton)
        column.addView(discardButton)
        setContentView(ScrollView(this).apply { addView(column) })

        refresh()
        if (!GestureRecorderController.hasConsent()) {
            showOptInDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showOptInDialog() {
        AlertDialog.Builder(this)
            .setTitle("Gesture collection (debug)")
            .setMessage(
                "This debug build can record glide traces of prompted words for decoder " +
                    "calibration. While armed, a red recording banner stays visible on the " +
                    "keyboard.\n\n" +
                    "Each record contains exactly: the prompt word (a dictionary word, never " +
                    "anything you typed), the trace resampled to 50 points in key-radius " +
                    "units, per-point timing, the layout id, the screen density bucket, and " +
                    "whether the decoder's top suggestion matched the prompt.\n\n" +
                    "Records stay in this app's private storage (excluded from backup), are " +
                    "capped at 2000, and leave the device only when you export them yourself. " +
                    "\"Discard all\" deletes them at any time."
            )
            .setPositiveButton("Agree and arm") { _, _ ->
                GestureRecorderController.grantConsentAndArm()
                refresh()
            }
            .setNegativeButton("Not now") { _, _ -> refresh() }
            .show()
    }

    private fun refresh() {
        val armed = GestureRecorderController.isArmed()
        status.text = if (armed) {
            "ARMED — recording prompted gestures. ${GestureRecorderController.recordCount()} records buffered."
        } else {
            "Disarmed — nothing is recorded. ${GestureRecorderController.recordCount()} records buffered."
        }
        status.setTextColor(if (armed) Color.rgb(0xB0, 0x00, 0x00) else Color.BLACK)
        val current = GestureRecorderController.currentPrompt()
        prompt.text = if (armed && current != null) "Trace: $current" else ""
        prompt.visibility = if (armed && current != null) View.VISIBLE else View.GONE
        armButton.text = if (armed) "Disarm" else "Arm (shows the consent dialog first)"
    }
}
