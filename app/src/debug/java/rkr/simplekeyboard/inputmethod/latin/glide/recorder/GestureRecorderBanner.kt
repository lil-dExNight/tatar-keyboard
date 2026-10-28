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

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * The persistent visible indicator the protocol requires while the recorder is armed: an opaque
 * banner that shows the recording state and the current prompt word. The keyboard-side wiring
 * attaches the view to the input view's root for as long as
 * [GestureRecorderController.isArmed] is true (an armed listener on the controller drives the
 * attach and detach) and calls [update] whenever the prompt advances.
 */
object GestureRecorderBanner {

    /** View tag for the banner, so the wiring can find and remove exactly this view. */
    const val VIEW_TAG = "gesture_recorder_banner"

    fun createView(context: Context): View {
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        return TextView(context).apply {
            tag = VIEW_TAG
            setBackgroundColor(Color.rgb(0xB0, 0x00, 0x00))
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(pad, pad / 2, pad, pad)
            textSize = 14f
            text = textFor(GestureRecorderController.currentPrompt())
        }
    }

    /** Refreshes the banner's prompt; the view must be one [createView] made. */
    fun update(view: View, prompt: String?) {
        (view as? TextView)?.text = textFor(prompt)
    }

    private fun textFor(prompt: String?): String =
        if (prompt == null) "REC — gesture recording armed" else "REC — trace: $prompt"
}
