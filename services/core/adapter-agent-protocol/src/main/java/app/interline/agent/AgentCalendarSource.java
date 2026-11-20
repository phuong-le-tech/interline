package app.interline.agent;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarDeniedException;
import app.interline.domain.CalendarEvent;
import app.interline.domain.CalendarSnapshot;
import app.interline.domain.CalendarSource;
import app.interline.domain.CalendarUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Calendar snapshots over correlated agent requests; malformed replies never become destructive snapshots. */
public final class AgentCalendarSource implements CalendarSource {
    private final AgentSecretStore.Exchange exchange;

    AgentCalendarSource(AgentSecretStore.Exchange exchange) { this.exchange = exchange; }

    @Override public CalendarSnapshot fetch(long from, long to) {
        try {
            var query = ControlMessage.nodes().objectNode().put("from_ms", from).put("to_ms", to);
            ControlMessage reply = exchange.call("calendar.query", query, Duration.ofMinutes(2));
            var permissions = ControlMessage.nodes().objectNode();
            permissions.putArray("permissions").add("calendar");
            ControlMessage status = exchange.call("permission.status", permissions, Duration.ofSeconds(5));
            if (!"permission.status".equals(status.type())) throw invalid("permission status");
            String state = status.body().path("calendar").asText();
            if ("denied".equals(state) || "restricted".equals(state)) throw new CalendarDeniedException();
            if (!"granted".equals(state)) throw invalid("permission state");
            if (!"calendar.events".equals(reply.type()) || !reply.body().path("events").isArray()
                    || (reply.body().has("complete") && !reply.body().path("complete").isBoolean())) throw invalid("events envelope");
            JsonNode events = reply.body().path("events");
            if (events.size() > 5000) throw invalid("event count");
            List<CalendarEvent> result = new ArrayList<>();
            for (JsonNode event : events) {
                for (String field : List.of("external_id", "title", "calendar_name")) {
                    if (!event.path(field).isTextual()) throw invalid("event text fields");
                }
                if (event.path("external_id").asText().isBlank() || !event.path("starts_at").isIntegralNumber()
                        || !event.path("starts_at").canConvertToLong() || !event.path("ends_at").isIntegralNumber()
                        || !event.path("ends_at").canConvertToLong() || !event.path("attendees").isArray()
                        || !event.has("conference_url") || !(event.path("conference_url").isNull()
                        || event.path("conference_url").isTextual())) throw invalid("event fields");
                long start = event.path("starts_at").longValue(), end = event.path("ends_at").longValue();
                if (end < start) throw invalid("event duration");
                List<Attendee> attendees = new ArrayList<>();
                for (JsonNode attendee : event.path("attendees")) {
                    if (!attendee.path("name").isTextual() || !attendee.path("email").isTextual()) throw invalid("attendee fields");
                    attendees.add(new Attendee(attendee.path("name").textValue(), attendee.path("email").textValue()));
                }
                if (start < to && (end > from || start == end && start >= from)) {
                    result.add(new CalendarEvent(event.path("external_id").textValue(), event.path("title").textValue(),
                            start, end, event.path("conference_url").textValue(), attendees));
                }
            }
            return new CalendarSnapshot(result, List.of(), 0, reply.body().path("complete").asBoolean(true));
        } catch (IllegalStateException e) {
            throw new CalendarUnavailableException("The capture agent could not read the calendar");
        }
    }

    private static CalendarUnavailableException invalid(String field) {
        return new CalendarUnavailableException("The capture agent returned an invalid calendar snapshot (" + field + ")");
    }
}
