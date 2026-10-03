package rkr.simplekeyboard.inputmethod.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The theme-contrast contract: every informative glyph/background pair of the keyboard theme,
 * in both palettes, held at WCAG AA (4.5:1) for text and at the WCAG 1.4.11 non-text floor
 * (3:1) for state icons. Ratios are computed from the parsed hex, never pinned as numbers, so
 * a palette edit re-measures itself. Alpha-carrying colors are composited onto their
 * background the way Android paints them; a pair's alphaOverride mirrors a paint alpha the
 * drawing code forces (the spacebar label's pinned opaque alpha, the emoji dimming constants,
 * the trail color's own alpha as the trail peak).
 */
class ThemeContrastContractTest {

    /** One pinned pair; [alphaOverride] replaces the foreground color's own alpha channel. */
    private data class ContrastPair(val foreground: String, val background: String,
                                    val alphaOverride: Int? = null)

    private val textPairs = listOf(
        // Letter labels; also the more-keys panel text and the key preview text.
        ContrastPair("ios_key_text_color", "ios_key_normal"),
        // Suggestion strip words and the emoji search results.
        ContrastPair("ios_key_text_color", "ios_keyboard_background"),
        // Functional key glyphs (shift, delete, ?123, ...), tinted icons included.
        ContrastPair("ios_key_functional_text_color", "ios_key_functional"),
        // Action/enter labels and their tinted icons on the accent fill.
        ContrastPair("ios_key_action_text_color", "ios_key_action_fill"),
        // Hint letters on the letter keys.
        ContrastPair("ios_key_hint_color", "ios_key_normal"),
        // Hint labels on the number rows.
        ContrastPair("ios_key_text_inactive_color", "ios_key_normal"),
        // The period key's punctuation hint sits on a functional fill.
        ContrastPair("ios_key_text_inactive_color", "ios_key_functional"),
        // The spacebar language label is painted with the paint alpha forced opaque.
        ContrastPair("ios_key_text_inactive_color", "ios_key_spacebar", 0xFF),
        // The slide-to-select cell of the more-keys panel.
        ContrastPair("ios_popup_key_selected_text", "ios_popup_key_pressed"),
        // The autocorrect preview's correction cell.
        ContrastPair("ios_suggestion_emphasis", "ios_keyboard_background"),
        // Emoji panel category headers, dimmed to HEADER_ALPHA.
        ContrastPair("ios_key_functional_text_color", "ios_keyboard_background", HEADER_ALPHA),
        // Emoji search hint and empty message, dimmed to HINT_ALPHA.
        ContrastPair("ios_key_functional_text_color", "ios_keyboard_background", HINT_ALPHA),
        // The glide trail at its fingertip peak over the letter keys.
        ContrastPair("ios_glide_trail", "ios_key_normal"),
    )

    private val iconPairs = listOf(
        // Locked shift and sticky shift are state icons: WCAG 1.4.11 asks 3:1, not AA text.
        ContrastPair("ios_key_functional_text_color", "ios_key_checked"),
        ContrastPair("ios_key_functional_text_color", "ios_key_sticky_on"),
        // The emoji panel's search-entry icon, dimmed to SEARCH_HINT_ALPHA.
        ContrastPair("ios_key_functional_text_color", "ios_keyboard_background", SEARCH_HINT_ALPHA),
        // The trail also crosses the gaps between keys.
        ContrastPair("ios_glide_trail", "ios_keyboard_background"),
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
    fun stateIconsMeetTheNonTextFloorInBothPalettes() {
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

    @Test
    fun theDimmingAlphasMatchTheirSourceConstants() {
        assertTrue(
            "HEADER_ALPHA drifted",
            source("src/main/java/rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
                .contains("private const val HEADER_ALPHA = 0xE6"),
        )
        assertTrue(
            "HINT_ALPHA drifted",
            source("src/main/java/rkr/simplekeyboard/inputmethod/latin/emoji/EmojiSearchView.kt")
                .contains("private const val HINT_ALPHA = 0xE6"),
        )
        assertTrue(
            "SEARCH_HINT_ALPHA drifted",
            source("src/main/java/rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
                .contains("private const val SEARCH_HINT_ALPHA = 0xB0"),
        )
    }

    private fun palettes(): List<Pair<String, Map<String, Int>>> =
        listOf("values", "values-night").map { dir ->
            dir to parseColors(source("src/main/res/$dir/colors.xml"))
        }

    private fun contrastRatio(colors: Map<String, Int>, pair: ContrastPair): Double {
        val fg = resolve(colors, pair.foreground)
        val bg = resolve(colors, pair.background)
        val alpha = (pair.alphaOverride ?: fg ushr 24) / 255.0
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
        // Mirrors of the paint alpha constants, pinned by theDimmingAlphasMatchTheirSourceConstants.
        private const val HEADER_ALPHA = 0xE6
        private const val HINT_ALPHA = 0xE6
        private const val SEARCH_HINT_ALPHA = 0xB0
    }
}
