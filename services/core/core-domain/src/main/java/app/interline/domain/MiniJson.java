package app.interline.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader for the domain, so the contract stays free of any library. Objects become
 * {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code BigDecimal}, plus String, Boolean and null.
 */
final class MiniJson {
    private static final int MAX_DEPTH = 64;
    private final String text;
    private int position;

    private MiniJson(String text) { this.text = text; }

    /** @throws IllegalArgumentException when the text is not exactly one JSON value */
    static Object parse(String text) {
        MiniJson reader = new MiniJson(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.position != text.length()) throw reader.fail("trailing content");
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw fail("nesting too deep");
        if (position >= text.length()) throw fail("unexpected end");
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        position++;
        skipSpace();
        if (peek('}')) { position++; return members; }
        while (true) {
            skipSpace();
            if (!peek('"')) throw fail("object key expected");
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            members.put(key, value(depth + 1));
            skipSpace();
            if (peek(',')) { position++; continue; }
            expect('}');
            return members;
        }
    }

    private List<Object> array(int depth) {
        List<Object> items = new ArrayList<>();
        position++;
        skipSpace();
        if (peek(']')) { position++; return items; }
        while (true) {
            skipSpace();
            items.add(value(depth + 1));
            skipSpace();
            if (peek(',')) { position++; continue; }
            expect(']');
            return items;
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        position++;
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw fail("control character in string");
            if (c != '\\') { out.append(c); continue; }
            if (position >= text.length()) break;
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicodeEscape());
                default -> throw fail("bad escape");
            }
        }
        throw fail("unterminated string");
    }

    private char unicodeEscape() {
        if (position + 4 > text.length()) throw fail("bad unicode escape");
        try {
            char c = (char) Integer.parseInt(text.substring(position, position + 4), 16);
            position += 4;
            return c;
        } catch (NumberFormatException e) {
            throw fail("bad unicode escape");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) throw fail("unexpected token");
        position += word.length();
        return value;
    }

    private BigDecimal number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
        try {
            return new BigDecimal(text.substring(start, position));
        } catch (NumberFormatException e) {
            throw fail("unexpected token");
        }
    }

    private void skipSpace() {
        while (position < text.length() && " \t\n\r".indexOf(text.charAt(position)) >= 0) position++;
    }

    private boolean peek(char expected) { return position < text.length() && text.charAt(position) == expected; }

    private void expect(char expected) {
        if (!peek(expected)) throw fail("'" + expected + "' expected");
        position++;
    }

    private IllegalArgumentException fail(String reason) {
        return new IllegalArgumentException("invalid JSON at " + position + ": " + reason);
    }
}
