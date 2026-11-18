package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;

import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3BriefSupport.*;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC5, the M3 slice 2 exit criterion: a recurring series is synced, meetings are created from its first two
 * occurrences, each gets an open action item, and the brief of the third occurrence lists both actions.
 */
@Timeout(180)
class M3BriefExitCriterionTest {
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

    /** Syncs a daily series of three occurrences (now-20h, now+4h, now+28h) and returns the event ids in start order. */
    private List<String> syncSeries() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(calendar(vevent("series-uid", "Daily standup", NOW - 20 * HOUR, NOW - 19 * HOUR, "RRULE:FREQ=DAILY;COUNT=3")));
        ok(sync(port), "sync");
        List<String> ids = icsEventIds(data);
        assertThat(ids).hasSize(3);
        return ids;
    }

    private String meetingFor(String eventId) {
        return ok(fromEvent(port, eventId), "meeting from event").json().path("id").asText();
    }

    @Test void AC5_theBriefOfTheThirdOccurrenceListsTheActionsOfTheFirstTwo() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        String second = meetingFor(events.get(1));
        insertAction(data, "act-1", first, "Send the report", "open", 10);
        insertAction(data, "act-2", second, "Book the room", "open", 20);
        String third = meetingFor(events.get(2));

        JsonNode brief = briefOk(port, third);

        assertThat(brief.path("meetingId").asText()).isEqualTo(third);
        JsonNode related = brief.path("related");
        assertThat(texts(related, "meetingId")).containsExactly(second, first);
        related.forEach(r -> assertThat(r.path("matchedBy").asText()).isEqualTo("series"));
        assertThat(texts(brief.path("openActions"), "text")).containsExactlyInAnyOrder("Send the report", "Book the room");
        assertThat(texts(brief.path("openActions"), "id")).containsExactlyInAnyOrder("act-1", "act-2");
        JsonNode action = brief.path("openActions").get(0);
        assertThat(action.path("meetingId").asText()).isIn(first, second);
        assertThat(action.path("ownerLabel").asText()).isEqualTo("Olive");
        assertThat(action.path("dueDate").asText()).isEqualTo("2026-07-01");
    }

    @Test void AC5_aMeetingNeverRelatesToALaterOccurrence() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        meetingFor(events.get(1));
        meetingFor(events.get(2));
        assertThat(briefOk(port, first).path("related")).isEmpty();
    }

    @Test void AC5_doneAndDismissedActionsAreExcluded() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        String second = meetingFor(events.get(1));
        insertAction(data, "act-open", first, "Still open", "open", 10);
        insertAction(data, "act-done", first, "Finished", "done", 11);
        insertAction(data, "act-dismissed", second, "Dropped", "dismissed", 12);
        String third = meetingFor(events.get(2));

        JsonNode brief = briefOk(port, third);

        assertThat(texts(brief.path("openActions"), "id")).containsExactly("act-open");
        assertThat(brief.path("related")).hasSize(2);
    }

    @Test void AC5_anAiNoteNeverAppearsAndUserNotesComeInPositionOrder() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        meetingFor(events.get(1));
        insertNote(data, "n-c", first, "a2", "user", "third note");
        insertNote(data, "n-a", first, "a0", "user", "first note");
        insertNote(data, "n-ai", first, "a1", "ai", "AI SUMMARY MUST NOT LEAK");
        insertNote(data, "n-b", first, "a1x", "user", "second note");
        String third = meetingFor(events.get(2));

        JsonNode brief = briefOk(port, third);

        JsonNode firstRelated = null;
        for (JsonNode r : brief.path("related")) if (first.equals(r.path("meetingId").asText())) firstRelated = r;
        assertThat(firstRelated).isNotNull();
        List<String> notes = new java.util.ArrayList<>();
        firstRelated.path("notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).containsExactly("first note", "second note", "third note");
        assertThat(brief.toString()).doesNotContain("AI SUMMARY MUST NOT LEAK");
    }

    @Test void AC5_atMostFiveNotesPerRelatedMeeting() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        for (int i = 0; i < 8; i++) insertNote(data, "n-" + i, first, "a" + i, "user", "note " + i);
        meetingFor(events.get(1));
        String third = meetingFor(events.get(2));

        JsonNode related = briefOk(port, third).path("related");
        JsonNode firstRelated = null;
        for (JsonNode r : related) if (first.equals(r.path("meetingId").asText())) firstRelated = r;
        assertThat(firstRelated).isNotNull();
        List<String> notes = new java.util.ArrayList<>();
        firstRelated.path("notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).containsExactly("note 0", "note 1", "note 2", "note 3", "note 4");
    }

    @Test void AC5_relatedIsCappedAtTenWhenTwelveMeetingsMatch() {
        String subject = createMeetingOk(port, "Quarterly review", null);
        for (int i = 0; i < 12; i++) insertMeeting(data, "old-" + i, "Quarterly Review", NOW - (i + 1) * HOUR);

        JsonNode related = briefOk(port, subject).path("related");

        assertThat(related).hasSize(10);
        List<String> ids = texts(related, "meetingId");
        assertThat(ids.get(0)).isEqualTo("old-0");
        assertThat(ids.get(9)).isEqualTo("old-9");
        related.forEach(r -> assertThat(r.path("matchedBy").asText()).isEqualTo("title"));
    }

    @Test void AC5_aSharedParticipantRelatesMeetingsAndOnlyTheSeriesLinksTheThirdOccurrence() {
        List<String> events = syncSeries();
        String first = meetingFor(events.get(0));
        String third = meetingFor(events.get(2));
        String other = createMeetingOk(port, "Something else", List.of(person("Alice", "alice@example.com")));
        String earlier = createMeetingOk(port, "Unrelated title", List.of(person("Alice", "alice@example.com")));
        sql(data, "UPDATE meetings SET created_at = ?, updated_at = ? WHERE id = ?", NOW - 40 * HOUR, NOW - 40 * HOUR, earlier);
        insertAction(data, "act-p", earlier, "From a participant", "open", 1);

        // third shares no participant here: only the series link applies
        JsonNode thirdBrief = briefOk(port, third);
        assertThat(texts(thirdBrief.path("related"), "meetingId")).containsExactly(first);

        // `other` has Alice, so the earlier Alice meeting is related by participant
        JsonNode otherBrief = briefOk(port, other);
        assertThat(texts(otherBrief.path("related"), "meetingId")).contains(earlier);
        for (JsonNode r : otherBrief.path("related")) {
            if (earlier.equals(r.path("meetingId").asText())) assertThat(r.path("matchedBy").asText()).isEqualTo("participant");
        }
        assertThat(texts(otherBrief.path("openActions"), "id")).contains("act-p");
    }
}
