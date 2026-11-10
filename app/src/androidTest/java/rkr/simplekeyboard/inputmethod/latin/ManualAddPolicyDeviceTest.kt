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

package rkr.simplekeyboard.inputmethod.latin

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.InputType
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import java.util.Locale
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalAdd
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalForget
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.suggestions.SuggestionStripView

/** Consumer-visible manual-add policy on the live IME and its real personal store. */
class ManualAddPolicyDeviceTest : InstrumentationTestCase() {

    private data class SavedBoolean(val present: Boolean, val value: Boolean)
    private data class LiveCell(val x: Float, val y: Float)

    private enum class ConfirmationChange { PAUSE, POSTAL }

    private lateinit var preferences: SharedPreferences
    private lateinit var savedPreferences: Map<String, SavedBoolean>
    private lateinit var imm: InputMethodManager
    private lateinit var normal: EditText
    private lateinit var postal: EditText
    private var startedActivity: Activity? = null
    private var oldSubtype: Subtype? = null
    private var wordCounter = 0
    private val testWords = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        preferences = PreferenceManagerCompat.getDeviceSharedPreferences(context)
        savedPreferences = preferenceKeys.associateWith { key ->
            SavedBoolean(preferences.contains(key), preferences.getBoolean(key, false))
        }
        preferences.edit()
            .putBoolean(Settings.PREF_AUTO_CAP, false)
            .putBoolean(Settings.PREF_TATAR_SUGGESTIONS, true)
            .putBoolean(Settings.PREF_TATAR_AUTOCORRECT, false)
            .putBoolean(Settings.PREF_STRIP_FOURTH_CELL, false)
            .putBoolean(Settings.PREF_PERSONAL_DICTIONARY, true)
            .putBoolean(Settings.PREF_INCOGNITO_MODE, false)
            .putBoolean(Settings.PREF_TATAR_SUGGESTIONS_OFFER_SPENT, true)
            .commit()
        imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        val activity = instrumentation.startActivitySync(
            Intent()
                .setClassName(context, SETUP_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        startedActivity = activity
        normal = activity.findViewById(R.id.setup_test_field)
        instrumentation.runOnMainSync {
            postal = EditText(activity)
            val parent = normal.parent as ViewGroup
            parent.addView(postal, parent.indexOfChild(normal) + 1, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        instrumentation.waitForIdleSync()

        val location = IntArray(2)
        normal.getLocationOnScreen(location)
        tap(location[0] + normal.width / 2f, location[1] + normal.height / 2f)
        assertTrue("the IME never accepted text", waitFor(10_000L) { imm.isAcceptingText })
        var shown = false
        for (attempt in 0 until 10) {
            instrumentation.runOnMainSync { imm.showSoftInput(normal, 0) }
            if (waitFor(2_000L) { keyboardIsShown() }) {
                shown = true
                break
            }
        }
        assertTrue("the keyboard view never came up", shown)

        val subtypes = RichInputMethodManager.getInstance()
        oldSubtype = subtypes.currentSubtype
        instrumentation.runOnMainSync { subtypes.setCurrentSubtype(Locale("tt", "RU")) }
        assertTrue("the Tatar keyboard never became active", waitFor(5_000L) {
            keyboardIsShown() && subtypes.currentSubtype.locale == PersonalSubtypes.TATAR_RU
        })
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { optionsDialog()?.dismiss() }
        val context = instrumentation.targetContext
        for (word in testWords) {
            if (PersonalForget.savedFormOf(context, PersonalSubtypes.TATAR_RU, word) != null) {
                PersonalForget.confirmForget(context, PersonalSubtypes.TATAR_RU, word)
                waitFor(2_000L) {
                    PersonalForget.savedFormOf(context, PersonalSubtypes.TATAR_RU, word) == null
                }
            }
        }
        oldSubtype?.let { subtype ->
            instrumentation.runOnMainSync {
                RichInputMethodManager.getInstance().setCurrentSubtype(subtype)
            }
        }
        startedActivity?.finish()
        val restore = preferences.edit()
        for ((key, saved) in savedPreferences) {
            if (saved.present) restore.putBoolean(key, saved.value) else restore.remove(key)
        }
        restore.commit()
        super.tearDown()
    }

    fun testManualAddPrivacyPolicyOnLiveIme() {
        assertInitialRejection(normal, false, true, R.string.personal_dictionary_learning_paused)
        assertInitialRejection(postal, true, false, R.string.personal_dictionary_add_postal_address)
        assertConfirmationRejection(ConfirmationChange.PAUSE)
        assertConfirmationRejection(ConfirmationChange.POSTAL)
        assertAllowedControl()
    }

    private fun assertInitialRejection(
        field: EditText,
        postalAddress: Boolean,
        pause: Boolean,
        messageId: Int,
    ) {
        val word = typeNewWord(field, postalAddress, pause)
        longPressSuggestion(word)
        awaitDialogMessage(messageId)
        assertWordStaysAbsent(word)
        dismissOptionsDialog()
    }

    private fun assertConfirmationRejection(change: ConfirmationChange) {
        val word = typeNewWord(normal, false, false)
        longPressSuggestion(word)
        awaitAddDialog()
        when (change) {
            ConfirmationChange.PAUSE -> {
                preferences.edit().putBoolean(Settings.PREF_INCOGNITO_MODE, true).commit()
                assertTrue("the add dialog disappeared when learning paused", waitFor(5_000L) {
                    currentDialogPositiveText() == getString(R.string.personal_dictionary_add_action)
                })
            }
            ConfirmationChange.POSTAL -> {
                instrumentation.runOnMainSync {
                    normal.inputType = POSTAL_INPUT_TYPE
                    imm.restartInput(normal)
                }
                assertTrue("the postal field did not restart under the add dialog", waitFor(5_000L) {
                    fieldIsReady(normal, true) &&
                        currentDialogPositiveText() ==
                        getString(R.string.personal_dictionary_add_action)
                })
            }
        }
        clickDialogPositive()
        awaitDialogMessage(
            if (change == ConfirmationChange.PAUSE) {
                R.string.personal_dictionary_learning_paused
            } else {
                R.string.personal_dictionary_add_postal_address
            },
        )
        assertWordStaysAbsent(word)
        dismissOptionsDialog()
    }

    private fun assertAllowedControl() {
        val word = typeNewWord(normal, false, false)
        longPressSuggestion(word)
        awaitAddDialog()
        clickDialogPositive()
        assertTrue("the allowed confirmation never saved the word", waitFor(5_000L) {
            PersonalForget.savedFormOf(
                instrumentation.targetContext,
                PersonalSubtypes.TATAR_RU,
                word,
            ) != null
        })
    }

    private fun typeNewWord(field: EditText, postalAddress: Boolean, pause: Boolean): String {
        preferences.edit().putBoolean(Settings.PREF_INCOGNITO_MODE, pause).commit()
        prepareField(field, postalAddress)
        val word = newTestWord()
        assertNotNull(PersonalAdd.addableNormalizedForm(PersonalSubtypes.TATAR_RU, word))
        assertNull(PersonalForget.savedFormOf(
            instrumentation.targetContext,
            PersonalSubtypes.TATAR_RU,
            word,
        ))
        for ((index, letter) in word.withIndex()) {
            tapLetter(letter)
            val prefix = word.substring(0, index + 1)
            assertTrue("the live keyboard did not type '$prefix'", waitFor(2_000L) {
                field.text.toString() == prefix
            })
        }
        return word
    }

    private fun newTestWord(): String {
        val alphabet = "гүзәл"
        var value = SystemClock.uptimeMillis() + wordCounter++
        val suffix = StringBuilder()
        repeat(8) {
            suffix.append(alphabet[(value % alphabet.length).toInt()])
            value /= alphabet.length
        }
        return "гүзәл$suffix".also(testWords::add)
    }

    private fun prepareField(field: EditText, postalAddress: Boolean) {
        instrumentation.runOnMainSync {
            field.inputType = if (postalAddress) POSTAL_INPUT_TYPE else NORMAL_INPUT_TYPE
            field.requestFocus()
            field.setText("")
            field.setSelection(0)
            imm.showSoftInput(field, 0)
            imm.restartInput(field)
        }
        assertTrue("the requested field never became active", waitFor(5_000L) {
            fieldIsReady(field, postalAddress)
        })
    }

    /** Must run on the main thread. */
    private fun fieldIsReady(field: EditText, postalAddress: Boolean): Boolean {
        val values = Settings.getInstance().current
        return imm.isActive(field) && keyboardIsShown() &&
            values?.mInputAttributes?.mIsPostalAddressField == postalAddress
    }

    /** Must run on the main thread. */
    private fun keyboardIsShown(): Boolean {
        val view = KeyboardSwitcher.getInstance().mainKeyboardView
        return view != null && view.isShown && view.keyboard != null
    }

    private fun tapLetter(letter: Char) {
        var x = 0f
        var y = 0f
        var found = false
        instrumentation.runOnMainSync {
            val view = KeyboardSwitcher.getInstance().mainKeyboardView
            val key = view?.keyboard?.sortedKeys?.firstOrNull {
                Character.isLetter(it.code) && Character.toLowerCase(it.code) == letter.code
            }
            if (view != null && key != null) {
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                x = origin[0] + view.paddingLeft + key.x + key.width / 2f
                y = origin[1] + view.paddingTop + key.y + key.height / 2f
                found = true
            }
        }
        assertTrue("no live key for '$letter'", found)
        tap(x, y)
    }

    private fun longPressSuggestion(word: String) {
        var cell: LiveCell? = null
        assertTrue("the typed word never reached the suggestion strip", waitFor(7_000L) {
            cell = suggestionCell(word)
            cell != null
        })
        val target = cell!!
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, target.x, target.y, downTime, downTime)
        Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 250L)
        inject(MotionEvent.ACTION_UP, target.x, target.y, downTime, SystemClock.uptimeMillis())
    }

    /** Must run on the main thread. */
    private fun suggestionCell(word: String): LiveCell? {
        val keyboard = KeyboardSwitcher.getInstance().mainKeyboardView
        val strip = (keyboard?.parent as? ViewGroup)
            ?.findViewById<SuggestionStripView>(R.id.suggestion_strip_view)
        if (strip == null || !strip.isShown || strip.width <= 0) return null
        for (cell in 0 until VISIBLE_CELLS) {
            if (strip.getSuggestion(cell)?.equals(word, ignoreCase = true) != true) continue
            val origin = IntArray(2)
            strip.getLocationOnScreen(origin)
            return LiveCell(
                origin[0] + strip.width * (cell + 0.5f) / VISIBLE_CELLS,
                origin[1] + strip.height / 2f,
            )
        }
        return null
    }

    private fun awaitAddDialog() {
        assertTrue("the long press did not offer manual addition", waitFor(5_000L) {
            currentDialogPositiveText() == getString(R.string.personal_dictionary_add_action)
        })
    }

    private fun awaitDialogMessage(messageId: Int) {
        val expected = getString(messageId)
        assertTrue("the expected policy explanation did not appear", waitFor(5_000L) {
            currentDialogMessage() == expected
        })
    }

    private fun clickDialogPositive() {
        instrumentation.runOnMainSync {
            val dialog = optionsDialog()
            assertNotNull(dialog)
            assertTrue(dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        }
    }

    private fun dismissOptionsDialog() {
        instrumentation.runOnMainSync { optionsDialog()?.dismiss() }
    }

    /** Must run on the main thread. */
    private fun currentDialogMessage(): String? {
        val dialog = optionsDialog() ?: return null
        if (!dialog.isShowing) return null
        return dialog.findViewById<TextView>(android.R.id.message)?.text?.toString()
    }

    /** Must run on the main thread. */
    private fun currentDialogPositiveText(): String? {
        val dialog = optionsDialog() ?: return null
        if (!dialog.isShowing) return null
        return dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString()
    }

    /** Must run on the main thread. */
    private fun optionsDialog(): AlertDialog? {
        val switcherField = KeyboardSwitcher::class.java.getDeclaredField("mLatinIME")
            .apply { isAccessible = true }
        val ime = switcherField.get(KeyboardSwitcher.getInstance()) as? LatinIME ?: return null
        val dialogField = LatinIME::class.java.getDeclaredField("mOptionsDialog")
            .apply { isAccessible = true }
        return dialogField.get(ime) as? AlertDialog
    }

    private fun assertWordStaysAbsent(word: String) {
        Thread.sleep(750)
        assertNull("a rejected manual add reached the personal store",
            PersonalForget.savedFormOf(instrumentation.targetContext, PersonalSubtypes.TATAR_RU, word))
    }

    private fun getString(id: Int): String = instrumentation.targetContext.getString(id)

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        Thread.sleep(60)
        inject(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
        Thread.sleep(100)
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val SETUP_ACTIVITY = "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity"
        const val VISIBLE_CELLS = 3
        val NORMAL_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_NORMAL
        val POSTAL_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS

        val preferenceKeys = listOf(
            Settings.PREF_AUTO_CAP,
            Settings.PREF_TATAR_SUGGESTIONS,
            Settings.PREF_TATAR_AUTOCORRECT,
            Settings.PREF_STRIP_FOURTH_CELL,
            Settings.PREF_PERSONAL_DICTIONARY,
            Settings.PREF_INCOGNITO_MODE,
            Settings.PREF_TATAR_SUGGESTIONS_OFFER_SPENT,
        )
    }
}
