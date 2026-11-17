package app.interline.app;

import java.util.Map;
import java.util.Set;

/** Typed reads of a decoded JSON request body; every violation is a 400 that names the field. */
final class JsonBody {
    private final Map<String, Object> values;

    JsonBody(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    boolean has(String field) { return values.containsKey(field); }

    String requiredText(String field) {
        Object value = values.get(field);
        if (value instanceof String text && !text.isBlank()) return text;
        throw ApiProblems.badRequest(field + " is required");
    }

    /** Present and a string, even an empty one. */
    String text(String field) {
        if (values.get(field) instanceof String text) return text;
        throw ApiProblems.badRequest(field + " must be a string");
    }

    String optionalText(String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw ApiProblems.badRequest(field + " must be a string");
    }

    String requiredChoice(String field, Set<String> allowed) {
        String value = requiredText(field);
        if (!allowed.contains(value)) throw ApiProblems.badRequest(field + " must be one of " + allowed);
        return value;
    }

    long requiredWholeNumber(String field) {
        Long value = optionalWholeNumber(field);
        if (value == null) throw ApiProblems.badRequest(field + " is required");
        return value;
    }

    Long optionalWholeNumber(String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw ApiProblems.badRequest(field + " must be a whole number");
    }

    Double optionalNumber(String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        throw ApiProblems.badRequest(field + " must be a number");
    }

    Boolean optionalFlag(String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag;
        throw ApiProblems.badRequest(field + " must be true or false");
    }
}
