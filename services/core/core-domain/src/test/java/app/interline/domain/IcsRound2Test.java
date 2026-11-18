package app.interline.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * M3 review round 2, domain items 1-6: calendar framing, the incomplete flag of a snapshot, exact elapsed DTEND length,
 * over-domain duplicate lists, surrogate-safe caps and the budget skipping overrides. Expected epochs come from java.time.
 */
class IcsRound2Test {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long WIDE_START = ms(UTC, 1970, 1, 1, 0, 0);
    private static final long WIDE_END = ms(UTC, 2100, 1, 1, 0, 0);
    private static final Duration BOUND = Duration.ofSeconds(8);
    private static final long HOUR = 3_600_000L;
    private static final String EMOJI = "😀";

    // ---------------------------------------------------------------- helpers

    private static long ms(ZoneId zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli();
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

    private static CalendarSnapshot parse(ZoneId zone, long from, long to, String... events) {
        return IcsParser.parse(bytes(cal(events)), zone, from, to);
    }

    private static CalendarSnapshot parse(ZoneId zone, String... events) {
        return parse(zone, WIDE_START, WIDE_END, events);
    }

    private static CalendarSnapshot parseBounded(byte[] body, long from, long to) {
        return assertTimeoutPreemptively(BOUND, () -> IcsParser.parse(body, UTC, from, to));
    }

    private static List<Long> starts(CalendarSnapshot s) {
        return s.events().stream().map(CalendarEvent::startsAt).toList();
    }

    private static List<Long> lengths(CalendarSnapshot s) {
        return s.events().stream().map(e -> e.endsAt() - e.startsAt()).toList();
    }

    private static CalendarEvent single(CalendarSnapshot s) {
        assertThat(s.events()).hasSize(1);
        return s.events().get(0);
    }

    private static boolean framed(String body) {
        return IcsParser.isCompleteCalendar(bytes(body));
    }

    private static final String EMPTY_CAL = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

    // ---------------------------------------------------------------- 1. framing of every calendar

    @Test
    void framing_oneCompleteCalendar() {
        assertThat(framed(cal(vevent("UID:a", "DTSTART:20260302T090000Z")))).isTrue();
    }

    @Test
    void framing_twoCompleteCalendarsBackToBack() {
        assertThat(framed(EMPTY_CAL + EMPTY_CAL)).isTrue();
        assertThat(framed(cal(vevent("UID:a", "DTSTART:20260302T090000Z")) + cal(vevent("UID:b", "DTSTART:20260303T090000Z")))).isTrue();
    }

    @Test
    void framing_completeThenTruncatedSecondIsRejected() {
        assertThat(framed(EMPTY_CAL + "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")).isFalse();
        assertThat(framed(EMPTY_CAL + "BEGIN:VCALENDAR\r\n" + vevent("UID:b", "DTSTART:20260303T090000Z") + "\r\n")).isFalse();
    }

    @Test
    void framing_nestedBeginInsideOpenCalendarIsRejected() {
        assertThat(framed("BEGIN:VCALENDAR\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")).isFalse();
        assertThat(framed("BEGIN:VCALENDAR\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR\r\nEND:VCALENDAR\r\n")).isFalse();
    }

    @Test
    void framing_strayEndBeforeAnyBeginIsRejected() {
        assertThat(framed("END:VCALENDAR\r\n" + EMPTY_CAL)).isFalse();
        assertThat(framed("END:VCALENDAR\r\n")).isFalse();
    }

    @Test
    void framing_strayEndAfterACompleteCalendarIsRejected() {
        assertThat(framed(EMPTY_CAL + "END:VCALENDAR\r\n")).isFalse();
        assertThat(framed(EMPTY_CAL + EMPTY_CAL + "END:VCALENDAR\r\n")).isFalse();
    }

    @Test
    void framing_beginWithoutEndIsRejected() {
        assertThat(framed("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")).isFalse();
    }

    @Test
    void framing_emptyHtmlAndBlankAreRejected() {
        assertThat(framed("")).isFalse();
        assertThat(framed("   \r\n\r\n  ")).isFalse();
        assertThat(framed("<html><body>Sign in</body></html>")).isFalse();
    }

    @Test
    void framing_trailingBlankLinesAndWhitespaceAfterLastEndAreFine() {
        assertThat(framed(EMPTY_CAL + "\r\n\r\n   \r\n\t\r\n")).isTrue();
        assertThat(framed("BEGIN:VCALENDAR\r\nEND:VCALENDAR   ")).isTrue();
        assertThat(framed(EMPTY_CAL + EMPTY_CAL + "\n\n  \n")).isTrue();
    }

    @Test
    void framing_garbageAfterTheLastEndIsRejected() {
        assertThat(framed(EMPTY_CAL + "<html>proxy page</html>\r\n")).isFalse();
    }

    @Test
    void framing_caseInsensitiveLfAndBomStillWork() {
        assertThat(framed("begin:vcalendar\nend:vcalendar\n")).isTrue();
        assertThat(framed("﻿BEGIN:VCALENDAR\nEND:VCALENDAR\n")).isTrue();
    }

    // ---------------------------------------------------------------- 2. complete flag

    private static String expensiveYearly(int i) {
        String days = IntStream.rangeClosed(1, 31).mapToObj(Integer::toString).collect(Collectors.joining(","));
        String months = IntStream.rangeClosed(1, 12).mapToObj(Integer::toString).collect(Collectors.joining(","));
        return vevent("UID:hostile-" + i + "@example.com", "DTSTART:19700101T000000Z", "DTEND:19700101T010000Z", "SUMMARY:H" + i,
                "RRULE:FREQ=YEARLY;BYMONTHDAY=" + days + ";BYMONTH=" + months + ";BYDAY=MO,TU,WE,TH,FR,SA,SU");
    }

    private static String[] hostileEvents(int count) {
        return IntStream.range(0, count).mapToObj(IcsRound2Test::expensiveYearly).toArray(String[]::new);
    }

    private static String plain(int i) {
        return vevent("UID:p" + i + "@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", "SUMMARY:P" + i);
    }

    @Test
    void complete_threeArgumentConstructorMeansComplete() {
        CalendarSnapshot s = new CalendarSnapshot(List.of(), List.of(), 0);
        assertThat(s.complete()).isTrue();
        assertThat(new CalendarSnapshot(List.of(), List.of(), 0, false).complete()).isFalse();
        assertThat(new CalendarSnapshot(List.of(), List.of("w"), 2, true))
                .isEqualTo(new CalendarSnapshot(List.of(), List.of("w"), 2));
    }

    @Test
    void complete_normalFeedIsComplete() {
        CalendarSnapshot s = parse(UTC, plain(1), plain(2));
        assertThat(s.events()).hasSize(2);
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_malformedVeventSkipIsStillComplete() {
        CalendarSnapshot s = parse(UTC, plain(1), vevent("DTSTART:20260302T090000Z", "SUMMARY:No uid"));
        assertThat(s.skipped()).isEqualTo(1);
        assertThat(s.events()).hasSize(1);
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_unknownTzidWarningIsStillComplete() {
        CalendarSnapshot s = parse(UTC, vevent("UID:tz@example.com", "DTSTART;TZID=Nowhere/Land:20260302T090000", "SUMMARY:Tz"));
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_unsupportedRulePartWarningIsStillComplete() {
        CalendarSnapshot s = parse(UTC, vevent("UID:rd@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z",
                "RDATE:20260310T090000Z"));
        assertThat(s.warnings()).isNotEmpty();
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_stepBudgetHitIsIncomplete() {
        CalendarSnapshot s = parseBounded(bytes(cal(hostileEvents(3_000))), WIDE_START, WIDE_END);
        assertThat(s.complete()).isFalse();
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void complete_moreThanMaxVeventsIsIncomplete() {
        String[] events = IntStream.range(0, IcsParser.MAX_VEVENTS + 1).mapToObj(IcsRound2Test::plain).toArray(String[]::new);
        CalendarSnapshot s = parseBounded(bytes(cal(events)), WIDE_START, WIDE_END);
        assertThat(s.complete()).isFalse();
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void complete_moreThanMaxEventsPerSyncIsIncomplete() {
        String[] events = IntStream.range(0, IcsParser.MAX_EVENTS_PER_SYNC + 1).mapToObj(IcsRound2Test::plain).toArray(String[]::new);
        CalendarSnapshot s = parseBounded(bytes(cal(events)), WIDE_START, WIDE_END);
        assertThat(s.events()).hasSize(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.complete()).isFalse();
    }

    @Test
    void complete_exactlyMaxEventsPerSyncIsComplete() {
        String[] events = IntStream.range(0, IcsParser.MAX_EVENTS_PER_SYNC).mapToObj(IcsRound2Test::plain).toArray(String[]::new);
        CalendarSnapshot s = parseBounded(bytes(cal(events)), WIDE_START, WIDE_END);
        assertThat(s.events()).hasSize(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_seriesCutByPerSeriesCapIsIncomplete() {
        long from = ms(UTC, 2026, 1, 1, 0, 0);
        CalendarSnapshot s = parse(UTC, from, from + 700L * 24 * HOUR, vevent("UID:daily@example.com", "DTSTART:20260101T000000Z",
                "DTEND:20260101T010000Z", "SUMMARY:Daily", "RRULE:FREQ=DAILY"));
        assertThat(s.events()).hasSize(IcsParser.MAX_OCCURRENCES_PER_SERIES);
        assertThat(s.complete()).isFalse();
    }

    @Test
    void complete_seriesJustUnderThePerSeriesCapIsComplete() {
        long from = ms(UTC, 2026, 1, 1, 0, 0);
        CalendarSnapshot s = parse(UTC, from, from + 100L * 24 * HOUR, vevent("UID:daily@example.com", "DTSTART:20260101T000000Z",
                "DTEND:20260101T010000Z", "SUMMARY:Daily", "RRULE:FREQ=DAILY"));
        assertThat(s.events()).hasSize(100);
        assertThat(s.complete()).isTrue();
    }

    @Test
    void complete_bodyOverMaxBytesIsIncomplete() {
        byte[] body = new byte[IcsParser.MAX_BODY_BYTES + 1];
        Arrays.fill(body, (byte) 'a');
        CalendarSnapshot s = IcsParser.parse(body, UTC, WIDE_START, WIDE_END);
        assertThat(s.events()).isEmpty();
        assertThat(s.complete()).isFalse();
    }

    // ---------------------------------------------------------------- 3. timed DTEND keeps its elapsed length

    @Test
    void duration_weeklyAcrossSpringForwardKeepsTwoElapsedHours() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:w@example.com", "DTSTART;TZID=Europe/Paris:20260322T013000",
                "DTEND;TZID=Europe/Paris:20260322T033000", "SUMMARY:W", "RRULE:FREQ=WEEKLY;COUNT=3"));
        long mar22 = ms(PARIS, 2026, 3, 22, 1, 30);
        long mar29 = ms(PARIS, 2026, 3, 29, 1, 30);
        long apr5 = ms(PARIS, 2026, 4, 5, 1, 30);
        assertThat(starts(s)).containsExactly(mar22, mar29, apr5);
        assertThat(lengths(s)).containsExactly(2 * HOUR, 2 * HOUR, 2 * HOUR);
        CalendarEvent change = s.events().get(1);
        assertThat(change.endsAt()).isEqualTo(mar29 + 2 * HOUR);
        assertThat(change.endsAt()).isEqualTo(ms(PARIS, 2026, 3, 29, 4, 30));
    }

    @Test
    void duration_twoHourSeriesBeforeAndAfterTheChangeStaysTwoHours() {
        CalendarSnapshot before = parse(PARIS, vevent("UID:b@example.com", "DTSTART;TZID=Europe/Paris:20260301T013000",
                "DTEND;TZID=Europe/Paris:20260301T033000", "SUMMARY:B", "RRULE:FREQ=WEEKLY;COUNT=3"));
        assertThat(before.events()).hasSize(3);
        assertThat(lengths(before)).containsOnly(2 * HOUR);

        CalendarSnapshot after = parse(PARIS, vevent("UID:a@example.com", "DTSTART;TZID=Europe/Paris:20260412T013000",
                "DTEND;TZID=Europe/Paris:20260412T033000", "SUMMARY:A", "RRULE:FREQ=WEEKLY;COUNT=3"));
        assertThat(after.events()).hasSize(3);
        assertThat(lengths(after)).containsOnly(2 * HOUR);
    }

    @Test
    void duration_weeklyAcrossFallBackKeepsTwoElapsedHours() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:f@example.com", "DTSTART;TZID=Europe/Paris:20261018T013000",
                "DTEND;TZID=Europe/Paris:20261018T033000", "SUMMARY:F", "RRULE:FREQ=WEEKLY;COUNT=3"));
        assertThat(s.events()).hasSize(3);
        assertThat(lengths(s)).containsOnly(2 * HOUR);
        assertThat(s.events().get(1).startsAt()).isEqualTo(ms(PARIS, 2026, 10, 25, 1, 30));
    }

    @Test
    void duration_oneOffInRepeatedAutumnHourIsExactlyTwoHours() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:o@example.com", "DTSTART;TZID=Europe/Paris:20261025T020000",
                "DTEND:20261025T020000Z", "SUMMARY:O"));
        CalendarEvent e = single(s);
        assertThat(e.startsAt()).isEqualTo(ms(UTC, 2026, 10, 25, 0, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(2 * HOUR);
    }

    @Test
    void duration_floatingStartAndEndAcrossSpringForwardIsElapsed() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:fl@example.com", "DTSTART:20260329T010000", "DTEND:20260329T040000", "SUMMARY:Fl"));
        CalendarEvent e = single(s);
        long start = ms(PARIS, 2026, 3, 29, 1, 0);
        long end = ms(PARIS, 2026, 3, 29, 4, 0);
        assertThat(e.startsAt()).isEqualTo(start);
        assertThat(e.endsAt()).isEqualTo(end);
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(2 * HOUR);
    }

    @Test
    void duration_utcStartAndZonedEndUseEpochDifference() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:mix@example.com", "DTSTART:20260329T000000Z",
                "DTEND;TZID=Europe/Paris:20260329T040000", "SUMMARY:Mix"));
        CalendarEvent e = single(s);
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 3, 29, 4, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(2 * HOUR);
    }

    @Test
    void duration_allDayStaysNominalCalendarDaysAcrossSpringForward() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:d1@example.com", "DTSTART;VALUE=DATE:20260329", "DTEND;VALUE=DATE:20260330", "SUMMARY:D"));
        CalendarEvent e = single(s);
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 3, 29, 0, 0));
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 3, 30, 0, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(23 * HOUR);
    }

    @Test
    void duration_allDayStaysNominalCalendarDaysAcrossFallBack() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:d2@example.com", "DTSTART;VALUE=DATE:20261025", "DTEND;VALUE=DATE:20261026", "SUMMARY:D"));
        CalendarEvent e = single(s);
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 10, 25, 0, 0));
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 10, 26, 0, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(25 * HOUR);
    }

    @Test
    void duration_durationPropertyIsUnchanged() {
        CalendarSnapshot s = parse(PARIS, vevent("UID:dp@example.com", "DTSTART;TZID=Europe/Paris:20260329T013000",
                "DURATION:PT2H", "SUMMARY:Dp"));
        CalendarEvent e = single(s);
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(2 * HOUR);
    }

    // ---------------------------------------------------------------- 4. over-domain lists

    private static String listFeed(String rrule) {
        return cal(vevent("UID:qa@example.com", "DTSTART:20261118T080000Z", "DTEND:20261118T090000Z", "SUMMARY:QA", "RRULE:" + rrule));
    }

    private static String repeat(String value, int times) {
        return IntStream.range(0, times).mapToObj(i -> value).collect(Collectors.joining(","));
    }

    @Test
    void list_byMonthRepeated20000TimesIsJustByMonthOne() {
        byte[] body = bytes(listFeed("FREQ=YEARLY;BYMONTH=" + repeat("1", 20_000)));
        CalendarSnapshot s = parseBounded(body, ms(UTC, 2026, 11, 11, 0, 0), ms(UTC, 2026, 12, 2, 0, 0));
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 11, 18, 8, 0));
        assertThat(s.skipped()).isEqualTo(0);
        assertThat(s.complete()).isTrue();
    }

    @Test
    void list_byMonthRepeatedExpandsLikeTheDedupedRule() {
        long from = ms(UTC, 2026, 11, 1, 0, 0);
        long to = ms(UTC, 2029, 12, 31, 0, 0);
        CalendarSnapshot big = parseBounded(bytes(listFeed("FREQ=YEARLY;BYMONTH=" + repeat("1", 20_000))), from, to);
        CalendarSnapshot small = parseBounded(bytes(listFeed("FREQ=YEARLY;BYMONTH=1")), from, to);
        assertThat(starts(big)).containsExactly(ms(UTC, 2026, 11, 18, 8, 0), ms(UTC, 2027, 1, 18, 8, 0),
                ms(UTC, 2028, 1, 18, 8, 0), ms(UTC, 2029, 1, 18, 8, 0));
        assertThat(big.events()).isEqualTo(small.events());
        assertThat(big.warnings()).isEqualTo(small.warnings());
        assertThat(big.skipped()).isEqualTo(0);
    }

    @Test
    void list_byDayRepeatedHugeEqualsTheDedupedRule() {
        String rule = "FREQ=WEEKLY;COUNT=4;BYDAY=";
        long from = ms(UTC, 2026, 11, 1, 0, 0);
        long to = ms(UTC, 2027, 3, 1, 0, 0);
        CalendarSnapshot big = parseBounded(bytes(listFeed(rule + repeat("WE", 20_000))), from, to);
        CalendarSnapshot small = parseBounded(bytes(listFeed(rule + "WE")), from, to);
        assertThat(big.events()).hasSize(4);
        assertThat(big.events()).isEqualTo(small.events());
        assertThat(big.warnings()).isEqualTo(small.warnings());
        assertThat(big.skipped()).isEqualTo(0);
    }

    @Test
    void list_byMonthDayRepeatedHugeEqualsTheDedupedRule() {
        String rule = "FREQ=MONTHLY;COUNT=4;BYMONTHDAY=";
        long from = ms(UTC, 2026, 11, 1, 0, 0);
        long to = ms(UTC, 2027, 6, 1, 0, 0);
        CalendarSnapshot big = parseBounded(bytes(listFeed(rule + repeat("18", 20_000))), from, to);
        CalendarSnapshot small = parseBounded(bytes(listFeed(rule + "18")), from, to);
        assertThat(big.events()).hasSize(4);
        assertThat(big.events()).isEqualTo(small.events());
        assertThat(big.warnings()).isEqualTo(small.warnings());
        assertThat(big.skipped()).isEqualTo(0);
    }

    private static void assertDtstartKeptWithWarning(String rrule) {
        CalendarSnapshot s = parseBounded(bytes(listFeed(rrule)), ms(UTC, 2026, 11, 11, 0, 0), ms(UTC, 2029, 12, 2, 0, 0));
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 11, 18, 8, 0));
        assertThat(s.warnings()).isNotEmpty();
        assertThat(s.skipped()).isEqualTo(0);
    }

    @Test
    void list_outOfDomainDistinctByMonthKeepsDtstartWithWarning() {
        assertDtstartKeptWithWarning("FREQ=YEARLY;BYMONTH=13");
        assertDtstartKeptWithWarning("FREQ=YEARLY;BYMONTH=" + repeat("13", 20_000));
    }

    @Test
    void list_outOfDomainDistinctByMonthDayKeepsDtstartWithWarning() {
        assertDtstartKeptWithWarning("FREQ=MONTHLY;BYMONTHDAY=99");
        assertDtstartKeptWithWarning("FREQ=MONTHLY;BYMONTHDAY=" + repeat("99", 20_000));
    }

    @Test
    void list_outOfDomainDistinctByDayKeepsDtstartWithWarning() {
        assertDtstartKeptWithWarning("FREQ=WEEKLY;BYDAY=XX");
        assertDtstartKeptWithWarning("FREQ=WEEKLY;BYDAY=" + repeat("XX", 20_000));
    }

    @Test
    void list_validValueMixedWithOutOfDomainValueKeepsDtstartWithWarning() {
        assertDtstartKeptWithWarning("FREQ=YEARLY;BYMONTH=1,13");
    }

    // ---------------------------------------------------------------- 5. caps never split a surrogate pair

    private static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    private static CalendarEvent oneEvent(String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:sur@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z"));
        lines.addAll(Arrays.asList(extra));
        return single(parse(UTC, vevent(lines.toArray(String[]::new))));
    }

    @Test
    void surrogate_titleCutInTheMiddleOfAnEmojiStaysWellFormed() {
        // the emoji occupies chars 499 and 500, so a cut at 500 would split it
        CalendarEvent e = oneEvent("SUMMARY:" + "a".repeat(IcsParser.MAX_TITLE_CHARS - 1) + EMOJI + "tail".repeat(10));
        assertThat(e.title().length()).isLessThanOrEqualTo(IcsParser.MAX_TITLE_CHARS);
        assertThat(wellFormed(e.title())).isTrue();
        assertThat(e.title()).startsWith("a".repeat(IcsParser.MAX_TITLE_CHARS - 1));
    }

    @Test
    void surrogate_titleWithEmojiEndingExactlyAtTheCapIsKept() {
        String title = "a".repeat(IcsParser.MAX_TITLE_CHARS - 2) + EMOJI;
        CalendarEvent e = oneEvent("SUMMARY:" + title);
        assertThat(e.title()).isEqualTo(title);
    }

    @Test
    void surrogate_titleOfOnlyEmojiStaysWellFormed() {
        CalendarEvent e = oneEvent("SUMMARY:" + EMOJI.repeat(400));
        assertThat(e.title().length()).isLessThanOrEqualTo(IcsParser.MAX_TITLE_CHARS);
        assertThat(wellFormed(e.title())).isTrue();
    }

    @Test
    void surrogate_attendeeNameCutInTheMiddleOfAnEmojiStaysWellFormed() {
        String cn = "n".repeat(IcsParser.MAX_NAME_CHARS - 1) + EMOJI + "tail".repeat(10);
        CalendarEvent e = oneEvent("ATTENDEE;CN=" + cn + ":mailto:emoji@example.com");
        assertThat(e.attendees()).hasSize(1);
        String name = e.attendees().get(0).name();
        assertThat(name.length()).isLessThanOrEqualTo(IcsParser.MAX_NAME_CHARS);
        assertThat(wellFormed(name)).isTrue();
    }

    @Test
    void surrogate_attendeeNameWithEmojiEndingExactlyAtTheCapIsKept() {
        String cn = "n".repeat(IcsParser.MAX_NAME_CHARS - 2) + EMOJI;
        CalendarEvent e = oneEvent("ATTENDEE;CN=" + cn + ":mailto:emoji@example.com");
        assertThat(e.attendees().get(0).name()).isEqualTo(cn);
    }

    @Test
    void surrogate_conferenceUrlAtTheCapStaysWellFormedOrIsDropped() {
        String prefix = "https://meet.example.com/";
        String url = prefix + "a".repeat(IcsParser.MAX_URL_CHARS - prefix.length() - 1) + EMOJI + "tail".repeat(10);
        CalendarEvent e = oneEvent("URL:" + url);
        if (e.conferenceUrl() != null) {
            assertThat(e.conferenceUrl().length()).isLessThanOrEqualTo(IcsParser.MAX_URL_CHARS);
            assertThat(wellFormed(e.conferenceUrl())).isTrue();
        }
    }

    @Test
    void surrogate_attendeeEmailAtTheCapStaysWellFormedOrIsDropped() {
        String local = "e".repeat(IcsParser.MAX_EMAIL_CHARS - "@example.com".length() - 1) + EMOJI + "tail".repeat(5);
        CalendarEvent e = oneEvent("ATTENDEE;CN=Emo:mailto:" + local + "@example.com");
        assertThat(e.attendees()).hasSize(1);
        String email = e.attendees().get(0).email();
        if (email != null) {
            assertThat(email.length()).isLessThanOrEqualTo(IcsParser.MAX_EMAIL_CHARS);
            assertThat(wellFormed(email)).isTrue();
        }
    }

    // ---------------------------------------------------------------- 6. an exhausted budget skips moved instances

    @Test
    void budget_hostileFeedWithManyOverridesFinishesQuicklyAndIsIncomplete() {
        List<String> events = new ArrayList<>(Arrays.asList(hostileEvents(3_000)));
        for (int i = 0; i < 3_000; i++) {
            events.add(vevent("UID:hostile-" + i + "@example.com", "RECURRENCE-ID:19700101T000000Z", "DTSTART:20260302T090000Z",
                    "DTEND:20260302T100000Z", "SUMMARY:Moved " + i));
        }
        CalendarSnapshot s = parseBounded(bytes(cal(events.toArray(String[]::new))), WIDE_START, WIDE_END);
        assertThat(s.complete()).isFalse();
        assertThat(s.warnings()).isNotEmpty();
        assertThat(s.events().size()).isLessThanOrEqualTo(IcsParser.MAX_EVENTS_PER_SYNC);
    }

    @Test
    void budget_oneSeriesWithThousandsOfOverridesStaysBounded() {
        List<String> events = new ArrayList<>(Arrays.asList(hostileEvents(3_000)));
        for (int i = 0; i < 5_000; i++) {
            events.add(vevent("UID:hostile-0@example.com", "RECURRENCE-ID:" + String.format("1970%02d%02dT000000Z", 1 + i % 12, 1 + i % 28),
                    "DTSTART:2026" + String.format("%02d%02d", 1 + i % 12, 1 + i % 28) + "T090000Z", "SUMMARY:Moved " + i));
        }
        CalendarSnapshot s = parseBounded(bytes(cal(events.toArray(String[]::new))), WIDE_START, WIDE_END);
        assertThat(s.complete()).isFalse();
    }
}
