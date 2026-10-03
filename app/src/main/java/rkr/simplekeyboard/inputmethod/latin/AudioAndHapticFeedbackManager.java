/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 *
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private static final long TICK_FREQUENCY = 100;
    // One background thread for the process, started by the first init. A key press posts a
    // prebuilt Runnable; Handler.post takes its Message from the platform pool, so a press
    // allocates nothing here.
    private Handler mBackgroundHandler;
    private volatile AudioManager mAudioManager;
    private volatile Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private volatile float mKeypressSoundVolume;
    private long mLastTickTime = 0;

    private final Runnable mStandardSound = new KeypressSound(AudioManager.FX_KEYPRESS_STANDARD);
    private final Runnable mDeleteSound = new KeypressSound(AudioManager.FX_KEYPRESS_DELETE);
    private final Runnable mReturnSound = new KeypressSound(AudioManager.FX_KEYPRESS_RETURN);
    private final Runnable mSpacebarSound = new KeypressSound(AudioManager.FX_KEYPRESS_SPACEBAR);
    // API 29+ only, built once by initInternal; null below API 29.
    private Runnable mClickVibration;
    private Runnable mTickVibration;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        if (mBackgroundHandler == null) {
            final HandlerThread thread = new HandlerThread("KeyFeedback");
            thread.start();
            mBackgroundHandler = new Handler(thread.getLooper());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mClickVibration == null) {
            mClickVibration = new Vibration(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            mTickVibration = new Vibration(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        }
        mBackgroundHandler.post(() -> {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        });
    }

    public boolean hasVibrator() {
        final Vibrator vibrator = mVibrator;
        return vibrator != null && vibrator.hasVibrator();
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code) {
        // if mAudioManager is null, we can't play a sound anyway, so return
        if (mAudioManager == null) {
            return;
        }
        if (!mSoundOn) {
            return;
        }
        final Runnable sound;
        switch (code) {
        case Constants.CODE_DELETE:
            sound = mDeleteSound;
            break;
        case Constants.CODE_ENTER:
            sound = mReturnSound;
            break;
        case Constants.CODE_SPACE:
            sound = mSpacebarSound;
            break;
        default:
            sound = mStandardSound;
            break;
        }
        mBackgroundHandler.post(sound);
    }

    /** Plays one effect at {@code volume}; for the settings preview, not for key presses. */
    public void playSoundEffect(final int effectType, final float volume) {
        final AudioManager audioManager = mAudioManager;
        if (audioManager == null) {
            return;
        }

        mBackgroundHandler.post(() -> {
            audioManager.playSoundEffect(effectType, volume);
        });
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn) {
        if (!mSettingsValues.mVibrateOn || mVibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mBackgroundHandler.post(mClickVibration);
        } else if (viewToPerformHapticFeedbackOn != null) {
            // On the calling UI thread, as View requires. No global-setting override: the
            // system haptics switch wins here, as it does for the Vibrator on API 29+.
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /**
     * A long-press haptic (the emoji skin-tone popup), gated on the same key-press vibration
     * setting as a key press. Always goes through the view, so the LONG_PRESS feel survives on
     * every API level and the system haptics switch wins as it does for key presses.
     */
    public void performLongPressHapticFeedback(final View viewToPerformHapticFeedbackOn) {
        if (!mSettingsValues.mVibrateOn || viewToPerformHapticFeedbackOn == null) {
            return;
        }
        viewToPerformHapticFeedbackOn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    public void performTickFeedback() {
        if (!mSettingsValues.mVibrateOn
                || mVibrator == null
                || System.currentTimeMillis() - mLastTickTime < TICK_FREQUENCY ) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mLastTickTime = System.currentTimeMillis();
            mBackgroundHandler.post(mTickVibration);
        }
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mKeypressSoundVolume = settingsValues.mKeypressSoundVolume;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged() {
        mSoundOn = reevaluateIfSoundIsOn();
    }

    /** One key-press sound; reads the volume when it runs, so a settings change needs no rebuild. */
    private final class KeypressSound implements Runnable {
        private final int mEffectType;

        KeypressSound(final int effectType) {
            mEffectType = effectType;
        }

        @Override
        public void run() {
            final AudioManager audioManager = mAudioManager;
            if (audioManager != null) {
                audioManager.playSoundEffect(mEffectType, mKeypressSoundVolume);
            }
        }
    }

    /** One vibration with an effect built once; only built on API 29+. */
    private final class Vibration implements Runnable {
        private final VibrationEffect mEffect;

        Vibration(final VibrationEffect effect) {
            mEffect = effect;
        }

        @Override
        public void run() {
            final Vibrator vibrator = mVibrator;
            // Always true where an instance exists; states the API level for lint.
            if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(mEffect);
            }
        }
    }
}
