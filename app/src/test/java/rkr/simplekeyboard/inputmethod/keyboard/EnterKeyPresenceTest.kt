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

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural contract: every SHIPPED layout must have the three keys a keyboard cannot work
 * without — Enter, delete and shift — at EVERY screen bucket the app has resources for.
 *
 * This test exists because of defect Д-2 (`docs/DEVICE-UAT-1.9.12.md`): at smallest width >= 600dp
 * the Tatar and Russian layouts had NO Enter key at all. The mechanism was invisible in any single
 * file — `xml/rows_tatar.xml` and `xml/rows_russian.xml` take Enter from `@xml/row_qwerty4`, and
 * on sw600dp that include resolves to `xml-sw600dp/row_qwerty4.xml`, which ends in a `<Spacer>`
 * because the tablet QWERTY moves Enter into its second row through a tablet `rows_qwerty.xml`
 * that the Cyrillic layouts never got. Neither file is wrong on its own; only the resolved
 * combination is. So the assertion has to walk the includes the way the resource system does.
 *
 * The walk models Android's qualifier precedence: smallestWidth outranks orientation, so a tablet
 * in landscape prefers `xml-sw600dp` over `xml-land`.
 */
class EnterKeyPresenceTest {

    private companion object {
        /** Layout names of the three shipped subtypes — see `keyboard_layout_set_*.xml`. */
        private val SHIPPED_LAYOUTS = listOf("tatar", "russian", "qwerty")

        /** Resource-directory fallback chains, most specific first. */
        private val BUCKETS = linkedMapOf(
            "телефон, портрет" to listOf("xml"),
            "телефон, ландшафт" to listOf("xml-land", "xml"),
            "планшет, портрет" to listOf("xml-sw600dp", "xml"),
            "планшет, ландшафт" to listOf("xml-sw600dp-land", "xml-sw600dp", "xml-land", "xml"),
        )

        private const val ENTER = "enterKeyStyle"
        private const val DELETE = "deleteKeyStyle"
        private const val SHIFT = "shiftKeyStyle"
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate src/main/res from ${File(".").absolutePath}")
    }

    /** Resolves `@xml/<name>` against a fallback chain, exactly like the resource system. */
    private fun resolve(name: String, chain: List<String>): File? {
        val root = resRoot()
        for (dir in chain) {
            val file = File(File(root, dir), "$name.xml")
            if (file.isFile) return file
        }
        return null
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun descendants(element: Element): List<Element> {
        val result = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        stack.addLast(element)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            result.add(current)
            val children = current.childNodes
            for (index in 0 until children.length) {
                (children.item(index) as? Element)?.let(stack::addLast)
            }
        }
        return result
    }

    /**
     * Every `latin:keyStyle` reachable from [name] at this bucket, following includes. Cycles and
     * repeats are cut by [visited]; a missing include target is reported rather than skipped,
     * because a typo in a layout reference must not read as "the key is simply absent".
     */
    private fun keyStyles(
        name: String,
        chain: List<String>,
        visited: MutableSet<String>,
        missing: MutableList<String>,
    ): Set<String> {
        if (!visited.add(name)) return emptySet()
        val file = resolve(name, chain) ?: run { missing.add(name); return emptySet() }
        val styles = HashSet<String>()
        for (element in descendants(parse(file))) {
            element.getAttribute("latin:keyStyle").takeIf { it.isNotEmpty() }?.let(styles::add)
            val include = element.getAttribute("latin:keyboardLayout")
            if (include.startsWith("@xml/")) {
                styles += keyStyles(include.removePrefix("@xml/"), chain, visited, missing)
            }
        }
        return styles
    }

    private fun stylesOf(layout: String, chain: List<String>): Set<String> {
        val missing = ArrayList<String>()
        val styles = keyStyles("kbd_$layout", chain, HashSet(), missing)
        assertTrue(
            "неразрешённые include в раскладке $layout при цепочке $chain: $missing",
            missing.isEmpty(),
        )
        return styles
    }

    @Test
    fun everyShippedLayoutHasEnterAtEveryBucket() {
        val failures = ArrayList<String>()
        for (layout in SHIPPED_LAYOUTS) {
            for ((bucket, chain) in BUCKETS) {
                if (ENTER !in stylesOf(layout, chain)) {
                    failures.add("$layout / $bucket")
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("нет клавиши Enter: ${failures.joinToString("; ")}")
        }
    }

    @Test
    fun everyShippedLayoutHasDeleteAndShiftAtEveryBucket() {
        val failures = ArrayList<String>()
        for (layout in SHIPPED_LAYOUTS) {
            for ((bucket, chain) in BUCKETS) {
                val styles = stylesOf(layout, chain)
                if (DELETE !in styles) failures.add("$layout / $bucket: нет delete")
                if (SHIFT !in styles) failures.add("$layout / $bucket: нет shift")
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString("; "))
        }
    }

    /**
     * The Cyrillic layouts must go through their OWN bottom row. Pins the seam itself: pointing
     * `rows_tatar`/`rows_russian` back at `@xml/row_qwerty4` would silently restore Д-2 on every
     * tablet, and the test above would catch it — but only this one says where to look.
     */
    @Test
    fun cyrillicLayoutsUseTheirOwnBottomRow() {
        for (name in listOf("rows_tatar", "rows_russian")) {
            val text = resolve(name, listOf("xml"))!!.readText()
            assertTrue(
                "$name должен включать @xml/row_cyrillic4",
                text.contains("@xml/row_cyrillic4"),
            )
            assertTrue(
                "$name не должен включать @xml/row_qwerty4 напрямую",
                !text.contains("@xml/row_qwerty4"),
            )
        }
    }

    /**
     * The tablet Cyrillic bottom row keeps the width budget the Spacer used to hold:
     * 10 + 9 + [63] + 9 + 9 = 100 %p (the invariant is written down in `key_space_7kw.xml`).
     * Enter takes the trailing 9 %p through `fillRight`, so the row still adds up.
     */
    @Test
    fun tabletCyrillicBottomRowReplacesSpacerWithEnter() {
        val file = resolve("row_cyrillic4", listOf("xml-sw600dp"))!!
        val elements = descendants(parse(file))
        val styles = elements.mapNotNull { it.getAttribute("latin:keyStyle").ifEmpty { null } }
        assertTrue("планшетный row_cyrillic4 обязан нести enterKeyStyle", ENTER in styles)
        assertTrue(
            "Spacer должен быть заменён клавишей Enter, а не соседствовать с ней",
            elements.none { it.tagName == "Spacer" },
        )
        val enter = elements.single { it.getAttribute("latin:keyStyle") == ENTER }
        assertTrue(
            "Enter обязан занимать остаток ряда (fillRight), иначе сумма ширин ломается",
            enter.getAttribute("latin:keyWidth") == "fillRight",
        )
    }
}
