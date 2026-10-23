package com.qmurzik.yago.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/** Плавающая кнопка открытия пикера. Перетаскивается по вертикали и между краями экрана. */
final class Fab {
    private static final Map<Activity, View> buttons = new WeakHashMap<>();
    private static float savedY = 0.62f;
    private static boolean savedLeft;

    private Fab() {}

    static void sync(Activity a) {
        View existing = buttons.get(a);
        boolean want = HookConfig.enabled && HookConfig.fab;
        if (!want) {
            if (existing != null) {
                ((ViewGroup) existing.getParent()).removeView(existing);
                buttons.remove(a);
            }
            return;
        }
        if (existing == null) create(a);
    }

    static void setSuppressed(Activity a, boolean suppressed) {
        View b = buttons.get(a);
        if (b != null) b.setVisibility(suppressed ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void create(final Activity a) {
        final Ui ui = new Ui(a);
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        final TextView b = ui.text("✂", 20, Ui.ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(Ui.ACCENT, ui.dp(24)));
        b.setElevation(ui.dp(8));
        b.setTranslationZ(ui.dp(900));
        b.setOutlineProvider(null);
        b.setAlpha(0.85f);
        b.setContentDescription("YaGo: скрыть элемент");
        int size = ui.dp(48);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        ViewHider.ignore(b);
        decor.addView(b, lp);
        // размер окна может быть ещё неизвестен — ставим кнопку после каждой раскладки
        decor.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) place(b, decor, ui);
        });
        b.post(() -> place(b, decor, ui));

        b.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, sx, sy;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dx = v.getX() - sx;
                        dy = v.getY() - sy;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > ui.dp(8)) dragging = true;
                        if (dragging) {
                            v.setX(e.getRawX() + dx);
                            v.setY(e.getRawY() + dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            savedLeft = v.getX() + v.getWidth() / 2f < decor.getWidth() / 2f;
                            savedY = Math.max(0.05f, Math.min(0.92f, v.getY() / Math.max(1, decor.getHeight())));
                            place(v, decor, ui);
                        } else {
                            Picker.open(a, -1, -1);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        buttons.put(a, b);
    }

    private static void place(View b, ViewGroup decor, Ui ui) {
        int m = ui.dp(8);
        b.setX(savedLeft ? m : decor.getWidth() - b.getWidth() - m);
        b.setY(savedY * decor.getHeight());
    }
}
