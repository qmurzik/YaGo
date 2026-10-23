package com.qmurzik.yago;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Правило скрытия с подписью и флагом включения. Хранится в настройках как JSON-массив. */
public final class RuleEntry {
    /** Синтаксис строки правила: «тип:выражение [^N]». */
    public static final Pattern LINE = Pattern.compile(
            "^(id|text|desc|class)\\s*:\\s*(.+?)(?:\\s+\\^(\\d{1,2}))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    public String label;
    public String rule;
    public boolean enabled;

    public RuleEntry(String label, String rule, boolean enabled) {
        this.label = label == null ? "" : label;
        this.rule = rule;
        this.enabled = enabled;
    }

    /** Понимает JSON и старый формат «одно правило в строке». */
    public static List<RuleEntry> parseList(String s) {
        List<RuleEntry> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.trim();
        if (t.startsWith("[")) {
            try {
                JSONArray a = new JSONArray(t);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optString("rule").trim().isEmpty()) continue;
                    out.add(new RuleEntry(o.optString("label"), o.optString("rule").trim(), o.optBoolean("enabled", true)));
                }
                return out;
            } catch (JSONException ignored) {
                // упадём в построчный разбор
            }
        }
        return parseLines(t);
    }

    /** Построчный текст: «правило» или «правило  # подпись». Строки-комментарии пропускаются. */
    public static List<RuleEntry> parseLines(String text) {
        List<RuleEntry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String label = "";
            int hash = l.indexOf("  # ");
            if (hash > 0) {
                label = l.substring(hash + 4).trim();
                l = l.substring(0, hash).trim();
            }
            out.add(new RuleEntry(label, l, true));
        }
        return out;
    }

    public static String toLines(List<RuleEntry> list) {
        StringBuilder b = new StringBuilder();
        for (RuleEntry e : list) {
            b.append(e.rule);
            if (!e.label.isEmpty()) b.append("  # ").append(e.label.replace('\n', ' '));
            b.append('\n');
        }
        return b.toString();
    }

    public static String toJson(List<RuleEntry> list) {
        JSONArray a = new JSONArray();
        for (RuleEntry e : list) {
            try {
                a.put(new JSONObject().put("label", e.label).put("rule", e.rule).put("enabled", e.enabled));
            } catch (JSONException ignored) {
            }
        }
        return a.toString();
    }

    /** Разбирает строку правила на {тип, выражение, уровень} или возвращает null. */
    public static String[] split(String rule) {
        Matcher m = LINE.matcher(rule.trim());
        if (!m.matches()) return null;
        return new String[]{m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2), m.group(3) == null ? "0" : m.group(3)};
    }

    public static String compose(String type, String pattern, int up) {
        return type + ":" + pattern + (up > 0 ? " ^" + up : "");
    }

    /** Экранирует строку для использования в правиле как литерал. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                b.append("\\n");
                continue;
            }
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** Человекочитаемое название: подпись или само правило. */
    public String title() {
        return label.isEmpty() ? rule : label;
    }
}
