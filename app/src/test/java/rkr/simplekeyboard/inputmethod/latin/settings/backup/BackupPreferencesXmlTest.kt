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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The settings document of the backup archive: round-trip of every SharedPreferences value type,
 * the deterministic output, the strict parser's rejections, and the preference-key schema
 * (unknown keys dropped, a wrong-typed known key failed).
 */
class BackupPreferencesXmlTest {

    @Test
    fun everyValueTypeRoundTrips() {
        val values = mapOf<String, Any?>(
            "auto_cap" to true,
            "pref_bottom_offset_portrait" to -12,
            "long_key" to 9_876_543_210L,
            "pref_keyboard_height" to 1.25f,
            "pref_enabled_subtypes" to "tt_RU;ru",
            "a_set" to linkedSetOf("alpha", "beta"),
            "empty_string" to "",
            "empty_set" to emptySet<String>(),
        )
        assertEquals(values, BackupPreferencesXml.parse(BackupPreferencesXml.serialize(values)))
    }

    @Test
    fun escapingRoundTrips() {
        val values = mapOf<String, Any?>(
            "markup" to "<tag attr=\"v\">&amp;</tag> 'quoted'",
            "emoji" to "🎉 сәләм",
        )
        assertEquals(values, BackupPreferencesXml.parse(BackupPreferencesXml.serialize(values)))
    }

    @Test
    fun valuesXmlCannotRepresentAreSkipped() {
        val values = mapOf<String, Any?>("good" to "kept", "control" to "a\u0007b")
        assertEquals(mapOf<String, Any>("good" to "kept"),
            BackupPreferencesXml.parse(BackupPreferencesXml.serialize(values)))
    }

    @Test
    fun outputIsDeterministic() {
        val first = mapOf<String, Any?>("b" to 2, "a" to 1, "c" to 3)
        val second = mapOf<String, Any?>("c" to 3, "b" to 2, "a" to 1)
        assertEquals(
            BackupPreferencesXml.serialize(first).toList(),
            BackupPreferencesXml.serialize(second).toList(),
        )
    }

    @Test
    fun unsupportedValuesAreSkipped() {
        val values = mapOf<String, Any?>("good" to 1, "bad" to Any(), "alsoBad" to 2.5)
        assertEquals(mapOf<String, Any>("good" to 1),
            BackupPreferencesXml.parse(BackupPreferencesXml.serialize(values)))
    }

    @Test
    fun rejectsDoctype() {
        assertRejected("""<?xml version="1.0"?><!DOCTYPE map [<!ENTITY x "y">]><map/>""")
    }

    @Test
    fun rejectsUnknownElement() {
        assertRejected("<map><blob name=\"a\" /></map>")
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertRejected("<map><int name=\"a\" value=\"1\" /><int name=\"a\" value=\"2\" /></map>")
    }

    @Test
    fun rejectsMissingValueAttribute() {
        assertRejected("<map><int name=\"a\" /></map>")
    }

    @Test
    fun rejectsBadInt() {
        assertRejected("<map><int name=\"a\" value=\"1.5\" /></map>")
    }

    @Test
    fun rejectsNonMapRoot() {
        assertRejected("<string name=\"a\">x</string>")
    }

    @Test
    fun rejectsTextInsideMap() {
        assertRejected("<map>words</map>")
    }

    @Test
    fun rejectsUnknownEntity() {
        assertRejected("<map><string name=\"a\">&nbsp;</string></map>")
    }

    @Test
    fun rejectsCdata() {
        assertRejected("<map><string name=\"a\"><![CDATA[x]]></string></map>")
    }

    @Test
    fun rejectsTrailingContent() {
        assertRejected("<map></map><map></map>")
    }

    @Test
    fun acceptsOwnDeclarationAndComments() {
        val parsed = BackupPreferencesXml.parse(
            ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<!-- a comment --><map><boolean name=\"b\" value=\"true\" /></map>\n")
                .toByteArray(Charsets.UTF_8))
        assertEquals(mapOf<String, Any>("b" to true), parsed)
    }

    @Test
    fun realFrameworkDocumentParses() {
        // The shape SharedPreferences itself writes, attributes in the framework's order.
        val document = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <boolean name="auto_cap" value="true" />
                <int name="pref_key_longpress_timeout" value="300" />
                <string name="pref_enabled_subtypes">tt_RU;ru</string>
            </map>
        """.trimIndent()
        val parsed = BackupPreferencesXml.parse(document.toByteArray(Charsets.UTF_8))
        assertEquals(true, parsed["auto_cap"])
        assertEquals(300, parsed["pref_key_longpress_timeout"])
        assertEquals("tt_RU;ru", parsed["pref_enabled_subtypes"])
    }

    private fun assertRejected(document: String) {
        try {
            BackupPreferencesXml.parse(document.toByteArray(Charsets.UTF_8))
            fail("must reject: $document")
        } catch (expected: BackupPreferencesXml.PreferencesXmlException) {
        }
    }

    // ---- non-finite floats ------------------------------------------------------------------

    @Test
    fun rejectsNonFiniteFloats() {
        // The writer only produces finite values; NaN, the infinities and an overflow parse but
        // must fail.
        assertRejected("<map><float name=\"a\" value=\"NaN\" /></map>")
        assertRejected("<map><float name=\"a\" value=\"Infinity\" /></map>")
        assertRejected("<map><float name=\"a\" value=\"-Infinity\" /></map>")
        assertRejected("<map><float name=\"a\" value=\"1e40\" /></map>")
    }

    // ---- the preference-key schema ------------------------------------------------------------

    @Test
    fun schemaDropsUnknownKeys() {
        // A key no current build knows (a since-removed one, or a newer build's) is dropped, not
        // failed.
        val parsed = parseDoc(
            "<map><boolean name=\"auto_cap\" value=\"false\" />" +
                "<boolean name=\"pref_fifth_row_arm\" value=\"true\" /></map>")
        assertEquals(mapOf<String, Any>("auto_cap" to false), BackupPreferenceSchema.applyTo(parsed))
    }

    @Test
    fun schemaKeepsKnownKeysAtTheirTypes() {
        val parsed = parseDoc(
            "<map>" +
                "<boolean name=\"auto_cap\" value=\"true\" />" +
                "<int name=\"pref_key_longpress_timeout\" value=\"300\" />" +
                "<int name=\"pref_bottom_offset_portrait\" value=\"-12\" />" +
                "<float name=\"pref_keyboard_height\" value=\"1.15\" />" +
                "<string name=\"pref_enabled_subtypes\">tt_RU;ru</string>" +
                "<string name=\"pref_keyboard_theme_20140509\">7</string>" +
                "<int name=\"pref_keyboard_color\" value=\"-16776961\" />" +
                "</map>")
        assertEquals(parsed, BackupPreferenceSchema.applyTo(parsed))
    }

    @Test
    fun schemaRejectsAKnownKeyWithAWrongType() {
        // Each document is syntactically fine; the schema must refuse it whole.
        val documents = listOf(
            "<map><string name=\"auto_cap\">x</string></map>",
            "<map><int name=\"auto_cap\" value=\"1\" /></map>",
            "<map><boolean name=\"pref_keyboard_height\" value=\"true\" /></map>",
            "<map><string name=\"pref_bottom_offset_portrait\">5</string></map>",
            "<map><long name=\"pref_key_longpress_timeout\" value=\"5\" /></map>",
            "<map><set name=\"pref_enabled_subtypes\"></set></map>",
        )
        for (document in documents) {
            try {
                BackupPreferenceSchema.applyTo(parseDoc(document))
                fail("must reject: $document")
            } catch (expected: BackupPreferencesXml.PreferencesXmlException) {
            }
        }
    }

    @Test
    fun schemaPinsTheValueTypeOfEveryKey() {
        val boolean = BackupPreferenceSchema.ValueType.BOOLEAN
        val int = BackupPreferenceSchema.ValueType.INT
        val float = BackupPreferenceSchema.ValueType.FLOAT
        val string = BackupPreferenceSchema.ValueType.STRING
        val expected = mapOf(
            "auto_cap" to boolean,
            "vibrate_on" to boolean,
            "sound_on" to boolean,
            "popup_on" to boolean,
            "pref_show_language_switch_key" to boolean,
            "pref_use_on_screen" to boolean,
            "pref_enable_ime_switch" to boolean,
            "pref_show_special_chars" to boolean,
            "pref_show_number_row" to boolean,
            "pref_show_emoji_key" to boolean,
            "pref_space_swipe" to boolean,
            "pref_delete_swipe" to boolean,
            "pref_glide_typing" to boolean,
            "pref_tatar_suggestions" to boolean,
            "pref_personal_dictionary" to boolean,
            "pref_tatar_autocorrect" to boolean,
            "pref_emoji_suggestions" to boolean,
            "pref_incognito_mode" to boolean,
            "pref_tatar_suggestions_offer_spent" to boolean,
            "pref_key_longpress_timeout" to int,
            "pref_one_handed_side" to int,
            "pref_bottom_offset_portrait" to int,
            "pref_keyboard_color" to int,
            "pref_keypress_sound_volume" to float,
            "pref_keyboard_height" to float,
            "pref_emoji_panel_height" to float,
            "pref_enabled_subtypes" to string,
            "pref_current_subtype" to string,
            // The theme id crosses as a decimal string, as KeyboardTheme writes it.
            "pref_keyboard_theme_20140509" to string,
        )
        assertEquals(expected, BackupPreferenceSchema.KEY_TYPES)
    }

    @Test
    fun schemaCoversEveryCurrentPreferenceKey() {
        // The PREF_* constants of Settings.java plus KeyboardTheme's key are the whole key set.
        val settings = readSource("rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
        val prefKeys = Regex("""String\s+PREF_\w+\s*=\s*"([^"]+)"""")
            .findAll(settings).map { it.groupValues[1] }.toSet()
        assertTrue("no PREF_ constants found in Settings.java", prefKeys.isNotEmpty())
        val theme = readSource("rkr/simplekeyboard/inputmethod/keyboard/KeyboardTheme.java")
        val themeKey = Regex("""KEYBOARD_THEME_KEY\s*=\s*"([^"]+)"""")
            .find(theme)?.groupValues?.get(1) ?: error("KEYBOARD_THEME_KEY not found")
        assertEquals(prefKeys + themeKey, BackupPreferenceSchema.KEY_TYPES.keys)
    }

    private fun parseDoc(document: String): Map<String, Any> =
        BackupPreferencesXml.parse(document.toByteArray(Charsets.UTF_8))

    private fun readSource(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/java from ${File(".").absolutePath}")
        val file = File(root, relative)
        assertTrue("$relative missing", file.isFile)
        return file.readText()
    }
}
