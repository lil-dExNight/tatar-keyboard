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

/**
 * Which tone-capable emoji the system font cannot draw with a skin tone, probed once per process
 * on a background thread (one toned form per base, see [EmojiSkinTones.basesWithoutToneGlyph]).
 * The panel then offers no tone picker for such a base, and none of its toned forms is accepted
 * from recents or learned emoji. Kept in memory only, like [EmojiGlyphVerdicts]: fonts do not
 * change within a process. Until a probe ran nothing is known and nothing is rejected.
 */
internal class EmojiToneVerdicts {
    @Volatile
    private var rejectedBases: Set<String>? = null

    @Volatile
    private var rejectedToned: Set<String> = emptySet()

    /** True once a probe has run in this process. */
    val isKnown: Boolean get() = rejectedBases != null

    /**
     * [tones] without the bases whose toned forms the font cannot draw. The first call probes
     * with [probe]; a null probe leaves [tones] unfiltered and the verdicts unknown.
     */
    fun drawable(tones: EmojiSkinTones, probe: () -> GlyphProbe?): EmojiSkinTones {
        if (tones.isEmpty) return tones
        val known = rejectedBases ?: run {
            val glyphs = try {
                probe()
            } catch (_: Throwable) {
                null
            } ?: return tones
            val rejected = tones.basesWithoutToneGlyph(glyphs)
            // Written before the bases, so a reader that sees the verdicts known sees them whole.
            rejectedToned = tones.tonedSequencesOf(rejected)
            rejectedBases = rejected
            rejected
        }
        return tones.without(known)
    }

    /** True when [sequence] is a toned form the font is known not to draw. Allocation-free. */
    fun isUndrawableToned(sequence: String): Boolean = sequence in rejectedToned

    companion object {
        /** The instance shared by the panel and the strip's emoji cell. */
        val PROCESS = EmojiToneVerdicts()
    }
}
