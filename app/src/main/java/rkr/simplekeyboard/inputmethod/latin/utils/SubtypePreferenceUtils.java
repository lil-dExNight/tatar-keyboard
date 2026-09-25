/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2021 wittmane
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

package rkr.simplekeyboard.inputmethod.latin.utils;

import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import rkr.simplekeyboard.inputmethod.latin.Subtype;

public final class SubtypePreferenceUtils {
    private static final String TAG = SubtypePreferenceUtils.class.getSimpleName();

    private SubtypePreferenceUtils() {
        // This utility class is not publicly instantiable.
    }

    private static final String LOCALE_AND_LAYOUT_SEPARATOR = ":";
    private static final int INDEX_OF_LOCALE = 0;
    private static final int INDEX_OF_KEYBOARD_LAYOUT = 1;
    private static final int PREF_ELEMENTS_LENGTH = (INDEX_OF_KEYBOARD_LAYOUT + 1);
    public static final String PREF_SUBTYPE_SEPARATOR = ";";

    // Legacy pref values from earlier development phases that need to be migrated
    private static final String LEGACY_LOCALE_TATAR = "tt";
    private static final String LOCALE_TATAR = "tt_RU";
    private static final String LOCALE_RUSSIAN = "ru";

    public static String getPrefString(final Subtype subtype) {
        final String localeString = subtype.getLocale();
        final String keyboardLayoutSetName = subtype.getKeyboardLayoutSet();
        return localeString + LOCALE_AND_LAYOUT_SEPARATOR + keyboardLayoutSetName;
    }

    public static List<Subtype> createSubtypesFromPref(final String prefSubtypes, final Resources resources) {
        Log.i(TAG, "Loading subtypes: " + prefSubtypes);
        if (TextUtils.isEmpty(prefSubtypes)) {
            return new ArrayList<>();
        }
        final String[] prefSubtypeArray = prefSubtypes.split(PREF_SUBTYPE_SEPARATOR, 0);
        final ArrayList<Subtype> subtypesList = new ArrayList<>(prefSubtypeArray.length);
        for (final String prefSubtype : prefSubtypeArray) {
            final String[] elements = prefSubtype.split(LOCALE_AND_LAYOUT_SEPARATOR, 0);
            if (elements.length != PREF_ELEMENTS_LENGTH) {
                Log.w(TAG, "Unknown subtype specified: " + prefSubtype + " in "
                        + prefSubtypes);
                continue;
            }
            final String localeString = elements[INDEX_OF_LOCALE];
            String keyboardLayoutSetName = elements[INDEX_OF_KEYBOARD_LAYOUT];
            // migrate legacy pref entries from earlier development phases before looking
            // them up so old selections aren't silently dropped
            final String migratedLocaleString;
            if (LEGACY_LOCALE_TATAR.equals(localeString)) {
                migratedLocaleString = LOCALE_TATAR;
            } else {
                migratedLocaleString = localeString;
            }
            if (LOCALE_RUSSIAN.equals(migratedLocaleString)
                    && SubtypeLocaleUtils.LAYOUT_EAST_SLAVIC.equals(keyboardLayoutSetName)) {
                keyboardLayoutSetName = SubtypeLocaleUtils.LAYOUT_RUSSIAN;
            }
            final Subtype subtype = SubtypeLocaleUtils.getSubtype(migratedLocaleString,
                    keyboardLayoutSetName, resources);
            if (subtype == null) {
                continue;
            }
            subtypesList.add(subtype);
        }
        return subtypesList;
    }

    public static String createPrefSubtypes(final List<Subtype> subtypes) {
        if (subtypes == null || subtypes.size() == 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Subtype subtype : subtypes) {
            if (sb.length() > 0) {
                sb.append(PREF_SUBTYPE_SEPARATOR);
            }
            sb.append(getPrefString(subtype));
        }
        return sb.toString();
    }
}
