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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skin-tone glyph probe: one toned form per base is checked against the font, once per
 * process, and a base whose tones the font cannot draw loses its tone picker and every toned form
 * in recents and recording.
 */
class EmojiToneProbeTest {

    private val hand = "\u270B"
    private val thumb = "\uD83D\uDC4D"
    private val handLight = "\u270B\uD83C\uDFFB"
    private val thumbLight = "\uD83D\uDC4D\uD83C\uDFFB"
    private val thumbDark = "\uD83D\uDC4D\uD83C\uDFFF"
    private val tones = EmojiSkinTones.parse("$hand\t$hand\t\n$thumb\t$thumb\t\n")
    private val base = EmojiSet.parse("#people\n$hand\n$thumb")

    /** Draws every sequence except the toned forms of [undrawableBase]; counts the probes. */
    private class FontWithoutTonesOf(private val undrawableBase: String) : GlyphProbe {
        val probed = mutableListOf<String>()

        override fun hasGlyph(sequence: String): Boolean {
            probed.add(sequence)
            return !(sequence.startsWith(undrawableBase) && sequence != undrawableBase)
        }
    }

    // --- The table ---------------------------------------------------------------------------

    @Test
    fun oneProbePerBaseFindsTheBasesWithoutToneGlyphs() {
        val font = FontWithoutTonesOf(hand)

        assertEquals(setOf(hand), tones.basesWithoutToneGlyph(font))
        assertEquals(listOf(handLight, thumbLight).sorted(), font.probed.sorted())
    }

    @Test
    fun aProbeErrorCountsAsCannotDraw() {
        val broken = GlyphProbe { throw IllegalStateException("font service gone") }

        assertEquals(setOf(hand, thumb), tones.basesWithoutToneGlyph(broken))
    }

    @Test
    fun theFilteredTableKeepsOnlyTheDrawableBases() {
        val filtered = tones.without(setOf(hand))

        assertFalse(filtered.hasTones(hand))
        assertTrue(filtered.hasTones(thumb))
        assertEquals(thumbDark, filtered.variantAt(thumb, EmojiSkinTones.TONE_COUNT))
        assertEquals(hand, filtered.variantAt(hand, 1))
        assertFalse(filtered.allTonedSequences().contains(handLight))
        assertSame(tones, tones.without(emptySet()))
        assertTrue(tones.without(setOf(hand, thumb)).isEmpty)
    }

    // --- The process verdicts ----------------------------------------------------------------

    @Test
    fun theVerdictsProbeOnceAndAreReused() {
        val verdicts = EmojiToneVerdicts()
        var probes = 0
        val font = FontWithoutTonesOf(hand)
        val first = verdicts.drawable(tones) {
            probes++
            font
        }
        val second = verdicts.drawable(tones) {
            probes++
            font
        }

        assertEquals(1, probes)
        assertFalse(first.hasTones(hand))
        assertFalse(second.hasTones(hand))
        assertTrue(second.hasTones(thumb))
        assertTrue(verdicts.isUndrawableToned(handLight))
        assertFalse(verdicts.isUndrawableToned(thumbLight))
        assertFalse(verdicts.isUndrawableToned(hand))
    }

    @Test
    fun withoutAProbeNothingIsKnownOrFiltered() {
        val verdicts = EmojiToneVerdicts()

        assertSame(tones, verdicts.drawable(tones) { null })
        assertFalse(verdicts.isKnown)
        assertFalse(verdicts.isUndrawableToned(handLight))
    }

    // --- The panel ---------------------------------------------------------------------------

    private class FakeSurface : EmojiSurface {
        val shown = ArrayList<EmojiSetSnapshot>()
        var boundTones: EmojiSkinTones? = null

        override fun showPanel(snapshot: EmojiSetSnapshot) {
            shown.add(snapshot)
        }

        override fun bindSkinTones(tones: EmojiSkinTones) {
            boundTones = tones
        }
    }

    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {
            stopped = true
        }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class FakeFileOps(var content: String? = null) : RecentEmojiFileOps {
        override fun read(file: File): String? = content
        override fun writeAtomic(file: File, content: String) {
            this.content = content
        }
        override fun isAbsentOrEmpty(file: File): Boolean = content.isNullOrEmpty()
    }

    private val openGate = RecentEmojiGate {
        RecentEmojiGateState(
            shouldShowSuggestions = true,
            userUnlocked = true,
            noPersonalizedLearning = false,
            keyguardLocked = false,
            incognito = false,
        )
    }

    private fun panel(surface: FakeSurface, ops: FakeFileOps, probe: GlyphProbe?): EmojiPanelController {
        val executor = DirectExecutor()
        val store = RecentEmojiStore(RecentEmojiFileProvider { File("recent_emoji_tone_test") }, ops, openGate)
        return EmojiPanelController(
            surface,
            EmojiUiPoster { it.run() },
            { executor },
            { EmojiSnapshotSource { base } },
            { store },
            { null },
            { EmojiSkinToneSource { tones } },
            { probe },
            EmojiToneVerdicts(),
        )
    }

    @Test
    fun aBaseWithoutToneGlyphsGetsNoTonePicker() {
        val surface = FakeSurface()
        panel(surface, FakeFileOps(), FontWithoutTonesOf(hand)).onEmojiKeyPressed()

        val bound = requireNotNull(surface.boundTones)
        assertFalse(bound.hasTones(hand))
        assertTrue(bound.hasTones(thumb))
    }

    @Test
    fun recentsAndRecordingDropTheUndrawableTonedForms() {
        val surface = FakeSurface()
        // A recents file carried over from a device whose font draws both.
        val ops = FakeFileOps(listOf(handLight, thumbLight).joinToString(RecentEmojiList.SEPARATOR.toString()))
        val controller = panel(surface, ops, FontWithoutTonesOf(hand))

        controller.onEmojiKeyPressed()
        assertEquals(listOf(thumbLight), surface.shown.last().entriesOf(0))

        controller.onEmojiInserted(handLight)
        controller.onEmojiInserted(thumbDark)
        controller.onEmojiKeyPressed()
        assertEquals(listOf(thumbDark, thumbLight), surface.shown.last().entriesOf(0))
    }

    @Test
    fun aFontThatDrawsEveryToneKeepsTheWholeTable() {
        val surface = FakeSurface()
        panel(surface, FakeFileOps(), GlyphProbe { true }).onEmojiKeyPressed()

        val bound = requireNotNull(surface.boundTones)
        assertTrue(bound.hasTones(hand))
        assertTrue(bound.hasTones(thumb))
    }
}
