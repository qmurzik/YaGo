package com.qmurzik.yago.hook;

import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Скрывает элементы интерфейса по правилам и эвристикам.
 * Работает только с классами Android SDK, поэтому не зависит от версии приложения.
 * Все методы вызываются в главном потоке.
 */
final class ViewHider {
    private static final long MIN_INTERVAL_MS = 250;
    private static final int MAX_CARD_DEPTH = 8;

    /** Типичные id рекламных контейнеров (если имена ресурсов не обфусцированы). */
    private static final Pattern AD_ID = Pattern.compile(
            "(^|_)(ad|ads|adview|ad_view|advert|adverts|advertising|advertisement|admob|adfox|native_ad|banner_ad|ad_banner|ad_container|sponsored)(_|$)",
            Pattern.CASE_INSENSITIVE);

    /** Классы рекламных SDK (имена из публичного API SDK). */
    private static final Pattern AD_CLASS = Pattern.compile(
            "^(com\\.yandex\\.mobile\\.ads\\.|com\\.google\\.android\\.gms\\.ads\\.|com\\.my\\.target\\.|com\\.applovin\\.|com\\.unity3d\\.ads\\.)"
                    + "|(AdView|NativeAdView|BannerAdView|AdContainer)$");

    /** Пометка «Реклама» на карточке. */
    private static final Pattern AD_LABEL = Pattern.compile(
            "^\\s*(реклама|рекламa|ad|ads|advertisement|sponsored|спонсорский материал|партн[её]рский материал)(\\s*[·•|,.-]?\\s*(18|16|12|6|0)\\+)?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final class Saved {
        int visibility;
        boolean lpZeroed;
        int width, height, ml, mt, mr, mb;
    }

    private static final Map<View, Saved> hidden = new WeakHashMap<>();
    /** Собственные элементы модуля (пикер, плавающая кнопка) — не трогаем. */
    private static final Map<View, Boolean> ignored = new WeakHashMap<>();
    private static final Map<View, Boolean> roots = new WeakHashMap<>();
    private static final Map<View, Runnable> pending = new WeakHashMap<>();
    private static final Map<View, Long> lastScan = new WeakHashMap<>();
    private static final SparseArray<String> idNames = new SparseArray<>();
    private static final Map<Class<?>, Boolean> adClassCache = new HashMap<>();
    private static final Map<Class<?>, Boolean> scrollClassCache = new HashMap<>();

    private ViewHider() {}

    // ---------------------------------------------------------------- корни окон

    static void attachRoot(final View root) {
        if (root == null || roots.containsKey(root)) return;
        roots.put(root, Boolean.TRUE);
        ViewTreeObserver vto = root.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(() -> schedule(root));
        vto.addOnScrollChangedListener(() -> schedule(root));
        schedule(root);
    }

    static void ignore(View v) {
        ignored.put(v, Boolean.TRUE);
    }

    static boolean isIgnored(View v) {
        return ignored.containsKey(v);
    }

    static void rescanAll() {
        for (View root : new ArrayList<>(roots.keySet())) {
            lastScan.remove(root);
            schedule(root);
        }
    }

    static void schedule(final View root) {
        if (pending.containsKey(root)) return;
        Runnable r = () -> {
            pending.remove(root);
            lastScan.put(root, SystemClock.uptimeMillis());
            try {
                scan(root);
            } catch (Throwable t) {
                Log.e("Ошибка при обходе интерфейса", t);
            }
        };
        pending.put(root, r);
        Long last = lastScan.get(root);
        long wait = last == null ? 16 : Math.max(16, MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - last));
        root.postDelayed(r, wait);
    }

    /** Немедленный обход окна (без ожидания) — для проверки сразу после создания правила. */
    static void scanNow(View root) {
        Runnable r = pending.remove(root);
        if (r != null) root.removeCallbacks(r);
        lastScan.put(root, SystemClock.uptimeMillis());
        scan(root);
    }

    /** Диагностика: почему элемент виден, хотя правило для него есть. */
    static String diagnose(View root, View target, String ruleText) {
        StringBuilder b = new StringBuilder();
        b.append("enabled=").append(HookConfig.enabled);
        b.append(", rules=").append(HookConfig.rules.size());
        boolean loaded = false;
        for (Rule r : HookConfig.rules) if (r.raw.equals(ruleText.trim())) loaded = true;
        b.append(", загружено=").append(loaded);
        Rule rule = Rule.parse(ruleText);
        b.append(", разобрано=").append(rule != null);
        if (rule != null) b.append(", находит=").append(evaluate(root, rule).contains(target));
        b.append(", в списке скрытых=").append(hidden.containsKey(target));
        b.append(", visibility=").append(target.getVisibility());
        b.append(", attached=").append(target.isAttachedToWindow());
        b.append(", rootOk=").append(target.getRootView() == root);
        b.append(", корней=").append(roots.size()).append(", rootизвестен=").append(roots.containsKey(root));
        return b.toString();
    }

    // ---------------------------------------------------------------- обход

    /** Источник скрытия для эвристик (для защиты от падений). */
    static final String SRC_ADS = "@ads";
    static final String SRC_LABELED = "@labeled";

    /** Правила, из-за которых что-то было скрыто впервые, и когда это было. */
    private static final Set<String> recentSources = new java.util.HashSet<>();
    private static volatile long recentTime;

    static synchronized Set<String> recentSources(long withinMs) {
        if (SystemClock.uptimeMillis() - recentTime > withinMs) return Collections.emptySet();
        return new java.util.HashSet<>(recentSources);
    }

    private static void scan(View root) {
        boolean active = HookConfig.enabled
                && (HookConfig.hideAdViews || HookConfig.hideAdLabeled || !HookConfig.rules.isEmpty());
        Map<View, String> targets = new IdentityHashMap<>();
        if (active && root.isAttachedToWindow()) {
            walk(root, root, HookConfig.rules, true, targets);
            escalateEmptyParents(root, targets);
        }
        boolean fresh = false;
        for (Map.Entry<View, String> t : targets.entrySet()) {
            if (!hidden.containsKey(t.getKey())) {
                synchronized (ViewHider.class) {
                    if (!fresh) {
                        recentSources.clear();
                        fresh = true;
                    }
                    recentSources.add(t.getValue());
                    recentTime = SystemClock.uptimeMillis();
                }
            }
            hide(t.getKey());
        }

        // Возвращаем элементы, которые больше не подходят под правила
        // (например, переиспользованные ячейки списка).
        List<View> restore = new ArrayList<>();
        for (View v : hidden.keySet()) {
            if (!targets.containsKey(v) && (v.getRootView() == root || !v.isAttachedToWindow())) restore.add(v);
        }
        for (View v : restore) show(v);
        if (!targets.isEmpty()) Log.d("Скрыто элементов: " + targets.size());
    }

    /**
     * Контейнер, у которого не осталось ни одного видимого элемента, скрываем целиком.
     * Пустой контейнер бесполезен, а некоторые (сетка DivKit) на пустом списке падают.
     */
    private static void escalateEmptyParents(View root, Map<View, String> targets) {
        ArrayDeque<View> queue = new ArrayDeque<>(targets.keySet());
        while (!queue.isEmpty()) {
            View v = queue.poll();
            ViewParent p = v.getParent();
            if (!(p instanceof ViewGroup) || p == root || targets.containsKey(p)) continue;
            ViewGroup g = (ViewGroup) p;
            boolean anyVisible = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || ignored.containsKey(c) || targets.containsKey(c)) continue;
                if (c.getVisibility() != View.GONE) {
                    anyVisible = true;
                    break;
                }
            }
            if (!anyVisible) {
                targets.put(g, targets.get(v));
                queue.add(g);
            }
        }
    }

    /** Что именно скрыло бы правило в этом окне (для проверки при создании правила). */
    static Set<View> evaluate(View root, Rule rule) {
        Map<View, String> targets = new IdentityHashMap<>();
        walk(root, root, Collections.singletonList(rule), false, targets);
        return targets.keySet();
    }

    private static void walk(View v, View root, List<Rule> rules, boolean heuristics, Map<View, String> targets) {
        if (ignored.containsKey(v)) return;
        if (v != root) {
            for (int i = 0; i < rules.size(); i++) {
                Rule r = rules.get(i);
                if (matches(r, v)) {
                    View t = ancestor(v, r.up);
                    if (t != null && t != root && !targets.containsKey(t)) targets.put(t, r.raw);
                }
            }
            if (heuristics && HookConfig.hideAdViews && isAdView(v) && !targets.containsKey(v)) targets.put(v, SRC_ADS);
            if (heuristics && HookConfig.hideAdLabeled && v instanceof TextView) {
                CharSequence text = ((TextView) v).getText();
                if (text != null && text.length() <= 40 && AD_LABEL.matcher(text).matches()) {
                    View card = findCard(v, root);
                    if (card != null && !targets.containsKey(card)) targets.put(card, SRC_LABELED);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) {
                View c = g.getChildAt(i);
                if (c != null) walk(c, root, rules, heuristics, targets);
            }
        }
    }

    static boolean matches(Rule r, View v) {
        switch (r.type) {
            case ID:
                return r.matches(idName(v));
            case TEXT:
                if (!(v instanceof TextView)) return false;
                CharSequence t = ((TextView) v).getText();
                return t != null && r.matches(t.toString());
            case DESC:
                CharSequence d = v.getContentDescription();
                return d != null && r.matches(d.toString());
            case CLASS:
                return r.matches(v.getClass().getName());
        }
        return false;
    }

    static View ancestor(View v, int up) {
        View cur = v;
        for (int i = 0; i < up; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return null;
            cur = (View) p;
        }
        return cur;
    }

    private static boolean isAdView(View v) {
        Class<?> c = v.getClass();
        Boolean cached = adClassCache.get(c);
        if (cached == null) {
            cached = Boolean.FALSE;
            for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
                String n = k.getName();
                if (n.startsWith("android.")) break;
                if (AD_CLASS.matcher(n).find()) {
                    cached = Boolean.TRUE;
                    break;
                }
            }
            adClassCache.put(c, cached);
        }
        if (cached) return true;
        String id = idName(v);
        return id != null && AD_ID.matcher(id).find();
    }

    /** Ближайший предок, являющийся элементом прокручиваемого списка/ленты. */
    private static View findCard(View label, View root) {
        View cur = label;
        for (int i = 0; i < MAX_CARD_DEPTH; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View) || p == root) return null;
            if (isScrollContainer((View) p)) return cur;
            cur = (View) p;
        }
        return null;
    }

    private static boolean isScrollContainer(View v) {
        if (v instanceof AdapterView || v instanceof ScrollView || v instanceof HorizontalScrollView) return true;
        Class<?> c = v.getClass();
        Boolean cached = scrollClassCache.get(c);
        if (cached == null) {
            cached = Boolean.FALSE;
            for (Class<?> k = c; k != null && k != View.class; k = k.getSuperclass()) {
                String n = k.getSimpleName();
                if (n.contains("RecyclerView") || n.contains("ScrollView") || n.contains("ViewPager")) {
                    cached = Boolean.TRUE;
                    break;
                }
            }
            scrollClassCache.put(c, cached);
        }
        return cached;
    }

    static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return null;
        String name = idNames.get(id);
        if (name == null) {
            try {
                name = v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                name = "";
            }
            idNames.put(id, name);
        }
        return name.isEmpty() ? null : name;
    }

    // ---------------------------------------------------------------- скрытие

    static boolean isHidden(View v) {
        return hidden.containsKey(v);
    }

    /** Вызывается из хука View.setVisibility: запоминает желаемую видимость и не даёт показать элемент. */
    static int onSetVisibility(View v, int visibility) {
        Saved s = hidden.get(v);
        if (s == null) return visibility;
        s.visibility = visibility;
        return View.GONE;
    }

    private static void hide(View v) {
        Saved s = hidden.get(v);
        if (s == null) {
            s = new Saved();
            s.visibility = v.getVisibility();
            hidden.put(v, s);
            v.setVisibility(View.GONE); // пройдёт через хук и останется GONE
        } else if (v.getVisibility() != View.GONE) {
            // ART может встроить setVisibility в код приложения, и тогда хук его не видит:
            // приложение снова показало элемент — запоминаем и прячем заново.
            s.visibility = v.getVisibility();
            v.setVisibility(View.GONE);
        }
        // RecyclerView и похожие контейнеры не убирают место под GONE-элементом,
        // поэтому дополнительно обнуляем размеры.
        ViewParent p = v.getParent();
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (p instanceof View && lp != null && isScrollContainer((View) p) && !(p instanceof ScrollView)
                && !(p instanceof HorizontalScrollView)) {
            if (!s.lpZeroed || lp.width != 0 || lp.height != 0) {
                s.width = lp.width;
                s.height = lp.height;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    s.ml = m.leftMargin;
                    s.mt = m.topMargin;
                    s.mr = m.rightMargin;
                    s.mb = m.bottomMargin;
                    m.setMargins(0, 0, 0, 0);
                }
                lp.width = 0;
                lp.height = 0;
                s.lpZeroed = true;
                v.setLayoutParams(lp);
            }
        }
    }

    private static void show(View v) {
        Saved s = hidden.remove(v);
        if (s == null) return;
        if (s.lpZeroed) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.width = s.width;
                lp.height = s.height;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(s.ml, s.mt, s.mr, s.mb);
                }
                v.setLayoutParams(lp);
            }
        }
        v.setVisibility(s.visibility);
    }
}
