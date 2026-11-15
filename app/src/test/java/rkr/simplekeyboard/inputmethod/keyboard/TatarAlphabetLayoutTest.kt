/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every Tatar alphabet letter is a directly tappable key of the Tatar layout, on the phone and
 * the tablet variants, with and without the number row. The extra top row carries the eight
 * letters the three ЙЦУКЕН rows do not have (ә ө ү җ ң һ ё ъ), and the direct keys do NOT take
 * away the long-press alternatives: е still offers ё and ь still offers ъ, beside the Tatar
 * partners the bilingual layout shares with Russian.
 *
 * This is a resource test, not a rendering test: it parses the shipped XML, so it runs on the
 * JVM. What it cannot prove is the on-device look (key labels, popup panels, thumb reach) — that
 * stays a hardware check.
 */
class TatarAlphabetLayoutTest {

    /** The 39 letters of the Tatar alphabet, lower case. */
    private val alphabet: List<Char> =
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList() + listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ')

    private val extraRowLetters = listOf('ә', 'ө', 'ү', 'җ', 'ң', 'һ', 'ё', 'ъ')

    private fun resRoot(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun children(element: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    private fun rows(rowsFile: File): List<Element> = children(parse(rowsFile)).filter { it.tagName == "Row" }

    private data class Key(val spec: Char, val moreKeys: List<Char>, val additionalMoreKeys: List<Char>)

    private fun specs(value: String?): List<Char> =
        value?.split(",")?.mapNotNull { it.singleOrNull() } ?: emptyList()

    private fun keysOf(element: Element): List<Key> =
        children(element).filter { it.tagName == "Key" }.mapNotNull { key ->
            val spec = key.getAttribute("latin:keySpec")
            // Escaped punctuation specs ("\?") and multi-code-point labels are not letter keys.
            if (spec.length != 1) return@mapNotNull null
            Key(
                spec.single(),
                specs(key.getAttribute("latin:moreKeys")),
                specs(key.getAttribute("latin:additionalMoreKeys")),
            )
        }

    /** The element a rowkeys file resolves to: the number-row case, the default, or the file. */
    private fun resolveBranch(root: Element, useNumberRow: Boolean): Element {
        val switch = children(root).firstOrNull { it.tagName == "switch" } ?: return root
        val branch = if (useNumberRow) {
            children(switch).firstOrNull {
                it.tagName == "case" && it.getAttribute("latin:showNumberRow") == "true"
            }
        } else {
            children(switch).firstOrNull { it.tagName == "default" }
        }
        return requireNotNull(branch) { "a <switch> must resolve for numberRow=$useNumberRow" }
    }

    /** The letter keys of one include target in the chosen variant. */
    private fun includeKeys(xmlDir: File, layoutRef: String, useNumberRow: Boolean = false): List<Key> {
        val file = File(xmlDir, layoutRef.substringAfterLast('/') + ".xml")
        assertTrue("$file must resolve", file.isFile)
        return keysOf(resolveBranch(parse(file), useNumberRow))
    }

    /** The direct letter keys of a rows file in the chosen variant. */
    private fun directLetters(rowsFile: File, xmlDir: File, useNumberRow: Boolean = false): Set<Char> {
        val letters = mutableSetOf<Char>()
        for (row in rows(rowsFile)) {
            for (child in children(row)) {
                when (child.tagName) {
                    "Key" -> {
                        val spec = child.getAttribute("latin:keySpec")
                        if (spec.length == 1) letters.add(spec.single())
                    }
                    "include" -> includeKeys(xmlDir, child.getAttribute("latin:keyboardLayout"),
                        useNumberRow).forEach { letters.add(it.spec) }
                }
            }
        }
        return letters
    }

    // --- Direct availability -----------------------------------------------------------------

    @Test
    fun theAlphabetIsThirtyNineLetters() {
        assertEquals(39, alphabet.size)
        assertEquals(39, alphabet.distinct().size)
    }

    @Test
    fun everyLetterIsADirectKeyOfThePhoneLayout() {
        val xmlDir = File(resRoot(), "xml")
        for (useNumberRow in listOf(false, true)) {
            val letters = directLetters(File(xmlDir, "rows_tatar.xml"), xmlDir, useNumberRow)
            for (letter in alphabet) {
                assertTrue("phone (numberRow=$useNumberRow): $letter has no direct key",
                    letter in letters)
            }
            assertEquals("phone (numberRow=$useNumberRow): exactly the alphabet",
                alphabet.toSet(), letters)
        }
    }

    @Test
    fun everyLetterIsADirectKeyOfTheTabletLayout() {
        // The tablet rows include the phone rowkeys through the resource fallback chain.
        val xmlDir = File(resRoot(), "xml")
        for (useNumberRow in listOf(false, true)) {
            val letters = directLetters(File(resRoot(), "xml-sw600dp/rows_tatar.xml"), xmlDir,
                useNumberRow)
            for (letter in alphabet) {
                assertTrue("tablet (numberRow=$useNumberRow): $letter has no direct key",
                    letter in letters)
            }
        }
    }

    @Test
    fun theExtraRowCarriesTheEightLettersTheHomeRowsDoNotHave() {
        val xmlDir = File(resRoot(), "xml")
        val keys = keysOf(parse(File(xmlDir, "rowkeys_tatar_extra.xml")))
        assertEquals("the extra row is eight keys", extraRowLetters, keys.map { it.spec })
        // Both new letters sit on the row itself, not only on a long-press popup.
        assertTrue('ё' in keys.map { it.spec })
        assertTrue('ъ' in keys.map { it.spec })
        // The extra row's keys carry no more-keys of their own: the alternatives live on the
        // home-row base keys, so nothing about the popup wiring changes here.
        assertTrue(keys.all { it.moreKeys.isEmpty() && it.additionalMoreKeys.isEmpty() })
    }

    @Test
    fun theNumberRowVariantKeepsTheSameDirectLetters() {
        // rowkeys_tatar1 switches on showNumberRow; both branches must offer the same letters —
        // only the digit hints differ.
        val xmlDir = File(resRoot(), "xml")
        val withNumberRow = includeKeys(xmlDir, "@xml/rowkeys_tatar1", useNumberRow = true)
        val withoutNumberRow = includeKeys(xmlDir, "@xml/rowkeys_tatar1", useNumberRow = false)
        assertEquals("the number-row case must not add or drop a letter",
            withoutNumberRow.map { it.spec }, withNumberRow.map { it.spec })
        assertTrue("the first row keeps е, whose long press still gives ё",
            'е' in withoutNumberRow.map { it.spec })
    }

    // --- The long-press alternatives survive --------------------------------------------------

    @Test
    fun theBaseKeysKeepTheirLongPressPartnersInEveryBranch() {
        val xmlDir = File(resRoot(), "xml")
        val expected = mapOf(
            "@xml/rowkeys_tatar1" to mapOf('у' to 'ү', 'е' to 'ё', 'н' to 'ң', 'г' to 'һ', 'х' to 'һ'),
            "@xml/rowkeys_tatar2" to mapOf('а' to 'ә', 'о' to 'ө', 'ж' to 'җ', 'э' to 'ә'),
            "@xml/rowkeys_tatar3" to mapOf('ь' to 'ъ'),
        )
        for ((include, partners) in expected) {
            for (useNumberRow in listOf(false, true)) {
                val keys = includeKeys(xmlDir, include, useNumberRow)
                for ((base, partner) in partners) {
                    val key = keys.firstOrNull { it.spec == base }
                        ?: error("$include (numberRow=$useNumberRow): no $base key")
                    assertTrue(
                        "$include (numberRow=$useNumberRow): $base must keep $partner on long press",
                        partner in key.moreKeys)
                }
            }
        }
    }

    @Test
    fun theRussianLayoutKeepsTheSharedLongPressPartners() {
        // The Russian rows are byte-identical to the Tatar ones (RowkeysSyncTest), so the same
        // partners must resolve there too — the bilingual promise did not move.
        val xmlDir = File(resRoot(), "xml")
        val keys = includeKeys(xmlDir, "@xml/rowkeys_russian3")
        val softSign = keys.first { it.spec == 'ь' }
        assertTrue("Russian ь keeps ъ on long press", 'ъ' in softSign.moreKeys)
        val firstRow = includeKeys(xmlDir, "@xml/rowkeys_russian1")
        assertTrue("Russian е keeps ё on long press",
            'ё' in firstRow.first { it.spec == 'е' }.moreKeys)
        // The Russian layout has no extra row: ё and ъ stay long-press-only there by design.
        val russianLetters = directLetters(File(xmlDir, "rows_russian.xml"), xmlDir)
        assertTrue("the Russian layout must not grow the Tatar extra row", 'ә' !in russianLetters)
        assertTrue("the Russian layout keeps ё on long press only", 'ё' !in russianLetters)
    }

    // --- Geometry budgets ---------------------------------------------------------------------

    @Test
    fun thePhoneExtraRowFillsTheWidthAtAnEvenPitch() {
        val firstRow = rows(File(resRoot(), "xml/rows_tatar.xml")).first()
        val width = firstRow.getAttribute("latin:keyWidth")
        assertEquals("the extra row pitch is 12.5%p", "12.5%p", width)
        val keys = keysOf(parse(File(resRoot(), "xml/rowkeys_tatar_extra.xml")))
        assertEquals(100.0, keys.size * width.removeSuffix("%p").toDouble(), 0.0001)
    }

    @Test
    fun theTabletExtraRowStaysCenteredAtTheCommonPitch() {
        val firstRow = rows(File(resRoot(), "xml-sw600dp/rows_tatar.xml")).first()
        val include = children(firstRow).single { it.tagName == "include" }
        assertEquals("@xml/rowkeys_tatar_extra", include.getAttribute("latin:keyboardLayout"))
        val xPos = include.getAttribute("latin:keyXPos").removeSuffix("%p").toDouble()
        val width = include.getAttribute("latin:keyWidth").removeSuffix("%p").toDouble()
        val keys = keysOf(parse(File(resRoot(), "xml/rowkeys_tatar_extra.xml")))
        val occupied = keys.size * width
        assertEquals("the eight tablet keys stay centered: left and right margins match",
            xPos, 100.0 - xPos - occupied, 0.0001)
        assertEquals("the tablet extra row keeps the common letter pitch", 8.0, width, 0.0001)
    }

    @Test
    fun theOnboardingGuideNamesTheSameExtraRowAsTheLayout() {
        // The setup guide is the only place a user reads the row aloud: its letters must be the
        // row's keySpecs, in the same order, in every shipped locale — not a stale copy.
        val layoutLetters = keysOf(parse(File(resRoot(), "xml/rowkeys_tatar_extra.xml")))
            .map { it.spec }
        for (folder in listOf("values", "values-ru", "values-tt")) {
            val strings = File(resRoot(), "$folder/strings-setup.xml").readText()
            val line = strings.substringAfter("<string name=\"setup_guide_layout\">")
                .substringBefore("</string>")
            // The row listing sits between the locale's ": " and the " —" that starts the
            // explanation, so a letter added to or dropped from the row fails here in all three.
            val rowText = line.substringAfter(": ").substringBefore(" —").trim()
            assertEquals("$folder: the guide must name exactly the extra row, in layout order",
                layoutLetters.joinToString(" "), rowText.split(Regex("\\s+")).joinToString(" "))
        }
    }

    @Test
    fun theGoldenGeometryCoversTheSameAlphabet() {
        // The committed interchange artifact the parity suite consumes must describe the same
        // 39 direct keys; KeyGeometryTsvTest pins its bytes against this layout.
        val tsv = listOf(
            File("src/test/resources/golden/keys-tt.tsv"),
            File("app/src/test/resources/golden/keys-tt.tsv"),
        ).firstOrNull(File::isFile) ?: error("cannot locate keys-tt.tsv")
        val letters = tsv.readLines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t').toInt(16) }
        assertEquals("the golden TSV holds one row per direct letter key", 39, letters.size)
        for (letter in alphabet) {
            assertTrue("keys-tt.tsv misses $letter", letter.code in letters)
        }
        assertTrue("ё is a direct key in the golden geometry", 'ё'.code in letters)
        assertTrue("ъ is a direct key in the golden geometry", 'ъ'.code in letters)
    }
}
