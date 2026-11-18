package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC8: the ICS URL (Google private addresses embed a token) is a secret. Outside the OS secret store (here: the
 * MockAgent, reached through secret.put / secret.get) it appears nowhere: not in the database file or its WAL, not in
 * any log, not in any API response (success or error), and GET /ics-source exposes the host only.
 */
@Timeout(180)
class M3UrlSafetyTest {
    @TempDir Path data;
    private IcsStub stub;
    private ConfigurableApplicationContext context;
    private PipelineAgentProbe probe;
    private int port;
    private final List<Resp> responses = new ArrayList<>();

    @BeforeEach void start() {
        stub = new IcsStub();
        probe = new PipelineAgentProbe(data);
        context = boot(data, M1AgentProbe.launcher(data, false),
                "--logging.level.app.interline=TRACE", "--logging.level.org.springframework.web=TRACE",
                "--logging.level.org.springframework.ai=TRACE");
        port = port(context);
    }

    @AfterEach void stop() throws Exception {
        if (context.isActive()) context.close();
        probe.close();
        stub.close();
    }

    private Resp record(Resp response) {
        responses.add(response);
        return response;
    }

    private List<JsonNode> coreRequests(String type) throws IOException {
        return probe.observations().stream().filter(o -> o.path("direction").asText().equals("core"))
                .filter(o -> o.path("type").asText().equals(type)).map(o -> o.path("message")).toList();
    }

    private static void assertNoSecret(String text, String what) {
        assertThat(text).as(what).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/private/");
    }

    private String feed() {
        return calendar(
                vevent("uid-1", "Planning", NOW + HOUR, NOW + 2 * HOUR,
                        "ORGANIZER;CN=Olive Organizer:mailto:olive@example.com", "ATTENDEE;CN=Alice Example:mailto:alice@example.com"),
                vevent("uid-2", "Review", NOW + DAY, NOW + DAY + HOUR));
    }

    @Test void AC8_urlNeverLeaks_notInTheDatabaseFileTheLogsOrAnyApiResponse() throws Exception {
        String url = stub.url();
        stub.serve(feed());
        record(putSource(port, url));
        record(call(port, "GET", "/calendar/ics-source", null));
        assertThat(record(sync(port)).status()).isEqualTo(200);
        record(call(port, "GET", "/calendar/events", null));
        record(call(port, "GET", "/calendar/events?from=" + NOW + "&to=" + (NOW + DAY), null));
        // error paths that could echo the URL: a bad scheme and an oversized URL both carrying the token
        record(call(port, "PUT", "/calendar/ics-source", Map.of("url", "ftp://127.0.0.1" + IcsStub.PATH + "?" + IcsStub.QUERY)));
        record(call(port, "PUT", "/calendar/ics-source", Map.of("url", url + "&pad=" + "a".repeat(2100))));
        record(call(port, "PUT", "/calendar/ics-source", Map.of("url", "http://example.com" + IcsStub.PATH + "?" + IcsStub.QUERY)));
        record(call(port, "PUT", "/calendar/ics-source", "{\"url\": \"" + url + "\""));   // broken JSON carrying the URL
        record(call(port, "GET", "/calendar/events?from=not-a-number", null));
        // fetch failures: server error, garbage, then a vanished server
        stub.failWith(500);
        assertThat(record(sync(port)).status()).isEqualTo(502);
        byte[] garbage = new byte[8192];
        new Random(7).nextBytes(garbage);
        stub.serveBytes(garbage);
        assertThat(record(sync(port)).status()).isEqualTo(502);
        stub.serve(feed());
        record(sync(port));
        int serverPort = stub.port();
        stub.close();
        assertThat(record(sync(port)).status()).isEqualTo(502);
        assertThat(serverPort).isPositive();
        // removal
        record(call(port, "DELETE", "/calendar/ics-source", null));
        record(sync(port));
        record(call(port, "GET", "/calendar/ics-source", null));
        record(call(port, "GET", "/calendar/events", null));
        context.close();

        assertThat(stub.requestUris).as("the stub really was fetched with the full secret URL").isNotEmpty()
                .allSatisfy(uri -> assertThat(uri).contains(SECRET_PATH_TOKEN).contains(SECRET_QUERY));
        assertThat(responses).hasSizeGreaterThan(15).allSatisfy(r -> assertNoSecret(r.body(), "response body: " + r.body()));
        assertNoSecret(new String(Files.readAllBytes(data.resolve("db.sqlite")), StandardCharsets.ISO_8859_1), "db.sqlite");
        Path wal = data.resolve("db.sqlite-wal");
        if (Files.exists(wal)) assertNoSecret(new String(Files.readAllBytes(wal), StandardCharsets.ISO_8859_1), "db.sqlite-wal");
        try (Stream<Path> logs = Files.walk(data.resolve("logs"))) {
            assertThat(logs.filter(Files::isRegularFile).toList()).isNotEmpty().allSatisfy(file ->
                    assertNoSecret(Files.readString(file, StandardCharsets.ISO_8859_1), "log " + file.getFileName()));
        }
    }

    @Test void AC8_theDatabaseHoldsNoUrlAtThisPointEither_evenWhileTheCoreIsRunning() throws Exception {
        stub.serve(feed());
        record(putSource(port, stub.url()));
        assertThat(sync(port).status()).isEqualTo(200);

        for (Map<String, Object> row : AppTestSupport.rows(data, "SELECT * FROM calendar_events")) {
            assertNoSecret(row.toString(), "calendar_events row");
        }
        for (Map<String, Object> row : AppTestSupport.rows(data, "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            String table = row.get("name").toString();
            if (table.startsWith("sqlite_") || table.contains("flyway")) continue;
            for (Map<String, Object> content : AppTestSupport.rows(data, "SELECT * FROM \"" + table + "\"")) {
                assertNoSecret(content.toString(), "table " + table);
            }
        }
    }

    @Test void AC8_getIcsSourceExposesTheHostOnly() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);

        Resp response = ok(call(port, "GET", "/calendar/ics-source", null), "get ics-source");

        assertThat(response.json().path("configured").asBoolean()).isTrue();
        assertThat(response.json().path("host").asText()).isEqualTo("127.0.0.1");
        List<String> fields = new ArrayList<>();
        response.json().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsOnly("configured", "host");
        assertNoSecret(response.body(), "GET /ics-source");
        assertThat(response.body()).doesNotContain("http://").doesNotContain(String.valueOf(stub.port()) + "/");
    }

    @Test void AC8_theUrlTravelsOnlyOnTheAgentPipe_asTheSecretOfAccountCalendarIcs() throws Exception {
        String url = stub.url();
        stub.serve(feed());
        record(putSource(port, url));
        assertThat(record(sync(port)).status()).isEqualTo(200);
        assertThat(record(sync(port)).status()).isEqualTo(200);
        record(call(port, "DELETE", "/calendar/ics-source", null));
        context.close();

        List<JsonNode> puts = coreRequests("secret.put");
        assertThat(puts).singleElement().satisfies(put -> {
            assertThat(put.path("body").path("account").asText()).isEqualTo("calendar.ics");
            assertThat(put.path("body").path("value").asText()).isEqualTo(url);
        });
        assertThat(coreRequests("secret.get")).as("the URL is fetched from the secret store on every sync, not cached").isNotEmpty()
                .allSatisfy(get -> assertThat(get.path("body").path("account").asText()).isEqualTo("calendar.ics"));
        assertThat(coreRequests("secret.delete")).isNotEmpty()
                .allSatisfy(d -> assertThat(d.path("body").path("account").asText()).isEqualTo("calendar.ics"));
        assertThat(probe.observations().stream().filter(o -> o.toString().contains(SECRET_PATH_TOKEN)).map(o -> o.path("type").asText()).toList())
                .isNotEmpty().allSatisfy(type -> assertThat(type).isIn("secret.put", "secret.get"));
        assertThat(responses).allSatisfy(r -> assertNoSecret(r.body(), "response"));
    }

    @Test void AC8r1_invalidJsonBodiesCarryingATokenNeverLeak_notInResponsesTheDatabaseOrTheLogs() throws Exception {
        String fakeToken = "FAKE-TOKEN-123";
        String fakeKey = "FAKE-KEY-456";
        List<Resp> bad = new ArrayList<>();
        // unterminated JSON
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "{\"url\":\"https://example.com/private/" + fakeToken + ".ics?key=" + fakeKey + "\"")));
        // unterminated inside the string value
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "{\"url\":\"https://example.com/private/" + fakeToken + ".ics?key=" + fakeKey)));
        // type error: an array where a string is expected
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "{\"url\":[\"https://x/" + fakeToken + "\"]}")));
        // type error: an object, and a number next to the token
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "{\"url\":{\"k\":\"https://x/" + fakeToken + "?key=" + fakeKey + "\"}}")));
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "{\"url\":12345,\"extra\":\"" + fakeToken + "\"}")));
        // not JSON at all
        bad.add(record(call(port, "PUT", "/calendar/ics-source", "url=https://example.com/" + fakeToken + "?key=" + fakeKey)));
        // a valid request afterwards still works and nothing from the bad ones was stored
        assertThat(record(putSource(port, stub.url())).status()).isEqualTo(204);
        record(call(port, "GET", "/calendar/ics-source", null));

        assertThat(bad).allSatisfy(r -> assertThat(r.status()).as(r.body()).isBetween(400, 499));
        context.close();

        assertThat(responses).allSatisfy(r -> assertThat(r.body()).as("response body: " + r.body())
                .doesNotContain(fakeToken).doesNotContain(fakeKey));
        assertNoFake(new String(Files.readAllBytes(data.resolve("db.sqlite")), StandardCharsets.ISO_8859_1), "db.sqlite", fakeToken, fakeKey);
        Path wal = data.resolve("db.sqlite-wal");
        if (Files.exists(wal)) {
            assertNoFake(new String(Files.readAllBytes(wal), StandardCharsets.ISO_8859_1), "db.sqlite-wal", fakeToken, fakeKey);
        }
        try (Stream<Path> logs = Files.walk(data.resolve("logs"))) {
            assertThat(logs.filter(Files::isRegularFile).toList()).isNotEmpty().allSatisfy(file ->
                    assertNoFake(Files.readString(file, StandardCharsets.ISO_8859_1), "log " + file.getFileName(), fakeToken, fakeKey));
        }
    }

    private static void assertNoFake(String text, String what, String fakeToken, String fakeKey) {
        assertThat(text).as(what).doesNotContain(fakeToken).doesNotContain(fakeKey);
    }
}
