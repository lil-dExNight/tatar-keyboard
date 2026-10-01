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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Without the number row, the first ten keys of the top Tatar row offer `1`–`0` on long press.
 * A key with a letter partner keeps that letter as its hint and first more key: its `moreKeys`
 * ends in the `%` marker, which `MoreKeySpec.insertAdditionalMoreKeys` replaces with the digit;
 * without the marker the digit would go first and become the long-press default. The number-row
 * branch carries no digits, and both branches have the same keys in the same order. The Russian
 * row is the same file (`RowkeysSyncTest`).
 */
class TopRowDigitMoreKeysTest {

    private data class RowKey(
        val keySpec: String,
        val hint: String?,
        val moreKeys: String?,
        val additional: String?,
    )

    private val ns = "http://schemas.android.com/apk/res-auto"

    private fun rowFile(): File {
        val candidates = listOf(File("src/main/res/xml"), File("app/src/main/res/xml"))
        val dir = candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate res/xml from ${File(".").absolutePath}")
        return File(dir, "rowkeys_tatar1.xml")
    }

    private val branches: Pair<List<RowKey>, List<RowKey>> by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(rowFile()).documentElement
        val switches = root.getElementsByTagName("switch")
        assertEquals("one <switch> in the row", 1, switches.length)
        val switch = switches.item(0) as Element
        val children = (0 until switch.childNodes.length)
            .map { switch.childNodes.item(it) }
            .filterIsInstance<Element>()
        assertEquals(listOf("case", "default"), children.map { it.tagName })
        val case = children[0]
        assertEquals("true", case.getAttributeNS(ns, "showNumberRow"))
        assertEquals("the case tests only the number row", 1, case.attributes.length)
        keysOf(case) to keysOf(children[1])
    }

    private fun keysOf(branch: Element): List<RowKey> {
        val keys = branch.getElementsByTagName("Key")
        return (0 until keys.length).map { index ->
            val key = keys.item(index) as Element
            fun attr(name: String) = if (key.hasAttributeNS(ns, name)) key.getAttributeNS(ns, name) else null
            RowKey(attr("keySpec")!!, attr("keyHintLabel"), attr("moreKeys"), attr("additionalMoreKeys"))
        }
    }

    private val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    /** The letter partners of today's row, the only long-press letters it has. */
    private val letterPartners = mapOf(
        "у" to "ү",
        "е" to "ё",
        "н" to "ң",
        "г" to "һ",
        "х" to "һ",
    )

    @Test
    fun bothBranchesHaveTheSameKeysInTheSameOrder() {
        val (numberRow, default) = branches
        assertEquals(
            listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
            default.map { it.keySpec },
        )
        assertEquals(default.map { it.keySpec }, numberRow.map { it.keySpec })
    }

    @Test
    fun theDefaultBranchCarriesTheDigitsOnTheFirstTenKeys() {
        val default = branches.second
        for ((index, digit) in digits.withIndex()) {
            val key = default[index]
            assertEquals("${key.keySpec} carries $digit", digit, key.additional)
            val partner = letterPartners[key.keySpec]
            if (partner == null) {
                assertEquals("${key.keySpec} shows its digit", digit, key.hint)
                assertNull("${key.keySpec} has no other more key", key.moreKeys)
            }
        }
        val last = default.last()
        assertNull("х gets no digit", last.additional)
        assertEquals("һ", last.moreKeys)
    }

    @Test
    fun letterPartnersKeepTheFirstPositionAndTheHint() {
        for (key in branches.second) {
            val partner = letterPartners[key.keySpec] ?: continue
            assertEquals("${key.keySpec} hint", partner, key.hint)
            if (key.additional == null) {
                assertEquals(partner, key.moreKeys)
            } else {
                assertEquals("${key.keySpec} puts the digit after the letter",
                    "$partner,%", key.moreKeys)
            }
        }
    }

    @Test
    fun theNumberRowBranchIsTodaysRowWithoutDigits() {
        for (key in branches.first) {
            assertNull("${key.keySpec} has no additional more keys", key.additional)
            assertEquals(letterPartners[key.keySpec], key.hint)
            assertEquals(letterPartners[key.keySpec], key.moreKeys)
            for (text in listOfNotNull(key.hint, key.moreKeys)) {
                assertTrue("${key.keySpec} carries no digit", text.none(Char::isDigit))
            }
        }
    }
}
