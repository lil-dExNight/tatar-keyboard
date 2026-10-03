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

package rkr.simplekeyboard.inputmethod.latin.emoji

/**
 * Gesture helpers of [EmojiPanelView] as `internal` extension functions: the skin-tone long-press
 * arming and its cancel, and the sideways-flick section jump. The long-press timeout, runnable,
 * skin-tone table, scroller, jump duration and motion policy are `internal` on the view so these
 * can reach them.
 */

/** Arms the skin-tone long press, but only over a cell whose emoji actually has tones. */
internal fun EmojiPanelView.maybeArmLongPress(target: Int) {
    cancelSkinTonePopupTimer()
    if (skinTones.isEmpty || !EmojiPanelState.isCell(target) || state.isPopupOpen()) {
        return
    }
    if (!skinTones.hasTones(state.entryAt(target))) {
        return
    }
    postDelayed(longPressRunnable, longPressTimeoutMs)
}

internal fun EmojiPanelView.cancelSkinTonePopupTimer() {
    removeCallbacks(longPressRunnable)
}

/**
 * Animates a sideways flick into a jump to the neighboring section. The same single scroller
 * that carries a fling carries this, so there is still no second animator and no allocation.
 * With a zero system animator scale the jump gets a zero duration: the scroller then lands on
 * the target in its first computeScroll pass, so the jump reads as a teleport (WCAG 2.3.3).
 */
internal fun EmojiPanelView.maybeJumpSection(direction: Int) {
    if (direction == 0) return
    val sections = state.sectionCount()
    if (sections <= 0) return
    val target = (state.activeCategory() + direction).coerceIn(0, sections - 1)
    val from = state.scrollY()
    val to = state.sectionTop(target).coerceIn(0, state.maxScrollY())
    if (to == from) return
    scroller.forceFinished(true)
    val durationMs = if (motionPolicy?.animationsEnabled == false) 0 else EmojiPanelView.SECTION_JUMP_MS
    scroller.startScroll(0, from, 0, to - from, durationMs)
    postInvalidateOnAnimation()
}
