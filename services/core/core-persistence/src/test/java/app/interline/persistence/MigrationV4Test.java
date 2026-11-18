package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static app.interline.persistence.SqlSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** M3 persistence: the V4 migration (docs/ARCHITECTURE.md section 13: calendar_events, people, meeting_participants). */
class MigrationV4Test {
    private static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/migration-m1",
            "classpath:db/migration-m2", "classpath:db/migration-m3"};
    private static final String[] LOCATIONS_V3 = {"classpath:db/migration", "classpath:db/migration-m1",
            "classpath:db/migration-m2"};

    @TempDir
    Path directory;

    private String url;

    @BeforeEach
    void migrateFreshDatabase() {
        url = "jdbc:sqlite:" + directory.resolve("interline.db");
        Flyway.configure().dataSource(url, null, null).locations(LOCATIONS).load().migrate();
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void event(Connection c, String id, String source, String externalId) {
        execute(c, "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES (?,?,?,'T',1000,2000)",
                id, source, externalId);
    }

    private static void person(Connection c, String id, String email) {
        execute(c, "INSERT INTO people(id,display_name,email,created_at) VALUES (?, 'P', ?, 1)", id, email);
    }

    @Test
    void AC1_freshDatabaseAppliesFlywayVersionsOneToFour() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4");
            assertThat(strings(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN "
                    + "('calendar_events','people','meeting_participants')"))
                    .containsExactlyInAnyOrder("calendar_events", "people", "meeting_participants");
            assertThat(strings(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void AC1_migrationV4AddsTheDocumentedColumns() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT name FROM pragma_table_info('calendar_events')"))
                    .contains("id", "source", "external_id", "title", "starts_at", "ends_at", "conference_url",
                            "attendees", "synced_at");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('people')"))
                    .contains("id", "display_name", "email", "created_at");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('meeting_participants')"))
                    .contains("meeting_id", "person_id");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('meetings')")).contains("calendar_event_id");
        }
    }

    @Test
    void AC1_startsAtIsIndexed() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT ii.name FROM pragma_index_list('calendar_events') il, "
                    + "pragma_index_info(il.name) ii WHERE il.origin = 'c' AND ii.name = 'starts_at'"))
                    .containsExactly("starts_at");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e','google','x','T',1,2)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e',NULL,'x','T',1,2)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e','ics','x',NULL,1,2)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e','ics','x','T',NULL,2)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e','ics','x','T',1,NULL)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('dup','ics','y','T',1,2)",
            "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('e2','ics','taken','T',1,2)",
            "INSERT INTO people(id,display_name,email,created_at) VALUES ('p',NULL,'a@example.com',1)",
            "INSERT INTO people(id,display_name,email,created_at) VALUES ('p','A','a@example.com',NULL)",
            "INSERT INTO people(id,display_name,email,created_at) VALUES ('p2','A','taken@example.com',1)",
            "INSERT INTO people(id,display_name,email,created_at) VALUES ('p1','A','b@example.com',1)",
            "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('missing','p1')",
            "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m1','missing')",
            "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m1','p1')",
            "UPDATE meetings SET calendar_event_id = 'missing' WHERE id = 'm1'"
    })
    void AC1_migrationV4EnforcesCheckUniqueAndForeignKeyConstraints(String statement) throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            event(c, "dup", "ics", "y");
            event(c, "taken-ev", "ics", "taken");
            person(c, "p1", "taken@example.com");
            execute(c, "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m1','p1')");
            rejectsConstraint(c, statement);
        }
    }

    @Test
    void AC1_sameExternalIdIsAllowedForDifferentSources() throws Exception {
        try (Connection c = open()) {
            event(c, "e1", "ics", "uid");
            event(c, "e2", "agent", "uid");
            event(c, "e3", "manual", "uid");
            assertThat(strings(c, "SELECT count(*) FROM calendar_events")).containsExactly("3");
        }
    }

    @Test
    void AC1_optionalColumnsAndNullExternalIdsAreAccepted() throws Exception {
        try (Connection c = open()) {
            execute(c, "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('a','manual',NULL,'T',1,2)");
            execute(c, "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES ('b','manual',NULL,'T',1,2)");
            execute(c, "INSERT INTO people(id,display_name,email,created_at) VALUES ('p1','A',NULL,1)");
            execute(c, "INSERT INTO people(id,display_name,email,created_at) VALUES ('p2','B',NULL,1)");
            assertThat(strings(c, "SELECT conference_url FROM calendar_events WHERE id = 'a'")).containsNull();
            assertThat(strings(c, "SELECT attendees FROM calendar_events WHERE id = 'a'")).containsNull();
            assertThat(strings(c, "SELECT synced_at FROM calendar_events WHERE id = 'a'")).containsNull();
        }
    }

    @Test
    void AC1_meetingsCalendarEventIdDefaultsToNullAndAcceptsARealEvent() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings WHERE id = 'm1'")).containsNull();
            event(c, "e1", "ics", "uid");
            execute(c, "UPDATE meetings SET calendar_event_id = 'e1' WHERE id = 'm1'");
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings WHERE id = 'm1'")).containsExactly("e1");
        }
    }

    @Test
    void AC1_deletingACalendarEventSetsMeetingLinkToNullAndKeepsTheMeeting() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            meeting(c, "m2");
            event(c, "e1", "ics", "uid1");
            event(c, "e2", "ics", "uid2");
            execute(c, "UPDATE meetings SET calendar_event_id = 'e1' WHERE id = 'm1'");
            execute(c, "UPDATE meetings SET calendar_event_id = 'e2' WHERE id = 'm2'");
            execute(c, "DELETE FROM calendar_events WHERE id = 'e1'");
            assertThat(strings(c, "SELECT id FROM meetings ORDER BY id")).containsExactly("m1", "m2");
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings WHERE id = 'm1'")).containsNull();
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings WHERE id = 'm2'")).containsExactly("e2");
        }
    }

    @Test
    void AC1_participantsCascadeWithTheirMeetingOrPerson() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            meeting(c, "m2");
            person(c, "p1", "a@example.com");
            person(c, "p2", "b@example.com");
            execute(c, "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m1','p1')");
            execute(c, "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m1','p2')");
            execute(c, "INSERT INTO meeting_participants(meeting_id,person_id) VALUES ('m2','p1')");
            execute(c, "DELETE FROM people WHERE id = 'p2'");
            assertThat(strings(c, "SELECT meeting_id || ':' || person_id FROM meeting_participants ORDER BY 1"))
                    .containsExactly("m1:p1", "m2:p1");
            execute(c, "DELETE FROM meetings WHERE id = 'm1'");
            assertThat(strings(c, "SELECT meeting_id || ':' || person_id FROM meeting_participants"))
                    .containsExactly("m2:p1");
            assertThat(strings(c, "SELECT id FROM people")).containsExactly("p1");
            assertThat(strings(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void AC1_migrationV4UpgradesPopulatedV3DatabaseKeepingEveryRow() throws Exception {
        String upgradeUrl = "jdbc:sqlite:" + directory.resolve("populated.db");
        Flyway.configure().dataSource(upgradeUrl, null, null).locations(LOCATIONS_V3).load().migrate();
        String templatesBefore;
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3");
            meeting(c, "m1");
            meeting(c, "m2");
            segment(c, 1, "m1", "Bonjour");
            block(c, "b1", "m1", "Notes de la reunion");
            execute(c, "INSERT INTO settings(key,value,updated_at) VALUES ('language','fr',100)");
            templatesBefore = String.join(",", strings(c, "SELECT id || ':' || name FROM templates ORDER BY id"));
            assertThat(templatesBefore).isNotEmpty();
        }
        Flyway.configure().dataSource(upgradeUrl, null, null).locations(LOCATIONS).load().migrate();
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            execute(c, "PRAGMA foreign_keys = ON");
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4");
            assertThat(strings(c, "SELECT id FROM meetings ORDER BY id")).containsExactly("m1", "m2");
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings ORDER BY id")).containsOnlyNulls();
            assertThat(strings(c, "SELECT text FROM transcript_segments")).containsExactly("Bonjour");
            assertThat(strings(c, "SELECT id FROM note_blocks")).containsExactly("b1");
            assertThat(strings(c, "SELECT value FROM settings WHERE key = 'language'")).containsExactly("fr");
            assertThat(String.join(",", strings(c, "SELECT id || ':' || name FROM templates ORDER BY id")))
                    .isEqualTo(templatesBefore);
            assertThat(strings(c, "SELECT count(*) FROM calendar_events")).containsExactly("0");
            assertThat(strings(c, "SELECT count(*) FROM people")).containsExactly("0");
            assertThat(strings(c, "SELECT count(*) FROM meeting_participants")).containsExactly("0");
            // the FTS triggers of V3 still work after the upgrade
            assertThat(strings(c, "SELECT b.id FROM blocks_fts f JOIN note_blocks b ON b.rowid_fts = f.rowid "
                    + "WHERE blocks_fts MATCH 'reunion'")).containsExactly("b1");
            assertThat(strings(c, "PRAGMA foreign_key_check")).isEmpty();
            // and the new link works on the upgraded data
            event(c, "e1", "ics", "uid");
            execute(c, "UPDATE meetings SET calendar_event_id = 'e1' WHERE id = 'm1'");
            execute(c, "DELETE FROM calendar_events WHERE id = 'e1'");
            assertThat(strings(c, "SELECT calendar_event_id FROM meetings WHERE id = 'm1'")).containsNull();
        }
    }

    @Test
    void AC1_migrateIsIdempotentOnAnAlreadyUpgradedDatabase() throws Exception {
        Flyway.configure().dataSource(url, null, null).locations(LOCATIONS).load().migrate();
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4");
        }
    }
}
