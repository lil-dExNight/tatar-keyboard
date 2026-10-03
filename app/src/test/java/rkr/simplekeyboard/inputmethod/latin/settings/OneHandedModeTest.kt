/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The one-handed (compact) mode: [OneHandedMode] scales the key grid to 85% of the keyboard
 * width and docks it to the chosen side, and the freed strip on the other side stays dead and
 * background-colored.
 *
 * The geometry math (scale, dock paddings, the fifth-row floor, left/right symmetry) is pure and
 * tested directly; the wiring (the pref's path into the keyboard builder, the dead strip's hitbox
 * caps, the full-width strip and panel) is pinned at the source level — this project's JVM suite
 * runs without Robolectric on purpose.
 */
class OneHandedModeTest {

    // --- The geometry math (pure JVM) -------------------------------------------------

    private val left = OneHandedMode.SIDE_LEFT
    private val right = OneHandedMode.SIDE_RIGHT
    private val off = OneHandedMode.SIDE_OFF

    @Test
    fun offKeepsTheFullWidthGrid() {
        assertEquals(1.0f, OneHandedMode.effectiveScale(off, 720))
        assertEquals(720, OneHandedMode.gridWidthPx(720, off))
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.dockPaddingsPx(720, off))
    }

    @Test
    fun bothHandsGetTheSameGridWithMirroredPaddings() {
        // The 720 px reference width: 85% is 612 px, so the dock frees a 108 px strip.
        assertEquals(612, OneHandedMode.gridWidthPx(720, left))
        assertEquals(612, OneHandedMode.gridWidthPx(720, right))
        assertArrayEquals(intArrayOf(0, 108), OneHandedMode.dockPaddingsPx(720, left))
        assertArrayEquals(intArrayOf(108, 0), OneHandedMode.dockPaddingsPx(720, right))
        val leftPaddings = OneHandedMode.dockPaddingsPx(720, left)
        val rightPaddings = OneHandedMode.dockPaddingsPx(720, right)
        assertEquals(leftPaddings[0], rightPaddings[1])
        assertEquals(leftPaddings[1], rightPaddings[0])
    }

    @Test
    fun theReferenceDeviceKeepsThePinnedScale() {
        // 720 px: the fifth-row pitch under the scale (0.16667 × 612 ≈ 102 px) sits far above
        // the floor, so the guard must not bite on the reference device.
        assertEquals(OneHandedMode.WIDTH_SCALE, OneHandedMode.effectiveScale(left, 720))
        assertEquals(OneHandedMode.WIDTH_SCALE, OneHandedMode.effectiveScale(right, 720))
    }

    @Test
    fun aNarrowScreenClampsTheScaleToTheFloor() {
        // 400 px: 0.85 would give the fifth-row keys 0.16667 × 340 ≈ 56.7 px — below the floor —
        // so the scale climbs to 62.7 / (0.16667 × 400) ≈ 0.9405 and the grid stays hittable.
        val scale = OneHandedMode.effectiveScale(left, 400)
        assertTrue("the scale must rise above the pinned one", scale > OneHandedMode.WIDTH_SCALE)
        assertEquals(OneHandedMode.MIN_FIFTH_ROW_KEY_WIDTH_PX,
                OneHandedMode.FIFTH_ROW_KEY_FRACTION * 400 * scale, 0.01f)
    }

    @Test
    fun aScreenTooNarrowForTheFloorGetsNoDock() {
        // 300 px: even the full width gives the fifth-row keys 0.16667 × 300 = 50 px, below the
        // floor — the dock degenerates to the full-width grid instead of shrinking past it.
        assertEquals(1.0f, OneHandedMode.effectiveScale(left, 300))
        assertEquals(300, OneHandedMode.gridWidthPx(300, right))
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.dockPaddingsPx(300, left))
    }

    @Test
    fun anUnknownSideReadsAsOff() {
        assertFalse(OneHandedMode.isValidSide(-1))
        assertFalse(OneHandedMode.isValidSide(3))
        assertTrue(OneHandedMode.isValidSide(off))
        assertTrue(OneHandedMode.isValidSide(left))
        assertTrue(OneHandedMode.isValidSide(right))
        assertEquals(1.0f, OneHandedMode.effectiveScale(7, 720))
        assertArrayEquals(intArrayOf(0, 0), OneHandedMode.dockPaddingsPx(720, 7))
    }

    @Test
    fun theDialogSidesAreOffLeftRightInOrder() {
        assertEquals(listOf(off, left, right), OneHandedMode.SIDES)
        assertEquals(off, OneHandedMode.SIDE_DEFAULT)
    }

    // --- The layout contract: every Tatar layout file keeps the fifth-row floor ---------

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main/res from ${File(".").absolutePath}")
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun descendants(element: Element): List<Element> {
        val result = ArrayList<Element>()
        val stack = ArrayDeque<Element>()
        stack.addLast(element)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            result.add(current)
            val children = current.childNodes
            for (index in 0 until children.length) {
                (children.item(index) as? Element)?.let(stack::addLast)
            }
        }
        return result
    }

    /** A `latin:keyWidth` of the `NNN%p` shape as a fraction, or null for fillRight and friends. */
    private fun widthFraction(element: Element): Float? {
        val width = element.getAttribute("latin:keyWidth")
        if (!width.endsWith("%p")) return null
        return width.removeSuffix("%p").toFloatOrNull()?.div(100f)
    }

    private fun tatarRowFiles(): List<File> {
        val root = resRoot()
        val dirs = listOf("xml", "xml-land", "xml-sw600dp", "xml-sw600dp-land")
        return dirs.mapNotNull { File(root, "$it/rows_tatar.xml").takeIf(File::isFile) }
    }

    @Test
    fun everyTatarLayoutFileKeepsTheFifthRowFloorUnderTheScale() {
        val files = tatarRowFiles()
        assertTrue("rows_tatar.xml must exist in at least one resource bucket", files.isNotEmpty())
        for (file in files) {
            // getElementsByTagName, not the DFS above: document order matters — the first row
            // is the fifth (extra letters) row; the row's keyWidth is its keys' pitch, and a
            // per-key override would shrink a key below it.
            val rowNodes = parse(file).getElementsByTagName("Row")
            val rows = (0 until rowNodes.length).map { rowNodes.item(it) as Element }
            val fifthRowFraction = rows.first().let(::widthFraction)
            assertTrue("${file.name}: the fifth row must carry a %p keyWidth",
                    fifthRowFraction != null)
            assertEquals("${file.name}: OneHandedMode.FIFTH_ROW_KEY_FRACTION mirrors the layout",
                    OneHandedMode.FIFTH_ROW_KEY_FRACTION, fifthRowFraction!!, 0.0001f)
            val fifthRowKeys = descendants(parse(File(file.parentFile,
                    "rowkeys_tatar_extra.xml")))
                    .filter { it.tagName == "Key" }
            assertEquals("${file.name}: the fifth row is six keys", 6, fifthRowKeys.size)
            assertTrue("${file.name}: a fifth-row key must not override the row's width",
                    fifthRowKeys.none { it.hasAttribute("latin:keyWidth") })
            // The floor, on the 720 px reference width the floor constant is derived from.
            val scaledPitch = fifthRowFraction * OneHandedMode.WIDTH_SCALE * 720
            assertTrue("${file.name}: the fifth-row pitch under the scale ($scaledPitch px) " +
                    "dropped below the floor (${OneHandedMode.MIN_FIFTH_ROW_KEY_WIDTH_PX} px)",
                    scaledPitch >= OneHandedMode.MIN_FIFTH_ROW_KEY_WIDTH_PX)
        }
    }

    @Test
    fun theFloorIsTheNarrowestKeyTheTatarLayoutShips() {
        // The floor constant derives from the narrowest shipped key pitch: the smallest %p width
        // among rows_tatar.xml's rows and per-key overrides, on the 720 px reference width.
        for (file in tatarRowFiles()) {
            val fractions = descendants(parse(file))
                    .filter { it.tagName == "Row" || it.tagName == "Key" }
                    .mapNotNull(::widthFraction)
            val narrowest = fractions.minOrNull()
            assertTrue("${file.name}: no %p widths found", narrowest != null)
            assertEquals("${file.name}: the floor drifted from the narrowest shipped key",
                    narrowest!! * 720, OneHandedMode.MIN_FIFTH_ROW_KEY_WIDTH_PX, 0.05f)
        }
    }

    // --- The wiring (source contracts) --------------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private fun res(path: String) = File(sourceRoot(), "res/$path").readText()

    private val settings by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/Settings.java")
    }
    private val settingsValues by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java")
    }

    @Test
    fun thePrefIsAnIntWithAValidatingReader() {
        assertTrue(settings.contains("PREF_ONE_HANDED_SIDE = \"pref_one_handed_side\""))
        assertTrue(settings.contains("public static int readOneHandedSide(final SharedPreferences prefs)"))
        assertTrue(settings.contains("OneHandedMode.isValidSide(side)"))
        assertTrue(settingsValues.contains("mOneHandedSide = Settings.readOneHandedSide(prefs)"))
    }

    @Test
    fun theSideReachesTheBuilderThroughTheLayoutSetAndTheKeyboardId() {
        val switcher = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
        assertTrue(switcher.contains("builder.setOneHandedSide(settingsValues.mOneHandedSide)"))
        val layoutSet = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java")
        assertTrue(layoutSet.contains("public Builder setOneHandedSide(final int oneHandedSide)"))
        // The side is part of the keyboard identity: a changed side can never hit a stale
        // sKeyboardCache entry, which is why no cache clear is needed for this pref.
        val keyboardId = java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardId.java")
        val hashBody = keyboardId.substringAfter("private static int computeHashCode(")
            .substringBefore("private boolean equalsId(")
        val equalsBody = keyboardId.substringAfter("private boolean equalsId(final KeyboardId other)")
            .substringBefore("private static boolean isAlphabetKeyboard(")
        assertTrue(hashBody.contains("id.mOneHandedSide"))
        assertTrue(equalsBody.contains("other.mOneHandedSide == mOneHandedSide"))
    }

    @Test
    fun theDockIsGeometryOnlyAndTheStripStaysDead() {
        val builder = java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java")
        val attrs = builder.substringAfter("private void parseKeyboardAttributes(")
            .substringBefore("private void parseKeyboardContent(")
        // The view still spans the full width (the strip keeps the keyboard background)...
        assertTrue(attrs.contains("params.mOccupiedWidth = width;"))
        // ...the grid scales and the freed strip joins the side padding opposite the grid...
        assertTrue(attrs.contains("OneHandedMode.gridWidthPx(width, oneHandedSide)"))
        assertTrue(attrs.contains("OneHandedMode.dockPaddingsPx(width, oneHandedSide)"))
        // ...and the hitbox span keeps the strip touch-dead on both sides.
        assertTrue(attrs.contains("params.mHitboxMinX = dockPaddings[0];"))
        assertTrue(attrs.contains("params.mHitboxMaxX = width - dockPaddings[1];"))
        // The row-end extension and the row-start span honor the two caps.
        assertTrue(builder.substringAfter("private void endRow(")
            .contains("setKeyHitboxRightEdge(mPreviousKeyInRow, mParams.mHitboxMaxX);"))
        val row = java("rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardRow.java")
        assertTrue(row.contains("mCurrentX - Math.max(mLastKeyRightEdge,"))
        assertTrue(row.contains("mParams.mHitboxMinX);"))
    }

    @Test
    fun theBalloonClampBandFollowsTheDockedGrid() {
        // The band derives from the keyboard's paddings and occupied width, which the dock
        // adjusts — no separate one-handed branch may appear in the balloon path.
        val view = java("rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
        val band = view.substringAfter("setBalloonClampBand(").substringBefore(");")
        assertTrue(band.contains("keyboard.mLeftPadding"))
        assertTrue(band.contains("keyboard.mOccupiedWidth") && band.contains("keyboard.mRightPadding"))
        assertFalse(view.contains("mOneHandedSide"))
    }

    @Test
    fun theSuggestionStripAndTheEmojiPanelStayFullWidth() {
        // Both are siblings of the keyboard view in the input view, not part of the key grid.
        for (layout in listOf("layout/input_view.xml", "layout-v28/input_view.xml")) {
            val root = parse(File(sourceRoot(), "res/$layout"))
            val stubs = descendants(root).filter {
                it.getAttribute("android:id").let { id ->
                    id == "@+id/suggestion_strip_stub" || id == "@+id/emoji_panel_stub"
                }
            }
            assertEquals("$layout: both the strip and the panel stubs", 2, stubs.size)
            for (stub in stubs) {
                assertEquals("$layout: ${stub.getAttribute("android:id")} must stay full width",
                        "match_parent", stub.getAttribute("android:layout_width"))
            }
        }
    }

    @Test
    fun theSettingsRowIsAOneTapPickerWritingTheInt() {
        val host = java("rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt")
        assertTrue(host.contains("oneHandedModeRow(),"))
        assertTrue(host.contains("setItems(labels)"))
        assertTrue(host.contains("OneHandedMode.SIDES[which]"))
        assertTrue(host.contains("prefs.edit().putInt(Settings.PREF_ONE_HANDED_SIDE,"))
        assertTrue(host.contains("isRestricted(Settings.PREF_ONE_HANDED_SIDE)"))
    }

    @Test
    fun theManagedRestrictionGovernsTheSameKey() {
        assertTrue(res("xml/app_restrictions.xml").contains(
            "android:key=\"pref_one_handed_side\""))
        assertTrue(settings.contains("case PREF_ONE_HANDED_SIDE:"))
    }

    @Test
    fun theFourLabelsExistInAllThreeLocales() {
        for (key in listOf("one_handed_mode", "one_handed_mode_off",
                "one_handed_mode_left", "one_handed_mode_right")) {
            for (dir in listOf("values", "values-ru", "values-tt")) {
                assertTrue("$dir misses $key",
                    res("$dir/strings.xml").contains("name=\"$key\""))
            }
        }
    }
}
