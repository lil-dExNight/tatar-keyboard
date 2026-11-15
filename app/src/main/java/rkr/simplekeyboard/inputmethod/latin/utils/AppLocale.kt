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
 * The locale policy of the app's own screens and keyboard chrome:
 *  - the first supported system language (tt, ru or en) wins, wherever it sits in the list
 *  - a system list without any supported language → English (forced by [wrap])
 *
 * A first entry that is already supported needs no wrap: Android resolves values-tt, values-ru
 * or the English base on its own. When the supported language sits further down ([de, tt]),
 * [wrap] forces it explicitly, so a bilingual user gets the translation they asked for instead
 * of the English fallback the unsupported first entry would have produced.
 *
 * [wrap] overrides only the language in the base context; the process-wide default is unchanged.
 */
object AppLocale {

    /** Languages with a shipped translation: values-tt, values-ru and the English base. */
    private val SUPPORTED_LANGUAGES = setOf("en", "ru", "tt")

    private val ENGLISH = Locale.ENGLISH

    /**
     * The first language of [systemLanguages] that has a shipped translation, in the user's own
     * preference order, or null when the list holds none. Pure and JVM-testable.
     */
    fun firstSupportedLanguage(systemLanguages: List<String>): String? =
        systemLanguages.firstOrNull { it in SUPPORTED_LANGUAGES }

    /**
     * The locale the app screens must be forced into for a system locale list, or null when the
     * list's first entry is already supported and the system's own resolution picks the shipped
     * translation without a wrap. An empty or wholly unsupported list maps to English.
     */
    fun forcedLocaleFor(systemLanguages: List<String>): Locale? {
        val supported = firstSupportedLanguage(systemLanguages)
        return when {
            supported == null -> ENGLISH
            systemLanguages.firstOrNull() == supported -> null
            else -> Locale(supported)
        }
    }

    /**
     * Returns [newBase] when the first system locale already resolves to a shipped translation,
     * or a context forced into the first supported language of the whole list (English when the
     * list has none).
     */
    fun wrap(newBase: Context): Context {
        val locales = newBase.resources.configuration.locales
        val languages = if (locales.isEmpty) listOf(Locale.getDefault().language)
            else (0 until locales.size()).map { locales[it].language }
        val forced = forcedLocaleFor(languages) ?: return newBase
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(forced)
        return newBase.createConfigurationContext(config)
    }
}
