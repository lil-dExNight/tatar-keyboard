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
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Source contract: the release manifest declares one perceptible `<memory-budget>` whose maxMb
 * is the system memory ceiling of the device performance script, and the main manifest (shared
 * with debug builds) declares none.
 */
class MemoryBudgetManifestSourceContractTest {

    private fun repoFile(path: String): File =
        listOf(File(path), File("..", path)).firstOrNull(File::isFile)
            ?: error("cannot locate $path from ${File(".").absolutePath}")

    private fun stripXmlComments(xml: String): String =
        Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(xml, "")

    private fun systemMemoryCeilingMb(): Int {
        val script = repoFile("scripts/device-perf-ritual.sh").readText()
        val match = Regex("""(?m)^SYSTEM_MEMORY_CEILING_RELEASE_MB=(\d+)$""").find(script)
            ?: error("SYSTEM_MEMORY_CEILING_RELEASE_MB not found")
        return match.groupValues[1].toInt()
    }

    @Test
    fun releaseManifestDeclaresPerceptibleBudgetFromSystemCeiling() {
        val xml = stripXmlComments(repoFile("app/src/release/AndroidManifest.xml").readText())
        val elements = Regex("""<memory-budget\b([^>]*)/>""").findAll(xml).toList()
        assertEquals("exactly one <memory-budget>", 1, elements.size)
        val attrs = elements.single().groupValues[1]
        fun attr(name: String): String? =
            Regex("""android:$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)

        assertEquals("perceptible", attr("state"))
        assertEquals(systemMemoryCeilingMb().toString(), attr("maxMb"))
        assertEquals(null, attr("feature"))
        assertEquals(null, attr("additionalBytesPerDisplayPixel"))
        assertEquals(null, attr("additionalMbPerDensity"))
    }

    @Test
    fun mainManifestDeclaresNoBudget() {
        val xml = stripXmlComments(repoFile("app/src/main/AndroidManifest.xml").readText())
        assertFalse(xml.contains("<memory-budget"))
    }
}
