package app.interline.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * M3 review round 1, domain item 4: CPU limits on hostile input. Every test runs under a preemptive 8 second timeout and
 * asserts a bounded result rather than exact numbers.
 */
class IcsHostileInputTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Duration BOUND = Duration.ofSeconds(8);

    private static long ms(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, UTC).toInstant().toEpochMilli();
    }

    private static String vevent(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT";
    }

    private static byte[] cal(String... events) {
        String body = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("\r\n", events) + "\r\nEND:VCALENDAR\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static CalendarSnapshot parseBounded(byte[] body, long from, long to) {
        return assertTimeoutPreemptively(BOUND, () -> IcsParser.parse(body, UTC, from, to));
    }

    private static CalendarSnapshot ruleSnapshot(String rrule) {
        byte[] body = cal(vevent("UID:rule@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", "SUMMARY:R", "RRULE:" + rrule));
        return parseBounded(body, ms(2020, 1, 1, 0, 0), ms(2040, 1, 1, 0, 0));
    }

    /** The list value repeated until the part is about 40 KB long. */
    private static String repeated(String value) {
        return value.repeat(40_000 / value.length() + 1).replaceAll(",$", "");
    }

    // ---------------------------------------------------------------- constants

    @Test
    void constants_haveTheContractValues() {
        assertThat((long) IcsParser.MAX_PERIOD_STEPS).isEqualTo(2_000_000L);
        assertThat((long) IcsParser.MAX_PARSE_MILLIS).isEqualTo(10_000L);
    }

    // ---------------------------------------------------------------- (i) huge repeated lists

    @Test
    void list_byDayRepeatedIsDeduped() {
        CalendarSnapshot big = ruleSnapshot("FREQ=WEEKLY;COUNT=5;BYDAY=" + repeated("MO,"));
        CalendarSnapshot small = ruleSnapshot("FREQ=WEEKLY;COUNT=5;BYDAY=MO");
        assertThat(big.events()).hasSize(5);
        assertThat(big.events()).isEqualTo(small.events());
        assertThat(big.warnings()).isEqualTo(small.warnings());
    }

    @Test
    void list_byMonthDayRepeatedIsDeduped() {
        CalendarSnapshot big = ruleSnapshot("FREQ=MONTHLY;COUNT=4;BYMONTHDAY=" + repeated("5,"));
        CalendarSnapshot small = ruleSnapshot("FREQ=MONTHLY;COUNT=4;BYMONTHDAY=5");
        assertThat(big.events()).hasSize(4);
        assertThat(big.events()).isEqualTo(small.events());
    }

    @Test
    void list_byMonthRepeatedIsDeduped() {
        CalendarSnapshot big = ruleSnapshot("FREQ=YEARLY;COUNT=3;BYMONTH=" + repeated("3,"));
        CalendarSnapshot small = ruleSnapshot("FREQ=YEARLY;COUNT=3;BYMONTH=3");
        assertThat(big.events()).hasSize(3);
        assertThat(big.events()).isEqualTo(small.events());
    }

    private static void assertFirstOccurrenceOnly(CalendarSnapshot s) {
        assertThat(s.events()).hasSize(1);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(2026, 3, 2, 9, 0));
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void list_byMonthDayOutOfDomainRepeatedFallsBackToFirstOccurrence() {
        assertFirstOccurrenceOnly(ruleSnapshot("FREQ=MONTHLY;COUNT=10;BYMONTHDAY=" + repeated("99,")));
    }

    @Test
    void list_byMonthOutOfDomainRepeatedFallsBackToFirstOccurrence() {
        assertFirstOccurrenceOnly(ruleSnapshot("FREQ=YEARLY;COUNT=10;BYMONTH=" + repeated("13,")));
    }

    @Test
    void list_byMonthWithManyDistinctOutOfDomainValuesFallsBackToFirstOccurrence() {
        String values = IntStream.rangeClosed(1, 5_000).mapToObj(Integer::toString).collect(Collectors.joining(","));
        assertFirstOccurrenceOnly(ruleSnapshot("FREQ=YEARLY;COUNT=10;BYMONTH=" + values));
    }

    @Test
    void list_byDayGarbageRepeatedStaysBounded() {
        CalendarSnapshot s = ruleSnapshot("FREQ=WEEKLY;COUNT=10;BYDAY=" + repeated("XX,"));
        assertThat(s.events().size()).isLessThanOrEqualTo(1);
        assertThat(s.warnings()).isNotEmpty();
    }

    // ---------------------------------------------------------------- (ii) old DTSTART, no COUNT/UNTIL

    private static byte[] dailySince1970() {
        return cal(vevent("UID:old@example.com", "DTSTART:19700101T000000Z", "DTEND:19700101T010000Z", "SUMMARY:Old",
                "RRULE:FREQ=DAILY;INTERVAL=1"));
    }

    @Test
    void oldDtstart_dailyReturnsExactlyTheWindowOccurrences() {
        CalendarSnapshot s = parseBounded(dailySince1970(), ms(2026, 3, 1, 0, 0), ms(2026, 3, 8, 0, 0));
        assertThat(s.events()).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(s.events().get(i).startsAt()).isEqualTo(ms(2026, 3, 1 + i, 0, 0));
        }
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void oldDtstart_dailyWindowOverCapIsCappedWithWarning() {
        CalendarSnapshot s = parseBounded(dailySince1970(), ms(2026, 1, 1, 0, 0), ms(2028, 1, 1, 0, 0));
        assertThat(s.events()).hasSize(IcsParser.MAX_OCCURRENCES_PER_SERIES);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(2026, 1, 1, 0, 0));
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void oldDtstart_weeklyAndMonthlyStayCorrectAndBounded() {
        byte[] weekly = cal(vevent("UID:w@example.com", "DTSTART:19700105T000000Z", "DTEND:19700105T010000Z", "SUMMARY:W",
                "RRULE:FREQ=WEEKLY;BYDAY=MO"));
        CalendarSnapshot w = parseBounded(weekly, ms(2026, 3, 1, 0, 0), ms(2026, 3, 16, 0, 0));
        assertThat(w.events()).extracting(CalendarEvent::startsAt).containsExactly(ms(2026, 3, 2, 0, 0), ms(2026, 3, 9, 0, 0));

        byte[] monthly = cal(vevent("UID:m@example.com", "DTSTART:19700115T000000Z", "DTEND:19700115T010000Z", "SUMMARY:M",
                "RRULE:FREQ=MONTHLY"));
        CalendarSnapshot m = parseBounded(monthly, ms(2026, 1, 1, 0, 0), ms(2026, 4, 1, 0, 0));
        assertThat(m.events()).extracting(CalendarEvent::startsAt)
                .containsExactly(ms(2026, 1, 15, 0, 0), ms(2026, 2, 15, 0, 0), ms(2026, 3, 15, 0, 0));
    }

    // ---------------------------------------------------------------- (iii) many expensive series

    @Test
    void manyExpensiveSeriesFinishWithinTheBoundAndWarn() {
        List<String> events = new ArrayList<>();
        String days = IntStream.rangeClosed(1, 31).mapToObj(Integer::toString).collect(Collectors.joining(","));
        String months = IntStream.rangeClosed(1, 12).mapToObj(Integer::toString).collect(Collectors.joining(","));
        for (int i = 0; i < 3_000; i++) {
            events.add(vevent("UID:hostile-" + i + "@example.com", "DTSTART:19700101T000000Z", "DTEND:19700101T010000Z", "SUMMARY:H" + i,
                    "RRULE:FREQ=YEARLY;BYMONTHDAY=" + days + ";BYMONTH=" + months + ";BYDAY=MO,TU,WE,TH,FR,SA,SU"));
        }
        CalendarSnapshot s = parseBounded(cal(events.toArray(String[]::new)), ms(1970, 1, 1, 0, 0), ms(2100, 1, 1, 0, 0));
        assertThat(s).isNotNull();
        assertThat(s.events().size()).isLessThanOrEqualTo(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void manyExpensiveMonthlySeriesFinishWithinTheBound() {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            events.add(vevent("UID:monthly-" + i + "@example.com", "DTSTART:19700101T000000Z", "DTEND:19700101T010000Z", "SUMMARY:M" + i,
                    "RRULE:FREQ=MONTHLY;BYMONTHDAY=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31"));
        }
        CalendarSnapshot s = parseBounded(cal(events.toArray(String[]::new)), ms(1970, 1, 1, 0, 0), ms(2100, 1, 1, 0, 0));
        assertThat(s.events().size()).isLessThanOrEqualTo(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isNotEmpty();
    }
}
