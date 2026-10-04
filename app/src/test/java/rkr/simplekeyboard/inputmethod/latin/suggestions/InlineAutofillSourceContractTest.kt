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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inline autofill's source contracts: every API-30 framework type is confined to the gated
 * binder, the strip's word path carries no trace of the feature, and the surface arbitration in
 * `InputView` (inline session vs word strip vs emoji panel) cannot silently lose a rule.
 */
class InlineAutofillSourceContractTest {

    @Test
    fun theAutofillFrameworkTypesAreConfinedToTheBinder() {
        val offenders = mainSources().filter { file ->
            file.name != "InlineAutofillBinder.kt" &&
                file.readText().contains("android.widget.inline")
        }
        assertEquals("android.widget.inline outside InlineAutofillBinder: $offenders",
            emptyList<File>(), offenders)
        assertTrue(binder().contains("@TargetApi(Build.VERSION_CODES.R)"))
    }

    @Test
    fun latinImeGatesBothEntryPointsOnTheApiAndTheField() {
        val latinIme = latinImeSource()
        // The platform creates the request only after the field's startInput, so the EditorInfo
        // is the field's own and both entry points can afford to be fail-closed.
        val request = methodBody(latinIme,
            "public InlineSuggestionsRequest onCreateInlineSuggestionsRequest(final Bundle uiExtras)")
        assertTrue(request.contains("InlineAutofillGate.mayHost(Build.VERSION.SDK_INT"))
        assertTrue(request.contains("editorInfo == null"))
        val response = methodBody(latinIme,
            "public boolean onInlineSuggestionsResponse(final InlineSuggestionsResponse response)")
        assertTrue(response.contains("InlineAutofillGate.mayHost(Build.VERSION.SDK_INT"))
        assertTrue(response.contains("editorInfo == null"))
        assertTrue(response.contains("return false"))
    }

    @Test
    fun theSessionBoundaryIsThePlatformsEmptyResponsePlusFieldFinish() {
        val latinIme = latinImeSource()
        val response = methodBody(latinIme,
            "public boolean onInlineSuggestionsResponse(final InlineSuggestionsResponse response)")
        // The empty response is the platform's session-end signal: it clears the surface BEFORE
        // the gate, which reads the new field and must not strand the old field's content.
        val clearAt = response.indexOf("hideInlineAutofillStrip()")
        val gateAt = response.indexOf("InlineAutofillGate.mayHost(Build.VERSION.SDK_INT")
        assertTrue("the clear and the gate both run", clearAt >= 0 && gateAt >= 0)
        assertTrue("the clear precedes the gate", clearAt < gateAt)
        // onStartInputView has no end hook of its own: it would kill a response that legitimately
        // arrived before it for the field that is just starting.
        val startView = methodBody(latinIme,
            "void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting)")
        assertFalse(startView.contains("hideInlineAutofillStrip"))
        // A finished field drops its session immediately instead of waiting for the next startInput.
        val finishView = methodBody(latinIme,
            "void onFinishInputViewInternal(final boolean finishingInput)")
        assertTrue(finishView.contains("hideInlineAutofillStrip()"))
    }

    @Test
    fun theImeAdvertisesInlineSuggestionsToThePlatform() {
        // Without this attribute the system never asks the IME for an inline request at all.
        val method = File(sourceRoot(), "res/xml/method.xml").readText()
        assertTrue(method.contains("""android:supportsInlineSuggestions="true""""))
    }

    @Test
    fun theStripWordPathCarriesNoTraceOfTheFeature() {
        for (name in listOf("SuggestionStripView.kt", "SuggestionStripState.kt")) {
            val text = File(suggestionsDir(), name).readText().lowercase()
            assertFalse("$name must stay free of inline autofill", text.contains("inline"))
            assertFalse("$name must stay free of inline autofill", text.contains("autofill"))
        }
    }

    @Test
    fun theHostViewLoadsWithoutTheAutofillClasses() {
        val host = File(suggestionsDir(), "InlineAutofillStripView.kt").readText()
        assertFalse(host.contains("android.widget.inline"))
        assertFalse(host.contains("InlineSuggestion"))
    }

    @Test
    fun theSurfaceArbitrationKeepsAllThreeOwners() {
        val inputView = File(sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/InputView.java").readText()
        val showInline = methodBody(inputView, "public InlineAutofillStripView showInlineAutofillStrip()")
        assertTrue("the emoji panel refuses the inline surface",
            showInline.contains("if (isEmojiPanelShowing())"))
        assertTrue("a visible strip is displaced, not duplicated",
            showInline.contains("mStripHiddenByInline = true"))
        val showPanel = methodBody(inputView, "public EmojiPanelView showEmojiPanel(")
        assertTrue("the panel displaces a live inline host like the strip",
            showPanel.contains("mInlineHiddenByEmojiPanel = true"))
        val clear = methodBody(inputView, "public void clearAndHideSuggestionStrip()")
        assertTrue("a controller hide cancels the inline restore",
            clear.contains("mStripHiddenByInline = false"))
    }

    @Test
    fun bothInputViewLayoutsCarryTheGatedStub() {
        for (folder in listOf("layout", "layout-v28")) {
            val xml = File(sourceRoot(), "res/$folder/input_view.xml").readText()
            assertTrue(xml.contains("inline_autofill_strip_stub"))
            assertTrue(xml.contains("@layout/inline_autofill_strip"))
        }
        val host = File(sourceRoot(), "res/layout/inline_autofill_strip.xml").readText()
        assertTrue("the host stays gone until a session shows it",
            host.contains("""android:visibility="gone""""))
    }

    @Test
    fun theSpecMathMirrorsTheStripCellBoundaries() {
        val specs = File(suggestionsDir(), "InlineStripSpecs.kt").readText()
        assertTrue(specs.contains("stripWidthPx * cell / CELL_COUNT"))
        assertTrue(specs.contains("const val CELL_COUNT = SuggestionStripState.CELL_COUNT"))
    }

    @Test
    fun everySpecCarriesTheInlineUiVersionHandshake() {
        // The platform's render service refuses a spec without the style bundle, and an empty
        // render has no dropdown fallback: no handshake means no autofill UI at all. The shape is
        // the canonical one: the version list under the :key entry and a per-version bundle.
        val binder = binder()
        assertTrue(binder.contains("setStyle("))
        assertTrue(binder.contains("androidx.autofill.inline.ui.version:key"))
        assertTrue(binder.contains("androidx.autofill.inline.ui.version:v1"))
        assertTrue(binder.contains("putBundle("))
    }

    private fun latinImeSource(): String = File(sourceRoot(),
        "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java").readText()

    private fun binder(): String = File(suggestionsDir(), "InlineAutofillBinder.kt").readText()

    private fun suggestionsDir(): File =
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/suggestions")

    private fun mainSources(): List<File> =
        File(sourceRoot(), "java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    /** The body of the method whose declaration starts with [signature], braces balanced. */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("method not found: $signature", start >= 0)
        var index = source.indexOf('{', start)
        assertTrue("method body not found: $signature", index >= 0)
        var depth = 0
        val open = index
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces after $signature")
    }
}
