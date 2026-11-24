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

import java.net.URLEncoder

object FeedbackEmailUri {
    /** Uses UTF-8 percent encoding and %20 for spaces in the mailto subject query. */
    fun build(address: String, subject: String): String {
        val encodedSubject = URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
        return "mailto:$address?subject=$encodedSubject"
    }
}
