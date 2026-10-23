package com.qmurzik.yago.hook;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Составляет правило для выбранного элемента.
 *
 * У многих элементов (картинки, фоновые подложки) нет своих признаков. Поэтому
 * перебираются «якоря» по соседству: id, описание или текст самого элемента, его
 * потомков или элементов рядом. Правило: найти якорь → подняться на ^N →
 * спуститься по номерам дочерних элементов >i>j к выбранному.
 * Каждый вариант проверяется на текущем экране; годится только тот,
 * который находит ровно выбранный элемент.
 */
final class RuleBuilder {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_UP = 6;
    private static final int MAX_ANCHORS_PER_LEVEL = 12;
    private static final int MAX_CANDIDATES = 48;
    private static final int MAX_TEXT = 40;

    /** Классы общих библиотек: правило по ним задело бы все такие элементы. */
    private static final String[] LIBRARY_PREFIXES = {
            "android.", "androidx.", "com.google.android.", "com.yandex.div.", "kotlin.", "com.facebook."
    };

    static final class Candidate {
        final String rule;
        final int matches;
        final int priority;
        final int distance;

        Candidate(String rule, int matches, int priority, int distance) {
            this.rule = rule;
            this.matches = matches;
            this.priority = priority;
            this.distance = distance;
        }

        boolean exact() {
            return matches == 1;
        }

        String explain() {
            return exact() ? "скроется только этот элемент" : "⚠ заденет элементов на экране: " + matches;
        }
    }

    private static final class Anchor {
        final String rule; // без ^N/пути
        final int priority;

        Anchor(String rule, int priority) {
            this.rule = rule;
            this.priority = priority;
        }
    }

    private RuleBuilder() {}

    /** Кандидаты от лучшего к худшему; точные (только этот элемент) идут первыми. */
    static List<Candidate> build(View root, View target) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Поднимаемся от выбранного элемента: на каждом уровне ищем якоря внутри предка
        // и строим путь вниз от предка к выбранному элементу.
        View ancestor = target;
        String down = "";
        for (int k = 0; k <= MAX_UP && ancestor != null && ancestor != root; k++) {
            int anchors = 0;
            ArrayDeque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[]{ancestor, 0});
            while (!queue.isEmpty() && anchors < MAX_ANCHORS_PER_LEVEL && out.size() < MAX_CANDIDATES) {
                Object[] item = queue.poll();
                View v = (View) item[0];
                int depth = (Integer) item[1];
                if (v.getVisibility() == View.VISIBLE) {
                    for (Anchor a : anchorsOf(v)) {
                        anchors++;
                        String rule = a.rule + (depth > 0 ? " ^" + depth : "") + (down.isEmpty() ? "" : " " + down);
                        if (!seen.add(rule)) continue;
                        Candidate c = check(root, target, rule, a.priority, k * 10 + depth);
                        if (c != null) out.add(c);
                    }
                }
                if (v instanceof ViewGroup && depth < MAX_DEPTH) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View ch = g.getChildAt(i);
                        if (ch != null) queue.add(new Object[]{ch, depth + 1});
                    }
                }
            }
            // точный вариант на ближнем уровне найден — дальше подниматься незачем
            boolean exact = false;
            for (Candidate c : out) if (c.exact()) exact = true;
            if (exact && k >= 1) break;

            ViewParent p = ancestor.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup pg = (ViewGroup) p;
            down = ">" + pg.indexOfChild(ancestor) + down;
            ancestor = pg;
        }

        // Класс самого элемента — только если это не общий класс библиотеки
        String cls = target.getClass().getName();
        if (!isLibraryClass(cls)) {
            Candidate c = check(root, target, "class:^" + Rule.escape(cls) + "$", 90, 0);
            if (c != null) out.add(c);
        }

        Collections.sort(out, (a, b) -> {
            if (a.exact() != b.exact()) return a.exact() ? -1 : 1;
            if (!a.exact() && a.matches != b.matches) return Integer.compare(a.matches, b.matches);
            if (a.distance / 10 != b.distance / 10) return Integer.compare(a.distance / 10, b.distance / 10);
            if (a.priority != b.priority) return Integer.compare(a.priority, b.priority);
            return Integer.compare(a.distance, b.distance);
        });
        return out;
    }

    private static Candidate check(View root, View target, String rule, int priority, int distance) {
        Rule r = Rule.parse(rule);
        if (r == null) return null;
        Set<View> hit = ViewHider.evaluate(root, r);
        if (!hit.contains(target)) return null;
        return new Candidate(rule, hit.size(), priority, distance);
    }

    private static boolean isLibraryClass(String cls) {
        for (String p : LIBRARY_PREFIXES) if (cls.startsWith(p)) return true;
        return false;
    }

    private static List<Anchor> anchorsOf(View v) {
        List<Anchor> out = new ArrayList<>(3);
        String id = ViewHider.idName(v);
        if (id != null && id.length() > 2) out.add(new Anchor("id:^" + Rule.escape(id) + "$", 10));
        CharSequence d = v.getContentDescription();
        if (d != null) {
            String p = textPattern(d.toString());
            if (p != null) out.add(new Anchor("desc:" + p, hasDigits(d) ? 35 : 20));
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String p = textPattern(t.toString());
                if (p != null) out.add(new Anchor("text:" + p, hasDigits(t) ? 40 : 30));
                // «Едет в г. Ефремов» → «^Едет в»: переживает смену города/адреса
                String prefix = prefixPattern(t.toString());
                if (prefix != null) out.add(new Anchor("text:" + prefix, 50));
            }
        }
        return out;
    }

    /** Литерал для правила; числа заменяются на \d+, чтобы правило пережило смену суммы/времени. */
    static String textPattern(String s) {
        String t = s.replaceAll("[\\s\u00a0\u202f]+", " ").trim();
        if (t.isEmpty()) return null;
        boolean cut = t.length() > MAX_TEXT;
        if (cut) t = t.substring(0, MAX_TEXT).trim();
        String esc = Rule.escape(t).replaceAll("[0-9]+( [0-9]+)*", "\\\\d[\\\\d\\\\s]*");
        esc = esc.replace(" ", "\\s+");
        return "^\\s*" + esc + (cut ? "" : "\\s*$");
    }

    /** Первые два слова текста, если слов больше двух и в них нет цифр. */
    static String prefixPattern(String s) {
        String[] words = s.replaceAll("[\\s\u00a0\u202f]+", " ").trim().split(" ");
        if (words.length < 3) return null;
        String head = words[0] + " " + words[1];
        if (head.length() < 4 || hasDigits(head)) return null;
        return "^\\s*" + Rule.escape(head).replace(" ", "\\s+") + "\\b";
    }

    private static boolean hasDigits(CharSequence s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    /** Короткое человекочитаемое название элемента: его текст или тексты внутри. */
    static String label(View v) {
        List<String> parts = new ArrayList<>();
        collectTexts(v, parts, 0);
        String s = String.join(" · ", parts);
        if (s.isEmpty()) {
            s = v.getClass().getSimpleName();
            String id = ViewHider.idName(v);
            if (id != null) s += " #" + id;
            // картинка/подложка без текста — подписываем по ближайшему тексту рядом
            View a = v;
            for (int i = 0; i < 3 && parts.isEmpty(); i++) {
                ViewParent p = a.getParent();
                if (!(p instanceof View)) break;
                a = (View) p;
                collectTexts(a, parts, 0);
            }
            if (!parts.isEmpty()) s += " в «" + parts.get(0) + "»";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static void collectTexts(View v, List<String> out, int depth) {
        if (out.size() >= 3 || depth > MAX_DEPTH || v.getVisibility() != View.VISIBLE) return;
        CharSequence t = v instanceof TextView ? ((TextView) v).getText() : null;
        if (t == null || t.toString().trim().isEmpty()) t = v.getContentDescription();
        if (t != null && !t.toString().trim().isEmpty()) {
            out.add(t.toString().trim().replaceAll("\\s+", " "));
            if (v instanceof TextView) return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && out.size() < 3; i++) collectTexts(g.getChildAt(i), out, depth + 1);
        }
    }
}
