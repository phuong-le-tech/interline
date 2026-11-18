package app.interline.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses model output into an {@link EnhancementOutput}; no repair, no invented members. */
public final class EnhancementParser {
    private EnhancementParser() { }

    /** @throws InvalidEnhancementException when the text is not the contract's JSON document */
    public static EnhancementOutput parse(String json) {
        Object root;
        try {
            root = MiniJson.parse(CodeFence.strip(json == null ? "" : json));
        } catch (IllegalArgumentException e) {
            throw new InvalidEnhancementException(e.getMessage());
        }
        Map<String, Object> document = object(root, "document");
        List<EnhancementOutput.BlockAdditions> blocks = new ArrayList<>();
        for (Object item : array(document.get("blocks"), "blocks", true)) blocks.add(block(object(item, "blocks[]")));
        List<EnhancementOutput.OtherPoint> otherPoints = new ArrayList<>();
        for (Object item : array(document.get("other_points"), "other_points", false)) {
            Map<String, Object> point = object(item, "other_points[]");
            otherPoints.add(new EnhancementOutput.OtherPoint(text(point, "text"), sources(point)));
        }
        List<EnhancementOutput.ActionItem> actions = new ArrayList<>();
        for (Object item : array(document.get("action_items"), "action_items", false)) {
            Map<String, Object> action = object(item, "action_items[]");
            actions.add(new EnhancementOutput.ActionItem(text(action, "text"), optionalText(action, "owner"),
                    optionalText(action, "due"), sources(action)));
        }
        return new EnhancementOutput(blocks, otherPoints, actions, optionalText(document, "title_suggestion"));
    }

    private static EnhancementOutput.BlockAdditions block(Map<String, Object> block) {
        List<EnhancementOutput.Addition> additions = new ArrayList<>();
        for (Object item : array(block.get("additions"), "additions", true)) {
            Map<String, Object> addition = object(item, "additions[]");
            additions.add(new EnhancementOutput.Addition(text(addition, "text"), sources(addition)));
        }
        return new EnhancementOutput.BlockAdditions(text(block, "user_block_id"), additions);
    }

    private static List<Long> sources(Map<String, Object> owner) {
        List<Long> ids = new ArrayList<>();
        for (Object item : array(owner.get("sources"), "sources", false)) {
            if (!(item instanceof BigDecimal number) || number.stripTrailingZeros().scale() > 0) {
                throw new InvalidEnhancementException("sources must be segment ids");
            }
            ids.add(number.longValueExact());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String where) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new InvalidEnhancementException(where + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String where, boolean required) {
        if (value == null) {
            if (required) throw new InvalidEnhancementException(where + " is required");
            return List.of();
        }
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new InvalidEnhancementException(where + " must be an array");
    }

    private static String text(Map<String, Object> owner, String key) {
        if (owner.get(key) instanceof String value) return value;
        throw new InvalidEnhancementException(key + " must be a string");
    }

    private static String optionalText(Map<String, Object> owner, String key) {
        Object value = owner.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new InvalidEnhancementException(key + " must be a string");
    }
}
