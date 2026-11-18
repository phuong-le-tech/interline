package app.interline.persistence;

import app.interline.persistence.CalendarStore.PersonRow;
import app.interline.persistence.MeetingStore.PersonInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2 review round 2 persistence: replaceParticipantsAndList returns the list it wrote, from inside its transaction. */
@Timeout(120)
class MeetingStoreRound2Test {
    private static final long NOW = 5_000_000;

    @TempDir
    Path directory;

    private SqliteDatabase database;
    private MeetingStore store;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new MeetingStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    // ---- helpers ----

    private static PersonInput person(String name, String email) {
        return new PersonInput(name, email);
    }

    private void meeting(String id, Long deletedAt) {
        database.write(j -> j.sql("INSERT INTO meetings(id,title,status,created_at,updated_at,deleted_at) VALUES (?,?,'done',?,?,?)")
                .params(id, "t", NOW, NOW, deletedAt).update());
    }

    private void installBoomTrigger() {
        database.write(j -> j.sql("CREATE TRIGGER boom BEFORE INSERT ON meeting_participants "
                + "WHEN EXISTS (SELECT 1 FROM people WHERE id = NEW.person_id AND display_name = 'Boom') "
                + "BEGIN SELECT RAISE(ABORT, 'boom'); END").update());
    }

    private List<String> personIds(String meetingId) {
        return database.read(j -> j.sql("SELECT person_id FROM meeting_participants WHERE meeting_id = ? ORDER BY person_id")
                .param(meetingId).query(String.class).list());
    }

    private List<String> allPeopleNames() {
        return database.read(j -> j.sql("SELECT display_name FROM people ORDER BY display_name").query(String.class).list());
    }

    private static List<String> emails(List<PersonRow> rows) {
        return rows.stream().map(PersonRow::email).toList();
    }

    private static List<String> names(List<PersonRow> rows) {
        return rows.stream().map(PersonRow::displayName).toList();
    }

    // ---- result content ----

    @Test
    void returnsExactlyTheListThatWasWritten() {
        meeting("m1", null);

        Optional<List<PersonRow>> result = store.replaceParticipantsAndList("m1",
                List.of(person("Ann", "ann@x.io"), person("Bob", "bob@x.io")), NOW);

        assertThat(result).isPresent();
        assertThat(emails(result.get())).containsExactlyInAnyOrder("ann@x.io", "bob@x.io");
        assertThat(names(result.get())).containsExactlyInAnyOrder("Ann", "Bob");
        assertThat(result.get().stream().map(PersonRow::id).toList()).containsExactlyInAnyOrderElementsOf(personIds("m1"));
    }

    @Test
    void returnsEmailLessPeopleToo() {
        meeting("m1", null);

        List<PersonRow> result = store.replaceParticipantsAndList("m1",
                List.of(person("Ann", "ann@x.io"), person("Nomail", null)), NOW).orElseThrow();

        assertThat(names(result)).containsExactlyInAnyOrder("Ann", "Nomail");
        assertThat(result.stream().filter(p -> p.displayName().equals("Nomail")).findFirst().orElseThrow().email()).isNull();
    }

    @Test
    void returnsOnlyTheNewListNotTheOldOne() {
        meeting("m1", null);
        store.replaceParticipantsAndList("m1", List.of(person("Old", "old@x.io")), NOW);

        List<PersonRow> result = store.replaceParticipantsAndList("m1", List.of(person("New", "new@x.io")), NOW + 1).orElseThrow();

        assertThat(emails(result)).containsExactly("new@x.io");
    }

    @Test
    void anEmptyListClearsTheLinksAndReturnsAnEmptyPresentList() {
        meeting("m1", null);
        store.replaceParticipantsAndList("m1", List.of(person("Ann", "ann@x.io")), NOW);

        Optional<List<PersonRow>> result = store.replaceParticipantsAndList("m1", List.of(), NOW + 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
        assertThat(personIds("m1")).isEmpty();
    }

    @Test
    void theResultIsOrderedByCreatedAtThenInsertion() {
        meeting("m1", null);

        List<PersonRow> result = store.replaceParticipantsAndList("m1",
                List.of(person("Zed", "zed@x.io"), person("Amy", "amy@x.io"), person("Max", null)), NOW).orElseThrow();

        assertThat(names(result)).containsExactly("Zed", "Amy", "Max");
    }

    @Test
    void theResultMatchesThePersistedLinksOfTheOldBooleanApi() {
        meeting("m1", null);
        assertThat(store.replaceParticipants("m1", List.of(person("Ann", "ann@x.io")), NOW)).isTrue();
        List<PersonRow> result = store.replaceParticipantsAndList("m1", List.of(person("Ann", "ann@x.io"), person("Bob", null)), NOW + 1)
                .orElseThrow();

        assertThat(result.stream().map(PersonRow::id).toList()).containsExactlyInAnyOrderElementsOf(personIds("m1"));
    }

    // ---- unknown and deleted ----

    @Test
    void anUnknownMeetingIsEmptyAndCreatesNoPeople() {
        assertThat(store.replaceParticipantsAndList("nope", List.of(person("Ann", "ann@x.io")), NOW)).isEmpty();

        assertThat(allPeopleNames()).isEmpty();
    }

    @Test
    void aDeletedMeetingIsEmptyAndChangesNothing() {
        meeting("m1", null);
        store.replaceParticipantsAndList("m1", List.of(person("Ann", "ann@x.io")), NOW);
        List<String> before = personIds("m1");
        database.write(j -> j.sql("UPDATE meetings SET deleted_at = ? WHERE id = 'm1'").param(NOW + 1).update());

        assertThat(store.replaceParticipantsAndList("m1", List.of(person("Eve", "eve@x.io")), NOW + 2)).isEmpty();

        assertThat(personIds("m1")).isEqualTo(before);
        assertThat(allPeopleNames()).containsExactly("Ann");
    }

    // ---- failure rollback ----

    @Test
    void aFailureRollsBackPeopleAndLinks() {
        meeting("m1", null);
        store.replaceParticipantsAndList("m1", List.of(person("Ann", "ann@x.io")), NOW);
        List<String> before = personIds("m1");
        List<String> peopleBefore = allPeopleNames();
        installBoomTrigger();

        try {
            store.replaceParticipantsAndList("m1", List.of(person("Fresh", "fresh@x.io"), person("Boom", null)), NOW + 1);
        } catch (RuntimeException expected) {
            // ignored
        }

        assertThat(personIds("m1")).isEqualTo(before);
        assertThat(allPeopleNames()).isEqualTo(peopleBefore);
    }

    @Test
    void aFailureThrowsInsteadOfReturningAList() {
        meeting("m1", null);
        installBoomTrigger();

        boolean threw = false;
        try {
            store.replaceParticipantsAndList("m1", List.of(person("Boom", null)), NOW + 1);
        } catch (RuntimeException expected) {
            threw = true;
        }

        assertThat(threw).isTrue();
    }

    // ---- concurrency ----

    @Test
    void concurrentCallsOnTheSameMeetingEachGetBackExactlyTheirOwnList() throws Exception {
        int threads = 16;
        int rounds = 6;
        meeting("shared", null);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Set<String>>> futures = new ArrayList<>();
                List<Set<String>> expected = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    int r = round;
                    int size = 2 + (t % 2);
                    List<PersonInput> input = new ArrayList<>();
                    Set<String> mine = new HashSet<>();
                    for (int k = 0; k < size; k++) {
                        String email = "r" + r + "t" + thread + "k" + k + "@x.io";
                        input.add(person("P" + r + "-" + thread + "-" + k, email));
                        mine.add(email);
                    }
                    expected.add(mine);
                    futures.add(pool.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        List<PersonRow> rows = store.replaceParticipantsAndList("shared", input, NOW + r * 1000L + thread).orElseThrow();
                        return new HashSet<>(emails(rows));
                    }));
                }
                for (int t = 0; t < threads; t++) {
                    assertThat(futures.get(t).get(60, TimeUnit.SECONDS))
                            .as("round %d thread %d", round, t)
                            .isEqualTo(expected.get(t));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
