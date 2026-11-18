package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.BriefMatcher;
import app.interline.domain.BriefMatcher.Candidate;
import app.interline.persistence.MeetingStore.DraftResult;
import app.interline.persistence.MeetingStore.PersonInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 review round 1 persistence: atomic MeetingStore writes, series matching by source, candidate paging. */
class MeetingStoreRound1Test {
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

    // ---- helpers ----

    private void event(String id, String source, String externalId, long startsAt) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES (?,?,?,'ev',?,?,1)").params(id, source, externalId, startsAt, startsAt + 1000).update());
    }

    private void eventWithAttendees(String id, String title, List<Attendee> attendees) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,attendees,synced_at) "
                + "VALUES (?,'ics',?,?,1000,2000,?,1)").params(id, "uid-" + id, title, AttendeesJson.encode(attendees)).update());
    }

    private void meeting(String id, String title, Long startedAt, long createdAt, String eventId, Long deletedAt) {
        database.write(j -> j.sql("INSERT INTO meetings(id,title,status,started_at,created_at,updated_at,calendar_event_id,deleted_at) "
                + "VALUES (?,?,'done',?,?,?,?,?)").params(id, title, startedAt, createdAt, createdAt, eventId, deletedAt).update());
    }

    private void installBoomTrigger() {
        database.write(j -> j.sql("CREATE TRIGGER boom BEFORE INSERT ON meeting_participants "
                + "WHEN EXISTS (SELECT 1 FROM people WHERE id = NEW.person_id AND display_name = 'Boom') "
                + "BEGIN SELECT RAISE(ABORT, 'boom'); END").update());
    }

    private void dropBoomTrigger() {
        database.write(j -> j.sql("DROP TRIGGER boom").update());
    }

    private long count(String table) {
        return database.read(j -> j.sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }

    private long meetingCount() {
        return count("meetings");
    }

    private List<String> participantNames(String meetingId) {
        return database.read(j -> j.sql("SELECT p.display_name FROM meeting_participants mp JOIN people p ON p.id = mp.person_id "
                + "WHERE mp.meeting_id = ? ORDER BY p.display_name").param(meetingId).query(String.class).list());
    }

    private List<String> personIds(String meetingId) {
        return database.read(j -> j.sql("SELECT person_id FROM meeting_participants WHERE meeting_id = ? ORDER BY person_id")
                .param(meetingId).query(String.class).list());
    }

    private List<String> allPeopleNames() {
        return database.read(j -> j.sql("SELECT display_name FROM people ORDER BY display_name").query(String.class).list());
    }

    private static PersonInput person(String name, String email) {
        return new PersonInput(name, email);
    }

    private List<String> ids(List<Candidate> list) {
        return list.stream().map(Candidate::meetingId).toList();
    }

    // ---- createManual ----

    @Test
    void createManualCreatesDraftPeopleAndLinks() {
        store.createManual("m1", "Kickoff", List.of(person("Alice", "alice@x.io"), person("Bob", null)), NOW);

        var info = store.find("m1").orElseThrow();
        assertThat(info.title()).isEqualTo("Kickoff");
        assertThat(info.status()).isEqualTo("draft");
        assertThat(info.calendarEventId()).isNull();
        assertThat(info.createdAt()).isEqualTo(NOW);
        assertThat(participantNames("m1")).containsExactly("Alice", "Bob");
        assertThat(count("people")).isEqualTo(2);
    }

    @Test
    void createManualWithNoPeopleCreatesJustTheMeeting() {
        store.createManual("m1", "Solo", List.of(), NOW);
        assertThat(store.find("m1")).isPresent();
        assertThat(count("people")).isZero();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void createManualSkipsEntriesWithoutNameOrEmail() {
        store.createManual("m1", "t", List.of(person(null, null), person("  ", ""), person(null, "  "), person("Ann", null)), NOW);
        assertThat(participantNames("m1")).containsExactly("Ann");
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void createManualTreatsAnEmailWithoutAtSignAsNoEmail() {
        store.createManual("m1", "t", List.of(person("Ann", "not-an-email"), person(null, "also-bad")), NOW);
        assertThat(participantNames("m1")).containsExactly("Ann");
        String email = database.read(j -> j.sql("SELECT email FROM people WHERE display_name = 'Ann'").query(String.class).optional())
                .orElse(null);
        assertThat(email).isNull();
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void createManualReusesAPersonByEmailAndByNameWithoutEmail() {
        var existing = calendar.resolvePerson("Alice", "alice@x.io", 10).orElseThrow();
        var named = calendar.resolvePerson("Bob", null, 20).orElseThrow();

        store.createManual("m1", "t", List.of(person("Alice Renamed", "ALICE@x.io"), person(" bob ", null)), NOW);

        assertThat(count("people")).isEqualTo(2);
        assertThat(personIds("m1")).containsExactlyInAnyOrder(existing.id(), named.id());
    }

    @Test
    void createManualDeduplicatesTheSamePersonListedTwice() {
        store.createManual("m1", "t", List.of(person("Ann", "a@x.io"), person("Ann", "a@x.io")), NOW);
        assertThat(count("people")).isEqualTo(1);
        assertThat(count("meeting_participants")).isEqualTo(1);
    }

    @Test
    void createManualRollsBackEverythingWhenALinkFails() {
        installBoomTrigger();

        try {
            store.createManual("m1", "t", List.of(person("Alice", "alice@x.io"), person("Boom", null)), NOW);
        } catch (RuntimeException expected) {
            // the failure must surface; what matters is that nothing was written
        }

        assertThat(meetingCount()).isZero();
        assertThat(count("people")).isZero();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void createManualFailureLeavesPreExistingPeopleAndMeetingsUntouched() {
        calendar.resolvePerson("Old", "old@x.io", 10);
        meeting("pre", "pre", 1L, 1, null, null);
        installBoomTrigger();

        try {
            store.createManual("m1", "t", List.of(person("New", "new@x.io"), person("Boom", null)), NOW);
        } catch (RuntimeException expected) {
            // ignored
        }

        assertThat(allPeopleNames()).containsExactly("Old");
        assertThat(meetingCount()).isEqualTo(1);
    }

    @Test
    void createManualWorksAgainAfterAFailedAttempt() {
        installBoomTrigger();
        try {
            store.createManual("m1", "t", List.of(person("Boom", null)), NOW);
        } catch (RuntimeException expected) {
            // ignored
        }
        dropBoomTrigger();

        store.createManual("m1", "t", List.of(person("Alice", null), person("Boom", null)), NOW);

        assertThat(participantNames("m1")).containsExactly("Alice", "Boom");
    }

    // ---- createFromEvent ----

    @Test
    void createFromEventCreatesAMeetingWithAllAttendees() {
        eventWithAttendees("e1", "Weekly sync", List.of(new Attendee("Ann", "ann@x.io"), new Attendee(null, "bob@x.io"),
                new Attendee("Cy", null)));

        Optional<DraftResult> result = store.createFromEvent("e1", "m1", NOW);

        assertThat(result).contains(new DraftResult("m1", true));
        var info = store.find("m1").orElseThrow();
        assertThat(info.title()).isEqualTo("Weekly sync");
        assertThat(info.status()).isEqualTo("draft");
        assertThat(info.calendarEventId()).isEqualTo("e1");
        assertThat(personIds("m1")).hasSize(3);
        assertThat(count("people")).isEqualTo(3);
    }

    @Test
    void createFromEventStripsAndCutsTheTitleTo500Characters() {
        eventWithAttendees("e1", "   " + "x".repeat(600) + "   ", List.of());
        store.createFromEvent("e1", "m1", NOW);
        assertThat(store.find("m1").orElseThrow().title()).isEqualTo("x".repeat(500));
    }

    @Test
    void createFromEventKeepsAShortTitleStripped() {
        eventWithAttendees("e1", "  Standup \n", List.of());
        store.createFromEvent("e1", "m1", NOW);
        assertThat(store.find("m1").orElseThrow().title()).isEqualTo("Standup");
    }

    @Test
    void createFromEventWithoutAttendeesCreatesNoPeople() {
        eventWithAttendees("e1", "t", List.of());
        assertThat(store.createFromEvent("e1", "m1", NOW)).contains(new DraftResult("m1", true));
        assertThat(count("people")).isZero();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void createFromEventSkipsAttendeesWithNothingUsable() {
        eventWithAttendees("e1", "t", List.of(new Attendee(null, null), new Attendee(" ", "no-at-sign"), new Attendee("Ann", null)));
        store.createFromEvent("e1", "m1", NOW);
        assertThat(participantNames("m1")).containsExactly("Ann");
    }

    @Test
    void createFromEventForAnUnknownEventIsEmptyAndWritesNothing() {
        assertThat(store.createFromEvent("nope", "m1", NOW)).isEmpty();
        assertThat(meetingCount()).isZero();
        assertThat(count("people")).isZero();
    }

    @Test
    void createFromEventReturnsTheExistingMeetingWithoutDuplicating() {
        eventWithAttendees("e1", "t", List.of(new Attendee("Ann", "ann@x.io")));
        store.createFromEvent("e1", "m1", NOW);

        Optional<DraftResult> again = store.createFromEvent("e1", "m2", NOW + 1);

        assertThat(again).contains(new DraftResult("m1", false));
        assertThat(meetingCount()).isEqualTo(1);
        assertThat(store.find("m2")).isEmpty();
        assertThat(count("people")).isEqualTo(1);
        assertThat(count("meeting_participants")).isEqualTo(1);
    }

    @Test
    void createFromEventIgnoresADeletedMeetingForTheEvent() {
        eventWithAttendees("e1", "t", List.of());
        meeting("dead", "t", null, 1, "e1", 9L);

        assertThat(store.createFromEvent("e1", "m2", NOW)).contains(new DraftResult("m2", true));
        assertThat(store.find("m2").orElseThrow().calendarEventId()).isEqualTo("e1");
    }

    @Test
    void createFromEventReturnsAnExistingNonDraftMeeting() {
        eventWithAttendees("e1", "t", List.of());
        meeting("m-done", "t", 1L, 1, "e1", null);
        assertThat(store.createFromEvent("e1", "m2", NOW)).contains(new DraftResult("m-done", false));
        assertThat(meetingCount()).isEqualTo(1);
    }

    @Test
    void createFromEventRollsBackWhenAnAttendeeFails() {
        eventWithAttendees("e1", "t", List.of(new Attendee("Alice", "alice@x.io"), new Attendee("Boom", null)));
        installBoomTrigger();

        try {
            store.createFromEvent("e1", "m1", NOW);
        } catch (RuntimeException expected) {
            // ignored
        }

        assertThat(meetingCount()).isZero();
        assertThat(count("people")).isZero();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void createFromEventRetryAfterAFailureCreatesTheFullMeeting() {
        eventWithAttendees("e1", "t", List.of(new Attendee("Alice", "alice@x.io"), new Attendee("Boom", null),
                new Attendee("Cy", "cy@x.io")));
        installBoomTrigger();
        try {
            store.createFromEvent("e1", "m1", NOW);
        } catch (RuntimeException expected) {
            // ignored
        }
        dropBoomTrigger();

        Optional<DraftResult> retry = store.createFromEvent("e1", "m2", NOW + 1);

        assertThat(retry).contains(new DraftResult("m2", true));
        assertThat(participantNames("m2")).containsExactly("Alice", "Boom", "Cy");
        assertThat(meetingCount()).isEqualTo(1);
        assertThat(count("people")).isEqualTo(3);
    }

    @Test
    void createFromEventSucceededOnceThenReportsNotCreatedWithTheSameId() {
        eventWithAttendees("e1", "t", List.of(new Attendee("Ann", "ann@x.io")));
        DraftResult first = store.createFromEvent("e1", "m1", NOW).orElseThrow();
        DraftResult second = store.createFromEvent("e1", "m-other", NOW + 5).orElseThrow();

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(meetingCount()).isEqualTo(1);
    }

    // ---- replaceParticipants ----

    @Test
    void replaceParticipantsReplacesTheLinksExactly() {
        store.createManual("m1", "t", List.of(person("Ann", "ann@x.io"), person("Bob", null)), NOW);

        boolean changed = store.replaceParticipants("m1", List.of(person("Bob", null), person("Cy", "cy@x.io")), NOW + 1);

        assertThat(changed).isTrue();
        assertThat(participantNames("m1")).containsExactly("Bob", "Cy");
        assertThat(count("people")).isEqualTo(3); // Ann stays in the people table, only unlinked
    }

    @Test
    void replaceParticipantsWithAnEmptyListClearsTheLinks() {
        store.createManual("m1", "t", List.of(person("Ann", null)), NOW);
        assertThat(store.replaceParticipants("m1", List.of(), NOW + 1)).isTrue();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void replaceParticipantsSkipsUnusableEntries() {
        store.createManual("m1", "t", List.of(person("Ann", null)), NOW);
        assertThat(store.replaceParticipants("m1", List.of(person(null, null), person("", "bad"), person("Dee", null)), NOW + 1)).isTrue();
        assertThat(participantNames("m1")).containsExactly("Dee");
    }

    @Test
    void replaceParticipantsOfAnUnknownMeetingIsFalseAndCreatesNoPeople() {
        assertThat(store.replaceParticipants("nope", List.of(person("Ann", "ann@x.io")), NOW)).isFalse();
        assertThat(count("people")).isZero();
        assertThat(count("meeting_participants")).isZero();
    }

    @Test
    void replaceParticipantsOfADeletedMeetingIsFalseAndChangesNothing() {
        meeting("gone", "t", 1L, 1, null, 9L);
        var ann = calendar.resolvePerson("Ann", null, 10).orElseThrow();
        calendar.replaceParticipants("gone", List.of(ann.id()));

        assertThat(store.replaceParticipants("gone", List.of(person("New", "new@x.io")), NOW)).isFalse();

        assertThat(personIds("gone")).containsExactly(ann.id());
        assertThat(allPeopleNames()).containsExactly("Ann");
    }

    @Test
    void replaceParticipantsFailureRollsBackPeopleAndLinks() {
        store.createManual("m1", "t", List.of(person("Ann", "ann@x.io")), NOW);
        List<String> before = personIds("m1");
        List<String> peopleBefore = allPeopleNames();
        installBoomTrigger();

        try {
            store.replaceParticipants("m1", List.of(person("Fresh", "fresh@x.io"), person("Boom", null)), NOW + 1);
        } catch (RuntimeException expected) {
            // ignored
        }

        assertThat(personIds("m1")).isEqualTo(before);
        assertThat(allPeopleNames()).isEqualTo(peopleBefore);
        assertThat(participantNames("m1")).containsExactly("Ann");
    }

    @Test
    void replaceParticipantsWorksAfterTheFailureIsRemoved() {
        store.createManual("m1", "t", List.of(person("Ann", null)), NOW);
        installBoomTrigger();
        try {
            store.replaceParticipants("m1", List.of(person("Boom", null)), NOW + 1);
        } catch (RuntimeException expected) {
            // ignored
        }
        dropBoomTrigger();

        assertThat(store.replaceParticipants("m1", List.of(person("Boom", null)), NOW + 2)).isTrue();
        assertThat(participantNames("m1")).containsExactly("Boom");
    }

    // ---- series matching across sources ----

    @Test
    void agentEventsSharingAUidShareASeriesUidAndMatchAsSeries() {
        event("e1", "agent", "uid-1", 100);
        event("e2", "agent", "uid-1#20260102", 200);
        meeting("m1", "Alpha", null, 1, "e1", null);
        meeting("m2", "Beta", null, 1, "e2", null);

        var subject = store.subject("m2").orElseThrow();
        List<Candidate> candidates = store.candidates("m2", 10_000, 10);

        assertThat(subject.seriesUid()).isNotNull();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).seriesUid()).isNotNull().isEqualTo(subject.seriesUid());
        var matches = BriefMatcher.rank(subject, candidates);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).reason()).isEqualTo(BriefMatcher.Reason.SERIES);
    }

    @Test
    void nonIcsSeriesUidIsPrefixedWithTheSourceAndANulSeparator() {
        event("e1", "agent", "uid-1#20260102", 100);
        meeting("m1", "t", null, 1, "e1", null);
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isEqualTo("agent\u0000uid-1");
    }

    @Test
    void anAgentRowAndAnIcsRowWithTheSameUidDoNotShareASeriesUid() {
        event("e-agent", "agent", "same-uid", 100);
        event("e-ics", "ics", "same-uid", 200);
        meeting("m-agent", "Alpha", null, 1, "e-agent", null);
        meeting("m-ics", "Beta", null, 1, "e-ics", null);

        var subject = store.subject("m-ics").orElseThrow();
        Candidate other = store.candidates("m-ics", 10_000, 10).get(0);

        assertThat(subject.seriesUid()).isEqualTo("same-uid");
        assertThat(other.seriesUid()).isNotNull().isNotEqualTo(subject.seriesUid());
        assertThat(BriefMatcher.rank(subject, List.of(other))).isEmpty();
    }

    @Test
    void differentNonIcsSourcesDoNotShareASeriesUid() {
        event("e-agent", "agent", "same-uid", 100);
        event("e-manual", "manual", "same-uid", 200);
        meeting("m-agent", "Alpha", null, 1, "e-agent", null);
        meeting("m-manual", "Beta", null, 1, "e-manual", null);

        var subject = store.subject("m-manual").orElseThrow();
        Candidate other = store.candidates("m-manual", 10_000, 10).get(0);

        assertThat(subject.seriesUid()).isNotNull();
        assertThat(other.seriesUid()).isNotNull().isNotEqualTo(subject.seriesUid());
        assertThat(BriefMatcher.rank(subject, List.of(other))).isEmpty();
    }

    @Test
    void nonIcsEventWithoutExternalIdHasNoSeriesUid() {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES ('e1','agent',NULL,'ev',1,2,1)").update());
        meeting("m1", "t", null, 1, "e1", null);
        assertThat(store.subject("m1").orElseThrow().seriesUid()).isNull();
        assertThat(store.candidates("x", 10_000, 10).get(0).seriesUid()).isNull();
    }

    @Test
    void icsSeriesUidBehaviourIsUnchanged() {
        event("e1", "ics", "uid-abc@host#20260101T090000Z", 100);
        event("e2", "ics", "uid-abc@host#20260108T090000Z", 200);
        meeting("m1", "t", null, 1, "e1", null);
        meeting("m2", "t2", null, 1, "e2", null);

        assertThat(store.subject("m2").orElseThrow().seriesUid()).isEqualTo("uid-abc@host");
        assertThat(store.candidates("m2", 10_000, 10).get(0).seriesUid()).isEqualTo("uid-abc@host");
    }

    // ---- candidates paging ----

    @Test
    void candidatesWithOffsetSkipTheFirstRowsInTheSameOrder() {
        for (int i = 1; i <= 8; i++) meeting("m" + i, "t", i * 100L, 1, null, null);
        assertThat(ids(store.candidates("x", 10_000, 3, 2))).containsExactly("m6", "m5", "m4");
        assertThat(ids(store.candidates("x", 10_000, 3, 0))).isEqualTo(ids(store.candidates("x", 10_000, 3)));
    }

    @Test
    void candidatesOffsetBeyondTheDataIsEmpty() {
        meeting("a", "t", 100L, 1, null, null);
        assertThat(store.candidates("x", 10_000, 10, 5)).isEmpty();
    }

    @Test
    void candidatePagesOf500TogetherEqualOneQueryOf1000() {
        database.write(j -> {
            for (int i = 0; i < 1200; i++) {
                // ties on the date exercise the id tie-break
                j.sql("INSERT INTO meetings(id,title,status,started_at,created_at,updated_at) VALUES (?,?,'done',?,1,1)")
                        .params(String.format("m%04d", i), "t", 1_000L + (i / 3)).update();
            }
            return null;
        });

        List<Candidate> first = store.candidates("x", 1_000_000, 500, 0);
        List<Candidate> second = store.candidates("x", 1_000_000, 500, 500);
        List<Candidate> whole = store.candidates("x", 1_000_000, 1000);

        assertThat(first).hasSize(500);
        assertThat(second).hasSize(500);
        assertThat(whole).hasSize(1000);
        List<String> paged = new ArrayList<>(ids(first));
        paged.addAll(ids(second));
        assertThat(paged).isEqualTo(ids(whole));
        assertThat(paged).doesNotHaveDuplicates();
    }

    @Test
    void candidateOffsetStillExcludesTheGivenAndDeletedMeetings() {
        meeting("self", "t", 900L, 1, null, null);
        meeting("dead", "t", 850L, 1, null, 5L);
        meeting("a", "t", 800L, 1, null, null);
        meeting("b", "t", 700L, 1, null, null);
        meeting("c", "t", 600L, 1, null, null);
        assertThat(ids(store.candidates("self", 10_000, 10, 1))).containsExactly("b", "c");
    }
}
