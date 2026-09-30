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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the key-preview dismiss animator. The animation is a framework
 * `ObjectAnimator` on a pooled view, so behavior needs a device; a JVM test pins the code shape:
 *
 * - the animator is built in code, never via `AnimatorInflater` (no XML parse per key press);
 * - the visual parameters: scaleY 1.0 -> 0.94 over 53 ms under an `AccelerateInterpolator`
 *   (the former XML also had a no-op scaleX 1.0 -> 1.0);
 * - the choreographer caches the animator per pooled preview view, so the per-press path
 *   allocates nothing;
 * - the animation-end listener resolves the key from the view at END time
 *   (`dismissKeyPreviewView`) instead of capturing a key at creation time — the cached
 *   animator outlives the key press it was created for;
 * - the former XML resource, its theme attribute and the styleable entry do not exist.
 */
class KeyPreviewDismissAnimatorSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val drawParams by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewDrawParams.java")
    }
    private val choreographer by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyPreviewChoreographer.java")
    }

    @Test
    fun theAnimatorIsBuiltInCodeWithoutXmlInflation() {
        assertTrue(
            "the dismiss animator is a programmatic ObjectAnimator on scaleY",
            drawParams.contains("ObjectAnimator.ofFloat(target, View.SCALE_Y"),
        )
        assertFalse(
            "AnimatorInflater was the finding: an XML parse on every key press",
            drawParams.contains("AnimatorInflater.loadAnimator"),
        )
        assertFalse(
            "no animator resource id is read from the theme anymore",
            drawParams.contains("keyPreviewDismissAnimator"),
        )
    }

    @Test
    fun theVisualParametersMatchTheDeletedXml() {
        assertTrue("53 ms, as in key_preview_dismiss_lxx.xml",
            drawParams.contains("DISMISS_ANIMATION_DURATION_MS = 53"))
        assertTrue("scaleY 1.0 -> 0.94, as in key_preview_dismiss_lxx.xml",
            drawParams.contains("DISMISS_ANIMATION_TO_SCALE_Y = 0.94f"))
        assertTrue("the same AccelerateInterpolator the code set on the inflated set",
            drawParams.contains("animator.setInterpolator(ACCELERATE_INTERPOLATOR)"))
    }

    @Test
    fun theChoreographerCachesOneAnimatorPerPooledView() {
        assertTrue(choreographer.contains("mDismissAnimatorsCache"))
        assertTrue(
            "creation happens only on a cache miss",
            choreographer.contains(
                "animators = new KeyPreviewAnimators(createDismissAnimator(keyPreviewView))"),
        )
        // One cache lookup in the show path, not one animator construction.
        val showPath = choreographer.substringAfter("void showKeyPreview(")
            .substringBefore("private KeyPreviewAnimators getDismissAnimators")
        assertTrue(showPath.contains("getDismissAnimators(keyPreviewView)"))
        assertFalse(showPath.contains("createDismissAnimator("))
    }

    @Test
    fun theEndListenerResolvesTheKeyAtEndTimeNotAtCreationTime() {
        assertTrue(
            "the cached animator outlives the press it was built for, so the end listener " +
                "must look the key up by the view",
            choreographer.contains("dismissKeyPreviewView(keyPreviewView)"),
        )
        val lookup = choreographer.substringAfter("private void dismissKeyPreviewView(")
        assertTrue(lookup.contains("entry.getValue() == keyPreviewView"))
        assertTrue(lookup.contains("mShowingKeyPreviewViews.remove(showingKey)"))
    }

    @Test
    fun theDeadXmlAndItsAttributeAreGone() {
        assertFalse(
            "res/anim/key_preview_dismiss_lxx.xml is superseded by code and must not linger",
            File(sourceRoot(), "res/anim/key_preview_dismiss_lxx.xml").exists(),
        )
        val resDir = File(sourceRoot(), "res")
        val offenders = resDir.walkTopDown().filter { it.isFile }
            .filter { it.readText().contains("keyPreviewDismissAnimator") }
            .map { it.name }.toList()
        assertTrue("no resource may reference keyPreviewDismissAnimator: $offenders",
            offenders.isEmpty())
    }
}
