/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalInventoryCategory
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalInventorySnapshot

/**
 * The personal-data inventory away from the view code: one counts-only snapshot and deletion of one
 * category at a time. File work runs on the shared personal-store worker; both callbacks arrive on
 * the UI thread through [uiPoster].
 */
internal class PersonalInventoryScreenController(
    private val context: Context,
    private val uiPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    /** Reads every category's count; no stored word is returned to the screen. */
    fun snapshot(
        subtypeIds: List<String>,
        onReady: (PersonalInventorySnapshot) -> Unit,
    ) {
        PersonalInventory.snapshot(context, subtypeIds) { snapshot ->
            uiPoster { onReady(snapshot) }
        }
    }

    /**
     * Deletes [category] and answers with the state read right after it, so the screen repaints
     * from the new counts rather than from the counts shown before the deletion.
     */
    fun clear(
        category: PersonalInventoryCategory,
        subtypeIds: List<String>,
        onFinished: (Boolean, PersonalInventorySnapshot) -> Unit,
    ) {
        PersonalInventory.clear(context, subtypeIds, category) { cleared, snapshot ->
            uiPoster { onFinished(cleared, snapshot) }
        }
    }
}
