package com.hamer.picohapticui.haptic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.bytedance.osui.utils.OSUIHelper;

import java.lang.ref.WeakReference;

public class HapticManager {
    private static final String TAG = "PicoHapticUI_Mgr";

    public static final String SETTING_ENABLED = "pico_haptic_enabled";
    public static final String SETTING_HOVER_ENABLED = "pico_haptic_hover_enabled";
    public static final String SETTING_CLICK_ENABLED = "pico_haptic_click_enabled";
    public static final String SETTING_HOVER_AMP = "pico_haptic_hover_amp";
    public static final String SETTING_HOVER_FREQ = "pico_haptic_hover_freq";
    public static final String SETTING_HOVER_DUR = "pico_haptic_hover_dur";
    public static final String SETTING_CLICK_AMP = "pico_haptic_click_amp";
    public static final String SETTING_CLICK_FREQ = "pico_haptic_click_freq";
    public static final String SETTING_CLICK_DUR = "pico_haptic_click_dur";

    private static int sLastDeviceId = 0;
    private static long sLastHoverTimeMs = 0;
    private static long sLastClickTimeMs = 0;
    private static int sLastClickDeviceId = 0;

    private static WeakReference<View> sLastHoveredViewRef = new WeakReference<>(null);
    private static WeakReference<View> sLastHoveredChildRef = new WeakReference<>(null);
    private static WeakReference<View> sLastClickedViewRef = new WeakReference<>(null);

    private static final long HOVER_DEBOUNCE_MS = 80;
    private static final long CLICK_DEBOUNCE_MS = 150;

    // Cache settings for performance during rapid motion events
    private static long sLastSettingsCheckTimeMs = 0;
    private static final long SETTINGS_CACHE_TTL_MS = 1000;
    private static boolean sCachedEnabled = true;
    private static boolean sCachedHoverEnabled = true;
    private static boolean sCachedClickEnabled = true;
    private static float sCachedHoverAmp = 120.0f;
    private static float sCachedHoverFreq = 177.0f;
    private static float sCachedHoverDur = 10.0f;
    private static float sCachedClickAmp = 255.0f;
    private static float sCachedClickFreq = 100.0f;
    private static float sCachedClickDur = 35.0f;

    public enum HapticProfile {
        HOVER(120.0f, 177.0f, 10.0f),
        CLICK(255.0f, 100.0f, 35.0f),
        TOGGLE(240.0f, 120.0f, 25.0f);

        public final float defaultAmplitude;
        public final float defaultFrequency;
        public final float defaultDuration;

        HapticProfile(float amplitude, float frequency, float duration) {
            this.defaultAmplitude = amplitude;
            this.defaultFrequency = frequency;
            this.defaultDuration = duration;
        }
    }

    public static void setLastDeviceId(int deviceId) {
        if (deviceId != 0) {
            sLastDeviceId = deviceId;
        }
    }

    public static int getLastDeviceId() {
        return sLastDeviceId;
    }

    private static void refreshSettingsCache(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSettingsCheckTimeMs < SETTINGS_CACHE_TTL_MS) {
            return;
        }
        sLastSettingsCheckTimeMs = now;
        if (context == null) return;

        try {
            sCachedEnabled = Settings.Global.getInt(context.getContentResolver(), SETTING_ENABLED, 1) != 0;
            sCachedHoverEnabled = Settings.Global.getInt(context.getContentResolver(), SETTING_HOVER_ENABLED, 1) != 0;
            sCachedClickEnabled = Settings.Global.getInt(context.getContentResolver(), SETTING_CLICK_ENABLED, 1) != 0;

            sCachedHoverAmp = Settings.Global.getFloat(context.getContentResolver(), SETTING_HOVER_AMP, HapticProfile.HOVER.defaultAmplitude);
            sCachedHoverFreq = Settings.Global.getFloat(context.getContentResolver(), SETTING_HOVER_FREQ, HapticProfile.HOVER.defaultFrequency);
            sCachedHoverDur = Settings.Global.getFloat(context.getContentResolver(), SETTING_HOVER_DUR, HapticProfile.HOVER.defaultDuration);

            sCachedClickAmp = Settings.Global.getFloat(context.getContentResolver(), SETTING_CLICK_AMP, HapticProfile.CLICK.defaultAmplitude);
            sCachedClickFreq = Settings.Global.getFloat(context.getContentResolver(), SETTING_CLICK_FREQ, HapticProfile.CLICK.defaultFrequency);
            sCachedClickDur = Settings.Global.getFloat(context.getContentResolver(), SETTING_CLICK_DUR, HapticProfile.CLICK.defaultDuration);
        } catch (Throwable t) {
            // Keep default values if settings cannot be read
        }
    }

    public static void triggerHaptic(HapticProfile profile, int deviceId, Context context) {
        triggerHaptic(profile, deviceId, context, null);
    }

    public static void triggerHaptic(HapticProfile profile, int deviceId, Context context, View targetView) {
        refreshSettingsCache(context);

        if (!sCachedEnabled) return;

        boolean isHover = (profile == HapticProfile.HOVER);
        if (isHover && !sCachedHoverEnabled) return;
        if (!isHover && !sCachedClickEnabled) return;

        long now = SystemClock.uptimeMillis();

        if (isHover) {
            View lastView = sLastHoveredViewRef.get();
            if (targetView != null && targetView == lastView) {
                return;
            }
            if (targetView != null) {
                sLastHoveredViewRef = new WeakReference<>(targetView);
            } else {
                if (now - sLastHoverTimeMs < HOVER_DEBOUNCE_MS) {
                    return;
                }
            }
            sLastHoverTimeMs = now;
        }

        if (deviceId != 0) {
            setLastDeviceId(deviceId);
        }
        int targetDeviceId = (deviceId != 0) ? deviceId : getLastDeviceId();

        float amp = isHover ? sCachedHoverAmp : sCachedClickAmp;
        float freq = isHover ? sCachedHoverFreq : sCachedClickFreq;
        float dur = isHover ? sCachedHoverDur : sCachedClickDur;

        int nativeDeviceId;
        if (targetDeviceId == 100001 || targetDeviceId == 1) {
            nativeDeviceId = 1;
        } else if (targetDeviceId == 100002 || targetDeviceId == 2) {
            nativeDeviceId = 2;
        } else {
            nativeDeviceId = 0;
        }

        boolean nativeSuccess = false;
        if (OSUIHelper.isLoaded) {
            try {
                if (nativeDeviceId == 1) {
                    OSUIHelper.a(1, amp, freq, dur);
                    nativeSuccess = true;
                } else if (nativeDeviceId == 2) {
                    OSUIHelper.a(2, amp, freq, dur);
                    nativeSuccess = true;
                } else {
                    OSUIHelper.a(1, amp, freq, dur);
                    OSUIHelper.a(2, amp, freq, dur);
                    nativeSuccess = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "OSUIHelper.a call failed", t);
            }
        }

        if (!nativeSuccess && context != null) {
            fallbackVibrator(context, (long) dur);
        }
    }

    public static boolean onHoverEnter(View view, int deviceId, Context context) {
        triggerHaptic(HapticProfile.HOVER, deviceId, context, view);
        return true;
    }

    public static void onHoverExit(View view) {
        if (view != null) {
            if (view == sLastHoveredViewRef.get()) {
                sLastHoveredViewRef = new WeakReference<>(null);
            }
            if (view == sLastHoveredChildRef.get()) {
                sLastHoveredChildRef = new WeakReference<>(null);
            }
        }
    }

    public static boolean onChildHoverEntered(ViewGroup parent, View child, int deviceId, Context context) {
        if (child == null) return false;
        View lastChild = sLastHoveredChildRef.get();

        if (child != lastChild) {
            sLastHoveredChildRef = new WeakReference<>(child);
            sLastHoveredViewRef = new WeakReference<>(child);
            triggerHaptic(HapticProfile.HOVER, deviceId, context, child);
            return true;
        }
        return false;
    }

    public static boolean onTouchDown(View view, int deviceId, Context context) {
        sLastClickTimeMs = SystemClock.uptimeMillis();
        sLastClickedViewRef = new WeakReference<>(view);
        sLastClickDeviceId = deviceId;
        triggerHaptic(HapticProfile.CLICK, deviceId, context, view);
        return true;
    }

    public static boolean onClickTriggered(View view, int deviceId, Context context) {
        long now = SystemClock.uptimeMillis();
        View lastClickedView = sLastClickedViewRef.get();
        if (now - sLastClickTimeMs < CLICK_DEBOUNCE_MS && (view == null || view == lastClickedView)) {
            // Already triggered on ACTION_DOWN
            return false;
        }
        sLastClickTimeMs = now;
        sLastClickedViewRef = new WeakReference<>(view);
        int finalDeviceId = (deviceId != 0) ? deviceId : sLastClickDeviceId;
        triggerHaptic(HapticProfile.CLICK, finalDeviceId, context, view);
        return true;
    }

    @SuppressLint("MissingPermission")
    private static void fallbackVibrator(Context context, long durationMs) {
        try {
            Vibrator vibrator = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            }
            if (vibrator == null) {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(Math.max(5, durationMs), VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable t) {
            // Ignore vibrator fallback exceptions
        }
    }
}
