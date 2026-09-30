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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract: backup is closed as a WHITELIST (the test greps the source rather than
 * exercising Android). What is checked is the result, not the exact XML shape:
 *
 *  (1) neither section of the rule file carries a single allowing (<include>) element;
 *  (2) no allowing element can resolve to a path under `personal/`, `dictionaries/` or the
 *      "recent emoji" medium (`recent_emoji*`);
 *  (3) the manifest carries android:allowBackup="false" and references the
 *      dataExtractionRules edition (and carries neither android:fullBackupOnly nor the
 *      legacy android:fullBackupContent, which is dead once allowBackup=false;
 *      dataExtractionRules stays because on API 31+ allowBackup=false does NOT close
 *      device-to-device transfer, the <device-transfer> section does);
 *  (4) not one call to BackupManager remains in the code.
 *
 * The level-2 check on the BUILT artifact (aapt2 on the APK) lives in
 * scripts/check-no-internet.sh; this test is the source level.
 *
 * Every predicate this test relies on is proven fail-capable by exercising it against a
 * deliberately-broken in-memory input as well as the real files, so a future regression
 * that reopens backup turns this test red.
 */
class BackupWhitelistSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val manifest by lazy { File(sourceRoot(), "AndroidManifest.xml").readText() }
    private val dataExtractionRules by lazy {
        stripXmlComments(File(sourceRoot(), "res/xml/data_extraction_rules.xml").readText())
    }

    /** The data domains a whitelist must exclude WHOLE (regular + device-protected). */
    private val requiredDomains = setOf(
        "file", "database", "sharedpref", "external",
        "device_file", "device_database", "device_sharedpref",
    )

    /** Path fragments that must never be reachable by an allowing element. */
    private val sensitiveMarkers = listOf("personal", "dictionaries", "recent_emoji")

    // --- pure predicates (kept pure so the fail-capability tests can exercise them) -----------

    private fun stripXmlComments(xml: String): String =
        Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(xml, "")

    /** Inner text of the first <tag>...</tag> element (tags here carry no attributes). */
    private fun innerXml(xml: String, tag: String): String {
        val start = xml.indexOf("<$tag>")
        require(start >= 0) { "<$tag> not found" }
        val open = xml.indexOf('>', start) + 1
        val close = xml.indexOf("</$tag>", open)
        require(close >= 0) { "</$tag> not found" }
        return xml.substring(open, close)
    }

    /** Every <include ...> element (the only allowing element in these schemas). */
    private fun allowingElements(xml: String): List<String> =
        Regex("<include\\b[^>]*>").findAll(xml).map { it.value }.toList()

    /** Domains named by <exclude domain="..."> in the given fragment. */
    private fun excludedDomains(xml: String): Set<String> =
        Regex("<exclude\\b[^>]*\\bdomain=\"([^\"]+)\"").findAll(xml)
            .map { it.groupValues[1] }.toSet()

    /**
     * An allowing element could expose sensitive data if it opens a whole domain (no path,
     * so it covers every file including the sensitive ones) or names a sensitive path.
     */
    private fun includeCouldExposeSensitive(includeElement: String): Boolean {
        val path = Regex("\\bpath=\"([^\"]*)\"").find(includeElement)?.groupValues?.get(1)
        if (path.isNullOrBlank()) return true
        return sensitiveMarkers.any { path.contains(it) }
    }

    // --- (1) no allowing element in any section ---------------------------------------------

    @Test
    fun neitherSectionCarriesAnAllowingElement() {
        val sections = listOf(
            "data_extraction_rules/cloud-backup" to innerXml(dataExtractionRules, "cloud-backup"),
            "data_extraction_rules/device-transfer" to innerXml(dataExtractionRules, "device-transfer"),
        )
        for ((name, body) in sections) {
            assertTrue(
                "$name must contain no allowing <include> element",
                allowingElements(body).isEmpty(),
            )
        }
    }

    // --- (2) no allowing element can resolve under personal/, dictionaries/ or recents --------

    @Test
    fun noAllowingElementCanResolveUnderASensitivePath() {
        // The strongest form of (2): the allow-list is empty, so nothing can resolve anywhere.
        val allIncludes = allowingElements(dataExtractionRules)
        assertTrue(
            "an empty allow-list is the only way to guarantee nothing resolves to a sensitive path",
            allIncludes.isEmpty(),
        )
        // And, defensively, any include that ever appeared must not reach a sensitive path.
        for (include in allIncludes) {
            assertFalse(
                "allowing element resolves to a sensitive path: $include",
                includeCouldExposeSensitive(include),
            )
        }
    }

    // --- whitelist completeness: every domain excluded whole, both sections ------------------

    @Test
    fun dataExtractionRulesDeclaresBothSectionsEachExcludingEveryDomain() {
        assertTrue(dataExtractionRules.contains("<data-extraction-rules>"))
        assertTrue("cloud-backup section required", dataExtractionRules.contains("<cloud-backup>"))
        assertTrue("device-transfer section required", dataExtractionRules.contains("<device-transfer>"))
        assertEquals(
            "cloud-backup must exclude every data domain whole",
            requiredDomains,
            excludedDomains(innerXml(dataExtractionRules, "cloud-backup")),
        )
        assertEquals(
            "device-transfer must exclude every data domain whole",
            requiredDomains,
            excludedDomains(innerXml(dataExtractionRules, "device-transfer")),
        )
    }

    // --- (3) manifest disables backup and references the API 31+ edition ----------------------

    @Test
    fun manifestDisablesBackupAndReferencesDataExtractionRules() {
        assertTrue(
            "android:allowBackup must be false",
            manifest.contains("android:allowBackup=\"false\""),
        )
        assertFalse(
            "android:allowBackup=\"true\" must be gone",
            manifest.contains("android:allowBackup=\"true\""),
        )
        assertFalse(
            "android:fullBackupOnly is meaningless with allowBackup=false and must be removed",
            manifest.contains("android:fullBackupOnly"),
        )
        assertTrue(
            "manifest must reference the API 31+ rules (they close device-to-device transfer)",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
        assertFalse(
            "android:fullBackupContent is dead with allowBackup=false and must stay removed",
            manifest.contains("android:fullBackupContent"),
        )
    }

    // --- (4) no BackupManager call anywhere in the code --------------------------------------

    @Test
    fun noBackupManagerReferenceRemainsInTheCode() {
        val javaRoot = File(sourceRoot(), "java")
        val offenders = javaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("BackupManager") }
            .map { it.name }
            .toList()
        assertTrue("BackupManager must not appear in the code: $offenders", offenders.isEmpty())
    }

    // --- fail-capability: prove each predicate turns red on a broken input --------------------

    @Test
    fun theNoAllowingElementPredicateIsFailCapable() {
        // Real sections have none; a section with an <include> is detected.
        assertTrue(allowingElements(innerXml(dataExtractionRules, "cloud-backup")).isEmpty())
        val broken = """
            <cloud-backup>
                <include domain="sharedpref" path="dictionaries/" />
                <exclude domain="file" />
            </cloud-backup>
        """.trimIndent()
        assertFalse(allowingElements(innerXml(broken, "cloud-backup")).isEmpty())
    }

    @Test
    fun theSensitivePathPredicateIsFailCapable() {
        // A whole-domain include (no path) and a dictionaries/ include are both flagged;
        // a genuinely narrow, non-sensitive include is not.
        assertTrue(includeCouldExposeSensitive("<include domain=\"sharedpref\" />"))
        assertTrue(includeCouldExposeSensitive("<include domain=\"file\" path=\"personal/word.list\" />"))
        assertTrue(includeCouldExposeSensitive("<include domain=\"file\" path=\"dictionaries/x.tdict\" />"))
        assertTrue(includeCouldExposeSensitive("<include domain=\"file\" path=\"recent_emoji_v1\" />"))
        assertFalse(includeCouldExposeSensitive("<include domain=\"file\" path=\"public/manual.txt\" />"))
    }

    @Test
    fun theExcludedDomainsPredicateIsFailCapable() {
        assertEquals(requiredDomains, excludedDomains(innerXml(dataExtractionRules, "cloud-backup")))
        // Dropping a domain from a section must be detectable.
        val missingOne = "<cloud-backup><exclude domain=\"file\" /></cloud-backup>"
        assertFalse(requiredDomains == excludedDomains(innerXml(missingOne, "cloud-backup")))
    }
}
