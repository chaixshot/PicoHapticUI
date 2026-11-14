package com.hamer.picohapticui.firefox;

import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;

import com.hamer.picohapticui.haptic.HapticManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GeckoViewHapticHelper {
    private static final String TAG = "PicoHapticUI_Firefox";

    private static boolean sIsLinkHovered = false;
    private static int sLastPointerIconType = PointerIcon.TYPE_NULL;
    private static long sLastWebClickTimeMs = 0;
    private static final long WEB_CLICK_DEBOUNCE_MS = 120;

    public static boolean isFirefoxPackage(String packageName) {
        if (packageName == null) return false;
        return packageName.contains("mozilla")
                || packageName.contains("firefox")
                || packageName.contains("fenix")
                || packageName.contains("focus")
                || packageName.contains("klar")
                || packageName.contains("geckoview");
    }

    public static boolean isWebContentView(View view) {
        if (view == null) return false;
        String className = view.getClass().getName();
        return className.contains("GeckoView")
                || className.contains("GeckoDisplay")
                || className.contains("WebView")
                || className.contains("WebContentView")
                || className.contains("BrowserContentView")
                || className.contains("ContentChildView");
    }

    public static void installHooks(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        boolean isFirefox = isFirefoxPackage(lpparam.packageName);
        if (isFirefox) {
            Log.d(TAG, "Installing GeckoView WebPage Haptic Hooks into: " + lpparam.packageName);
            XposedBridge.log(TAG + ": Installing GeckoView WebPage Haptic Hooks into: " + lpparam.packageName);
        }

        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", lpparam.classLoader);

            // 1. Hook onResolvePointerIcon for webpage link & button hover detection
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "onResolvePointerIcon",
                    MotionEvent.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];
                            PointerIcon icon = (PointerIcon) param.getResult();

                            if (view == null || event == null || icon == null) return;

                            if (isFirefoxPackage(lpparam.packageName) || isWebContentView(view)) {
                                handlePointerIconHover(view, event, icon);
                            }
                        }
                    }
            );

            // 2. Hook setPointerIcon for explicit link icon changes
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "setPointerIcon",
                    PointerIcon.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            PointerIcon icon = (PointerIcon) param.args[0];

                            if (view == null || icon == null) return;

                            if (isFirefoxPackage(lpparam.packageName) || isWebContentView(view)) {
                                handlePointerIconChange(view, icon);
                            }
                        }
                    }
            );

            // 3. Hook dispatchTouchEvent on Web/Gecko views for webpage link clicks
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];

                            if (view == null || event == null) return;

                            if (isFirefoxPackage(lpparam.packageName) || isWebContentView(view)) {
                                HapticManager.setLastDeviceId(event.getDeviceId());
                                int action = event.getActionMasked();

                                if (action == MotionEvent.ACTION_UP) {
                                    Boolean result = (Boolean) param.getResult();
                                    if (Boolean.TRUE.equals(result) || sIsLinkHovered) {
                                        handleWebClick(view, event);
                                    }
                                }
                            }
                        }
                    }
            );

            // 4. Hook dispatchGenericMotionEvent for webpage hover & scroll
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "dispatchGenericMotionEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];

                            if (view == null || event == null) return;

                            if (isFirefoxPackage(lpparam.packageName) || isWebContentView(view)) {
                                HapticManager.setLastDeviceId(event.getDeviceId());
                                int action = event.getActionMasked();

                                if (action == MotionEvent.ACTION_HOVER_ENTER) {
                                    if (sIsLinkHovered) {
                                        HapticManager.onHoverEnter(view, event.getDeviceId(), view.getContext());
                                    }
                                } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                                    sIsLinkHovered = false;
                                    HapticManager.onHoverExit(view);
                                }
                            }
                        }
                    }
            );

            Log.d(TAG, "GeckoView WebPage Haptic Hooks successfully registered for " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install GeckoView WebPage Haptic Hooks: " + t.getMessage());
        }
    }

    private static void handlePointerIconHover(View view, MotionEvent event, PointerIcon icon) {
        int iconType = getIconType(icon);

        if (isClickableLinkIcon(iconType)) {
            if (!sIsLinkHovered || iconType != sLastPointerIconType) {
                sIsLinkHovered = true;
                sLastPointerIconType = iconType;
                Log.d(TAG, "GeckoView link hover ENTER (iconType=" + iconType + ") on " + view.getClass().getName());
                HapticManager.onHoverEnter(view, event.getDeviceId(), view.getContext());
            }
        } else {
            if (sIsLinkHovered) {
                sIsLinkHovered = false;
                sLastPointerIconType = iconType;
                Log.d(TAG, "GeckoView link hover EXIT on " + view.getClass().getName());
                HapticManager.onHoverExit(view);
            }
        }
    }

    private static void handlePointerIconChange(View view, PointerIcon icon) {
        int iconType = getIconType(icon);

        if (isClickableLinkIcon(iconType)) {
            if (!sIsLinkHovered) {
                sIsLinkHovered = true;
                sLastPointerIconType = iconType;
                Log.d(TAG, "GeckoView link setPointerIcon HAND on " + view.getClass().getName());
                HapticManager.triggerHaptic(HapticManager.HapticProfile.HOVER, 0, view.getContext(), view);
            }
        } else {
            if (sIsLinkHovered) {
                sIsLinkHovered = false;
                sLastPointerIconType = iconType;
                HapticManager.onHoverExit(view);
            }
        }
    }

    private static void handleWebClick(View view, MotionEvent event) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastWebClickTimeMs < WEB_CLICK_DEBOUNCE_MS) {
            return;
        }
        sLastWebClickTimeMs = now;
        Log.d(TAG, "GeckoView web click triggered on " + view.getClass().getName());
        HapticManager.onClickTriggered(view, event.getDeviceId(), view.getContext());
    }

    private static boolean isClickableLinkIcon(int iconType) {
        return iconType == PointerIcon.TYPE_HAND
                || iconType == PointerIcon.TYPE_CONTEXT_MENU
                || iconType == PointerIcon.TYPE_ALIAS;
    }

    private static int getIconType(PointerIcon icon) {
        if (icon == null) return PointerIcon.TYPE_NULL;
        try {
            Object typeObj = XposedHelpers.callMethod(icon, "getType");
            if (typeObj instanceof Integer) {
                return (Integer) typeObj;
            }
        } catch (Throwable t) {
            try {
                return (int) XposedHelpers.getObjectField(icon, "mType");
            } catch (Throwable ignored) {}
        }
        return PointerIcon.TYPE_NULL;
    }
}
