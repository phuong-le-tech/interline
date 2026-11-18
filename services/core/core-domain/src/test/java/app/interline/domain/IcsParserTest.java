package app.interline.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * M3 AC2 (parser edge cases), AC4 (attendees, unit part) and AC6 (limits, malformed input and fuzz, unit part).
 * Every expected epoch value is computed with java.time in the test.
 */
class IcsParserTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long WIDE_START = ms(UTC, 2000, 1, 1, 0, 0);
    private static final long WIDE_END = ms(UTC, 2100, 1, 1, 0, 0);
    private static final Path FIXTURES = Path.of("../../../fixtures/calendar");

    // ---------------------------------------------------------------- helpers

    static long ms(ZoneId zone, int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Wraps lines (joined with CRLF) in a VCALENDAR. */
    private static String cal(String... lines) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n" + String.join("\r\n", lines) + "\r\nEND:VCALENDAR\r\n";
    }

    private static String event(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT";
    }

    private static CalendarSnapshot parse(String body, ZoneId zone) {
        return IcsParser.parse(bytes(body), zone, WIDE_START, WIDE_END);
    }

    private static CalendarSnapshot parseEvent(ZoneId zone, String... lines) {
        return parse(cal(event(lines)), zone);
    }

    private static CalendarEvent single(CalendarSnapshot s) {
        assertThat(s.events()).hasSize(1);
        return s.events().get(0);
    }

    private static CalendarSnapshot fixture(String name, ZoneId zone, long from, long to) throws IOException {
        return IcsParser.parse(Files.readAllBytes(FIXTURES.resolve(name)), zone, from, to);
    }

    private static String fmt(LocalDateTime t) {
        return String.format("%04d%02d%02dT%02d%02d%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond());
    }

    private static void assertSane(CalendarSnapshot s) {
        assertThat(s).isNotNull();
        assertThat(s.events()).isNotNull();
        assertThat(s.warnings()).isNotNull();
        assertThat(s.skipped()).isGreaterThanOrEqualTo(0);
    }

    // ---------------------------------------------------------------- AC2: times

    @Test
    void AC2_utcTimesAreEpochExact() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u1@example.com", "DTSTART:20261118T140000Z",
                "DTEND:20261118T153000Z", "SUMMARY:Standup"));
        assertThat(e.externalId()).isEqualTo("u1@example.com");
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-11-18T14:00:00Z").toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(Instant.parse("2026-11-18T15:30:00Z").toEpochMilli());
        assertThat(e.title()).isEqualTo("Standup");
    }

    @Test
    void AC2_utcIgnoresLocalZone() {
        long expected = Instant.parse("2026-07-01T08:00:00Z").toEpochMilli();
        for (ZoneId zone : List.of(UTC, PARIS, ZoneId.of("America/New_York"))) {
            CalendarEvent e = single(parseEvent(zone, "UID:u@example.com", "DTSTART:20260701T080000Z",
                    "DTEND:20260701T090000Z", "SUMMARY:x"));
            assertThat(e.startsAt()).isEqualTo(expected);
        }
    }

    @Test
    void AC2_tzidIanaWinterOffset() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20261118T140000",
                "DTEND;TZID=Europe/Paris:20261118T150000", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 11, 18, 14, 0));
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 11, 18, 15, 0));
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-11-18T13:00:00Z").toEpochMilli());
    }

    @Test
    void AC2_tzidIanaSummerOffset() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20260718T140000",
                "DTEND;TZID=Europe/Paris:20260718T150000", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 7, 18, 14, 0));
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-07-18T12:00:00Z").toEpochMilli());
    }

    @Test
    void AC2_tzidOtherIanaZone() {
        ZoneId ny = ZoneId.of("America/New_York");
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART;TZID=America/New_York:20260115T090000",
                "DTEND;TZID=America/New_York:20260115T100000", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(ms(ny, 2026, 1, 15, 9, 0));
        assertThat(e.endsAt()).isEqualTo(ms(ny, 2026, 1, 15, 10, 0));
    }

    @Test
    void AC2_floatingTimesUseLocalZoneParis() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART:20260718T140000", "DTEND:20260718T150000",
                "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 7, 18, 14, 0));
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 7, 18, 15, 0));
    }

    @Test
    void AC2_floatingTimesUseLocalZoneUtc() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260718T140000", "DTEND:20260718T150000",
                "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-07-18T14:00:00Z").toEpochMilli());
    }

    @Test
    void AC2_allDayIsLocalMidnightToNextMidnightParis() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART;VALUE=DATE:20260610",
                "DTEND;VALUE=DATE:20260611", "SUMMARY:Holiday"));
        assertThat(e.startsAt()).isEqualTo(LocalDate.of(2026, 6, 10).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(LocalDate.of(2026, 6, 11).atStartOfDay(PARIS).toInstant().toEpochMilli());
    }

    @Test
    void AC2_allDayUtc() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;VALUE=DATE:20260610",
                "DTEND;VALUE=DATE:20260611", "SUMMARY:Holiday"));
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z").toEpochMilli());
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofDays(1).toMillis());
    }

    @Test
    void AC2_allDayMultiDayEndIsExclusiveDate() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART;VALUE=DATE:20260701",
                "DTEND;VALUE=DATE:20260704", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(LocalDate.of(2026, 7, 1).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(LocalDate.of(2026, 7, 4).atStartOfDay(PARIS).toInstant().toEpochMilli());
    }

    @Test
    void AC2_allDayOnDstChangeDayIsTwentyThreeHoursInParis() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART;VALUE=DATE:20260329",
                "DTEND;VALUE=DATE:20260330", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(LocalDate.of(2026, 3, 29).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(LocalDate.of(2026, 3, 30).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(23).toMillis());
    }

    @Test
    void AC2_allDayMissingEndIsOneDay() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART;VALUE=DATE:20260612", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(LocalDate.of(2026, 6, 12).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(LocalDate.of(2026, 6, 13).atStartOfDay(PARIS).toInstant().toEpochMilli());
    }

    @Test
    void AC2_dateTimeMissingEndEqualsStart() {
        CalendarEvent e = single(parseEvent(PARIS, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-06-12T10:00:00Z").toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(e.startsAt());
    }

    @Test
    void AC2_zeroLengthEventStillWithinWindowByWindowRule() {
        long start = Instant.parse("2026-06-12T10:00:00Z").toEpochMilli();
        CalendarSnapshot s = IcsParser.parse(bytes(cal(event("UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x"))),
                UTC, start - 1000, start + 1000);
        assertThat(s.events()).hasSize(1);
    }

    @ParameterizedTest(name = "AC2_duration_{0}")
    @CsvSource({"PT1H30M,5400", "PT45M,2700", "PT2H,7200", "P1D,86400", "P1W,604800", "P1DT2H,93600", "PT30S,30"})
    void AC2_durationUsedWhenDtendAbsent(String duration, long seconds) {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "DURATION:" + duration,
                "SUMMARY:x"));
        long start = Instant.parse("2026-06-12T10:00:00Z").toEpochMilli();
        assertThat(e.startsAt()).isEqualTo(start);
        assertThat(e.endsAt()).isEqualTo(start + Duration.ofSeconds(seconds).toMillis());
    }

    @Test
    void AC2_durationWorksWithTzidStart() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20260612T100000",
                "DURATION:PT1H30M", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 6, 12, 10, 0));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofMinutes(90).toMillis());
    }

    @Test
    void AC2_dtendWinsOverNothingWhenBothAbsentOrPresent() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "DTEND:20260612T110000Z",
                "SUMMARY:x"));
        assertThat(e.endsAt() - e.startsAt()).isEqualTo(Duration.ofHours(1).toMillis());
    }

    // ---------------------------------------------------------------- AC2: DST transitions

    @Test
    void AC2_dstSpringForwardWallClockOffsetsDiffer() {
        CalendarSnapshot s = parse(cal(
                event("UID:before@example.com", "DTSTART;TZID=Europe/Paris:20260328T090000", "DTEND;TZID=Europe/Paris:20260328T100000", "SUMMARY:b"),
                event("UID:after@example.com", "DTSTART;TZID=Europe/Paris:20260329T090000", "DTEND;TZID=Europe/Paris:20260329T100000", "SUMMARY:a")), UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("before@example.com", "after@example.com");
        assertThat(s.events().get(0).startsAt()).isEqualTo(Instant.parse("2026-03-28T08:00:00Z").toEpochMilli());
        assertThat(s.events().get(1).startsAt()).isEqualTo(Instant.parse("2026-03-29T07:00:00Z").toEpochMilli());
        assertThat(s.events().get(1).startsAt()).isEqualTo(ms(PARIS, 2026, 3, 29, 9, 0));
    }

    @Test
    void AC2_dstSpringForwardNonexistentLocalTimeResolvedLikeJavaTime() {
        // 02:30 does not exist in Paris on 2026-03-29; java.time moves it forward by the gap.
        long expected = ZonedDateTime.of(LocalDateTime.of(2026, 3, 29, 2, 30), PARIS).toInstant().toEpochMilli();
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20260329T023000",
                "DTEND;TZID=Europe/Paris:20260329T033000", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(expected);
    }

    @Test
    void AC2_dstFallBackAmbiguousLocalTimeResolvedLikeJavaTime() {
        // 02:30 occurs twice in Paris on 2026-10-25; java.time picks the earlier offset.
        long expected = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 2, 30), PARIS).toInstant().toEpochMilli();
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20261025T023000",
                "DTEND;TZID=Europe/Paris:20261025T033000", "SUMMARY:x"));
        assertThat(e.startsAt()).isEqualTo(expected);
    }

    @Test
    void AC2_dstFallBackDayOffsets() {
        CalendarSnapshot s = parse(cal(
                event("UID:before@example.com", "DTSTART;TZID=Europe/Paris:20261024T090000", "DTEND;TZID=Europe/Paris:20261024T100000", "SUMMARY:b"),
                event("UID:after@example.com", "DTSTART;TZID=Europe/Paris:20261026T090000", "DTEND;TZID=Europe/Paris:20261026T100000", "SUMMARY:a")), UTC);
        assertThat(s.events().get(0).startsAt()).isEqualTo(Instant.parse("2026-10-24T07:00:00Z").toEpochMilli());
        assertThat(s.events().get(1).startsAt()).isEqualTo(Instant.parse("2026-10-26T08:00:00Z").toEpochMilli());
    }

    @Test
    void AC2_floatingTimeAcrossDstUsesLocalZoneRules() {
        CalendarSnapshot s = parse(cal(
                event("UID:a@example.com", "DTSTART:20260328T090000", "SUMMARY:a"),
                event("UID:b@example.com", "DTSTART:20260330T090000", "SUMMARY:b")), PARIS);
        assertThat(s.events().get(0).startsAt()).isEqualTo(ms(PARIS, 2026, 3, 28, 9, 0));
        assertThat(s.events().get(1).startsAt()).isEqualTo(ms(PARIS, 2026, 3, 30, 9, 0));
        assertThat(s.events().get(1).startsAt() - s.events().get(0).startsAt())
                .isEqualTo(Duration.ofHours(47).toMillis());
    }

    // ---------------------------------------------------------------- AC2: Windows and unknown TZID

    @ParameterizedTest(name = "AC2_windowsTzid_{0}")
    @CsvSource({
            "W. Europe Standard Time,Europe/Berlin",
            "Romance Standard Time,Europe/Paris",
            "Eastern Standard Time,America/New_York",
            "Pacific Standard Time,America/Los_Angeles",
            "GMT Standard Time,Europe/London",
            "UTC,UTC"})
    void AC2_windowsTzidMappedToIana(String windowsName, String iana) {
        CalendarSnapshot s = parseEvent(ZoneId.of("Asia/Tokyo"), "UID:u@example.com",
                "DTSTART;TZID=" + windowsName + ":20260715T100000", "DTEND;TZID=" + windowsName + ":20260715T110000", "SUMMARY:x");
        CalendarEvent e = single(s);
        assertThat(e.startsAt()).isEqualTo(ms(ZoneId.of(iana), 2026, 7, 15, 10, 0));
        assertThat(e.endsAt()).isEqualTo(ms(ZoneId.of(iana), 2026, 7, 15, 11, 0));
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC2_unknownTzidFallsBackToLocalZoneWithWarning() {
        CalendarSnapshot s = parseEvent(PARIS, "UID:u@example.com", "DTSTART;TZID=Mars/Olympus:20260715T100000",
                "DTEND;TZID=Mars/Olympus:20260715T110000", "SUMMARY:x");
        CalendarEvent e = single(s);
        assertThat(e.startsAt()).isEqualTo(ms(PARIS, 2026, 7, 15, 10, 0));
        assertThat(e.endsAt()).isEqualTo(ms(PARIS, 2026, 7, 15, 11, 0));
        assertThat(s.warnings()).anyMatch(w -> w.contains("Mars/Olympus"));
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC2_unknownTzidFallbackFollowsConfiguredZone() {
        CalendarSnapshot s = parseEvent(UTC, "UID:u@example.com", "DTSTART;TZID=Custom Zone 9:20260715T100000",
                "DTEND;TZID=Custom Zone 9:20260715T110000", "SUMMARY:x");
        assertThat(single(s).startsAt()).isEqualTo(Instant.parse("2026-07-15T10:00:00Z").toEpochMilli());
        assertThat(s.warnings()).anyMatch(w -> w.contains("Custom Zone 9"));
    }

    @Test
    void AC2_knownTzidProducesNoWarning() {
        CalendarSnapshot s = parseEvent(PARIS, "UID:u@example.com", "DTSTART;TZID=Europe/Paris:20260715T100000",
                "DTEND;TZID=Europe/Paris:20260715T110000", "SUMMARY:x");
        assertThat(s.warnings()).isEmpty();
        assertThat(s.skipped()).isZero();
    }

    // ---------------------------------------------------------------- AC2: text handling

    @Test
    void AC2_unfoldingCrlfSpace() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T100000Z\r\nSUMMARY:Hel\r\n lo wor\r\n ld\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        assertThat(single(parse(body, UTC)).title()).isEqualTo("Hello world");
    }

    @Test
    void AC2_unfoldingCrlfTab() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T100000Z\r\nSUMMARY:Hel\r\n\tlo\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        assertThat(single(parse(body, UTC)).title()).isEqualTo("Hello");
    }

    @Test
    void AC2_unfoldingBareLf() {
        String body = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:u@example.com\nDTSTART:20260612T100000Z\nSUMMARY:Hel\n lo wor\n\tld\nEND:VEVENT\nEND:VCALENDAR\n";
        assertThat(single(parse(body, UTC)).title()).isEqualTo("Hello world");
    }

    @Test
    void AC2_unfoldingKeepsOnlyOneLeadingWhitespace() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T100000Z\r\nSUMMARY:a\r\n  b\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        assertThat(single(parse(body, UTC)).title()).isEqualTo("a b");
    }

    @Test
    void AC2_unfoldingInsideDateValue() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T10\r\n 0000Z\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        assertThat(single(parse(body, UTC)).startsAt()).isEqualTo(Instant.parse("2026-06-12T10:00:00Z").toEpochMilli());
    }

    @Test
    void AC2_textUnescaping() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z",
                "SUMMARY:a\\nb\\Nc\\,d\\;e\\\\f"));
        assertThat(e.title()).isEqualTo("a\nb\nc,d;e\\f");
    }

    @Test
    void AC2_utf8MultibyteSurvives() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z",
                "SUMMARY:Réunion café ☕ 日本"));
        assertThat(e.title()).isEqualTo("Réunion café ☕ 日本");
    }

    @Test
    void AC2_invalidUtf8BecomesReplacementCharacter() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytes("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T100000Z\r\nSUMMARY:Bad "));
        out.write(0xFF);
        out.write(0xC3);
        out.write(bytes(" byte\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"));
        CalendarSnapshot s = IcsParser.parse(out.toByteArray(), UTC, WIDE_START, WIDE_END);
        assertThat(single(s).title()).startsWith("Bad ").contains("�").endsWith(" byte");
    }

    @Test
    void AC2_blankSummaryBecomesNoTitle() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:")).title())
                .isEqualTo("(No title)");
    }

    @Test
    void AC2_missingSummaryBecomesNoTitle() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z")).title())
                .isEqualTo("(No title)");
    }

    @Test
    void AC2_whitespaceOnlySummaryBecomesNoTitle() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:    ")).title())
                .isEqualTo("(No title)");
    }

    // ---------------------------------------------------------------- AC2: identity, conference, status, windows

    @Test
    void AC2_nonRecurringExternalIdIsUidAlone() {
        CalendarEvent e = single(parseEvent(UTC, "UID:abc-123@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x"));
        assertThat(e.externalId()).isEqualTo("abc-123@example.com").doesNotContain("#");
    }

    @Test
    void AC2_conferenceFromUrlProperty() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "URL:https://zoom.example.com/j/123"));
        assertThat(e.conferenceUrl()).isEqualTo("https://zoom.example.com/j/123");
    }

    @Test
    void AC2_conferenceFromGoogleConference() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "X-GOOGLE-CONFERENCE:https://meet.example.com/abc-defg-hij"));
        assertThat(e.conferenceUrl()).isEqualTo("https://meet.example.com/abc-defg-hij");
    }

    @Test
    void AC2_conferenceFromHttpsLocation() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "LOCATION:https://teams.example.com/l/meetup-join/abc"));
        assertThat(e.conferenceUrl()).isEqualTo("https://teams.example.com/l/meetup-join/abc");
    }

    @Test
    void AC2_conferenceNotTakenFromPlainLocation() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "LOCATION:Room 4, Building B")).conferenceUrl()).isNull();
    }

    @Test
    void AC2_conferenceNotTakenFromHttpLocation() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "LOCATION:http://insecure.example.com/room")).conferenceUrl()).isNull();
    }

    @Test
    void AC2_conferenceNullWhenNothingMatches() {
        assertThat(single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x")).conferenceUrl()).isNull();
    }

    @Test
    void AC2_conferencePriorityUrlThenGoogleThenLocation() {
        CalendarEvent all = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "LOCATION:https://loc.example.com/room", "X-GOOGLE-CONFERENCE:https://google.example.com/g",
                "URL:https://url.example.com/u"));
        assertThat(all.conferenceUrl()).isEqualTo("https://url.example.com/u");
        CalendarEvent noUrl = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "LOCATION:https://loc.example.com/room", "X-GOOGLE-CONFERENCE:https://google.example.com/g"));
        assertThat(noUrl.conferenceUrl()).isEqualTo("https://google.example.com/g");
    }

    @Test
    void AC2_cancelledEventIsDroppedWithoutBeingMalformed() {
        CalendarSnapshot s = parse(cal(
                event("UID:gone@example.com", "DTSTART:20260612T100000Z", "SUMMARY:gone", "STATUS:CANCELLED"),
                event("UID:kept@example.com", "DTSTART:20260612T110000Z", "SUMMARY:kept", "STATUS:CONFIRMED")), UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("kept@example.com");
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC2_nonVeventComponentsAreIgnored() {
        String body = cal(
                "BEGIN:VTIMEZONE", "TZID:Europe/Paris", "BEGIN:STANDARD", "DTSTART:19701025T030000", "TZOFFSETFROM:+0200",
                "TZOFFSETTO:+0100", "END:STANDARD", "END:VTIMEZONE",
                "BEGIN:VTODO", "UID:todo@example.com", "DTSTART:20260612T100000Z", "SUMMARY:todo", "END:VTODO",
                "BEGIN:VJOURNAL", "UID:j@example.com", "DTSTART:20260612T100000Z", "SUMMARY:journal", "END:VJOURNAL",
                event("UID:real@example.com", "DTSTART:20260612T100000Z", "SUMMARY:real"));
        CalendarSnapshot s = parse(body, UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("real@example.com");
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC2_windowRuleKeepsOverlapAndDropsEdgesSilently() {
        long ws = Instant.parse("2026-06-12T00:00:00Z").toEpochMilli();
        long we = Instant.parse("2026-06-13T00:00:00Z").toEpochMilli();
        String body = cal(
                event("UID:endsAtStart@example.com", "DTSTART:20260611T230000Z", "DTEND:20260612T000000Z", "SUMMARY:a"),
                event("UID:overlapsStart@example.com", "DTSTART:20260611T230000Z", "DTEND:20260612T000001Z", "SUMMARY:b"),
                event("UID:startsAtEnd@example.com", "DTSTART:20260613T000000Z", "DTEND:20260613T010000Z", "SUMMARY:c"),
                event("UID:startsBeforeEnd@example.com", "DTSTART:20260612T235959Z", "DTEND:20260613T010000Z", "SUMMARY:d"),
                event("UID:zeroAtStart@example.com", "DTSTART:20260612T000000Z", "SUMMARY:e"),
                event("UID:zeroAtEnd@example.com", "DTSTART:20260613T000000Z", "SUMMARY:f"),
                event("UID:farBefore@example.com", "DTSTART:20250101T000000Z", "DTEND:20250101T010000Z", "SUMMARY:g"),
                event("UID:farAfter@example.com", "DTSTART:20290101T000000Z", "DTEND:20290101T010000Z", "SUMMARY:h"));
        CalendarSnapshot s = IcsParser.parse(bytes(body), UTC, ws, we);
        assertThat(s.events()).extracting(CalendarEvent::externalId)
                .containsExactlyInAnyOrder("overlapsStart@example.com", "startsBeforeEnd@example.com", "zeroAtStart@example.com");
        assertThat(s.warnings()).isEmpty();
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC2_eventsOrderedByStartThenExternalId() {
        CalendarSnapshot s = parse(cal(
                event("UID:c@example.com", "DTSTART:20260612T120000Z", "SUMMARY:c"),
                event("UID:b@example.com", "DTSTART:20260612T100000Z", "SUMMARY:b"),
                event("UID:a@example.com", "DTSTART:20260612T100000Z", "SUMMARY:a"),
                event("UID:d@example.com", "DTSTART:20260611T090000Z", "SUMMARY:d")), UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId)
                .containsExactly("d@example.com", "a@example.com", "b@example.com", "c@example.com");
    }

    @Test
    void AC2_emptyCalendarGivesEmptySnapshot() {
        CalendarSnapshot s = parse(cal(), UTC);
        assertThat(s.events()).isEmpty();
        assertThat(s.skipped()).isZero();
    }

    // ---------------------------------------------------------------- AC2: fixtures

    @Test
    void AC2_fixtureGoogleLike() throws IOException {
        CalendarSnapshot s = fixture("google-like.ics", PARIS, ms(UTC, 2026, 11, 17, 0, 0), ms(UTC, 2026, 11, 19, 0, 0));
        CalendarEvent e = single(s);
        assertThat(e.externalId()).isEqualTo("google-like-1@example.com");
        assertThat(e.title()).isEqualTo("Quarterly planning, roadmap; part one of two");
        assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-11-18T14:00:00Z").toEpochMilli());
        assertThat(e.endsAt()).isEqualTo(Instant.parse("2026-11-18T15:00:00Z").toEpochMilli());
        assertThat(e.conferenceUrl()).isEqualTo("https://meet.example.com/abc-defg-hij");
        assertThat(e.attendees()).containsExactly(
                new Attendee("Alice Organizer", "alice@example.com"),
                new Attendee("Builder, Bob", "bob@example.com"),
                new Attendee("Carol Guest", "carol.guest@example.com"));
        assertThat(s.skipped()).isZero();
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC2_fixtureWindowsTzid() throws IOException {
        CalendarSnapshot s = fixture("windows-tzid.ics", UTC, ms(UTC, 2026, 7, 1, 0, 0), ms(UTC, 2026, 8, 1, 0, 0));
        assertThat(s.events()).hasSize(6);
        assertThat(s.warnings()).isEmpty();
        java.util.Map<String, String> zones = java.util.Map.of(
                "win-berlin@example.com", "Europe/Berlin", "win-paris@example.com", "Europe/Paris",
                "win-ny@example.com", "America/New_York", "win-la@example.com", "America/Los_Angeles",
                "win-london@example.com", "Europe/London", "win-utc@example.com", "UTC");
        for (CalendarEvent e : s.events()) {
            ZoneId z = ZoneId.of(zones.get(e.externalId()));
            assertThat(e.startsAt()).as(e.externalId()).isEqualTo(ms(z, 2026, 7, 15, 10, 0));
            assertThat(e.endsAt()).as(e.externalId()).isEqualTo(ms(z, 2026, 7, 15, 11, 0));
        }
    }

    @Test
    void AC2_fixtureAllDay() throws IOException {
        CalendarSnapshot s = fixture("all-day.ics", PARIS, ms(UTC, 2026, 3, 1, 0, 0), ms(UTC, 2026, 7, 31, 0, 0));
        java.util.Map<String, CalendarEvent> byId = new java.util.HashMap<>();
        s.events().forEach(e -> byId.put(e.externalId(), e));
        assertThat(byId).containsOnlyKeys("allday-single@example.com", "allday-noend@example.com",
                "allday-multi@example.com", "allday-dst@example.com");
        assertThat(byId.get("allday-single@example.com").startsAt())
                .isEqualTo(LocalDate.of(2026, 6, 10).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(byId.get("allday-single@example.com").endsAt())
                .isEqualTo(LocalDate.of(2026, 6, 11).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(byId.get("allday-noend@example.com").endsAt())
                .isEqualTo(LocalDate.of(2026, 6, 13).atStartOfDay(PARIS).toInstant().toEpochMilli());
        assertThat(byId.get("allday-multi@example.com").endsAt())
                .isEqualTo(LocalDate.of(2026, 7, 4).atStartOfDay(PARIS).toInstant().toEpochMilli());
        CalendarEvent dst = byId.get("allday-dst@example.com");
        assertThat(dst.endsAt() - dst.startsAt()).isEqualTo(Duration.ofHours(23).toMillis());
    }

    @Test
    void AC2_fixtureMalformedMixedGoodAndBad() throws IOException {
        CalendarSnapshot s = fixture("malformed.ics", UTC, ms(UTC, 2026, 6, 1, 0, 0), ms(UTC, 2026, 7, 1, 0, 0));
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("good-1@example.com", "good-2@example.com");
        assertThat(s.skipped()).isEqualTo(3);
        assertThat(s.events().get(0).startsAt()).isEqualTo(Instant.parse("2026-06-10T09:00:00Z").toEpochMilli());
        assertThat(s.events().get(1).startsAt()).isEqualTo(Instant.parse("2026-06-14T09:00:00Z").toEpochMilli());
    }

    // ---------------------------------------------------------------- AC4: attendees (unit part)

    @Test
    void AC4_attendeeWithCnAndMailto() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Bob Builder:mailto:bob@example.com"));
        assertThat(e.attendees()).containsExactly(new Attendee("Bob Builder", "bob@example.com"));
    }

    @Test
    void AC4_quotedCnKeepsCommaAndColon() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=\"Doe, John: Jr\";ROLE=REQ-PARTICIPANT:mailto:john@example.com"));
        assertThat(e.attendees()).containsExactly(new Attendee("Doe, John: Jr", "john@example.com"));
    }

    @Test
    void AC4_mailtoStrippedInAnyCase() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=A:MAILTO:a@example.com", "ATTENDEE;CN=B:Mailto:b@example.com", "ATTENDEE;CN=C:mailto:c@example.com"));
        assertThat(e.attendees()).extracting(Attendee::email).containsExactly("a@example.com", "b@example.com", "c@example.com");
    }

    @Test
    void AC4_emailTrimmedAndLowerCased() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Dan:mailto: Dan.Smith@EXAMPLE.com  "));
        assertThat(e.attendees()).containsExactly(new Attendee("Dan", "dan.smith@example.com"));
    }

    @Test
    void AC4_attendeeWithoutCnHasNullOrBlankName() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE:mailto:nameless@example.com"));
        assertThat(e.attendees()).hasSize(1);
        assertThat(e.attendees().get(0).email()).isEqualTo("nameless@example.com");
        assertThat(e.attendees().get(0).name()).satisfiesAnyOf(n -> assertThat(n).isNull(), n -> assertThat(n).isBlank());
    }

    @Test
    void AC4_attendeeWithoutEmailKeptWithNameOnly() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Meeting Room:urn:x-room:nomail"));
        assertThat(e.attendees()).containsExactly(new Attendee("Meeting Room", null));
    }

    @Test
    void AC4_attendeesWithoutEmailAreNeverDeduplicated() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Ghost:urn:x-room:one", "ATTENDEE;CN=Ghost:urn:x-room:two"));
        assertThat(e.attendees()).hasSize(2);
        assertThat(e.attendees()).allMatch(a -> a.email() == null && "Ghost".equals(a.name()));
    }

    @Test
    void AC4_duplicateEmailsDifferingInCaseAreMerged() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Bob:mailto:Bob@Example.COM", "ATTENDEE;CN=Bobby:mailto:bob@example.com",
                "ATTENDEE;CN=Eve:mailto:eve@example.com"));
        assertThat(e.attendees()).extracting(Attendee::email).containsExactly("bob@example.com", "eve@example.com");
    }

    @Test
    void AC4_organizerIsIncludedFirst() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ATTENDEE;CN=Bob:mailto:bob@example.com", "ORGANIZER;CN=Olga Organizer:mailto:Olga@Example.com",
                "ATTENDEE;CN=Eve:mailto:eve@example.com"));
        assertThat(e.attendees()).containsExactly(
                new Attendee("Olga Organizer", "olga@example.com"),
                new Attendee("Bob", "bob@example.com"),
                new Attendee("Eve", "eve@example.com"));
    }

    @Test
    void AC4_organizerAlsoListedAsAttendeeAppearsOnce() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x",
                "ORGANIZER;CN=Olga Organizer:mailto:olga@example.com", "ATTENDEE;CN=Olga Organizer:MAILTO:OLGA@example.com",
                "ATTENDEE;CN=Bob:mailto:bob@example.com"));
        assertThat(e.attendees()).extracting(Attendee::email).containsExactly("olga@example.com", "bob@example.com");
        assertThat(e.attendees().get(0).name()).isEqualTo("Olga Organizer");
    }

    @Test
    void AC4_noAttendeesGivesEmptyListNotNull() {
        CalendarEvent e = single(parseEvent(UTC, "UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:x"));
        assertThat(e.attendees()).isNotNull().isEmpty();
    }

    @Test
    void AC4_foldedAttendeeLineIsReassembled() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u@example.com\r\nDTSTART:20260612T100000Z\r\nATTENDEE;CN=Long Name;PARTSTAT=ACCEPTED:mailto:very.long.add\r\n ress@example.com\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        assertThat(single(parse(body, UTC)).attendees())
                .containsExactly(new Attendee("Long Name", "very.long.address@example.com"));
    }

    // ---------------------------------------------------------------- AC6: malformed VEVENTs

    @ParameterizedTest(name = "AC6_malformedVeventSkipped_{0}")
    @CsvSource(delimiter = '|', value = {
            "noDtstart|UID:bad@example.com|SUMMARY:x",
            "unparseableDate|UID:bad@example.com|DTSTART:not-a-date",
            "impossibleDate|UID:bad@example.com|DTSTART:20261345T250000Z",
            "impossibleAllDay|UID:bad@example.com|DTSTART;VALUE=DATE:20260231",
            "noUid|DTSTART:20260612T100000Z|SUMMARY:x"})
    void AC6_malformedVeventSkippedAndCounted(String name, String line1, String line2) {
        CalendarSnapshot s = parse(cal(
                event(line1, line2),
                event("UID:good@example.com", "DTSTART:20260612T100000Z", "SUMMARY:good")), UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("good@example.com");
        assertThat(s.skipped()).isEqualTo(1);
    }

    @Test
    void AC6_multipleMalformedAreAllCounted() {
        CalendarSnapshot s = parse(cal(
                event("UID:a@example.com"), event("UID:b@example.com", "DTSTART:zzz"),
                event("DTSTART:20260612T100000Z"), event("UID:ok@example.com", "DTSTART:20260612T100000Z")), UTC);
        assertThat(s.skipped()).isEqualTo(3);
        assertThat(s.events()).hasSize(1);
    }

    @Test
    void AC6_unterminatedVeventAtEndOfFileDoesNotThrow() {
        String body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a@example.com\r\nDTSTART:20260612T100000Z\r\nSUMMARY:cut off";
        assertThatCode(() -> assertSane(parse(body, UTC))).doesNotThrowAnyException();
    }

    @Test
    void AC6_nestedBeginVeventDoesNotThrow() {
        String body = cal("BEGIN:VEVENT", "UID:a@example.com", "BEGIN:VEVENT", "UID:b@example.com", "DTSTART:20260612T100000Z",
                "END:VEVENT", "END:VEVENT");
        assertThatCode(() -> assertSane(parse(body, UTC))).doesNotThrowAnyException();
    }

    @Test
    void AC6_linesWithoutColonAreTolerated() {
        CalendarSnapshot s = parse(cal("garbage without colon", event("UID:a@example.com", "NOCOLON", "DTSTART:20260612T100000Z")), UTC);
        assertThat(s.events()).extracting(CalendarEvent::externalId).containsExactly("a@example.com");
    }

    @Test
    void AC6_htmlBodyGivesEmptySnapshot() {
        CalendarSnapshot s = parse("<!DOCTYPE html><html><body>Sign in to continue</body></html>", UTC);
        assertThat(s.events()).isEmpty();
    }

    @Test
    void AC6_emptyBody() {
        CalendarSnapshot s = IcsParser.parse(new byte[0], UTC, WIDE_START, WIDE_END);
        assertThat(s.events()).isEmpty();
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC6_invertedWindowDoesNotThrow() {
        assertThatCode(() -> assertSane(IcsParser.parse(bytes(cal(event("UID:a@example.com", "DTSTART:20260612T100000Z"))),
                UTC, WIDE_END, WIDE_START))).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- AC6: limits

    @Test
    void AC6_limitConstantsMatchContract() {
        assertThat(IcsParser.MAX_BODY_BYTES).isEqualTo(10 * 1024 * 1024);
        assertThat(IcsParser.MAX_LINE_CHARS).isEqualTo(64 * 1024);
        assertThat(IcsParser.MAX_VEVENTS).isEqualTo(20_000);
        assertThat(IcsParser.MAX_OCCURRENCES_PER_SERIES).isEqualTo(500);
        assertThat(IcsParser.MAX_EVENTS_PER_SYNC).isEqualTo(5_000);
    }

    @Test
    void AC6_bodyOverTenMegabytesGivesEmptySnapshotWithWarning() {
        byte[] body = new byte[IcsParser.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(body, (byte) 'a');
        CalendarSnapshot s = IcsParser.parse(body, UTC, WIDE_START, WIDE_END);
        assertThat(s.events()).isEmpty();
        assertThat(s.warnings()).isNotEmpty();
        assertThat(s.skipped()).isZero();
    }

    @Test
    void AC6_oversizedBodyOfValidEventsIsNotPartiallyParsed() {
        String one = event("UID:x@example.com", "DTSTART:20260612T100000Z", "DTEND:20260612T110000Z", "SUMMARY:padding padding padding") + "\r\n";
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        while (sb.length() <= IcsParser.MAX_BODY_BYTES) {
            sb.append(one);
        }
        sb.append("END:VCALENDAR\r\n");
        CalendarSnapshot s = IcsParser.parse(bytes(sb.toString()), UTC, WIDE_START, WIDE_END);
        assertThat(s.events()).isEmpty();
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC6_logicalLineOver64KbIsSkippedWithWarningOtherLinesKept() {
        String huge = "x".repeat(IcsParser.MAX_LINE_CHARS + 1);
        CalendarSnapshot s = parse(cal(event("UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:" + huge)), UTC);
        assertThat(s.warnings()).isNotEmpty();
        CalendarEvent e = single(s);
        assertThat(e.externalId()).isEqualTo("u@example.com");
        assertThat(e.title()).isEqualTo("(No title)");
    }

    @Test
    void AC6_hugeIrrelevantPropertyKeepsEventAndWarns() {
        String huge = "x".repeat(IcsParser.MAX_LINE_CHARS * 2);
        CalendarSnapshot s = parse(cal(event("UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:kept",
                "DESCRIPTION:" + huge, "LOCATION:Room")), UTC);
        assertThat(single(s).title()).isEqualTo("kept");
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC6_foldedContinuationsExceedingLineLimitAreSkippedWithWarning() {
        StringBuilder sb = new StringBuilder("SUMMARY:");
        for (int i = 0; i < 70; i++) {
            sb.append("\r\n ").append("y".repeat(1000));
        }
        CalendarSnapshot s = parse(cal(event("UID:u@example.com", "DTSTART:20260612T100000Z", sb.toString())), UTC);
        assertThat(s.warnings()).isNotEmpty();
        assertThat(single(s).title()).isEqualTo("(No title)");
    }

    @Test
    void AC6_lineExactlyAtLimitIsAccepted() {
        String value = "z".repeat(IcsParser.MAX_LINE_CHARS - "SUMMARY:".length());
        CalendarSnapshot s = parse(cal(event("UID:u@example.com", "DTSTART:20260612T100000Z", "SUMMARY:" + value)), UTC);
        // The line is accepted (not skipped); the 500 character title cap of review round 1 then applies.
        assertThat(single(s).title()).isEqualTo(value.substring(0, IcsParser.MAX_TITLE_CHARS));
    }

    private static String manyEvents(int count) {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        long base = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < count; i++) {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(base + i * 60_000L), UTC);
            sb.append("BEGIN:VEVENT\r\nUID:e").append(i).append("@example.com\r\nDTSTART:").append(fmt(t)).append("Z\r\nSUMMARY:e")
                    .append(i).append("\r\nEND:VEVENT\r\n");
        }
        return sb.append("END:VCALENDAR\r\n").toString();
    }

    @Test
    void AC6_exactlyFiveThousandEventsAreAllReturnedWithoutWarning() {
        CalendarSnapshot s = parse(manyEvents(IcsParser.MAX_EVENTS_PER_SYNC), UTC);
        assertThat(s.events()).hasSize(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isEmpty();
    }

    @Test
    void AC6_moreThanFiveThousandEventsAreCutWithWarning() {
        CalendarSnapshot s = parse(manyEvents(IcsParser.MAX_EVENTS_PER_SYNC + 500), UTC);
        assertThat(s.events()).hasSize(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC6_moreThanTwentyThousandVeventsWarnsAndNeverExceedsStoredCap() {
        CalendarSnapshot s = parse(manyEvents(IcsParser.MAX_VEVENTS + 50), UTC);
        assertThat(s.events().size()).isLessThanOrEqualTo(IcsParser.MAX_EVENTS_PER_SYNC);
        assertThat(s.warnings()).isNotEmpty();
    }

    @Test
    void AC6_manyMalformedVeventsOverLimitDoNotThrow() {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\n");
        for (int i = 0; i < IcsParser.MAX_VEVENTS + 100; i++) {
            sb.append("BEGIN:VEVENT\r\nSUMMARY:no start\r\nEND:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        CalendarSnapshot s = parse(sb.toString(), UTC);
        assertSane(s);
        assertThat(s.events()).isEmpty();
        assertThat(s.warnings()).isNotEmpty();
    }

    // ---------------------------------------------------------------- AC6: fuzz (never throws)

    @Test
    void AC6_fuzzRandomBytesNeverThrow() {
        Random rnd = new Random(20261118L);
        for (int i = 0; i < 300; i++) {
            byte[] data = new byte[rnd.nextInt(4096)];
            rnd.nextBytes(data);
            CalendarSnapshot s = IcsParser.parse(data, i % 2 == 0 ? UTC : PARIS, WIDE_START, WIDE_END);
            assertSane(s);
        }
    }

    @Test
    void AC6_fuzzRandomIcsFragmentsNeverThrow() {
        String[] frags = {"BEGIN:VCALENDAR", "END:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "UID:", "DTSTART", "DTSTART;TZID=",
                "DTSTART;VALUE=DATE:", "DTEND:", "DURATION:", "P", "PT", "T", "Z", "RRULE:", "FREQ=", "DAILY", "WEEKLY", "MONTHLY",
                "YEARLY", "HOURLY", ";", ",", "=", ":", "COUNT=", "INTERVAL=", "UNTIL=", "BYDAY=", "BYMONTHDAY=", "BYMONTH=", "-1",
                "2TU", "MO", "EXDATE:", "RECURRENCE-ID:", "STATUS:CANCELLED", "ATTENDEE;CN=", "mailto:", "ORGANIZER:", "\"", "\\",
                "\r\n", "\n", "\r\n ", "\t", " ", "20260612", "T100000", "99999999T999999Z", "0", "-5", "2147483648", "999999999999",
                "é", "\u0000", "SUMMARY:", "X-GOOGLE-CONFERENCE:", "URL:", "LOCATION:https://"};
        Random rnd = new Random(7L);
        long ws = ms(UTC, 2026, 1, 1, 0, 0);
        long we = ms(UTC, 2027, 1, 1, 0, 0);
        for (int i = 0; i < 400; i++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(80);
            for (int j = 0; j < n; j++) {
                sb.append(frags[rnd.nextInt(frags.length)]);
            }
            CalendarSnapshot s = IcsParser.parse(bytes(sb.toString()), PARIS, ws, we);
            assertSane(s);
        }
    }

    @Test
    void AC6_fuzzTruncatedFixturesNeverThrow() throws IOException {
        long ws = ms(UTC, 2026, 1, 1, 0, 0);
        long we = ms(UTC, 2027, 1, 1, 0, 0);
        for (String name : List.of("google-like.ics", "recurring-weekly-dst.ics", "windows-tzid.ics", "all-day.ics", "malformed.ics")) {
            byte[] full = Files.readAllBytes(FIXTURES.resolve(name));
            for (int len = 0; len <= full.length; len += 7) {
                assertSane(IcsParser.parse(java.util.Arrays.copyOf(full, len), PARIS, ws, we));
            }
            assertSane(IcsParser.parse(full, PARIS, ws, we));
        }
    }

    @Test
    void AC6_fuzzMutatedFixturesNeverThrow() throws IOException {
        long ws = ms(UTC, 2026, 1, 1, 0, 0);
        long we = ms(UTC, 2027, 1, 1, 0, 0);
        Random rnd = new Random(99L);
        for (String name : List.of("google-like.ics", "recurring-weekly-dst.ics", "windows-tzid.ics", "all-day.ics", "malformed.ics")) {
            byte[] full = Files.readAllBytes(FIXTURES.resolve(name));
            for (int i = 0; i < 60; i++) {
                byte[] copy = full.clone();
                int flips = 1 + rnd.nextInt(8);
                for (int f = 0; f < flips; f++) {
                    copy[rnd.nextInt(copy.length)] = (byte) rnd.nextInt(256);
                }
                assertSane(IcsParser.parse(copy, PARIS, ws, we));
            }
        }
    }

    @Test
    void AC6_fuzzHostileRruleValuesNeverThrowOrHang() {
        String[] rules = {"FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;INTERVAL=-1", "FREQ=DAILY;INTERVAL=999999999999", "FREQ=DAILY;COUNT=-5",
                "FREQ=DAILY;COUNT=abc", "FREQ=WEEKLY;BYDAY=XX", "FREQ=WEEKLY;BYDAY=", "FREQ=MONTHLY;BYDAY=99MO", "FREQ=MONTHLY;BYMONTHDAY=0",
                "FREQ=MONTHLY;BYMONTHDAY=99", "FREQ=YEARLY;BYMONTH=13", "FREQ=DAILY;UNTIL=garbage", "FREQ=", "FREQ=BOGUS", ";;;;", "=",
                "FREQ=DAILY;FREQ=WEEKLY", "FREQ=YEARLY;INTERVAL=1000000", "FREQ=DAILY;COUNT=2147483647"};
        for (String rule : rules) {
            assertThatCode(() -> assertSane(parseEvent(PARIS, "UID:u@example.com", "DTSTART:20260612T100000Z",
                    "DTEND:20260612T110000Z", "RRULE:" + rule, "SUMMARY:x")))
                    .as(rule).doesNotThrowAnyException();
        }
    }
}
