package app.interline.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A versioned prompt file from the classpath with {{name}} placeholders. Rendering is a single pass over the
 * template: substituted values (user notes, transcript text) are never scanned for placeholders again.
 */
final class PromptTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private final String text;

    private PromptTemplate(String text) { this.text = text; }

    static PromptTemplate load(String resourcePath) {
        try (InputStream in = PromptTemplate.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("missing prompt resource " + resourcePath);
            return new PromptTemplate(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String render(Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
