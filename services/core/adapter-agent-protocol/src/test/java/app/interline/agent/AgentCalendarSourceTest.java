package app.interline.agent;

import app.interline.domain.CalendarDeniedException;
import app.interline.domain.CalendarUnavailableException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentCalendarSourceTest {
    @Test void validEventsPreserveTheirFields() {
        var body = events();
        var event = body.withArray("events").addObject().put("external_id", "a").put("title", "Planning")
                .put("calendar_name", "Work").put("starts_at", 1795200000000L).put("ends_at", 1795203600000L)
                .put("conference_url", "https://meet.example.com/a");
        event.putArray("attendees").addObject().put("name", "Alice").put("email", "alice@example.com");
        assertThat(source(body, "granted").fetch(1795190000000L, 1795300000000L).events())
                .singleElement().satisfies(value -> assertThat(value.title()).isEqualTo("Planning"));
    }

    @Test void partialSnapshotsNeverBecomeComplete() {
        var source = source(events().put("complete", false), "granted");
        assertThat(source.fetch(0, 3000).complete()).isFalse();
    }

    @Test void invalidEventsFailInsteadOfBecomingAnEmptyDestructiveSnapshot() {
        ObjectNode body = events();
        body.withArray("events").addObject().put("external_id", "missing-fields");
        assertThatThrownBy(() -> source(body, "granted").fetch(0, 3000))
                .isInstanceOf(CalendarUnavailableException.class);
    }

    @Test void missingCompletenessIsRejectedEvenIfCalendarAccessIsDenied() {
        ObjectNode body = events();
        body.remove("complete");
        assertThatThrownBy(() -> source(body, "granted").fetch(0, 3000))
                .isInstanceOf(CalendarUnavailableException.class);
        assertThatThrownBy(() -> source(body, "denied").fetch(0, 3000))
                .isInstanceOf(CalendarUnavailableException.class).isNotInstanceOf(CalendarDeniedException.class);
    }

    @Test void unknownPermissionFailsAndDeniedHasItsOwnOutcome() {
        assertThatThrownBy(() -> source(events(), "invented").fetch(0, 3000))
                .isInstanceOf(CalendarUnavailableException.class);
        assertThatThrownBy(() -> source(events(), "denied").fetch(0, 3000))
                .isInstanceOf(CalendarDeniedException.class);
    }

    private static ObjectNode events() {
        var body = ControlMessage.nodes().objectNode().put("complete", true);
        body.putArray("events");
        return body;
    }

    private static AgentCalendarSource source(ObjectNode events, String state) {
        return new AgentCalendarSource((type, body, timeout) -> "calendar.query".equals(type)
                ? ControlMessage.response("c-1", "calendar.events", events)
                : ControlMessage.response("c-2", "permission.status", ControlMessage.nodes().objectNode().put("calendar", state)));
    }
}
