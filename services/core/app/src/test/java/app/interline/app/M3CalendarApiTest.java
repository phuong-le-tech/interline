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
import java.util.Random;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC5 (sync replaces the window), AC6 (limits and malformed input never give a 500) and AC7 (API contract) against the
 * real core, a fixed clock (2026-06-15T12:00:00Z, zone UTC) and a JDK HttpServer serving ICS on loopback.
 */
@Timeout(180)
class M3CalendarApiTest {
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

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void configure() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
    }

    private JsonNode syncOk() {
        return ok(sync(port), "sync").json();
    }

    private JsonNode eventsOk() {
        return ok(events(port), "events").json();
    }

    private static void assertProblem(Resp response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/problem+json");
        JsonNode body = response.json();
        if (code != null) assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("status").asInt(status)).isEqualTo(status);
    }

    private static List<String> titles(JsonNode events) {
        List<String> titles = new ArrayList<>();
        events.forEach(e -> titles.add(e.path("title").asText()));
        return titles;
    }

    private String threeEventsInWindow() {
        return calendar(
                vevent("uid-b", "Second", NOW + 2 * DAY, NOW + 2 * DAY + HOUR),
                vevent("uid-a", "First", NOW + HOUR, NOW + 2 * HOUR,
                        "ORGANIZER;CN=Olive Organizer:mailto:Olive@Example.com",
                        "ATTENDEE;CN=Alice Example:mailto:alice@example.com",
                        "URL:https://meet.example.com/abc"),
                vevent("uid-c", "Third", NOW + 10 * DAY, NOW + 10 * DAY + HOUR));
    }

    // ---- 401 ------------------------------------------------------------------------------------------------------

    @Test void AC7_everyRouteRequiresTheToken() {
        String[][] routes = {{"PUT", "/calendar/ics-source"}, {"GET", "/calendar/ics-source"}, {"DELETE", "/calendar/ics-source"},
                {"POST", "/calendar/sync"}, {"GET", "/calendar/events"}, {"GET", "/calendar/events?from=1&to=2"}};
        for (String[] route : routes) {
            String body = route[0].equals("PUT") ? "{\"url\":\"https://example.com/a.ics\"}" : route[0].equals("POST") ? "{}" : null;
            assertThat(callWithAuth(port, route[0], route[1], null, body).status()).as("%s %s without token", route[0], route[1]).isEqualTo(401);
            assertThat(callWithAuth(port, route[0], route[1], "Bearer wrong-token", body).status())
                    .as("%s %s wrong token", route[0], route[1]).isEqualTo(401);
        }
        assertThat(stub.hits).hasValue(0);
    }

    // ---- PUT /ics-source ------------------------------------------------------------------------------------------

    @Test void AC7_putIcsSource_acceptsHttpsWebcalAndLoopbackHttp() {
        assertThat(putSource(port, "https://calendar.example.com/ical/abc/basic.ics").status()).isEqualTo(204);
        assertThat(putSource(port, "webcal://calendar.example.com/ical/abc/basic.ics").status()).isEqualTo(204);
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
    }

    @Test void AC7_putIcsSource_acceptsAUrlOfExactly2048Characters() {
        String prefix = "https://example.com/";
        String url = prefix + "a".repeat(2048 - prefix.length());
        assertThat(url).hasSize(2048);
        assertThat(putSource(port, url).status()).isEqualTo(204);
    }

    @Test void AC7_putIcsSource_validation400s() {
        String prefix = "https://example.com/";
        List<Object> bodies = List.of(
                Map.of(),
                Map.of("url", ""),
                Map.of("url", "   "),
                Map.of("url", prefix + "a".repeat(2049 - prefix.length())),
                Map.of("url", "ftp://example.com/a.ics"),
                Map.of("url", "file:///etc/passwd"),
                Map.of("url", "javascript:alert(1)"),
                Map.of("url", "http://example.com/a.ics"),     // plain http to a non-loopback host, even with the flag
                Map.of("url", "http://203.0.113.7/a.ics"),
                Map.of("url", "https://"),
                Map.of("url", "https:///only/a/path.ics"),
                Map.of("url", "webcal://"),
                Map.of("url", "not a url"));
        for (Object body : bodies) {
            assertProblem(call(port, "PUT", "/calendar/ics-source", body), 400, "invalid_request");
        }
        assertThat(call(port, "PUT", "/calendar/ics-source", "{\"url\":123}").status()).isEqualTo(400);
        assertThat(call(port, "PUT", "/calendar/ics-source", "{not json").status()).isEqualTo(400);
        assertThat(call(port, "PUT", "/calendar/ics-source", "[]").status()).isEqualTo(400);
        assertProblem(call(port, "PUT", "/calendar/ics-source", "{\"url\":null}"), 400, "invalid_request");
        assertThat(call(port, "GET", "/calendar/ics-source", null).json().path("configured").asBoolean()).isFalse();
    }

    @Test void AC7_aRejectedPutDoesNotReplaceAnExistingSource() {
        configure();
        assertProblem(call(port, "PUT", "/calendar/ics-source", Map.of("url", "ftp://example.com/a.ics")), 400, "invalid_request");
        JsonNode source = ok(call(port, "GET", "/calendar/ics-source", null), "get").json();
        assertThat(source.path("configured").asBoolean()).isTrue();
        assertThat(source.path("host").asText()).isEqualTo("127.0.0.1");
    }

    // ---- GET / DELETE /ics-source ---------------------------------------------------------------------------------

    @Test void AC7_getIcsSource_reportsConfiguredAndHostOnly() {
        JsonNode before = ok(call(port, "GET", "/calendar/ics-source", null), "get").json();
        assertThat(before.path("configured").asBoolean(true)).isFalse();
        assertThat(before.path("host").isMissingNode() || before.path("host").isNull()).isTrue();

        assertThat(putSource(port, "https://calendar.example.com/ical/" + SECRET_PATH_TOKEN + "/basic.ics?key=" + SECRET_QUERY)
                .status()).isEqualTo(204);
        Resp after = ok(call(port, "GET", "/calendar/ics-source", null), "get");
        assertThat(after.json().path("configured").asBoolean()).isTrue();
        assertThat(after.json().path("host").asText()).isEqualTo("calendar.example.com");
        List<String> fields = new ArrayList<>();
        after.json().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsOnly("configured", "host");
        assertThat(after.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/ical").doesNotContain("basic.ics");
    }

    @Test void AC7_putReplacesThePreviousSource() {
        assertThat(putSource(port, "https://first.example.com/a.ics").status()).isEqualTo(204);
        assertThat(putSource(port, "https://second.example.com/b.ics").status()).isEqualTo(204);
        assertThat(call(port, "GET", "/calendar/ics-source", null).json().path("host").asText()).isEqualTo("second.example.com");
    }

    @Test void AC7_deleteIcsSource_removesTheSourceAndTheIcsEventsOnly_andIsIdempotent() throws Exception {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();
        insertManualEvent(data, "manual-1", "Typed by hand", NOW + DAY, NOW + DAY + HOUR);
        assertThat(countEvents(data, "ics")).isEqualTo(3);

        assertThat(call(port, "DELETE", "/calendar/ics-source", null).status()).isEqualTo(204);

        assertThat(call(port, "GET", "/calendar/ics-source", null).json().path("configured").asBoolean()).isFalse();
        assertThat(countEvents(data, "ics")).isZero();
        assertThat(titles(eventsOk())).containsExactly("Typed by hand");
        assertThat(call(port, "DELETE", "/calendar/ics-source", null).status()).isEqualTo(204);
        assertProblem(sync(port), 409, "calendar_not_configured");
    }

    // ---- POST /sync: 409 / 502 ------------------------------------------------------------------------------------

    @Test void AC7_syncWithoutASourceIs409ProblemJson() {
        assertProblem(sync(port), 409, "calendar_not_configured");
        assertThat(stub.hits).hasValue(0);
    }

    @Test void AC7_syncIs502WhenTheServerAnswers500_withoutTheUrlInTheBody() {
        configure();
        stub.failWith(500);
        Resp response = sync(port);
        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/private");
        assertThat(stub.hits.get()).isPositive();
    }

    @Test void AC7_syncIs502WhenTheServerIsGone() {
        configure();
        stub.close();
        Resp response = sync(port);
        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/private");
    }

    @Test void AC7_aFailedSyncKeepsTheEventsAlreadyStored() {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();
        stub.failWith(503);
        assertProblem(sync(port), 502, "calendar_fetch_failed");
        assertThat(eventsOk()).hasSize(3);
    }

    @Test void AC6_aBodyOverTheTenMegabyteCapIs502NotA500() {
        configure();
        stub.serveBytes(new byte[10 * 1024 * 1024 + 1024]);
        assertProblem(sync(port), 502, "calendar_fetch_failed");
    }

    // ---- POST /sync: success --------------------------------------------------------------------------------------

    @Test void AC5_syncStoresTheWindowEventsAndReportsCounts() {
        configure();
        stub.serve(threeEventsInWindow());

        JsonNode result = syncOk();

        assertThat(result.path("upserted").asInt()).isEqualTo(3);
        assertThat(result.path("removed").asInt()).isZero();
        assertThat(result.path("skipped").asInt()).isZero();
        assertThat(result.path("warnings").isArray()).isTrue();
        assertThat(result.path("warnings")).isEmpty();
        assertThat(stub.requestUris).isNotEmpty().allSatisfy(uri -> assertThat(uri).contains(SECRET_PATH_TOKEN).contains(SECRET_QUERY));
    }

    @Test void AC7_eventsAreOrderedByStartWithAttendeesAndSource() {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();

        JsonNode events = eventsOk();

        assertThat(titles(events)).containsExactly("First", "Second", "Third");
        JsonNode first = events.get(0);
        assertThat(first.path("source").asText()).isEqualTo("ics");
        assertThat(first.path("external_id").asText()).isEqualTo("uid-a");
        assertThat(first.path("id").asText()).isNotBlank();
        assertThat(first.path("starts_at").asLong()).isEqualTo(NOW + HOUR);
        assertThat(first.path("ends_at").asLong()).isEqualTo(NOW + 2 * HOUR);
        assertThat(first.path("conference_url").asText()).isEqualTo("https://meet.example.com/abc");
        List<String> emails = new ArrayList<>();
        first.path("attendees").forEach(a -> emails.add(a.path("email").asText()));
        assertThat(emails).contains("alice@example.com", "olive@example.com");
        assertThat(first.path("attendees").toString()).contains("Alice Example");
    }

    @Test void AC5_aSecondSyncOfTheSameFeedIsIdempotent() {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();
        JsonNode before = eventsOk();

        JsonNode again = syncOk();

        assertThat(again.path("upserted").asInt()).isZero();
        assertThat(again.path("removed").asInt()).isZero();
        assertThat(eventsOk()).isEqualTo(before);
    }

    @Test void AC5_aChangedEventIsUpsertedWithTheSameRowId() {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();
        String id = eventsOk().get(0).path("id").asText();

        stub.serve(threeEventsInWindow().replace("SUMMARY:First", "SUMMARY:First (renamed)"));
        JsonNode result = syncOk();

        assertThat(result.path("upserted").asInt()).isEqualTo(1);
        assertThat(result.path("removed").asInt()).isZero();
        JsonNode events = eventsOk();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).path("title").asText()).isEqualTo("First (renamed)");
        assertThat(events.get(0).path("id").asText()).isEqualTo(id);
    }

    @Test void AC5_anEventRemovedUpstreamIsDeleted() {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();

        stub.serve(calendar(vevent("uid-a", "First", NOW + HOUR, NOW + 2 * HOUR,
                "ORGANIZER;CN=Olive Organizer:mailto:Olive@Example.com",
                "ATTENDEE;CN=Alice Example:mailto:alice@example.com",
                "URL:https://meet.example.com/abc"), vevent("uid-c", "Third", NOW + 10 * DAY, NOW + 10 * DAY + HOUR)));
        JsonNode result = syncOk();

        assertThat(result.path("removed").asInt()).isEqualTo(1);
        assertThat(result.path("upserted").asInt()).isZero();
        assertThat(titles(eventsOk())).containsExactly("First", "Third");
    }

    @Test void AC5_aFeedThatBecomesEmptyRemovesEveryIcsEvent_butNotManualOnes() throws Exception {
        configure();
        stub.serve(threeEventsInWindow());
        syncOk();
        insertManualEvent(data, "manual-1", "Typed by hand", NOW + DAY, NOW + DAY + HOUR);

        stub.serve(calendar());
        JsonNode result = syncOk();

        assertThat(result.path("removed").asInt()).isEqualTo(3);
        assertThat(countEvents(data, "ics")).isZero();
        assertThat(countEvents(data, "manual")).isEqualTo(1);
    }

    @Test void AC5_eventsOutsideTheWindowAreNotStored_andIcsRowsOutsideItSurviveASync() throws Exception {
        configure();
        stub.serve(calendar(
                vevent("in-1", "Inside", NOW + DAY, NOW + DAY + HOUR),
                vevent("too-old", "Two days ago", NOW - 2 * DAY, NOW - 2 * DAY + HOUR),
                vevent("too-far", "In fifteen days", NOW + 15 * DAY, NOW + 15 * DAY + HOUR),
                vevent("ended-before", "Ended at the window start", WINDOW_START - HOUR, WINDOW_START),
                vevent("starts-at-end", "Starts at the window end", WINDOW_END, WINDOW_END + HOUR),
                vevent("straddles-start", "Straddles the window start", WINDOW_START - HOUR, WINDOW_START + HOUR)));

        JsonNode result = syncOk();

        assertThat(result.path("upserted").asInt()).isEqualTo(2);
        assertThat(countEvents(data, "ics")).isEqualTo(2);
        assertThat(titles(eventsOk())).containsExactly("Straddles the window start", "Inside");
    }

    // ---- GET /events ----------------------------------------------------------------------------------------------

    @Test void AC7_eventsDefaultWindowComesFromTheFixedClock() {
        insertManualEvent(data, "far-past", "Three days ago", NOW - 3 * DAY, NOW - 3 * DAY + HOUR);
        insertManualEvent(data, "ended-at-start", "Ended at the window start", WINDOW_START - HOUR, WINDOW_START);
        insertManualEvent(data, "just-in", "Starts one hour before the end", WINDOW_END - HOUR, WINDOW_END + HOUR);
        insertManualEvent(data, "tomorrow", "Tomorrow", NOW + DAY, NOW + DAY + HOUR);
        insertManualEvent(data, "at-end", "Starts at the window end", WINDOW_END, WINDOW_END + HOUR);
        insertManualEvent(data, "far-future", "In twenty days", NOW + 20 * DAY, NOW + 20 * DAY + HOUR);

        assertThat(titles(eventsOk())).containsExactly("Tomorrow", "Starts one hour before the end");
    }

    @Test void AC7_eventsHonourExplicitFromAndTo() {
        insertManualEvent(data, "a", "A", NOW + 1000, NOW + 2000);
        insertManualEvent(data, "b", "B", NOW + 5000, NOW + 6000);
        insertManualEvent(data, "c", "C", NOW + 9000, NOW + 9500);

        Resp response = ok(call(port, "GET", "/calendar/events?from=" + (NOW + 3000) + "&to=" + (NOW + 8000), null), "events");

        assertThat(titles(response.json())).containsExactly("B");
        assertThat(response.json().get(0).path("source").asText()).isEqualTo("manual");
    }

    @Test void AC7_eventsAreOrderedByStartThenId() {
        insertManualEvent(data, "z", "Later", NOW + 2 * HOUR, NOW + 3 * HOUR);
        insertManualEvent(data, "b", "Tie B", NOW + HOUR, NOW + 2 * HOUR);
        insertManualEvent(data, "a", "Tie A", NOW + HOUR, NOW + 2 * HOUR);
        insertManualEvent(data, "m", "Earliest", NOW, NOW + HOUR);

        assertThat(titles(eventsOk())).containsExactly("Earliest", "Tie A", "Tie B", "Later");
    }

    @Test void AC7_eventsValidation400s() {
        String[] bad = {
                "from=abc&to=2000", "from=1000&to=abc", "from=abc", "to=abc",
                "from=2000&to=2000", "from=3000&to=2000",
                "from=0&to=" + (32 * DAY),
                "from=0&to=" + (31 * DAY + 1)};
        for (String query : bad) {
            assertProblem(call(port, "GET", "/calendar/events?" + query, null), 400, null);
        }
    }

    @Test void AC7_eventsSpanOfExactly31DaysIsAccepted() {
        assertThat(call(port, "GET", "/calendar/events?from=0&to=" + (31 * DAY), null).status()).isEqualTo(200);
        assertThat(call(port, "GET", "/calendar/events?from=" + NOW + "&to=" + (NOW + 1), null).status()).isEqualTo(200);
    }

    @Test void AC7_eventsOnAFreshCoreIsAnEmptyArray() {
        JsonNode events = eventsOk();
        assertThat(events.isArray()).isTrue();
        assertThat(events).isEmpty();
    }

    // ---- AC6: garbage and limits -----------------------------------------------------------------------------------

    /** Stores the three good events, then serves {@code body} and expects 502 calendar_fetch_failed with the events untouched. */
    private void assertInvalidSnapshotRefused(byte[] body) {
        configure();
        stub.serve(threeEventsInWindow());
        assertThat(syncOk().path("upserted").asInt()).isEqualTo(3);
        List<String> before = titles(eventsOk());
        assertThat(before).hasSize(3);

        stub.serveBytes(body);
        Resp response = sync(port);

        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/private");
        assertThat(titles(eventsOk())).as("stored events untouched").isEqualTo(before);
    }

    @Test void AC6_aGarbageBodyGives502AndKeepsTheStoredEvents() {
        byte[] garbage = new byte[64 * 1024];
        new Random(42).nextBytes(garbage);
        assertInvalidSnapshotRefused(garbage);
    }

    @Test void AC6_textThatIsNotICalendarGives502AndKeepsTheStoredEvents() {
        assertInvalidSnapshotRefused("<html><body>Please sign in</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void AC6_anEmptyBodyGives502AndKeepsTheStoredEvents() {
        assertInvalidSnapshotRefused(new byte[0]);
    }

    @Test void AC6_aTruncatedFeedGives502AndKeepsTheStoredEvents() {
        String full = threeEventsInWindow();
        assertInvalidSnapshotRefused(full.substring(0, full.length() / 2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void AC6_aMalformedEventIsSkippedAndCounted_theGoodOnesAreStored() {
        configure();
        stub.serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                + vevent("good-1", "Good one", NOW + HOUR, NOW + 2 * HOUR)
                + "BEGIN:VEVENT\r\nUID:no-start\r\nSUMMARY:No start\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:bad-date\r\nDTSTART:not-a-date\r\nSUMMARY:Bad date\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nDTSTART:" + icsTime(NOW + DAY) + "\r\nSUMMARY:No uid\r\nEND:VEVENT\r\n"
                + vevent("good-2", "Good two", NOW + 3 * HOUR, NOW + 4 * HOUR)
                + "END:VCALENDAR\r\n");

        Resp response = sync(port);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("upserted").asInt()).isEqualTo(2);
        assertThat(response.json().path("skipped").asInt()).isEqualTo(3);
        assertThat(titles(eventsOk())).containsExactly("Good one", "Good two");
    }

    @Test void AC6_aHugeLineGives200WithAWarning_andTheRestOfTheFeedIsStillUsed() {
        configure();
        stub.serve(calendar(
                vevent("huge", "Has a huge property", NOW + HOUR, NOW + 2 * HOUR, "X-JUNK:" + "a".repeat(100_000)),
                vevent("fine", "Fine", NOW + 3 * HOUR, NOW + 4 * HOUR)));

        Resp response = sync(port);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("warnings")).isNotEmpty();
        assertThat(titles(eventsOk())).contains("Fine");
    }

    @Test void AC6_aHugeSummaryLineIsSkippedWithAWarning_neverA500() {
        configure();
        stub.serve(calendar(
                vevent("huge", "x".repeat(100_000), NOW + HOUR, NOW + 2 * HOUR),
                vevent("fine", "Fine", NOW + 3 * HOUR, NOW + 4 * HOUR)));

        Resp response = sync(port);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("warnings")).isNotEmpty();
        assertThat(titles(eventsOk())).contains("Fine");
    }

    @Test void AC6_aDailySeriesIsExpandedWithinTheWindowOnly() {
        configure();
        stub.serve(calendar(vevent("daily", "Every day", NOW, NOW + HOUR, "RRULE:FREQ=DAILY")));
        Resp response = sync(port);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        // occurrences at NOW + k days, k = 0..13, fall in the window; an unbounded series is clipped, never a 500.
        assertThat(response.json().path("upserted").asInt()).isEqualTo(14);
    }

    @Test void AC6_anUnsupportedRecurrencePartGives200WithAWarning() {
        configure();
        stub.serve(calendar(vevent("hourly", "Hourly", NOW + HOUR, NOW + 2 * HOUR, "RRULE:FREQ=HOURLY;INTERVAL=2")));
        Resp response = sync(port);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("warnings")).isNotEmpty();
        assertThat(response.json().path("upserted").asInt()).isEqualTo(1);
    }

    // ---- problem+json -----------------------------------------------------------------------------------------------

    @Test void AC7_errorsArriveAsProblemJsonWithoutTheUrl() {
        configure();
        stub.failWith(500);
        List<Resp> errors = List.of(
                call(port, "PUT", "/calendar/ics-source", Map.of("url", "ftp://example.com/" + SECRET_PATH_TOKEN + "?key=" + SECRET_QUERY)),
                call(port, "GET", "/calendar/events?from=abc", null),
                sync(port));
        for (Resp error : errors) {
            assertThat(error.status()).isBetween(400, 599).isNotEqualTo(500);
            assertThat(error.contentType()).startsWith("application/problem+json");
            assertThat(error.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY);
            assertThat(error.json().path("code").asText()).isNotBlank();
        }
        assertThat(errors.get(2).body()).doesNotContain("/private");
    }
}
