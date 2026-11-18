package app.interline.domain;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed DTSTART-like value: a wall-clock time in a zone (all-day values sit at local midnight). */
record IcsTime(LocalDateTime local, ZoneId zone, boolean allDay) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DURATION = Pattern.compile(
            "([+-])?P(?:(\\d{1,6})W)?(?:(\\d{1,6})D)?(?:T(?:(\\d{1,6})H)?(?:(\\d{1,6})M)?(?:(\\d{1,6})S)?)?");
    private static final Map<String, String> WINDOWS_ZONES = Map.ofEntries(
            Map.entry("UTC", "UTC"),
            Map.entry("W. Europe Standard Time", "Europe/Berlin"),
            Map.entry("Romance Standard Time", "Europe/Paris"),
            Map.entry("Central Europe Standard Time", "Europe/Budapest"),
            Map.entry("Central European Standard Time", "Europe/Warsaw"),
            Map.entry("GMT Standard Time", "Europe/London"),
            Map.entry("E. Europe Standard Time", "Europe/Chisinau"),
            Map.entry("Eastern Standard Time", "America/New_York"),
            Map.entry("Central Standard Time", "America/Chicago"),
            Map.entry("Mountain Standard Time", "America/Denver"),
            Map.entry("Pacific Standard Time", "America/Los_Angeles"),
            Map.entry("Tokyo Standard Time", "Asia/Tokyo"),
            Map.entry("India Standard Time", "Asia/Kolkata"),
            Map.entry("China Standard Time", "Asia/Shanghai"),
            Map.entry("AUS Eastern Standard Time", "Australia/Sydney"));

    long epochMs() {
        return local.atZone(zone).toInstant().toEpochMilli();
    }

    /** Parses the value of a date or date-time property; null when it is not a valid RFC 5545 value. */
    static IcsTime parse(String value, Map<String, String> params, ZoneId localZone, Set<String> warnings) {
        String v = value.trim();
        boolean utc = v.endsWith("Z") || v.endsWith("z");
        if (utc) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            if (v.indexOf('T') < 0 && v.indexOf('t') < 0) {
                return new IcsTime(LocalDate.parse(v, DATE).atStartOfDay(), localZone, true);
            }
            ZoneId zone = utc ? ZoneOffset.UTC : zoneFor(params.get("TZID"), localZone, warnings);
            return new IcsTime(LocalDateTime.parse(v.toUpperCase(java.util.Locale.ROOT), DATE_TIME), zone, false);
        } catch (DateTimeException e) {
            return null;
        }
    }

    static IcsTime parse(IcsProp prop, ZoneId localZone, Set<String> warnings) {
        return parse(prop.value(), prop.params(), localZone, warnings);
    }

    /** Parses an RFC 5545 DURATION (P1W, P1DT2H, PT1H30M ...); null when invalid. */
    static Duration parseDuration(String value) {
        Matcher m = DURATION.matcher(value.trim().toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        long seconds = part(m, 2) * 604_800 + part(m, 3) * 86_400 + part(m, 4) * 3_600 + part(m, 5) * 60 + part(m, 6);
        return Duration.ofSeconds("-".equals(m.group(1)) ? -seconds : seconds);
    }

    private static long part(Matcher m, int group) {
        return m.group(group) == null ? 0 : Long.parseLong(m.group(group));
    }

    private static ZoneId zoneFor(String tzid, ZoneId localZone, Set<String> warnings) {
        if (tzid == null || tzid.isBlank()) {
            return localZone;
        }
        try {
            return ZoneId.of(WINDOWS_ZONES.getOrDefault(tzid.trim(), tzid.trim()));
        } catch (DateTimeException e) {
            warnings.add("Unknown time zone '" + tzid.substring(0, Math.min(tzid.length(), 80)) + "', using the local zone");
            return localZone;
        }
    }
}
