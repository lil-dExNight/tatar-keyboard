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

package rkr.simplekeyboard.inputmethod.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import rkr.simplekeyboard.inputmethod.compat.ExploreByTouchHelper
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.Key
import rkr.simplekeyboard.inputmethod.keyboard.KeyDetector
import rkr.simplekeyboard.inputmethod.keyboard.MoreKeysKeyboardView

/**
 * ExploreByTouchHelper for the long-press (moreKeys) panel, mirroring
 * KeyboardAccessibilityDelegate on MainKeyboardView: one virtual node per
 * panel key (id = index in getSortedKeys()), descriptions from
 * KeyDescriptionMapper. ACTION_CLICK commits through the panel's regular
 * selection path — MoreKeysKeyboardView.onDownEvent/onUpEvent at the key's
 * center — so press graphics and onKeyInput fire exactly as for a finger,
 * then dismisses the panel like PointerTracker does after a real up event.
 *
 * The panel view itself is created once per MainKeyboardView and reused; only
 * its keyboard changes on every long-press, so the host view calls
 * [onPanelShown] each time the panel is (re)shown to refresh the virtual
 * hierarchy and announce the panel to TalkBack (AOSP
 * spoken_open_more_keys_keyboard pattern), and [onPanelDismissed] when it
 * leaves the screen.
 */
class MoreKeysKeyboardAccessibilityDelegate(
    private val panelView: MoreKeysKeyboardView,
    private val keyDetector: KeyDetector,
    /**
     * KeyboardView#getVerticalCorrection() of the panel view. The detector is
     * calibrated for fingers: it shifts every incoming point up by this much
     * (config_more_keys_keyboard_vertical_correction, negative) before hit
     * testing. Accessibility works with visual coordinates — node bounds, key
     * centers — so every point handed to the detector must be pre-shifted by
     * -verticalCorrection, or on multi-row panels hover and click resolve to
     * the key one row above (rows are shorter than the correction).
     */
    private val verticalCorrection: Float,
) : ExploreByTouchHelper(panelView) {

    private val tempBounds = Rect()

    private fun sortedKeys(): List<Key> =
        panelView.keyboard?.sortedKeys ?: emptyList()

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        // MoreKeysDetector snaps to the nearest key within the slide
        // allowance, so hovering just outside the panel edge still resolves
        // to the closest key — same forgiveness as sliding a finger. The
        // vertical correction is compensated so hover matches the visual node
        // bounds instead of the finger-calibrated hitboxes.
        val key = keyDetector.detectHitKey(x.toInt(), (y - verticalCorrection).toInt())
            ?: return INVALID_ID
        val index = sortedKeys().indexOf(key)
        return if (index >= 0) index else INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        val keys = sortedKeys()
        for (i in keys.indices) {
            if (!keys[i].isSpacer) {
                virtualViewIds.add(i)
            }
        }
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfo,
    ) {
        val keys = sortedKeys()
        val key = keys.getOrNull(virtualViewId)
        val keyboard = panelView.keyboard
        if (key == null || key.isSpacer || keyboard == null) {
            // The helper requires non-empty content and bounds even for stale ids.
            node.contentDescription = ""
            tempBounds.set(0, 0, 1, 1)
            node.setBoundsInParent(tempBounds)
            return
        }
        node.contentDescription =
            KeyDescriptionMapper.getDescription(panelView.context, keyboard, key)
        tempBounds.set(
            key.x + panelView.paddingLeft,
            key.y + panelView.paddingTop,
            key.x + panelView.paddingLeft + key.width,
            key.y + panelView.paddingTop + key.height,
        )
        node.setBoundsInParent(tempBounds)
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.isClickable = true
        // Same rationale as MainKeyboardView's delegate: every key of an IME
        // is a text entry key so TalkBack enables lift-to-type on the panel.
        // The API 29 gate mirrors the androidx compat (a no-op below 29) — see the sibling.
        if (Build.VERSION.SDK_INT >= 29) node.isTextEntryKey = true
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
        // A stale click after the panel already left the screen must not
        // reach onKeyInput: the action listener is only valid while showing.
        if (!panelView.isShowingInParent) return false
        val key = sortedKeys().getOrNull(virtualViewId)?.takeUnless { it.isSpacer } ?: return false
        val x = key.x + key.width / 2 + panelView.paddingLeft
        // Compensate the detector's vertical correction so the synthetic tap
        // lands on this key's center, not one row above (see constructor doc).
        val y = key.y + key.height / 2 + panelView.paddingTop - verticalCorrection.toInt()
        // The regular selection path: down/up at the key center runs
        // detectKey → press/release graphics → onKeyInput → listener commit.
        panelView.onDownEvent(x, y, 0 /* pointerId */)
        panelView.onUpEvent(x, y, 0 /* pointerId */)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        // PointerTracker dismisses the panel right after a real up event;
        // do the same for the accessibility path.
        panelView.dismissMoreKeysPanel()
        return true
    }

    /** Called every time the (reused) panel view is shown with a fresh keyboard. */
    fun onPanelShown() {
        invalidateRoot()
        panelView.announceForAccessibility(
            panelView.context.getString(R.string.spoken_open_more_keys_keyboard))
    }

    /** Called when the panel leaves the screen, whatever the dismissal path. */
    fun onPanelDismissed() {
        panelView.announceForAccessibility(
            panelView.context.getString(R.string.spoken_close_more_keys_keyboard))
    }
}
