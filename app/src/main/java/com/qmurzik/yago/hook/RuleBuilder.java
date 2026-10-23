package com.qmurzik.yago.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Составляет правило для выбранного элемента.
 * Перебирает «якоря» — id, описание, текст самого элемента или его потомков —
 * и проверяет каждое правило на текущем экране: лучшим считается то,
 * которое скрывает ровно выбранный элемент и опирается на самый стабильный признак.
 */
final class RuleBuilder {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ANCHORS = 16;
    private static final int MAX_TEXT = 40;

    static final class Candidate {
        final String rule;
        final int matches;
        final int priority;
        final int depth;

        Candidate(String rule, int matches, int priority, int depth) {
            this.rule = rule;
            this.matches = matches;
            this.priority = priority;
            this.depth = depth;
        }

        String explain() {
            return matches == 1 ? "скроется только этот элемент" : "скроется элементов на экране: " + matches;
        }
    }

    private RuleBuilder() {}

    /** Кандидаты от лучшего к худшему. Пустой список — правило составить не удалось. */
    static List<Candidate> build(View root, View target) {
        Set<String> rules = new LinkedHashSet<>();
        List<int[]> meta = new ArrayList<>(); // {priority, depth} в том же порядке
        addAnchors(target, 0, rules, meta);

        if (target instanceof ViewGroup) {
            ArrayDeque<Object[]> queue = new ArrayDeque<>();
            enqueueChildren((ViewGroup) target, 1, queue);
            while (!queue.isEmpty() && rules.size() < MAX_ANCHORS) {
                Object[] item = queue.poll();
                View v = (View) item[0];
                int depth = (Integer) item[1];
                if (depth > MAX_DEPTH) continue;
                addAnchors(v, depth, rules, meta);
                if (v instanceof ViewGroup) enqueueChildren((ViewGroup) v, depth + 1, queue);
            }
        }

        String cls = target.getClass().getName();
        if (!cls.startsWith("android.") && !cls.startsWith("androidx.") && !cls.startsWith("com.google.android.material.")) {
            if (rules.add("class:^" + Rule.escape(cls) + "$")) meta.add(new int[]{90, 0});
        }

        List<Candidate> out = new ArrayList<>();
        int i = 0;
        for (String r : rules) {
            int[] m = meta.get(i++);
            Rule rule = Rule.parse(r);
            if (rule == null) continue;
            Set<View> hit = ViewHider.evaluate(root, rule);
            if (!hit.contains(target)) continue;
            out.add(new Candidate(r, hit.size(), m[0], m[1]));
        }
        Collections.sort(out, (a, b) -> {
            boolean ua = a.matches == 1, ub = b.matches == 1;
            if (ua != ub) return ua ? -1 : 1;
            if (a.priority != b.priority) return Integer.compare(a.priority, b.priority);
            if (a.depth != b.depth) return Integer.compare(a.depth, b.depth);
            return Integer.compare(a.matches, b.matches);
        });
        return out;
    }

    private static void enqueueChildren(ViewGroup g, int depth, ArrayDeque<Object[]> queue) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c != null) queue.add(new Object[]{c, depth});
        }
    }

    private static void addAnchors(View v, int depth, Set<String> rules, List<int[]> meta) {
        String up = depth > 0 ? " ^" + depth : "";
        String id = ViewHider.idName(v);
        if (id != null && id.length() > 2) {
            if (rules.add("id:^" + Rule.escape(id) + "$" + up)) meta.add(new int[]{10, depth});
        }
        CharSequence d = v.getContentDescription();
        if (d != null) {
            String p = textPattern(d.toString());
            if (p != null && rules.add("desc:" + p + up)) meta.add(new int[]{hasDigits(d) ? 35 : 20, depth});
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String p = textPattern(t.toString());
                if (p != null && rules.add("text:" + p + up)) meta.add(new int[]{hasDigits(t) ? 40 : 30, depth});
                // «Едет в г. Ефремов» → «^Едет в»: переживает смену города/адреса
                String prefix = prefixPattern(t.toString());
                if (prefix != null && rules.add("text:" + prefix + up)) meta.add(new int[]{50, depth});
            }
        }
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
