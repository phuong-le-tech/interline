package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore.EventRow;
import app.interline.persistence.CalendarStore.PersonRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 persistence: CalendarStore.findEvent, resolvePerson, replaceParticipants. */
class CalendarStoreMeetingsTest {
    private static final long NOW = 5_000_000;

    @TempDir
    Path directory;

    private SqliteDatabase database;
    private CalendarStore store;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new CalendarStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void insertMeeting(String id) {
        database.write(j -> j.sql("INSERT INTO meetings(id,status,created_at,updated_at) VALUES (?, 'draft', 1, 1)")
                .param(id).update());
    }

    private long count(String table) {
        return database.read(j -> j.sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }

    private List<String> participantIds(String meetingId) {
        return database.read(j -> j.sql("SELECT person_id FROM meeting_participants WHERE meeting_id = ? ORDER BY person_id")
                .param(meetingId).query(String.class).list());
    }

    private String insertPerson(String id, String name, String email, long createdAt) {
        database.write(j -> j.sql("INSERT INTO people(id,display_name,email,created_at) VALUES (?,?,?,?)")
                .params(id, name, email, createdAt).update());
        return id;
    }

    // ---- findEvent ----

    @Test
    void findEventReturnsTheStoredRowWithAttendees() {
        store.replaceIcsWindow(List.of(new CalendarEvent("uid-1", "Standup", 1_000, 2_000, "https://meet/x",
                List.of(new Attendee("Ann", "ann@x.io"), new Attendee(null, "bob@x.io")))), 0, 10_000, NOW);
        String id = database.read(j -> j.sql("SELECT id FROM calendar_events WHERE external_id = 'uid-1'")
                .query(String.class).single());

        Optional<EventRow> found = store.findEvent(id);

        assertThat(found).isPresent();
        EventRow row = found.get();
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.source()).isEqualTo("ics");
        assertThat(row.externalId()).isEqualTo("uid-1");
        assertThat(row.title()).isEqualTo("Standup");
        assertThat(row.startsAt()).isEqualTo(1_000);
        assertThat(row.endsAt()).isEqualTo(2_000);
        assertThat(row.conferenceUrl()).isEqualTo("https://meet/x");
        assertThat(row.attendees()).containsExactly(new Attendee("Ann", "ann@x.io"), new Attendee(null, "bob@x.io"));
    }

    @Test
    void findEventFindsNonIcsSourcesToo() {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES ('e1','manual','g1','G',1,2,1)").update());
        assertThat(store.findEvent("e1")).map(EventRow::source).contains("manual");
    }

    @Test
    void findEventUnknownIdIsEmpty() {
        assertThat(store.findEvent("nope")).isEmpty();
    }

    @Test
    void findEventOnAnEmptyStoreIsEmpty() {
        assertThat(store.findEvent("")).isEmpty();
    }

    // ---- resolvePerson: nothing to resolve ----

    @Test
    void blankNameAndNoEmailIsEmpty() {
        assertThat(store.resolvePerson("   ", null, NOW)).isEmpty();
        assertThat(store.resolvePerson("", null, NOW)).isEmpty();
        assertThat(store.resolvePerson(null, null, NOW)).isEmpty();
        assertThat(store.resolvePerson(null, "   ", NOW)).isEmpty();
        assertThat(store.resolvePerson(" ", "", NOW)).isEmpty();
        assertThat(count("people")).isZero();
    }

    // ---- resolvePerson: with email ----

    @Test
    void withAnEmailItCreatesAPersonWithTheLowerCasedEmail() {
        PersonRow p = store.resolvePerson("Ann", "  Ann@X.IO ", NOW).orElseThrow();
        assertThat(p.email()).isEqualTo("ann@x.io");
        assertThat(p.displayName()).isEqualTo("Ann");
        assertThat(p.createdAt()).isEqualTo(NOW);
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void withAnEmailAndNoNameItStillCreatesAPerson() {
        PersonRow p = store.resolvePerson(null, "bob@x.io", NOW).orElseThrow();
        assertThat(p.email()).isEqualTo("bob@x.io");
        assertThat(p.displayName()).isEmpty();
    }

    @Test
    void sameEmailInAnotherCaseReusesThePerson() {
        PersonRow first = store.resolvePerson("Ann", "ann@x.io", NOW).orElseThrow();
        PersonRow second = store.resolvePerson("Ann", "ANN@X.IO", NOW + 1).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void anExistingNonEmptyNameIsKept() {
        PersonRow first = store.resolvePerson("Ann", "ann@x.io", NOW).orElseThrow();
        PersonRow second = store.resolvePerson("Anna Other", "ann@x.io", NOW + 1).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.displayName()).isEqualTo("Ann");
        assertThat(store.listPeople()).extracting(PersonRow::displayName).containsExactly("Ann");
    }

    @Test
    void aBlankExistingNameIsFilled() {
        store.resolvePerson(null, "ann@x.io", NOW);
        PersonRow second = store.resolvePerson("Ann", "ann@x.io", NOW + 1).orElseThrow();
        assertThat(second.displayName()).isEqualTo("Ann");
        assertThat(store.listPeople()).extracting(PersonRow::displayName).containsExactly("Ann");
    }

    @Test
    void withAnEmailTheNameLessMatchingRuleIsNotUsed() {
        PersonRow nameOnly = store.resolvePerson("Ann", null, NOW).orElseThrow();
        PersonRow withEmail = store.resolvePerson("Ann", "ann@x.io", NOW + 1).orElseThrow();
        assertThat(withEmail.id()).isNotEqualTo(nameOnly.id());
        assertThat(count("people")).isEqualTo(2);
    }

    // ---- resolvePerson: email-less ----

    @Test
    void nameOnlyCreatesAnEmailLessPerson() {
        PersonRow p = store.resolvePerson("Carol", null, NOW).orElseThrow();
        assertThat(p.email()).isNull();
        assertThat(p.displayName()).isEqualTo("Carol");
        assertThat(p.createdAt()).isEqualTo(NOW);
        Long nullEmailRows = database.read(j -> j.sql("SELECT count(*) FROM people WHERE id = ? AND email IS NULL")
                .param(p.id()).query(Long.class).single());
        assertThat(nullEmailRows).isEqualTo(1L);
    }

    @Test
    void nameOnlyWithABlankEmailAlsoCreatesAnEmailLessPerson() {
        PersonRow p = store.resolvePerson("Carol", "   ", NOW).orElseThrow();
        assertThat(p.email()).isNull();
    }

    @Test
    void nameOnlyTrimsTheNameWhenCreating() {
        PersonRow p = store.resolvePerson("  Carol  ", null, NOW).orElseThrow();
        assertThat(p.displayName()).isEqualTo("Carol");
    }

    @Test
    void sameNameWithDifferentCaseReusesTheEmailLessPerson() {
        PersonRow first = store.resolvePerson("Carol Smith", null, NOW).orElseThrow();
        PersonRow second = store.resolvePerson("  carol SMITH ", null, NOW + 1).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("people")).isEqualTo(1);
        assertThat(second.displayName()).isEqualTo("Carol Smith");
    }

    @Test
    void differentNamesCreateDifferentEmailLessPeople() {
        PersonRow a = store.resolvePerson("Carol", null, NOW).orElseThrow();
        PersonRow b = store.resolvePerson("Dave", null, NOW + 1).orElseThrow();
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(count("people")).isEqualTo(2);
    }

    @Test
    void nameOnlyNeverReusesAPersonThatHasAnEmail() {
        PersonRow withEmail = store.resolvePerson("Carol", "carol@x.io", NOW).orElseThrow();
        PersonRow nameOnly = store.resolvePerson("Carol", null, NOW + 1).orElseThrow();
        assertThat(nameOnly.id()).isNotEqualTo(withEmail.id());
        assertThat(nameOnly.email()).isNull();
        assertThat(count("people")).isEqualTo(2);
    }

    @Test
    void nameOnlyReusesAnEmailLessPersonInsertedDirectly() {
        insertPerson("p-manual", "Eve", null, 10);
        PersonRow p = store.resolvePerson("EVE", null, NOW).orElseThrow();
        assertThat(p.id()).isEqualTo("p-manual");
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void multipleEmailLessPeopleCoexistDespiteTheUniqueEmailColumn() {
        store.resolvePerson("Carol", null, NOW);
        store.resolvePerson("Dave", null, NOW + 1);
        store.resolvePerson("Eve", null, NOW + 2);
        assertThat(count("people")).isEqualTo(3);
    }

    // ---- listPeople / listParticipants with email-less people ----

    @Test
    void listPeopleIncludesEmailLessPeopleInCreationOrder() {
        store.resolvePerson("Carol", null, 10);
        store.resolvePerson("Ann", "ann@x.io", 20);
        store.resolvePerson("Dave", null, 30);
        List<PersonRow> people = store.listPeople();
        assertThat(people).extracting(PersonRow::displayName).containsExactly("Carol", "Ann", "Dave");
        assertThat(people).extracting(PersonRow::email).containsExactly(null, "ann@x.io", null);
    }

    @Test
    void listParticipantsReturnsEmailLessPeople() {
        insertMeeting("m1");
        PersonRow carol = store.resolvePerson("Carol", null, 10).orElseThrow();
        store.addParticipant("m1", carol.id());
        assertThat(store.listParticipants("m1")).containsExactly(carol);
    }

    // ---- replaceParticipants ----

    @Test
    void replaceParticipantsSetsTheListOnAnEmptyMeeting() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        PersonRow b = store.resolvePerson("B", "b@x.io", 20).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id(), b.id()));
        assertThat(store.listParticipants("m1")).extracting(PersonRow::id).containsExactly(a.id(), b.id());
    }

    @Test
    void replaceParticipantsReplacesTheWholeSet() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        PersonRow b = store.resolvePerson("B", "b@x.io", 20).orElseThrow();
        PersonRow c = store.resolvePerson("C", "c@x.io", 30).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id(), b.id()));
        store.replaceParticipants("m1", List.of(b.id(), c.id()));
        assertThat(participantIds("m1")).containsExactlyInAnyOrder(b.id(), c.id());
    }

    @Test
    void replaceParticipantsWithAnEmptyListClearsThem() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id()));
        store.replaceParticipants("m1", List.of());
        assertThat(store.listParticipants("m1")).isEmpty();
        assertThat(count("people")).isEqualTo(1);
    }

    @Test
    void replaceParticipantsIgnoresDuplicateIds() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id(), a.id(), a.id()));
        assertThat(participantIds("m1")).containsExactly(a.id());
    }

    @Test
    void replaceParticipantsTouchesOnlyTheGivenMeeting() {
        insertMeeting("m1");
        insertMeeting("m2");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        PersonRow b = store.resolvePerson("B", "b@x.io", 20).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id()));
        store.replaceParticipants("m2", List.of(b.id()));
        store.replaceParticipants("m1", List.of(b.id()));
        assertThat(participantIds("m1")).containsExactly(b.id());
        assertThat(participantIds("m2")).containsExactly(b.id());
    }

    @Test
    void replaceParticipantsKeepsThePeopleRows() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id()));
        store.replaceParticipants("m1", List.of());
        assertThat(store.listPeople()).extracting(PersonRow::id).containsExactly(a.id());
    }

    @Test
    void replaceParticipantsWithAnUnknownPersonRollsBackAndKeepsTheOldSet() {
        insertMeeting("m1");
        PersonRow a = store.resolvePerson("A", "a@x.io", 10).orElseThrow();
        store.replaceParticipants("m1", List.of(a.id()));
        try {
            store.replaceParticipants("m1", List.of("no-such-person"));
        } catch (RuntimeException expected) {
            // a foreign key failure is the expected way to refuse an unknown person
        }
        assertThat(participantIds("m1")).containsExactly(a.id());
    }
}
