package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
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

/** M3 slice 2 acceptance criteria AC1 to AC4 and AC7 over the real core (fixed clock 2026-06-15T12:00:00Z). */
@Timeout(180)
class M3MeetingsApiTest {
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

    private static List<String> names(JsonNode list) {
        return texts(list, "name");
    }

    private static List<String> emails(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("email").isNull() ? null : n.path("email").asText()));
        return out;
    }

    // ---- AC1: manual meeting --------------------------------------------------------------------------------------

    @Test void AC1_createsADraftMeetingWithParticipants() {
        Resp response = createMeeting(port, "  Planning  ", List.of(person("Alice Example", "alice@example.com"), person("Bob", null)));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        String id = response.json().path("id").asText();
        assertThat(id).isNotBlank();

        JsonNode list = ok(call(port, "GET", "/meetings", null), "list").json();
        JsonNode found = null;
        for (JsonNode m : list) if (id.equals(m.path("id").asText())) found = m;
        assertThat(found).isNotNull();
        assertThat(found.path("status").asText()).isEqualTo("draft");
        assertThat(found.path("title").asText()).isEqualTo("Planning");

        JsonNode participants = ok(participants(port, id), "participants").json();
        assertThat(names(participants)).containsExactly("Alice Example", "Bob");
        assertThat(emails(participants)).containsExactly("alice@example.com", null);
    }

    @Test void AC1_participantsAreOptional() {
        String id = createMeetingOk(port, "Solo", null);
        assertThat(ok(participants(port, id), "participants").json()).isEmpty();
    }

    @Test void AC1_titleOfExactly500CharactersAndExactly200ParticipantsAreAccepted() {
        assertThat(createMeeting(port, "t".repeat(500), null).status()).isEqualTo(201);
        List<Object> two = new ArrayList<>();
        for (int i = 0; i < 200; i++) two.add(person("P" + i, "p" + i + "@example.com"));
        assertThat(createMeeting(port, "Big", two).status()).isEqualTo(201);
    }

    @Test void AC1_invalidBodiesAre400ProblemJson() {
        assertProblem(createMeeting(port, "", null), 400, "invalid_request");
        assertProblem(createMeeting(port, "   ", null), 400, "invalid_request");
        assertProblem(createMeeting(port, "t".repeat(501), null), 400, "invalid_request");

        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(person("P" + i, "p" + i + "@example.com"));
        assertProblem(createMeeting(port, "Too many", many), 400, "invalid_request");

        assertProblem(createMeeting(port, "No name", List.of(person("   ", null))), 400, "invalid_request");
        assertProblem(createMeeting(port, "No name", List.of(person(null, null))), 400, "invalid_request");
        assertProblem(createMeeting(port, "Bad email", List.of(person("A", "no-at-sign"))), 400, "invalid_request");
        assertProblem(createMeeting(port, "Long name", List.of(person("n".repeat(201), null))), 400, "invalid_request");
        assertProblem(createMeeting(port, "Long email", List.of(person("A", "e".repeat(320) + "@x.com"))), 400, "invalid_request");

        assertProblem(call(port, "POST", "/meetings", "{\"title\":\"x\",\"participants\":\"alice\"}"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", "{\"title\":\"x\",\"participants\":{\"name\":\"a\"}}"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", "{\"title\":\"x\",\"participants\":[\"alice\"]}"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", "{\"participants\":[]}"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", "{\"title\":5}"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", "{not json"), 400, "invalid_request");
        assertProblem(call(port, "POST", "/meetings", null), 400, "invalid_request");
    }

    @Test void AC1_aRejectedCreateLeavesNoMeetingAndNoPerson() {
        createMeeting(port, "Half", List.of(person("Valid", "valid@example.com"), person("   ", null)));
        assertThat(rows(data, "SELECT id FROM meetings")).isEmpty();
        assertThat(people(data)).isEmpty();
    }

    // ---- AC2: meeting from a calendar event -----------------------------------------------------------------------

    @Test void AC2_createsAMeetingFromAnEventAndIsIdempotent() {
        insertEventWithAttendees(data, "ev-1", "Design review", NOW + HOUR,
                "[{\"name\":\"Alice Example\",\"email\":\"alice@example.com\"},{\"name\":\"Bob Nomail\"}]");

        Resp first = fromEvent(port, "ev-1");
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        String id = first.json().path("id").asText();
        assertThat(id).isNotBlank();

        Resp second = fromEvent(port, "ev-1");
        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(second.json().path("id").asText()).isEqualTo(id);

        List<Map<String, Object>> meetings = rows(data, "SELECT id, title, calendar_event_id FROM meetings");
        assertThat(meetings).hasSize(1);
        assertThat(meetings.get(0).get("title")).isEqualTo("Design review");
        assertThat(meetings.get(0).get("calendar_event_id")).isEqualTo("ev-1");

        JsonNode participants = ok(participants(port, id), "participants").json();
        assertThat(names(participants)).containsExactlyInAnyOrder("Alice Example", "Bob Nomail");
        assertThat(emails(participants)).containsExactlyInAnyOrder("alice@example.com", null);
        assertThat(people(data)).hasSize(2);
    }

    @Test void AC2_unknownEventIs404() {
        assertProblem(fromEvent(port, "no-such-event"), 404, "not_found");
        assertThat(rows(data, "SELECT id FROM meetings")).isEmpty();
    }

    @Test void AC2_anAttendeeWithNeitherNameNorEmailIsSkipped() {
        insertEventWithAttendees(data, "ev-2", "Standup", NOW + HOUR,
                "[{\"name\":\"\",\"email\":\"\"},{},{\"name\":\"Carol\",\"email\":\"carol@example.com\"}]");
        String id = ok(fromEvent(port, "ev-2"), "from event").json().path("id").asText();
        assertThat(names(ok(participants(port, id), "participants").json())).containsExactly("Carol");
        assertThat(people(data)).hasSize(1);
    }

    @Test void AC2_aEventWithoutAttendeesGivesAnEmptyParticipantList() {
        insertManualEvent(data, "ev-3", "Alone", NOW + HOUR, NOW + 2 * HOUR);
        String id = ok(fromEvent(port, "ev-3"), "from event").json().path("id").asText();
        assertThat(ok(participants(port, id), "participants").json()).isEmpty();
    }

    @Test void AC2_anEventSyncedTwiceDoesNotDuplicatePeople() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(calendar(vevent("uid-a", "Synced", NOW + HOUR, NOW + 2 * HOUR,
                "ATTENDEE;CN=Alice Example:mailto:alice@example.com")));
        ok(sync(port), "sync 1");
        String eventId = icsEventIds(data).get(0);
        String id = ok(fromEvent(port, eventId), "from event").json().path("id").asText();
        ok(sync(port), "sync 2");
        Resp again = fromEvent(port, icsEventIds(data).get(0));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("id").asText()).isEqualTo(id);

        assertThat(people(data)).hasSize(1);
        assertThat(rows(data, "SELECT person_id FROM meeting_participants WHERE meeting_id = ?", id)).hasSize(1);
    }

    // ---- AC3: people dedup through the API -------------------------------------------------------------------------

    @Test void AC3_sameEmailInDifferentCaseIsOnePerson() {
        String a = createMeetingOk(port, "A", List.of(person("Alice", "Alice@Example.com")));
        String b = createMeetingOk(port, "B", List.of(person("Alice", "ALICE@example.COM")));
        assertThat(people(data)).hasSize(1);
        assertThat(people(data).get(0).get("email")).isEqualTo("alice@example.com");
        assertThat(ok(participants(port, a), "a").json().get(0).path("id").asText())
                .isEqualTo(ok(participants(port, b), "b").json().get(0).path("id").asText());
    }

    @Test void AC3_emailLessSameNameInDifferentCaseIsOnePerson() {
        createMeetingOk(port, "A", List.of(person("Dana Smith", null)));
        createMeetingOk(port, "B", List.of(person("  dana SMITH ", null)));
        assertThat(people(data)).hasSize(1);
        assertThat(people(data).get(0).get("email")).isNull();
    }

    @Test void AC3_anExistingNonEmptyNameIsKept() {
        createMeetingOk(port, "A", List.of(person("Alice Example", "alice@example.com")));
        createMeetingOk(port, "B", List.of(person("A. Other", "alice@example.com")));
        assertThat(people(data)).hasSize(1);
        assertThat(people(data).get(0).get("display_name")).isEqualTo("Alice Example");
    }

    @Test void AC3_aDuplicateInOneRequestGivesOneParticipant() {
        String id = createMeetingOk(port, "A", List.of(person("Alice", "alice@example.com"), person("Alice", "ALICE@example.com")));
        assertThat(ok(participants(port, id), "participants").json()).hasSize(1);
    }

    // ---- AC4: participants GET / PUT --------------------------------------------------------------------------------

    @Test void AC4_putReplacesTheListAndGetReturnsIt() {
        String id = createMeetingOk(port, "Sync", List.of(person("Alice", "alice@example.com"), person("Bob", "bob@example.com")));

        Resp put = putParticipants(port, id, List.of(person("Carol", "carol@example.com"), person("Dan Nomail", null)));
        assertThat(put.status()).as(put.body()).isEqualTo(200);
        assertThat(names(put.json())).containsExactly("Carol", "Dan Nomail");
        assertThat(put.json().get(0).path("id").asText()).isNotBlank();

        JsonNode got = ok(participants(port, id), "get").json();
        assertThat(names(got)).containsExactly("Carol", "Dan Nomail");
        assertThat(emails(got)).containsExactly("carol@example.com", null);
    }

    @Test void AC4_putAnEmptyArrayClearsTheList() {
        String id = createMeetingOk(port, "Sync", List.of(person("Alice", "alice@example.com")));
        assertThat(ok(putParticipants(port, id, List.of()), "put").json()).isEmpty();
        assertThat(ok(participants(port, id), "get").json()).isEmpty();
    }

    @Test void AC4_putValidatesLikeCreateAndKeepsTheOldListOnFailure() {
        String id = createMeetingOk(port, "Sync", List.of(person("Alice", "alice@example.com")));
        assertProblem(putParticipants(port, id, List.of(person(null, null))), 400, "invalid_request");
        assertProblem(putParticipants(port, id, List.of(person("X", "no-at"))), 400, "invalid_request");
        assertProblem(putParticipants(port, id, "{\"name\":\"X\"}"), 400, "invalid_request");
        assertProblem(putParticipants(port, id, "not json"), 400, "invalid_request");
        assertProblem(putParticipants(port, id, null), 400, "invalid_request");
        assertThat(names(ok(participants(port, id), "get").json())).containsExactly("Alice");
    }

    @Test void AC4_unknownAndDeletedMeetingsAre404() {
        assertProblem(participants(port, "nope"), 404, "not_found");
        assertProblem(putParticipants(port, "nope", List.of()), 404, "not_found");

        String id = createMeetingOk(port, "Gone", List.of(person("Alice", "alice@example.com")));
        softDelete(data, id);
        assertProblem(participants(port, id), 404, "not_found");
        assertProblem(putParticipants(port, id, List.of()), 404, "not_found");
    }

    // ---- AC7: a brief without history --------------------------------------------------------------------------------

    @Test void AC7_aMeetingWithoutHistoryHasAnEmptyBrief() {
        String id = createMeetingOk(port, "Brand new topic", List.of(person("Alice", "alice@example.com")));
        Resp response = brief(port, id);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode brief = response.json();
        assertThat(brief.path("meeting_id").asText()).isEqualTo(id);
        assertThat(brief.path("participants").isArray()).isTrue();
        assertThat(names(brief.path("participants"))).containsExactly("Alice");
        assertThat(brief.path("related").isArray()).isTrue();
        assertThat(brief.path("related")).isEmpty();
        assertThat(brief.path("open_actions").isArray()).isTrue();
        assertThat(brief.path("open_actions")).isEmpty();
    }

    @Test void AC7_unknownAndDeletedMeetingBriefIs404() {
        assertProblem(brief(port, "nope"), 404, "not_found");
        String id = createMeetingOk(port, "Gone", null);
        softDelete(data, id);
        assertProblem(brief(port, id), 404, "not_found");
    }
}
