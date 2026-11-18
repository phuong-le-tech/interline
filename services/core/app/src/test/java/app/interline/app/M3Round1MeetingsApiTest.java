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
import java.util.Map;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3BriefSupport.*;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 review round 1 over the real core: atomic writes, related meetings beyond 500 rows, camelCase wire names. */
@Timeout(180)
class M3Round1MeetingsApiTest {
    private static final String TRIGGER = "CREATE TRIGGER boom BEFORE INSERT ON meeting_participants WHEN EXISTS "
            + "(SELECT 1 FROM people WHERE id = NEW.person_id AND display_name = 'Boom') BEGIN SELECT RAISE(ABORT, 'boom'); END";

    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        context = boot(data);
        port = port(context);
    }

    @AfterEach void stop() {
        if (context.isActive()) context.close();
    }

    private void armTrigger() {
        sql(data, TRIGGER);
    }

    private void disarmTrigger() {
        sql(data, "DROP TRIGGER boom");
    }

    private long meetingCount() {
        return ((Number) rows(data, "SELECT COUNT(*) AS n FROM meetings").get(0).get("n")).longValue();
    }

    private static void assertNotSuccess(Resp response) {
        assertThat(response.status()).as(response.body()).isNotBetween(200, 299);
    }

    private static List<String> names(JsonNode list) {
        return texts(list, "name");
    }

    private List<String> personNames() {
        List<String> out = new ArrayList<>();
        people(data).forEach(p -> out.add((String) p.get("display_name")));
        return out;
    }

    // ---- item 1: atomicity at API level -----------------------------------------------------------------------------

    @Test void createMeetingWithAFailingParticipantLeavesNoMeetingAndNoPeople() {
        armTrigger();
        Resp response = createMeeting(port, "Atomic", List.of(person("Alice", "alice@example.com"), person("Boom", null)));
        assertNotSuccess(response);
        assertThat(response.status()).isNotEqualTo(201);

        assertThat(ok(call(port, "GET", "/meetings", null), "list").json()).isEmpty();
        assertThat(meetingCount()).isZero();
        assertThat(people(data)).isEmpty();
        assertThat(rows(data, "SELECT * FROM meeting_participants")).isEmpty();
    }

    @Test void createMeetingRetryAfterTheFailureCreatesAllParticipants() {
        armTrigger();
        assertNotSuccess(createMeeting(port, "Atomic", List.of(person("Alice", "alice@example.com"), person("Boom", null))));
        disarmTrigger();

        Resp retry = createMeeting(port, "Atomic", List.of(person("Alice", "alice@example.com"), person("Boom", null)));
        assertThat(retry.status()).as(retry.body()).isEqualTo(201);
        String id = retry.json().path("id").asText();
        assertThat(names(ok(participants(port, id), "participants").json())).containsExactlyInAnyOrder("Alice", "Boom");
        assertThat(meetingCount()).isEqualTo(1);
    }

    @Test void meetingFromEventWithAFailingAttendeeLeavesNoMeetingAndNoPerson() {
        insertEventWithAttendees(data, "ev-boom", "Boom review", NOW + HOUR,
                "[{\"name\":\"Alice\",\"email\":\"alice@example.com\"},{\"name\":\"Boom\"}]");
        armTrigger();
        assertNotSuccess(fromEvent(port, "ev-boom"));
        assertThat(meetingCount()).isZero();
        assertThat(people(data)).isEmpty();
        assertThat(rows(data, "SELECT * FROM meeting_participants")).isEmpty();
    }

    @Test void meetingFromEventRetryAfterTheFailureCreatesAllAttendees() {
        insertEventWithAttendees(data, "ev-boom", "Boom review", NOW + HOUR,
                "[{\"name\":\"Alice\",\"email\":\"alice@example.com\"},{\"name\":\"Boom\"}]");
        armTrigger();
        assertNotSuccess(fromEvent(port, "ev-boom"));
        disarmTrigger();

        Resp retry = fromEvent(port, "ev-boom");
        assertThat(retry.status()).as(retry.body()).isEqualTo(201);
        String id = retry.json().path("id").asText();
        assertThat(names(ok(participants(port, id), "participants").json())).containsExactlyInAnyOrder("Alice", "Boom");
        assertThat(meetingCount()).isEqualTo(1);

        Resp again = fromEvent(port, "ev-boom");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("id").asText()).isEqualTo(id);
        assertThat(meetingCount()).isEqualTo(1);
    }

    @Test void aFailingPutKeepsThePreviousParticipantsAndThePeopleTable() {
        String id = createMeetingOk(port, "Sync", List.of(person("Carol", "carol@example.com")));
        List<Map<String, Object>> peopleBefore = people(data);
        armTrigger();

        assertNotSuccess(putParticipants(port, id, List.of(person("Dave", "dave@example.com"), person("Boom", null))));

        assertThat(names(ok(participants(port, id), "get").json())).containsExactly("Carol");
        assertThat(people(data)).isEqualTo(peopleBefore);
        assertThat(personNames()).containsExactly("Carol");
    }

    // ---- item 2: related meeting older than 500 unrelated ones -------------------------------------------------------

    /** Inserts {@code count} done meetings in one transaction, the i-th created at {@code firstCreatedAt - i}. */
    private void bulkMeetings(String idPrefix, String titlePrefix, int count, long firstCreatedAt) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            try (PreparedStatement busy = c.prepareStatement("PRAGMA busy_timeout = 5000")) {
                busy.execute();
            }
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO meetings(id,title,status,created_at,updated_at) VALUES (?,?,'done',?,?)")) {
                for (int i = 0; i < count; i++) {
                    long at = firstCreatedAt - i;
                    s.setString(1, idPrefix + i);
                    s.setString(2, titlePrefix + i);
                    s.setLong(3, at);
                    s.setLong(4, at);
                    s.addBatch();
                }
                s.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            throw new AssertionError("bulk insert failed", e);
        }
    }

    @Test void aRelatedMeetingOlderThan500UnrelatedOnesStillAppearsInRelated() {
        bulkMeetings("noise-", "Unrelated noise topic ", 600, NOW - HOUR);
        insertMeeting(data, "old-related", "Quarterly roadmap sync", NOW - 200 * DAY);
        String subject = createMeetingOk(port, "Quarterly roadmap sync", null);

        JsonNode brief = briefOk(port, subject);
        assertThat(texts(brief.path("related"), "meetingId")).containsExactly("old-related");
        assertThat(brief.path("related").get(0).path("matchedBy").asText()).isEqualTo("title");
    }

    @Test void theLimitOfTenRelatedMeetingsStillAppliesAfterMatching() {
        bulkMeetings("noise-", "Unrelated noise topic ", 600, NOW - HOUR);
        for (int i = 0; i < 12; i++) insertMeeting(data, "rel-" + i, "Weekly planning", NOW - 100 * DAY - i * HOUR);
        String subject = createMeetingOk(port, "Weekly planning", null);

        JsonNode related = briefOk(port, subject).path("related");
        assertThat(related).hasSize(10);
        assertThat(texts(related, "meetingId")).doesNotContain("rel-10", "rel-11");
    }

    // ---- item 4: camelCase wire names --------------------------------------------------------------------------------

    @Test void theBriefUsesCamelCaseKeysAndNoSnakeCaseKeys() {
        insertMeeting(data, "earlier", "Budget review", NOW - 3 * DAY);
        insertAction(data, "act-1", "earlier", "Send the budget", "open", 10);
        insertNote(data, "n-1", "earlier", "a0", "user", "Budget notes");
        String subject = createMeetingOk(port, "Budget review", List.of(person("Alice", "alice@example.com")));

        JsonNode brief = briefOk(port, subject);
        assertThat(brief.has("meetingId")).isTrue();
        assertThat(brief.path("meetingId").asText()).isEqualTo(subject);
        assertThat(brief.has("participants")).isTrue();
        assertThat(brief.has("related")).isTrue();
        assertThat(brief.has("openActions")).isTrue();
        assertThat(brief.has("meeting_id")).isFalse();
        assertThat(brief.has("open_actions")).isFalse();

        JsonNode participant = brief.path("participants").get(0);
        assertThat(participant.has("id")).isTrue();
        assertThat(participant.has("name")).isTrue();
        assertThat(participant.has("email")).isTrue();

        JsonNode related = brief.path("related").get(0);
        assertThat(related.has("meetingId")).isTrue();
        assertThat(related.has("title")).isTrue();
        assertThat(related.has("date")).isTrue();
        assertThat(related.has("matchedBy")).isTrue();
        assertThat(related.has("notes")).isTrue();
        assertThat(related.has("meeting_id")).isFalse();
        assertThat(related.has("matched_by")).isFalse();

        JsonNode action = brief.path("openActions").get(0);
        assertThat(action.has("id")).isTrue();
        assertThat(action.has("meetingId")).isTrue();
        assertThat(action.has("text")).isTrue();
        assertThat(action.has("ownerLabel")).isTrue();
        assertThat(action.has("dueDate")).isTrue();
        assertThat(action.has("meeting_id")).isFalse();
        assertThat(action.has("owner_label")).isFalse();
        assertThat(action.has("due_date")).isFalse();
    }
}
