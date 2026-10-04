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

package rkr.simplekeyboard.inputmethod.latin.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity
import rkr.simplekeyboard.inputmethod.latin.utils.AppLocale

/**
 * Two-step onboarding screen, following the AOSP LatinIME
 * SetupWizardActivity pattern in a minimal single-Activity form: step 1
 * enables the IME via the system input-method settings screen, step 2
 * selects it as the current keyboard via the system input-method picker.
 * A launch onto a completed setup forwards straight to the settings and
 * finishes; the done block with the try-it field belongs to the instance
 * that watched the steps get finished.
 *
 * Both step states are read live from the system on every appearance
 * (enabled input-method list and Settings.Secure.DEFAULT_INPUT_METHOD) —
 * no completion flag is stored, the system is the single source of truth.
 * States are re-read in both [onResume] and [onWindowFocusChanged] because
 * the input-method picker is a floating window and returning from it does
 * not reliably trigger onResume.
 *
 * Deliberately NOT directBootAware: first-time setup happens on an
 * unlocked device. Incoming intent extras are ignored entirely — the
 * Activity only reads system state and starts fixed system intents.
 */
class SetupActivity : Activity() {

    companion object {
        private val TAG = SetupActivity::class.java.simpleName

        /** Cadence of the enable watcher, ms: one cheap binder read per tick. */
        private const val ENABLE_WATCH_INTERVAL_MS = 500L

        /** Bail-out for the enable watcher: ~5 minutes of background polling,
         *  after which the next resume re-reads the state anyway. */
        private const val ENABLE_WATCH_MAX_TICKS = 600
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watchingForEnable = false
    private var enableWatchTicks = 0

    // True once this instance has rendered an incomplete wizard: completing
    // the steps in place then keeps this instance on the done block, while a
    // fresh launch onto a completed setup forwards to the settings instead.
    private var sawIncompleteSetup = false

    /**
     * Auto-return watcher, alive only while the user is away in the system
     * input-method settings: once this IME shows up as enabled, the wizard
     * relaunches on top of the settings at whichever step is now pending.
     * Leak-safe by construction: [onResume] and [onDestroy] both remove the
     * queued callback, so the main looper never holds this activity past its
     * destruction, and the flag guards a callback already mid-dispatch.
     */
    private val enableWatcher = object : Runnable {
        override fun run() {
            if (!watchingForEnable) return
            if (isImeEnabled()) {
                watchingForEnable = false
                startActivity(Intent(this@SetupActivity, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return
            }
            if (++enableWatchTicks >= ENABLE_WATCH_MAX_TICKS) {
                watchingForEnable = false
                return
            }
            handler.postDelayed(this, ENABLE_WATCH_INTERVAL_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Tatar is the app's UI default (see AppLocale): Russian and Tatar systems
        // resolve on their own, everything else is wrapped into Tatar here.
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher tap onto a completed setup wants the settings, not the
        // wizard; forward before any view exists so nothing flashes. Explicit
        // `am start -n` launches (the device scripts type into the try-it
        // field) carry no launcher category and keep the full screen.
        if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && SetupState.shouldForwardToSettings(
                SetupState.isSetupComplete(isImeEnabled(), isImeEnabled() && isImeCurrent()),
                setupIncompleteSeen = false)) {
            openSettingsAndFinish()
            return
        }
        setContentView(R.layout.setup_activity)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val root = findViewById<View>(R.id.setup_root)
            root.setOnApplyWindowInsetsListener { view, windowInsets ->
                // ime() keeps the try-it field visible above the keyboard
                // once the done block opens it (edge-to-edge on 35+).
                val insets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                WindowInsets.CONSUMED
            }
        }

        // The step-1 instruction names the keyboard exactly as it appears
        // in the system list — substituted here, never hardcoded in strings.
        findViewById<TextView>(R.id.setup_step1_instruction).text =
                getString(R.string.setup_step1_instruction,
                        getString(R.string.english_ime_name))

        findViewById<Button>(R.id.setup_step1_button).setOnClickListener {
            // Some stripped OEM/enterprise builds ship without the
            // input-method settings screen — never crash the first tap.
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.setup_error_no_settings,
                        Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.setup_step2_button).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
        }
        findViewById<Button>(R.id.setup_done_button).setOnClickListener {
            openSettingsAndFinish()
        }
    }

    /** The done button and the completed-setup forward share this: settings on top, no return here. */
    private fun openSettingsAndFinish() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        stopEnableWatcher()
        updateStepStates()
    }

    override fun onPause() {
        super.onPause()
        // Auto-return: leaving with step 1 pending means the user headed for
        // the system input-method settings — watch for the toggle there.
        if (!watchingForEnable && !isImeEnabled()) {
            watchingForEnable = true
            enableWatchTicks = 0
            handler.postDelayed(enableWatcher, ENABLE_WATCH_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        stopEnableWatcher()
        super.onDestroy()
    }

    private fun stopEnableWatcher() {
        watchingForEnable = false
        handler.removeCallbacks(enableWatcher)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStepStates()
    }

    /**
     * Step 1 — is this IME enabled in the system? The IMM read is guarded
     * like upstream's SettingsActivity did it: the binder call has thrown
     * on some OEM builds, and an exception here must not crash the
     * first-run screen — treat it as "not enabled".
     */
    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return try {
            SetupState.isImeEnabled(
                    imm.enabledInputMethodList.map { it.packageName }, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in check if input method is enabled", e)
            false
        }
    }

    /** Step 2 — is this IME the current one? See [SetupState.isImeCurrent]. */
    private fun isImeCurrent(): Boolean {
        val current = Settings.Secure.getString(
                contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return SetupState.isImeCurrent(current, packageName)
    }

    /**
     * Idempotent render of the three states: nothing done, step 1 done (step 2
     * becomes active), and both done in this instance (step cards and subtitle
     * hidden, done block with the try-it field shown). A completed setup this
     * instance did not watch finish forwards to the settings instead — the same
     * gate as in onCreate, kept for state flips between create and resume.
     */
    private fun updateStepStates() {
        val enabled = isImeEnabled()
        val current = enabled && isImeCurrent()
        val setupComplete = SetupState.isSetupComplete(enabled, current)
        if (!setupComplete) sawIncompleteSetup = true
        if (intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && SetupState.shouldForwardToSettings(setupComplete, sawIncompleteSetup)
                && !isFinishing) {
            openSettingsAndFinish()
            return
        }

        // The visual marks ("1"/"2"/"✓") mean nothing to TalkBack — each
        // status mark carries a spoken done/pending description instead.
        // Both marks are live regions (setup_activity.xml), and TextView
        // fires a content-change event even when the new text equals the
        // old one — while this method re-runs on every resume/focus gain.
        // Setting text only on a real change keeps the live region from
        // re-announcing an unchanged status.
        val step1Status = findViewById<TextView>(R.id.setup_step1_status)
        setTextIfChanged(step1Status,
                getString(if (enabled) R.string.setup_step_done_mark
                          else R.string.setup_step1_number))
        step1Status.contentDescription =
                getString(if (enabled) R.string.setup_step_status_done
                          else R.string.setup_step_status_pending)
        findViewById<Button>(R.id.setup_step1_button).isEnabled = !enabled

        val step2Status = findViewById<TextView>(R.id.setup_step2_status)
        setTextIfChanged(step2Status,
                getString(if (current) R.string.setup_step_done_mark
                          else R.string.setup_step2_number))
        step2Status.contentDescription =
                getString(if (current) R.string.setup_step_status_done
                          else R.string.setup_step_status_pending)
        // The dimming below is decorative; the locked state of step 2 is
        // conveyed non-visually by the button's disabled semantics.
        findViewById<Button>(R.id.setup_step2_button).isEnabled = enabled && !current
        findViewById<View>(R.id.setup_step2_card).alpha = if (enabled) 1f else 0.4f

        // A fully set-up keyboard needs no wizard: collapse the steps. The
        // done-block title stays the layout's celebratory default; this point
        // is reached only by an instance that watched the steps complete.
        findViewById<View>(R.id.setup_step1_card).visibility =
                if (setupComplete) View.GONE else View.VISIBLE
        findViewById<View>(R.id.setup_step2_card).visibility =
                if (setupComplete) View.GONE else View.VISIBLE
        findViewById<View>(R.id.setup_subtitle).visibility =
                if (setupComplete) View.GONE else View.VISIBLE

        findViewById<View>(R.id.setup_done_block).visibility =
                if (current) View.VISIBLE else View.GONE
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) {
            view.text = text
        }
    }
}
