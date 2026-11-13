package com.bytedance.osui.utils;

import android.util.Log;

public class OSUIHelper {
    private static final String TAG = "OSUIHelper";
    public static boolean isLoaded = false;

    static {
        ensureLoaded();
    }

    public static synchronized void ensureLoaded() {
        if (isLoaded) return;

        try {
            System.loadLibrary("vibration");
            isLoaded = true;
            Log.d(TAG, "Successfully loaded libvibration.so via System.loadLibrary");
            return;
        } catch (Throwable t1) {
            Log.w(TAG, "System.loadLibrary(vibration) failed: " + t1.getMessage());
        }

        String[] paths = new String[]{
            "/system/lib64/libvibration.so",
            "/vendor/lib64/libvibration.so",
            "/system/lib/libvibration.so",
            "/vendor/lib/libvibration.so"
        };
        for (String path : paths) {
            try {
                System.load(path);
                isLoaded = true;
                Log.d(TAG, "Successfully loaded " + path);
                break;
            } catch (Throwable t2) {
                Log.w(TAG, "Failed to load " + path + ": " + t2.getMessage());
            }
        }
    }

    public static synchronized void tryLoadFromPath(String path) {
        if (isLoaded || path == null || path.isEmpty()) return;
        try {
            System.load(path);
            isLoaded = true;
            Log.d(TAG, "Successfully loaded native vibration library from: " + path);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load native vibration library from " + path + ": " + t.getMessage());
        }
    }

    public static native int nativeSetVCMotorEvent(int controller, float amplitude, float frequency, float duration);

    public static int a(int controller, float amplitude, float frequency, float duration) {
        if (!isLoaded) {
            ensureLoaded();
        }
        if (!isLoaded) {
            Log.w(TAG, "OSUIHelper.a called but native library is not loaded");
            return -1;
        }
        try {
            int result = nativeSetVCMotorEvent(controller, amplitude, frequency, duration);
            Log.d(TAG, "nativeSetVCMotorEvent(ctrl=" + controller + ", amp=" + amplitude + ", freq=" + frequency + ", dur=" + duration + ") -> " + result);
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "nativeSetVCMotorEvent failed", t);
            return -1;
        }
    }
}
