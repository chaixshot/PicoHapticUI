package com.hamer.picohapticui;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.bytedance.osui.utils.OSUIHelper;
import com.hamer.picohapticui.chromium.ChromiumHapticHelper;
import com.hamer.picohapticui.firefox.GeckoViewHapticHelper;
import com.hamer.picohapticui.haptic.HapticManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {
    private static final String TAG = "PicoHapticUI";
    private static final String SELF_PACKAGE = "com.hamer.picohapticui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null) return;

        Log.d(TAG, "Loaded module into target scope: " + lpparam.packageName);
        XposedBridge.log(TAG + ": Loaded module into target scope: " + lpparam.packageName);

        // Try loading native lib from target or module path
        try {
            if (lpparam.appInfo != null && lpparam.appInfo.nativeLibraryDir != null) {
                OSUIHelper.tryLoadFromPath(lpparam.appInfo.nativeLibraryDir + "/libvibration.so");
            }
        } catch (Throwable t) {
            // Ignore native path probing exceptions
        }

        // Self active check hook
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            try {
                Class<?> moduleUtilsClass = XposedHelpers.findClassIfExists(
                        "com.hamer.picohapticui.utils.ModuleUtils",
                        lpparam.classLoader
                );
                if (moduleUtilsClass != null) {
                    XposedHelpers.findAndHookMethod(
                            moduleUtilsClass,
                            "isModuleActive",
                            XC_MethodReplacement.returnConstant(true)
                    );
                    Log.d(TAG, "Self-hook applied, module marked ACTIVE.");
                    XposedBridge.log(TAG + ": Self-hook applied, module marked ACTIVE.");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to hook ModuleUtils: " + t.getMessage());
            }
        }

        // Hook Android View System for all enabled apps in LSPosed scope
        hookViewSystem(lpparam);

        // Hook Jetpack Compose if present
        hookComposeIfPresent(lpparam);

        // Install Firefox WebPage Haptic Hooks
        GeckoViewHapticHelper.installHooks(lpparam);

        // Install Chromium WebPage Haptic Hooks
        ChromiumHapticHelper.installHooks(lpparam);
    }

    private void hookViewSystem(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", lpparam.classLoader);

            // Hook dispatchTouchEvent to capture active VR controller device ID from motion events
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (event != null) {
                                HapticManager.setLastDeviceId(event.getDeviceId());
                            }
                        }
                    }
            );

            // Hook dispatchHoverEvent for Hover Enter, Move, and Exit
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "dispatchHoverEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (event == null || view == null) return;

                            HapticManager.setLastDeviceId(event.getDeviceId());

                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                                if (isInteractiveView(view)) {
                                    HapticManager.onHoverEnter(view, event.getDeviceId(), view.getContext());
                                }
                            } else if (action == MotionEvent.ACTION_HOVER_MOVE) {
                                if (isListContainer(view)) {
                                    ViewGroup group = (ViewGroup) view;
                                    checkListContainerHoverTransition(group, event);
                                }
                            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                                HapticManager.onHoverExit(view);
                            }
                        }
                    }
            );

            // Hook performClick for click feedback - ONLY trigger when performClick returns true and view is interactive
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (view == null) return;

                            Boolean result = (Boolean) param.getResult();
                            if (Boolean.TRUE.equals(result) && isInteractiveView(view)) {
                                HapticManager.onClickTriggered(view, 0, view.getContext());
                            }
                        }
                    }
            );

            // Hook performLongClick for long-press feedback
            XposedHelpers.findAndHookMethod(
                    viewClass,
                    "performLongClick",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (view == null) return;

                            Boolean result = (Boolean) param.getResult();
                            if (Boolean.TRUE.equals(result) && isInteractiveView(view)) {
                                HapticManager.onClickTriggered(view, 0, view.getContext());
                            }
                        }
                    }
            );

            // Hook AdapterView.performItemClick for list item clicks
            Class<?> adapterViewClass = XposedHelpers.findClassIfExists("android.widget.AdapterView", lpparam.classLoader);
            if (adapterViewClass != null) {
                XposedHelpers.findAndHookMethod(
                        adapterViewClass,
                        "performItemClick",
                        View.class,
                        int.class,
                        long.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Boolean result = (Boolean) param.getResult();
                                if (Boolean.TRUE.equals(result)) {
                                    View adapterView = (View) param.thisObject;
                                    View itemView = (View) param.args[0];
                                    Context context = adapterView != null ? adapterView.getContext() : (itemView != null ? itemView.getContext() : null);
                                    HapticManager.onClickTriggered(itemView != null ? itemView : adapterView, 0, context);
                                }
                            }
                        }
                );
            }

            Log.d(TAG, "View system hooks successfully installed for " + lpparam.packageName);
            XposedBridge.log(TAG + ": View system hooks successfully installed for " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "Error installing View hooks for " + lpparam.packageName + ": " + t.getMessage());
            XposedBridge.log(TAG + ": Error installing View hooks: " + t.getMessage());
        }
    }

    private void hookComposeIfPresent(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> composeHapticClass = XposedHelpers.findClassIfExists(
                    "androidx.compose.ui.hapticfeedback.PlatformHapticFeedback",
                    lpparam.classLoader
            );
            if (composeHapticClass != null) {
                XposedBridge.hookAllMethods(composeHapticClass, "performHapticFeedback", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        HapticManager.triggerHaptic(HapticManager.HapticProfile.CLICK, 0, null);
                    }
                });
                Log.d(TAG, "Compose haptic hooks installed for " + lpparam.packageName);
                XposedBridge.log(TAG + ": Compose haptic hooks installed for " + lpparam.packageName);
            }
        } catch (Throwable t) {
            // Compose not used in this app, safe to ignore
        }
    }

    private static boolean isListContainer(View view) {
        if (!(view instanceof ViewGroup)) return false;
        if (view instanceof android.widget.AdapterView) return true;
        String className = view.getClass().getName();
        return className.contains("RecyclerView")
                || className.contains("ListView")
                || className.contains("GridView")
                || className.contains("ViewPager")
                || className.contains("ScrollView")
                || className.contains("TabStrip")
                || className.contains("TabsView")
                || className.contains("Carousel");
    }

    private static boolean isChildOfListContainer(View view) {
        if (view == null) return false;
        ViewParent parent = view.getParent();
        if (parent instanceof View parentView) {
            return isListContainer(parentView);
        }
        return false;
    }

    private static void checkListContainerHoverTransition(ViewGroup group, MotionEvent event) {
        if (group == null || group.getChildCount() == 0) return;

        float x = event.getX();
        float y = event.getY();

        View hoveredChild = findDirectChildUnder(group, x, y);
        if (isInteractiveOrContainsInteractive(hoveredChild)) {
            HapticManager.onChildHoverEntered(group, hoveredChild, event.getDeviceId(), group.getContext());
        }
    }

    private static View findDirectChildUnder(ViewGroup parent, float x, float y) {
        int count = parent.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                float tx = child.getTranslationX();
                float ty = child.getTranslationY();
                if (x >= child.getLeft() + tx &&
                        x <= child.getRight() + tx &&
                        y >= child.getTop() + ty &&
                        y <= child.getBottom() + ty) {
                    return child;
                }
            }
        }
        return null;
    }

    private static boolean isInteractiveOrContainsInteractive(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (isInteractiveView(view)) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (isInteractiveOrContainsInteractive(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInteractiveView(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isEnabled()) {
            return false;
        }

        if (view.isClickable() || view.isLongClickable() || view.isContextClickable() || view.hasOnClickListeners()) {
            return true;
        }

        if (view instanceof android.widget.Button
                || view instanceof android.widget.SeekBar
                || view instanceof android.widget.RatingBar
                || view instanceof android.widget.EditText
                || view instanceof android.widget.Spinner) {
            return true;
        }

        // Direct child of a List, Grid, or Tab container
        if (isChildOfListContainer(view)) {
            return true;
        }

        String className = view.getClass().getName();
        if (className.contains("Cell")
                || className.contains("Tab")
                || className.contains("Item")
                || className.contains("Card")
                || className.contains("Button")
                || className.contains("FAB")
                || className.contains("FloatingActionButton")
                || className.contains("ComponentHost")
                || className.contains("LithoView")
                || className.contains("YouTubeElement")
                || className.contains("PlayerView")
                || className.contains("TouchDelegateView")) {
            return true;
        }

        return view.getParent() instanceof android.widget.AdapterView;
    }
}
