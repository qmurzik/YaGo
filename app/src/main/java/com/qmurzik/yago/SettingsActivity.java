package com.qmurzik.yago;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек модуля. Разметка строится в коде, без библиотек. */
public class SettingsActivity extends Activity {
    private static final String TARGET = "ru.yandex.taxi";

    private SharedPreferences prefs;
    private boolean worldReadable;
    private LinearLayout list;
    private EditText rules;
    private EditText hosts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);

        status();

        header("Общее");
        toggle("Модуль включён", null, Config.K_ENABLED, Config.D_ENABLED);

        header("Реклама");
        toggle("Блокировать рекламные домены",
                "Запросы к рекламным сетям (Яндекс Реклама, AdFox, Google, myTarget…) не проходят",
                Config.K_BLOCK_AD_HOSTS, Config.D_BLOCK_AD_HOSTS);
        toggle("Блокировать аналитику",
                "AppMetrica, Firebase, AppsFlyer и т.п. Может повлиять на работу отдельных функций",
                Config.K_BLOCK_ANALYTICS, Config.D_BLOCK_ANALYTICS);
        toggle("Скрывать рекламные элементы",
                "Элементы рекламных SDK и контейнеры с id вида ad_*, *_banner_ad, adview…",
                Config.K_HIDE_AD_VIEWS, Config.D_HIDE_AD_VIEWS);
        toggle("Скрывать карточки с пометкой «Реклама»",
                "Карточка в ленте/списке, внутри которой есть надпись «Реклама», «Ad», «Sponsored»",
                Config.K_HIDE_AD_LABELED, Config.D_HIDE_AD_LABELED);
        caption("Дополнительные домены для блокировки (по одному в строке, поддомены тоже блокируются):");
        hosts = editor(prefs.getString(Config.K_EXTRA_HOSTS, ""), "example.com", 3);

        header("Скрытие элементов");
        caption("Правила, по одному в строке:\n"
                + "  id:^имя_ресурса$\n"
                + "  text:^Текст на экране$ ^2   (^N — скрыть N-го родителя)\n"
                + "  desc:описание для TalkBack\n"
                + "  class:имя.класса$\n"
                + "  # комментарий\n"
                + "Выражения — регулярные, без учёта регистра. Правила применяются сразу при возврате в приложение.");
        rules = editor(prefs.getString(Config.K_RULES, ""), "text:^Истории$ ^1", 8);
        rules.setTypeface(Typeface.MONOSPACE);
        toggle("Инспектор элементов",
                "Удерживайте два пальца неподвижно ~1.5 с в Яндекс Go — появится список элементов "
                        + "под пальцем, любой можно скрыть или скопировать для него правило",
                Config.K_INSPECTOR, Config.D_INSPECTOR);

        header("Прочее");
        toggle("Разрешить скриншоты", "Убирает запрет на скриншоты и запись экрана",
                Config.K_ALLOW_SCREENSHOTS, Config.D_ALLOW_SCREENSHOTS);
        toggle("Режим отладки",
                "Пишет дерево элементов каждого экрана и заблокированные запросы в logcat (тег YaGo)",
                Config.K_DEBUG_LOG, Config.D_DEBUG_LOG);

        Button save = new Button(this);
        save.setText("Сохранить");
        save.setOnClickListener(v -> save());
        list.addView(save, matchWrap(dp(16)));

        Button app = new Button(this);
        app.setText("Открыть Яндекс Go в настройках (принудительная остановка)");
        app.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", TARGET, null)));
            } catch (Exception e) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
            }
        });
        list.addView(app, matchWrap(dp(4)));

        caption("Переключатели «Блокировать домены» и «Скриншоты» вступают в силу сразу, "
                + "но уже загруженная реклама исчезнет только после перезапуска приложения.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false);
    }

    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            // LSPosed (xposedsharedprefs) разрешает MODE_WORLD_READABLE, если модуль активен.
            SharedPreferences p = getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
            worldReadable = true;
            return p;
        } catch (SecurityException e) {
            worldReadable = false;
            return getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        }
    }

    private void status() {
        TextView t = new TextView(this);
        boolean active = ModuleStatus.isActive();
        if (active && worldReadable) {
            t.setText("✅ Модуль активен");
        } else if (!active) {
            t.setText("⚠ Модуль не активен. Включите его в LSPosed, отметьте Яндекс Go в области действия "
                    + "и перезапустите приложение.");
        } else {
            t.setText("⚠ Настройки недоступны приложению — обновите LSPosed.");
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        list.addView(t, matchWrap(0));
    }

    private void save() {
        save(true);
    }

    private void save(boolean toast) {
        prefs.edit()
                .putString(Config.K_RULES, rules.getText().toString())
                .putString(Config.K_EXTRA_HOSTS, hosts.getText().toString())
                .apply();
        if (toast) Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- разметка

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(t, matchWrap(dp(20)));
    }

    private void caption(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(0.75f);
        list.addView(t, matchWrap(dp(8)));
    }

    private void toggle(String title, String summary, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        list.addView(s, matchWrap(dp(12)));
        if (summary != null) {
            TextView t = new TextView(this);
            t.setText(summary);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setAlpha(0.65f);
            list.addView(t, matchWrap(0));
        }
    }

    private EditText editor(String value, String hint, int lines) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setHorizontallyScrolling(false);
        list.addView(e, matchWrap(dp(4)));
        return e;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
