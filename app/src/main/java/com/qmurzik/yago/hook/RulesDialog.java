package com.qmurzik.yago.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.qmurzik.yago.RuleEntry;

import java.util.List;

/** Список скрытых в приложении элементов: включить/выключить, удалить, скопировать. */
final class RulesDialog {
    private RulesDialog() {}

    static void show(final Activity a, final Ui ui, final Runnable onChanged) {
        final List<RuleEntry> list = HookConfig.localRules(a);

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(12), ui.dp(8));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);

        final Runnable save = () -> {
            HookConfig.setLocalRules(a, list);
            ViewHider.rescanAll();
            if (onChanged != null) onChanged.run();
        };

        final java.util.Set<String> off = HookConfig.disabledAfterCrash(a);
        if (!off.isEmpty()) {
            final LinearLayout warn = new LinearLayout(a);
            warn.setOrientation(LinearLayout.VERTICAL);
            warn.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            warn.setBackground(Ui.round(ui.tonal, ui.dp(12)));
            warn.addView(ui.text("Отключено после падения приложения: " + String.join(", ", off),
                    13, ui.onSurface, false));
            TextView back = ui.button("Включить снова", false);
            back.setOnClickListener(v -> {
                HookConfig.clearDisabledAfterCrash(a);
                ViewHider.rescanAll();
                box.removeView(warn);
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = ui.dp(8);
            warn.addView(back, blp);
            box.addView(warn);
        }

        if (list.isEmpty()) {
            box.addView(ui.text("Пока ничего не скрыто.\n\nОткройте пикер, нажмите на элемент и выберите «Скрыть элемент».",
                    14, ui.onSurfaceVariant, false));
        }
        for (final RuleEntry e : list) {
            final LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, ui.dp(8), 0, ui.dp(8));

            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = ui.text(e.title(), 15, ui.onSurface, true);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
            TextView r = ui.text(e.rule, 11, ui.onSurfaceVariant, false);
            r.setTypeface(Typeface.MONOSPACE);
            r.setMaxLines(2);
            r.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(r);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Switch sw = new Switch(a);
            sw.setChecked(e.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                e.enabled = on;
                save.run();
            });
            row.addView(sw);

            TextView del = ui.text("✕", 18, ui.onSurfaceVariant, false);
            del.setGravity(Gravity.CENTER);
            del.setOnClickListener(v -> {
                list.remove(e);
                box.removeView(row);
                save.run();
            });
            row.addView(del, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            box.addView(row);
        }

        ui.dialog()
                .setTitle("Скрытые элементы")
                .setView(scroll)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Копировать", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("YaGo rules", RuleEntry.toLines(list)));
                    Toast.makeText(a, "Правила скопированы — их можно вставить в настройки модуля (Импорт)",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }
}
