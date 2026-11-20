package app.interline.agent;

import app.interline.domain.CalendarDeniedException;
import app.interline.domain.CalendarUnavailableException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentCalendarSourceTest {
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

    @Test void unknownPermissionFailsAndDeniedHasItsOwnOutcome() {
        assertThatThrownBy(() -> source(events(), "invented").fetch(0, 3000))
                .isInstanceOf(CalendarUnavailableException.class);
        assertThatThrownBy(() -> source(events(), "denied").fetch(0, 3000))
                .isInstanceOf(CalendarDeniedException.class);
    }

    private static ObjectNode events() {
        var body = ControlMessage.nodes().objectNode();
        body.putArray("events");
        return body;
    }

    private static AgentCalendarSource source(ObjectNode events, String state) {
        return new AgentCalendarSource((type, body, timeout) -> "calendar.query".equals(type)
                ? ControlMessage.response("c-1", "calendar.events", events)
                : ControlMessage.response("c-2", "permission.status", ControlMessage.nodes().objectNode().put("calendar", state)));
    }
}
