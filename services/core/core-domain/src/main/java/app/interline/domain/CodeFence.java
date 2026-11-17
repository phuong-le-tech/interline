package app.interline.domain;

/** Some models wrap a JSON document in a markdown fence even when told not to. */
final class CodeFence {
    private static final String FENCE = "```";

    private CodeFence() { }

    static String strip(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith(FENCE) || !trimmed.endsWith(FENCE) || trimmed.length() < 2 * FENCE.length()) return trimmed;
        String inner = trimmed.substring(FENCE.length(), trimmed.length() - FENCE.length());
        int firstLine = inner.indexOf('\n');
        return (firstLine < 0 ? inner : inner.substring(firstLine + 1)).strip();
    }
}
