package com.qmurzik.yago.hook;

import de.robv.android.xposed.XposedBridge;

final class Log {
    static final String TAG = "YaGo";

    private Log() {}

    static void i(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    static void w(String msg) {
        XposedBridge.log(TAG + " [!]: " + msg);
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + " [E]: " + msg);
        XposedBridge.log(t);
    }

    /** Подробный вывод в logcat (тег YaGo), только при включённом режиме отладки. */
    static void d(String msg) {
        if (HookConfig.debugLog) android.util.Log.d(TAG, msg);
    }
}
