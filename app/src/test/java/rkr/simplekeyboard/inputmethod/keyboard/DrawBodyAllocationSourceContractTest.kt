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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw bodies of the suggestion strip and the glide trail allocate nothing, checked from
 * source (same reason as [KeyboardViewDrawLoopContractTest]: no Robolectric in this project).
 * SuggestionStripView.onDraw and MainKeyboardView's onDraw/drawGlideTrail contain no `new `, no
 * constructor call, no allocating collection factory and no string template; GlideTrail's ring
 * allocates its three parallel arrays at construction and nothing in the operations the draw pass
 * calls. An allocation a frame genuinely needs joins the per-body exception list, with the
 * reason, in the same change.
 */
class DrawBodyAllocationSourceContractTest {

    private val stripView by lazy {
        projectFile("src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt")
    }
    private val mainKeyboardView by lazy {
        projectFile("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
    }
    private val glideTrail by lazy {
        projectFile("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/GlideTrail.kt")
    }

    @Test
    fun theStripDrawBodyAllocatesNothing() {
        val onDraw = body(stripView, "override fun onDraw(canvas: Canvas)")
        // Extraction sanity: the real draw body, not an empty slice.
        assertTrue(onDraw.contains("canvas.drawText("))
        assertTrue("the pressed-cell rect is a reused field", onDraw.contains("pressedCellRect.set("))
        assertNoAllocation("SuggestionStripView.onDraw", onDraw)
    }

    @Test
    fun theGlideTrailDrawAllocatesNothing() {
        val onDraw = body(mainKeyboardView, "protected void onDraw(final Canvas canvas)")
        assertTrue(onDraw.contains("super.onDraw(canvas);"))
        assertTrue(
            "the trail draws on top of the keys",
            onDraw.indexOf("drawGlideTrail(canvas);") > onDraw.indexOf("super.onDraw(canvas);"),
        )
        assertNoAllocation("MainKeyboardView.onDraw", onDraw)
        val trail = body(mainKeyboardView, "private void drawGlideTrail(final Canvas canvas)")
        assertTrue(
            "the polyline walks the visible window on a preallocated paint",
            trail.contains("canvas.drawLine("),
        )
        assertNoAllocation("MainKeyboardView.drawGlideTrail", trail)
    }

    @Test
    fun theGlideTrailRingAllocatesOnlyAtConstruction() {
        val construction = glideTrail.substringBefore("    fun addPoint(")
        assertEquals(
            "the ring state is the three parallel primitive arrays, built at construction",
            3,
            Regex("FloatArray\\(capacity\\)").findAll(construction).count(),
        )
        val operations = glideTrail.substring(glideTrail.indexOf("    fun addPoint("))
            .substringBefore("    companion object")
        // Extraction sanity: the fade math the draw pass calls is inside the scanned region.
        assertTrue(operations.contains("fun alphaAt(i: Int): Int"))
        assertNoAllocation("GlideTrail ring operations", operations)
    }

    // --- machinery -------------------------------------------------------------------------------

    /**
     * Asserts the comment-free [body] holds no allocation form: no `new `, no constructor call
     * (an uppercase-led callee that is not a static call on a type), no allocating collection
     * factory and no string template. [exceptions] names the allocation snippets a body is
     * allowed to keep; each must be present, so a stale exception fails too.
     */
    private fun assertNoAllocation(what: String, body: String, exceptions: List<String> = emptyList()) {
        var remainder = stripComments(body)
        for (exception in exceptions) {
            val at = remainder.indexOf(exception)
            assertTrue("$what: named exception is gone: $exception", at >= 0)
            remainder = remainder.removeRange(at, at + exception.length)
        }
        assertFalse("$what allocates with `new`", remainder.contains("new "))
        assertNull(
            "$what calls a constructor",
            CONSTRUCTOR_CALL.find(remainder)?.value,
        )
        for (factory in ALLOCATING_FACTORIES) {
            assertFalse("$what allocates through $factory", remainder.contains(factory))
        }
        assertNull(
            "$what builds a string template",
            STRING_TEMPLATE.find(remainder)?.value,
        )
    }

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    /**
     * The text with block comments blanked and `//` tails cut. Naive on purpose: string literals
     * are not honored (the scanned bodies embed no comment opener in a literal).
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder()
        var inBlock = false
        var i = 0
        while (i < text.length) {
            if (inBlock) {
                val end = text.indexOf("*/", i)
                if (end < 0) break
                inBlock = false
                i = end + 2
            } else if (text.startsWith("//", i)) {
                val end = text.indexOf('\n', i)
                if (end < 0) break
                out.append('\n')
                i = end + 1
            } else if (text.startsWith("/*", i)) {
                inBlock = true
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun projectFile(relative: String): String {
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private companion object {
        // A Kotlin/Java constructor call: uppercase-led callee, not a member or static call
        // (x.Foo( and Foo.bar( are excluded); `new int[...]` stays covered by the `new ` token.
        val CONSTRUCTOR_CALL = Regex("""(?<![A-Za-z0-9_.])[A-Z][A-Za-z0-9_]*\(""")

        // The lowercase stdlib builders the constructor regex cannot see.
        val ALLOCATING_FACTORIES = listOf(
            "listOf(", "mutableListOf(", "arrayListOf(", "setOf(", "mutableSetOf(",
            "hashSetOf(", "linkedSetOf(", "mapOf(", "mutableMapOf(", "hashMapOf(",
            "linkedMapOf(", "arrayOf(", "intArrayOf(", "floatArrayOf(", "longArrayOf(",
            "doubleArrayOf(", "booleanArrayOf(", "charArrayOf(", "byteArrayOf(", "shortArrayOf(",
            "buildString(", "buildList(", "buildSet(", "buildMap(",
        )

        // A string literal carrying a template ("$x" or "${x}") is a StringBuilder allocation.
        val STRING_TEMPLATE = Regex("\"[^\"\\n]*\\$[A-Za-z{]")
    }
}
