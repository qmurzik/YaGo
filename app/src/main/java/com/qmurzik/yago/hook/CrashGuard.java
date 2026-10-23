package com.qmurzik.yago.hook;

import android.app.AndroidAppHelper;
import android.app.Application;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Защита от падений: если приложение падает вскоре после того, как модуль впервые
 * что-то скрыл, виновные правила отключаются — следующий запуск пройдёт нормально.
 * Без этого неудачное правило приводило бы к падению при каждом запуске.
 */
final class CrashGuard {
    private static final long WINDOW_MS = 8000;

    private CrashGuard() {}

    static void install() {
        // Вызывается рантаймом для любого непойманного исключения, до обработчиков приложения.
        XposedBridge.hookAllMethods(Thread.class, "dispatchUncaughtException", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                onCrash(param.args.length > 0 ? param.args[0] : null);
            }
        });
    }

    private static void onCrash(Object error) {
        try {
            Set<String> sources = ViewHider.recentSources(WINDOW_MS);
            if (sources.isEmpty()) return;
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) return;
            Log.w("Приложение упало вскоре после скрытия (" + error + "), отключаю правила: " + sources);
            HookConfig.disableAfterCrash(app, sources);
        } catch (Throwable t) {
            Log.e("Ошибка защиты от падений", t);
        }
    }
}
