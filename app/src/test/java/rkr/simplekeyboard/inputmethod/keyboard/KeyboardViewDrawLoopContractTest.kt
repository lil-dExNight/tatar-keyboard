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
 * O4 of `docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md`: the board's draw loop, pinned at the source
 * level (no Robolectric here, by design — the discipline of the other *SourceContract tests).
 *
 * What these pins freeze:
 * - a key press invalidates the touched key's RECT, never the whole board, and the press/release
 *   plumbing routes through `invalidateKey(Key)`;
 * - the offscreen buffer is redrawn only for invalidated keys, one indexed loop, no collection
 *   iterator allocation on a key-press frame;
 * - the frame itself is a single `drawBitmap` blit (the dirty rect clips it);
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
    fun pressAndReleaseRouteThroughTheRectInvalidation() {
        val pressed = mainKeyboardView.substringAfter("public void onKeyPressed(final Key key")
            .substringBefore("private void showKeyPreview")
        assertTrue(pressed.contains("invalidateKey(key)"))
        val released = mainKeyboardView.substringAfter("public void onKeyReleased(final Key key")
            .substringBefore("private void dismissKeyPreview(final Key key)")
        assertTrue(released.contains("invalidateKey(key)"))
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
