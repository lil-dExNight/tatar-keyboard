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
 * The three small gesture helpers of [EmojiPanelView] (T2 part 3, docs/ROADMAP-P6.md): the
 * skin-tone long-press arming (and its cancel) and the sideways-flick section jump — moved
 * verbatim out of the view as `internal` extension functions, so the touch handler's call sites
 * kept their exact text. The long-press timeout, the runnable, the skin-tone table, the scroller
 * and the jump-duration constant are `internal` on the view for the same mechanical reason the
 * painters' paints are (parts 1–2 record the pattern).
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
 * Animates a sideways flick into a jump to the neighbouring section. The same single scroller
 * that carries a fling carries this, so there is still no second animator and no allocation.
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
    scroller.startScroll(0, from, 0, to - from, EmojiPanelView.SECTION_JUMP_MS)
    postInvalidateOnAnimation()
}
