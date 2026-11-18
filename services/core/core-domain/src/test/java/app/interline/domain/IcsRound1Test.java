package app.interline.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 review round 1, domain items 1-3, 5 and 6: complete-calendar check, recurrence fixes (RECURRENCE-ID window rule, WKST,
 * YEARLY BYMONTHDAY), DURATION semantics, override inheritance and field caps. Expected epoch values come from java.time.
 */
class IcsRound1Test {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long WIDE_START = ms(UTC, 1990, 1, 1, 0, 0);
    private static final long WIDE_END = ms(UTC, 2100, 1, 1, 0, 0);
    private static final String UID = "series@example.com";

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

    private static List<Long> starts(CalendarSnapshot s) {
        return s.events().stream().map(CalendarEvent::startsAt).toList();
    }

    private static String id(long recurrenceMs) {
        return UID + "#" + recurrenceMs;
    }

    private static CalendarEvent single(CalendarSnapshot s) {
        assertThat(s.events()).hasSize(1);
        return s.events().get(0);
    }

    // ---------------------------------------------------------------- 1. isCompleteCalendar

    private static boolean complete(String body) {
        return IcsParser.isCompleteCalendar(bytes(body));
    }

    @Test
    void complete_validCalendarWithCrlf() {
        assertThat(complete(cal(vevent("UID:a", "DTSTART:20260302T090000Z")))).isTrue();
    }

    @Test
    void complete_emptyButValidCalendar() {
        assertThat(complete("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")).isTrue();
    }

    @Test
    void complete_lfOnlyAndNoTrailingNewline() {
        assertThat(complete("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR")).isTrue();
    }

    @Test
    void complete_caseInsensitiveNames() {
        assertThat(complete("begin:vcalendar\r\nVERSION:2.0\r\nend:vcalendar\r\n")).isTrue();
        assertThat(complete("Begin:VCalendar\nEnd:VCalendar\n")).isTrue();
    }

    @Test
    void complete_toleratesLeadingBomAndBlankLines() {
        assertThat(complete("﻿BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")).isTrue();
        assertThat(complete("\r\n   \r\n\nBEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")).isTrue();
        assertThat(complete("﻿\r\n\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR")).isTrue();
    }

    @Test
    void complete_htmlPageIsNotACalendar() {
        assertThat(complete("<!DOCTYPE html><html><head><title>Sign in</title></head><body>Please sign in</body></html>")).isFalse();
    }

    @Test
    void complete_emptyAndBlankBodies() {
        assertThat(IcsParser.isCompleteCalendar(new byte[0])).isFalse();
        assertThat(complete("")).isFalse();
        assertThat(complete("   \r\n\r\n \n")).isFalse();
    }

    @Test
    void complete_truncatedWithoutEnd() {
        assertThat(complete("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260302T090000Z\r\n")).isFalse();
        assertThat(complete("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEV")).isFalse();
    }

    @Test
    void complete_endBeforeBeginIsNotComplete() {
        assertThat(complete("END:VCALENDAR\r\nBEGIN:VCALENDAR\r\n")).isFalse();
    }

    @Test
    void complete_onlyEndOrOnlyBegin() {
        assertThat(complete("END:VCALENDAR\r\n")).isFalse();
        assertThat(complete("BEGIN:VCALENDAR\r\n")).isFalse();
    }

    @Test
    void complete_garbageStaysTotalForParse() {
        for (String body : List.of("", "<html>nope</html>", "BEGIN:VCALENDAR\r\nBEGIN:VEV", "END:VCALENDAR\r\nBEGIN:VCALENDAR")) {
            CalendarSnapshot s = IcsParser.parse(bytes(body), UTC, WIDE_START, WIDE_END);
            assertThat(s.events()).isEmpty();
            assertThat(s.skipped()).isZero();
        }
    }

    // ---------------------------------------------------------------- 2a. RECURRENCE-ID versus the window

    private static String master(String dtstartLine, String dtendLine, String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:" + UID, dtstartLine, dtendLine, "SUMMARY:Series"));
        lines.addAll(List.of(extra));
        return vevent(lines.toArray(String[]::new));
    }

    private static String override(String recurrenceId, String start, String end, String summary, String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:" + UID, "RECURRENCE-ID:" + recurrenceId, "DTSTART:" + start, "DTEND:" + end, "SUMMARY:" + summary));
        lines.addAll(List.of(extra));
        return vevent(lines.toArray(String[]::new));
    }

    private static String dailyMaster(int count) {
        return master("DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", "RRULE:FREQ=DAILY;COUNT=" + count);
    }

    @Test
    void recurrenceId_outsideWindowButOwnStartInsideIsReturned() {
        // instance of Mar 3 (before the window) moved to Mar 10 (inside the window)
        long from = ms(UTC, 2026, 3, 9, 0, 0);
        long to = ms(UTC, 2026, 3, 11, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, dailyMaster(5),
                override("20260303T090000Z", "20260310T090000Z", "20260310T100000Z", "Moved in"));
        CalendarEvent e = single(s);
        assertThat(e.title()).isEqualTo("Moved in");
        assertThat(e.startsAt()).isEqualTo(ms(UTC, 2026, 3, 10, 9, 0));
        assertThat(e.externalId()).isEqualTo(id(ms(UTC, 2026, 3, 3, 9, 0)));
    }

    @Test
    void recurrenceId_afterWindowEndButOwnStartInsideIsReturned() {
        // instance of Mar 5 (after the window) moved back to Mar 2 afternoon (inside the window)
        long from = ms(UTC, 2026, 3, 2, 0, 0);
        long to = ms(UTC, 2026, 3, 3, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, dailyMaster(5),
                override("20260305T090000Z", "20260302T150000Z", "20260302T160000Z", "Moved earlier"));
        assertThat(s.events()).hasSize(2);
        assertThat(s.events()).extracting(CalendarEvent::externalId)
                .contains(id(ms(UTC, 2026, 3, 5, 9, 0)), id(ms(UTC, 2026, 3, 2, 9, 0)));
        CalendarEvent moved = s.events().stream().filter(e -> e.title().equals("Moved earlier")).findFirst().orElseThrow();
        assertThat(moved.startsAt()).isEqualTo(ms(UTC, 2026, 3, 2, 15, 0));
    }

    @Test
    void recurrenceId_insideWindowButMovedOutsideIsDropped() {
        long from = ms(UTC, 2026, 3, 2, 0, 0);
        long to = ms(UTC, 2026, 3, 5, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, dailyMaster(5),
                override("20260303T090000Z", "20260320T090000Z", "20260320T100000Z", "Moved out"));
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 3, 2, 9, 0), ms(UTC, 2026, 3, 4, 9, 0));
        assertThat(s.events()).extracting(CalendarEvent::title).doesNotContain("Moved out");
    }

    @Test
    void recurrenceId_bothOutsideWindowIsDropped() {
        long from = ms(UTC, 2026, 4, 1, 0, 0);
        long to = ms(UTC, 2026, 4, 2, 0, 0);
        CalendarSnapshot s = parse(UTC, from, to, dailyMaster(5),
                override("20260303T090000Z", "20260310T090000Z", "20260310T100000Z", "Nowhere"));
        assertThat(s.events()).isEmpty();
    }

    // ---------------------------------------------------------------- 2b. WKST

    private static List<Long> ny9(int... days) {
        List<Long> out = new ArrayList<>();
        for (int d : days) {
            out.add(ms(NEW_YORK, 1997, 8, d, 9, 0));
        }
        return out;
    }

    private static CalendarSnapshot wkst(String wkstPart) {
        String m = vevent("UID:" + UID, "DTSTART;TZID=America/New_York:19970805T090000", "DTEND;TZID=America/New_York:19970805T100000",
                "SUMMARY:Series", "RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=TU,SU" + wkstPart);
        return parse(UTC, ms(UTC, 1997, 1, 1, 0, 0), ms(UTC, 1998, 1, 1, 0, 0), m);
    }

    @Test
    void wkst_mondayRfcExample() {
        CalendarSnapshot s = wkst(";WKST=MO");
        assertThat(starts(s)).containsExactlyElementsOf(ny9(5, 10, 19, 24));
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void wkst_sundayRfcExample() {
        CalendarSnapshot s = wkst(";WKST=SU");
        assertThat(starts(s)).containsExactlyElementsOf(ny9(5, 17, 19, 31));
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void wkst_defaultsToMonday() {
        assertThat(starts(wkst(""))).containsExactlyElementsOf(ny9(5, 10, 19, 24));
    }

    // ---------------------------------------------------------------- 2c. YEARLY BYMONTHDAY

    @Test
    void yearly_byMonthDayWithoutByMonthAppliesToEveryMonth() {
        String m = master("DTSTART:20260115T090000Z", "DTEND:20260115T100000Z", "RRULE:FREQ=YEARLY;BYMONTHDAY=15");
        CalendarSnapshot s = parse(UTC, ms(UTC, 2026, 1, 1, 0, 0), ms(UTC, 2027, 1, 1, 0, 0), m);
        List<Long> expected = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            expected.add(ms(UTC, 2026, month, 15, 9, 0));
        }
        assertThat(starts(s)).containsExactlyElementsOf(expected);
    }

    @Test
    void yearly_byMonthDayWithoutByMonthTwelvePerYear() {
        String m = master("DTSTART:20260115T090000Z", "DTEND:20260115T100000Z", "RRULE:FREQ=YEARLY;BYMONTHDAY=15");
        CalendarSnapshot s = parse(UTC, ms(UTC, 2026, 1, 1, 0, 0), ms(UTC, 2028, 1, 1, 0, 0), m);
        assertThat(s.events()).hasSize(24);
    }

    @Test
    void yearly_byMonthDayWithByMonthOnlyThoseMonths() {
        String m = master("DTSTART:20260315T090000Z", "DTEND:20260315T100000Z", "RRULE:FREQ=YEARLY;BYMONTHDAY=15;BYMONTH=3,9");
        CalendarSnapshot s = parse(UTC, ms(UTC, 2026, 1, 1, 0, 0), ms(UTC, 2028, 1, 1, 0, 0), m);
        assertThat(starts(s)).containsExactly(ms(UTC, 2026, 3, 15, 9, 0), ms(UTC, 2026, 9, 15, 9, 0),
                ms(UTC, 2027, 3, 15, 9, 0), ms(UTC, 2027, 9, 15, 9, 0));
    }

    // ---------------------------------------------------------------- 3. DURATION: elapsed time versus nominal days

    private static CalendarSnapshot withDuration(String localStart, String duration, String... rrule) {
        List<String> lines = new ArrayList<>(List.of("UID:" + UID, "DTSTART;TZID=Europe/Paris:" + localStart, "DURATION:" + duration, "SUMMARY:D"));
        lines.addAll(List.of(rrule));
        return parse(UTC, vevent(lines.toArray(String[]::new)));
    }

    private static long zdt(int y, int mo, int d, int h, int mi) {
        return ms(PARIS, y, mo, d, h, mi);
    }

    @Test
    void duration_timedPartIsElapsedAcrossSpringForward() {
        CalendarEvent e = single(withDuration("20260329T013000", "PT2H"));
        long start = zdt(2026, 3, 29, 1, 30);
        assertThat(e.startsAt()).isEqualTo(start);
        assertThat(e.endsAt()).isEqualTo(start + Duration.ofHours(2).toMillis());
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 29, 4, 30));
    }

    @Test
    void duration_timedAcrossTransitionFromBefore() {
        CalendarEvent e = single(withDuration("20260329T003000", "PT3H"));
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 29, 0, 30) + Duration.ofHours(3).toMillis());
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 29, 4, 30));
    }

    @Test
    void duration_dayPartIsNominalCalendarDay() {
        CalendarEvent e = single(withDuration("20260328T090000", "P1D"));
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 29, 9, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(23).toMillis());
    }

    @Test
    void duration_dayPlusTimedMixesNominalAndElapsed() {
        CalendarEvent e = single(withDuration("20260328T090000", "P1DT2H"));
        long expected = ZonedDateTime.of(2026, 3, 28, 9, 0, 0, 0, PARIS).plusDays(1).toInstant().toEpochMilli() + Duration.ofHours(2).toMillis();
        assertThat(e.endsAt()).isEqualTo(expected);
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 29, 11, 0));
    }

    @Test
    void duration_dayPlusTimedAcrossTransitionLateEvening() {
        CalendarEvent e = single(withDuration("20260328T230000", "P1DT2H"));
        long expected = ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, PARIS).plusDays(1).toInstant().toEpochMilli() + Duration.ofHours(2).toMillis();
        assertThat(e.endsAt()).isEqualTo(expected);
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 3, 30, 1, 0));
    }

    @Test
    void duration_weekIsSevenNominalDays() {
        CalendarEvent e = single(withDuration("20260325T090000", "P1W"));
        assertThat(e.endsAt()).isEqualTo(zdt(2026, 4, 1, 9, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(7 * 24 - 1).toMillis());
    }

    @Test
    void duration_timedPartsHoursMinutesSecondsAreElapsed() {
        CalendarEvent e = single(withDuration("20260329T013000", "PT1H30M30S"));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofSeconds(3600 + 1800 + 30).toMillis());
    }

    @Test
    void duration_recurringTimedInstancesEndAtStartPlusElapsed() {
        CalendarSnapshot s = withDuration("20260328T013000", "PT2H", "RRULE:FREQ=DAILY;COUNT=3");
        assertThat(s.events()).hasSize(3);
        for (CalendarEvent e : s.events()) {
            assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(2).toMillis());
        }
        assertThat(s.events().get(1).startsAt()).isEqualTo(zdt(2026, 3, 29, 1, 30));
        assertThat(s.events().get(1).endsAt()).isEqualTo(zdt(2026, 3, 29, 4, 30));
    }

    @Test
    void duration_recurringDayInstancesAreNominalDays() {
        CalendarSnapshot s = withDuration("20260327T090000", "P1D", "RRULE:FREQ=DAILY;COUNT=4");
        assertThat(s.events()).hasSize(4);
        LocalDate day = LocalDate.of(2026, 3, 27);
        for (int i = 0; i < 4; i++) {
            CalendarEvent e = s.events().get(i);
            assertThat(e.startsAt()).isEqualTo(zdt(2026, 3, 27 + i, 9, 0));
            assertThat(e.endsAt()).isEqualTo(ms(PARIS, day.plusDays(i + 1).getYear(), day.plusDays(i + 1).getMonthValue(),
                    day.plusDays(i + 1).getDayOfMonth(), 9, 0));
        }
        // the instance of Mar 28 spans the spring-forward night (23 hours), its neighbours are 24 hours
        assertThat(s.events().get(0).endsAt() - s.events().get(0).startsAt()).isEqualTo(Duration.ofHours(24).toMillis());
        assertThat(s.events().get(1).endsAt() - s.events().get(1).startsAt()).isEqualTo(Duration.ofHours(23).toMillis());
        assertThat(s.events().get(2).endsAt() - s.events().get(2).startsAt()).isEqualTo(Duration.ofHours(24).toMillis());
    }

    @Test
    void duration_recurringDayInstanceSpanningTransitionIs23Hours() {
        CalendarSnapshot s = withDuration("20260328T090000", "P1D", "RRULE:FREQ=DAILY;COUNT=3");
        assertThat(s.events()).hasSize(3);
        assertThat(s.events().get(0).endsAt() - s.events().get(0).startsAt()).isEqualTo(Duration.ofHours(23).toMillis());
        assertThat(s.events().get(1).endsAt() - s.events().get(1).startsAt()).isEqualTo(Duration.ofHours(24).toMillis());
        assertThat(s.events().get(1).endsAt()).isEqualTo(zdt(2026, 3, 30, 9, 0));
    }

    // ---------------------------------------------------------------- 5. override inherits attendees and conference URL

    private static final String MASTER_PEOPLE_URL_RRULE = "RRULE:FREQ=DAILY;COUNT=3";

    private static String peopleMaster() {
        return master("DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", MASTER_PEOPLE_URL_RRULE,
                "ORGANIZER;CN=Olivia:mailto:olivia@example.com", "ATTENDEE;CN=Alice:mailto:alice@example.com",
                "ATTENDEE;CN=Bob:mailto:bob@example.com", "URL:https://meet.example.com/master");
    }

    private static CalendarEvent secondInstance(CalendarSnapshot s) {
        return s.events().stream().filter(e -> e.externalId().equals(id(ms(UTC, 2026, 3, 3, 9, 0)))).findFirst().orElseThrow();
    }

    @Test
    void inherit_overrideWithoutPeopleOrUrlInheritsFromMaster() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Moved"));
        CalendarEvent e = secondInstance(s);
        assertThat(e.title()).isEqualTo("Moved");
        assertThat(e.startsAt()).isEqualTo(ms(UTC, 2026, 3, 3, 14, 0));
        assertThat(e.endsAt()).isEqualTo(ms(UTC, 2026, 3, 3, 15, 0));
        assertThat(e.conferenceUrl()).isEqualTo("https://meet.example.com/master");
        assertThat(e.attendees()).extracting(Attendee::email)
                .containsExactlyInAnyOrder("olivia@example.com", "alice@example.com", "bob@example.com");
    }

    @Test
    void inherit_titleAndTimeStayTheOverridesOwn() {
        String master = master("DTSTART:20260302T090000Z", "DTEND:20260302T100000Z", MASTER_PEOPLE_URL_RRULE,
                "ATTENDEE:mailto:alice@example.com", "URL:https://meet.example.com/master");
        CalendarSnapshot s = parse(UTC, master,
                override("20260303T090000Z", "20260303T091500Z", "20260303T094500Z", "Shorter"));
        CalendarEvent e = secondInstance(s);
        assertThat(e.title()).isEqualTo("Shorter");
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(s.events().stream().filter(x -> x.title().equals("Series")).count()).isEqualTo(2);
    }

    @Test
    void inherit_overrideWithOwnAttendeeKeepsOnlyItsOwn() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Mine", "ATTENDEE;CN=Carol:mailto:carol@example.com"));
        CalendarEvent e = secondInstance(s);
        assertThat(e.attendees()).extracting(Attendee::email).containsExactly("carol@example.com");
    }

    @Test
    void inherit_overrideWithOwnOrganizerKeepsOnlyItsOwn() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Mine", "ORGANIZER;CN=Dan:mailto:dan@example.com"));
        CalendarEvent e = secondInstance(s);
        assertThat(e.attendees()).extracting(Attendee::email).containsExactly("dan@example.com");
    }

    @Test
    void inherit_overrideOwnConferenceUrlIsKept() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Own link", "URL:https://meet.example.com/own"));
        assertThat(secondInstance(s).conferenceUrl()).isEqualTo("https://meet.example.com/own");
    }

    @Test
    void inherit_overrideOwnLocationHttpsIsKept() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Own loc", "LOCATION:https://zoom.example.com/j/1"));
        assertThat(secondInstance(s).conferenceUrl()).isEqualTo("https://zoom.example.com/j/1");
    }

    @Test
    void inherit_otherInstancesKeepMasterPeople() {
        CalendarSnapshot s = parse(UTC, peopleMaster(),
                override("20260303T090000Z", "20260303T140000Z", "20260303T150000Z", "Mine", "ATTENDEE;CN=Carol:mailto:carol@example.com"));
        CalendarEvent first = s.events().stream().filter(e -> e.externalId().equals(id(ms(UTC, 2026, 3, 2, 9, 0)))).findFirst().orElseThrow();
        assertThat(first.attendees()).hasSize(3);
        assertThat(first.conferenceUrl()).isEqualTo("https://meet.example.com/master");
    }

    // ---------------------------------------------------------------- 6. field caps

    private static CalendarSnapshot oneEvent(String... extra) {
        List<String> lines = new ArrayList<>(List.of("UID:caps@example.com", "DTSTART:20260302T090000Z", "DTEND:20260302T100000Z"));
        lines.addAll(List.of(extra));
        return parse(UTC, vevent(lines.toArray(String[]::new)));
    }

    @Test
    void caps_constantsHaveTheContractValues() {
        assertThat((long) IcsParser.MAX_ATTENDEES_PER_EVENT).isEqualTo(200L);
        assertThat((long) IcsParser.MAX_TITLE_CHARS).isEqualTo(500L);
        assertThat((long) IcsParser.MAX_NAME_CHARS).isEqualTo(200L);
        assertThat((long) IcsParser.MAX_EMAIL_CHARS).isEqualTo(320L);
        assertThat((long) IcsParser.MAX_URL_CHARS).isEqualTo(2048L);
    }

    private static String[] attendees(int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = "ATTENDEE;CN=Person " + i + ":mailto:p" + i + "@example.com";
        }
        return out;
    }

    @Test
    void caps_attendeesBeyond200AreDroppedWithWarning() {
        CalendarSnapshot s = oneEvent(attendees(250));
        CalendarEvent e = single(s);
        assertThat(e.attendees()).hasSize(IcsParser.MAX_ATTENDEES_PER_EVENT);
        assertThat(e.attendees().get(0).email()).isEqualTo("p0@example.com");
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void caps_exactly200AttendeesAreKeptWithoutWarning() {
        CalendarSnapshot s = oneEvent(attendees(200));
        assertThat(single(s).attendees()).hasSize(200);
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void caps_titleLongerThan500IsCutWithWarning() {
        String title = "T".repeat(600);
        CalendarSnapshot s = oneEvent("SUMMARY:" + title);
        assertThat(single(s).title()).hasSize(IcsParser.MAX_TITLE_CHARS);
        assertThat(single(s).title()).isEqualTo(title.substring(0, 500));
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void caps_titleOfExactly500IsKeptWithoutWarning() {
        CalendarSnapshot s = oneEvent("SUMMARY:" + "T".repeat(500));
        assertThat(single(s).title()).hasSize(500);
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void caps_attendeeNameLongerThan200IsCutWithWarning() {
        CalendarSnapshot s = oneEvent("ATTENDEE;CN=" + "N".repeat(300) + ":mailto:big@example.com");
        Attendee a = single(s).attendees().get(0);
        assertThat(a.name()).hasSize(IcsParser.MAX_NAME_CHARS);
        assertThat(a.email()).isEqualTo("big@example.com");
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void caps_attendeeEmailLongerThan320IsKeptWithoutEmail() {
        String email = "e".repeat(330) + "@example.com";
        CalendarSnapshot s = oneEvent("ATTENDEE;CN=Longmail:mailto:" + email);
        Attendee a = single(s).attendees().get(0);
        assertThat(a.email()).isNull();
        assertThat(a.name()).isEqualTo("Longmail");
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void caps_emailOfExactly320IsKept() {
        String email = "e".repeat(320 - "@example.com".length()) + "@example.com";
        assertThat(email).hasSize(320);
        CalendarSnapshot s = oneEvent("ATTENDEE;CN=Edge:mailto:" + email);
        assertThat(single(s).attendees().get(0).email()).isEqualTo(email);
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void caps_conferenceUrlLongerThan2048IsNullWithWarning() {
        String url = "https://example.com/" + "a".repeat(2100);
        CalendarSnapshot s = oneEvent("URL:" + url);
        assertThat(single(s).conferenceUrl()).isNull();
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void caps_conferenceUrlOfExactly2048IsKept() {
        String url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length());
        assertThat(url).hasSize(2048);
        CalendarSnapshot s = oneEvent("URL:" + url);
        assertThat(single(s).conferenceUrl()).isEqualTo(url);
        assertThat(s.warnings()).isEmpty();
    }
}
