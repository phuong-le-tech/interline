package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review round 1 over the real core (fixed clock 2026-06-15T12:00:00Z, zone UTC): items 9 (invalid snapshot is a 502
 * and leaves stored events alone), 11 (epoch overflow in GET /events is a 400) and 12 (webcal normalisation).
 */
@Timeout(180)
class M3Round1ApiTest {
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

    private static void assertProblem(Resp response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.json().path("code").asText()).isEqualTo(code);
    }

    private static List<String> titles(JsonNode events) {
        List<String> titles = new ArrayList<>();
        events.forEach(e -> titles.add(e.path("title").asText()));
        return titles;
    }

    private String threeEvents() {
        return calendar(
                vevent("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR),
                vevent("uid-2", "Two", NOW + 2 * HOUR, NOW + 3 * HOUR),
                vevent("uid-3", "Three", NOW + DAY, NOW + DAY + HOUR));
    }

    private void seedThreeEvents() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(threeEvents());
        assertThat(ok(sync(port), "seed sync").json().path("upserted").asInt()).isEqualTo(3);
    }

    private List<String> storedTitles() {
        return titles(ok(events(port), "events").json());
    }

    // ---- item 9: invalid snapshot -----------------------------------------------------------------------------------

    @Test void whitespaceOnlyBodyIs502AndTheStoredEventsAreUntouched() throws Exception {
        seedThreeEvents();
        List<String> before = storedTitles();
        stub.serve("   \r\n\t\r\n \n");

        Resp response = sync(port);

        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY).doesNotContain("/private");
        assertThat(storedTitles()).isEqualTo(before);
        assertThat(countEvents(data, "ics")).isEqualTo(3);
    }

    @Test void anHtmlSignInPageServedWith200Is502AndTheStoredEventsAreUntouched() throws Exception {
        seedThreeEvents();
        stub.serve("<!DOCTYPE html><html><body><form>Sign in to continue</form></body></html>");

        Resp response = sync(port);

        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY);
        assertThat(countEvents(data, "ics")).isEqualTo(3);
        assertThat(storedTitles()).containsExactly("One", "Two", "Three");
    }

    @Test void anEmptyBodyIs502AndTheStoredEventsAreUntouched() throws Exception {
        seedThreeEvents();
        stub.serveBytes(new byte[0]);

        assertProblem(sync(port), 502, "calendar_fetch_failed");
        assertThat(countEvents(data, "ics")).isEqualTo(3);
    }

    @Test void aTruncatedCalendarWithoutEndVcalendarIs502AndTheStoredEventsAreUntouched() throws Exception {
        seedThreeEvents();
        stub.serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + vevent("uid-x", "Cut off", NOW + HOUR, NOW + 2 * HOUR));

        assertProblem(sync(port), 502, "calendar_fetch_failed");
        assertThat(countEvents(data, "ics")).isEqualTo(3);
        assertThat(storedTitles()).containsExactly("One", "Two", "Three");
    }

    @Test void aCalendarWhoseEndComesBeforeItsBeginIs502() throws Exception {
        seedThreeEvents();
        stub.serve("END:VCALENDAR\r\nBEGIN:VCALENDAR\r\n" + vevent("uid-x", "Odd", NOW + HOUR, NOW + 2 * HOUR));

        assertProblem(sync(port), 502, "calendar_fetch_failed");
        assertThat(countEvents(data, "ics")).isEqualTo(3);
    }

    @Test void aValidButEmptyCalendarSucceedsAndClearsTheWindowsIcsEvents_butNotManualOnes() throws Exception {
        seedThreeEvents();
        insertManualEvent(data, "manual-1", "Mine", NOW + HOUR, NOW + 2 * HOUR);
        stub.serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n");

        Resp response = ok(sync(port), "empty but valid");

        assertThat(response.json().path("upserted").asInt()).isZero();
        assertThat(response.json().path("removed").asInt()).isEqualTo(3);
        assertThat(countEvents(data, "ics")).isZero();
        assertThat(countEvents(data, "manual")).isEqualTo(1);
    }

    @Test void aValidEmptyCalendarWithLfLineEndingsAndLowercaseNamesAlsoSucceeds() throws Exception {
        seedThreeEvents();
        stub.serve("begin:vcalendar\nversion:2.0\nend:vcalendar\n");

        Resp response = ok(sync(port), "lf and lowercase");

        assertThat(response.json().path("removed").asInt()).isEqualTo(3);
        assertThat(countEvents(data, "ics")).isZero();
    }

    @Test void aMalformedSingleEventInACompleteCalendarIsStillJustSkipped() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                + vevent("good", "Good", NOW + HOUR, NOW + 2 * HOUR)
                + "BEGIN:VEVENT\r\nUID:bad\r\nDTSTART:not-a-date\r\nSUMMARY:Bad\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n");

        Resp response = ok(sync(port), "complete with a bad event");

        assertThat(response.json().path("upserted").asInt()).isEqualTo(1);
        assertThat(response.json().path("skipped").asInt()).isEqualTo(1);
        assertThat(storedTitles()).containsExactly("Good");
    }

    // ---- item 11: epoch overflow ------------------------------------------------------------------------------------

    @Test void eventsWithLongMinFromAndLongMaxToIs400InvalidRequest() {
        assertProblem(call(port, "GET", "/calendar/events?from=-9223372036854775808&to=9223372036854775807", null), 400, "invalid_request");
    }

    @Test void eventsWithLongMaxFromAndLongMinToIs400InvalidRequest() {
        assertProblem(call(port, "GET", "/calendar/events?from=9223372036854775807&to=-9223372036854775808", null), 400, "invalid_request");
    }

    @Test void eventsWithZeroFromAndLongMaxToIs400InvalidRequest() {
        assertProblem(call(port, "GET", "/calendar/events?from=0&to=9223372036854775807", null), 400, "invalid_request");
    }

    @Test void eventsWithExtremeValuesNeverGiveA500() {
        String min = "-9223372036854775808";
        String max = "9223372036854775807";
        for (String query : List.of("from=" + min, "to=" + max, "from=" + max, "to=" + min, "from=" + min + "&to=" + min,
                "from=" + max + "&to=" + max, "from=" + min + "&to=0", "from=0&to=" + min, "from=99999999999999999999")) {
            Resp response = call(port, "GET", "/calendar/events?" + query, null);
            assertThat(response.status()).as(query + " -> " + response.body()).isBetween(400, 499);
            assertThat(response.contentType()).startsWith("application/problem+json");
        }
    }

    // ---- item 12: webcal --------------------------------------------------------------------------------------------

    @Test void webcalIsRewrittenToHttpsWithoutDoubleEncoding() throws Exception {
        URI uri = new HttpIcsFetcher(false).normalize("webcal://example.com/a%20b/c.ics?x=%2Fy%20z&k=1");

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("example.com");
        assertThat(uri.getRawPath()).isEqualTo("/a%20b/c.ics");
        assertThat(uri.getRawQuery()).isEqualTo("x=%2Fy%20z&k=1");
        assertThat(uri.toString()).doesNotContain("%25");
    }

    @Test void webcalKeepsAPortInTheAuthority() throws Exception {
        URI uri = new HttpIcsFetcher(false).normalize("webcal://example.com:8443/cal.ics?token=abc");

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("example.com");
        assertThat(uri.getPort()).isEqualTo(8443);
        assertThat(uri.getRawPath()).isEqualTo("/cal.ics");
        assertThat(uri.getRawQuery()).isEqualTo("token=abc");
    }

    @Test void anUppercaseWebcalSchemeWorks() throws Exception {
        URI uri = new HttpIcsFetcher(false).normalize("WEBCAL://example.com/a%20b/c.ics?x=%2Fy%20z&k=1");

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualToIgnoringCase("example.com");
        assertThat(uri.getRawPath()).isEqualTo("/a%20b/c.ics");
        assertThat(uri.getRawQuery()).isEqualTo("x=%2Fy%20z&k=1");
    }

    @Test void aWebcalAddressOverTheApiIsAcceptedAndReportsItsHost() {
        assertThat(putSource(port, "webcal://example.com/a%20b/c.ics?x=%2Fy%20z&k=1").status()).isEqualTo(204);

        Resp response = ok(call(port, "GET", "/calendar/ics-source", null), "get ics-source");

        assertThat(response.json().path("configured").asBoolean()).isTrue();
        assertThat(response.json().path("host").asText()).isEqualTo("example.com");
        assertThat(response.body()).doesNotContain("c.ics").doesNotContain("x=");
    }
}
