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

/**
 * Arms the long-press timer: over a cell whose emoji has tones, for the skin-tone popup; over a
 * Recent-section cell without tones, for removing its entry; over the Recent tab, for clearing
 * the recents. Every other target disarms.
 */
internal fun EmojiPanelView.maybeArmLongPress(target: Int) {
    cancelSkinTonePopupTimer()
    if (state.isPopupOpen()) {
        return
    }
    if (EmojiPanelState.isCell(target)) {
        val hasTonePicker = !skinTones.isEmpty && skinTones.hasTones(state.entryAt(target))
        val removal = !hasTonePicker && isRecentCell(target)
        if (hasTonePicker || removal) {
            postDelayed(longPressRunnable, longPressTimeoutMs)
        }
        return
    }
    // The Recent tab (tab 0 whenever the recents are shown) long-press clears the recents.
    if (EmojiPanelState.isTab(target) && hasRecentTab &&
        EmojiPanelState.tabIndexOf(target) == 0
    ) {
        postDelayed(clearRecentsRunnable, longPressTimeoutMs)
    }
}

/**
 * Cancels every armed panel long-press — the skin-tone popup and the Recent-tab clear. Called on
 * every stop condition (move off the target, up, cancel, hide, release), so at most one long
 * press can ever fire per gesture.
 */
internal fun EmojiPanelView.cancelSkinTonePopupTimer() {
    removeCallbacks(longPressRunnable)
    removeCallbacks(clearRecentsRunnable)
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
