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
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
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
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils

/**
 * View-based settings screens (IOS-REDESIGN.md S1 + S2): every screen of
 * the app (root, Preferences, Key press, Appearance, Languages and the
 * per-language layouts screen) rendered as iOS-style grouped cards built
 * from the row_link / row_switch / row_value layouts — no
 * android.preference, no new dependencies.
 *
 * Navigation is a single Activity swapping pages inside one scaffold
 * ([R.layout.settings_screen]) with a manual back stack, so system back
 * and the back chevron pop pages exactly like separate activities would,
 * without six manifest entries. The item composition of every screen is
 * 1:1 with the legacy screen it replaces.
 *
 * The pieces of the legacy harness this class carries over
 * (SubScreenFragment / the settings fragments / InputMethodSettingsImpl /
 * LanguagesSettingsFragment / SingleLanguageSettingsFragment):
 *  - device-protected SharedPreferences via [PreferenceManagerCompat]
 *  - the legacy harness scheduled a backup after every preference change;
 *    that is deliberately NOT carried over. E2b-3 turns backup off
 *    (android:allowBackup="false") and excludes every app data domain in
 *    res/xml/data_extraction_rules.xml (API 31+; its device-transfer section
 *    is what closes D2D transfer, which allowBackup=false does not), so a
 *    per-change backup request would have nothing to back up and is gone
 *  - enterprise restrictions ([Settings.ACTIVE_RESTRICTIONS]) disable rows
 *  - dependency chains: sound volume ⇢ sound_on, IME switch ⇢ language key
 *  - [KeyboardLayoutSet.onKeyboardThemeChanged] for the number-row and
 *    special-chars toggles so the open keyboard rebuilds its layout live
 *  - vibrate row hidden without a vibrator; on-screen-keyboard row only
 *    on API 36+ (as in the legacy fragments)
 *  - [RichInputMethodManager.init] before any subtype access, and content
 *    rebuilt on every [onStart] (the legacy fragments' buildContent)
 *
 * [Screen.LANGUAGE_DETAIL] is the one parameterized screen: its locale
 * lives in [detailLocale] and rides along in the saved instance state.
 *
 * T2 part 3 (docs/ROADMAP-P6.md) split the file without touching a call
 * site: the row builders live in `SettingsRows.kt`, the two languages
 * screens in `SettingsLanguagesScreens.kt`, the key-press screen and the
 * seek-bar proxies in `SettingsKeyPressScreen.kt` — all `internal`
 * extension functions on this activity, because sixteen source-contract
 * tests pin their exact call text to this file.
 */
class SettingsHostActivity : Activity() {

    // internal, not private: the languages screens live in SettingsLanguagesScreens.kt since the
    // T2 split (docs/ROADMAP-P6.md, part 3) and navigate by these constants from there.
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
        private val TAG = SettingsHostActivity::class.java.simpleName
        private const val STATE_SCREEN = "screen"
        private const val STATE_BACK_STACK = "back_stack"
        private const val STATE_DETAIL_LOCALE = "detail_locale"
        // internal, not private: the row builders live in SettingsRows.kt since the T2 split
        // (docs/ROADMAP-P6.md, part 3) and read them from there.
        internal const val DISABLED_ALPHA = 0.4f
        internal const val PERCENTAGE_FLOAT = 100.0f
    }

    internal lateinit var prefs: SharedPreferences
    internal lateinit var richImm: RichInputMethodManager
    private lateinit var scrollView: ScrollView
    internal lateinit var contentView: LinearLayout
    private lateinit var titleView: TextView

    private val backStack = ArrayDeque<Screen>()
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
     * The quarantine copies found for each language, or null while the answer is still being read.
     *
     * Null is "not asked yet", not "none": the read happens on the personal-store worker like every
     * other read in that subsystem, so the screen paints once without the card and repaints when the
     * answers arrive. Every finished mutation puts it back to null, because a restore, a discard and
     * an erasure all change what the answer is.
     *
     * It holds two numbers per language and no word — see `PersonalQuarantineReport`.
     */
    private var personalQuarantines: Map<String, PersonalQuarantineReport>? = null

    /**
     * The pairs half of [personalQuarantines] (U7 of Phase 2, docs/ROADMAP-P2.md): the same
     * not-asked/asking/answered lifecycle, the same invalidation rule — every finished pair
     * mutation puts it back to null. The two maps are separate because the two stores quarantine
     * independently; a language can hold a copy of its words, of its pairs, of both or of neither.
     */
    private var personalPairQuarantines: Map<String, PersonalQuarantineReport>? = null

    /**
     * Registered on the device-protected prefs exactly like
     * SubScreenFragment.onCreate, minus its backup request: E2b-3 disables
     * backup entirely, so the only job left here is to clear the keyboard
     * layout cache for the keys whose legacy fragments did so. Everything
     * else reaches the live keyboard through the Settings singleton's own
     * listener on the same prefs file.
     */
    private val prefChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (Settings.PREF_SHOW_NUMBER_ROW == key
                    || Settings.PREF_SHOW_EMOJI_KEY == key
                    || Settings.PREF_SHOW_SPECIAL_CHARS == key) {
                KeyboardLayoutSet.onKeyboardThemeChanged()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE once, for the WHOLE activity, not per "screen": the screens here are not
        // separate activities but swapped content in one ScrollView, and adding/clearing the flag
        // during navigation is a known source of flicker, surface recreation and races with the
        // recent-apps snapshot on OEM builds. Without it the list of what the user typed lands in
        // the recent-apps thumbnail and can be screenshotted. Nobody suffers from a permanent flag
        // on a keyboard settings screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Content capture is switched off for the whole window by the API that actually exists
            // publicly for it (added in R). Without it the platform's content-capture pipeline may
            // see the saved words rendered on the screen.
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
        // Rebuild the visible screen every time the activity comes back to
        // the foreground — the legacy fragments' buildContent-in-onStart.
        // The language list, enabled-layout summaries and restriction state
        // are all re-read, so external changes are picked up.
        showScreen(currentScreen)
    }

    override fun onDestroy() {
        // Dismiss any open slider dialog: AlertDialog is not lifecycle-aware,
        // and leaving it attached across a configuration change leaks the
        // window (WindowLeaked). The uncommitted slider value is discarded,
        // matching the legacy DialogPreference behavior closely enough.
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
            showScreen(previous)
        } else {
            super.onBackPressed()
        }
    }

    internal fun navigateTo(screen: Screen) {
        backStack.addLast(currentScreen)
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
    }

    // ---------------------------------------------------------------------
    // Screens (item composition 1:1 with prefs.xml / prefs_screen_*.xml)
    // ---------------------------------------------------------------------

    private fun buildRootScreen() {
        // The languages entry InputMethodSettingsImpl used to add in code:
        // same title/summary, same PREF_ENABLED_SUBTYPES restriction.
        addCard(listOf(
            linkRow(R.string.keyboard_languages, R.string.keyboard_languages_summary,
                    Settings.PREF_ENABLED_SUBTYPES) { navigateTo(Screen.LANGUAGES) }))
        addCard(listOf(
            linkRow(R.string.settings_screen_preferences) { navigateTo(Screen.PREFERENCES) },
            linkRow(R.string.settings_screen_key_press) { navigateTo(Screen.KEY_PRESS) },
            linkRow(R.string.settings_screen_appearance) { navigateTo(Screen.APPEARANCE) }))
        addCard(listOf(
            linkRow(R.string.privacy_policy) { openUrl(getString(R.string.privacy_policy_url)) },
            linkRow(R.string.license) { openUrl(getString(R.string.license_url)) },
            linkRow(R.string.settings_screen_data_sources) { navigateTo(Screen.DATA_SOURCES) }))
    }

    /**
     * "Data sources": where the words in this keyboard come from, one row per collection.
     *
     * It exists because the collections ask for it. Leipzig and Tatoeba are CC BY, and the BY is
     * the whole condition — naming the source is what buys the right to ship a word list derived
     * from it. OpenSubtitles asks for one thing only, a link back to opensubtitles.org, and that
     * link is this screen's [R.string.data_sources_opensubtitles_url] row. `NOTICE.txt` next to
     * the assets carries the same names in full; this screen is the half a person can actually
     * reach without unpacking an APK.
     *
     * The two section headers are not decoration. Only the Leipzig data is inside the app today;
     * the conversational frequencies from Tatoeba and OpenSubtitles are measured, queued for
     * word-by-word acceptance (`docs/DICTIONARY-*-CONV-REVIEW.tsv`) and not packed into any
     * asset. Listing all three under one heading would claim something untrue about the shipped
     * files, and the release that merges them has a checklist line to move the rows up.
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
        rows.add(switchRow(Settings.PREF_TATAR_SUGGESTIONS, false,
                R.string.tatar_suggestions, R.string.tatar_suggestions_summary) { checked ->
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
        })
        // The personal dictionary rides on the suggestion band: without suggestions there is
        // nowhere for a remembered word to appear, so the row follows the switch above it.
        val personalRow = switchRow(Settings.PREF_PERSONAL_DICTIONARY, false,
                R.string.personal_dictionary, R.string.personal_dictionary_summary)
        personalDictionaryRow = personalRow
        rows.add(personalRow)
        // U8 (docs/ROADMAP-P2.md): incognito pauses the very learning that only exists while
        // suggestions run, so the row follows the same switch. It pauses BOTH stores at once —
        // two pause switches would give four states, only three of which mean anything. The
        // key is never declared in app_restrictions.xml, so isRestricted below never fires; the
        // check keeps the row's shape identical to its siblings if that ever changes.
        val incognitoSwitch = switchRow(Settings.PREF_INCOGNITO_MODE, false,
                R.string.incognito_mode, R.string.incognito_mode_summary)
        incognitoRow = incognitoSwitch
        rows.add(incognitoSwitch)
        // Autocorrection (D3) is subordinate to suggestions for a different reason than the personal
        // dictionary: it draws its candidate from the very same lookup that feeds the band, so with
        // suggestions off there is nothing to correct from. Its own switch stays separate because a
        // suggestion offers while a correction changes what is already typed.
        val autocorrectSwitch = switchRow(Settings.PREF_TATAR_AUTOCORRECT, false,
                R.string.tatar_autocorrect, R.string.tatar_autocorrect_summary)
        autocorrectRow = autocorrectSwitch
        rows.add(autocorrectSwitch)
        // Emoji suggestions (mission 2 of docs/EMOJI-SUGGEST-PLAN.md) are subordinate to the
        // suggestions switch because the emoji cell lives in the very same band; separate because
        // a picture among the words is a taste, not a feature of the words themselves. The default
        // matches Settings.readEmojiSuggestionsEnabled (on, M4b); a user-set value always wins.
        val emojiSuggestSwitch = switchRow(Settings.PREF_EMOJI_SUGGESTIONS, true,
                R.string.emoji_suggestions, R.string.emoji_suggestions_summary)
        emojiSuggestRow = emojiSuggestSwitch
        rows.add(emojiSuggestSwitch)
        addCard(rows)
        // android:dependency="pref_show_language_switch_key" from the legacy screen.
        setRowEnabled(imeRow,
                prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true)
                        && !isRestricted(Settings.PREF_ENABLE_IME_SWITCH))
        setRowEnabled(personalRow,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_PERSONAL_DICTIONARY))
        setRowEnabled(incognitoSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_INCOGNITO_MODE))
        setRowEnabled(autocorrectSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_TATAR_AUTOCORRECT))
        setRowEnabled(emojiSuggestSwitch,
                Settings.readTatarSuggestionsEnabled(prefs)
                        && !isRestricted(Settings.PREF_EMOJI_SUGGESTIONS))
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
     * The "Personal dictionary" screen (E4b, extended by U7 of Phase 2 — docs/ROADMAP-P2.md): the
     * words AND the learned word pairs of EVERY language, grouped by language, with a search field,
     * an "Add word…" row, a usage count on every row, a "Delete" action on each shown row, a
     * per-language "Clear all" for each store and a global "Erase all" that covers both stores.
     *
     * Fully usable with the setting off — erasing what was already saved must always be possible.
     * Only ADDING follows the setting, because the acceptance says that with the personal dictionary
     * off not a single file is created.
     *
     * The search text deliberately does NOT survive rotation: `onSaveInstanceState` carries the
     * screen, the back stack and the detail locale, and a Bundle travels through Binder into
     * `system_server` — putting a fragment of a personal word there for the convenience of a rotation
     * is not a trade worth making. Documented in docs/DICTIONARY-E4.md as expected behaviour.
     *
     * The stores are never read directly: every read is the published snapshot of the process-wide
     * owner (priming and file work happen on the shared personal-store worker, never on this
     * thread), and a store that cannot be read — the device still locked, a file set aside — simply
     * publishes an empty snapshot, so the screen shows the empty state and the quarantine card says
     * why. Fail-closed, no crash.
     */
    private fun buildPersonalDictionaryScreen() {
        val controller = PersonalDictionaryScreenController(this)
        val pairController = PersonalBigramScreenController(this)
        val subtypeIds = personalSubtypeIds()
        val content = PersonalDictionaryScreenModel.build(
                controller.sections(subtypeIds), pairController.sections(subtypeIds),
                personalSearchQuery)

        // U8: the pause is visible exactly where the learned content is managed — a small note,
        // not a dialog: the state is not an emergency, it is something the user asked for.
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

        if (content.totalCount == 0) {
            // Three states, not two. "Nothing saved yet" while the personal dictionary is ALREADY on
            // used to end with "…once the personal dictionary is on", sending the person to look for
            // a switch that is not off. The hint belongs only to the state it describes.
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
            // Two cards per language, one per store, each opened by its true saved count and
            // closed by its own "clear all": a count the list does not visibly reach (the cap)
            // and an erasure the list does not cover would both be lies the user cannot detect.
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
        }

        if (content.isTruncated) {
            // Never silently truncated: a capped list that does not say so reads as "this is
            // everything you saved", which would be a lie the user cannot detect.
            addCard(listOf(inflateRow(R.layout.row_link,
                    getString(R.string.personal_dictionary_shown_of_total,
                            content.shownCount, content.totalCount), null).also {
                it.findViewById<View>(R.id.row_chevron).visibility = View.GONE
            }))
        }

        if (content.totalCount > 0 || personalSearchQuery.isNotEmpty()) {
            addCard(listOf(actionRow(R.string.personal_dictionary_erase_all) {
                showErasePersonalDictionaryDialog(controller, pairController, subtypeIds)
            }))
        }
    }

    /**
     * One saved-content row of the personal screen: the word or the pair as the title, the usage
     * count and the delete affordance as the summary. The summary carries both on purpose — the
     * count is information (U7), the word "Delete" is what tells the user the row is tappable.
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
     * The card that finishes what 1.8.2 started: a personal dictionary that could not be read is
     * kept as a copy, and until this card existed no screen showed it and no code could read it.
     *
     * One card per language that has a copy, with the two numbers the user needs and nothing else:
     * how many words came out of it, and — when part of it is damaged — that the rest is lost. That
     * second sentence is not decoration. Handing back two thirds of someone's words under the word
     * "restored" is the one outcome this feature must never produce, so the count and the damage are
     * printed in the same breath.
     *
     * Two actions, both started by the person and neither by the keyboard: put the readable words
     * back, and delete the copy. They are separate on purpose — restoring does not destroy the part
     * no parser could read, so a better reader later still has something to read.
     *
     * A copy that yielded NOTHING still gets a card. There is nothing to restore, but the bytes are
     * the user's own words sitting on their device, and the only way to ask for them to go must not
     * be hidden behind a word count greater than zero.
     */
    private fun addPersonalQuarantineCards(
            controller: PersonalDictionaryScreenController, subtypeIds: List<String>) {
        val reports = personalQuarantines
        if (reports == null) {
            // Not asked yet. The read is file work and belongs on the store's worker; the screen
            // repaints when it answers, which is the same shape every mutation on it already uses.
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
            // Plurals, not a bare %d: "1 words" in English and "1 слов" in Russian are the kind of
            // sloppiness that makes a person doubt the sentence beside it, and the sentence beside it
            // is the one that says part of their words is gone.
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
     * The pairs half of [addPersonalQuarantineCards] (U7 of Phase 2, docs/ROADMAP-P2.md): one card
     * per language whose learned PAIRS file could not be read and was set aside. Same rules: the
     * count and the damage are printed in the same breath, restoring and discarding are two
     * separate actions the user starts, and a copy that yielded nothing keeps its card because the
     * bytes are still on the device. The read runs on the store's worker; the screen repaints when
     * the answer arrives.
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

    /** Subtypes whose words the screen shows: every enabled one, in the order the system lists them. */
    private fun personalSubtypeIds(): List<String> =
            richImm.getEnabledSubtypes(true).map { it.locale }.distinct()

    /**
     * The store a hand-added word goes into: the language the keyboard is currently set to, when it
     * has a personal dictionary, and otherwise the first enabled subtype that does.
     *
     * With two languages "the first enabled one" is no longer good enough — it would file a Russian
     * word under Tatar for a user whose Tatar layout simply sits earlier in the system's list. The
     * live subtype is the closest thing this screen has to "the language the user means"; the
     * screen shows every language's words in separate sections either way, so a wrong guess stays
     * visible and fixable rather than silent.
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
                    dialog.show()
                }
    }

    /**
     * The pair half of [showForgetPersonalWordDialog] (U7 of Phase 2, docs/ROADMAP-P2.md): the
     * title shows the pair the way the row does — "A → B" — so the confirmation names exactly
     * what is about to be gone. The deletion goes through the store's `forget`, which purges the
     * quarantine copy with it: a forgotten pair is never resurrected by a later restore.
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
                    dialog.show()
                }
    }

    /**
     * Per-language "Clear all words" (U7): confirmed, then routed through the store's `clearAll`,
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

    /** The pairs half of [showClearPersonalWordsDialog]. */
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

    private fun showErasePersonalDictionaryDialog(
            controller: PersonalDictionaryScreenController,
            pairController: PersonalBigramScreenController, subtypeIds: List<String>) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
                .setTitle(R.string.personal_dictionary_erase_all)
                .setMessage(R.string.personal_dictionary_erase_confirm)
                .setPositiveButton(R.string.personal_dictionary_erase_action) { _, _ ->
                    // U7: "erase all" covers BOTH stores — a global erasure that left the learned
                    // pairs behind would read as "everything is gone" while the predictions kept
                    // coming. The two halves answer independently and the screen reports success
                    // only when every file of every language is really gone.
                    controller.eraseAll(subtypeIds) { wordsErased ->
                        pairController.eraseAll(subtypeIds) { pairsErased ->
                            // Erasing takes the copies with it, so both cards are re-read rather
                            // than repainted from an answer that is now out of date.
                            personalQuarantines = null
                            personalPairQuarantines = null
                            afterPersonalMutation(wordsErased && pairsErased,
                                    R.string.personal_dictionary_erase_failed)
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
     * The one place the personal-dictionary screen reacts to a mutation that has actually finished.
     *
     * Both halves matter and neither used to happen. The list is repainted only NOW, because the
     * published snapshot is what it reads and the snapshot did not exist yet at the moment the
     * dialog closed — the added word was simply missing from the list, which reads as "the button
     * did nothing". And a mutation that failed says so: the subsystem may not log, so a message on
     * screen is the only channel it has, and it names no word, no file and no cause.
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
     * The three flags both text fields of this screen carry — the search field and the "Add word…"
     * field. Without them the name or the village a user puts into OUR private dictionary would be
     * learned and synced to the cloud by whichever third-party keyboard is typing it (people
     * normally have two installed), or picked up by an autofill service. The search field takes the
     * very same personal words as the add field, so there is no exception here.
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

    // "Key press" screen — moved verbatim to SettingsKeyPressScreen.kt (T2 part 3,
    // docs/ROADMAP-P6.md), together with the three seek-bar value proxies.

    private fun buildAppearanceScreen() {
        addCard(listOf(
            switchRow(Settings.PREF_SHOW_NUMBER_ROW, false,
                    R.string.show_number_row, R.string.show_number_row_summary),
            switchRow(Settings.PREF_SHOW_EMOJI_KEY, true,
                    R.string.show_emoji_key, R.string.show_emoji_key_summary),
            keyboardHeightRow(),
            valueRow(Settings.PREF_BOTTOM_OFFSET_PORTRAIT,
                    R.string.prefs_bottom_offset_portrait_settings,
                    resources.getInteger(R.integer.config_min_bottom_offset_portrait),
                    resources.getInteger(R.integer.config_max_bottom_offset_portrait),
                    resources.getInteger(R.integer.config_bottom_offset_step),
                    bottomOffsetProxy())))
    }

    // ---------------------------------------------------------------------
    // The languages screens moved verbatim to SettingsLanguagesScreens.kt
    // (T2 part 3, docs/ROADMAP-P6.md). The emoji-recents dialog below stays
    // here: EmojiRecentAndFlingSourceContractTest pins its body to this file.
    // ---------------------------------------------------------------------

    /**
     * Confirmation dialog for "Clear recent emoji", built like [showLocalePickerDialog]: the
     * previous dialog is dismissed, the reference is kept in [currentDialog] and torn down in
     * [onDestroy] so a rotation with the dialog open never leaks the window (WindowLeaked). The
     * buttons are the platform strings; only the row title and the dialog body are our own.
     *
     * The erase goes to [EmojiPanelController.clearRecents], which routes to the live keyboard when
     * one exists in this process (updating its in-memory list and any open panel) and otherwise
     * replaces the medium directly. It runs off the UI thread inside the controller. This screen
     * never reads the recents content — it only asks for the erase.
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
    // The row builders moved verbatim to SettingsRows.kt (T2 part 3,
    // docs/ROADMAP-P6.md). The keyboard-height row below stays here:
    // KeyboardHeightPreferenceTest pins its text to this file.
    // ---------------------------------------------------------------------

    /**
     * U6 of Phase 5 (docs/ROADMAP-P5.md): "Keyboard height" as three named presets instead of
     * the inherited 21-step seek bar. The row reuses row_value; the tap target opens a
     * one-tap picker that writes the preset's float into the very same
     * [Settings.PREF_KEYBOARD_HEIGHT] the seek bar wrote, so the live-apply path (Settings
     * rebuild → next loadKeyboard → new mHeight in the KeyboardId, hence a fresh build) is the
     * one the slider already used. A float from the seek-bar era matches no preset: it keeps
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

    private val keyboardHeightLabelRes = listOf(
        R.string.keyboard_height_compact,
        R.string.keyboard_height_default,
        R.string.keyboard_height_tall,
    )

    /** The preset's localized name, or the seek-bar era value rendered as a plain percent. */
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
                // closes — a one-tap picker with no buttons. (An unnamed OK would also break
                // the file-wide contract of EmojiRecentAndFlingSourceContractTest.)
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
     * Opens a link, or says that nothing on this device can.
     *
     * The log line alone was the whole answer before: the row took the tap, the screen did not
     * change, and only `adb logcat` knew why. On a keyboard whose one claim is privacy, "Privacy
     * Policy" being a row that does nothing is the worst row to lose quietly. The log line stays for
     * a developer; the Toast is for the person holding the phone, and it names no package and no
     * intent — neither is anything they could act on.
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
