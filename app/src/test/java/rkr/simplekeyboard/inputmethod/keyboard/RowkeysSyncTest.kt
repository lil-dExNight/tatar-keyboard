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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Russian letter rows are byte-for-byte the Tatar ones.
 *
 * That is deliberate, not an accident of copy-paste: the Russian layout carries the same Tatar
 * long-press duplicates (у→ү, н→ң, х→һ, а→ә, о→ө, ж→җ …) so a bilingual user gets the Tatar
 * letters without switching layouts. The only difference between the two layouts lives in
 * `rowkeys_tatar_extra.xml` — the visible fifth row of ә ө ү җ ң һ ё ъ, which the Russian layout
 * does not have.
 *
 * Because the three shared files are identical, editing one side without the other silently
 * desynchronises the layouts, and nothing else in the tree would notice. This test is the guard:
 * any one-sided edit of `rowkeys_russian{1,2,3}.xml` or `rowkeys_tatar{1,2,3}.xml` fails here.
 * If the layouts ever need to diverge on purpose, drop the identity and delete this test.
 */
class RowkeysSyncTest {

    private fun xmlDir(): File {
        val candidates = listOf(File("src/main/res/xml"), File("app/src/main/res/xml"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate res/xml from ${File(".").absolutePath}")
    }

    @Test
    fun russianLetterRowsAreByteIdenticalToTatarOnes() {
        val dir = xmlDir()
        for (row in 1..3) {
            val russian = File(dir, "rowkeys_russian$row.xml").readBytes()
            val tatar = File(dir, "rowkeys_tatar$row.xml").readBytes()
            assertTrue(
                "rowkeys_russian$row.xml and rowkeys_tatar$row.xml drifted apart; " +
                    "the Tatar long-press duplicates must stay on both layouts",
                russian.contentEquals(tatar),
            )
        }
    }
}
