package app.interline.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated written lines, each grounded in at least one related meeting. */
public record BriefOutput(List<Line> lines) {
    public record Line(String text, List<String> sources) { }

    public static BriefOutput parse(String json, Set<String> relatedIds) {
        Object root;
        try {
            root = MiniJson.parse(CodeFence.strip(json == null ? "" : json));
        } catch (IllegalArgumentException e) {
            throw new InvalidEnhancementException("The reply must be JSON");
        }
        if (!(root instanceof Map<?, ?> doc) || !(doc.get("lines") instanceof List<?> items) || items.size() > 5) {
            throw new InvalidEnhancementException("lines must be an array of at most five lines");
        }
        List<Line> lines = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> line) || !(line.get("text") instanceof String text)
                    || text.isBlank() || text.contains("\n") || text.contains("\r")
                    || !(line.get("sources") instanceof List<?> sources) || sources.isEmpty()) {
                throw new InvalidEnhancementException("Each line needs text and related meeting sources");
            }
            List<String> ids = new ArrayList<>();
            for (Object source : sources) {
                if (!(source instanceof String id) || !relatedIds.contains(id)) {
                    throw new InvalidEnhancementException("Every source must be a related meeting id");
                }
                if (!ids.contains(id)) ids.add(id);
            }
            lines.add(new Line(text, List.copyOf(ids)));
        }
        if (lines.isEmpty() && !relatedIds.isEmpty()) throw new InvalidEnhancementException("Write at least one line");
        return new BriefOutput(List.copyOf(lines));
    }
}
