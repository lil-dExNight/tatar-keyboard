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

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import rkr.simplekeyboard.inputmethod.compat.ExploreByTouchHelper
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.Key
import rkr.simplekeyboard.inputmethod.keyboard.KeyDetector
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView

/**
 * ExploreByTouchHelper implementation: exposes each keyboard key as a virtual accessibility
 * node. Virtual view id = index of the key in keyboard.getSortedKeys(). Descriptions come
 * from KeyDescriptionMapper; ACTION_CLICK synthesizes a DOWN/UP MotionEvent pair at the
 * key's visible center into MainKeyboardView.processMotionEvent, so TalkBack input rides
 * the regular touch path (shift state machine, haptics, preview) exactly like a finger.
 */
class KeyboardAccessibilityDelegate(
    private val keyboardView: MainKeyboardView,
    private val keyDetector: KeyDetector,
) : ExploreByTouchHelper(keyboardView) {

    private val tempBounds = Rect()
    private val accessibilityManager = keyboardView.context
        .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    /** Lazily filled resId → string cache so keyboard switches while
     * accessibility is on never re-resolve resources (no allocations on the
     * repeated announce path; the delegate lives as long as the view). */
    private val announceCache = SparseArray<String>(3)

    private fun cachedString(resId: Int): String {
        var text = announceCache.get(resId)
        if (text == null) {
            text = keyboardView.context.getString(resId)
            announceCache.put(resId, text)
        }
        return text
    }

    private fun sortedKeys(): List<Key> =
        keyboardView.keyboard?.sortedKeys ?: emptyList()

    /**
     * Announces shift-mode changes (AOSP spoken_description_shiftmode_*
     * pattern), called by MainKeyboardView.setKeyboard on every keyboard
     * swap. Transitions between ALPHABET and ALPHABET_AUTOMATIC_SHIFTED are
     * deliberately silent, as in AOSP: auto-caps flips on every sentence
     * start and would drown the user in announcements.
     */
    fun onKeyboardChanged(lastKeyboard: Keyboard?, newKeyboard: Keyboard) {
        if (!accessibilityManager.isEnabled) return
        val lastElementId = lastKeyboard?.mId?.mElementId ?: return
        val newElementId = newKeyboard.mId.mElementId
        if (lastElementId == newElementId) return
        val resId = when (newElementId) {
            KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED ->
                R.string.spoken_description_shiftmode_on
            KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED ->
                R.string.spoken_description_shiftmode_locked
            KeyboardId.ELEMENT_ALPHABET -> when (lastElementId) {
                KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED,
                KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED,
                -> R.string.spoken_description_shiftmode_off
                // Auto-shift release or a return from symbols — not a
                // shift-mode change the user performed.
                else -> return
            }
            else -> return
        }
        keyboardView.announceForAccessibility(cachedString(resId))
    }

    /**
     * Announces the input language after a user-initiated subtype switch (globe key or the
     * subtype picker). The name is rendered in its own locale ("Татарча" / "Русская" /
     * "English") — the same source the spacebar hint and the space key description use.
     * Programmatic subtype changes are filtered out by the caller and never reach here.
     */
    fun announceLanguage(languageName: String) {
        if (!accessibilityManager.isEnabled) return
        keyboardView.announceForAccessibility(languageName)
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val key = keyDetector.detectHitKey(x.toInt(), y.toInt()) ?: return INVALID_ID
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
        val keyboard = keyboardView.keyboard
        if (key == null || key.isSpacer || keyboard == null) {
            // The helper requires non-empty content and bounds even for stale ids.
            node.contentDescription = ""
            tempBounds.set(0, 0, 1, 1)
            node.setBoundsInParent(tempBounds)
            return
        }
        node.contentDescription =
            KeyDescriptionMapper.getDescription(keyboardView.context, keyboard, key)
        tempBounds.set(
            key.x + keyboardView.paddingLeft,
            key.y + keyboardView.paddingTop,
            key.x + keyboardView.paddingLeft + key.width,
            key.y + keyboardView.paddingTop + key.height,
        )
        node.setBoundsInParent(tempBounds)
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.isClickable = true
        // Set on every key, not just letters: the framework contract for this flag is "a
        // text entry key that is part of a keyboard or keypad", i.e. any key of an IME.
        // TalkBack uses it to enable lift-to-type across the whole keyboard (including
        // delete/shift); restricting it to letters would break lift-to-type on delete.
        // Matches Gboard/LatinIME. Do not "fix" to letters-only.
        // The gate is the androidx compat's own semantics: AccessibilityNodeInfoCompat
        // setTextEntryKey is a no-op below API 29, so gating keeps the port byte-identical.
        if (Build.VERSION.SDK_INT >= 29) node.isTextEntryKey = true
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
        val key = sortedKeys().getOrNull(virtualViewId)?.takeUnless { it.isSpacer } ?: return false
        // Visible center of the key in view coordinates (mHitbox is private in the fork;
        // the visible center is always inside the hitbox).
        val x = key.x + key.width / 2 + keyboardView.paddingLeft
        val y = key.y + key.height / 2 + keyboardView.paddingTop
        val t = SystemClock.uptimeMillis()
        for (a in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, t, a, x.toFloat(), y.toFloat(), 0)
            keyboardView.processMotionEvent(ev)
            ev.recycle()
        }
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }
}
