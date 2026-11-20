package app.interline.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("repo.root"));

    static Stream<ObjectNode> messages() throws Exception {
        ObjectNode hello = (ObjectNode) JSON.readTree(Files.readAllLines(ROOT.resolve("protocol/agent/v1/golden/m0.ndjson")).getFirst());
        ((ObjectNode) hello.path("body").path("capabilities")).put("calendar", true);
        return Stream.of(hello,
            envelope("calendar.query", "{\"from_ms\":1000,\"to_ms\":2000}").put("id", "c-calendar"),
            envelope("calendar.events", "{\"events\":[{\"external_id\":\"series/1000\",\"title\":\"Planning\",\"starts_at\":1000,\"ends_at\":2000,\"attendees\":[{\"name\":\"Alice\",\"email\":\"alice@example.com\"}],\"conference_url\":\"https://meet.example.com/a\",\"calendar_name\":\"Work\"}],\"complete\":true}").put("re", "c-calendar"),
            envelope("calendar.events", "{\"events\":[],\"complete\":true}").put("re", "c-empty"),
            envelope("calendar.changed", "{}"),
            envelope("permission.status", "{\"permissions\":[\"calendar\"]}").put("id", "c-status"),
            envelope("permission.status", "{\"calendar\":\"denied\"}").put("re", "c-status"),
            envelope("permission.denied", "{\"permission\":\"calendar\",\"detection\":\"tcc_denied\",\"settings_uri\":\"x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars\"}"));
    }

    @ParameterizedTest @MethodSource("messages")
    void calendarMessagesValidateAndRoundTrip(ObjectNode message) throws Exception {
        assertThat(schema().validate(message)).as("schema: %s", message).isEmpty();
        ControlMessage decoded = new FrameCodec().decodeControl(JSON.writeValueAsBytes(message));
        assertThat(decoded.type()).isEqualTo(message.path("t").asText());
        assertThat(decoded.body()).isEqualTo(message.path("body"));
        assertThat(decoded.id()).isEqualTo(message.has("id") ? message.path("id").asText() : null);
        assertThat(decoded.replyTo()).isEqualTo(message.has("re") ? message.path("re").asText() : null);
    }

    @ParameterizedTest @MethodSource("messages")
    void everyCalendarMessageRequiresAnObjectBody(ObjectNode message) throws Exception {
        var schema = schema();
        message.remove("body");
        assertThat(schema.validate(message)).isNotEmpty();
        message.put("body", "wrong type");
        assertThat(schema.validate(message)).isNotEmpty();
    }

    @Test void queryRequiresIntegerWindowAndEventsRequireTypedFieldsAndCorrelation() throws Exception {
        var schema = schema();
        ObjectNode query = messages().filter(n -> n.path("t").asText().equals("calendar.query")).findFirst().orElseThrow();
        assertThat(schema.validate(query)).as("valid query before mutations").isEmpty();
        for (String field : List.of("from_ms", "to_ms")) {
            ObjectNode bad = query.deepCopy();
            ((ObjectNode) bad.path("body")).remove(field);
            assertThat(schema.validate(bad)).as("missing %s", field).isNotEmpty();
            ((ObjectNode) bad.path("body")).put(field, "1000");
            assertThat(schema.validate(bad)).as("string %s", field).isNotEmpty();
        }
        ObjectNode events = messages().filter(n -> n.path("t").asText().equals("calendar.events")).findFirst().orElseThrow();
        assertThat(schema.validate(events)).as("valid events before mutations").isEmpty();
        ObjectNode uncorrelated = events.deepCopy();
        uncorrelated.remove("re");
        assertThat(schema.validate(uncorrelated)).as("events must correlate by re").isNotEmpty();
        for (String field : List.of("external_id", "title", "starts_at", "ends_at", "attendees", "conference_url", "calendar_name")) {
            ObjectNode bad = events.deepCopy();
            ObjectNode event = (ObjectNode) bad.path("body").path("events").get(0);
            event.remove(field);
            assertThat(schema.validate(bad)).as("missing event %s", field).isNotEmpty();
            event.put(field, true);
            assertThat(schema.validate(bad)).as("boolean event %s", field).isNotEmpty();
        }
        ObjectNode bad = events.deepCopy();
        ((ObjectNode) bad.path("body")).put("events", "wrong type");
        assertThat(schema.validate(bad)).isNotEmpty();
        for (String field : List.of("name", "email")) {
            bad = events.deepCopy();
            ObjectNode attendee = (ObjectNode) bad.path("body").path("events").get(0).path("attendees").get(0);
            attendee.remove(field);
            assertThat(schema.validate(bad)).as("missing attendee %s", field).isNotEmpty();
            attendee.put(field, 42);
            assertThat(schema.validate(bad)).as("attendee %s must be a string", field).isNotEmpty();
        }
    }

    @Test void calendarEventsMustExplicitlyDeclareCompleteness() throws Exception {
        ObjectNode reply = envelope("calendar.events", "{\"events\":[]}").put("re", "c-empty");
        assertThat(schema().validate(reply)).as("missing complete must not become a destructive snapshot").isNotEmpty();
        ((ObjectNode) reply.path("body")).put("complete", "true");
        assertThat(schema().validate(reply)).isNotEmpty();
    }

    @Test void calendarChangedRequiresAnObjectBody() throws Exception {
        ObjectNode changed = envelope("calendar.changed", "{}");
        assertThat(schema().validate(changed)).isEmpty();
        changed.remove("body");
        assertThat(schema().validate(changed)).isNotEmpty();
        changed.put("body", false);
        assertThat(schema().validate(changed)).isNotEmpty();
    }

    @Test void calendarPermissionStateIsValidated() throws Exception {
        ObjectNode status = envelope("permission.status", "{\"calendar\":42}").put("re", "c-status");
        assertThat(schema().validate(status)).isNotEmpty();
        ((ObjectNode) status.path("body")).put("calendar", "invented");
        assertThat(schema().validate(status)).isNotEmpty();
    }

    @Test void calendarCapabilityMustBeBoolean() throws Exception {
        ObjectNode hello = messages().findFirst().orElseThrow();
        ((ObjectNode) hello.path("body").path("capabilities")).put("calendar", "yes");
        assertThat(schema().validate(hello)).isNotEmpty();
    }

    @Test void earlierGoldenBytesAreFrozen() throws Exception {
        Map<String, String> hashes = Map.of(
            "audio.chunk.bin", "730941448fa993d2c6b946eab04cd09bb4ea162a93db89ab7a5c023b794ddc0a",
            "audio.chunk.hex", "f1c60b923d11e52bc26693ac37765716b83cd2765fd2793436312e282e1bb44f",
            "m0.ndjson", "8aefbffb39f220ac6e65fe142f01329e0cdfa5b20f39d1084ecbc1a50d9dd623",
            "m1/m1.ndjson", "f53713cb529def4dfad1b3e5861210334e54a5c5c61b99b707c464a5ff7b36b8",
            "m2/m2.ndjson", "1c85a6f90d933c36c923e158e5f3e860e01b71f2c62658f1fd8b7dcb232389a1");
        for (var entry : hashes.entrySet()) {
            byte[] bytes = Files.readAllBytes(ROOT.resolve("protocol/agent/v1/golden/" + entry.getKey()));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test void m3GoldenCorpusValidatesAndRoundTrips() throws Exception {
        for (String line : Files.readAllLines(ROOT.resolve("protocol/agent/v1/golden/m3/m3.ndjson"))) {
            ObjectNode message = (ObjectNode) JSON.readTree(line);
            calendarMessagesValidateAndRoundTrip(message);
        }
    }

    private static ObjectNode envelope(String type, String body) throws Exception {
        ObjectNode node = JSON.createObjectNode().put("v", 1).put("t", type).put("ts", 0);
        node.set("body", JSON.readTree(body));
        return node;
    }

    private static JsonSchema schema() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(JSON.readTree(ROOT.resolve("protocol/agent/v1/schema.json").toFile()));
    }
}
