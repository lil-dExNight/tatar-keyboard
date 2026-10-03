/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app-screen contrast contract: every text/background pair of the setup, settings and
 * personal-dictionary surfaces, in both palettes, held at WCAG AA (4.5:1); the back chevron is
 * pinned at the WCAG 1.4.11 non-text floor (3:1). Ratios are computed from the parsed hex,
 * never pinned as numbers, so a palette edit re-measures itself. The disabled filled button
 * and the dimmed rows are inactive components (WCAG-exempt) and stay unpinned; the disclosure
 * chevron and the switch keep their iOS colors because the state is also carried by the row
 * text and the thumb position.
 */
class AppScreenContrastContractTest {

    private data class ContrastPair(val foreground: String, val background: String)

    private val textPairs = listOf(
        // Card text: row titles and the personal rows, the dialog title and message, input text.
        ContrastPair("app_text_primary", "app_card_bg"),
        // Screen text: the settings large title and the setup headings.
        ContrastPair("app_text_primary", "app_screen_bg"),
        // Row summaries, trailing values and input hints sit on cards.
        ContrastPair("app_text_secondary", "app_card_bg"),
        // The grouped-list section headers sit on the bare field.
        ContrastPair("app_text_secondary", "app_screen_bg"),
        // The setup "Open settings" link (borderless button on the field).
        ContrastPair("app_accent", "app_screen_bg"),
        // Action rows ("Add word…", "Clear all", "Erase all") and the dialog buttons.
        ContrastPair("app_accent", "app_card_bg"),
        // The filled setup-button label (the selector's default item is the enabled state).
        ContrastPair("setup_button_text", "app_accent_fill"),
        // The same label while the button is pressed.
        ContrastPair("setup_button_text", "app_accent_pressed"),
    )

    private val iconPairs = listOf(
        // The settings back chevron is tinted with the accent on the bare field.
        ContrastPair("app_accent", "app_screen_bg"),
    )

    @Test
    fun textPairsMeetWcagAaInBothPalettes() {
        for ((palette, colors) in palettes()) {
            for (pair in textPairs) {
                val ratio = contrastRatio(colors, pair)
                assertTrue(
                    "$palette: ${pair.foreground} on ${pair.background} must meet AA 4.5:1, " +
                        "got " + "%.2f".format(java.util.Locale.US, ratio) + ":1",
                    ratio >= AA_TEXT,
                )
            }
        }
    }

    @Test
    fun theBackChevronMeetsTheNonTextFloorInBothPalettes() {
        for ((palette, colors) in palettes()) {
            for (pair in iconPairs) {
                val ratio = contrastRatio(colors, pair)
                assertTrue(
                    "$palette: ${pair.foreground} on ${pair.background} must meet the non-text " +
                        "floor 3:1, got " + "%.2f".format(java.util.Locale.US, ratio) + ":1",
                    ratio >= NON_TEXT_FLOOR,
                )
            }
        }
    }

    /** The pinned button pairs measure the colors the button drawable actually paints. */
    @Test
    fun theFilledButtonStatesUseTheirTokens() {
        val selector = source("src/main/res/drawable/setup_button_filled.xml")
        assertTrue("disabled state drifted", selector.contains("@color/app_accent_disabled"))
        assertTrue("pressed state drifted", selector.contains("@color/app_accent_pressed"))
        assertTrue("default state drifted", selector.contains("@color/app_accent_fill"))
    }

    /** The setup "Open settings" link and the action rows take the accent the pairs assume. */
    @Test
    fun theAccentConsumersStayOnTheToken() {
        assertTrue("setup link drifted",
            source("src/main/res/layout/setup_activity.xml").contains(
                "android:textColor=\"@color/app_accent\""))
        assertTrue("action rows drifted",
            source("src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsRows.kt")
                .contains("R.color.app_accent"))
    }

    private fun palettes(): List<Pair<String, Map<String, Int>>> =
        listOf("values", "values-night").map { dir ->
            val colors = HashMap(parseColors(source("src/main/res/$dir/colors.xml")))
            colors["setup_button_text"] = parseDefaultStateColor(
                source("src/main/res/color/setup_button_text.xml"))
            dir to colors
        }

    private fun contrastRatio(colors: Map<String, Int>, pair: ContrastPair): Double {
        val fg = resolve(colors, pair.foreground)
        val bg = resolve(colors, pair.background)
        val alpha = (fg ushr 24) / 255.0
        val effective = DoubleArray(3) { channel ->
            val shift = 16 - channel * 8
            alpha * ((fg ushr shift) and 0xFF) + (1 - alpha) * ((bg ushr shift) and 0xFF)
        }
        val fgLum = luminance(effective[0] / 255.0, effective[1] / 255.0, effective[2] / 255.0)
        val bgLum = luminance(
            ((bg ushr 16) and 0xFF) / 255.0,
            ((bg ushr 8) and 0xFF) / 255.0,
            (bg and 0xFF) / 255.0,
        )
        val lighter = maxOf(fgLum, bgLum)
        val darker = minOf(fgLum, bgLum)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(r: Double, g: Double, b: Double): Double =
        0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)

    private fun linearize(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92
        else Math.pow((channel + 0.055) / 1.055, 2.4)

    private fun resolve(colors: Map<String, Int>, name: String): Int =
        colors[name] ?: error("color $name missing from the palette")

    /** Parses one colors.xml into ARGB ints, following @color references. */
    private fun parseColors(text: String): Map<String, Int> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(text.byteInputStream())
        val raw = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("color")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            raw[node.attributes.getNamedItem("name").nodeValue] = node.textContent.trim()
        }
        val resolved = HashMap<String, Int>()
        fun resolve(name: String): Int {
            resolved[name]?.let { return it }
            val value = raw[name] ?: error("color $name missing")
            val argb = if (value.startsWith("@color/")) resolve(value.removePrefix("@color/"))
            else parseHex(value)
            resolved[name] = argb
            return argb
        }
        for (name in raw.keys) resolve(name)
        return resolved
    }

    /** The default (stateless) item of a ColorStateList selector — the enabled label color. */
    private fun parseDefaultStateColor(text: String): Int {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(text.byteInputStream())
        val nodes = doc.getElementsByTagName("item")
        var defaultColor: String? = null
        for (i in 0 until nodes.length) {
            val attributes = nodes.item(i).attributes
            var hasState = false
            var color: String? = null
            for (j in 0 until attributes.length) {
                val attribute = attributes.item(j)
                if (attribute.nodeName.startsWith("android:state_")) hasState = true
                if (attribute.nodeName == "android:color") color = attribute.nodeValue
            }
            if (!hasState) defaultColor = color
        }
        return parseHex(defaultColor ?: error("setup_button_text has no default item"))
    }

    private fun parseHex(value: String): Int {
        val hex = value.removePrefix("#")
        val argb = (if (hex.length == 6) "FF$hex" else hex).toLong(16)
        return argb.toInt()
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app", relative)).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative")

    private companion object {
        private const val AA_TEXT = 4.5
        private const val NON_TEXT_FLOOR = 3.0
    }
}
