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

package rkr.simplekeyboard.inputmethod.latin.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The locale policy of the app's own screens (SetupActivity, SettingsHostActivity),
 * decided 2026-09-25: this is a Tatar keyboard, so the UI default is TATAR, not the
 * resource fallback (English).
 *
 *  - system language tt → Tatar   (Android resolves values-tt on its own)
 *  - system language ru → Russian (Android resolves values-ru on its own)
 *  - ANYTHING else, English included → Tatar (forced by [wrap])
 *
 * English speakers therefore see Tatar, not English — that is the operator's explicit
 * call: the product exists for Tatar speakers, and a Tatar UI in a Tatar-keyboard app
 * is the honest default. The English strings stay shipped (values/) as the resource
 * fallback and for anyone who needs them via a future language picker (a settings row
 * is a noted follow-up, not built yet).
 *
 * Mechanism: a [Context.createConfigurationContext] wrap applied in
 * `attachBaseContext` of each app-screen activity — no appcompat, no new dependency,
 * no locale writing into the process-wide default (the IME process and its keyboard
 * texts are untouched: their strings are layout labels, not prose). Only the language
 * is overridden; night mode, font scale and the rest of the base configuration are
 * copied through unchanged. Configuration changes re-run attachBaseContext, so a
 * system-language switch while a screen is open resolves on the next recreation like
 * any other config change.
 */
object AppLocale {

    /** System languages whose resolution already lands on a shipped translation. */
    private val PASSTHROUGH_LANGUAGES = setOf("ru", "tt")

    private val TATAR = Locale("tt")

    /**
     * The locale the app screens must be forced into for a given system language,
     * or null when the system's own resolution already picks a shipped translation
     * and no wrap is needed. Pure and JVM-testable; [wrap] is its Android half.
     */
    fun forcedLocaleFor(systemLanguage: String): Locale? =
        if (systemLanguage in PASSTHROUGH_LANGUAGES) null else TATAR

    /**
     * The base context for an app-screen activity: [newBase] itself when the system
     * language is Russian or Tatar, otherwise a configuration context forced to Tatar.
     * The first system locale decides — a list like [en, tt] still means the user's
     * phone speaks English, and per the policy above that maps to Tatar.
     */
    fun wrap(newBase: Context): Context {
        val locales = newBase.resources.configuration.locales
        val system = if (locales.isEmpty) Locale.getDefault() else locales[0]
        val forced = forcedLocaleFor(system.language) ?: return newBase
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(forced)
        return newBase.createConfigurationContext(config)
    }
}
