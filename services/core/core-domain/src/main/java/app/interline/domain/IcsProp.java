package app.interline.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One unfolded content line: NAME;PARAM=v:value. Names and parameter keys are upper-cased, quotes removed. */
record IcsProp(String name, Map<String, String> params, String value) {

    /** Parses a logical line; null when it has no colon or no name. */
    static IcsProp parse(String line) {
        int colon = indexOutsideQuotes(line, ':', 0);
        if (colon <= 0) {
            return null;
        }
        List<String> head = splitOutsideQuotes(line.substring(0, colon));
        String name = head.get(0).trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (String token : head.subList(1, head.size())) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                params.put(token.substring(0, eq).trim().toUpperCase(Locale.ROOT), unquote(token.substring(eq + 1)));
            }
        }
        return new IcsProp(name, params, line.substring(colon + 1));
    }

    String param(String key) {
        return params.get(key);
    }

    /** RFC 5545 TEXT unescaping: \n and \N become a newline, any other escaped character stands for itself. */
    static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 'n' || next == 'N' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private static int indexOutsideQuotes(String s, char target, int from) {
        boolean quoted = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == target && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitOutsideQuotes(String s) {
        List<String> parts = new java.util.ArrayList<>();
        int from = 0;
        for (int i = indexOutsideQuotes(s, ';', 0); i >= 0; i = indexOutsideQuotes(s, ';', from)) {
            parts.add(s.substring(from, i));
            from = i + 1;
        }
        parts.add(s.substring(from));
        return parts;
    }
}
