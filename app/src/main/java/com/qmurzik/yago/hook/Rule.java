package com.qmurzik.yago.hook;

import com.qmurzik.yago.RuleEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Правило скрытия элемента.
 *
 * <pre>
 * тип:регулярное_выражение [^N]
 *
 * id:^promo_banner$      имя ресурса id элемента
 * text:^Реклама$ ^3      текст TextView, скрыть 3-го родителя найденного элемента
 * desc:Истории           contentDescription
 * class:StoriesView$     полное имя класса элемента
 * # комментарий
 * </pre>
 *
 * Выражение ищется в строке (find), без учёта регистра.
 */
final class Rule {
    enum Type { ID, TEXT, DESC, CLASS }

    private static final Pattern LINE = RuleEntry.LINE;

    final Type type;
    final Pattern pattern;
    final int up;
    final String raw;

    private Rule(Type type, Pattern pattern, int up, String raw) {
        this.type = type;
        this.pattern = pattern;
        this.up = up;
        this.raw = raw;
    }

    static Rule parse(String line) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return null;
        Matcher m = LINE.matcher(s);
        if (!m.matches()) {
            Log.w("Не понял правило: " + s);
            return null;
        }
        Type type = Type.valueOf(m.group(1).toUpperCase(java.util.Locale.ROOT));
        Pattern p;
        try {
            // \s, \d и \b в Android (ICU) и так понимают Unicode; UNICODE_CHARACTER_CLASS там не поддерживается
            p = Pattern.compile(m.group(2), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (IllegalArgumentException e) {
            Log.w("Ошибка в выражении правила " + s + ": " + e.getMessage());
            return null;
        }
        int up = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new Rule(type, p, up, s);
    }

    static List<Rule> parseAll(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<Rule> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Rule r = parse(line);
            if (r != null) out.add(r);
        }
        return out;
    }

    boolean matches(String value) {
        return value != null && !value.isEmpty() && pattern.matcher(value).find();
    }

    /** Экранирует строку для использования в правиле как литерал. */
    static String escape(String s) {
        return RuleEntry.escape(s);
    }
}
