package com.qmurzik.yago.hook;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.TextView;

import com.qmurzik.yago.BuildConfig;
import com.qmurzik.yago.ModuleStatus;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Точка входа. Все хуки ставятся только на классы Android SDK
 * (Activity, View, Window, InetAddress, WebView…), поэтому модуль не зависит
 * от обфусцированных имён классов приложения и переживает его обновления.
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (BuildConfig.APPLICATION_ID.equals(lpparam.packageName)) {
            hookSelf(lpparam);
            return;
        }
        if (!lpparam.isFirstApplication || "android".equals(lpparam.packageName)) return;

        Log.i("Загрузка в " + lpparam.packageName + " (" + lpparam.processName + ")");
        HookConfig.init();

        safe("сеть", MainHook::hookNetwork);
        safe("WebView", MainHook::hookWebView);
        safe("Activity", MainHook::hookActivity);
        safe("окна", MainHook::hookWindows);
        safe("видимость", MainHook::hookVisibility);
        safe("скриншоты", MainHook::hookSecureFlag);
    }

    private interface Installer {
        void install() throws Throwable;
    }

    private static void safe(String name, Installer i) {
        try {
            i.install();
        } catch (Throwable t) {
            Log.e("Не удалось установить хуки: " + name, t);
        }
    }

    private static void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(ModuleStatus.class.getName(), lpparam.classLoader,
                "isActive", XC_MethodReplacement.returnConstant(true));
    }

    // ---------------------------------------------------------------- реклама в сети

    private static void hookNetwork() {
        XC_MethodHook dns = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object host = param.args.length > 0 ? param.args[0] : null;
                if (host instanceof String && HookConfig.isHostBlocked((String) host)) {
                    Log.d("Заблокирован хост " + host);
                    param.setThrowable(new UnknownHostException(host + ": blocked by YaGo Tweaks"));
                }
            }
        };
        XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, dns);
        XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, dns);
    }

    private static void hookWebView() {
        XC_MethodHook load = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object url = param.args[0];
                if (!(url instanceof String)) return;
                try {
                    String host = URI.create((String) url).getHost();
                    if (HookConfig.isHostBlocked(host)) {
                        Log.d("WebView: заблокирован " + url);
                        param.setResult(null);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        };
        XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, load);
        XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, Map.class, load);
    }

    // ---------------------------------------------------------------- интерфейс

    private static void hookActivity() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                if (HookConfig.refresh(a)) ViewHider.rescanAll();
                View decor = a.getWindow().getDecorView();
                ViewHider.attachRoot(decor);
                Fab.sync(a);
                if (HookConfig.debugLog) {
                    decor.postDelayed(() -> dump(a.getClass().getName(), decor), 2000);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPostCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                HookConfig.refresh(a);
                ViewHider.attachRoot(a.getWindow().getDecorView());
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Picker.onTouch((Activity) param.thisObject, (MotionEvent) param.args[0]);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Picker.onPause((Activity) param.thisObject);
            }
        });
    }

    /** Любое окно приложения (Activity, диалоги, bottom sheet, popup) проходит через WindowManagerGlobal. */
    private static void hookWindows() throws ClassNotFoundException {
        Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
        XposedBridge.hookAllMethods(wmg, "addView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                stripSecure(param.args.length > 1 ? param.args[1] : null);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getThrowable() == null && param.args[0] instanceof View) {
                    ViewHider.attachRoot((View) param.args[0]);
                }
            }
        });
        XposedBridge.hookAllMethods(wmg, "updateViewLayout", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                stripSecure(param.args.length > 1 ? param.args[1] : null);
            }
        });
    }

    private static void hookVisibility() {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = ViewHider.onSetVisibility((View) param.thisObject, (Integer) param.args[0]);
            }
        });
    }

    // ---------------------------------------------------------------- скриншоты

    private static void stripSecure(Object lp) {
        if (HookConfig.enabled && HookConfig.allowScreenshots && lp instanceof WindowManager.LayoutParams) {
            ((WindowManager.LayoutParams) lp).flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        }
    }

    private static void hookSecureFlag() {
        XposedHelpers.findAndHookMethod(Window.class, "setFlags", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookConfig.enabled && HookConfig.allowScreenshots) {
                    param.args[0] = (Integer) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                }
            }
        });
        XposedHelpers.findAndHookMethod(SurfaceView.class, "setSecure", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (HookConfig.enabled && HookConfig.allowScreenshots) param.args[0] = false;
            }
        });
    }

    // ---------------------------------------------------------------- отладка

    private static void dump(String title, View root) {
        android.util.Log.d(Log.TAG, "===== " + title + " =====");
        dump(root, 0);
    }

    private static void dump(View v, int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < depth; i++) b.append("  ");
        b.append(v.getClass().getName());
        String id = ViewHider.idName(v);
        if (id != null) b.append(" #").append(id);
        if (v instanceof TextView && ((TextView) v).getText() != null) {
            b.append(" «").append(((TextView) v).getText()).append('»');
        }
        if (v.getContentDescription() != null) b.append(" [").append(v.getContentDescription()).append(']');
        if (v.getVisibility() != View.VISIBLE) b.append(v.getVisibility() == View.GONE ? " (gone)" : " (invisible)");
        if (ViewHider.isHidden(v)) b.append(" (скрыто модулем)");
        android.util.Log.d(Log.TAG, b.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1);
        }
    }
}
