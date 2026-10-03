// Copyright (C) 2026 Tatar Keyboard contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package rkr.simplekeyboard.inputmethod.keyboard

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Re-reads the dynamic theme when the wallpaper palette turns over. The wallpaper listener is
 * registered only while an input view driven by the dynamic theme exists ([sync] with
 * active=true at input-view creation) and is dropped with it ([sync] false, [release]); the
 * callback itself re-runs the same resolve path as input-view creation, so a wallpaper change
 * and a fresh input view never disagree about the palette.
 */
class DynamicThemeController(
    private val context: Context,
    private val rebuildInputView: Runnable,
) {
    private var wallpaperManager: WallpaperManager? = null
    private var listener: WallpaperManager.OnColorsChangedListener? = null

    /** Brings the registration in line with [active]. Idempotent, cheap on every call. */
    fun sync(active: Boolean) {
        if (active) {
            registerOnce()
        } else {
            release()
        }
    }

    /** Drops the listener. Safe to call when nothing is registered. */
    fun release() {
        // The listener can only be registered on API 31+ (see registerOnce); the version guard
        // keeps the wallpaper-listener API references behind the same gate.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val current = listener ?: return
        wallpaperManager?.removeOnColorsChangedListener(current)
        listener = null
        wallpaperManager = null
    }

    private fun registerOnce() {
        if (listener != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // The system colors the callback re-reads exist only on API 31+.
            return
        }
        val manager = context.getSystemService(WallpaperManager::class.java) ?: return
        val callback = WallpaperManager.OnColorsChangedListener { _, _ -> rebuildInputView.run() }
        manager.addOnColorsChangedListener(callback, Handler(Looper.getMainLooper()))
        wallpaperManager = manager
        listener = callback
    }
}
