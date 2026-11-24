package app.interline.agent;

import app.interline.domain.CalendarSource;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Supplies wire replies to the real parser without a child process. */
public final class CalendarSourceFixture {
    public static CalendarSource reply(ObjectNode events) {
        return new AgentCalendarSource((type, body, timeout) -> "calendar.query".equals(type)
                ? ControlMessage.response("c-1", "calendar.events", events)
                : ControlMessage.response("c-2", "permission.status", ControlMessage.nodes().objectNode().put("calendar", "granted")));
    }
}
