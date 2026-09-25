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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3 of `docs/ROADMAP-P8-PLAN.md`: at most ONE glide word index may be resident.
 *
 * The 3.0.0 audit recorded an accepted worst case of ~5.8 MB — both warm engines holding their
 * own index at once (warm slots are deliberate, see `LanguageSlot`'s class doc). A glide request
 * now drops every OTHER language's index first, so the worst case is one index; the language the
 * user returns to rebuilds lazily on its next gesture, exactly as it already does after the idle
 * release (`releaseGlideIndexes`, O2-3).
 *
 * Why a source contract: the drop is a POST to another engine's serialized worker, so its effect
 * is only observable across threads and a real engine pair. That the drop itself frees the index
 * is proven at the engine level by `MappedDictionaryEngineGlideTest`; what this pins is the
 * controller's dispatch — the invariant and its ORDER (release before the request, never after).
 */
class GlideIndexResidencySourceContractTest {

    private fun controllerSource(): String {
        val relative =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt"
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private fun onGlideInputBody(text: String) =
        text.substringAfter("fun onGlideInput(path: GlidePath)")
            .substringBefore("private fun releaseGlideIndexesExcept(")

    @Test
    fun aGlideRequestFirstDropsTheOtherLanguagesIndexes() {
        val body = onGlideInputBody(controllerSource())
        val release = body.indexOf("releaseGlideIndexesExcept(activeLanguage)")
        val request = body.indexOf("activeEngine.requestGlide(")
        assertTrue("the release must be there", release >= 0)
        assertTrue("the request must be there", request >= 0)
        assertTrue("and the release must come FIRST", release < request)
    }

    @Test
    fun theKeptLanguageIsTheActiveOneAndEveryOtherSlotIsDropped() {
        val fn = controllerSource().substringAfter("private fun releaseGlideIndexesExcept(")
        assertTrue(
            "iterates the slots",
            fn.contains("for ((subtypeId, slot) in slots)"),
        )
        assertTrue(
            "skips exactly the kept one",
            fn.contains("if (subtypeId == keepSubtypeId) continue"),
        )
        assertTrue(
            "and drops the rest through the engine's own seam",
            fn.contains("slot.engine?.releaseGlideIndex()"),
        )
    }

    @Test
    fun theIdleReleaseStillDropsEverything() {
        // C3 narrows the residency during typing; it must not weaken the idle release that frees
        // the last index when the keyboard goes away.
        val fn = controllerSource().substringAfter("fun releaseGlideIndexes()")
            .substringBefore("/**")
        assertTrue(
            "the idle path keeps no exception",
            fn.contains("for (slot in slots.values)") && fn.contains("slot.engine?.releaseGlideIndex()"),
        )
    }
}
