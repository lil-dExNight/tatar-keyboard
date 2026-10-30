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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recent-clip holder: the freshness window, the first-line display form with its ellipsis, and
 * the full-text commit form. Pure JVM, no Android — the clipboard listener lives in LatinIME.
 */
class RecentClipCellTest {

    private class FakeScheduler : RecentClipExpiryScheduler {
        val tasks = mutableListOf<Runnable>()

        override fun schedule(delayMillis: Long, task: Runnable) {
            tasks.add(task)
        }

        fun run(index: Int) {
            tasks[index].run()
        }
    }

    private class Harness {
        var now = 10_000_000L
        val scheduler = FakeScheduler()
        var expirations = 0
        val cell = RecentClipCell(scheduler) { now }.apply {
            onExpired = { expirations++ }
        }
    }

    @Test
    fun aClipMarkedSensitiveIsNeverOffered() {
        val extras = mapOf(RecentClipCell.EXTRA_IS_SENSITIVE to true)
        assertFalse(RecentClipCell.mayOfferClip { extras[it] ?: false })
    }

    @Test
    fun aClipWithoutTheSensitiveMarkMayBeOffered() {
        assertTrue(RecentClipCell.mayOfferClip { false })
        val unrelated = mapOf("android.content.extra.OTHER" to true)
        assertTrue(RecentClipCell.mayOfferClip { unrelated[it] ?: false })
    }

    @Test
    fun theSensitiveKeyIsThePlatformsOwn() {
        // A compile-time constant of the API 33 stubs, inlined here, so no Android runtime runs.
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, RecentClipCell.EXTRA_IS_SENSITIVE)
    }

    @Test
    fun aFreshClipIsOfferedAsItsFirstLine() {
        val h = Harness()
        h.cell.noteClip("сәләм дөнья", h.now)

        assertEquals("сәләм дөнья", h.cell.offerText())
        assertEquals("сәләм дөнья", h.cell.fullTextForCommit())
    }

    @Test
    fun aClipOlderThanTheTtlIsNeverOffered() {
        val h = Harness()
        h.cell.noteClip("сәләм", h.now)
        h.now += RecentClipCell.TTL_MILLIS + 1

        assertNull(h.cell.offerText())
        assertNull(h.cell.fullTextForCommit())
    }

    @Test
    fun aClipAtExactlyTheTtlIsStillFresh() {
        val h = Harness()
        h.cell.noteClip("сәләм", h.now)
        h.now += RecentClipCell.TTL_MILLIS

        assertEquals("сәләм", h.cell.offerText())
    }

    @Test
    fun aClipFromTheFutureIsNeverOffered() {
        // Clock skew between the clip's timestamp and the keyboard's clock fails closed.
        val h = Harness()
        h.cell.noteClip("сәләм", h.now + 5_000L)

        assertNull(h.cell.offerText())
    }

    @Test
    fun aBlankClipClearsTheOffer() {
        val h = Harness()
        h.cell.noteClip("сәләм", h.now)

        h.cell.noteClip("   ", h.now)

        assertNull(h.cell.offerText())
        assertNull(h.cell.fullTextForCommit())
    }

    @Test
    fun aMultiLineClipOffersItsFirstLineWithAnEllipsis() {
        val h = Harness()
        h.cell.noteClip("беренче юл\nикенче юл\nөченче юл", h.now)

        assertEquals("беренче юл…", h.cell.offerText())
        // The commit form is the full clip, not the display form.
        assertEquals("беренче юл\nикенче юл\nөченче юл", h.cell.fullTextForCommit())
    }

    @Test
    fun aLongFirstLineIsCappedWithAnEllipsis() {
        val h = Harness()
        val long = "сүз ".repeat(30).trim()
        h.cell.noteClip(long, h.now)

        val offer = h.cell.offerText()!!
        assertTrue(offer.endsWith("…"))
        assertTrue(offer.codePointCount(0, offer.length) <= RecentClipCell.MAX_CELL_CODE_POINTS)
    }

    @Test
    fun aClipWhoseFirstLineIsBlankOffersNothing() {
        val h = Harness()
        h.cell.noteClip("\nикенче юл", h.now)

        assertNull(h.cell.offerText())
    }

    @Test
    fun clearDropsEverything() {
        val h = Harness()
        h.cell.noteClip("сәләм", h.now)

        h.cell.clear()

        assertNull(h.cell.offerText())
        assertNull(h.cell.fullTextForCommit())
    }

    @Test
    fun aNewClipReplacesTheOldOne() {
        val h = Harness()
        h.cell.noteClip("беренче", h.now)
        h.cell.noteClip("икенче", h.now)

        assertEquals("икенче", h.cell.offerText())
    }

    @Test
    fun theExpiryTimerClearsTheClipAndNotifiesTheStrip() {
        val h = Harness()
        h.cell.noteClip("сәләм", h.now)
        h.now += RecentClipCell.TTL_MILLIS + 1

        h.scheduler.run(0)

        assertNull(h.cell.offerText())
        assertNull(h.cell.fullTextForCommit())
        assertEquals(1, h.expirations)
    }

    @Test
    fun anOlderTimerCannotClearANewerClipGeneration() {
        val h = Harness()
        val firstSetAt = h.now
        h.cell.noteClip("беренче", firstSetAt)
        h.now += 1_000L
        h.cell.noteClip("икенче", h.now)

        h.now = firstSetAt + RecentClipCell.TTL_MILLIS + 1
        h.scheduler.run(0)

        assertEquals("икенче", h.cell.offerText())
        assertEquals(0, h.expirations)

        h.now += 1_000L
        h.scheduler.run(1)

        assertNull(h.cell.fullTextForCommit())
        assertEquals(1, h.expirations)
    }
}
