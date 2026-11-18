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

import static app.interline.app.M2Support.Resp;
import static app.interline.app.M2Support.ok;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review round 2 over the real core (fixed clock 2026-06-15T12:00:00Z, zone UTC): item 8 (an incomplete parse keeps
 * stored events and reports complete:false) and the multi-calendar framing check (502, stored events untouched).
 */
@Timeout(180)
class M3Round2ApiTest {
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

    private List<String> storedTitles() {
        return titles(ok(events(port), "events").json());
    }

    private String threeEvents() {
        return calendar(
                vevent("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR),
                vevent("uid-2", "Two", NOW + 2 * HOUR, NOW + 3 * HOUR),
                vevent("uid-3", "Three", NOW + DAY, NOW + DAY + HOUR));
    }

    /** 3000 yearly series with every BY-part expansion allowed: enough to exhaust the parser's step budget. */
    private static String hostileEvents() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("BEGIN:VEVENT\r\nUID:hostile-").append(i).append("\r\nDTSTAMP:20260101T000000Z\r\n")
                    .append("DTSTART:20200101T080000Z\r\nDTEND:20200101T090000Z\r\nSUMMARY:Hostile ").append(i).append("\r\n")
                    .append("RRULE:FREQ=YEARLY;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;BYMONTHDAY=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31\r\n")
                    .append("END:VEVENT\r\n");
        }
        return sb.toString();
    }

    @Test void aCompleteSyncReportsCompleteTrue() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(threeEvents());

        JsonNode json = ok(sync(port), "sync").json();

        assertThat(json.path("upserted").asInt()).isEqualTo(3);
        assertThat(json.has("complete")).isTrue();
        assertThat(json.path("complete").asBoolean()).isTrue();
    }

    @Test void aSyncThatHitsTheParserBudgetKeepsStoredEventsAndReportsCompleteFalse() throws Exception {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(calendar(vevent("uid-keep", "Keep me", NOW + HOUR, NOW + 2 * HOUR)));
        JsonNode first = ok(sync(port), "first sync").json();
        assertThat(first.path("complete").asBoolean()).isTrue();
        assertThat(storedTitles()).containsExactly("Keep me");

        // the next feed no longer lists "Keep me", but the parser gives up part way through the hostile series
        stub.serve(calendar(hostileEvents() + vevent("uid-new", "Brand new", NOW + 3 * HOUR, NOW + 4 * HOUR)));
        Resp second = sync(port);

        assertThat(second.status()).as(second.body()).isEqualTo(200);
        assertThat(second.json().path("complete").asBoolean()).isFalse();
        assertThat(second.json().path("removed").asInt()).isZero();
        assertThat(second.json().path("warnings").isArray()).isTrue();
        assertThat(second.json().path("warnings").size()).isGreaterThan(0);
        assertThat(storedTitles()).contains("Keep me");
        assertThat(countEvents(data, "ics")).isGreaterThanOrEqualTo(1);
    }

    @Test void aLaterCompleteSyncRemovesTheEventTheIncompleteOneKept() throws Exception {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(calendar(vevent("uid-keep", "Keep me", NOW + HOUR, NOW + 2 * HOUR)));
        ok(sync(port), "first sync");
        stub.serve(calendar(hostileEvents()));
        assertThat(ok(sync(port), "incomplete sync").json().path("complete").asBoolean()).isFalse();
        assertThat(storedTitles()).contains("Keep me");

        stub.serve(calendar(vevent("uid-other", "Other", NOW + 5 * HOUR, NOW + 6 * HOUR)));
        JsonNode third = ok(sync(port), "complete sync").json();

        assertThat(third.path("complete").asBoolean()).isTrue();
        assertThat(storedTitles()).containsExactly("Other");
    }

    @Test void aCompleteCalendarFollowedByATruncatedSecondOneIs502AndTheStoredEventsAreUntouched() throws Exception {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(threeEvents());
        assertThat(ok(sync(port), "seed sync").json().path("upserted").asInt()).isEqualTo(3);
        List<String> before = storedTitles();

        stub.serve(threeEvents() + "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                + vevent("uid-x", "Cut off", NOW + HOUR, NOW + 2 * HOUR));
        Resp response = sync(port);

        assertProblem(response, 502, "calendar_fetch_failed");
        assertThat(response.body()).doesNotContain(SECRET_PATH_TOKEN).doesNotContain(SECRET_QUERY);
        assertThat(storedTitles()).isEqualTo(before);
        assertThat(countEvents(data, "ics")).isEqualTo(3);
    }

    @Test void twoCompleteCalendarsBackToBackSucceed() {
        assertThat(putSource(port, stub.url()).status()).isEqualTo(204);
        stub.serve(calendar(vevent("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR))
                + calendar(vevent("uid-2", "Two", NOW + 2 * HOUR, NOW + 3 * HOUR)));

        JsonNode json = ok(sync(port), "sync").json();

        assertThat(json.path("complete").asBoolean()).isTrue();
        assertThat(storedTitles()).contains("One", "Two");
    }
}
