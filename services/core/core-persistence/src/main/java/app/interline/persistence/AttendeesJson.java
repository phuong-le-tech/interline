package app.interline.persistence;

import app.interline.domain.Attendee;

import java.util.ArrayList;
import java.util.List;

/** The {@code attendees} column: a JSON array of {@code {"name":...,"email":...}} with null for a missing value. */
final class AttendeesJson {
    private AttendeesJson() { }

    static String encode(List<Attendee> attendees) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < attendees.size(); i++) {
            if (i > 0) out.append(',');
            out.append("{\"name\":");
            string(out, attendees.get(i).name());
            out.append(",\"email\":");
            string(out, attendees.get(i).email());
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static void string(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** Lenient on purpose: an unreadable column yields an empty list rather than failing a listing. */
    static List<Attendee> decode(String json) {
        List<Attendee> result = new ArrayList<>();
        if (json == null) return result;
        Reader in = new Reader(json);
        try {
            in.expect('[');
            in.skipSpace();
            if (in.peek() == ']') return result;
            do {
                result.add(in.attendee());
                in.skipSpace();
            } while (in.accept(','));
            in.expect(']');
            return result;
        } catch (IllegalStateException e) {
            return new ArrayList<>();
        }
    }

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) { this.text = text; }

        char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

        void skipSpace() { while (Character.isWhitespace(peek())) pos++; }

        boolean accept(char c) {
            skipSpace();
            if (peek() != c) return false;
            pos++;
            return true;
        }

        void expect(char c) {
            if (!accept(c)) throw new IllegalStateException("expected " + c);
        }

        Attendee attendee() {
            expect('{');
            String name = null;
            String email = null;
            skipSpace();
            if (peek() != '}') {
                do {
                    String key = value();
                    expect(':');
                    String value = value();
                    if ("name".equals(key)) name = value;
                    else if ("email".equals(key)) email = value;
                } while (accept(','));
            }
            expect('}');
            return new Attendee(name, email);
        }

        /** A JSON string or the literal null. */
        String value() {
            skipSpace();
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw new IllegalStateException("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) throw new IllegalStateException("bad escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw new IllegalStateException("bad escape");
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(e);
                }
            }
        }
    }
}
