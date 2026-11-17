package app.interline.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A follow-up email draft as the model returns it. Sources are transcript segment ids. */
public record FollowUpDraft(String subject, String body, List<Long> sources) {
    /** @throws InvalidEnhancementException when the text is not the follow-up JSON document */
    public static FollowUpDraft parse(String json) {
        Object root;
        try {
            root = MiniJson.parse(CodeFence.strip(json == null ? "" : json));
        } catch (IllegalArgumentException e) {
            throw new InvalidEnhancementException(e.getMessage());
        }
        if (!(root instanceof Map<?, ?> document)) throw new InvalidEnhancementException("document must be an object");
        if (!(document.get("subject") instanceof String subject) || !(document.get("body") instanceof String body)) {
            throw new InvalidEnhancementException("subject and body must be strings");
        }
        List<Long> sources = new ArrayList<>();
        if (document.get("sources") instanceof List<?> items) {
            for (Object item : items) {
                if (!(item instanceof BigDecimal number) || number.stripTrailingZeros().scale() > 0) {
                    throw new InvalidEnhancementException("sources must be segment ids");
                }
                sources.add(number.longValueExact());
            }
        } else if (document.get("sources") != null) {
            throw new InvalidEnhancementException("sources must be an array");
        }
        return new FollowUpDraft(subject, body, List.copyOf(sources));
    }
}
