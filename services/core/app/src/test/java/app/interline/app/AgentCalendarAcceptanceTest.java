package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Uses the real MockAgent and core with no ICS URL, fake EventKit data and no permission prompts. */
@Timeout(60)
class AgentCalendarAcceptanceTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;

    @AfterEach void stop() {
        if (context != null) context.close();
    }

    @Test void startupWithoutIcsImportsAgentEventsAndPreservesTheirPublicFields() throws Exception {
        start(calendarScript(List.of(event("a", "Planning")), true), false);
        awaitEvents("Planning");
        JsonNode event = events(port).json().get(0);
        assertThat(event.path("source").asText()).isEqualTo("agent");
        assertThat(event.path("external_id").asText()).isEqualTo("a");
        assertThat(event.path("starts_at").asLong()).isEqualTo(NOW + HOUR);
        assertThat(event.path("ends_at").asLong()).isEqualTo(NOW + 2 * HOUR);
        assertThat(event.path("conference_url").asText()).isEqualTo("https://meet.example.com/a");
        assertThat(event.path("attendees").get(0).path("email").asText()).isEqualTo("alice@example.com");
        assertThat(countEvents(data, "agent")).isEqualTo(1);
        assertThat(call(port, "GET", "/calendar/ics-source", null).json().path("configured").asBoolean()).isFalse();
    }

    @Test void changedRefreshRemovesMissingAgentEventsAndKeepsManualEvents() throws Exception {
        ObjectNode script = calendarScript(List.of(event("a", "Planning"), event("b", "Removed")), true);
        // Timed changes are relative to the first calendar.query, so boot speed cannot consume the initial snapshot.
        ObjectNode change = script.withObject("calendar").putArray("changes").addObject().put("after_ms", 3000);
        change.putArray("events").add(event("a", "Renamed"));
        change.put("complete", true);
        start(script, false);
        awaitEvents("Planning", "Removed");
        String stableId = events(port).json().findParents("external_id").stream()
            .filter(e -> e.path("external_id").asText().equals("a")).findFirst().orElseThrow().path("id").asText();
        insertManualEvent(data, "manual", "Typed by hand", NOW + 3 * HOUR, NOW + 4 * HOUR);
        // No POST /sync here: only calendar.changed can explain this refresh before the five-minute poll.
        awaitEvents("Renamed", "Typed by hand");
        assertThat(events(port).json().get(0).path("id").asText()).isEqualTo(stableId);
        assertThat(countEvents(data, "agent")).isEqualTo(1);
        assertThat(countEvents(data, "manual")).isEqualTo(1);
    }

    @Test void partialAgentSnapshotUpdatesGoodEventsWithoutDeletingMissingOnes() throws Exception {
        start(calendarScript(List.of(event("a", "Planning"), event("b", "Keep me")), true), false);
        awaitEvents("Planning", "Keep me");
        writeScript(calendarScript(List.of(event("a", "Updated")), false));
        JsonNode result = ok(sync(port), "partial agent sync").json();
        assertThat(result.path("complete").asBoolean(true)).isFalse();
        assertThat(result.path("removed").asInt(-1)).isZero();
        awaitEvents("Updated", "Keep me");
    }

    @Test void calendarDenialEmitsSseAndLeavesManualMeetingsUsable() throws Exception {
        start(calendarScript(List.of(), true), true);
        JsonNode meeting = ok(call(port, "POST", "/meetings", Map.of("title", "Manual still works")), "manual meeting").json();
        assertThat(meeting.path("id").asText()).isNotBlank();
        assertThat(call(port, "GET", "/meetings", null).body()).contains("Manual still works");
        assertThat(ok(events(port), "denied events").json()).isEmpty();
        try (AppTestSupport.Events sse = new AppTestSupport.Events(port)) {
            ok(sync(port), "denied calendar sync");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(sse.received.stream().map(AppTestSupport.Event::name).toList()).contains("calendar.denied"));
        }
    }

    @Test void configuredIcsTakesPrecedenceOverAdvertisedAgentCalendar() throws Exception {
        start(calendarScript(List.of(event("a", "Agent planning")), true), false);
        awaitEvents("Agent planning");
        try (IcsStub ics = new IcsStub()) {
            ics.serve(calendar(vevent("ics-a", "ICS planning", NOW + HOUR, NOW + 2 * HOUR)));
            assertThat(putSource(port, ics.url()).status()).isEqualTo(204);
            ok(sync(port), "ICS sync");
            assertThat(ics.hits.get()).isPositive();
            assertThat(events(port).json()).anySatisfy(e -> {
                assertThat(e.path("title").asText()).isEqualTo("ICS planning");
                assertThat(e.path("source").asText()).isEqualTo("ics");
            });
        }
    }

    private void start(ObjectNode script, boolean deny) throws Exception {
        writeScript(script);
        context = M3Support.boot(data, "--interline.agent-args[2]=--script=" + data.resolve("calendar.json"),
            "--interline.agent-args[3]=" + (deny ? "--deny=calendar" : "--speed=20"));
        port = port(context);
    }

    private void writeScript(ObjectNode script) throws Exception {
        // Atomic replacement prevents the child from observing a truncated test fixture.
        Path pending = data.resolve("calendar.next.json");
        Files.writeString(pending, JSON.writeValueAsString(script));
        Files.move(pending, data.resolve("calendar.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void awaitEvents(String... titles) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode result = ok(events(port), "calendar events").json();
            assertThat(result.findValuesAsText("title")).containsExactlyInAnyOrder(titles);
        });
    }

    private static ObjectNode calendarScript(List<ObjectNode> events, boolean complete) {
        ObjectNode script = JSON.createObjectNode().put("sample_rate", 16000).put("samples", 0);
        script.putArray("segments");
        ObjectNode calendar = script.putObject("calendar").put("complete", complete);
        calendar.set("events", JSON.valueToTree(events));
        return script;
    }

    private static ObjectNode event(String id, String title) {
        ObjectNode event = JSON.createObjectNode().put("external_id", id).put("title", title)
            .put("starts_at", NOW + HOUR).put("ends_at", NOW + 2 * HOUR)
            .put("conference_url", "https://meet.example.com/a").put("calendar_name", "Work");
        event.putArray("attendees").addObject().put("name", "Alice").put("email", "alice@example.com");
        return event;
    }
}
