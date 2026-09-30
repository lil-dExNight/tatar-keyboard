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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interface ships in three languages, and none of them may lose a string.
 *
 * The app's strings live in exactly three locales — `values` (English), `values-ru` and
 * `values-tt`; the upstream locale folders were removed with the layouts they served. A key missing
 * from one translation shows up as an English row between translated ones.
 */
class ThreeLanguageStringsTest {

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }

    private data class Entry(val value: String, val translatable: Boolean, val file: String)

    private fun load(dir: String): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val folder = File(resRoot(), dir)
        for (file in folder.listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }) {
            val text = file.readText()
            STRING.findAll(text).forEach {
                out[it.groupValues[1]] =
                    Entry(it.groupValues[3], it.groupValues[2].contains("translatable=\"false\""), file.name)
            }
            PLURALS.findAll(text).forEach {
                out["plurals:" + it.groupValues[1]] = Entry(it.groupValues[2], false, file.name)
            }
        }
        return out
    }

    private val en by lazy { load("values") }
    private val ru by lazy { load("values-ru") }
    private val tt by lazy { load("values-tt") }

    /**
     * Keys English carries that the two translated folders deliberately do not.
     *
     * Every one of them is either a machine-readable config value or upstream's own layout label
     * format. Kept as an explicit list rather than a pattern, so adding a genuinely new
     * untranslated string turns this red instead of slipping under a wildcard.
     */
    private val untranslatedOnPurpose = setOf(
        // Punctuation tables read by the input logic, not shown to anyone.
        "symbols_sentence_terminators", "symbols_word_separators",
    )

    @Test
    fun russianAndTatarCarryEveryStringEnglishOffersForTranslation() {
        val expected = en.filterValues { !it.translatable }.keys - untranslatedOnPurpose
        for ((name, table) in listOf("values-ru" to ru, "values-tt" to tt)) {
            val missing = (expected - table.keys).sorted()
            assertEquals("$name is missing strings the interface shows", emptyList<String>(), missing)
        }
    }

    /** Nothing translated may exist that English does not define — a stale key is dead weight. */
    @Test
    fun neitherTranslationCarriesAKeyEnglishDoesNotHave() {
        for ((name, table) in listOf("values-ru" to ru, "values-tt" to tt)) {
            val extra = (table.keys - en.keys).sorted()
            assertEquals("$name defines strings nothing reads", emptyList<String>(), extra)
        }
    }

    /**
     * A format argument that exists in one language and not another is a crash, not a typo:
     * `getString` throws when the template asks for an argument the caller did not pass.
     */
    @Test
    fun everyTranslationTakesTheSameFormatArgumentsAsEnglish() {
        for (key in en.keys) {
            if (en.getValue(key).translatable) continue
            val master = argumentsOf(itemsOf(key, en.getValue(key).value).first())
            for ((name, table) in listOf("values-ru" to ru, "values-tt" to tt)) {
                val entry = table[key] ?: continue
                for (item in itemsOf(key, entry.value)) {
                    assertEquals("$name/$key takes different arguments", master, argumentsOf(item))
                }
            }
        }
    }

    /**
     * The one string set that must NOT be checked against English's plural categories: Russian has
     * four and Tatar has one, and that is the point of a plurals resource. What is checked is that
     * every category a translation does declare is one Android knows.
     */
    @Test
    fun everyPluralCategoryIsOneAndroidKnows() {
        val known = setOf("zero", "one", "two", "few", "many", "other")
        for ((name, table) in listOf("values" to en, "values-ru" to ru, "values-tt" to tt)) {
            for ((key, entry) in table) {
                if (!key.startsWith("plurals:")) continue
                val quantities = QUANTITY.findAll(entry.value).map { it.groupValues[1] }.toList()
                assertTrue("$name/$key declares no category at all", quantities.isNotEmpty())
                assertTrue("$name/$key must have an \"other\" category", quantities.contains("other"))
                for (q in quantities) {
                    assertTrue("$name/$key declares unknown quantity \"$q\"", known.contains(q))
                }
            }
        }
    }

    /**
     * The failure messages this keyboard shows are held to one shape:
     * say what happened and in what state the data is, offer an action if there is one, and name no
     * file, no path, no error code and no cause. This pins the half a machine can check.
     */
    @Test
    fun noFailureMessageNamesAFileAPathOrACode() {
        val banned = Regex(
            """\.tpers|\.tdict|\.tatbigr|/data/|/sdcard/|files/|errno|Exception|SIGSEGV|0x[0-9a-fA-F]{2,}"""
        )
        var checked = 0
        for ((name, table) in listOf("values" to en, "values-ru" to ru, "values-tt" to tt)) {
            for ((key, entry) in table) {
                if (!key.contains("failed") && !key.contains("unavailable") &&
                    !key.contains("unreadable") && !key.contains("rejected") &&
                    !key.contains("no_app_for_link") && !key.contains("not_saved")
                ) {
                    continue
                }
                checked++
                assertTrue(
                    "$name/$key names something the user cannot act on: ${entry.value}",
                    !banned.containsMatchIn(entry.value),
                )
            }
        }
        assertTrue("no failure message was found to check — the filter has drifted", checked >= 20)
    }

    private fun itemsOf(key: String, value: String): List<String> =
        if (key.startsWith("plurals:")) ITEM.findAll(value).map { it.groupValues[1] }.toList()
        else listOf(value)

    private fun argumentsOf(text: String): Set<String> = ARGUMENT.findAll(text).map { it.value }.toSet()

    private companion object {
        val STRING = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLURALS = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val QUANTITY = Regex("""<item quantity="([^"]+)"""")
        val ARGUMENT = Regex("""%\d+\$[sd]|%[sd]""")
    }
}
