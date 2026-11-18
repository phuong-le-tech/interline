package app.interline.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 AC3: RRULE expansion, EXDATE, RECURRENCE-ID overrides, cancelled instances, unsupported parts, caps, window clipping. */
class IcsRecurrenceTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long WIDE_START = ms(UTC, 2020, 1, 1, 0, 0);
    private static final long WIDE_END = ms(UTC, 2040, 1, 1, 0, 0);
    private static final Path FIXTURES = Path.of("../../../fixtures/calendar");
    private static final String UID = "series@example.com";

    // ---------------------------------------------------------------- helpers

    private static long ms(ZoneId zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli();
    }

    private static long ms(ZoneId zone, LocalDate date, int h, int mi) {
        return ZonedDateTime.of(date, LocalTime.of(h, mi), zone).toInstant().toEpochMilli();
    }

    private static String cal(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("\r\n", events) + "\r\nEND:VCALENDAR\r\n";
    }

    private static String vevent(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT";
    }

    /** A recurring master event in UTC, 09:00-10:00 on the given date (yyyyMMdd), with extra lines (RRULE, EXDATE ...). */
    private static String master(String date, String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:" + UID, "DTSTART:" + date + "T090000Z", "DTEND:" + date + "T100000Z", "SUMMARY:Series"));
        lines.addAll(List.of(extra));
        return vevent(lines.toArray(String[]::new));
    }

    private static CalendarSnapshot parse(ZoneId zone, long from, long to, String... events) {
        return IcsParser.parse(cal(events).getBytes(StandardCharsets.UTF_8), zone, from, to);
    }

    private static CalendarSnapshot parse(ZoneId zone, String... events) {
        return parse(zone, WIDE_START, WIDE_END, events);
    }

    private static List<Long> starts(CalendarSnapshot s) {
        return s.events().stream().map(CalendarEvent::startsAt).toList();
    }

    private static List<Long> utc9(LocalDate... dates) {
        return java.util.Arrays.stream(dates).map(d -> ms(UTC, d, 9, 0)).toList();
    }

    private static String id(long start) {
        return UID + "#" + start;
    }

    private static String fmt(LocalDateTime t) {
        return String.format("%04d%02d%02dT%02d%02d%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond());
    }

    private static final LocalDate MAR2 = LocalDate.of(2026, 3, 2); // a Monday

    // ---------------------------------------------------------------- FREQ=DAILY

    @Test
    void AC3_dailyCount() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(1), MAR2.plusDays(2)));
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC3_occurrenceExternalIdIsUidHashStartEpochMs() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"));
        assertThat(s.events()).extracting(CalendarEvent::externalId)
                .containsExactly(id(ms(UTC, MAR2, 9, 0)), id(ms(UTC, MAR2.plusDays(1), 9, 0)), id(ms(UTC, MAR2.plusDays(2), 9, 0)));
    }

    @Test
    void AC3_occurrencesKeepDurationAndTitle() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"));
        for (CalendarEvent e : s.events()) {
            assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(1).toMillis());
            assertThat(e.title()).isEqualTo("Series");
        }
    }

    @Test
    void AC3_dailyInterval() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;INTERVAL=2;COUNT=4"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2), MAR2.plusDays(4), MAR2.plusDays(6)));
    }

    // ---------------------------------------------------------------- FREQ=WEEKLY

    @Test
    void AC3_weeklyWithoutByDayKeepsDtstartWeekday() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=WEEKLY;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusWeeks(1), MAR2.plusWeeks(2)));
    }

    @Test
    void AC3_weeklyByDayMondayWednesdayFriday() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6"));
        List<Long> expected = new ArrayList<>();
        for (LocalDate d = MAR2; expected.size() < 6; d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.MONDAY || d.getDayOfWeek() == DayOfWeek.WEDNESDAY || d.getDayOfWeek() == DayOfWeek.FRIDAY) {
                expected.add(ms(UTC, d, 9, 0));
            }
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_weeklyIntervalTwoByDayTuesdayThursday() {
        LocalDate tue = LocalDate.of(2026, 3, 3);
        CalendarSnapshot s = parse(UTC, master("20260303", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=6"));
        List<Long> expected = new ArrayList<>();
        for (int week = 0; week < 3; week++) {
            expected.add(ms(UTC, tue.plusWeeks(2L * week), 9, 0));
            expected.add(ms(UTC, tue.plusWeeks(2L * week).plusDays(2), 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_weeklyIntervalThree() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=WEEKLY;INTERVAL=3;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusWeeks(3), MAR2.plusWeeks(6)));
    }

    // ---------------------------------------------------------------- FREQ=MONTHLY

    @Test
    void AC3_monthlyDefaultsToDtstartDayOfMonth() {
        LocalDate d = LocalDate.of(2026, 1, 15);
        CalendarSnapshot s = parse(UTC, master("20260115", "RRULE:FREQ=MONTHLY;COUNT=4"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(d, d.plusMonths(1), d.plusMonths(2), d.plusMonths(3)));
    }

    @Test
    void AC3_monthlyIntervalThree() {
        LocalDate d = LocalDate.of(2026, 1, 15);
        CalendarSnapshot s = parse(UTC, master("20260115", "RRULE:FREQ=MONTHLY;INTERVAL=3;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(d, d.plusMonths(3), d.plusMonths(6)));
    }

    @Test
    void AC3_monthlyDay31SkipsShortMonths() {
        CalendarSnapshot s = parse(UTC, master("20260131", "RRULE:FREQ=MONTHLY;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31)));
    }

    @Test
    void AC3_monthlyByDaySecondTuesday() {
        LocalDate first = LocalDate.of(2026, 1, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.TUESDAY));
        CalendarSnapshot s = parse(UTC, master(first.toString().replace("-", ""), "RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=5"));
        List<Long> expected = new ArrayList<>();
        for (int m = 1; m <= 5; m++) {
            expected.add(ms(UTC, LocalDate.of(2026, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.TUESDAY)), 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_monthlyByDayLastFriday() {
        LocalDate first = LocalDate.of(2026, 1, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.FRIDAY));
        CalendarSnapshot s = parse(UTC, master(first.toString().replace("-", ""), "RRULE:FREQ=MONTHLY;BYDAY=-1FR;COUNT=5"));
        List<Long> expected = new ArrayList<>();
        for (int m = 1; m <= 5; m++) {
            expected.add(ms(UTC, LocalDate.of(2026, m, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.FRIDAY)), 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_monthlyByMonthDayList() {
        CalendarSnapshot s = parse(UTC, master("20260101", "RRULE:FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=5"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 1)));
    }

    @Test
    void AC3_monthlyByMonthDayNegativeIsLastDay() {
        CalendarSnapshot s = parse(UTC, master("20260131", "RRULE:FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=4"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)));
    }

    @Test
    void AC3_monthlyWithByMonthRestrictsMonths() {
        CalendarSnapshot s = parse(UTC, master("20260105", "RRULE:FREQ=MONTHLY;BYMONTH=1,7;BYMONTHDAY=5;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 7, 5), LocalDate.of(2027, 1, 5)));
    }

    // ---------------------------------------------------------------- FREQ=YEARLY

    @Test
    void AC3_yearlyCount() {
        CalendarSnapshot s = parse(UTC, master("20260610", "RRULE:FREQ=YEARLY;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 6, 10), LocalDate.of(2027, 6, 10), LocalDate.of(2028, 6, 10)));
    }

    @Test
    void AC3_yearlyIntervalTwo() {
        CalendarSnapshot s = parse(UTC, master("20260610", "RRULE:FREQ=YEARLY;INTERVAL=2;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 6, 10), LocalDate.of(2028, 6, 10), LocalDate.of(2030, 6, 10)));
    }

    @Test
    void AC3_yearlyByMonthList() {
        CalendarSnapshot s = parse(UTC, master("20260310", "RRULE:FREQ=YEARLY;BYMONTH=3,9;COUNT=4"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 9, 10),
                LocalDate.of(2027, 3, 10), LocalDate.of(2027, 9, 10)));
    }

    @Test
    void AC3_yearlyFebruary29OnlyInLeapYears() {
        CalendarSnapshot s = parse(UTC, master("20240229", "RRULE:FREQ=YEARLY;COUNT=2"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29)));
    }

    @Test
    void AC3_yearlyByMonthDay() {
        CalendarSnapshot s = parse(UTC, master("20260610", "RRULE:FREQ=YEARLY;BYMONTH=6;BYMONTHDAY=10,20;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20), LocalDate.of(2027, 6, 10)));
    }

    // ---------------------------------------------------------------- COUNT / UNTIL

    @Test
    void AC3_countOne() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=1"));
        assertThat(starts(s)).containsExactly(ms(UTC, MAR2, 9, 0));
    }

    @Test
    void AC3_countIncludesExcludedInstances() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=5", "EXDATE:20260303T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2), MAR2.plusDays(3), MAR2.plusDays(4)));
    }

    @Test
    void AC3_untilUtcIsInclusive() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;UNTIL=20260305T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(1), MAR2.plusDays(2), MAR2.plusDays(3)));
    }

    @Test
    void AC3_untilJustBeforeOccurrenceExcludesIt() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;UNTIL=20260305T085959Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(1), MAR2.plusDays(2)));
    }

    @Test
    void AC3_untilFloatingDateTimeUsesLocalZone() {
        String ev = vevent("UID:" + UID, "DTSTART:20260302T090000", "DTEND:20260302T100000", "SUMMARY:x",
                "RRULE:FREQ=DAILY;UNTIL=20260305T235959");
        CalendarSnapshot s = parse(PARIS, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, MAR2, 9, 0), ms(PARIS, MAR2.plusDays(1), 9, 0),
                ms(PARIS, MAR2.plusDays(2), 9, 0), ms(PARIS, MAR2.plusDays(3), 9, 0));
    }

    @Test
    void AC3_untilDateOnAllDaySeriesIsInclusive() {
        String ev = vevent("UID:" + UID, "DTSTART;VALUE=DATE:20260302", "DTEND;VALUE=DATE:20260303", "SUMMARY:x",
                "RRULE:FREQ=DAILY;UNTIL=20260305");
        CalendarSnapshot s = parse(PARIS, ev);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            expected.add(MAR2.plusDays(i).atStartOfDay(PARIS).toInstant().toEpochMilli());
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
        for (CalendarEvent e : s.events()) {
            LocalDate day = Instant.ofEpochMilli(e.startsAt()).atZone(PARIS).toLocalDate();
            assertThat(e.endsAt()).isEqualTo(day.plusDays(1).atStartOfDay(PARIS).toInstant().toEpochMilli());
        }
    }

    @Test
    void AC3_untilUtcOnTzidSeries() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260302T090000", "DTEND;TZID=Europe/Paris:20260302T100000",
                "SUMMARY:x", "RRULE:FREQ=DAILY;UNTIL=20260305T080000Z");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).hasSize(4);
        assertThat(starts(s).get(3)).isEqualTo(ms(PARIS, 2026, 3, 5, 9, 0));
    }

    // ---------------------------------------------------------------- EXDATE

    @Test
    void AC3_exdateSingle() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20260303T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2)));
    }

    @Test
    void AC3_exdateCommaSeparatedValues() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=5", "EXDATE:20260303T090000Z,20260305T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2), MAR2.plusDays(4)));
    }

    @Test
    void AC3_exdateMultipleLines() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=5", "EXDATE:20260303T090000Z", "EXDATE:20260304T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(3), MAR2.plusDays(4)));
    }

    @Test
    void AC3_exdateWithTzidMatchesWallClockAcrossDst() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260323T090000", "DTEND;TZID=Europe/Paris:20260323T100000",
                "SUMMARY:x", "RRULE:FREQ=WEEKLY;COUNT=3", "EXDATE;TZID=Europe/Paris:20260330T090000");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, 2026, 3, 23, 9, 0), ms(PARIS, 2026, 4, 6, 9, 0));
    }

    @Test
    void AC3_exdateAsUtcMatchesInstantOfTzidSeries() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260302T090000", "DTEND;TZID=Europe/Paris:20260302T100000",
                "SUMMARY:x", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20260303T080000Z");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, 2026, 3, 2, 9, 0), ms(PARIS, 2026, 3, 4, 9, 0));
    }

    @Test
    void AC3_exdateAllDay() {
        String ev = vevent("UID:" + UID, "DTSTART;VALUE=DATE:20260302", "DTEND;VALUE=DATE:20260303", "SUMMARY:x",
                "RRULE:FREQ=DAILY;COUNT=3", "EXDATE;VALUE=DATE:20260303");
        CalendarSnapshot s = parse(PARIS, ev);
        assertThat(starts(s)).containsExactly(MAR2.atStartOfDay(PARIS).toInstant().toEpochMilli(),
                MAR2.plusDays(2).atStartOfDay(PARIS).toInstant().toEpochMilli());
    }

    @Test
    void AC3_exdateNotMatchingAnyInstanceHasNoEffect() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20260303T100000Z"));
        assertThat(s.events()).hasSize(3);
    }

    @Test
    void AC3_exdateExcludingFirstOccurrence() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE:20260302T090000Z"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2.plusDays(1), MAR2.plusDays(2)));
    }

    // ---------------------------------------------------------------- RECURRENCE-ID overrides

    private static String override(String recurrenceIdLine, String start, String end, String summary, String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:" + UID, recurrenceIdLine, "DTSTART:" + start, "DTEND:" + end, "SUMMARY:" + summary));
        lines.addAll(List.of(extra));
        return vevent(lines.toArray(String[]::new));
    }

    @Test
    void AC3_overrideMovesInstanceAndKeepsOriginalStartInId() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Moved"));
        assertThat(s.events()).hasSize(3);
        CalendarEvent moved = s.events().get(1);
        assertThat(moved.title()).isEqualTo("Moved");
        assertThat(moved.startsAt()).isEqualTo(ms(UTC, MAR2.plusDays(1), 14, 0));
        assertThat(moved.endsAt()).isEqualTo(ms(UTC, MAR2.plusDays(1), 15, 0));
        assertThat(moved.externalId()).isEqualTo(id(ms(UTC, MAR2.plusDays(1), 9, 0)));
        assertThat(s.events().get(0).title()).isEqualTo("Series");
        assertThat(s.events().get(2).title()).isEqualTo("Series");
    }

    @Test
    void AC3_overrideRetitlesWithoutMoving() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260304T090000Z", "20260304T090000Z", "20260304T100000Z", "Renamed"));
        assertThat(s.events()).hasSize(3);
        assertThat(s.events().get(2).title()).isEqualTo("Renamed");
        assertThat(s.events().get(2).startsAt()).isEqualTo(ms(UTC, MAR2.plusDays(2), 9, 0));
        assertThat(s.events().get(2).externalId()).isEqualTo(id(ms(UTC, MAR2.plusDays(2), 9, 0)));
    }

    @Test
    void AC3_overrideReplacesInsteadOfDuplicatingTheInstance() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260303T090000Z", "20260303T090000Z", "20260303T100000Z", "Same slot"));
        assertThat(s.events()).hasSize(3);
        assertThat(s.events()).extracting(CalendarEvent::externalId).doesNotHaveDuplicates();
        assertThat(s.events()).filteredOn(e -> e.startsAt() == ms(UTC, MAR2.plusDays(1), 9, 0)).hasSize(1);
    }

    @Test
    void AC3_overrideBeforeMasterInFileStillApplies() {
        CalendarSnapshot s = parse(UTC,
                override("RECURRENCE-ID:20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Moved"),
                master("20260302", "RRULE:FREQ=DAILY;COUNT=3"));
        assertThat(s.events()).hasSize(3);
        assertThat(s.events()).extracting(CalendarEvent::title).containsExactly("Series", "Moved", "Series");
    }

    @Test
    void AC3_overrideWithTzidRecurrenceIdMatchesInstance() {
        String m = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260323T090000", "DTEND;TZID=Europe/Paris:20260323T100000",
                "SUMMARY:Series", "RRULE:FREQ=WEEKLY;COUNT=3");
        String o = vevent("UID:" + UID, "RECURRENCE-ID;TZID=Europe/Paris:20260330T090000", "DTSTART;TZID=Europe/Paris:20260330T110000",
                "DTEND;TZID=Europe/Paris:20260330T120000", "SUMMARY:Moved");
        CalendarSnapshot s = parse(UTC, m, o);
        assertThat(s.events()).hasSize(3);
        CalendarEvent moved = s.events().get(1);
        assertThat(moved.startsAt()).isEqualTo(ms(PARIS, 2026, 3, 30, 11, 0));
        assertThat(moved.externalId()).isEqualTo(id(ms(PARIS, 2026, 3, 30, 9, 0)));
    }

    @Test
    void AC3_overrideForOtherUidDoesNotTouchSeries() {
        String other = vevent("UID:other@example.com", "RECURRENCE-ID:20260303T090000Z", "DTSTART:20260303T140000Z",
                "DTEND:20260303T150000Z", "SUMMARY:Other");
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"), other);
        assertThat(s.events().stream().filter(e -> e.title().equals("Series")).count()).isEqualTo(3);
    }

    @Test
    void AC3_overrideMovedOutOfWindowIsDropped() {
        long from = ms(UTC, MAR2, 0, 0);
        long to = ms(UTC, MAR2.plusDays(3), 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260303T090000Z", "20260310T090000Z", "20260310T100000Z", "Far away"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2)));
    }

    @Test
    void AC3_overrideMovedIntoWindowIsKept() {
        long from = ms(UTC, MAR2.plusDays(8), 0, 0);
        long to = ms(UTC, MAR2.plusDays(10), 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260303T090000Z", "20260310T090000Z", "20260310T100000Z", "Moved in"));
        CalendarEvent e = s.events().get(0);
        assertThat(s.events()).hasSize(1);
        assertThat(e.title()).isEqualTo("Moved in");
        assertThat(e.startsAt()).isEqualTo(ms(UTC, 2026, 3, 10, 9, 0));
        assertThat(e.externalId()).isEqualTo(id(ms(UTC, MAR2.plusDays(1), 9, 0)));
    }

    // ---------------------------------------------------------------- cancelled

    @Test
    void AC3_cancelledOverrideDropsOnlyThatInstance() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3"),
                override("RECURRENCE-ID:20260303T090000Z", "20260303T090000Z", "20260303T100000Z", "Cancelled one", "STATUS:CANCELLED"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(2)));
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC3_cancelledMasterDropsTheWholeSeries() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=3", "STATUS:CANCELLED"));
        assertThat(s.events()).isEmpty();
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC3_cancelledOverrideBeforeMasterStillDropsInstance() {
        CalendarSnapshot s = parse(UTC,
                override("RECURRENCE-ID:20260302T090000Z", "20260302T090000Z", "20260302T100000Z", "x", "STATUS:CANCELLED"),
                master("20260302", "RRULE:FREQ=DAILY;COUNT=3"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2.plusDays(1), MAR2.plusDays(2)));
    }

    // ---------------------------------------------------------------- unsupported parts

    @ParameterizedTest(name = "AC3_unsupportedRulePart_{1}")
    @CsvSource(delimiter = '|', value = {
            "RRULE:FREQ=WEEKLY;BYDAY=MO,TU;BYSETPOS=1;COUNT=6|BYSETPOS",
            "RRULE:FREQ=YEARLY;BYWEEKNO=20;COUNT=3|BYWEEKNO",
            "RRULE:FREQ=DAILY;BYHOUR=9,17;COUNT=4|BYHOUR",
            "RRULE:FREQ=HOURLY;COUNT=5|HOURLY"})
    void AC3_unsupportedRulePartKeepsFirstOccurrenceAndWarns(String rrule, String part) {
        CalendarSnapshot s = parse(UTC, master("20260302", rrule));
        assertThat(s.events()).hasSize(1);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(UTC, MAR2, 9, 0));
        assertThat(s.warnings()).hasSize(1);
        assertThat(s.warnings().get(0)).contains(part);
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC3_rdateWithRruleKeepsFirstOccurrenceAndWarns() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=5", "RDATE:20260320T090000Z"));
        assertThat(s.events()).hasSize(1);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(UTC, MAR2, 9, 0));
        assertThat(s.warnings()).hasSize(1);
        assertThat(s.warnings().get(0)).contains("RDATE");
    }

    @Test
    void AC3_rdateWithoutRruleKeepsFirstOccurrenceAndWarns() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RDATE:20260320T090000Z"));
        assertThat(s.events()).hasSize(1);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(UTC, MAR2, 9, 0));
        assertThat(s.warnings()).anyMatch(w -> w.contains("RDATE"));
    }

    @Test
    void AC3_unsupportedRuleOnOneSeriesDoesNotAffectAnother() {
        String other = vevent("UID:other@example.com", "DTSTART:20260302T120000Z", "DTEND:20260302T130000Z", "SUMMARY:ok",
                "RRULE:FREQ=DAILY;COUNT=3");
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;BYHOUR=9;COUNT=4"), other);
        assertThat(s.events().stream().filter(e -> e.title().equals("ok")).count()).isEqualTo(3);
        assertThat(s.events().stream().filter(e -> e.title().equals("Series")).count()).isEqualTo(1);
        assertThat(s.warnings()).hasSize(1);
    }

    // ---------------------------------------------------------------- caps

    @Test
    void AC3_unboundedSeriesIsCutAtFiveHundredOccurrencesWithWarning() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY"));
        assertThat(s.events()).hasSize(IcsParser.MAX_OCCURRENCES_PER_SERIES);
        assertThat(starts(s).get(0)).isEqualTo(ms(UTC, MAR2, 9, 0));
        assertThat(starts(s).get(499)).isEqualTo(ms(UTC, MAR2.plusDays(499), 9, 0));
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC3_countAboveCapIsCutWithWarning() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=600"));
        assertThat(s.events()).hasSize(500);
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC3_countExactlyAtCapHasNoWarning() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=500"));
        assertThat(s.events()).hasSize(500);
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC3_countJustAboveCapWarns() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=501"));
        assertThat(s.events()).hasSize(500);
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC3_manyUnboundedSeriesStayWithinSyncCap() {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(vevent("UID:s" + i + "@example.com", "DTSTART:20260302T0" + (i % 10) + "0000Z", "DTEND:20260302T0" + (i % 10) + "3000Z",
                    "SUMMARY:s" + i, "RRULE:FREQ=DAILY"));
        }
        CalendarSnapshot s = parse(UTC, events.toArray(String[]::new));
        assertThat(s.events().size()).isLessThanOrEqualTo(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isNotEmpty();
    }

    // ---------------------------------------------------------------- window clipping

    @Test
    void AC3_unboundedSeriesClippedToWindowSilently() {
        long from = ms(UTC, 2026, 3, 10, 0, 0);
        long to = ms(UTC, 2026, 3, 13, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260301", "RRULE:FREQ=DAILY"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11), LocalDate.of(2026, 3, 12)));
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC3_clippingKeepsOccurrenceOverlappingWindowStartAndDropsOneStartingAtEnd() {
        String ev = vevent("UID:" + UID, "DTSTART:20260301T233000Z", "DTEND:20260302T003000Z", "SUMMARY:x", "RRULE:FREQ=DAILY");
        long from = ms(UTC, 2026, 3, 10, 0, 0);
        long to = ms(UTC, 2026, 3, 13, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, ev);
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 3, 9, 23, 30), ms(UTC, 2026, 3, 10, 23, 30), ms(UTC, 2026, 3, 11, 23, 30),
                ms(UTC, 2026, 3, 12, 23, 30));
    }

    @Test
    void AC3_occurrenceStartingExactlyAtWindowEndIsDropped() {
        long from = ms(UTC, 2026, 3, 2, 0, 0);
        long to = ms(UTC, 2026, 3, 4, 9, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260302", "RRULE:FREQ=DAILY;COUNT=5"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(MAR2, MAR2.plusDays(1)));
    }

    @Test
    void AC3_occurrencesBeforeWindowStillCountTowardCount() {
        long from = ms(UTC, 2026, 3, 6, 0, 0);
        long to = ms(UTC, 2026, 3, 31, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260302", "RRULE:FREQ=DAILY;COUNT=5"));
        assertThat(starts(s)).containsExactlyElementsOf(utc9(LocalDate.of(2026, 3, 6)));
    }

    @Test
    void AC3_longRunningWeeklySeriesStartedYearsAgoYieldsCorrectWindow() {
        LocalDate start = LocalDate.of(2025, 1, 6); // Monday
        long from = ms(UTC, 2026, 11, 16, 0, 0);
        long to = ms(UTC, 2026, 12, 1, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20250106", "RRULE:FREQ=WEEKLY"));
        List<Long> expected = new ArrayList<>();
        for (LocalDate d = start; d.isBefore(LocalDate.of(2026, 12, 1)); d = d.plusWeeks(1)) {
            long t = ms(UTC, d, 9, 0);
            if (t >= from && t < to) {
                expected.add(t);
            }
        }
        assertThat(expected).hasSize(3);
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_seriesEntirelyOutsideWindowGivesNothingAndNoWarning() {
        long from = ms(UTC, 2027, 1, 1, 0, 0);
        long to = ms(UTC, 2027, 1, 15, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, master("20260302", "RRULE:FREQ=DAILY;COUNT=5"));
        assertThat(s.events()).isEmpty();
        assertThat(s.warnings()).isEmpty();
    }

    // ---------------------------------------------------------------- DST wall-clock behaviour

    @Test
    void AC3_weeklyParisMeetingStaysAtNineLocalAcrossSpringDst() {
        List<Long> expected = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            expected.add(ms(PARIS, LocalDate.of(2026, 3, 23).plusWeeks(w), 9, 0));
        }
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260323T090000", "DTEND;TZID=Europe/Paris:20260323T100000",
                "SUMMARY:x", "RRULE:FREQ=WEEKLY;COUNT=4");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).containsExactlyElementsOf(expected);
        // 08:00Z before the change, 07:00Z after it
        assertThat(starts(s).get(1)).isEqualTo(Instant.parse("2026-03-30T07:00:00Z").toEpochMilli());
        assertThat(starts(s).get(0)).isEqualTo(Instant.parse("2026-03-23T08:00:00Z").toEpochMilli());
        for (CalendarEvent e : s.events()) {
            assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(1).toMillis());
        }
    }

    @Test
    void AC3_fixtureWeeklyDstParis() throws IOException {
        long from = ms(UTC, 2026, 3, 1, 0, 0);
        long to = ms(UTC, 2026, 5, 1, 0, 0);
        CalendarSnapshot s = IcsParser.parse(Files.readAllBytes(FIXTURES.resolve("recurring-weekly-dst.ics")), UTC, from, to);
        List<Long> expected = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            expected.add(ms(PARIS, LocalDate.of(2026, 3, 23).plusWeeks(w), 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
        assertThat(s.events()).extracting(CalendarEvent::externalId)
                .containsExactlyElementsOf(expected.stream().map(t -> "weekly-dst@example.com#" + t).toList());
        assertThat(s.events()).extracting(CalendarEvent::title).containsOnly("Weekly sync");
        assertThat(s.warnings()).isEmpty();
        assertThat(expected.get(2) - expected.get(1)).isEqualTo(Duration.ofDays(7).toMillis());
        assertThat(expected.get(1) - expected.get(0)).isEqualTo(Duration.ofDays(7).minusHours(1).toMillis());
    }

    @Test
    void AC3_dailyParisAcrossSpringDstKeepsLocalTime() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260327T090000", "DTEND;TZID=Europe/Paris:20260327T100000",
                "SUMMARY:x", "RRULE:FREQ=DAILY;COUNT=4");
        CalendarSnapshot s = parse(UTC, ev);
        List<Long> expected = new ArrayList<>();
        for (int d = 0; d < 4; d++) {
            expected.add(ms(PARIS, LocalDate.of(2026, 3, 27).plusDays(d), 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void AC3_weeklyParisAcrossAutumnDstKeepsLocalTime() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20261019T090000", "DTEND;TZID=Europe/Paris:20261019T100000",
                "SUMMARY:x", "RRULE:FREQ=WEEKLY;COUNT=3");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, 2026, 10, 19, 9, 0), ms(PARIS, 2026, 10, 26, 9, 0), ms(PARIS, 2026, 11, 2, 9, 0));
        assertThat(starts(s).get(1)).isEqualTo(Instant.parse("2026-10-26T08:00:00Z").toEpochMilli());
    }

    @Test
    void AC3_monthlyParisAcrossDstKeepsLocalTime() {
        String ev = vevent("UID:" + UID, "DTSTART;TZID=Europe/Paris:20260315T090000", "DTEND;TZID=Europe/Paris:20260315T100000",
                "SUMMARY:x", "RRULE:FREQ=MONTHLY;COUNT=3");
        CalendarSnapshot s = parse(UTC, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, 2026, 3, 15, 9, 0), ms(PARIS, 2026, 4, 15, 9, 0), ms(PARIS, 2026, 5, 15, 9, 0));
    }

    @Test
    void AC3_utcSeriesIsNotShiftedByDst() {
        CalendarSnapshot s = parse(PARIS, master("20260323", "RRULE:FREQ=WEEKLY;COUNT=3"));
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 3, 23, 9, 0), ms(UTC, 2026, 3, 30, 9, 0), ms(UTC, 2026, 4, 6, 9, 0));
        assertThat(starts(s).get(1) - starts(s).get(0)).isEqualTo(Duration.ofDays(7).toMillis());
    }

    @Test
    void AC3_floatingSeriesFollowsLocalZoneDst() {
        String ev = vevent("UID:" + UID, "DTSTART:20260323T090000", "DTEND:20260323T100000", "SUMMARY:x", "RRULE:FREQ=WEEKLY;COUNT=3");
        CalendarSnapshot s = parse(PARIS, ev);
        assertThat(starts(s)).containsExactly(ms(PARIS, 2026, 3, 23, 9, 0), ms(PARIS, 2026, 3, 30, 9, 0), ms(PARIS, 2026, 4, 6, 9, 0));
    }

    @Test
    void AC3_allDayWeeklySeriesInParisAcrossDst() {
        String ev = vevent("UID:" + UID, "DTSTART;VALUE=DATE:20260323", "DTEND;VALUE=DATE:20260324", "SUMMARY:x", "RRULE:FREQ=WEEKLY;COUNT=2");
        CalendarSnapshot s = parse(PARIS, ev);
        assertThat(s.events()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            LocalDate d = LocalDate.of(2026, 3, 23).plusWeeks(i);
            assertThat(s.events().get(i).startsAt()).isEqualTo(d.atStartOfDay(PARIS).toInstant().toEpochMilli());
            assertThat(s.events().get(i).endsAt()).isEqualTo(d.plusDays(1).atStartOfDay(PARIS).toInstant().toEpochMilli());
        }
    }

    // ---------------------------------------------------------------- ordering / misc

    @Test
    void AC3_interleavedSeriesAreOrderedByStart() {
        String a = vevent("UID:a@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", "SUMMARY:a", "RRULE:FREQ=DAILY;COUNT=2");
        String b = vevent("UID:b@example.com", "DTSTART:20260302T120000Z", "DTEND:20260302T130000Z", "SUMMARY:b", "RRULE:FREQ=DAILY;COUNT=2");
        CalendarSnapshot s = parse(UTC, b, a);
        assertThat(s.events()).extracting(CalendarEvent::title).containsExactly("a", "b", "a", "b");
        assertThat(starts(s)).isSorted();
    }

    @Test
    void AC3_recurringAttendeesAreCopiedToEveryOccurrence() {
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=2", "ORGANIZER;CN=Olga:mailto:olga@example.com",
                "ATTENDEE;CN=Bob:mailto:bob@example.com"));
        for (CalendarEvent e : s.events()) {
            assertThat(e.attendees()).containsExactly(new Attendee("Olga", "olga@example.com"), new Attendee("Bob", "bob@example.com"));
        }
    }

    @Test
    void AC3_nonRecurringEventNextToSeriesKeepsPlainUid() {
        String single = vevent("UID:single@example.com", "DTSTART:20260302T180000Z", "DTEND:20260302T190000Z", "SUMMARY:single");
        CalendarSnapshot s = parse(UTC, master("20260302", "RRULE:FREQ=DAILY;COUNT=2"), single);
        assertThat(s.events()).extracting(CalendarEvent::externalId).contains("single@example.com");
        assertThat(s.events().stream().filter(e -> e.externalId().startsWith(UID + "#")).count()).isEqualTo(2);
    }

    @Test
    void AC3_fmtHelperRoundTripsForDocumentationOfExpectedStrings() {
        // Sanity check of the helper used to build DTSTART strings in this class.
        assertThat(fmt(LocalDateTime.of(2026, 3, 2, 9, 5, 7))).isEqualTo("20260302T090507");
    }
}
