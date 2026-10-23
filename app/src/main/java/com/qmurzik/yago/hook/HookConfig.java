package com.qmurzik.yago.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.qmurzik.yago.BuildConfig;
import com.qmurzik.yago.Config;
import com.qmurzik.yago.RuleEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Настройки внутри процесса приложения.
 * Источники: настройки модуля (XSharedPreferences) и правила, созданные пикером
 * прямо в приложении (хранятся в данных самого приложения).
 */
final class HookConfig {
    static final String LOCAL_PREFS = "yago_tweaks_local";
    static final String LOCAL_RULES = "rules";
    /** Правила (строки) и эвристики (@ads, @labeled), отключённые защитой от падений. */
    static final String LOCAL_DISABLED = "disabled_after_crash";
    static final String LOCAL_NOTICE = "crash_notice";

    static volatile boolean enabled = Config.D_ENABLED;
    static volatile boolean hideAdViews = Config.D_HIDE_AD_VIEWS;
    static volatile boolean hideAdLabeled = Config.D_HIDE_AD_LABELED;
    private static volatile boolean prefHideAdViews = Config.D_HIDE_AD_VIEWS;
    private static volatile boolean prefHideAdLabeled = Config.D_HIDE_AD_LABELED;
    private static volatile Set<String> disabled = Collections.emptySet();
    static volatile boolean inspector = Config.D_INSPECTOR;
    static volatile boolean fab = Config.D_FAB;
    static volatile boolean allowScreenshots = Config.D_ALLOW_SCREENSHOTS;
    static volatile boolean debugLog = Config.D_DEBUG_LOG;
    static volatile Set<String> blockedHosts = Collections.emptySet();
    static volatile List<Rule> rules = Collections.emptyList();

    private static XSharedPreferences prefs;
    private static String lastRulesSource;

    private HookConfig() {}

    static synchronized void init() {
        try {
            prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, Config.PREFS);
        } catch (Throwable t) {
            Log.e("Не удалось открыть настройки модуля", t);
        }
        loadModulePrefs();
        rebuildRules(null);
    }

    /** Перечитывает настройки, если они изменились. Возвращает true, если что-то поменялось. */
    static synchronized boolean refresh(Context ctx) {
        boolean changed = false;
        try {
            if (prefs != null && prefs.hasFileChanged()) {
                prefs.reload();
                loadModulePrefs();
                changed = true;
            }
        } catch (Throwable t) {
            Log.e("Ошибка перечитывания настроек", t);
        }
        return rebuildRules(ctx) || changed;
    }

    static List<RuleEntry> localRules(Context ctx) {
        return RuleEntry.parseList(local(ctx).getString(LOCAL_RULES, ""));
    }

    static void setLocalRules(Context ctx, List<RuleEntry> list) {
        local(ctx).edit().putString(LOCAL_RULES, RuleEntry.toJson(list)).apply();
        synchronized (HookConfig.class) {
            lastRulesSource = null;
            rebuildRules(ctx);
        }
    }

    private static SharedPreferences local(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    private static boolean readable() {
        return prefs != null && prefs.getFile().canRead();
    }

    private static void loadModulePrefs() {
        XSharedPreferences p = prefs;
        boolean ok = readable();
        if (p != null && !ok) Log.w("Файл настроек модуля недоступен, используются значения по умолчанию");

        enabled = ok ? p.getBoolean(Config.K_ENABLED, Config.D_ENABLED) : Config.D_ENABLED;
        prefHideAdViews = ok ? p.getBoolean(Config.K_HIDE_AD_VIEWS, Config.D_HIDE_AD_VIEWS) : Config.D_HIDE_AD_VIEWS;
        prefHideAdLabeled = ok ? p.getBoolean(Config.K_HIDE_AD_LABELED, Config.D_HIDE_AD_LABELED) : Config.D_HIDE_AD_LABELED;
        applyDisabled();
        inspector = ok ? p.getBoolean(Config.K_INSPECTOR, Config.D_INSPECTOR) : Config.D_INSPECTOR;
        fab = ok ? p.getBoolean(Config.K_FAB, Config.D_FAB) : Config.D_FAB;
        allowScreenshots = ok ? p.getBoolean(Config.K_ALLOW_SCREENSHOTS, Config.D_ALLOW_SCREENSHOTS) : Config.D_ALLOW_SCREENSHOTS;
        debugLog = ok ? p.getBoolean(Config.K_DEBUG_LOG, Config.D_DEBUG_LOG) : Config.D_DEBUG_LOG;

        Set<String> hosts = new HashSet<>();
        if (ok ? p.getBoolean(Config.K_BLOCK_AD_HOSTS, Config.D_BLOCK_AD_HOSTS) : Config.D_BLOCK_AD_HOSTS) {
            Collections.addAll(hosts, Config.AD_HOSTS);
        }
        if (ok ? p.getBoolean(Config.K_BLOCK_ANALYTICS, Config.D_BLOCK_ANALYTICS) : Config.D_BLOCK_ANALYTICS) {
            Collections.addAll(hosts, Config.ANALYTICS_HOSTS);
        }
        if (ok) {
            for (String h : p.getString(Config.K_EXTRA_HOSTS, "").split("[\\s,;]+")) {
                h = h.trim().toLowerCase(Locale.ROOT);
                if (!h.isEmpty() && !h.startsWith("#")) hosts.add(h);
            }
        }
        blockedHosts = hosts;
    }

    private static boolean rebuildRules(Context ctx) {
        String module = readable() ? prefs.getString(Config.K_RULES, "") : "";
        String local = "";
        String off = "";
        if (ctx != null) {
            try {
                local = local(ctx).getString(LOCAL_RULES, "");
                off = local(ctx).getString(LOCAL_DISABLED, "");
            } catch (Throwable t) {
                Log.e("Не удалось прочитать локальные правила", t);
            }
        } else if (lastRulesSource != null) {
            return false;
        }
        String source = module + "\u0000" + local + "\u0000" + off;
        if (source.equals(lastRulesSource)) return false;
        lastRulesSource = source;
        disabled = splitLines(off);
        applyDisabled();

        List<Rule> out = new ArrayList<>();
        addEnabled(out, RuleEntry.parseList(module));
        addEnabled(out, RuleEntry.parseList(local));
        rules = out;
        Log.d("Правил загружено: " + out.size());
        return true;
    }

    private static void addEnabled(List<Rule> out, List<RuleEntry> entries) {
        for (RuleEntry e : entries) {
            if (!e.enabled || disabled.contains(e.rule.trim())) continue;
            Rule r = Rule.parse(e.rule);
            if (r != null) out.add(r);
        }
    }

    private static void applyDisabled() {
        hideAdViews = prefHideAdViews && !disabled.contains(ViewHider.SRC_ADS);
        hideAdLabeled = prefHideAdLabeled && !disabled.contains(ViewHider.SRC_LABELED);
    }

    private static Set<String> splitLines(String s) {
        Set<String> out = new HashSet<>();
        for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    static Set<String> disabledAfterCrash(Context ctx) {
        return splitLines(local(ctx).getString(LOCAL_DISABLED, ""));
    }

    static void clearDisabledAfterCrash(Context ctx) {
        local(ctx).edit().remove(LOCAL_DISABLED).apply();
        synchronized (HookConfig.class) {
            lastRulesSource = null;
            rebuildRules(ctx);
        }
    }

    /**
     * Вызывается при падении приложения (процесс вот-вот умрёт, поэтому commit()).
     * Отключает правила, которые только что впервые что-то скрыли.
     */
    static void disableAfterCrash(Context ctx, Set<String> sources) {
        SharedPreferences p = local(ctx);
        Set<String> off = splitLines(p.getString(LOCAL_DISABLED, ""));
        off.addAll(sources);
        StringBuilder names = new StringBuilder();
        List<RuleEntry> list = RuleEntry.parseList(p.getString(LOCAL_RULES, ""));
        for (RuleEntry e : list) {
            if (sources.contains(e.rule.trim())) {
                e.enabled = false;
                off.remove(e.rule.trim()); // локальное правило просто выключаем
                names.append("«").append(e.title()).append("» ");
            }
        }
        for (String src : sources) {
            if (off.contains(src)) names.append(src.equals(ViewHider.SRC_ADS) ? "«рекламные блоки» "
                    : src.equals(ViewHider.SRC_LABELED) ? "«карточки Реклама» " : "«" + src + "» ");
        }
        p.edit()
                .putString(LOCAL_RULES, RuleEntry.toJson(list))
                .putString(LOCAL_DISABLED, String.join("\n", off))
                .putString(LOCAL_NOTICE, names.toString().trim())
                .commit();
    }

    /** Сообщение о правилах, отключённых после падения (одноразовое). */
    static String takeCrashNotice(Context ctx) {
        SharedPreferences p = local(ctx);
        String n = p.getString(LOCAL_NOTICE, null);
        if (n != null) p.edit().remove(LOCAL_NOTICE).apply();
        return n;
    }

    static boolean isHostBlocked(String host) {
        if (host == null || !enabled) return false;
        Set<String> set = blockedHosts;
        if (set.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }
}
