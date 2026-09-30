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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet
import rkr.simplekeyboard.inputmethod.latin.AudioAndHapticFeedbackManager
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineReport
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPanelController
import rkr.simplekeyboard.inputmethod.latin.utils.AppLocale
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils

/**
 * View-based settings screens, built as iOS-style grouped cards from the row_link / row_switch /
 * row_value layouts without android.preference. One activity swaps pages inside one scaffold
 * ([R.layout.settings_screen]) with a manual back stack, so system back and the back chevron
 * behave like separate activities. Prefs are device-protected ([PreferenceManagerCompat]);
 * enterprise restrictions ([Settings.ACTIVE_RESTRICTIONS]) disable rows; theme-affecting toggles
 * rebuild the open keyboard live; content is rebuilt on every [onStart]. Backup is off for the
 * whole app (res/xml/data_extraction_rules.xml), so no backup is requested on changes.
 * [Screen.LANGUAGE_DETAIL] is the one parameterized screen: its locale lives in [detailLocale].
 * Row builders and some screens are `internal` extensions in `SettingsRows.kt`,
 * `SettingsLanguagesScreens.kt` and `SettingsKeyPressScreen.kt`.
 */
class SettingsHostActivity : Activity() {

    // internal, not private: the languages screens in SettingsLanguagesScreens.kt navigate by
    // these constants.
    internal enum class Screen(val titleRes: Int) {
        ROOT(R.string.english_ime_name),
        PREFERENCES(R.string.settings_screen_preferences),
        KEY_PRESS(R.string.settings_screen_key_press),
        APPEARANCE(R.string.settings_screen_appearance),
        LANGUAGES(R.string.keyboard_languages),
        // Title is the language display name, set dynamically in showScreen.
        LANGUAGE_DETAIL(0),
        PERSONAL_DICTIONARY(R.string.personal_dictionary),
        DATA_SOURCES(R.string.settings_screen_data_sources)
    }
    companion object {
        private const val TRANSITION_NONE = 0
        private const val TRANSITION_FORWARD = 1
        private const val TRANSITION_BACKWARD = -1
        /** Slide distance of a screen push/pop; 24dp reads as motion, not travel. */
        private const val SCREEN_TRANSITION_OFFSET_DP = 24f
        private const val SCREEN_TRANSITION_MS = 200L
        private val TAG = SettingsHostActivity::class.java.simpleName
        private const val STATE_SCREEN = "screen"
        private const val STATE_BACK_STACK = "back_stack"
        private const val STATE_DETAIL_LOCALE = "detail_locale"
        // internal, not private: read by the row builders in SettingsRows.kt.
        internal const val DISABLED_ALPHA = 0.4f
        internal const val PERCENTAGE_FLOAT = 100.0f
    }

    internal lateinit var prefs: SharedPreferences
    internal lateinit var richImm: RichInputMethodManager
    private lateinit var scrollView: ScrollView
    internal lateinit var contentView: LinearLayout
    private lateinit var titleView: TextView

    private val backStack = ArrayDeque<Screen>()
    /**
     * Direction of the navigation behind the next [showScreen]: set by [navigateTo] and
     * [onBackPressed], consumed by [playScreenTransition]. A rebuild without navigation (create,
     * restore, refresh after an edit) stays at [TRANSITION_NONE] and does not animate.
     */
    private var pendingTransition = TRANSITION_NONE
    internal var currentDialog: AlertDialog? = null
    private var currentScreen = Screen.ROOT
    /** Locale string of the language shown by [Screen.LANGUAGE_DETAIL]. */
    internal var detailLocale: String? = null
    internal var restrictionKeys: Set<String> = emptySet()

    /**
     * The personal-dictionary search text. Deliberately transient: it is NOT written to
     * [onSaveInstanceState], so it does not survive rotation and no fragment of a personal word
     * travels through Binder into `system_server`.
     */
    private var personalSearchQuery: String = ""

    /**
     * The quarantined copies found for each language, or null while not read yet (not "none").
     * The read runs on the personal-store worker, so the screen paints once without the card and
     * repaints when the answer arrives. Every finished mutation resets it to null. Holds two
     * numbers per language and no word (see `PersonalQuarantineReport`).
     */
    private var personalQuarantines: Map<String, PersonalQuarantineReport>? = null

    /**
     * The learned-pairs counterpart of [personalQuarantines], with the same lifecycle and reset
     * rule. The maps are separate because each store quarantines its file independently.
     */
    private var personalPairQuarantines: Map<String, PersonalQuarantineReport>? = null

    /** The learned-emoji counterpart of [personalQuarantines]; see [personalPairQuarantines]. */
    private var personalEmojiQuarantines: Map<String, PersonalQuarantineReport>? = null

    /**
     * Clears the keyboard layout cache when a layout-affecting pref changes. Everything else
     * reaches the live keyboard through the Settings singleton's own listener on the same file.
     */
    private val prefChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (Settings.PREF_SHOW_NUMBER_ROW == key
                    || Settings.PREF_SHOW_EMOJI_KEY == key
                    || Settings.PREF_SHOW_SPECIAL_CHARS == key) {
                KeyboardLayoutSet.onKeyboardThemeChanged()
            }
        }

    override fun attachBaseContext(newBase: Context) {
        // Tatar is the app's UI default (see AppLocale): Russian and Tatar systems
        // resolve on their own, everything else is wrapped into Tatar here.
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE for the whole activity, not per screen: the screens are swapped content in
        // one ScrollView, and toggling the flag during navigation causes flicker, surface
        // recreation and races with the recent-apps snapshot on OEM builds. Without it the saved
        // words would appear in the recent-apps thumbnail and in screenshots.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Turn off content capture for the whole window (public API since R), so the
            // platform's content-capture pipeline does not see the saved words on screen.
            window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
        setContentView(R.layout.settings_screen)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(R.id.settings_root).setOnApplyWindowInsetsListener { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                WindowInsets.CONSUMED
            }
        }

        prefs = PreferenceManagerCompat.getDeviceSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        // The keyboard may not be running when settings open from the
        // system list; init the singletons used here (see LatinIME#onCreate).
        AudioAndHapticFeedbackManager.init(this)
        RichInputMethodManager.init(this)
        richImm = RichInputMethodManager.getInstance()

        scrollView = findViewById(R.id.settings_scroll)
        contentView = findViewById(R.id.settings_content)
        titleView = findViewById(R.id.settings_title)
        findViewById<ImageButton>(R.id.settings_back).setOnClickListener { onBackPressed() }

        val screens = Screen.values()
        savedInstanceState?.getIntArray(STATE_BACK_STACK)?.forEach { ordinal ->
            if (ordinal in screens.indices) backStack.addLast(screens[ordinal])
        }
        detailLocale = savedInstanceState?.getString(STATE_DETAIL_LOCALE)
        val initial = savedInstanceState?.getInt(STATE_SCREEN, 0) ?: 0
        currentScreen = screens[initial.coerceIn(screens.indices)]
        // The content itself is built in onStart, which follows right after.
    }

    override fun onStart() {
        super.onStart()
        // Rebuild the visible screen every time the activity returns to the
        // foreground: the language list, enabled-layout summaries and restriction
        // state are re-read, so external changes are picked up.
        showScreen(currentScreen)
    }

    override fun onDestroy() {
        // Dismiss any open slider dialog: AlertDialog is not lifecycle-aware,
        // and leaving it attached across a configuration change leaks the
        // window (WindowLeaked). An unsaved slider value is discarded.
        currentDialog?.dismiss()
        currentDialog = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SCREEN, currentScreen.ordinal)
        outState.putIntArray(STATE_BACK_STACK, backStack.map { it.ordinal }.toIntArray())
        outState.putString(STATE_DETAIL_LOCALE, detailLocale)
    }

    override fun onBackPressed() {
        val previous = backStack.removeLastOrNull()
        if (previous != null) {
            pendingTransition = TRANSITION_BACKWARD
            showScreen(previous)
        } else {
            super.onBackPressed()
        }
    }

    internal fun navigateTo(screen: Screen) {
        backStack.addLast(currentScreen)
        pendingTransition = TRANSITION_FORWARD
        showScreen(screen)
    }

    internal fun showScreen(screen: Screen) {
        val detail = if (screen == Screen.LANGUAGE_DETAIL) detailLocale else null
        if (screen == Screen.LANGUAGE_DETAIL && detail == null) {
            // Defensive: a detail screen without its locale (unexpected
            // restore) falls back to the languages list.
            showScreen(Screen.LANGUAGES)
            return
        }
        detailLocale = detail
        currentScreen = screen
        restrictionKeys = prefs.getStringSet(Settings.ACTIVE_RESTRICTIONS, null) ?: emptySet()
        if (detail != null) {
            titleView.text = LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(detail)
        } else {
            titleView.setText(screen.titleRes)
        }
        // The large title is child 0 of the content column and stays.
        contentView.removeViews(1, contentView.childCount - 1)
        when (screen) {
            Screen.ROOT -> buildRootScreen()
            Screen.PREFERENCES -> buildPreferencesScreen()
            Screen.KEY_PRESS -> buildKeyPressScreen()
            Screen.APPEARANCE -> buildAppearanceScreen()
            Screen.LANGUAGES -> buildLanguagesScreen()
            Screen.LANGUAGE_DETAIL -> buildLanguageDetailScreen(detail!!)
            Screen.PERSONAL_DICTIONARY -> buildPersonalDictionaryScreen()
            Screen.DATA_SOURCES -> buildDataSourcesScreen()
        }
        scrollView.scrollTo(0, 0)
        playScreenTransition()
    }

    /**
     * As on iOS, a pushed screen slides in from the right and a popped one from the left: a short
     * slide + fade of the rebuilt content column. With system animations off
     * (`Settings.Global.ANIMATOR_DURATION_SCALE` = 0) the screen just appears; a rebuild without
     * a navigation direction never animates.
     */
    private fun playScreenTransition() {
        val direction = pendingTransition
        pendingTransition = TRANSITION_NONE
        if (direction == TRANSITION_NONE) return
        val scale = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        if (scale <= 0f) {
            // Leave the column exactly where a finished animation would have left it.
            contentView.translationX = 0f
            contentView.alpha = 1f
            return
        }
        val offset = SCREEN_TRANSITION_OFFSET_DP * resources.displayMetrics.density * direction
        contentView.animate().cancel()
        contentView.translationX = offset
        contentView.alpha = 0f
        contentView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(SCREEN_TRANSITION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ---------------------------------------------------------------------
    // Screens
    // ---------------------------------------------------------------------

    private fun buildRootScreen() {
        // Languages entry, disabled by the PREF_ENABLED_SUBTYPES restriction.
        addCard(listOf(
            linkRow(R.string.keyboard_languages, R.string.keyboard_languages_summary,
                    Settings.PREF_ENABLED_SUBTYPES) { navigateTo(Screen.LANGUAGES) }))
        addCard(listOf(
            linkRow(R.string.settings_screen_preferences) { navigateTo(Screen.PREFERENCES) }
                    .apply { id = R.id.row_link_preferences },
            linkRow(R.string.settings_screen_key_press) { navigateTo(Screen.KEY_PRESS) },
            linkRow(R.string.settings_screen_appearance) { navigateTo(Screen.APPEARANCE) }))
        addCard(listOf(
            linkRow(R.string.privacy_policy) { openUrl(getString(R.string.privacy_policy_url)) },
            linkRow(R.string.license) { openUrl(getString(R.string.license_url)) },
            linkRow(R.string.settings_screen_data_sources) { navigateTo(Screen.DATA_SOURCES) }))
    }

    /**
     * "Data sources": one row per collection the word lists come from, as their terms require.
     * Leipzig and Tatoeba are CC BY (attribution); OpenSubtitles asks for a link back to
     * opensubtitles.org ([R.string.data_sources_opensubtitles_url]). `NOTICE.txt` next to the
     * assets carries the same names in full. All three collections are in the shipped
     * dictionaries and bigram tables, so they share one "In this version" section.
     */
    private fun buildDataSourcesScreen() {
        addCard(listOf(textRow(getString(R.string.data_sources_intro))))

        addSectionHeader(getString(R.string.data_sources_in_app))
        addCard(listOf(
            linkRow(getString(R.string.data_sources_leipzig_title),
                    getString(R.string.data_sources_leipzig_summary)) {
                openUrl(getString(R.string.data_sources_leipzig_url))
            },
            linkRow(getString(R.string.data_sources_tatoeba_title),
                    getString(R.string.data_sources_tatoeba_summary)) {
                openUrl(getString(R.string.data_sources_tatoeba_url))
            },
            linkRow(getString(R.string.data_sources_opensubtitles_title),
                    getString(R.string.data_sources_opensubtitles_summary)) {
                openUrl(getString(R.string.data_sources_opensubtitles_url))
            }), spacedFromPrevious = false)
    }

    private fun buildPreferencesScreen() {
        val rows = ArrayList<View>()
        rows.add(switchRow(Settings.PREF_AUTO_CAP, true,
                R.string.auto_cap, R.string.auto_cap_summary))
        rows.add(switchRow(Settings.PREF_SHOW_SPECIAL_CHARS, true,
                R.string.show_special_chars, R.string.show_special_chars_summary))
        var imeSwitchRow: View? = null
        rows.add(switchRow(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true,
                R.string.show_language_switch_key,
                R.string.show_language_switch_key_summary) { checked ->
            imeSwitchRow?.let {
                setRowEnabled(it, checked && !isRestricted(Settings.PREF_ENABLE_IME_SWITCH))
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            rows.add(switchRow(Settings.PREF_USE_ON_SCREEN, false,
                    R.string.pref_use_on_screen, R.string.pref_use_on_screen_summary))
        }
        val imeRow = switchRow(Settings.PREF_ENABLE_IME_SWITCH, false,
                R.string.pref_enable_ime_switch, R.string.pref_enable_ime_switch_summary)
        imeSwitchRow = imeRow
        rows.add(imeRow)
        rows.add(switchRow(Settings.PREF_SPACE_SWIPE, true,
                R.string.space_swipe, R.string.space_swipe_summary))
        rows.add(switchRow(Settings.PREF_DELETE_SWIPE, false,
                R.string.delete_swipe, R.string.delete_swipe_summary))
        var personalDictionaryRow: View? = null
        var incognitoRow: View? = null
        var autocorrectRow: View? = null
        var emojiSuggestRow: View? = null
        var glideRow: View? = null
        rows.add(switchRow(Settings.PREF_TATAR_SUGGESTIONS, false,
                R.string.tatar_suggestions, R.string.tatar_suggestions_summary) { checked ->
            // Rows disabled here get no disabledReason, so until the screen is rebuilt they
            // ignore taps instead of explaining why they are dimmed.
            personalDictionaryRow?.let {
                setRowEnabled(it, checked && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY))
            }
            incognitoRow?.let {
                setRowEnabled(it, checked && !isRestricted(Settings.PREF_INCOGNITO_MODE))
            }
            autocorrectRow?.let {
                setRowEnabled(it, checked && !isRestricted(Settings.PREF_TATAR_AUTOCORRECT))
            }
            emojiSuggestRow?.let {
                setRowEnabled(it, checked && !isRestricted(Settings.PREF_EMOJI_SUGGESTIONS))
            }
            glideRow?.let {
                // Glide typing does not depend on the master switch; only the MDM restriction
                // disables the row.
                setRowEnabled(it, !isRestricted(Settings.PREF_GLIDE_TYPING))
            }
        }.apply { id = R.id.row_switch_tatar_suggestions })
        // The personal dictionary depends on the suggestion strip: without suggestions a saved
        // word has nowhere to appear, so the row follows the switch above it.
        val personalRow = switchRow(Settings.PREF_PERSONAL_DICTIONARY, false,
                R.string.personal_dictionary, R.string.personal_dictionary_summary)
        personalDictionaryRow = personalRow
        rows.add(personalRow)
        // Pause learning (incognito) pauses learning that only runs while suggestions are on, so
        // the row follows the same switch. One switch pauses all personal learning at once. The
        // key is not declared in app_restrictions.xml, so isRestricted below never fires; the
        // check keeps the row consistent with its siblings.
        val incognitoSwitch = switchRow(Settings.PREF_INCOGNITO_MODE, false,
                R.string.incognito_mode, R.string.incognito_mode_summary)
        incognitoRow = incognitoSwitch
        rows.add(incognitoSwitch)
        // Autocorrection depends on suggestions for a different reason: it takes its candidate from
        // the same lookup that feeds the strip, so with suggestions off there is nothing to correct
        // from. It has its own switch because a suggestion only offers, while a correction changes
        // what is already typed.
        val autocorrectSwitch = switchRow(Settings.PREF_TATAR_AUTOCORRECT, false,
                R.string.tatar_autocorrect, R.string.tatar_autocorrect_summary)
        autocorrectRow = autocorrectSwitch
        rows.add(autocorrectSwitch)
        // Emoji suggestions depend on the suggestions switch because the emoji cell lives in the
        // same strip; they have their own switch because emoji among the words are a matter of
        // taste. The default matches Settings.readEmojiSuggestionsEnabled (on); a user-set value
        // always wins.
        val emojiSuggestSwitch = switchRow(Settings.PREF_EMOJI_SUGGESTIONS, true,
                R.string.emoji_suggestions, R.string.emoji_suggestions_summary)
        emojiSuggestRow = emojiSuggestSwitch
        rows.add(emojiSuggestSwitch)
        // Glide typing has its own row because tapping or sliding is the user's habit, not a
        // property of the words. It does not depend on the suggestions switch: the lift-commit is
        // typing, not a suggestion, and with suggestions off the strip just shows nothing. The
        // default matches Settings.readGlideTypingEnabled (on); a user-set value always wins.
        val glideSwitch = switchRow(Settings.PREF_GLIDE_TYPING, true,
                R.string.glide_typing, R.string.glide_typing_summary)
        glideRow = glideSwitch
        rows.add(glideSwitch)
        addCard(rows)
        // The IME-switch row depends on the language switch key.
        setRowEnabled(imeRow,
                prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true)
                        && !isRestricted(Settings.PREF_ENABLE_IME_SWITCH),
                disabledReason(isRestricted(Settings.PREF_ENABLE_IME_SWITCH),
                        R.string.row_needs_language_switch))
        setRowEnabled(personalRow,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY),
                disabledReason(isRestricted(Settings.PREF_PERSONAL_DICTIONARY),
                        R.string.row_needs_suggestions))
        setRowEnabled(incognitoSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_INCOGNITO_MODE),
                disabledReason(isRestricted(Settings.PREF_INCOGNITO_MODE),
                        R.string.row_needs_suggestions))
        setRowEnabled(autocorrectSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_TATAR_AUTOCORRECT),
                disabledReason(isRestricted(Settings.PREF_TATAR_AUTOCORRECT),
                        R.string.row_needs_suggestions))
        setRowEnabled(emojiSuggestSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_EMOJI_SUGGESTIONS),
                disabledReason(isRestricted(Settings.PREF_EMOJI_SUGGESTIONS),
                        R.string.row_needs_suggestions))
        setRowEnabled(glideSwitch,
                !isRestricted(Settings.PREF_GLIDE_TYPING))
        // Reachable whatever the toggles say: erasing what was already saved must always be
        // possible, so the entry never depends on the switch above it.
        addCard(listOf(linkRow(R.string.personal_dictionary_screen) {
            navigateTo(Screen.PERSONAL_DICTIONARY)
        }))
        // A data action, not an appearance toggle: its own card at the end of Preferences, next to
        // the Tatar-suggestions switch. Erasing recent emoji is a confirmed, one-way action; it does
        // not belong on the Appearance screen where the emoji-key toggle lives.
        addCard(listOf(actionRow(R.string.clear_recent_emoji) {
            showClearRecentEmojiDialog()
        }))
    }

    /**
     * The "Personal dictionary" screen: saved words, learned word pairs and learned emoji of every
     * language, grouped by language, with a search field, an "Add word…" row, usage counts, a
     * delete action per row, a per-language "Clear all" per store and a global "Erase all".
     *
     * Fully usable with the setting off, so saved content can always be erased; only adding
     * follows the setting (with the personal dictionary off no file is created). The search text
     * is not saved on rotation (see [personalSearchQuery]). Stores are read only through the
     * published snapshot of their process-wide owner (file work runs on the personal-store
     * worker); an unreadable store (device locked, file quarantined) publishes an empty snapshot,
     * so the screen shows the empty state and the quarantine card explains why.
     */
    private fun buildPersonalDictionaryScreen() {
        val controller = PersonalDictionaryScreenController(this)
        val pairController = PersonalBigramScreenController(this)
        val emojiController = PersonalEmojiScreenController(this)
        val subtypeIds = personalSubtypeIds()
        val content = PersonalDictionaryScreenModel.build(
                controller.sections(subtypeIds), pairController.sections(subtypeIds),
                personalSearchQuery, emojiController.sections(subtypeIds))

        // The learning pause is shown where the learned content is managed: a small note, not a
        // dialog, because the user asked for this state.
        if (Settings.readIncognitoModeEnabled(prefs)) {
            addCard(listOf(textRow(getString(R.string.personal_dictionary_learning_paused))))
        }

        addCard(listOf(
                textInputRow(R.string.personal_dictionary_search_hint, personalSearchQuery) { text ->
                    // Filtering happens before any row View exists: the whole screen is simply
                    // rebuilt from the model with the new query.
                    personalSearchQuery = text
                    showScreen(Screen.PERSONAL_DICTIONARY)
                }))

        val addRow = actionRow(R.string.personal_dictionary_add) {
            showAddPersonalWordDialog(controller, subtypeIds)
        }
        addCard(listOf(addRow))
        setRowEnabled(addRow, Settings.readPersonalDictionaryEnabled(prefs)
                && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY))

        addPersonalQuarantineCards(controller, subtypeIds)
        addPersonalPairQuarantineCards(pairController, subtypeIds)
        addPersonalEmojiQuarantineCards(emojiController, subtypeIds)

        if (content.totalCount == 0) {
            // Three states: no search matches, nothing saved with the personal dictionary on, and
            // nothing saved with it off. Only the last one points to the switch.
            val emptyMessage = when {
                personalSearchQuery.isNotEmpty() -> R.string.personal_dictionary_no_matches
                Settings.readPersonalDictionaryEnabled(prefs) ->
                    R.string.personal_dictionary_empty_ready
                else -> R.string.personal_dictionary_empty
            }
            addCard(listOf(inflateRow(R.layout.row_link, getString(emptyMessage), null).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            }))
        }

        for (section in content.sections) {
            addSectionHeader(
                    LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(section.subtypeId))
            // Cards per language, one per store, each headed by its true saved count (even when
            // the list is capped) and closed by its own "clear all".
            if (section.wordRows.isNotEmpty()) {
                val rows = ArrayList<View>()
                rows.add(textRow(resources.getQuantityString(
                        R.plurals.personal_dictionary_words_count,
                        section.wordCount, section.wordCount)))
                rows.addAll(section.wordRows.map { row ->
                    usageRow(row.rawForm, row.usageCount) {
                        showForgetPersonalWordDialog(controller, row)
                    }
                })
                rows.add(actionRow(R.string.personal_dictionary_clear_words) {
                    showClearPersonalWordsDialog(controller, section.subtypeId)
                })
                addCard(rows, spacedFromPrevious = false)
            }
            if (section.pairRows.isNotEmpty()) {
                val rows = ArrayList<View>()
                rows.add(textRow(resources.getQuantityString(
                        R.plurals.personal_dictionary_pairs_count,
                        section.pairCount, section.pairCount)))
                rows.addAll(section.pairRows.map { row ->
                    usageRow(row.contextForm + " → " + row.successorRawForm, row.usageCount) {
                        showForgetPersonalPairDialog(pairController, row)
                    }
                })
                rows.add(actionRow(R.string.personal_dictionary_clear_pairs) {
                    showClearPersonalPairsDialog(pairController, section.subtypeId)
                })
                addCard(rows, spacedFromPrevious = false)
            }
            if (section.emojiRows.isNotEmpty()) {
                val rows = ArrayList<View>()
                rows.add(textRow(resources.getQuantityString(
                        R.plurals.personal_emoji_count,
                        section.emojiCount, section.emojiCount)))
                rows.addAll(section.emojiRows.map { row ->
                    usageRow(row.word + " → " + row.emoji, row.usageCount) {
                        showForgetPersonalEmojiDialog(emojiController, row)
                    }
                })
                rows.add(actionRow(R.string.personal_emoji_clear) {
                    showClearPersonalEmojiDialog(emojiController, section.subtypeId)
                })
                addCard(rows, spacedFromPrevious = false)
            }
        }

        if (content.isTruncated) {
            // A capped list says so; otherwise it would read as everything that was saved.
            addCard(listOf(inflateRow(R.layout.row_link,
                    getString(R.string.personal_dictionary_shown_of_total,
                            content.shownCount, content.totalCount), null).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            }))
        }

        if (content.totalCount > 0 || personalSearchQuery.isNotEmpty()) {
            addCard(listOf(actionRow(R.string.personal_dictionary_erase_all) {
                showErasePersonalDictionaryDialog(controller, pairController, emojiController,
                        subtypeIds)
            }))
        }
    }

    /**
     * One saved-content row of the personal screen: the word or the pair as the title, the usage
     * count and the delete affordance as the summary. The word "Delete" tells the user the row is
     * tappable.
     */
    private fun usageRow(title: String, usageCount: Int, onClick: () -> Unit): View =
        inflateRow(R.layout.row_link, title,
                resources.getQuantityString(R.plurals.personal_dictionary_usage_count,
                        usageCount, usageCount) +
                        " · " + getString(R.string.personal_dictionary_delete)).also { view ->
            view.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            view.setOnClickListener { onClick() }
        }

    /**
     * Cards for personal-dictionary files that could not be read and were kept as a quarantined
     * copy. One card per language: how many words could be read and, if part of the copy is
     * damaged, that the rest is lost, so a partial restore never looks complete. Two actions, both
     * started by the user: restore the readable words and delete the copy. They are separate
     * because restoring does not delete the unreadable part. A copy that yielded no words still
     * gets a card, so the user can delete it.
     */
    private fun addPersonalQuarantineCards(
            controller: PersonalDictionaryScreenController, subtypeIds: List<String>) {
        val reports = personalQuarantines
        if (reports == null) {
            // Not read yet. The read is file work and runs on the store's worker; the screen
            // repaints when it answers, like after every mutation.
            controller.quarantines(subtypeIds) { found ->
                if (isFinishing || isDestroyed) return@quarantines
                personalQuarantines = found
                if (currentScreen == Screen.PERSONAL_DICTIONARY) {
                    showScreen(Screen.PERSONAL_DICTIONARY)
                }
            }
            return
        }
        // In the order the languages are listed, not the order the worker happened to answer in.
        for (subtypeId in subtypeIds) {
            val report = reports[subtypeId] ?: continue
            // Plurals, not a bare %d, so the count agrees with its noun in every language.
            val summary = when {
                report.wordCount == 0 -> getString(R.string.personal_dictionary_quarantine_none)
                report.readToEnd -> resources.getQuantityString(
                        R.plurals.personal_dictionary_quarantine_whole,
                        report.wordCount, report.wordCount)
                else -> resources.getQuantityString(
                        R.plurals.personal_dictionary_quarantine_partial,
                        report.wordCount, report.wordCount)
            }
            addSectionHeader(LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId))
            val rows = ArrayList<View>()
            rows.add(inflateRow(R.layout.row_link,
                    getString(R.string.personal_dictionary_quarantine_title), summary).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            })
            if (report.wordCount > 0) {
                rows.add(actionRow(R.string.personal_dictionary_quarantine_restore) {
                    controller.restoreQuarantine(subtypeId) { restored ->
                        personalQuarantines = null
                        afterPersonalMutation(restored,
                                R.string.personal_dictionary_quarantine_restore_failed)
                    }
                })
            }
            rows.add(actionRow(R.string.personal_dictionary_quarantine_discard) {
                showDiscardPersonalQuarantineDialog(controller, subtypeId)
            })
            addCard(rows)
        }
    }

    private fun showDiscardPersonalQuarantineDialog(
            controller: PersonalDictionaryScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_quarantine_discard)
                .setMessage(R.string.personal_dictionary_quarantine_discard_confirm)
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.discardQuarantine(subtypeId) { discarded ->
                        personalQuarantines = null
                        afterPersonalMutation(discarded,
                                R.string.personal_dictionary_quarantine_discard_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /**
     * The learned-pairs counterpart of [addPersonalQuarantineCards], with the same rules: count and
     * damage stated together, restore and delete as separate user actions, and a card even for a
     * copy that yielded nothing. The read runs on the store's worker.
     */
    private fun addPersonalPairQuarantineCards(
            controller: PersonalBigramScreenController, subtypeIds: List<String>) {
        val reports = personalPairQuarantines
        if (reports == null) {
            controller.quarantines(subtypeIds) { found ->
                if (isFinishing || isDestroyed) return@quarantines
                personalPairQuarantines = found
                if (currentScreen == Screen.PERSONAL_DICTIONARY) {
                    showScreen(Screen.PERSONAL_DICTIONARY)
                }
            }
            return
        }
        // In the order the languages are listed, not the order the worker happened to answer in.
        for (subtypeId in subtypeIds) {
            val report = reports[subtypeId] ?: continue
            val summary = when {
                report.wordCount == 0 -> getString(R.string.personal_bigrams_quarantine_none)
                report.readToEnd -> resources.getQuantityString(
                        R.plurals.personal_bigrams_quarantine_whole,
                        report.wordCount, report.wordCount)
                else -> resources.getQuantityString(
                        R.plurals.personal_bigrams_quarantine_partial,
                        report.wordCount, report.wordCount)
            }
            addSectionHeader(LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId))
            val rows = ArrayList<View>()
            rows.add(inflateRow(R.layout.row_link,
                    getString(R.string.personal_bigrams_quarantine_title), summary).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            })
            if (report.wordCount > 0) {
                rows.add(actionRow(R.string.personal_bigrams_quarantine_restore) {
                    controller.restoreQuarantine(subtypeId) { restored ->
                        personalPairQuarantines = null
                        afterPersonalMutation(restored,
                                R.string.personal_bigrams_quarantine_restore_failed)
                    }
                })
            }
            rows.add(actionRow(R.string.personal_bigrams_quarantine_discard) {
                showDiscardPersonalPairQuarantineDialog(controller, subtypeId)
            })
            addCard(rows)
        }
    }

    private fun showDiscardPersonalPairQuarantineDialog(
            controller: PersonalBigramScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_bigrams_quarantine_discard)
                .setMessage(R.string.personal_bigrams_quarantine_discard_confirm)
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.discardQuarantine(subtypeId) { discarded ->
                        personalPairQuarantines = null
                        afterPersonalMutation(discarded,
                                R.string.personal_bigrams_quarantine_discard_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /**
     * The learned-emoji counterpart of [addPersonalQuarantineCards], with the same rules as
     * [addPersonalPairQuarantineCards].
     */
    private fun addPersonalEmojiQuarantineCards(
            controller: PersonalEmojiScreenController, subtypeIds: List<String>) {
        val reports = personalEmojiQuarantines
        if (reports == null) {
            controller.quarantines(subtypeIds) { found ->
                if (isFinishing || isDestroyed) return@quarantines
                personalEmojiQuarantines = found
                if (currentScreen == Screen.PERSONAL_DICTIONARY) {
                    showScreen(Screen.PERSONAL_DICTIONARY)
                }
            }
            return
        }
        // In the order the languages are listed, not the order the worker happened to answer in.
        for (subtypeId in subtypeIds) {
            val report = reports[subtypeId] ?: continue
            val summary = when {
                report.wordCount == 0 -> getString(R.string.personal_emoji_quarantine_none)
                report.readToEnd -> resources.getQuantityString(
                        R.plurals.personal_emoji_quarantine_whole,
                        report.wordCount, report.wordCount)
                else -> resources.getQuantityString(
                        R.plurals.personal_emoji_quarantine_partial,
                        report.wordCount, report.wordCount)
            }
            addSectionHeader(LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId))
            val rows = ArrayList<View>()
            rows.add(inflateRow(R.layout.row_link,
                    getString(R.string.personal_emoji_quarantine_title), summary).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            })
            if (report.wordCount > 0) {
                rows.add(actionRow(R.string.personal_emoji_quarantine_restore) {
                    controller.restoreQuarantine(subtypeId) { restored ->
                        personalEmojiQuarantines = null
                        afterPersonalMutation(restored,
                                R.string.personal_emoji_quarantine_restore_failed)
                    }
                })
            }
            rows.add(actionRow(R.string.personal_emoji_quarantine_discard) {
                showDiscardPersonalEmojiQuarantineDialog(controller, subtypeId)
            })
            addCard(rows)
        }
    }

    private fun showDiscardPersonalEmojiQuarantineDialog(
            controller: PersonalEmojiScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_emoji_quarantine_discard)
                .setMessage(R.string.personal_emoji_quarantine_discard_confirm)
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.discardQuarantine(subtypeId) { discarded ->
                        personalEmojiQuarantines = null
                        afterPersonalMutation(discarded,
                                R.string.personal_emoji_quarantine_discard_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /** Subtypes whose words the screen shows: every enabled one, in the order the system lists them. */
    private fun personalSubtypeIds(): List<String> =
            richImm.getEnabledSubtypes(true).map { it.locale }.distinct()

    /**
     * The store a hand-added word goes into: the current keyboard language if it has a personal
     * dictionary, otherwise the first enabled subtype that does. The screen lists every language
     * separately, so a wrong guess stays visible and fixable.
     */
    private fun targetSubtypeForAddedWord(subtypeIds: List<String>): String? {
        val current = richImm.currentSubtype?.locale
        if (current != null && PersonalSubtypes.alphabetFor(current) != null) return current
        return subtypeIds.firstOrNull { PersonalSubtypes.alphabetFor(it) != null }
    }

    private fun showAddPersonalWordDialog(
            controller: PersonalDictionaryScreenController, subtypeIds: List<String>) {
        val field = layoutInflater.inflate(R.layout.row_text_input, contentView, false) as EditText
        applyPrivateInputFlags(field)
        field.setHint(R.string.personal_dictionary_add_hint)
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_add)
                .setView(field)
                .setPositiveButton(R.string.personal_dictionary_add_action) { _, _ ->
                    val subtypeId = targetSubtypeForAddedWord(subtypeIds)
                    val accepted = subtypeId != null
                            && controller.addWord(subtypeId, field.text.toString()) { saved ->
                                afterPersonalMutation(saved,
                                        R.string.personal_dictionary_save_failed)
                            }
                    if (!accepted) {
                        // The message names the alphabet of the store the word was meant for:
                        // the same screen adds to the Russian dictionary when the current
                        // subtype is Russian, and "use Tatar letters" is simply wrong there.
                        val messageRes = if (subtypeId == PersonalSubtypes.RUSSIAN)
                                R.string.personal_dictionary_add_rejected_ru
                            else R.string.personal_dictionary_add_rejected
                        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
                        showScreen(Screen.PERSONAL_DICTIONARY)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    // The field takes a personal word by hand: the dialog's own window needs
                    // FLAG_SECURE, because the activity-wide one does not cover dialog windows.
                    DialogUtils.securePersonalContent(dialog)
                    dialog.show()
                }
    }

    private fun showForgetPersonalWordDialog(
            controller: PersonalDictionaryScreenController, row: PersonalWordRow) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.personal_dictionary_forget_title, row.rawForm))
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.removeWord(row.subtypeId, row.normalizedForm) { removed ->
                        afterPersonalMutation(removed,
                                R.string.personal_dictionary_delete_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    // The title names the saved word itself, so the dialog window is secured.
                    DialogUtils.securePersonalContent(dialog)
                    dialog.show()
                }
    }

    /**
     * The learned-pairs counterpart of [showForgetPersonalWordDialog]: the title shows the pair the
     * way the row does ("A → B"), so the confirmation names exactly what is removed. The deletion
     * goes through the store's `forget`, which purges the quarantine copy with it: a forgotten
     * pair is never resurrected by a later restore.
     */
    private fun showForgetPersonalPairDialog(
            controller: PersonalBigramScreenController, row: PersonalPairRow) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.personal_dictionary_pair_forget_title,
                        row.contextForm, row.successorRawForm))
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.removePair(row.subtypeId, row.contextForm,
                            row.successorNormalizedForm) { removed ->
                        afterPersonalMutation(removed,
                                R.string.personal_dictionary_pair_delete_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    // The title names the saved pair itself, so the dialog window is secured.
                    DialogUtils.securePersonalContent(dialog)
                    dialog.show()
                }
    }

    /**
     * The learned-emoji counterpart of [showForgetPersonalWordDialog]: the title shows the entry the
     * way the row does ("word → emoji"), so the confirmation names exactly what is removed. The
     * deletion goes through the store's `forget`, which purges the quarantine copy with it: a
     * forgotten entry is never resurrected by a later restore.
     */
    private fun showForgetPersonalEmojiDialog(
            controller: PersonalEmojiScreenController, row: PersonalEmojiRow) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.personal_emoji_forget_title,
                        row.word, row.emoji))
                .setPositiveButton(R.string.personal_dictionary_delete) { _, _ ->
                    controller.removeEntry(row.subtypeId, row.word, row.emoji) { removed ->
                        afterPersonalMutation(removed,
                                R.string.personal_emoji_delete_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    // The title names the saved word and emoji, so the dialog window is secured.
                    DialogUtils.securePersonalContent(dialog)
                    dialog.show()
                }
    }

    /**
     * Per-language "Clear all words": confirmed, then routed through the store's `clearAll`,
     * which also takes the pending counters, the salt and the quarantine copy of that language —
     * so the card above is re-read rather than repainted from an answer that is now out of date.
     */
    private fun showClearPersonalWordsDialog(
            controller: PersonalDictionaryScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_clear_words)
                .setMessage(getString(R.string.personal_dictionary_clear_words_confirm,
                        LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId)))
                .setPositiveButton(R.string.personal_dictionary_erase_action) { _, _ ->
                    controller.clearWords(subtypeId) { cleared ->
                        personalQuarantines = null
                        afterPersonalMutation(cleared,
                                R.string.personal_dictionary_erase_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /** The learned-pairs counterpart of [showClearPersonalWordsDialog]. */
    private fun showClearPersonalPairsDialog(
            controller: PersonalBigramScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_clear_pairs)
                .setMessage(getString(R.string.personal_dictionary_clear_pairs_confirm,
                        LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId)))
                .setPositiveButton(R.string.personal_dictionary_erase_action) { _, _ ->
                    controller.clearPairs(subtypeId) { cleared ->
                        personalPairQuarantines = null
                        afterPersonalMutation(cleared,
                                R.string.personal_dictionary_erase_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /** The learned-emoji counterpart of [showClearPersonalWordsDialog]. */
    private fun showClearPersonalEmojiDialog(
            controller: PersonalEmojiScreenController, subtypeId: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_emoji_clear)
                .setMessage(getString(R.string.personal_emoji_clear_confirm,
                        LocaleResourceUtils.getLocaleDisplayNameInSystemLocale(subtypeId)))
                .setPositiveButton(R.string.personal_dictionary_erase_action) { _, _ ->
                    controller.clearEmoji(subtypeId) { cleared ->
                        personalEmojiQuarantines = null
                        afterPersonalMutation(cleared,
                                R.string.personal_dictionary_erase_failed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    private fun showErasePersonalDictionaryDialog(
            controller: PersonalDictionaryScreenController,
            pairController: PersonalBigramScreenController,
            emojiController: PersonalEmojiScreenController, subtypeIds: List<String>) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_erase_all)
                .setMessage(R.string.personal_dictionary_erase_confirm)
                .setPositiveButton(R.string.personal_dictionary_erase_action) { _, _ ->
                    // "Erase all" covers all three stores, so no learned pair or emoji keeps
                    // appearing afterwards. The stores answer independently; success is reported
                    // only when every file of every language is gone.
                    controller.eraseAll(subtypeIds) { wordsErased ->
                        pairController.eraseAll(subtypeIds) { pairsErased ->
                            emojiController.eraseAll(subtypeIds) { emojiErased ->
                                // Erasing takes the copies with it, so all three cards are re-read
                                // rather than repainted from an answer that is now out of date.
                                personalQuarantines = null
                                personalPairQuarantines = null
                                personalEmojiQuarantines = null
                                afterPersonalMutation(wordsErased && pairsErased && emojiErased,
                                        R.string.personal_dictionary_erase_failed)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    /**
     * Reacts to a finished personal-store mutation. The list is repainted only now, because it
     * reads the published snapshot, which does not exist yet when the dialog closes. A failed
     * mutation shows a message (the subsystem does not log); it names no word, file or cause.
     */
    private fun afterPersonalMutation(succeeded: Boolean, failureMessageRes: Int) {
        if (isFinishing || isDestroyed) return
        if (!succeeded) {
            Toast.makeText(this, failureMessageRes, Toast.LENGTH_LONG).show()
        }
        if (currentScreen == Screen.PERSONAL_DICTIONARY) {
            showScreen(Screen.PERSONAL_DICTIONARY)
        }
    }

    /**
     * The three flags both text fields of this screen carry (search and "Add word…"). Without them
     * the words the user puts into the personal dictionary could be learned or synced by another
     * keyboard, or picked up by an autofill service.
     */
    private fun applyPrivateInputFlags(field: EditText) {
        field.imeOptions = field.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        field.inputType = field.inputType or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    private fun textInputRow(hintRes: Int, initialText: String,
                             onTextCommitted: (String) -> Unit): View {
        val field = layoutInflater.inflate(R.layout.row_text_input, contentView, false) as EditText
        applyPrivateInputFlags(field)
        field.setHint(hintRes)
        field.setText(initialText)
        field.imeOptions = field.imeOptions or EditorInfo.IME_ACTION_SEARCH
        field.setOnEditorActionListener { view, _, _ ->
            onTextCommitted(view.text.toString())
            true
        }
        return field
    }

    // The "Key press" screen and the three seek-bar value proxies live in
    // SettingsKeyPressScreen.kt.

    private fun buildAppearanceScreen() {
        addCard(listOf(
            switchRow(Settings.PREF_SHOW_NUMBER_ROW, false,
                    R.string.show_number_row, R.string.show_number_row_summary),
            switchRow(Settings.PREF_SHOW_EMOJI_KEY, true,
                    R.string.show_emoji_key, R.string.show_emoji_key_summary),
            keyboardHeightRow(),
            emojiPanelHeightRow(),
            valueRow(Settings.PREF_BOTTOM_OFFSET_PORTRAIT,
                    R.string.prefs_bottom_offset_portrait_settings,
                    resources.getInteger(R.integer.config_min_bottom_offset_portrait),
                    resources.getInteger(R.integer.config_max_bottom_offset_portrait),
                    resources.getInteger(R.integer.config_bottom_offset_step),
                    bottomOffsetProxy())))
    }

    // ---------------------------------------------------------------------
    // The languages screens live in SettingsLanguagesScreens.kt.
    // ---------------------------------------------------------------------

    /**
     * Confirmation dialog for "Clear recent emoji", kept in [currentDialog] and torn down in
     * [onDestroy] like the other dialogs, so a rotation never leaks the window. The erase goes to
     * [EmojiPanelController.clearRecents], which updates the live keyboard when one exists in this
     * process and otherwise replaces the medium directly, off the UI thread. This screen never
     * reads the recents content.
     */
    private fun showClearRecentEmojiDialog() {
        currentDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.clear_recent_emoji)
                .setMessage(R.string.clear_recent_emoji_confirm)
                .setPositiveButton(R.string.clear_recent_emoji_action) { _, _ ->
                    EmojiPanelController.clearRecents(this)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        DialogUtils.filterObscuredTouches(dialog)
        dialog.show()
        currentDialog = dialog
    }

    // ---------------------------------------------------------------------
    // The generic row builders live in SettingsRows.kt.
    // ---------------------------------------------------------------------

    /**
     * "Keyboard height" as three named presets in a row_value row. The one-tap picker writes the
     * preset's float into [Settings.PREF_KEYBOARD_HEIGHT], the pref the older seek bar wrote, so
     * the keyboard applies it on its next rebuild. A stored value that matches no preset keeps
     * applying and is shown as a plain percent until the user picks a preset.
     */
    private fun keyboardHeightRow(): View {
        val row = inflateRow(R.layout.row_value, R.string.prefs_keyboard_height_settings, 0)
        val valueView = row.findViewById<TextView>(R.id.row_value)
        valueView.text = keyboardHeightValueText()
        row.setOnClickListener {
            showKeyboardHeightDialog {
                valueView.text = keyboardHeightValueText()
            }
        }
        if (isRestricted(Settings.PREF_KEYBOARD_HEIGHT)) {
            setRowEnabled(row, false)
        }
        return row
    }

    /**
     * "Emoji panel height": three named presets stored as one float scale of the keyboard box in
     * [Settings.PREF_EMOJI_PANEL_HEIGHT], mirroring [keyboardHeightRow]. The scale is read when the
     * panel is shown, so the next panel open uses it; no cache needs clearing.
     */
    private fun emojiPanelHeightRow(): View {
        val row = inflateRow(R.layout.row_value, R.string.emoji_panel_height, 0)
        val valueView = row.findViewById<TextView>(R.id.row_value)
        valueView.text = emojiPanelHeightValueText()
        row.setOnClickListener {
            showEmojiPanelHeightDialog {
                valueView.text = emojiPanelHeightValueText()
            }
        }
        if (isRestricted(Settings.PREF_EMOJI_PANEL_HEIGHT)) {
            setRowEnabled(row, false)
        }
        return row
    }

    private val emojiPanelHeightLabelRes = listOf(
        R.string.emoji_panel_height_same,
        R.string.emoji_panel_height_larger,
        R.string.emoji_panel_height_max,
    )

    /** The preset's localized name, or a restriction's percent value rendered as a plain percent. */
    private fun emojiPanelHeightValueText(): String {
        val scale = Settings.readEmojiPanelHeight(prefs, EmojiPanelHeightPresets.SAME_SCALE)
        val index = EmojiPanelHeightPresets.indexForScale(scale)
        return if (index >= 0) getString(emojiPanelHeightLabelRes[index])
        else getString(R.string.abbreviation_unit_percent,
                Math.round(scale * PERCENTAGE_FLOAT))
    }

    private fun showEmojiPanelHeightDialog(onValueChanged: () -> Unit) {
        val labels = emojiPanelHeightLabelRes.map { getString(it) }.toTypedArray()
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.emoji_panel_height)
                // setItems on purpose, exactly like the keyboard-height picker: the choice applies
                // on the tap itself and the dialog closes — no unnamed OK button.
                .setItems(labels) { _, which ->
                    val scale = EmojiPanelHeightPresets.SCALES[which]
                    if (scale != Settings.readEmojiPanelHeight(prefs,
                            EmojiPanelHeightPresets.SAME_SCALE)) {
                        prefs.edit().putFloat(Settings.PREF_EMOJI_PANEL_HEIGHT, scale).apply()
                    }
                    onValueChanged()
                }
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    private val keyboardHeightLabelRes = listOf(
        R.string.keyboard_height_compact,
        R.string.keyboard_height_default,
        R.string.keyboard_height_tall,
    )

    /** The preset's localized name, or a value that matches no preset rendered as a plain percent. */
    private fun keyboardHeightValueText(): String {
        val scale = Settings.readKeyboardHeight(prefs, KeyboardHeightPresets.DEFAULT_SCALE)
        val index = KeyboardHeightPresets.indexForScale(scale)
        return if (index >= 0) getString(keyboardHeightLabelRes[index])
        else getString(R.string.abbreviation_unit_percent,
                Math.round(scale * PERCENTAGE_FLOAT))
    }

    private fun showKeyboardHeightDialog(onValueChanged: () -> Unit) {
        val labels = keyboardHeightLabelRes.map { getString(it) }.toTypedArray()
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.prefs_keyboard_height_settings)
                // setItems on purpose: the choice applies on the tap itself and the dialog
                // closes, a one-tap picker with no buttons.
                .setItems(labels) { _, which ->
                    val scale = KeyboardHeightPresets.SCALES[which]
                    if (scale != Settings.readKeyboardHeight(prefs,
                            KeyboardHeightPresets.DEFAULT_SCALE)) {
                        prefs.edit().putFloat(Settings.PREF_KEYBOARD_HEIGHT, scale).apply()
                    }
                    onValueChanged()
                }
                .create()
                .also { dialog ->
                    DialogUtils.filterObscuredTouches(dialog)
                    dialog.show()
                }
    }

    // ---------------------------------------------------------------------
    // Row actions
    // ---------------------------------------------------------------------

    /**
     * Opens a link, or shows a Toast when no app on the device can open it. The log line is for
     * developers; the Toast names no package or intent.
     */
    private fun openUrl(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Browser not found")
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_LONG).show()
        }
    }
}
