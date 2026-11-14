package app.interline.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Agent Protocol control envelope; id and replyTo are absent on events. */
public record ControlMessage(int version, String type, String id, String replyTo,
                             long timestamp, JsonNode body) implements Inbound {
    public static final int ENVELOPE_VERSION = 1;

    public static ControlMessage event(String type, JsonNode body) {
        return new ControlMessage(ENVELOPE_VERSION, type, null, null, System.currentTimeMillis(), body);
    }

    public static ControlMessage request(String id, String type, JsonNode body) {
        return new ControlMessage(ENVELOPE_VERSION, type, id, null, System.currentTimeMillis(), body);
    }

    public static ControlMessage response(String replyTo, String type, JsonNode body) {
        return new ControlMessage(ENVELOPE_VERSION, type, null, replyTo, System.currentTimeMillis(), body);
    }

    public static JsonNodeFactory nodes() { return JsonNodeFactory.instance; }
}
