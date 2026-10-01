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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A key press allocates nothing in `AudioAndHapticFeedbackManager`: the per-press methods post
 * prebuilt Runnable fields to one Handler on one HandlerThread, the vibration effects are built
 * once in `initInternal`, and below API 29 the view haptic runs on the calling UI thread. A source
 * contract because these JVM tests have no Android framework to run the feedback with.
 */
class KeyFeedbackAllocationSourceContractTest {

    private val source by lazy {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "java/rkr/simplekeyboard/inputmethod/latin/AudioAndHapticFeedbackManager.java")
            .readText()
    }

    /** The body of the method whose declaration contains [signature], by brace matching. */
    private fun body(signature: String): String {
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

    private val perPress = listOf(
        "public void performHapticFeedback(",
        "public void performTickFeedback(",
        "public void performAudioFeedback(",
    )

    @Test
    fun perPressMethodsAllocateNothing() {
        for (name in perPress) {
            val method = body(name)
            assertFalse("$name has a lambda", method.contains("->"))
            assertFalse("$name has a method reference", method.contains("::"))
            assertFalse("$name creates an object", method.contains("new "))
            assertFalse("$name builds a VibrationEffect", method.contains("VibrationEffect.create"))
            assertFalse("$name uses an executor", method.contains("execute("))
            assertFalse("$name goes through the preview path", method.contains("playSoundEffect("))
        }
    }

    @Test
    fun perPressMethodsPostThePrebuiltFields() {
        assertTrue(body("public void performHapticFeedback(")
            .contains("mBackgroundHandler.post(mClickVibration);"))
        assertTrue(body("public void performTickFeedback(")
            .contains("mBackgroundHandler.post(mTickVibration);"))
        val audio = body("public void performAudioFeedback(")
        assertTrue(audio.contains("mBackgroundHandler.post(sound);"))
        for (field in listOf("mStandardSound", "mDeleteSound", "mReturnSound", "mSpacebarSound")) {
            assertTrue("$field is chosen per code", audio.contains("sound = $field;"))
        }
    }

    @Test
    fun eachKeypressSoundIsBuiltOnceWithItsConstant() {
        for ((field, constant) in listOf(
            "mStandardSound" to "FX_KEYPRESS_STANDARD",
            "mDeleteSound" to "FX_KEYPRESS_DELETE",
            "mReturnSound" to "FX_KEYPRESS_RETURN",
            "mSpacebarSound" to "FX_KEYPRESS_SPACEBAR",
        )) {
            assertTrue(field, source.contains(
                "private final Runnable $field = new KeypressSound(AudioManager.$constant);"))
        }
        // The volume is read when the sound runs, from the field the settings update.
        assertTrue(source.contains("private volatile float mKeypressSoundVolume;"))
        assertTrue(body("public void onSettingsChanged(")
            .contains("mKeypressSoundVolume = settingsValues.mKeypressSoundVolume;"))
        assertTrue(body("public void run()")
            .contains("audioManager.playSoundEffect(mEffectType, mKeypressSoundVolume);"))
    }

    @Test
    fun oneHandlerThreadAndTheEffectsAreBuiltOnceInInit() {
        assertFalse("no executor is left", source.contains("ExecutorService"))
        assertEquals(1, Regex("new HandlerThread\\(").findAll(source).count())
        assertEquals(1, Regex("new Handler\\(").findAll(source).count())
        val init = body("private void initInternal(")
        val guard = init.indexOf("if (mBackgroundHandler == null) {")
        assertTrue("a later init reuses the thread", guard in 0 until init.indexOf("new HandlerThread("))
        assertTrue(init.contains("new Handler(thread.getLooper())"))
        val effects = init.indexOf(
            "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mClickVibration == null) {")
        assertTrue("the effects are built under the API 29 check, once", effects >= 0)
        for (effect in listOf("EFFECT_CLICK", "EFFECT_TICK")) {
            val create = init.indexOf("VibrationEffect.createPredefined(VibrationEffect.$effect)")
            assertTrue("$effect is built after the check", create > effects)
        }
        assertEquals("VibrationEffect is created only in init", 2,
            Regex("VibrationEffect\\.createPredefined\\(").findAll(source).count())
    }

    @Test
    fun theViewHapticBelowApi29IsOutsideAnyPost() {
        val haptic = body("public void performHapticFeedback(")
        val post = haptic.indexOf("mBackgroundHandler.post(mClickVibration);")
        val elseBranch = haptic.indexOf("} else if (viewToPerformHapticFeedbackOn != null) {")
        val tap = haptic.indexOf("HapticFeedbackConstants.KEYBOARD_TAP")
        assertTrue("the view haptic is the else branch after the post", post in 0 until elseBranch)
        assertTrue(tap > elseBranch)
        assertEquals("one post in the method", 1, Regex("\\.post\\(").findAll(haptic).count())
        // The post is a single statement, so nothing after it runs on the background thread.
        assertFalse(haptic.substring(post, elseBranch).contains("{"))
    }
}
