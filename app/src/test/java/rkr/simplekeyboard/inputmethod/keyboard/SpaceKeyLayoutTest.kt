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

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The bottom row's width budget, checked on the XML alone.
 *
 * The test re-implements the first-match &lt;switch&gt;/&lt;case&gt; evaluation of
 * [rkr.simplekeyboard.inputmethod.keyboard.internal.KeyboardBuilder] purely on the XML, so it does
 * not need Android. For each file it checks:
 *  - exactly eight &lt;case&gt; are present (zwnj × globe × showEmojiKey);
 *  - **every** case spends exactly the width the row lends the include, whatever keys it lays out
 *    — this is the invariant that keeps the whole bottom row from skewing, and it is checked for
 *    all eight cases rather than only for the numbers that happen to be there today;
 *  - with showEmojiKey=false the parsed keys are byte-for-byte the pre-emoji layout across the four
 *    (globe × zwnj) combinations;
 *  - with showEmojiKey=true and no globe the emoji key appears left of the space bar and takes
 *    exactly the row's default width out of the space bar;
 *  - with showEmojiKey=true **and** the globe enabled there is no emoji key at all: the row has no
 *    width left for a second function key, so the space bar keeps its full width and the emoji
 *    panel moves onto the comma key's long press (see key_styles_settings.xml). Without this the
 *    space bar drops to 30%p — 111dp on a 1080×2280 phone, with its centre 6mm right of the screen
 *    centre — which is the regression this test exists to prevent.
 */
class SpaceKeyLayoutTest {

    private data class Key(val style: String, val width: Double?)

    private companion object {
        // A representative layout set from the zwnj list, and one outside it.
        private const val ZWNJ = "farsi"
        private const val NON_ZWNJ = "qwerty"
        private const val LANG = "languageSwitchKeyStyle"
        private const val EMOJI = "emojiKeyStyle"
        private const val SPACE = "spaceKeyStyle"
        private const val ZWNJ_KEY = "zwnjKeyStyle"
        private const val EMOJI_MORE_KEY = "!icon/emoji_key|!code/key_emoji"

        /**
         * What the enclosing row lends each include, and the width a key with no keyWidth of its
         * own takes. Both come from the row files that include these layouts:
         *  - res/xml/row_qwerty4.xml          Row keyWidth 10%p, 15+10+[50]+10+15 = 100
         *  - res/xml-sw600dp/row_qwerty4.xml  Row keyWidth  9%p, 10+9+[63]+9+9   = 100
         *
         * The Cyrillic layouts (tatar, russian) go through res/xml/row_cyrillic4.xml, which on the
         * phone delegates to row_qwerty4.xml and on sw600dp repeats the tablet row with the
         * trailing Spacer replaced by the Enter key — the same 9%p, so both budgets below are
         * unchanged by it.
         */
        private val BUDGET = mapOf(
            "key_space_5kw.xml" to Geometry(defaultKeyWidth = 10.0, total = 50.0),
            "key_space_7kw.xml" to Geometry(defaultKeyWidth = 9.0, total = 63.0),
        )
    }

    private data class Geometry(val defaultKeyWidth: Double, val total: Double)

    private fun spaceFile(name: String): File {
        val candidates = listOf(File("src/main/res/xml"), File("app/src/main/res/xml"))
        val dir = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate res/xml from ${File(".").absolutePath}")
        return File(dir, name)
    }

    private fun switchElement(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(file)
        val switches = doc.getElementsByTagName("switch")
        assertTrue("no <switch> in ${file.name}", switches.length == 1)
        return switches.item(0) as Element
    }

    private fun childElements(parent: Element): List<Element> {
        val result = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let { result.add(it) }
        }
        return result
    }

    private fun attrOrNull(el: Element, name: String): String? {
        val value = el.getAttribute(name)
        return if (value.isEmpty()) null else value
    }

    private fun keysOf(caseEl: Element): List<Key> =
        childElements(caseEl).filter { it.tagName == "Key" }.map { key ->
            val widthAttr = attrOrNull(key, "latin:keyWidth")
            Key(
                key.getAttribute("latin:keyStyle"),
                widthAttr?.removeSuffix("%p")?.toDouble(),
            )
        }

    /** First-match evaluation, exactly like KeyboardBuilder.parseCaseCondition. */
    private fun evaluate(
        switchEl: Element,
        layoutSet: String,
        globe: Boolean,
        emoji: Boolean,
    ): List<Key>? {
        for (child in childElements(switchEl)) {
            when (child.tagName) {
                "case" -> {
                    val ls = attrOrNull(child, "latin:keyboardLayoutSet")
                    val lsMatch = ls == null || ls.split("|").contains(layoutSet)
                    val g = attrOrNull(child, "latin:languageSwitchKeyEnabled")
                    val gMatch = g == null || g.toBoolean() == globe
                    val e = attrOrNull(child, "latin:showEmojiKey")
                    val eMatch = e == null || e.toBoolean() == emoji
                    if (lsMatch && gMatch && eMatch) return keysOf(child)
                }
                "default" -> return keysOf(child)
            }
        }
        return null
    }

    private fun caseCount(switchEl: Element): Int =
        childElements(switchEl).count { it.tagName == "case" }

    @Test
    fun eachFileHasExactlyEightCases() {
        assertEquals(8, caseCount(switchElement(spaceFile("key_space_5kw.xml"))))
        assertEquals(8, caseCount(switchElement(spaceFile("key_space_7kw.xml"))))
    }

    @Test
    fun phoneDisabledToggleIsIdenticalToPreE2() {
        val s = switchElement(spaceFile("key_space_5kw.xml"))
        assertEquals(
            listOf(Key(LANG, null), Key(SPACE, 30.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = true, emoji = false),
        )
        assertEquals(
            listOf(Key(SPACE, 40.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = false, emoji = false),
        )
        assertEquals(
            listOf(Key(LANG, null), Key(SPACE, 40.0)),
            evaluate(s, NON_ZWNJ, globe = true, emoji = false),
        )
        assertEquals(
            listOf(Key(SPACE, 50.0)),
            evaluate(s, NON_ZWNJ, globe = false, emoji = false),
        )
    }

    @Test
    fun tabletDisabledToggleIsIdenticalToPreE2() {
        val s = switchElement(spaceFile("key_space_7kw.xml"))
        assertEquals(
            listOf(Key(LANG, null), Key(SPACE, 45.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = true, emoji = false),
        )
        assertEquals(
            listOf(Key(SPACE, 54.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = false, emoji = false),
        )
        assertEquals(
            listOf(Key(LANG, null), Key(SPACE, 54.0)),
            evaluate(s, NON_ZWNJ, globe = true, emoji = false),
        )
        assertEquals(
            listOf(Key(SPACE, 63.0)),
            evaluate(s, NON_ZWNJ, globe = false, emoji = false),
        )
    }

    @Test
    fun phoneEnabledToggleAddsTenPercentEmojiKeyOnlyWhenTheGlobeIsOff() {
        val s = switchElement(spaceFile("key_space_5kw.xml"))
        // No globe: a 10%p emoji key, taken out of the space bar.
        assertEquals(
            listOf(Key(EMOJI, null), Key(SPACE, 30.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = false, emoji = true),
        )
        assertEquals(
            listOf(Key(EMOJI, null), Key(SPACE, 40.0)),
            evaluate(s, NON_ZWNJ, globe = false, emoji = true),
        )
    }

    @Test
    fun phoneEnabledToggleKeepsTheFullSpaceBarWhenTheGlobeIsOn() {
        val s = switchElement(spaceFile("key_space_5kw.xml"))
        // The globe already spends the row's spare key, so the emoji key is dropped and the space
        // bar is exactly as wide as with the emoji toggle off. Emoji move to the comma long press.
        for (layout in listOf(ZWNJ, NON_ZWNJ)) {
            assertEquals(
                "globe on: emoji=true must lay out the same keys as emoji=false [$layout]",
                evaluate(s, layout, globe = true, emoji = false),
                evaluate(s, layout, globe = true, emoji = true),
            )
        }
        assertFalse(
            "no emoji key may share the row with the globe",
            evaluate(s, NON_ZWNJ, globe = true, emoji = true)!!.any { it.style == EMOJI },
        )
    }

    @Test
    fun tabletEnabledToggleAddsNinePercentEmojiKeyOnlyWhenTheGlobeIsOff() {
        val s = switchElement(spaceFile("key_space_7kw.xml"))
        // No globe: a 9%p emoji key, taken out of the space bar.
        assertEquals(
            listOf(Key(EMOJI, null), Key(SPACE, 45.0), Key(ZWNJ_KEY, null)),
            evaluate(s, ZWNJ, globe = false, emoji = true),
        )
        assertEquals(
            listOf(Key(EMOJI, null), Key(SPACE, 54.0)),
            evaluate(s, NON_ZWNJ, globe = false, emoji = true),
        )
    }

    @Test
    fun tabletEnabledToggleKeepsTheFullSpaceBarWhenTheGlobeIsOn() {
        val s = switchElement(spaceFile("key_space_7kw.xml"))
        for (layout in listOf(ZWNJ, NON_ZWNJ)) {
            assertEquals(
                "globe on: emoji=true must lay out the same keys as emoji=false [$layout]",
                evaluate(s, layout, globe = true, emoji = false),
                evaluate(s, layout, globe = true, emoji = true),
            )
        }
        assertFalse(
            "no emoji key may share the row with the globe",
            evaluate(s, NON_ZWNJ, globe = true, emoji = true)!!.any { it.style == EMOJI },
        )
    }

    /**
     * The invariant the whole bottom row rests on: whatever keys a case lays out, their widths add
     * up to the slice the row lends the include. A case that spends too little or too much skews
     * every key to its right, so this is checked for all eight cases in both files rather than for
     * the current numbers only.
     */
    @Test
    fun everyCaseSpendsExactlyItsWidthBudget() {
        for ((file, geometry) in BUDGET) {
            val s = switchElement(spaceFile(file))
            for (layout in listOf(ZWNJ, NON_ZWNJ)) {
                for (globe in listOf(true, false)) {
                    for (emoji in listOf(true, false)) {
                        val keys = evaluate(s, layout, globe, emoji)
                            ?: error("$file [$layout globe=$globe emoji=$emoji] matched no case")
                        val spent = keys.sumOf { it.width ?: geometry.defaultKeyWidth }
                        assertEquals(
                            "$file [$layout globe=$globe emoji=$emoji] spends $spent%p " +
                                "of the ${geometry.total}%p the row lends it: $keys",
                            geometry.total,
                            spent,
                            1e-9,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun everyCombinationResolvesToACase() {
        for (file in listOf("key_space_5kw.xml", "key_space_7kw.xml")) {
            val s = switchElement(spaceFile(file))
            for (layout in listOf(ZWNJ, NON_ZWNJ)) {
                for (globe in listOf(true, false)) {
                    for (emoji in listOf(true, false)) {
                        assertNotNull(
                            "$file [$layout globe=$globe emoji=$emoji] matched no case",
                            evaluate(s, layout, globe, emoji),
                        )
                    }
                }
            }
        }
    }

    /**
     * The other half of the trade: when the globe pushes the emoji key out of the row, the emoji
     * panel must still be reachable, and visibly so. Both live in the comma key's shared style
     * (res/xml/key_styles_settings.xml) — the emoji more key first, because the first more key is
     * the one laid out under the finger, so a long press and release opens the panel with no
     * sliding; and a hint glyph, so the long press is discoverable rather than secret.
     */
    @Test
    fun commaKeyCarriesTheEmojiPanelExactlyWhenTheRowHasNoEmojiKey() {
        val file = File(spaceFile("key_space_5kw.xml").parentFile, "key_styles_settings.xml")
        val switchEl = switchElement(file)
        val styles = childElements(switchEl).map { branch ->
            val style = childElements(branch).single { it.tagName == "key-style" }
            Triple(
                branch.tagName to attrOrNull(branch, "latin:clobberSettingsKey"),
                attrOrNull(style, "latin:additionalMoreKeys").orEmpty(),
                attrOrNull(style, "latin:keyHintLabel"),
            )
        }
        val withEmoji = styles.filter { it.second.startsWith(EMOJI_MORE_KEY) }
        assertEquals("exactly one branch may offer the emoji panel", 1, withEmoji.size)

        val (branch, moreKeys, hint) = withEmoji.single()
        assertEquals("it must be a <case>, not the <default>", "case", branch.first)
        // ...and it must be the very case in which the space-bar layouts drop the emoji key:
        // globe on, emoji asked for. Guarded here so the two files cannot drift apart.
        val caseEl = childElements(switchEl).single { el ->
            childElements(el).any { style ->
                attrOrNull(style, "latin:additionalMoreKeys").orEmpty().startsWith(EMOJI_MORE_KEY)
            }
        }
        assertEquals("true", attrOrNull(caseEl, "latin:languageSwitchKeyEnabled"))
        assertEquals("true", attrOrNull(caseEl, "latin:showEmojiKey"))
        assertTrue(
            "paste and settings must survive as later more keys: $moreKeys",
            moreKeys.contains("!text/keyspec_settings"),
        )
        assertNotNull("the long press needs a visible hint on the key", hint)
    }

    @Test
    fun emojiKeyStyleIsUsedOnlyBySpaceBarLayouts() {
        val xmlDir = spaceFile("key_space_5kw.xml").parentFile
            ?: error("res/xml has no parent directory")
        val users = xmlDir.listFiles { f -> f.extension == "xml" }.orEmpty()
            .filter { it.readText().contains("keyStyle=\"emojiKeyStyle\"") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("key_space_5kw.xml", "key_space_7kw.xml"), users)
    }
}
