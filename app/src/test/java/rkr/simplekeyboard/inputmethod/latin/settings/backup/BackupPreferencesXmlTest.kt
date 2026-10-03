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

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The settings document of the backup archive: round-trip of every SharedPreferences value type,
 * the deterministic output, and the strict parser's rejections.
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
}
