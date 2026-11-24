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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackEmailUriTest {
    @Test
    fun build_percent_encodes_spaces_and_reserved_characters() {
        val address = "develam.postal629@passmail.com"
        val subject = "Tatar 1.0 + сәләм? 100% & / ="

        val uri = URI.create(FeedbackEmailUri.build(address, subject))
        val rawSchemeSpecificPart = uri.rawSchemeSpecificPart
        val rawQuery = rawSchemeSpecificPart.substringAfter('?')

        assertEquals("mailto", uri.scheme)
        assertEquals(address, rawSchemeSpecificPart.substringBefore('?'))
        assertEquals("subject=Tatar%201.0%20%2B%20" +
            "%D1%81%D3%99%D0%BB%D3%99%D0%BC%3F%20100%25%20%26%20%2F%20%3D", rawQuery)
        assertEquals(subject, URLDecoder.decode(rawQuery.removePrefix("subject="), "UTF-8"))
    }
}
