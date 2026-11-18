package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Review round 3 over the real core: a line dropped by the 64 KB line limit makes the sync incomplete and removes nothing. */
@Timeout(180)
class M3Round3ApiTest {
    @TempDir Path data;
    private IcsStub stub;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        stub = new IcsStub();
        context = boot(data);
        port = port(context);
    }

    @AfterEach void stop() {
        if (context.isActive()) context.close();
        stub.close();
    }

    private static List<String> titles(JsonNode events) {
        List<String> titles = new ArrayList<>();
        events.forEach(e -> titles.add(e.path("title").asText()));
        return titles;
    }

    private List<String> storedTitles() {
        return titles(ok(events(port), "events").json());
    }

    private static String fold(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i += 70) {
            if (i > 0) sb.append("\r\n ");
            sb.append(line, i, Math.min(line.length(), i + 70));
        }
        return sb.toString();
    }

    private static String goodFeed() {
        return calendar(
                vevent("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR),
                vevent("uid-2", "Two", NOW + 2 * HOUR, NOW + 3 * HOUR),
                vevent("uid-3", "Three", NOW + DAY, NOW + DAY + HOUR));
    }

    /** Same feed, but "Two" has a DTSTART folded into a logical line over 64 KB. */
    private static String feedWithOverlongDtstart() {
        String overlong = "DTSTART:" + icsTime(NOW + 2 * HOUR) + "0".repeat(70_000);
        String broken = "BEGIN:VEVENT\r\nUID:uid-2\r\nDTSTAMP:20260101T000000Z\r\n" + fold(overlong) + "\r\n"
                + "DTEND:" + icsTime(NOW + 3 * HOUR) + "\r\nSUMMARY:Two\r\nEND:VEVENT\r\n";
        return calendar(vevent("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR), broken,
                vevent("uid-3", "Three", NOW + DAY, NOW + DAY + HOUR));
    }

    private void linkMeeting(String meetingId, String calendarEventId) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            try (PreparedStatement busy = c.prepareStatement("PRAGMA busy_timeout = 5000")) {
                busy.execute();
            }
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO meetings(id,status,created_at,updated_at,calendar_event_id) VALUES (?, 'done', 1, 1, ?)")) {
                s.setString(1, meetingId);
                s.setString(2, calendarEventId);
                s.execute();
            }
        } catch (Exception e) {
            throw new AssertionError("cannot insert meeting " + meetingId, e);
        }
    }

    private Object linkedEvent(String meetingId) throws Exception {
        return AppTestSupport.rows(data, "SELECT calendar_event_id AS e FROM meetings WHERE id = ?", meetingId).get(0).get("e");
    }

    @Test void anOverlongFoldedDtstartMakesTheSyncIncompleteAndRemovesNothing() throws Exception {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(goodFeed());
        JsonNode first = ok(sync(port), "first sync").json();
        assertThat(first.path("complete").asBoolean()).isTrue();
        assertThat(storedTitles()).containsExactlyInAnyOrder("One", "Two", "Three");

        String twoId = AppTestSupport.rows(data, "SELECT id FROM calendar_events WHERE title = 'Two'").get(0).get("id").toString();
        linkMeeting("meeting-r3", twoId);
        assertThat(linkedEvent("meeting-r3")).isEqualTo(twoId);

        stub.serve(feedWithOverlongDtstart());
        Resp second = sync(port);

        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(second.json().path("complete").asBoolean()).isFalse();
        assertThat(second.json().path("removed").asInt()).isZero();
        assertThat(second.json().path("warnings").isArray()).isTrue();
        assertThat(second.json().path("warnings").size()).isGreaterThan(0);
        assertThat(storedTitles()).containsExactlyInAnyOrder("One", "Two", "Three");
        assertThat(countEvents(data, "ics")).isEqualTo(3);
        assertThat(linkedEvent("meeting-r3")).isEqualTo(twoId);

        stub.serve(goodFeed());
        JsonNode third = ok(sync(port), "full sync").json();

        assertThat(third.path("complete").asBoolean()).isTrue();
        assertThat(third.path("removed").asInt()).isZero();
        assertThat(storedTitles()).containsExactlyInAnyOrder("One", "Two", "Three");
        assertThat(linkedEvent("meeting-r3")).isEqualTo(twoId);
    }
}
