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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tatar typography follows Russian, so locale tt resolves to TEXTS_ru instead of falling back to
 * TEXTS_DEFAULT (which would show "$" on the currency key and "ABC" on the symbols-layer return
 * key). This pins the
 * user-visible consequences (₽, «АБВ», Russian „“ quotes) and the currency switch that routes
 * the tt symbols layer through "!text/keyspec_currency" instead of the hardcoded dollar style.
 */
class KeyboardTextsTableTatarTest {

    private val tt = KeyboardTextsTable.getTextsTable(Locale.forLanguageTag("tt"))
    private val ttRU = KeyboardTextsTable.getTextsTable(Locale.forLanguageTag("tt-RU"))
    private val ru = KeyboardTextsTable.getTextsTable(Locale.forLanguageTag("ru"))

    /** Both spellings of the shipped Tatar subtype ("tt" and "tt_RU") hit the Russian table. */
    @Test
    fun tatarLocaleResolvesToTheRussianTextsTable() {
        assertSame(ru, tt)
        assertSame(ru, ttRU)
    }

    /** The currency key on the Tatar ?123 layer is the ruble sign, not the dollar. */
    @Test
    fun tatarCurrencyIsTheRubleSign() {
        assertEquals("₽", KeyboardTextsTable.getText("keyspec_currency", tt))
        assertEquals("₽", KeyboardTextsTable.getText("keyspec_currency", ttRU))
    }

    /** The return-to-letters key on the Tatar ?123 layer reads «АБВ», not "ABC". */
    @Test
    fun tatarToAlphaLabelIsCyrillic() {
        assertEquals("АБВ", KeyboardTextsTable.getText("keylabel_to_alpha", tt))
        assertEquals("ABC", KeyboardTextsTable.getText(
            "keylabel_to_alpha", KeyboardTextsTable.getTextsTable(Locale.forLanguageTag("en"))))
    }

    /** Tatar quotes are the Russian „“/‚‘ pairs, not the English default “”/‘’. */
    @Test
    fun tatarQuotesFollowRussianTypography() {
        assertEquals("!text/double_9qm_lqm", KeyboardTextsTable.getText("double_quotes", tt))
        assertEquals("!text/single_9qm_lqm", KeyboardTextsTable.getText("single_quotes", tt))
        assertEquals("!text/double_lqm_rqm", KeyboardTextsTable.getText(
            "double_quotes", KeyboardTextsTable.getTextsTable(Locale.forLanguageTag("en"))))
    }

    /**
     * The texts table alone does not set the ruble sign: key_styles_currency.xml must route
     * language "tt" into key_styles_currency_generic (keySpec="!text/keyspec_currency"), otherwise
     * the switch falls through to the hardcoded dollar style and the table value is never read.
     */
    @Test
    fun currencySwitchRoutesTatarToTheGenericRubleStyle() {
        val text = File(resRoot(), "xml/key_styles_currency.xml").readText()
        val routed = GENERIC_CASE.findAll(text)
            .map { it.groupValues[1].split("|") }
            .toList()
        assertTrue("no languageCode case routes to key_styles_currency_generic", routed.isNotEmpty())
        assertTrue(
            "language \"tt\" is not routed to key_styles_currency_generic (routed: $routed)",
            routed.any { it.contains("tt") },
        )
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }

    private companion object {
        val GENERIC_CASE = Regex(
            """<case latin:languageCode="([^"]+)"[^>]*>\s*""" +
                """<include latin:keyboardLayout="@xml/key_styles_currency_generic""""
        )
    }
}
