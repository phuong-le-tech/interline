package app.interline.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** Agent Protocol control envelope; id and replyTo are absent on events. */
public record ControlMessage(int version, String type, String id, String replyTo,
                             long timestamp, JsonNode body) { }
