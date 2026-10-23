package com.qmurzik.yago;

/** Ключи и значения по умолчанию, общие для экрана настроек и хуков. */
public final class Config {
    private Config() {}

    public static final String PREFS = "settings";

    public static final String K_ENABLED = "enabled";
    public static final String K_BLOCK_AD_HOSTS = "block_ad_hosts";
    public static final String K_BLOCK_ANALYTICS = "block_analytics";
    public static final String K_EXTRA_HOSTS = "extra_hosts";
    public static final String K_HIDE_AD_VIEWS = "hide_ad_views";
    public static final String K_HIDE_AD_LABELED = "hide_ad_labeled";
    public static final String K_RULES = "rules";
    public static final String K_INSPECTOR = "inspector";
    public static final String K_ALLOW_SCREENSHOTS = "allow_screenshots";
    public static final String K_DEBUG_LOG = "debug_log";

    public static final boolean D_ENABLED = true;
    public static final boolean D_BLOCK_AD_HOSTS = true;
    public static final boolean D_BLOCK_ANALYTICS = false;
    public static final boolean D_HIDE_AD_VIEWS = true;
    public static final boolean D_HIDE_AD_LABELED = true;
    public static final boolean D_INSPECTOR = true;
    public static final boolean D_ALLOW_SCREENSHOTS = false;
    public static final boolean D_DEBUG_LOG = false;

    /** Рекламные домены. Блокируются вместе со всеми поддоменами. */
    public static final String[] AD_HOSTS = {
            "an.yandex.ru",
            "adfstat.yandex.ru",
            "yandexadexchange.net",
            "adfox.ru",
            "adfox.yandex.ru",
            "ads.adfox.ru",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googleads.g.doubleclick.net",
            "ad.mail.ru",
            "ads.vk.com",
            "mytarget.ru",
            "r.mradx.net",
            "applovin.com",
            "unityads.unity3d.com",
    };

    /** Аналитика и трекеры. Выключено по умолчанию: может влиять на работу приложения. */
    public static final String[] ANALYTICS_HOSTS = {
            "mc.yandex.ru",
            "report.appmetrica.yandex.net",
            "redirect.appmetrica.yandex.com",
            "appmetrica.yandex.ru",
            "app-measurement.com",
            "firebase-settings.crashlytics.com",
            "crashlytics.com",
            "adjust.com",
            "app.adjust.com",
            "appsflyer.com",
            "tracker-api.my.com",
    };
}
