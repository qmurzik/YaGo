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
        /** GONE или INVISIBLE («оставить место»). */
        int mode = View.GONE;
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
        // Изменения раскладки/прокрутки помечают окно «грязным», а проверка идёт прямо
        // перед отрисовкой кадра: так скрытые элементы не мелькают при возврате на экран
        // и при появлении новых ячеек списка.
        final boolean[] dirty = {true};
        final int[] cancelled = {0};
        vto.addOnGlobalLayoutListener(() -> dirty[0] = true);
        vto.addOnScrollChangedListener(() -> dirty[0] = true);
        vto.addOnPreDrawListener(() -> {
            if (!dirty[0]) return true;
            dirty[0] = false;
            boolean changed = false;
            try {
                lastScan.put(root, SystemClock.uptimeMillis());
                changed = scan(root);
            } catch (Throwable t) {
                Log.e("Ошибка при обходе интерфейса", t);
            }
            // Что-то только что скрыли — отменяем этот кадр, следующий нарисуется уже без них.
            // Не больше двух отмен подряд, чтобы не зависнуть, если приложение упорно показывает элемент.
            if (changed && cancelled[0] < 2) {
                cancelled[0]++;
                return false;
            }
            cancelled[0] = 0;
            return true;
        });
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

    /** Были ли в последнем обходе изменения видимости/размеров. */
    private static boolean scanChanged;

    /** @return true, если что-то было скрыто или показано */
    private static boolean scan(View root) {
        scanChanged = false;
        boolean active = HookConfig.enabled
                && (HookConfig.hideAdViews || HookConfig.hideAdLabeled || !HookConfig.rules.isEmpty());
        Map<View, String> targets = new IdentityHashMap<>();
        Set<View> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        if (active && root.isAttachedToWindow()) {
            walk(root, root, HookConfig.rules, true, targets, keep);
            escalateEmptyParents(root, targets, keep);
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
            hide(t.getKey(), keep.contains(t.getKey()));
        }

        // Возвращаем элементы, которые больше не подходят под правила
        // (например, переиспользованные ячейки списка).
        List<View> restore = new ArrayList<>();
        for (View v : hidden.keySet()) {
            // Отсоединённые элементы (экран ушёл в фон, ячейка в запасе у списка) не трогаем:
            // иначе при возврате они на мгновение видны. Их проверим, когда снова появятся в окне.
            if (!targets.containsKey(v) && v.isAttachedToWindow() && v.getRootView() == root) restore.add(v);
        }
        for (View v : restore) show(v);
        if (!targets.isEmpty()) Log.d("Скрыто элементов: " + targets.size());
        return scanChanged;
    }

    /**
     * Контейнер, у которого не осталось ни одного видимого элемента, скрываем целиком.
     * Пустой контейнер бесполезен, а некоторые (сетка DivKit) на пустом списке падают.
     */
    private static void escalateEmptyParents(View root, Map<View, String> targets, Set<View> keep) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        for (View v : targets.keySet()) if (!keep.contains(v)) queue.add(v); // «оставить место» не освобождает контейнер
        while (!queue.isEmpty()) {
            View v = queue.poll();
            ViewParent p = v.getParent();
            if (!(p instanceof ViewGroup) || p == root || targets.containsKey(p)) continue;
            ViewGroup g = (ViewGroup) p;
            boolean anyVisible = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || ignored.containsKey(c) || (targets.containsKey(c) && !keep.contains(c))) continue;
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
        walk(root, root, Collections.singletonList(rule), false, targets, new java.util.HashSet<>());
        return targets.keySet();
    }

    private static void walk(View v, View root, List<Rule> rules, boolean heuristics, Map<View, String> targets,
                             Set<View> keep) {
        if (ignored.containsKey(v)) return;
        if (v != root) {
            for (int i = 0; i < rules.size(); i++) {
                Rule r = rules.get(i);
                if (matches(r, v)) {
                    View t = r.target(v);
                    if (t != null && t != root && !ignored.containsKey(t) && !targets.containsKey(t)) {
                        targets.put(t, r.raw);
                        if (r.keep) keep.add(t);
                    }
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
                if (c != null) walk(c, root, rules, heuristics, targets, keep);
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
        return visibility == View.GONE ? View.GONE : s.mode;
    }

    private static void hide(View v, boolean keepSpace) {
        int mode = keepSpace ? View.INVISIBLE : View.GONE;
        Saved s = hidden.get(v);
        if (s == null) {
            s = new Saved();
            s.visibility = v.getVisibility();
            s.mode = mode;
            hidden.put(v, s);
            v.setVisibility(mode); // пройдёт через хук и останется скрытым
            scanChanged = true;
        } else {
            if (s.mode != mode) {
                s.mode = mode;
                if (keepSpace) restoreLp(v, s);
            }
            if (v.getVisibility() == View.VISIBLE) {
                // ART может встроить setVisibility в код приложения, и тогда хук его не видит:
                // приложение снова показало элемент — запоминаем и прячем заново.
                s.visibility = View.VISIBLE;
                v.setVisibility(mode);
                scanChanged = true;
            }
        }
        if (keepSpace) return;
        // RecyclerView и похожие контейнеры не убирают место под GONE-элементом,
        // поэтому обнуляем размер вдоль направления прокрутки (поперёк — не трогаем,
        // иначе соседние элементы сетки растягиваются).
        ViewParent p = v.getParent();
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (p instanceof View && lp != null && isScrollContainer((View) p) && !(p instanceof ScrollView)
                && !(p instanceof HorizontalScrollView)) {
            View pv = (View) p;
            boolean horizontal = pv.canScrollHorizontally(1) || pv.canScrollHorizontally(-1)
                    || p instanceof HorizontalScrollView;
            boolean zeroed = horizontal ? lp.width == 0 : lp.height == 0;
            if (!s.lpZeroed || !zeroed) {
                s.width = lp.width;
                s.height = lp.height;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    s.ml = m.leftMargin;
                    s.mt = m.topMargin;
                    s.mr = m.rightMargin;
                    s.mb = m.bottomMargin;
                    if (horizontal) m.setMargins(0, m.topMargin, 0, m.bottomMargin);
                    else m.setMargins(m.leftMargin, 0, m.rightMargin, 0);
                }
                if (horizontal) lp.width = 0;
                else lp.height = 0;
                s.lpZeroed = true;
                v.setLayoutParams(lp);
                scanChanged = true;
            }
        }
    }

    private static void restoreLp(View v, Saved s) {
        if (!s.lpZeroed) return;
        s.lpZeroed = false;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = s.width;
        lp.height = s.height;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(s.ml, s.mt, s.mr, s.mb);
        }
        v.setLayoutParams(lp);
    }

    private static void show(View v) {
        Saved s = hidden.remove(v);
        if (s == null) return;
        restoreLp(v, s);
        v.setVisibility(s.visibility);
        scanChanged = true;
    }
}
