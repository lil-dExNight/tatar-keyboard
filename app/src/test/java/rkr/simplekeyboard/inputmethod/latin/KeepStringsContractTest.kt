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
 * Source contract for the string keep list of the resource shrinker.
 *
 * `res/raw/keep.xml` is the only thing that stops `shrinkResources` from dropping strings:
 * every string resolved at runtime through `Resources.getIdentifier` (keyboard XML
 * `!string/<name>` labels resolved by `KeyboardTextsSet`, `locale_name_<locale>` names
 * resolved by `LocaleResourceUtils`) has no `R.string` reference for the shrinker to follow,
 * so it must be named in the keep list verbatim. A wholesale wildcard string rule would mask
 * a lost entry while also keeping every string alive; this test pins the exact set.
 *
 * The xml families stay globs (many files, one prefix table in
 * `scripts/release_check.sh`); only the strings are enumerated here.
 */
class KeepStringsContractTest {

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }

    /** The tools:keep patterns of res/raw/keep.xml, split and trimmed. */
    private fun keepPatterns(): List<String> {
        val keep = File(resRoot(), "raw/keep.xml")
        assertTrue("res/raw/keep.xml must exist for shrinkResources", keep.isFile)
        val text = keep.readText()
        val match = Regex("tools:keep=\"([^\"]+)\"").find(text)
            ?: error("no tools:keep attribute in ${keep.path}")
        return match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Names resolved by `getIdentifier` in the tree: `!string/<name>` occurrences in every
     * xml configuration of `res` and every main source file, plus the `locale_name_*` string
     * resources of the values configurations.
     */
    private fun referencedStringNames(): Set<String> {
        val names = mutableSetOf<String>()
        val sources = mutableListOf<File>()
        // Every xml configuration dir holds the keyboard layouts and key styles whose
        // !string/ references KeyboardTextsSet resolves at runtime.
        resRoot().listFiles { f -> f.isDirectory && f.name.startsWith("xml") }?.let { sources.addAll(it) }
        // The Java/Kotlin sources hold the text-table entries of KeyboardTextsTable.
        File(resRoot().parentFile, "java").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { sources.add(it) }
        val textRef = Regex("!string/([A-Za-z0-9_]+)")
        for (source in sources) {
            val files = if (source.isDirectory) {
                source.listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty().toList()
            } else {
                listOf(source)
            }
            for (file in files) {
                for (name in textRef.findAll(file.readText())) {
                    names.add(name.groupValues[1])
                }
            }
        }
        // LocaleResourceUtils builds "locale_name_" + entry of R.array.locale_exception_keys and
        // "locale_name_in_root_locale_" + entry of locale_displayed_in_root_locale, so the
        // shipped strings of those prefixes are all resolved by name.
        resRoot().listFiles { f -> f.isDirectory && f.name.startsWith("values") }?.forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { file ->
                for (m in Regex("<string\\b[^>]*\\bname=\"(locale_name_[^\"]+)\"")
                    .findAll(file.readText())) {
                    names.add(m.groupValues[1])
                }
            }
        }
        return names
    }

    @Test
    fun keepListCoversExactlyTheNameResolvedStrings() {
        val patterns = keepPatterns()
        val stringKeeps = patterns.filter { it.startsWith("@string/") }
            .map { it.removePrefix("@string/") }
            .toSet()
        val referenced = referencedStringNames()
        assertTrue(
            "no wildcard @string rule may remain in keep.xml (it defeats string shrinking); " +
                "the keep list must enumerate the names",
            patterns.none { it == "@string/" + "*" },
        )
        assertEquals(
            "keep.xml @string entries must be exactly the getIdentifier-resolved names " +
                "(layout !string/ references, KeyboardTextsTable entries, locale_name_* strings); " +
                "missing: ${(referenced - stringKeeps).sorted()}, " +
                "kept but unresolved: ${(stringKeeps - referenced).sorted()}",
            referenced,
            stringKeeps,
        )
    }

    @Test
    fun keptStringsAreDefinedInDefaultValues() {
        // A keep entry without a default res/values definition is a typo the shrinker will
        // happily honor while `getIdentifier` returns 0 and the keyboard shows the raw
        // reference on a key.
        val defaultStrings = mutableSetOf<String>()
        File(resRoot(), "values").listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.forEach { file ->
                for (m in Regex("<string\\b[^>]*\\bname=\"([^\"]+)\"").findAll(file.readText())) {
                    defaultStrings.add(m.groupValues[1])
                }
            }
        val missing = keepPatterns()
            .filter { it.startsWith("@string/") }
            .map { it.removePrefix("@string/") }
            .filterNot { it in defaultStrings }
        assertTrue(
            "keep.xml keeps strings that res/values/ does not define: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun xmlKeepFamiliesStayGlobs() {
        // The xml families are checked against the concrete file names by the release
        // checker; this only pins that the family list itself did not drift.
        val families = keepPatterns()
            .filter { it.startsWith("@xml/") && it.endsWith("*") }
            .map { it.removePrefix("@xml/").removeSuffix("*") }
        assertEquals(
            listOf("keyboard_layout_set_", "kbd_", "rows_", "rowkeys_", "row_"),
            families,
        )
    }
}
