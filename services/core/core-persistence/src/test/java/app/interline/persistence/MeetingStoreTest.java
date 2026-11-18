package app.interline.persistence;

import app.interline.domain.BriefMatcher;
import app.interline.domain.BriefMatcher.Candidate;
import app.interline.persistence.MeetingStore.ActionRow;
import app.interline.persistence.MeetingStore.MeetingInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 persistence: MeetingStore. */
class MeetingStoreTest {
    private static final long NOW = 5_000_000;

    @TempDir
    Path directory;

    private SqliteDatabase database;
    private MeetingStore store;
    private CalendarStore calendar;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new MeetingStore(database);
        calendar = new CalendarStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    // ---- seed helpers ----

    private void event(String id, String source, String externalId, long startsAt) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES (?,?,?,'ev',?,?,1)").params(id, source, externalId, startsAt, startsAt + 1000).update());
    }

    /** A meeting with explicit started_at (nullable), created_at, linked event (nullable) and deleted_at (nullable). */
    private void meeting(String id, String title, Long startedAt, long createdAt, String eventId, Long deletedAt) {
        database.write(j -> j.sql("INSERT INTO meetings(id,title,status,started_at,created_at,updated_at,calendar_event_id,deleted_at) "
                + "VALUES (?,?,'done',?,?,?,?,?)").params(id, title, startedAt, createdAt, createdAt, eventId, deletedAt).update());
    }

    private void block(String id, String meetingId, String position, String origin, String text) {
        database.write(j -> j.sql("INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) "
                + "VALUES (?,?,?,'paragraph',?,?,?,1,1)").params(id, meetingId, position, origin, text, text).update());
    }

    private void action(String id, String meetingId, String text, String owner, String due, String status, long createdAt) {
        database.write(j -> j.sql("INSERT INTO action_items(id,meeting_id,text,owner_label,due_date,status,created_at) "
                + "VALUES (?,?,?,?,?,?,?)").params(id, meetingId, text, owner, due, status, createdAt).update());
    }

    private List<String> ids(List<Candidate> list) {
        return list.stream().map(Candidate::meetingId).toList();
    }

    // ---- createDraft / find ----

    @Test
    void createDraftStoresADraftWithBothTimestamps() {
        store.createDraft("m1", "Planning", null, NOW);

        MeetingInfo info = store.find("m1").orElseThrow();
        assertThat(info.id()).isEqualTo("m1");
        assertThat(info.title()).isEqualTo("Planning");
        assertThat(info.status()).isEqualTo("draft");
        assertThat(info.calendarEventId()).isNull();
        assertThat(info.startedAt()).isNull();
        assertThat(info.createdAt()).isEqualTo(NOW);
        long updated = database.read(j -> j.sql("SELECT updated_at FROM meetings WHERE id = 'm1'")
                .query(Long.class).single());
        assertThat(updated).isEqualTo(NOW);
    }

    @Test
    void createDraftLinksTheCalendarEvent() {
        event("e1", "ics", "u1", 1000);
        store.createDraft("m1", "Planning", "e1", NOW);
        assertThat(store.find("m1").orElseThrow().calendarEventId()).isEqualTo("e1");
    }

    @Test
    void createDraftAllowsAnEmptyTitle() {
        store.createDraft("m1", "", null, NOW);
        assertThat(store.find("m1").orElseThrow().title()).isEmpty();
    }

    @Test
    void findUnknownIsEmpty() {
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void findHidesDeletedMeetings() {
        meeting("m1", "gone", null, 1, null, 99L);
        assertThat(store.find("m1")).isEmpty();
    }

    @Test
    void findReturnsStartedAtWhenSet() {
        meeting("m1", "t", 777L, 1, null, null);
        MeetingInfo info = store.find("m1").orElseThrow();
        assertThat(info.startedAt()).isEqualTo(777L);
        assertThat(info.status()).isEqualTo("done");
    }

    // ---- findByCalendarEvent ----

    @Test
    void findByCalendarEventReturnsTheLinkedMeeting() {
        event("e1", "ics", "u1", 1000);
        store.createDraft("m1", "t", "e1", NOW);
        assertThat(store.findByCalendarEvent("e1")).contains("m1");
    }

    @Test
    void findByCalendarEventUnknownOrUnlinkedIsEmpty() {
        event("e1", "ics", "u1", 1000);
        store.createDraft("m1", "t", null, NOW);
        assertThat(store.findByCalendarEvent("e1")).isEmpty();
        assertThat(store.findByCalendarEvent("nope")).isEmpty();
    }

    @Test
    void findByCalendarEventPicksTheOldestMeeting() {
        event("e1", "ics", "u1", 1000);
        meeting("m-new", "n", null, 300, "e1", null);
        meeting("m-old", "o", null, 100, "e1", null);
        meeting("m-mid", "m", null, 200, "e1", null);
        assertThat(store.findByCalendarEvent("e1")).contains("m-old");
    }

    @Test
    void findByCalendarEventIgnoresDeletedMeetings() {
        event("e1", "ics", "u1", 1000);
        meeting("m-deleted", "d", null, 100, "e1", 500L);
        assertThat(store.findByCalendarEvent("e1")).isEmpty();
        meeting("m-live", "l", null, 200, "e1", null);
        assertThat(store.findByCalendarEvent("e1")).contains("m-live");
    }

    // ---- subject ----

    @Test
    void subjectOfUnknownOrDeletedMeetingIsEmpty() {
        meeting("m1", "gone", null, 1, null, 9L);
        assertThat(store.subject("nope")).isEmpty();
        assertThat(store.subject("m1")).isEmpty();
    }

    @Test
    void subjectWithoutEventHasNoSeriesAndNoParticipants() {
        store.createDraft("m1", "Planning", null, NOW);
        BriefMatcher.Subject s = store.subject("m1").orElseThrow();
        assertThat(s.meetingId()).isEqualTo("m1");
        assertThat(s.title()).isEqualTo("Planning");
        assertThat(s.personIds()).isEmpty();
        assertThat(s.seriesUid()).isNull();
    }

    @Test
    void subjectSeriesUidIsThePartBeforeTheHashOfTheIcsExternalId() {
        event("e1", "ics", "uid-abc@host#20260101T090000Z", 1000);
        store.createDraft("m1", "t", "e1", NOW);
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isEqualTo("uid-abc@host");
    }

    @Test
    void subjectSeriesUidOfAnExternalIdWithoutHashIsTheWholeId() {
        event("e1", "ics", "single-uid", 1000);
        store.createDraft("m1", "t", "e1", NOW);
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isEqualTo("single-uid");
    }

    @Test
    void subjectSeriesUidOfANonIcsEventIsKeyedBySource() {
        event("e1", "manual", "g#1", 1000);
        store.createDraft("m1", "t", "e1", NOW);
        // Review round 1: series match on source AND UID, so other sources work too and never collide with ics.
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isEqualTo("manual\u0000g");
    }

    @Test
    void subjectSeriesUidIsNullWhenTheEventHasNoExternalId() {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES ('e1','ics',NULL,'ev',1,2,1)").update());
        store.createDraft("m1", "t", "e1", NOW);
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isNull();
    }

    @Test
    void subjectCarriesTheParticipantPersonIds() {
        store.createDraft("m1", "t", null, NOW);
        store.createDraft("m2", "t", null, NOW);
        var a = calendar.resolvePerson("A", "a@x.io", 10).orElseThrow();
        var b = calendar.resolvePerson("B", null, 20).orElseThrow();
        var c = calendar.resolvePerson("C", "c@x.io", 30).orElseThrow();
        calendar.replaceParticipants("m1", List.of(a.id(), b.id()));
        calendar.replaceParticipants("m2", List.of(c.id()));

        assertThat(store.subject("m1").orElseThrow().personIds()).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(store.subject("m2").orElseThrow().personIds()).containsExactly(c.id());
    }

    // ---- candidates: date rule ----

    @Test
    void candidateDateIsStartedAtWhenPresent() {
        event("e1", "ics", "u", 5_000);
        meeting("m1", "t", 1_000L, 9_000, "e1", null);
        Candidate c = store.candidates("x", 100_000, 10).get(0);
        assertThat(c.dateMs()).isEqualTo(1_000);
    }

    @Test
    void candidateDateFallsBackToTheLinkedEventStart() {
        event("e1", "ics", "u", 5_000);
        meeting("m1", "t", null, 9_000, "e1", null);
        assertThat(store.candidates("x", 100_000, 10).get(0).dateMs()).isEqualTo(5_000);
    }

    @Test
    void candidateDateFallsBackToCreatedAtWithoutStartOrEvent() {
        meeting("m1", "t", null, 9_000, null, null);
        assertThat(store.candidates("x", 100_000, 10).get(0).dateMs()).isEqualTo(9_000);
    }

    @Test
    void beforeMsIsExclusiveAndAppliesToTheComputedDate() {
        meeting("at", "t", 1_000L, 1, null, null);
        meeting("before", "t", 999L, 1, null, null);
        meeting("after", "t", 1_001L, 1, null, null);
        // created_at is far in the past but started_at decides
        meeting("fallback-created", "t", null, 500, null, null);
        assertThat(ids(store.candidates("x", 1_000, 10))).containsExactly("before", "fallback-created");
    }

    @Test
    void theEventDateDecidesTheBeforeFilter() {
        event("e-late", "ics", "u", 50_000);
        meeting("m1", "t", null, 1, "e-late", null); // created long ago but its event is in the future
        assertThat(store.candidates("x", 10_000, 10)).isEmpty();
        assertThat(ids(store.candidates("x", 60_000, 10))).containsExactly("m1");
    }

    // ---- candidates: exclusion ----

    @Test
    void candidatesExcludeTheGivenMeeting() {
        meeting("self", "t", 100L, 1, null, null);
        meeting("other", "t", 200L, 1, null, null);
        assertThat(ids(store.candidates("self", 10_000, 10))).containsExactly("other");
    }

    @Test
    void candidatesExcludeDeletedMeetings() {
        meeting("live", "t", 100L, 1, null, null);
        meeting("dead", "t", 200L, 1, null, 5L);
        assertThat(ids(store.candidates("x", 10_000, 10))).containsExactly("live");
    }

    @Test
    void candidatesOnAnEmptyStoreAreEmpty() {
        assertThat(store.candidates("x", 10_000, 10)).isEmpty();
    }

    // ---- candidates: ordering and limit ----

    @Test
    void candidatesAreMostRecentFirst() {
        meeting("a", "t", 100L, 1, null, null);
        meeting("b", "t", 300L, 1, null, null);
        meeting("c", "t", 200L, 1, null, null);
        assertThat(ids(store.candidates("x", 10_000, 10))).containsExactly("b", "c", "a");
    }

    @Test
    void candidatesMixDateSourcesInOneOrdering() {
        event("e1", "ics", "u", 250);
        meeting("started", "t", 300L, 1, null, null);
        meeting("viaEvent", "t", null, 1, "e1", null);
        meeting("created", "t", null, 200, null, null);
        assertThat(ids(store.candidates("x", 10_000, 10))).containsExactly("started", "viaEvent", "created");
    }

    @Test
    void candidatesHonourTheLimitKeepingTheMostRecent() {
        for (int i = 1; i <= 8; i++) meeting("m" + i, "t", i * 100L, 1, null, null);
        assertThat(ids(store.candidates("x", 10_000, 3))).containsExactly("m8", "m7", "m6");
    }

    @Test
    void candidatesLimitLargerThanTheDataReturnsAll() {
        meeting("a", "t", 100L, 1, null, null);
        meeting("b", "t", 200L, 1, null, null);
        assertThat(store.candidates("x", 10_000, 500)).hasSize(2);
    }

    @Test
    void theLimitIsAppliedAfterExcludingTheGivenMeeting() {
        meeting("self", "t", 900L, 1, null, null);
        meeting("a", "t", 800L, 1, null, null);
        meeting("b", "t", 700L, 1, null, null);
        assertThat(ids(store.candidates("self", 10_000, 2))).containsExactly("a", "b");
    }

    // ---- candidates: content ----

    @Test
    void candidateCarriesTitleParticipantsAndSeriesUid() {
        event("e1", "ics", "series-1#2026-01-01", 500);
        meeting("m1", "Weekly", null, 1, "e1", null);
        var p = calendar.resolvePerson("A", "a@x.io", 10).orElseThrow();
        calendar.replaceParticipants("m1", List.of(p.id()));

        Candidate c = store.candidates("x", 10_000, 10).get(0);

        assertThat(c.meetingId()).isEqualTo("m1");
        assertThat(c.title()).isEqualTo("Weekly");
        assertThat(c.dateMs()).isEqualTo(500);
        assertThat(c.personIds()).containsExactly(p.id());
        assertThat(c.seriesUid()).isEqualTo("series-1");
    }

    @Test
    void candidateWithoutEventOrParticipantsHasEmptyPeopleAndNullSeries() {
        meeting("m1", "t", 100L, 1, null, null);
        Candidate c = store.candidates("x", 10_000, 10).get(0);
        assertThat(c.personIds()).isEmpty();
        assertThat(c.seriesUid()).isNull();
    }

    @Test
    void candidateSeriesUidOfANonIcsEventIsKeyedBySource() {
        event("e1", "manual", "g#1", 500);
        meeting("m1", "t", null, 1, "e1", null);
        assertThat(store.candidates("x", 10_000, 10).get(0).seriesUid()).isEqualTo("manual\u0000g");
    }

    @Test
    void candidatesFeedTheMatcherEndToEnd() {
        event("e1", "ics", "daily#1", 100);
        event("e2", "ics", "daily#2", 200);
        event("e3", "ics", "daily#3", 300);
        meeting("m1", "Daily", null, 1, "e1", null);
        meeting("m2", "Daily", null, 1, "e2", null);
        meeting("m3", "Daily", null, 1, "e3", null);

        var subject = store.subject("m3").orElseThrow();
        var matches = BriefMatcher.rank(subject, store.candidates("m3", 300, 500));

        assertThat(matches).extracting(m -> m.candidate().meetingId()).containsExactly("m2", "m1");
        assertThat(matches).extracting(BriefMatcher.Match::reason)
                .containsOnly(BriefMatcher.Reason.SERIES);
    }

    // ---- userNotes ----

    @Test
    void userNotesAreInPositionOrder() {
        meeting("m1", "t", 1L, 1, null, null);
        block("b1", "m1", "c", "user", "third");
        block("b2", "m1", "a", "user", "first");
        block("b3", "m1", "b", "user", "second");
        assertThat(store.userNotes("m1", 10)).containsExactly("first", "second", "third");
    }

    @Test
    void userNotesNeverIncludeAiBlocks() {
        meeting("m1", "t", 1L, 1, null, null);
        block("b1", "m1", "a", "user", "mine");
        block("b2", "m1", "b", "ai", "generated");
        block("b3", "m1", "c", "user", "also mine");
        assertThat(store.userNotes("m1", 10)).containsExactly("mine", "also mine");
    }

    @Test
    void userNotesOfOnlyAiBlocksAreEmpty() {
        meeting("m1", "t", 1L, 1, null, null);
        block("b1", "m1", "a", "ai", "generated");
        assertThat(store.userNotes("m1", 10)).isEmpty();
    }

    @Test
    void userNotesHonourTheLimitKeepingTheFirstOnes() {
        meeting("m1", "t", 1L, 1, null, null);
        for (char ch = 'a'; ch <= 'h'; ch++) block("b" + ch, "m1", String.valueOf(ch), "user", "note-" + ch);
        assertThat(store.userNotes("m1", 5)).containsExactly("note-a", "note-b", "note-c", "note-d", "note-e");
    }

    @Test
    void userNotesLimitIsAppliedAfterDroppingAiBlocks() {
        meeting("m1", "t", 1L, 1, null, null);
        block("b1", "m1", "a", "ai", "ai-1");
        block("b2", "m1", "b", "ai", "ai-2");
        block("b3", "m1", "c", "user", "u-1");
        block("b4", "m1", "d", "user", "u-2");
        assertThat(store.userNotes("m1", 2)).containsExactly("u-1", "u-2");
    }

    @Test
    void userNotesAreScopedToTheMeeting() {
        meeting("m1", "t", 1L, 1, null, null);
        meeting("m2", "t", 1L, 1, null, null);
        block("b1", "m1", "a", "user", "one");
        block("b2", "m2", "a", "user", "two");
        assertThat(store.userNotes("m1", 10)).containsExactly("one");
    }

    @Test
    void userNotesOfAnUnknownMeetingAreEmpty() {
        assertThat(store.userNotes("nope", 5)).isEmpty();
    }

    // ---- openActions ----

    @Test
    void openActionsReturnsOnlyOpenItemsWithAllFields() {
        meeting("m1", "t", 1L, 1, null, null);
        action("a1", "m1", "send deck", "Ann", "2026-12-01", "open", 10);
        action("a2", "m1", "old", null, null, "done", 11);
        action("a3", "m1", "skip", null, null, "dismissed", 12);

        List<ActionRow> rows = store.openActions(List.of("m1"));

        assertThat(rows).containsExactly(new ActionRow("a1", "m1", "send deck", "Ann", "2026-12-01"));
    }

    @Test
    void openActionsKeepNullOwnerAndDueDate() {
        meeting("m1", "t", 1L, 1, null, null);
        action("a1", "m1", "x", null, null, "open", 10);
        ActionRow row = store.openActions(List.of("m1")).get(0);
        assertThat(row.ownerLabel()).isNull();
        assertThat(row.dueDate()).isNull();
    }

    @Test
    void openActionsOrderByMeetingThenCreatedAtThenId() {
        meeting("m1", "t", 1L, 1, null, null);
        meeting("m2", "t", 1L, 1, null, null);
        action("a-late", "m1", "x", null, null, "open", 30);
        action("b-tie", "m1", "x", null, null, "open", 20);
        action("a-tie", "m1", "x", null, null, "open", 20);
        action("z", "m2", "x", null, null, "open", 5);
        action("y", "m2", "x", null, null, "open", 1);
        assertThat(store.openActions(List.of("m2", "m1"))).extracting(ActionRow::id)
                .containsExactly("a-tie", "b-tie", "a-late", "y", "z");
    }

    @Test
    void openActionsOnlyCoverTheGivenMeetings() {
        meeting("m1", "t", 1L, 1, null, null);
        meeting("m2", "t", 1L, 1, null, null);
        action("a1", "m1", "x", null, null, "open", 1);
        action("a2", "m2", "x", null, null, "open", 1);
        assertThat(store.openActions(List.of("m2"))).extracting(ActionRow::id).containsExactly("a2");
    }

    @Test
    void openActionsOfAnEmptyListAreEmpty() {
        meeting("m1", "t", 1L, 1, null, null);
        action("a1", "m1", "x", null, null, "open", 1);
        assertThat(store.openActions(List.of())).isEmpty();
    }

    @Test
    void openActionsOfUnknownMeetingsAreEmpty() {
        assertThat(store.openActions(List.of("nope"))).isEmpty();
    }

    @Test
    void openActionsHandleManyMeetingIds() {
        meeting("m1", "t", 1L, 1, null, null);
        action("a1", "m1", "x", null, null, "open", 1);
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) many.add("none-" + i);
        many.add("m1");
        assertThat(store.openActions(many)).extracting(ActionRow::id).containsExactly("a1");
    }
}
