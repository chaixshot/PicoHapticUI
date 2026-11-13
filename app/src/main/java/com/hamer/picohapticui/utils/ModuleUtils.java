package com.hamer.picohapticui.utils;

public class ModuleUtils {
    /**
     * Hooked by XposedInit to return true if the module is active in LSPosed.
     */
    public static boolean isModuleActive() {
        return false;
    }
}
