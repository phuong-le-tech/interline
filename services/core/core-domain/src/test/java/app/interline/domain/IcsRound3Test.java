package app.interline.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 review round 3, domain: a line dropped by the line limit makes the snapshot incomplete, and the parse budget
 * (period steps, deadline) is injectable so isolation tests are deterministic.
 */
class IcsRound3Test {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long WIDE_START = ms(1970, 1, 1);
    private static final long WIDE_END = ms(2100, 1, 1);

    private static long ms(int y, int mo, int d) {
        return ZonedDateTime.of(y, mo, d, 0, 0, 0, 0, UTC).toInstant().toEpochMilli();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String cal(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("\r\n", events) + "\r\nEND:VCALENDAR\r\n";
    }

    private static String vevent(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT";
    }

    private static String plain(String uid, String start, String end) {
        return vevent("UID:" + uid, "DTSTAMP:20260101T000000Z", "DTSTART:" + start, "DTEND:" + end, "SUMMARY:" + uid);
    }

    /** Folds one logical line with CRLF + space every 70 characters. */
    private static String fold(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i += 70) {
            if (i > 0) {
                sb.append("\r\n ");
            }
            sb.append(line, i, Math.min(line.length(), i + 70));
        }
        return sb.toString();
    }

    private static CalendarSnapshot parse(String body) {
        return IcsParser.parse(bytes(body), UTC, WIDE_START, WIDE_END);
    }

    private static boolean mentions(List<String> warnings, String... words) {
        for (String w : warnings) {
            String lower = w.toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (lower.contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- line limit

    @Test
    void anOverlongFoldedDtstartDropsTheEventAndMarksTheSnapshotIncomplete() {
        String longStart = "DTSTART:20260601T100000Z" + "0".repeat(IcsParser.MAX_LINE_CHARS + 100);
        assertThat(longStart.length()).isGreaterThan(65_536);
        String broken = vevent("UID:long", "DTSTAMP:20260101T000000Z", fold(longStart), "DTEND:20260601T110000Z", "SUMMARY:long");

        CalendarSnapshot snapshot = parse(cal(broken, plain("fine", "20260602T100000Z", "20260602T110000Z")));

        assertThat(snapshot.events()).noneMatch(e -> e.externalId().startsWith("long"));
        assertThat(snapshot.events()).anyMatch(e -> e.externalId().startsWith("fine"));
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.warnings()).isNotEmpty();
        assertThat(mentions(snapshot.warnings(), "line")).isTrue();
    }

    @Test
    void anOverlongJunkLineInAnOtherwiseGoodEventKeepsTheEventButIsIncomplete() {
        String junk = "X-JUNK:" + "a".repeat(IcsParser.MAX_LINE_CHARS + 10);
        String event = vevent("UID:junky", "DTSTAMP:20260101T000000Z", "DTSTART:20260601T100000Z", "DTEND:20260601T110000Z",
                "SUMMARY:junky", fold(junk));

        CalendarSnapshot snapshot = parse(cal(event));

        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.events().get(0).externalId()).startsWith("junky");
        assertThat(snapshot.complete()).isFalse();
        assertThat(mentions(snapshot.warnings(), "line")).isTrue();
    }

    @Test
    void aLineExactlyAtTheLimitIsAcceptedAndTheSnapshotIsComplete() {
        String junk = "X-JUNK:" + "a".repeat(IcsParser.MAX_LINE_CHARS - "X-JUNK:".length());
        assertThat(junk.length()).isEqualTo(IcsParser.MAX_LINE_CHARS);
        String event = vevent("UID:edge", "DTSTAMP:20260101T000000Z", "DTSTART:20260601T100000Z", "DTEND:20260601T110000Z",
                "SUMMARY:edge", fold(junk));

        CalendarSnapshot snapshot = parse(cal(event));

        assertThat(snapshot.events()).hasSize(1);
        assertThat(snapshot.complete()).isTrue();
        assertThat(mentions(snapshot.warnings(), "line")).isFalse();
    }

    @Test
    void manyNormalEventsWithNoLongLineAreComplete() {
        String[] events = new String[300];
        for (int i = 0; i < events.length; i++) {
            events[i] = plain("e" + i, "20260601T100000Z", "20260601T110000Z");
        }

        CalendarSnapshot snapshot = parse(cal(events));

        assertThat(snapshot.events()).hasSize(300);
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.warnings()).isEmpty();
    }

    // ---------------------------------------------------------------- budget isolation

    private static String smallFeed() {
        return cal(
                plain("single", "20300301T100000Z", "20300301T110000Z"),
                vevent("UID:yearly", "DTSTAMP:20260101T000000Z", "DTSTART:19700101T080000Z", "DTEND:19700101T090000Z",
                        "SUMMARY:yearly", "RRULE:FREQ=YEARLY;COUNT=60"),
                vevent("UID:daily", "DTSTAMP:20260101T000000Z", "DTSTART:20291201T080000Z", "DTEND:20291201T090000Z",
                        "SUMMARY:daily", "RRULE:FREQ=DAILY;COUNT=400"));
    }

    @Test
    void aTinyStepBudgetCutsAFewEventsWhileTheDefaultBudgetCompletes() {
        long from = ms(1990, 1, 1);
        long to = ms(2040, 1, 1);
        byte[] body = bytes(smallFeed());

        CalendarSnapshot tight = IcsParser.parse(body, UTC, from, to, 10, IcsParser.MAX_PARSE_MILLIS);
        CalendarSnapshot normal = IcsParser.parse(body, UTC, from, to);

        assertThat(tight.complete()).isFalse();
        assertThat(mentions(tight.warnings(), "step", "budget", "limit", "complex")).isTrue();
        assertThat(normal.complete()).isTrue();
        assertThat(normal.warnings()).isEmpty();
        assertThat(normal.events().size()).isGreaterThan(tight.events().size());
    }

    @Test
    void theFourArgumentParseUsesTheDefaultBudgets() {
        byte[] body = bytes(smallFeed());
        CalendarSnapshot viaDefault = IcsParser.parse(body, UTC, ms(1990, 1, 1), ms(2040, 1, 1));
        CalendarSnapshot viaExplicit = IcsParser.parse(body, UTC, ms(1990, 1, 1), ms(2040, 1, 1),
                IcsParser.MAX_PERIOD_STEPS, IcsParser.MAX_PARSE_MILLIS);

        assertThat(viaExplicit.events()).isEqualTo(viaDefault.events());
        assertThat(viaExplicit.complete()).isEqualTo(viaDefault.complete());
    }

    @Test
    void aDeadlineAlreadyPassedCutsARecurringSeriesWithoutThrowing() {
        CalendarSnapshot snapshot = IcsParser.parse(bytes(smallFeed()), UTC, ms(1990, 1, 1), ms(2040, 1, 1),
                IcsParser.MAX_PERIOD_STEPS, 0);

        assertThat(snapshot.complete()).isFalse();
        assertThat(mentions(snapshot.warnings(), "too long", "time", "deadline", " ms")).isTrue();
    }

    @Test
    void aDeadlineAlreadyPassedOnANonRecurringFeedDoesNotThrow() {
        CalendarSnapshot snapshot = IcsParser.parse(bytes(cal(plain("one", "20300301T100000Z", "20300301T110000Z"))), UTC,
                WIDE_START, WIDE_END, IcsParser.MAX_PERIOD_STEPS, 0);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.events().size()).isLessThanOrEqualTo(1);
        if (snapshot.complete()) {
            assertThat(snapshot.warnings()).isEmpty();
        } else {
            assertThat(snapshot.warnings()).isNotEmpty();
        }
    }
}
