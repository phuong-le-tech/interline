package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.ok;
import static org.assertj.core.api.Assertions.assertThat;

/** Extra helpers of the M3 slice 2 tests (meetings from events, participants, brief). Builds on M3Support. */
final class M3BriefSupport {
    private M3BriefSupport() {
    }

    static void assertProblem(Resp response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/problem+json");
        if (code != null) assertThat(response.json().path("code").asText()).isEqualTo(code);
    }

    static Map<String, Object> person(String name, String email) {
        Map<String, Object> person = new java.util.LinkedHashMap<>();
        if (name != null) person.put("name", name);
        if (email != null) person.put("email", email);
        return person;
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    static Resp createMeeting(int port, String title, List<?> participants) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        if (participants != null) body.put("participants", participants);
        return call(port, "POST", "/meetings", body);
    }

    static String createMeetingOk(int port, String title, List<?> participants) {
        return ok(createMeeting(port, title, participants), "create meeting").json().path("id").asText();
    }

    static Resp fromEvent(int port, String eventId) {
        return call(port, "POST", "/calendar/events/" + eventId + "/meeting", null);
    }

    static Resp brief(int port, String meetingId) {
        return call(port, "GET", "/meetings/" + meetingId + "/brief", null);
    }

    static JsonNode briefOk(int port, String meetingId) {
        return ok(brief(port, meetingId), "brief").json();
    }

    static Resp participants(int port, String meetingId) {
        return call(port, "GET", "/meetings/" + meetingId + "/participants", null);
    }

    static Resp putParticipants(int port, String meetingId, Object body) {
        return call(port, "PUT", "/meetings/" + meetingId + "/participants", body);
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    // ---- database -----------------------------------------------------------------------------------------------

    static void sql(Path data, String sql, Object... params) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            try (PreparedStatement busy = c.prepareStatement("PRAGMA busy_timeout = 5000")) {
                busy.execute();
            }
            try (PreparedStatement statement = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
                statement.execute();
            }
        } catch (Exception e) {
            throw new AssertionError("sql failed: " + sql, e);
        }
    }

    static List<Map<String, Object>> rows(Path data, String sql, Object... params) {
        try {
            return AppTestSupport.rows(data, sql, params);
        } catch (Exception e) {
            throw new AssertionError("query failed: " + sql, e);
        }
    }

    /** A manual calendar event with a raw attendees JSON text, e.g. [{"name":"A","email":"a@x.com"}]. */
    static void insertEventWithAttendees(Path data, String id, String title, long startsAt, String attendeesJson) {
        sql(data, "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,attendees) VALUES (?,'manual',?,?,?,?,?)",
                id, id, title, startsAt, startsAt + M3Support.HOUR, attendeesJson);
    }

    /** A finished meeting dated {@code createdAt} (no start, no event). */
    static void insertMeeting(Path data, String id, String title, long createdAt) {
        sql(data, "INSERT INTO meetings(id,title,status,created_at,updated_at) VALUES (?,?,'done',?,?)", id, title, createdAt, createdAt);
    }

    static void softDelete(Path data, String meetingId) {
        sql(data, "UPDATE meetings SET deleted_at = ? WHERE id = ?", M3Support.NOW, meetingId);
    }

    static void insertAction(Path data, String id, String meetingId, String text, String status, long createdAt) {
        sql(data, "INSERT INTO action_items(id,meeting_id,text,owner_label,due_date,status,created_at) VALUES (?,?,?,?,?,?,?)",
                id, meetingId, text, "Olive", "2026-07-01", status, createdAt);
    }

    static void insertNote(Path data, String id, String meetingId, String position, String origin, String text) {
        sql(data, "INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,client_rev,created_at,updated_at) "
                + "VALUES (?,?,?,'paragraph',?,?,?,0,1,1)", id, meetingId, position, origin, "{}", text);
    }

    static void linkParticipant(Path data, String meetingId, String personId) {
        sql(data, "INSERT OR IGNORE INTO meeting_participants(meeting_id,person_id) VALUES (?,?)", meetingId, personId);
    }

    static List<Map<String, Object>> people(Path data) {
        return rows(data, "SELECT id, display_name, email FROM people ORDER BY created_at, id");
    }

    /** Event ids of the synced ics events in start order. */
    static List<String> icsEventIds(Path data) {
        List<String> ids = new ArrayList<>();
        rows(data, "SELECT id FROM calendar_events WHERE source = 'ics' ORDER BY starts_at").forEach(r -> ids.add((String) r.get("id")));
        return ids;
    }
}
