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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for the panel's accessibility delegate, its resilience to input-view
 * recreation, and its memory release. TalkBack behavior needs a device test; this guards the
 * code shape, like [EmojiPanelSourceContractTest] and `SuggestionStripSourceContractTest`.
 */
class EmojiPanelAccessibilitySourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val panel by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelView.kt")
    }
    private val keyboardSwitcher by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java")
    }
    private val latinIme by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
    }
    private val inputView by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/InputView.java")
    }

    // --- ExploreByTouchHelper on the panel -----------------------------------------------------

    @Test
    fun panelUsesTheForkedExploreByTouchHelper() {
        assertTrue(panel.contains("import rkr.simplekeyboard.inputmethod.compat.ExploreByTouchHelper"))
        assertTrue(panel.contains("ExploreByTouchHelper(this@EmojiPanelView)"))
        assertTrue(panel.contains("setAccessibilityDelegate(accessibilityHelper)"))
        assertTrue(panel.contains("importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES"))
        // Hover events are routed to the helper, like the suggestion strip does.
        assertTrue(panel.contains("accessibilityHelper.dispatchHoverEvent(event)"))
        // The helper is the AOSP fork in our compat package; the app has no androidx.customview
        // dependency.
        val gradle = listOf(File("build.gradle"), File("app/build.gradle"))
            .firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate app/build.gradle")
        assertFalse(gradle.contains("androidx.customview:customview"))
    }

    @Test
    fun virtualNodesAreVisibleCellsPlusTabsPlusTwoFunctionalKeysFromTheSameGeometry() {
        val body = panel.substringAfter("override fun getVisibleVirtualViews(")
            .substringBefore("override fun onPopulateNodeForHost(")
        // Built from the SAME hit-test geometry the grid draws with, not a second geometry.
        // While the skin-tone popup is up the tree is exactly its variants and nothing else.
        assertTrue(body.contains("state.isPopupOpen()"))
        assertTrue(body.contains("virtualViewIds.add(POPUP_ID_BASE + variant)"))
        assertTrue(body.contains("state.firstVisibleSection()"))
        assertTrue(body.contains("state.lastVisibleSection()"))
        assertTrue(body.contains("state.firstVisibleRowOf(section)"))
        assertTrue(body.contains("state.lastVisibleRowOf(section)"))
        assertTrue(body.contains("state.columnCount()"))
        assertTrue(body.contains("state.sectionEntryCount(section)"))
        assertTrue(body.contains("state.tabCount()"))
        // The search cell and the two functional keys are always present.
        assertTrue(body.contains("virtualViewIds.add(SEARCH_ID)"))
        assertTrue(body.contains("virtualViewIds.add(BACK_ID)"))
        assertTrue(body.contains("virtualViewIds.add(DELETE_ID)"))
        // getVirtualViewAt reuses the pure hit-test, no second geometry.
        assertTrue(panel.contains("targetToVirtualId(state.targetAt(x, y))"))
    }

    @Test
    fun cellContentDescriptionIsTheSpokenNameWithASequenceFallback() {
        // A cell speaks its short name from the search index in the bound language; the sequence
        // itself is the fallback before the index is bound, or for an emoji it does not name.
        assertTrue(panel.contains("fun spokenText(sequence: String): String ="))
        assertTrue(panel.contains("spokenNameIndex.nameOf(sequence, spokenNameLanguage) ?: sequence"))
        assertTrue(panel.contains("node.contentDescription = spokenText(state.entryAt(virtualViewId))"))
        assertTrue(panel.contains("spokenText(popupVariants.getOrElse(variant) { popupBase })"))
        // The names arrive through the controller's surface seam, never a second asset read.
        assertTrue(panel.contains("fun setSpokenNames(names: EmojiSearchIndex, language: String?)"))
        // No other emoji-name database is referenced anywhere in the panel.
        for (forbidden in listOf("emoji_names", "cldr", "R.array.")) {
            assertFalse("panel references a name list via $forbidden", panel.contains(forbidden))
        }
    }

    @Test
    fun tabsAndTwoFunctionalKeysGetLocalizedDescriptions() {
        // Tabs -> localized category names; the two functional keys reuse existing localized keys.
        assertTrue(panel.contains("categoryTitle("))
        assertTrue(panel.contains("R.string.spoken_emoji_category_recent"))
        assertTrue(panel.contains("R.string.spoken_emoji_category_smileys"))
        assertTrue(panel.contains("R.string.spoken_description_to_alpha"))
        assertTrue(panel.contains("R.string.spoken_description_delete"))
        // The localized category strings exist in the base and both project locales.
        for (dir in listOf("values", "values-ru", "values-tt")) {
            val a11y = File(sourceRoot(), "res/$dir/strings-a11y.xml").readText()
            for (name in listOf(
                "spoken_emoji_category_recent",
                "spoken_emoji_category_smileys",
                "spoken_emoji_category_people",
                "spoken_emoji_category_animals",
                "spoken_emoji_category_food",
                "spoken_emoji_category_travel",
                "spoken_emoji_category_activities",
                "spoken_emoji_category_objects",
                "spoken_emoji_category_symbols",
                "spoken_emoji_category_flags",
            )) {
                assertTrue("$dir missing $name", a11y.contains("\"$name\""))
            }
        }
    }

    @Test
    fun rootNodeExposesScrollActionsAndTheyMoveTheGrid() {
        val hostBody = panel.substringAfter("override fun onPopulateNodeForHost(")
            .substringBefore("override fun onPopulateNodeForVirtualView(")
        assertTrue(hostBody.contains("node.isScrollable = true"))
        assertTrue(hostBody.contains("AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"))
        assertTrue(hostBody.contains("AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD"))
        val performBody = panel.substringAfter("override fun performAccessibilityAction(action: Int")
        assertTrue(performBody.contains("ACTION_SCROLL_FORWARD"))
        assertTrue(performBody.contains("ACTION_SCROLL_BACKWARD"))
        assertTrue(performBody.contains("scrollOneViewport"))
    }

    @Test
    fun nodeClickRunsTheSameActionAsATapThroughTheSameListenerPath() {
        val activate = panel.substringAfter("private fun activateForAccessibility(")
            .substringBefore("private fun scrollOneViewport(")
        // The exact same listener calls the touch path uses — no second insertion/deletion route.
        assertTrue(activate.contains("onEmojiPanelPick(state.entryAt(target))"))
        assertTrue(activate.contains("onEmojiPanelBackToKeyboard()"))
        assertTrue(activate.contains("onEmojiPanelDelete()"))
        assertTrue(activate.contains("setActiveCategory(EmojiPanelState.tabIndexOf(target))"))
        // The click handler routes through activateForAccessibility, not a private commit/delete.
        val perform = panel.substringAfter("override fun onPerformActionForVirtualView(")
            .substringBefore("private fun targetToVirtualId(")
        assertTrue(perform.contains("AccessibilityNodeInfo.ACTION_CLICK"))
        assertTrue(perform.contains("activateForAccessibility(virtualIdToTarget(virtualViewId))"))
        assertTrue(perform.contains("sendEventForVirtualView(virtualViewId"))
        // No commit/delete/log of its own anywhere in the panel (mirrors EmojiPanelSourceContract).
        for (forbidden in listOf("commitText", "deleteSurroundingText", "deleteTextBeforeCursor")) {
            assertFalse("panel contains $forbidden", panel.contains(forbidden))
        }
    }

    @Test
    fun invalidateRootRunsOnlyThroughTheTouchExplorationGate() {
        // Every invalidateRoot() call goes through the single guarded helper, so it never fires
        // while touch exploration is off (this is what the TalkBack "no invalidateRoot on scroll
        // while exploration off" requirement looks like at the source level).
        assertEquals(
            "invalidateRoot() must be called from exactly one place",
            1,
            "\\.invalidateRoot\\(\\)".toRegex().findAll(panel).count(),
        )
        val guard = panel.substringAfter("private fun invalidateAccessibilityRootIfExploring()")
            .substringBefore("private fun activateForAccessibility(")
        assertTrue(guard.contains("accessibilityManager.isTouchExplorationEnabled"))
        assertTrue(guard.contains("accessibilityHelper.invalidateRoot()"))
        // It is invoked on a category change and on scroll settle.
        assertTrue(panel.contains("if (state.setActiveCategory(EmojiPanelState.tabIndexOf(target))) {"))
        val compute = panel.substringAfter("override fun computeScroll()")
            .substringBefore("override fun onDraw(")
        assertTrue(compute.contains("invalidateAccessibilityRootIfExploring()"))
    }

    // --- Input-view recreation resilience ------------------------------------------------------

    @Test
    fun openPanelStateNeverSurvivesInputViewRecreation() {
        // onCreateInputView resets the "shown" flag; release() drops the panel; the controller
        // drops any deferred show. The letters come back on recreation.
        val onCreate = keyboardSwitcher.substringAfter("public View onCreateInputView()")
            .substringBefore("private void setKeyboard")
        assertTrue(onCreate.contains("mEmojiPanelShown = false"))
        assertTrue(inputView.contains("mEmojiPanelView.release()"))
        assertTrue(latinIme.contains("mEmojiPanelController.onInputViewRecreated()"))
    }

    // --- Memory release (MSG_DEALLOCATE_MEMORY / onFinishInputView) -----------------------------

    @Test
    fun panelReleasesItsSnapshotAndCachesOnMemoryPressureAndHoldsNoOffscreenBitmap() {
        // The view frees its bound snapshot and layout caches; it never allocates a Bitmap.
        val releaseCaches = panel.substringAfter("fun releaseSnapshotCaches()")
            .substringBefore("override fun onMeasure")
        assertTrue(releaseCaches.contains("state.setSnapshot(EmojiSetSnapshot.EMPTY)"))
        assertTrue(releaseCaches.contains("tabLabels = emptyArray()"))
        assertTrue(releaseCaches.contains("tabNames = emptyArray()"))
        // The spoken-name index goes with the rest, or the idle release would not free it; the
        // next publish re-seeds it through setSpokenNames.
        assertTrue(releaseCaches.contains("spokenNameIndex = EmojiSearchIndex.EMPTY"))
        assertTrue(releaseCaches.contains("spokenNameLanguage = null"))
        // No-op while shown so it never blanks a live grid.
        assertTrue(releaseCaches.contains("if (visibility == VISIBLE)"))
        assertFalse("panel must hold no offscreen Bitmap", panel.contains("Bitmap"))

        // MSG_DEALLOCATE_MEMORY and onFinishInputView both reach the release through the switcher.
        assertTrue(latinIme.contains("latinIme.deallocateMemory()"))
        val deallocate = latinIme.substringAfter("protected void deallocateMemory()")
            .substringBefore("public void onUpdateSelection")
        assertTrue(deallocate.contains("mKeyboardSwitcher.deallocateMemory()"))
        val switcherDealloc = keyboardSwitcher.substringAfter("public void deallocateMemory()")
            .substringBefore("public void releaseEmojiPanelCaches()")
        assertTrue(switcherDealloc.contains("releaseEmojiPanelCaches()"))
        val releaseHelper = keyboardSwitcher.substringAfter("public void releaseEmojiPanelCaches()")
            .substringBefore("public View onCreateInputView()")
        assertTrue(releaseHelper.contains("mEmojiPanelShown"))
        assertTrue(releaseHelper.contains("panel.releaseSnapshotCaches()"))
        // onFinishInputView releases too.
        val finishBody = latinIme.substringAfter("void onFinishInputViewInternal(final boolean finishingInput)")
            .substringBefore("protected void deallocateMemory()")
        assertTrue(finishBody.contains("mKeyboardSwitcher.releaseEmojiPanelCaches()"))
    }

    // --- Spoken cell names (the search index's ru/tt channels) ----------------------------------

    @Test
    fun spokenNamesFlowFromTheSharedSearchIndexThroughTheSwitcherToThePanel() {
        val controller = java("rkr/simplekeyboard/inputmethod/latin/emoji/EmojiPanelController.kt")
        // The controller starts the one-per-process index load when the panel is published and
        // binds the names through the surface seam, alongside the search's own load.
        val publish = controller.substringAfter("private fun publish(")
            .substringBefore("private fun ensureSpokenNamesIndex()")
        assertTrue(publish.contains("ensureSpokenNamesIndex()"))
        val load = controller.substringAfter("private fun onSearchIndexLoaded(")
            .substringBefore("fun searchIndexOrNull()")
        assertTrue(load.contains("surface.bindSpokenNames(loaded, spokenNameLanguage)"))
        // The language follows the active subtype, exactly like the search results'.
        val show = latinIme.substringAfter("public void showEmojiPanel()")
            .substringBefore("public void onEmojiSearchUnavailable()")
        assertTrue(show.contains("mEmojiPanelController.setSpokenNameLanguage("))
        assertTrue(show.contains("EmojiSuggestIndex.assetLanguageOf("))
        // The switcher holds the index like the tone table and re-binds it on every show.
        assertTrue(keyboardSwitcher.contains("public void bindEmojiSpokenNames("))
        val showPanel = keyboardSwitcher.substringAfter("public void showEmojiPanel(final EmojiSetSnapshot snapshot)")
            .substringBefore("public void bindEmojiSkinTones(")
        assertTrue(showPanel.contains("panel.setSpokenNames(mEmojiSpokenNames, mEmojiSpokenNameLanguage)"))
        // The idle release drops the held copy, like the controller drops its index.
        val release = keyboardSwitcher.substringAfter("public void releaseEmojiPanelCaches()")
            .substringBefore("public View onCreateInputView()")
        assertTrue(release.contains("mEmojiSpokenNames = null"))
    }

    @Test
    fun spokenNameLookupStaysOffTheDrawPathAndNeverShipsASecondAsset() {
        val drawBody = panel.substringAfter("override fun onDraw")
            .substringBefore("@Suppress(\"ClickableViewAccessibility\")")
        assertFalse("onDraw must not look up spoken names", drawBody.contains("spokenText"))
        assertFalse("onDraw must not touch the name index", drawBody.contains("nameOf"))
        // The lookup is delegated to EmojiSearchIndex.nameOf; no second parse or asset read.
        for (forbidden in listOf("EmojiSearchIndex.parse", "assets.open")) {
            assertFalse("panel must not $forbidden", panel.contains(forbidden))
        }
    }
}
