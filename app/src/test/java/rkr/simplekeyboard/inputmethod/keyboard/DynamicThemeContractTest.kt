package rkr.simplekeyboard.inputmethod.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The dynamic-theme contract. The theme wires the iOS geometry to the framework system colors
 * (the Material You tonal palette) on API 31+ and to the default palette below it. This suite
 * pins, without a device:
 *  - the slot -> role mapping: the Kotlin table, the two v31 resource tiers and this file's
 *    framework fixtures must agree;
 *  - contrast: every informative glyph/background pair of the default theme's contrast
 *    contract, recomputed on the simulated framework palette, held at the same WCAG AA (4.5:1)
 *    and non-text (3:1) thresholds in both uiModes;
 *  - the wholesale fallback: the base tier aliases the default palette slot for slot;
 *  - the API gate: the resolver refuses to run below API 31;
 *  - geometry parity: every dyn_* drawable equals its ios_* twin modulo the color prefix.
 */
class DynamicThemeContractTest {

    /** One pinned pair; [alphaOverride] replaces the foreground color's own alpha channel. */
    private data class ContrastPair(val foreground: String, val background: String,
                                    val alphaOverride: Int? = null)

    // Mirrors of the pair list of ThemeContrastContractTest, spelled with the dyn_* slots. The
    // selected-glyph pair reads the action ink: the popup glyph is painted with
    // actionKeyTextColor (see MoreKeysKeyboardView).
    private val textPairs = listOf(
        // Letter labels; also the more-keys panel text and the key preview text.
        ContrastPair("dyn_key_text_color", "dyn_key_normal"),
        // Suggestion strip words and the emoji search results.
        ContrastPair("dyn_key_text_color", "dyn_keyboard_background"),
        // Functional key glyphs (shift, delete, ?123, ...), tinted icons included.
        ContrastPair("dyn_key_functional_text_color", "dyn_key_functional"),
        // Action/enter labels and their tinted icons on the accent fill.
        ContrastPair("dyn_key_action_text_color", "dyn_key_action_fill"),
        // Hint letters on the letter keys.
        ContrastPair("dyn_key_hint_color", "dyn_key_normal"),
        // Hint labels on the number rows.
        ContrastPair("dyn_key_text_inactive_color", "dyn_key_normal"),
        // The period key's punctuation hint sits on a functional fill.
        ContrastPair("dyn_key_text_inactive_color", "dyn_key_functional"),
        // The spacebar language label is painted with the paint alpha forced opaque.
        ContrastPair("dyn_key_text_inactive_color", "dyn_key_spacebar", 0xFF),
        // The slide-to-select cell of the more-keys panel.
        ContrastPair("dyn_key_action_text_color", "dyn_popup_key_pressed"),
        // The autocorrect preview's correction cell.
        ContrastPair("dyn_suggestion_emphasis", "dyn_keyboard_background"),
        // Emoji panel category headers, dimmed to HEADER_ALPHA.
        ContrastPair("dyn_key_functional_text_color", "dyn_keyboard_background", HEADER_ALPHA),
        // Emoji search hint and empty message, dimmed to HINT_ALPHA.
        ContrastPair("dyn_key_functional_text_color", "dyn_keyboard_background", HINT_ALPHA),
        // The glide trail at its fingertip peak over the letter keys.
        ContrastPair("dyn_glide_trail", "dyn_key_normal"),
    )

    private val iconPairs = listOf(
        // Locked shift and sticky shift are state icons: WCAG 1.4.11 asks 3:1, not AA text.
        ContrastPair("dyn_key_functional_text_color", "dyn_key_checked"),
        ContrastPair("dyn_key_functional_text_color", "dyn_key_sticky_on"),
        // The emoji panel's search-entry icon, dimmed to SEARCH_HINT_ALPHA.
        ContrastPair("dyn_key_functional_text_color", "dyn_keyboard_background", SEARCH_HINT_ALPHA),
        // The trail also crosses the gaps between keys.
        ContrastPair("dyn_glide_trail", "dyn_keyboard_background"),
    )

    @Test
    fun textPairsMeetWcagAaInBothUiModes() {
        for ((uiMode, palette) in simulatedPalettes()) {
            for (pair in textPairs) {
                val ratio = contrastRatio(palette, pair)
                assertTrue(
                    "$uiMode: ${pair.foreground} on ${pair.background} must meet AA 4.5:1, " +
                        "got " + "%.2f".format(java.util.Locale.US, ratio) + ":1",
                    ratio >= AA_TEXT,
                )
            }
        }
    }

    @Test
    fun stateIconsMeetTheNonTextFloorInBothUiModes() {
        for ((uiMode, palette) in simulatedPalettes()) {
            for (pair in iconPairs) {
                val ratio = contrastRatio(palette, pair)
                assertTrue(
                    "$uiMode: ${pair.foreground} on ${pair.background} must meet the non-text " +
                        "floor 3:1, got " + "%.2f".format(java.util.Locale.US, ratio) + ":1",
                    ratio >= NON_TEXT_FLOOR,
                )
            }
        }
    }

    @Test
    fun theKotlinRoleTableMatchesBothV31Tiers() {
        val table = parseRoleTable(source(PALETTE_KT))
        assertEquals("light tier and the Kotlin table disagree",
            table.associate { it.slot to it.lightRole }, systemColorRefs(v31Light))
        assertEquals("dark tier and the Kotlin table disagree",
            table.associate { it.slot to it.darkRole }, systemColorRefs(v31Dark))
    }

    @Test
    fun everyDynamicSlotExistsInEveryTier() {
        val slots = v31Light.keys
        assertEquals(slots, v31Dark.keys)
        val fallback = parseColors(source("src/main/res/values/colors-dynamic.xml"))
        assertEquals(slots, fallback.keys)
    }

    @Test
    fun theFallbackTierAliasesTheDefaultPaletteWholesale() {
        val fallback = parseColors(source("src/main/res/values/colors-dynamic.xml"))
        for ((slot, value) in fallback) {
            assertEquals("the fallback tier must alias the default palette slot for slot",
                "@color/" + slot.replaceFirst("dyn_", "ios_"), value)
        }
    }

    @Test
    fun theFixedSlotsStayTranslucentLiteralsOnTheV31Tiers() {
        // The shadow and the dimmed hint inks are fixed translucents on every tier: the
        // framework palette carries no translucent roles.
        for (tier in listOf(v31Light, v31Dark)) {
            for (slot in FIXED_LITERAL_SLOTS) {
                assertTrue("$slot must be a literal in the v31 tiers",
                    tier[slot]?.startsWith("#") == true)
            }
        }
    }

    @Test
    fun theResolverIsGatedToApi31() {
        val kotlin = source(PALETTE_KT)
        assertTrue("the API 31 gate line must exist in DynamicThemePalette",
            kotlin.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
        // The roles resolve by name, never by a compile-time android.R.color reference, so the
        // class loads below API 31 where those fields do not exist.
        assertFalse("the resolver must not reference android.R.color fields",
            kotlin.contains("android.R.color."))
    }

    @Test
    fun theThemeIsRegisteredWithTheNextFreeId() {
        val theme = source("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardTheme.java")
        assertTrue(theme.contains("THEME_ID_TATAR_DYNAMIC = 8"))
        assertTrue(theme.contains("R.style.KeyboardTheme_TatarDynamic"))
        // The default stays first: it is the fallback every selection path returns.
        val entries = Regex("new KeyboardTheme\\(THEME_ID_([A-Z_]+)").findAll(theme).toList()
        assertEquals("TATAR", entries.first().groupValues[1])
    }

    @Test
    fun thePickerArraysStayAligned() {
        val doc = parseXml(source("src/main/res/values/keyboard-themes.xml"))
        val names = arrayItems(doc, "string-array", "keyboard_theme_names")
        val ids = arrayItems(doc, "integer-array", "keyboard_theme_ids")
        val idsString = arrayItems(doc, "string-array", "keyboard_theme_ids_string")
        val colors = arrayItems(doc, "array", "keyboard_theme_colors")
        assertEquals(listOf("7", "8"), ids)
        assertEquals(ids, idsString)
        assertEquals("every picker array must have one entry per theme",
            setOf(names.size), setOf(ids.size, idsString.size, colors.size))
    }

    @Test
    fun theDynamicStylesNeverReferenceTheStaticPalette() {
        // Half-mapping guard: the dynamic styles take every color from the dyn_* set. The one
        // shared drawable reads ?attr/popupPanelBackgroundColor, which the theme overrides.
        val styles = source("src/main/res/values/themes-dynamic.xml")
        val iosRefs = Regex("@(color|drawable)/ios_[a-z_]+").findAll(styles)
            .map { it.value }.toList()
        assertEquals(listOf("@drawable/ios_popup_panel_background"), iosRefs)
    }

    @Test
    fun theDynamicDrawablesMirrorTheIosGeometry() {
        for (name in DRAWABLE_TWINS) {
            val dyn = normalizedDrawable("dyn_$name")
            val ios = normalizedDrawable("ios_$name")
            assertEquals("drawable/dyn_$name.xml must equal its ios twin modulo the color prefix",
                ios, dyn.replace("dyn_", "ios_"))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The simulated framework palette.
    // ---------------------------------------------------------------------------------------------

    /**
     * The effective dyn_* palette of one uiMode: system roles from the canonical fixtures,
     * literal slots parsed as-is. Reads the v31 tiers so a retone re-measures itself.
     */
    private fun simulatedPalettes(): List<Pair<String, Map<String, Int>>> =
        listOf("light" to v31Light, "dark" to v31Dark).map { (uiMode, tier) ->
            uiMode to tier.mapValues { (_, value) ->
                if (value.startsWith("@android:color/")) {
                    FRAMEWORK_FIXTURES[value.removePrefix("@android:color/")]
                        ?: error("no fixture for $value")
                } else parseHex(value)
            }
        }

    private val v31Light: Map<String, String> by lazy {
        parseColors(source("src/main/res/values-v31/colors-dynamic.xml"))
    }
    private val v31Dark: Map<String, String> by lazy {
        parseColors(source("src/main/res/values-night-v31/colors-dynamic.xml"))
    }

    private fun systemColorRefs(tier: Map<String, String>): Map<String, String> =
        tier.filterValues { it.startsWith("@android:color/") }
            .mapValues { (_, value) -> value.removePrefix("@android:color/") }

    /** Parses the one-entry-per-line RoleMapping table of DynamicThemePalette. */
    private fun parseRoleTable(kotlin: String): List<RoleMapping> =
        Regex("""RoleMapping\("([^"]+)", "([^"]+)", "([^"]+)"\)""").findAll(kotlin).map {
            RoleMapping(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }.toList()

    // ---------------------------------------------------------------------------------------------
    // Contrast math, mirroring ThemeContrastContractTest.
    // ---------------------------------------------------------------------------------------------

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
        if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)

    private fun resolve(colors: Map<String, Int>, name: String): Int =
        colors[name] ?: error("color $name missing from the palette")

    // ---------------------------------------------------------------------------------------------
    // Source and XML helpers.
    // ---------------------------------------------------------------------------------------------

    /** Parses one colors.xml into raw values: hex literals and @color/@android:color references. */
    private fun parseColors(text: String): Map<String, String> {
        val doc = parseXml(text)
        val raw = LinkedHashMap<String, String>()
        val nodes = doc.getElementsByTagName("color")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            raw[node.attributes.getNamedItem("name").nodeValue] = node.textContent.trim()
        }
        return raw
    }

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(text.byteInputStream())

    /** The <item> texts of one named array resource, in document order. */
    private fun arrayItems(doc: org.w3c.dom.Document, tag: String, name: String): List<String> {
        val nodes = doc.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == name) {
                val items = (node as org.w3c.dom.Element).getElementsByTagName("item")
                return (0 until items.length).map { items.item(it).textContent.trim() }
            }
        }
        error("array $name missing")
    }

    /** Drawable text with the license and note comments stripped: the structure that remains. */
    private fun normalizedDrawable(name: String): String =
        source("src/main/res/drawable/$name.xml")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun parseHex(value: String): Int {
        val hex = value.removePrefix("#")
        val argb = (if (hex.length == 6) "FF$hex" else hex).toLong(16)
        return argb.toInt()
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app", relative)).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative")

    private data class RoleMapping(val slot: String, val lightRole: String, val darkRole: String)

    private companion object {
        private const val AA_TEXT = 4.5
        private const val NON_TEXT_FLOOR = 3.0
        // Mirrors of the paint alpha constants, pinned against the sources by
        // ThemeContrastContractTest.theDimmingAlphasMatchTheirSourceConstants.
        private const val HEADER_ALPHA = 0xE6
        private const val HINT_ALPHA = 0xE6
        private const val SEARCH_HINT_ALPHA = 0xB0

        private const val PALETTE_KT =
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/DynamicThemePalette.kt"

        // Slots that stay fixed translucents on every tier (no framework role exists for them).
        private val FIXED_LITERAL_SLOTS = listOf(
            "dyn_key_shadow", "dyn_key_text_inactive_color", "dyn_key_hint_color")

        private val DRAWABLE_TWINS = listOf(
            "key_normal", "key_functional", "key_spacebar",
            "popup_key_background", "popup_panel_border", "key_preview_background")

        /**
         * The framework system colors as the API 31 platform ships them (AOSP frameworks/base
         * core/res values; unchanged through API 33). Real devices retone them from the
         * wallpaper — that variance is exactly why the pairs above keep an AA margin. Only the
         * roles the mapping uses are pinned.
         */
        private val FRAMEWORK_FIXTURES = mapOf(
            "system_neutral1_0" to 0xFFFFFFFF.toInt(),
            "system_neutral1_10" to 0xFFFCFCFF.toInt(),
            "system_neutral1_900" to 0xFF191C1E.toInt(),
            "system_neutral2_100" to 0xFFDCE3E9.toInt(),
            "system_neutral2_200" to 0xFFC0C7CD.toInt(),
            "system_neutral2_300" to 0xFFA5ACB2.toInt(),
            "system_neutral2_400" to 0xFF8A9297.toInt(),
            "system_neutral2_500" to 0xFF70777C.toInt(),
            "system_neutral2_600" to 0xFF585F65.toInt(),
            "system_neutral2_700" to 0xFF40484D.toInt(),
            "system_neutral2_800" to 0xFF2A3136.toInt(),
            "system_accent1_0" to 0xFFFFFFFF.toInt(),
            "system_accent1_100" to 0xFFC1E8FF.toInt(),
            "system_accent1_600" to 0xFF00668B.toInt(),
        )
    }
}
