package com.qmurzik.yago.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.qmurzik.yago.RuleEntry;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Пикер элементов прямо в приложении.
 *
 * Открывается удержанием двух пальцев (~1 с) или плавающей кнопкой.
 * Тап по экрану выбирает элемент (он подсвечивается), «Шире»/«Уже» переключают
 * уровень вложенности, «Скрыть» сохраняет автоматически составленное правило.
 * Прокрутка экрана во время выбора работает как обычно.
 */
final class Picker {
    private static final long HOLD_MS = 1000;
    private static final float SLOP_DP = 16;

    private static Picker current;

    // --- жест открытия
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingHold;
    private static float hx0, hy0, hx1, hy1;

    private final Activity activity;
    private final Ui ui;
    private final ViewGroup decor;
    private final View layer;
    private final LinearLayout panel;
    private final TextView title, subtitle, ruleView, status;
    private final TextView wider, narrower, hide, hiddenList;
    private final ViewTreeObserver.OnPreDrawListener preDraw;

    private List<View> path = Collections.emptyList();
    private int index = -1;
    private List<RuleBuilder.Candidate> candidates = Collections.emptyList();
    private int chosen;
    private RuleEntry lastAdded;
    private final Rect lastRect = new Rect();

    // касания слоя: тап — выбор, движение — передаём приложению
    private MotionEvent down;
    private boolean forwarding;
    private View forwardTarget;

    // ================================================================ открытие/закрытие

    static boolean isOpen() {
        return current != null;
    }

    static void open(Activity a, int rawX, int rawY) {
        if (current != null && current.activity != a) close();
        if (current == null) current = new Picker(a);
        if (rawX >= 0) current.selectAt(rawX, rawY);
        Fab.setSuppressed(a, true);
    }

    static void close() {
        Picker p = current;
        current = null;
        if (p == null) return;
        p.decor.getViewTreeObserver().removeOnPreDrawListener(p.preDraw);
        p.decor.removeView(p.layer);
        p.decor.removeView(p.panel);
        Fab.setSuppressed(p.activity, false);
    }

    static void onPause(Activity a) {
        if (current != null && current.activity == a) close();
        cancelHold();
    }

    // ================================================================ касания

    /** Вызывается из Activity.dispatchTouchEvent: ловит жест открытия, пока пикер закрыт. */
    static void onTouch(Activity a, MotionEvent e) {
        if (current == null && HookConfig.enabled && HookConfig.inspector) detectHold(a, e);
    }

    private static void detectHold(final Activity a, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelHold();
                if (e.getPointerCount() != 2) return;
                hx0 = e.getX(0);
                hy0 = e.getY(0);
                hx1 = e.getX(1);
                hy1 = e.getY(1);
                final int rawX = (int) e.getRawX();
                final int rawY = (int) e.getRawY();
                final WeakReference<Activity> ref = new WeakReference<>(a);
                pendingHold = () -> {
                    pendingHold = null;
                    Activity act = ref.get();
                    if (act == null || act.isFinishing()) return;
                    try {
                        open(act, rawX, rawY);
                    } catch (Throwable t) {
                        Log.e("Ошибка пикера", t);
                    }
                };
                handler.postDelayed(pendingHold, HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pendingHold == null) return;
                float slop = SLOP_DP * 1.5f * a.getResources().getDisplayMetrics().density;
                if (e.getPointerCount() != 2
                        || Math.hypot(e.getX(0) - hx0, e.getY(0) - hy0) > slop
                        || Math.hypot(e.getX(1) - hx1, e.getY(1) - hy1) > slop) {
                    cancelHold();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                break;
            default:
                break;
        }
    }

    private static void cancelHold() {
        if (pendingHold != null) {
            handler.removeCallbacks(pendingHold);
            pendingHold = null;
        }
    }

    // ================================================================ интерфейс

    @SuppressLint("SetTextI18n")
    private Picker(Activity a) {
        activity = a;
        ui = new Ui(a);
        decor = (ViewGroup) a.getWindow().getDecorView();

        layer = new HighlightLayer();
        // касания и отрисовка в ViewGroup идут с учётом Z: ставим слой и панель выше всего
        layer.setTranslationZ(ui.dp(1000));
        ViewHider.ignore(layer);
        decor.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16));
        android.graphics.drawable.GradientDrawable bg = Ui.round(ui.surface, ui.dp(24));
        bg.setStroke(Math.max(1, ui.dp(1)), ui.outline);
        panel.setBackground(bg);
        panel.setTranslationZ(ui.dp(1001));
        panel.setOutlineProvider(null); // без гигантской тени от большого Z
        panel.setClickable(true); // касания по панели не уходят в приложение
        ViewHider.ignore(panel);

        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = ui.text("YaGo", 11, Ui.ON_ACCENT, true);
        badge.setBackground(Ui.round(Ui.ACCENT, ui.dp(8)));
        badge.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        header.addView(badge);
        title = ui.text("", 16, ui.onSurface, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = ui.dp(8);
        header.addView(title, tlp);
        TextView closeBtn = ui.text("✕", 18, ui.onSurfaceVariant, false);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setOnClickListener(v -> close());
        header.addView(closeBtn, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        panel.addView(header);

        subtitle = ui.text("", 13, ui.onSurfaceVariant, false);
        panel.addView(subtitle, lp(2));

        ruleView = ui.text("", 12, ui.onSurface, false);
        ruleView.setTypeface(Typeface.MONOSPACE);
        ruleView.setBackground(Ui.round(ui.tonal, ui.dp(10)));
        ruleView.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        ruleView.setMaxLines(3);
        ruleView.setEllipsize(TextUtils.TruncateAt.END);
        ruleView.setOnClickListener(v -> safe(() -> showAlternatives()));
        panel.addView(ruleView, lp(10));

        LinearLayout row = new LinearLayout(a);
        wider = ui.button("⬆ Шире", false);
        narrower = ui.button("⬇ Уже", false);
        hiddenList = ui.button("Скрытые", false);
        row.addView(wider, weighted(0));
        row.addView(narrower, weighted(ui.dp(8)));
        row.addView(hiddenList, weighted(ui.dp(8)));
        panel.addView(row, lp(12));
        wider.setOnClickListener(v -> safe(() -> move(-1)));
        narrower.setOnClickListener(v -> safe(() -> move(1)));
        hiddenList.setOnClickListener(v -> safe(() -> RulesDialog.show(activity, ui, this::refreshCounters)));

        hide = ui.button("Скрыть элемент", true);
        hide.setOnClickListener(v -> safe(() -> hideSelected()));
        hide.setOnLongClickListener(v -> {
            safe(this::showAlternatives);
            return true;
        });
        panel.addView(hide, lp(8));

        status = ui.text("", 13, ui.onSurface, false);
        status.setBackground(Ui.round(ui.tonal, ui.dp(12)));
        status.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        status.setVisibility(View.GONE);
        status.setOnClickListener(v -> safe(() -> undo()));
        panel.addView(status, lp(8));

        decor.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        preDraw = () -> {
            View t = target();
            Rect r = new Rect();
            if (t != null && t.isAttachedToWindow()) rectOf(t, r);
            if (!r.equals(lastRect)) {
                lastRect.set(r);
                layer.invalidate();
            }
            return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(preDraw);

        update();
        placePanel();
    }

    /** Ошибка модуля не должна ронять Яндекс Go: пишем в лог и показываем коротко. */
    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void fail(Throwable t) {
        Log.e("Ошибка пикера", t);
        try {
            Toast.makeText(activity, "YaGo: ошибка — " + t, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = ui.dp(topDp);
        return p;
    }

    private static LinearLayout.LayoutParams weighted(int leftPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = leftPx;
        return p;
    }

    /** Панель внизу; если выбранный элемент в нижней половине — наверху. */
    @SuppressWarnings("deprecation")
    private void placePanel() {
        View t = target();
        boolean top = false;
        if (t != null) {
            Rect r = new Rect();
            rectOf(t, r);
            top = r.centerY() > decor.getHeight() / 2;
        }
        WindowInsets in = decor.getRootWindowInsets();
        int insetTop = in != null ? in.getSystemWindowInsetTop() : ui.dp(24);
        int insetBottom = in != null ? in.getSystemWindowInsetBottom() : ui.dp(24);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        p.gravity = top ? Gravity.TOP : Gravity.BOTTOM;
        int m = ui.dp(10);
        p.setMargins(m, top ? insetTop + m : m, m, top ? m : insetBottom + m);
        panel.setLayoutParams(p);
    }

    // ================================================================ выбор

    private View target() {
        if (index < 0 || index >= path.size()) return null;
        return path.get(index);
    }

    private void selectAt(int x, int y) {
        List<View> p = new ArrayList<>();
        hit(decor, x, y, p);
        // корень окна выбирать бессмысленно
        if (!p.isEmpty()) p.remove(0);
        path = p;
        index = p.size() - 1;
        status.setVisibility(View.GONE);
        onSelectionChanged();
    }

    private void move(int delta) {
        int n = index + delta;
        if (n < 0 || n >= path.size()) return;
        index = n;
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        View t = target();
        candidates = t == null ? Collections.emptyList() : RuleBuilder.build(decor, t);
        chosen = 0;
        update();
        placePanel();
        layer.invalidate();
    }

    @SuppressLint("SetTextI18n")
    private void update() {
        View t = target();
        refreshCounters();
        if (t == null) {
            title.setText("Выберите элемент");
            subtitle.setText("Нажмите на то, что хотите скрыть. Прокручивать экран можно как обычно.");
            ruleView.setVisibility(View.GONE);
            setEnabled(wider, false);
            setEnabled(narrower, false);
            setEnabled(hide, false);
            return;
        }
        title.setText(RuleBuilder.label(t));
        float d = activity.getResources().getDisplayMetrics().density;
        String size = Math.round(t.getWidth() / d) + "×" + Math.round(t.getHeight() / d) + " dp";
        subtitle.setText(t.getClass().getSimpleName() + " · " + size + " · уровень " + (index + 1) + " из " + path.size());
        ruleView.setVisibility(View.VISIBLE);
        if (candidates.isEmpty()) {
            ruleView.setText("Не удалось составить правило — выберите уровень шире");
            setEnabled(hide, false);
        } else {
            RuleBuilder.Candidate c = candidates.get(chosen);
            ruleView.setText(c.rule + "\n" + c.explain() + (candidates.size() > 1 ? " · ещё варианты ›" : ""));
            setEnabled(hide, true);
        }
        setEnabled(wider, index > 0);
        setEnabled(narrower, index < path.size() - 1);
    }

    private void refreshCounters() {
        int n = HookConfig.localRules(activity).size();
        hiddenList.setText(n > 0 ? "Скрытые · " + n : "Скрытые");
    }

    private static void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    private void showAlternatives() {
        if (candidates.size() < 2) return;
        String[] items = new String[candidates.size()];
        for (int i = 0; i < items.length; i++) {
            RuleBuilder.Candidate c = candidates.get(i);
            items[i] = c.rule + "\n— " + c.explain();
        }
        ui.dialog()
                .setTitle("Как узнавать этот элемент")
                .setSingleChoiceItems(items, chosen, (dlg, which) -> {
                    chosen = which;
                    update();
                    dlg.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @SuppressLint("SetTextI18n")
    private void hideSelected() {
        final View t = target();
        if (t == null || candidates.isEmpty()) return;
        final RuleEntry e = new RuleEntry(RuleBuilder.label(t), candidates.get(chosen).rule, true);
        List<RuleEntry> list = HookConfig.localRules(activity);
        list.add(e);
        HookConfig.setLocalRules(activity, list);
        lastAdded = e;
        path = Collections.emptyList();
        index = -1;
        candidates = Collections.emptyList();

        // применяем сразу и проверяем результат
        ViewHider.attachRoot(decor);
        ViewHider.scanNow(decor);
        ViewHider.rescanAll();
        update();
        placePanel();
        layer.invalidate();
        Log.i("Скрытие «" + e.title() + "» правилом " + e.rule + ": " + ViewHider.diagnose(decor, t, e.rule));

        if (t.getVisibility() == View.GONE) {
            showStatus("Скрыто: «" + e.title() + "».  Отменить");
        } else {
            showStatus("Правило сохранено, но элемент не скрылся.\n" + ViewHider.diagnose(decor, t, e.rule)
                    + "\nНажмите, чтобы отменить");
        }
        // приложение может заново показать элемент — проверяем чуть позже
        decor.postDelayed(() -> {
            if (current != this || lastAdded != e) return;
            if (t.isAttachedToWindow() && t.isShown()) {
                String d = ViewHider.diagnose(decor, t, e.rule);
                Log.i("Элемент снова виден: " + d);
                showStatus("Элемент снова показан приложением.\n" + d + "\nНажмите, чтобы отменить");
            }
        }, 700);
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        placePanel();
    }

    private void undo() {
        if (lastAdded == null) return;
        List<RuleEntry> list = HookConfig.localRules(activity);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).rule.equals(lastAdded.rule)) {
                list.remove(i);
                break;
            }
        }
        HookConfig.setLocalRules(activity, list);
        lastAdded = null;
        status.setVisibility(View.GONE);
        ViewHider.rescanAll();
        refreshCounters();
        Toast.makeText(activity, "Возвращено", Toast.LENGTH_SHORT).show();
    }

    // ================================================================ геометрия

    /** Путь от корня до самого вложенного видимого элемента под точкой (экранные координаты). */
    private static void hit(View v, int x, int y, List<View> out) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() == 0 || v.getHeight() == 0) return;
        if (ViewHider.isIgnored(v)) return;
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

    private void rectOf(View v, Rect out) {
        int[] a = new int[2], b = new int[2];
        v.getLocationOnScreen(a);
        layer.getLocationOnScreen(b);
        out.set(a[0] - b[0], a[1] - b[1], a[0] - b[0] + v.getWidth(), a[1] - b[1] + v.getHeight());
    }

    /** Слой с подсветкой. Сам касания не принимает — они проходят в приложение. */
    private final class HighlightLayer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint parent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect r = new Rect();
        private final RectF rf = new RectF();

        HighlightLayer() {
            super(activity);
            fill.setColor(Ui.alpha(Ui.HIGHLIGHT, 50));
            stroke.setColor(Ui.HIGHLIGHT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(ui.dp(2.5f));
            parent.setColor(Ui.alpha(Ui.HIGHLIGHT, 120));
            parent.setStyle(Paint.Style.STROKE);
            parent.setStrokeWidth(ui.dp(1));
            parent.setPathEffect(new android.graphics.DashPathEffect(new float[]{ui.dp(4), ui.dp(4)}, 0));
        }

        /**
         * Слой лежит поверх окна и первым получает касания, независимо от того,
         * как приложение обрабатывает их в Activity. Тап без движения выбирает
         * элемент и в приложение не попадает; как только палец сдвинулся или
         * появился второй палец — весь жест передаётся приложению (прокрутка, карта).
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            try {
                return handleTouch(e);
            } catch (Throwable t) {
                fail(t);
                finishGesture();
                return true;
            }
        }

        private boolean handleTouch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    recycleDown();
                    down = MotionEvent.obtain(e);
                    forwarding = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    startForwarding();
                    forward(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!forwarding && down != null
                            && Math.hypot(e.getX() - down.getX(), e.getY() - down.getY()) > ui.dp(SLOP_DP)) {
                        startForwarding();
                    }
                    if (forwarding) forward(e);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (forwarding) {
                        forward(e);
                    } else if (down != null) {
                        selectAt((int) down.getRawX(), (int) down.getRawY());
                    }
                    finishGesture();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (forwarding) forward(e);
                    finishGesture();
                    return true;
                default:
                    if (forwarding) forward(e);
                    return true;
            }
        }

        private void startForwarding() {
            if (forwarding) return;
            forwarding = true;
            forwardTarget = null;
            for (int i = decor.getChildCount() - 1; i >= 0; i--) {
                View c = decor.getChildAt(i);
                if (!ViewHider.isIgnored(c) && c.getVisibility() == View.VISIBLE) {
                    forwardTarget = c;
                    break;
                }
            }
            if (down != null) forward(down);
        }

        private void forward(MotionEvent e) {
            View t = forwardTarget;
            if (t == null) return;
            MotionEvent copy = MotionEvent.obtain(e);
            copy.offsetLocation(getLeft() - t.getLeft(), getTop() - t.getTop());
            t.dispatchTouchEvent(copy);
            copy.recycle();
        }

        private void finishGesture() {
            forwarding = false;
            forwardTarget = null;
            recycleDown();
        }

        private void recycleDown() {
            if (down != null) {
                down.recycle();
                down = null;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            View t = target();
            if (t == null || !t.isAttachedToWindow()) return;
            if (index > 0) {
                rectOf(path.get(index - 1), r);
                c.drawRect(r, parent);
            }
            rectOf(t, r);
            rf.set(r);
            float rad = ui.dp(6);
            c.drawRoundRect(rf, rad, rad, fill);
            c.drawRoundRect(rf, rad, rad, stroke);
        }
    }
}
