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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the keyboard's draw loop, checked from source (there is no Robolectric in this project):
 * - a key press invalidates the touched key's RECT, never the whole board, and the press/release
 *   plumbing routes through `invalidatePressState(Key)`, which leaves the offscreen buffer alone;
 * - the buffer holds released keys only and is redrawn only for invalidated keys, one indexed
 *   loop, no collection iterator allocation on a key-press frame;
 * - the frame is one `drawBitmap` blit plus the pressed keys painted over it;
 * - no raw text measurement lives in this file: reference glyph geometry comes from
 *   `TypefaceUtils`' cached helpers, and the one uncached width read (`getStringWidth`) is gated
 *   behind the auto-x-scale flag of the few keys that carry it.
 */
class KeyboardViewDrawLoopContractTest {

    private fun projectFile(relative: String): String {
        val root = listOf(File("."), File("app"), File("..")).firstOrNull {
            File(it, relative).isFile
        } ?: error("cannot locate $relative from ${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val keyboardView by lazy {
        projectFile("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardView.java")
    }
    private val mainKeyboardView by lazy {
        projectFile("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
    }

    private fun onDrawBody(text: String) =
        text.substringAfter("protected void onDraw(final Canvas canvas)")
            .substringBefore("private boolean maybeAllocateOffscreenBuffer")

    private fun onDrawKeyboardBody(text: String) =
        text.substringAfter("private void onDrawKeyboard(final Canvas canvas)")
            .substringBefore("private void onDrawKey(final Key key")

    @Test
    fun aKeyPressInvalidatesTheTouchedKeysRect() {
        val body = keyboardView.substringAfter("public void invalidateKey(final Key key)")
            .substringBefore("@Override")
        assertTrue(
            "the invalidation carries the key rect, not the whole board",
            body.contains("invalidate(x, y, x + key.getWidth(), y + key.getHeight())"),
        )
    }

    @Test
    fun pressAndReleaseRouteThroughThePressStateInvalidation() {
        val pressed = mainKeyboardView.substringAfter("public void onKeyPressed(final Key key")
            .substringBefore("private void showKeyPreview")
        assertTrue(pressed.contains("invalidatePressState(key)"))
        assertFalse(pressed.contains("invalidateKey("))
        val released = mainKeyboardView.substringAfter("private void dismissKeyPreviewWithoutDelay(")
            .substringBefore("private void dismissKeyPreview(final Key key)")
        assertTrue(released.contains("invalidatePressState(key)"))
        assertFalse(
            "a release does not redraw the key into the offscreen buffer",
            released.contains("invalidateKey("),
        )
    }

    @Test
    fun aPressStateChangeLeavesTheBufferAlone() {
        // A redrawn buffer is uploaded to the GPU again as a whole board texture.
        val body = keyboardView.substringAfter("public void invalidatePressState(final Key key)")
            .substringBefore("@Override")
        assertTrue(body.contains("invalidate(x, y, x + key.getWidth(), y + key.getHeight())"))
        assertFalse(body.contains("mInvalidatedKeys"))
        assertFalse(body.contains("mInvalidateAllKeys"))
    }

    @Test
    fun theBufferHoldsReleasedKeysAndPressedKeysArePaintedOverTheBlit() {
        val buffer = onDrawKeyboardBody(keyboardView)
        assertTrue(buffer.contains("onDrawKey(sortedKeys.get(i), canvas, paint, false /* pressed */)"))
        assertTrue(buffer.contains("onDrawKey(key, canvas, paint, false /* pressed */)"))
        assertFalse(buffer.contains("true /* pressed */"))
        val frame = onDrawBody(keyboardView)
        assertTrue(
            "the pressed keys follow the blit",
            frame.substringAfter("canvas.drawBitmap(mOffscreenBuffer, 0.0f, 0.0f, null);")
                .contains("onDrawPressedKeys(canvas);"),
        )
        val overlay = keyboardView.substringAfter("private void onDrawPressedKeys(final Canvas canvas)")
            .substringBefore("private boolean maybeAllocateOffscreenBuffer")
        assertTrue("indexed loop, no iterator", overlay.contains("final Key key = sortedKeys.get(i);"))
        assertTrue(overlay.contains("if (!key.isPressed()) {"))
        assertTrue(
            "clipped to the key, keyboard background first, as in the partial redraw",
            overlay.contains("canvas.clipRect(x, y, x + key.getWidth(), y + key.getHeight());") &&
                overlay.indexOf("background.draw(canvas);") <
                overlay.indexOf("onDrawKey(key, canvas, mPaint, true /* pressed */);"),
        )
        val key = projectFile("src/main/java/rkr/simplekeyboard/inputmethod/keyboard/Key.java")
            .substringAfter("public final Drawable selectBackgroundDrawable(")
            .substringBefore("public static class Spacer")
        assertTrue("the drawn state, not the key's own flag, picks the drawable state",
            key.contains(".getState(pressed);"))
    }

    @Test
    fun theInvalidatedKeyWalkAllocatesNoIterator() {
        assertFalse(
            "the per-press dirty set must not be a HashSet: its iterator allocates per frame",
            keyboardView.contains("HashSet"),
        )
        assertTrue(
            keyboardView.contains("private final ArrayList<Key> mInvalidatedKeys = new ArrayList<>()"),
        )
        assertTrue(
            "duplicates stay out of the list",
            keyboardView.contains("if (!mInvalidatedKeys.contains(key)) {"),
        )
        val partial = onDrawKeyboardBody(keyboardView)
        assertTrue(
            "the partial redraw is an indexed loop, like the all-keys branch",
            partial.contains("final Key key = mInvalidatedKeys.get(i);"),
        )
    }

    @Test
    fun aFrameWithoutInvalidatedKeysNeverRedrawsTheBuffer() {
        val body = onDrawBody(keyboardView)
        assertTrue(
            body.contains(
                "final boolean bufferNeedsUpdates = mInvalidateAllKeys || !mInvalidatedKeys.isEmpty();",
            ),
        )
        assertTrue(
            "the frame is one blit of the persistent buffer; the dirty rect clips it",
            body.contains("canvas.drawBitmap(mOffscreenBuffer, 0.0f, 0.0f, null);"),
        )
    }

    @Test
    fun noRawTextMeasurementLivesInTheView() {
        // Reference glyph geometry is served by TypefaceUtils' static caches; the single
        // uncached width read (getStringWidth) sits behind the auto-x-scale guard below.
        assertFalse(keyboardView.contains("measureText("))
        assertFalse(keyboardView.contains("getTextBounds("))
        assertTrue(keyboardView.contains("TypefaceUtils.getReferenceCharHeight(paint)"))
        assertTrue(keyboardView.contains("TypefaceUtils.getReferenceCharWidth(paint)"))
        assertTrue(
            "the only uncached measurement is gated on the key's auto-x-scale flag",
            keyboardView.contains("if (key.needsAutoXScale()) {"),
        )
    }

    @Test
    fun theReferenceGeometryHelpersAreCacheBacked() {
        val typefaceUtils = projectFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/utils/TypefaceUtils.java",
        )
        assertTrue(typefaceUtils.contains("sTextHeightCache"))
        assertTrue(typefaceUtils.contains("sTextWidthCache"))
        assertTrue(
            typefaceUtils.contains("public static float getReferenceCharHeight(final Paint paint)"),
        )
        assertTrue(
            typefaceUtils.contains("public static float getReferenceCharWidth(final Paint paint)"),
        )
    }
}
