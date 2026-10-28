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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy freeze of the debug gesture recorder's record type: the field set is an
 * allowlist, and any field outside it fails the build.
 *
 * The recorder lives in the debug source set, so the record class is on the debug unit-test
 * classpath only. The type walk runs there (and asserts the class's absence from the release
 * classpath, with a sanity class proving the classpath resolves); the source mirror parses the
 * record's primary constructor and runs identically on both variants, so the contract is
 * enforced even when the type itself is not loadable.
 */
class GestureRecorderRecordContractTest {

    @Test
    fun theRecordTypeCarriesOnlyTheAllowlistedFields() {
        val type = try {
            Class.forName(RECORD_CLASS)
        } catch (expected: ClassNotFoundException) {
            // The release unit-test classpath must not contain the recorder. The sanity class
            // proves the classloader resolves this module, so the absence is real.
            Class.forName(SANITY_CLASS)
            return
        }
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(
            "the record fields are exactly the privacy allowlist",
            ALLOWLIST.keys,
            fields.mapTo(HashSet()) { it.name },
        )
        for (field in fields) {
            assertEquals("record field ${field.name} type", ALLOWLIST[field.name], field.type)
        }
    }

    @Test
    fun theRecordSourceDeclaresExactlyTheAllowlistedFields() {
        val source = recordSource().readText()
        val start = source.indexOf("data class GestureRecord(")
        assertTrue("the record source declares GestureRecord", start >= 0)
        val end = source.indexOf("\n)", start)
        assertTrue("the record constructor block closes", end > start)
        val declared = Regex("""val\s+(\w+)\s*:""")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "the record constructor declares exactly the privacy allowlist, in order",
            ALLOWLIST.keys.toList(),
            declared,
        )
    }

    @Test
    fun theRecordSourcePinsTheSampleCount() {
        assertTrue(
            "the record keeps the resampled path at 50 points",
            recordSource().readText().contains("SAMPLE_COUNT = 50"),
        )
    }

    private fun recordSource(): File {
        val relative = "src/debug/java/rkr/simplekeyboard/inputmethod/latin/glide/recorder/" +
            "GestureRecord.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull(File::isFile)
            ?: error("the record source was not found; the debug source set is required")
    }

    private companion object {
        const val RECORD_CLASS =
            "rkr.simplekeyboard.inputmethod.latin.glide.recorder.GestureRecord"
        const val SANITY_CLASS = "rkr.simplekeyboard.inputmethod.latin.glide.GlidePath"

        /** Field name to field type; the order pins the constructor's declaration order. */
        val ALLOWLIST = linkedMapOf(
            "prompt" to String::class.java,
            "pathX" to FloatArray::class.java,
            "pathY" to FloatArray::class.java,
            "dtMs" to IntArray::class.java,
            "layoutId" to String::class.java,
            "densityBucket" to Int::class.javaPrimitiveType!!,
            "top1Match" to Boolean::class.javaPrimitiveType!!,
        )
    }
}
