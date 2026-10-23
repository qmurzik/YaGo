package com.qmurzik.yago.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Инспектор: удерживайте два пальца неподвижно ~1.5 с на нужном месте экрана,
 * появится список элементов под первым пальцем — от самого вложенного до корня.
 * Выбранный элемент можно скрыть (правило сохранится в приложении) или скопировать правило.
 */
final class Inspector {
    private static final long HOLD_MS = 1500;
    private static final float SLOP_DP = 24;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static float x0, y0, x1, y1;

    private Inspector() {}

    static void onTouch(final Activity activity, MotionEvent e) {
        if (!HookConfig.enabled || !HookConfig.inspector) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel();
                if (e.getPointerCount() != 2) return;
                x0 = e.getX(0);
                y0 = e.getY(0);
                x1 = e.getX(1);
                y1 = e.getY(1);
                final int rawX = (int) e.getRawX();
                final int rawY = (int) e.getRawY();
                final WeakReference<Activity> ref = new WeakReference<>(activity);
                pending = () -> {
                    pending = null;
                    Activity a = ref.get();
                    if (a != null && !a.isFinishing()) {
                        try {
                            showPicker(a, rawX, rawY);
                        } catch (Throwable t) {
                            Log.e("Ошибка инспектора", t);
                        }
                    }
                };
                handler.postDelayed(pending, HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pending == null) return;
                float slop = SLOP_DP * activity.getResources().getDisplayMetrics().density;
                if (e.getPointerCount() != 2
                        || Math.hypot(e.getX(0) - x0, e.getY(0) - y0) > slop
                        || Math.hypot(e.getX(1) - x1, e.getY(1) - y1) > slop) {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
    }

    private static void cancel() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
    }

    // ---------------------------------------------------------------- диалоги

    private static AlertDialog.Builder builder(Context ctx) {
        return new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private static void showPicker(final Activity a, int x, int y) {
        final List<View> path = new ArrayList<>();
        hit(a.getWindow().getDecorView(), x, y, path);
        final List<View> chain = new ArrayList<>();
        for (int i = path.size() - 1; i >= 1; i--) chain.add(path.get(i)); // без DecorView

        final int localCount = HookConfig.localRules(a).size();
        String[] items = new String[chain.size() + 1];
        items[0] = "⚙ Локальные правила (" + localCount + ")…";
        for (int i = 0; i < chain.size(); i++) items[i + 1] = (i == 0 ? "" : "↑" + i + "  ") + describe(chain.get(i));

        builder(a)
                .setTitle("YaGo: элементы под пальцем")
                .setItems(items, (d, which) -> {
                    if (which == 0) showLocalRules(a);
                    else showRuleChooser(a, chain.get(which - 1));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void showRuleChooser(final Activity a, final View target) {
        final List<String> rules = candidates(target);
        final GradientDrawable frame = highlight(target);

        if (rules.isEmpty()) {
            Toast.makeText(a, "Для этого элемента не удалось составить правило. Выберите другой уровень.", Toast.LENGTH_LONG).show();
            target.getOverlay().remove(frame);
            return;
        }
        final int[] selected = {0};
        AlertDialog dialog = builder(a)
                .setTitle(describe(target))
                .setSingleChoiceItems(rules.toArray(new String[0]), 0, (d, which) -> selected[0] = which)
                .setPositiveButton("Скрыть", (d, w) -> {
                    List<String> local = HookConfig.localRules(a);
                    String rule = rules.get(selected[0]);
                    if (!local.contains(rule)) local.add(rule);
                    HookConfig.setLocalRules(a, local);
                    ViewHider.rescanAll();
                    Toast.makeText(a, "Добавлено правило: " + rule, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Копировать", (d, w) -> copy(a, rules.get(selected[0])))
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnDismissListener(d -> target.getOverlay().remove(frame));
        dialog.show();
    }

    private static void showLocalRules(final Activity a) {
        final List<String> local = HookConfig.localRules(a);
        if (local.isEmpty()) {
            Toast.makeText(a, "Локальных правил нет", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean[] checked = new boolean[local.size()];
        builder(a)
                .setTitle("Отметьте правила для удаления")
                .setMultiChoiceItems(local.toArray(new String[0]), checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Удалить", (d, w) -> {
                    List<String> keep = new ArrayList<>();
                    for (int i = 0; i < local.size(); i++) if (!checked[i]) keep.add(local.get(i));
                    HookConfig.setLocalRules(a, keep);
                    ViewHider.rescanAll();
                })
                .setNeutralButton("Копировать все", (d, w) -> copy(a, String.join("\n", local)))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("YaGo rules", text));
        Toast.makeText(ctx, "Скопировано. Вставьте в настройки модуля.", Toast.LENGTH_SHORT).show();
    }

    private static GradientDrawable highlight(View v) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(60, 255, 0, 0));
        g.setStroke((int) (3 * v.getResources().getDisplayMetrics().density), Color.RED);
        g.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.getOverlay().add(g);
        return g;
    }

    // ---------------------------------------------------------------- анализ

    /** Путь от корня до самого вложенного видимого элемента под точкой (экранные координаты). */
    private static void hit(View v, int x, int y, List<View> out) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() == 0 || v.getHeight() == 0) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.getWidth() || y >= loc[1] + v.getHeight()) return;
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                int before = out.size();
                hit(g.getChildAt(i), x, y, out);
                if (out.size() > before) break;
            }
        }
    }

    static String describe(View v) {
        StringBuilder b = new StringBuilder(v.getClass().getSimpleName());
        if (b.length() == 0) b.append(v.getClass().getName());
        String id = ViewHider.idName(v);
        if (id != null) b.append("  #").append(id);
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) b.append("  «").append(shorten(t.toString(), 30)).append('»');
        }
        CharSequence d = v.getContentDescription();
        if (d != null && d.length() > 0) b.append("  [").append(shorten(d.toString(), 30)).append(']');
        float density = v.getResources().getDisplayMetrics().density;
        b.append("  ").append(Math.round(v.getWidth() / density)).append('×')
                .append(Math.round(v.getHeight() / density)).append("dp");
        return b.toString();
    }

    private static String shorten(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String textRule(String text, int up) {
        String t = text.trim();
        String r = t.length() <= 60 ? "text:^" + Rule.escape(t) + "$" : "text:^" + Rule.escape(t.substring(0, 60));
        return up > 0 ? r + " ^" + up : r;
    }

    private static List<String> candidates(View t) {
        List<String> out = new ArrayList<>();
        String id = ViewHider.idName(t);
        if (id != null) out.add("id:^" + Rule.escape(id) + "$");
        if (t instanceof TextView) {
            CharSequence s = ((TextView) t).getText();
            if (s != null && s.toString().trim().length() > 0) out.add(textRule(s.toString(), 0));
        }
        CharSequence d = t.getContentDescription();
        if (d != null && d.toString().trim().length() > 0) out.add("desc:^" + Rule.escape(d.toString().trim()) + "$");

        // Если у самого контейнера нет признаков — ищем внутри него элемент с id/текстом
        // и поднимаемся от него на нужное число уровней.
        if (t instanceof ViewGroup) {
            String idRule = null, textRule = null, descRule = null;
            ArrayDeque<Object[]> queue = new ArrayDeque<>();
            ViewGroup g = (ViewGroup) t;
            for (int i = 0; i < g.getChildCount(); i++) queue.add(new Object[]{g.getChildAt(i), 1});
            while (!queue.isEmpty() && (idRule == null || textRule == null || descRule == null)) {
                Object[] item = queue.poll();
                View v = (View) item[0];
                int depth = (Integer) item[1];
                if (depth > 6) continue;
                String cid = ViewHider.idName(v);
                if (idRule == null && cid != null) idRule = "id:^" + Rule.escape(cid) + "$ ^" + depth;
                if (textRule == null && v instanceof TextView) {
                    CharSequence s = ((TextView) v).getText();
                    if (s != null && s.toString().trim().length() > 0) textRule = textRule(s.toString(), depth);
                }
                CharSequence cd = v.getContentDescription();
                if (descRule == null && cd != null && cd.toString().trim().length() > 0) {
                    descRule = "desc:^" + Rule.escape(cd.toString().trim()) + "$ ^" + depth;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup cg = (ViewGroup) v;
                    for (int i = 0; i < cg.getChildCount(); i++) queue.add(new Object[]{cg.getChildAt(i), depth + 1});
                }
            }
            if (idRule != null) out.add(idRule);
            if (textRule != null) out.add(textRule);
            if (descRule != null) out.add(descRule);
        }

        String cls = t.getClass().getName();
        if (!cls.startsWith("android.") && !cls.startsWith("androidx.") && !cls.startsWith("com.google.android.material.")) {
            out.add("class:^" + Rule.escape(cls) + "$");
        }
        return out;
    }
}
