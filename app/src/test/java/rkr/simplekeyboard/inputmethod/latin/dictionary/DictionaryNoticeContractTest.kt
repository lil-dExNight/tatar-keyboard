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

package rkr.simplekeyboard.inputmethod.latin.dictionary

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corpus attribution ships as `NOTICE.txt` next to the bundled dictionaries: Leipzig
 * (CC BY 4.0), Tatoeba (CC BY 2.0 FR) and the link OpenSubtitles asks for, with the absent
 * license grant stated plainly. There is no data-sources screen in the settings UI; this
 * notice is the whole attribution surface, so its key statements are pinned here.
 */
class DictionaryNoticeContractTest {

    private val dictionaryNotice by lazy {
        val root = listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        File(root, "assets/dictionaries/NOTICE.txt").readText()
    }

    /** The link OpenSubtitles asks for, spelled out, and the grant's absence said aloud. */
    @Test
    fun the_opensubtitles_link_and_the_absent_grant_are_stated() {
        assertTrue("opensubtitles.org must be linked",
            dictionaryNotice.contains("http://www.opensubtitles.org/"))
        assertTrue("NOTICE.txt must say plainly that there is no licence grant, not soften it",
            dictionaryNotice.contains("NO license grant"))
    }

    /** The conversational sources are packed into the assets, not queued for a later version. */
    @Test
    fun the_conversational_sources_are_marked_as_packed() {
        assertTrue(dictionaryNotice.contains("PACKED since 1.9.0"))
        assertFalse(dictionaryNotice.contains("NOT yet packed"))
    }
}
