package app.interline.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The supported RRULE subset: FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH. */
final class IcsRecurrence {
    /** Result of parsing: a rule, or the name of the first unsupported or invalid part. */
    record Parsed(IcsRecurrence rule, String unsupported) { }

    private record Day(int ordinal, DayOfWeek dow) { }

    private enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    private static final int MAX_PERIODS = 200_000;
    private static final Pattern DAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
    private static final List<String> CODES = List.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");
    private static final List<String> SUPPORTED = List.of("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST");

    private final Freq freq;
    private final int interval;
    private final int count; // 0 = unbounded
    private final long untilMs;
    private final List<Day> byDay;
    private final List<Integer> byMonthDay;
    private final List<Integer> byMonth;

    private IcsRecurrence(Map<String, String> parts, IcsTime start) {
        this.freq = freq(parts.get("FREQ"));
        this.interval = parts.containsKey("INTERVAL") ? number(parts.get("INTERVAL"), "INTERVAL", 1, 1_000_000) : 1;
        this.count = parts.containsKey("COUNT") ? number(parts.get("COUNT"), "COUNT", 1, Integer.MAX_VALUE) : 0;
        this.untilMs = parts.containsKey("UNTIL") ? until(parts.get("UNTIL"), start) : Long.MAX_VALUE;
        this.byDay = parts.containsKey("BYDAY") ? days(parts.get("BYDAY")) : List.of();
        this.byMonthDay = numbers(parts.get("BYMONTHDAY"), "BYMONTHDAY", -31, 31);
        this.byMonth = numbers(parts.get("BYMONTH"), "BYMONTH", 1, 12);
        boolean ordinals = byDay.stream().anyMatch(d -> d.ordinal() != 0);
        if (ordinals && freq != Freq.MONTHLY || freq == Freq.YEARLY && !byDay.isEmpty()) {
            throw new IllegalArgumentException("BYDAY");
        }
        if (freq == Freq.WEEKLY && !byMonthDay.isEmpty()) {
            throw new IllegalArgumentException("BYMONTHDAY");
        }
    }

    /** Parses an RRULE value; never throws. */
    static Parsed parse(String text, IcsTime start) {
        Map<String, String> parts = new java.util.HashMap<>();
        for (String token : text.split(";")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                parts.put(token.substring(0, eq).trim().toUpperCase(Locale.ROOT), token.substring(eq + 1).trim().toUpperCase(Locale.ROOT));
            }
        }
        try {
            for (String key : parts.keySet()) {
                if (!SUPPORTED.contains(key)) {
                    throw new IllegalArgumentException(key);
                }
            }
            return new Parsed(new IcsRecurrence(parts, start), null);
        } catch (IllegalArgumentException | DateTimeException e) {
            return new Parsed(null, e instanceof IllegalArgumentException ? e.getMessage() : "UNTIL");
        }
    }

    /**
     * Calls the visitor with each occurrence start (wall clock, in the series zone) in order, honouring COUNT and UNTIL,
     * until the visitor returns false or the series ends. Occurrences before DTSTART are skipped.
     */
    void forEach(IcsTime start, Predicate<LocalDateTime> visitor) {
        LocalDate first = start.local().toLocalDate();
        int seen = 0;
        try {
            for (long k = 0; k < MAX_PERIODS; k++) {
                for (LocalDate date : period(k * interval, first)) {
                    LocalDateTime at = date.atTime(start.local().toLocalTime());
                    if (date.isBefore(first)) {
                        continue;
                    }
                    if (at.atZone(start.zone()).toInstant().toEpochMilli() > untilMs || !visitor.test(at) || ++seen == count) {
                        return;
                    }
                }
            }
        } catch (DateTimeException | ArithmeticException e) {
            // ran past the representable date range: the series is over
        }
    }

    private List<LocalDate> period(long step, LocalDate first) {
        return switch (freq) {
            case DAILY -> matches(first.plusDays(step)) ? List.of(first.plusDays(step)) : List.of();
            case WEEKLY -> week(first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(step), first);
            case MONTHLY -> byMonth.isEmpty() || byMonth.contains(YearMonth.from(first).plusMonths(step).getMonthValue())
                    ? monthDates(YearMonth.from(first).plusMonths(step), first.getDayOfMonth()) : List.of();
            case YEARLY -> yearDates(first.getYear() + step, first);
        };
    }

    private boolean matches(LocalDate d) {
        return (byMonth.isEmpty() || byMonth.contains(d.getMonthValue()))
                && (byMonthDay.isEmpty() || monthDayMatches(d))
                && (byDay.isEmpty() || byDay.stream().anyMatch(b -> b.dow() == d.getDayOfWeek()));
    }

    private List<LocalDate> week(LocalDate monday, LocalDate first) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            boolean day = byDay.isEmpty() ? d.getDayOfWeek() == first.getDayOfWeek() : byDay.stream().anyMatch(b -> b.dow() == d.getDayOfWeek());
            if (day && (byMonth.isEmpty() || byMonth.contains(d.getMonthValue()))) {
                dates.add(d);
            }
        }
        return dates;
    }

    private List<LocalDate> yearDates(long year, LocalDate first) {
        List<Integer> months = byMonth.isEmpty() ? List.of(first.getMonthValue()) : byMonth.stream().sorted().toList();
        List<LocalDate> dates = new ArrayList<>();
        for (int month : months) {
            dates.addAll(monthDates(YearMonth.of(Math.toIntExact(year), month), first.getDayOfMonth()));
        }
        return dates;
    }

    private List<LocalDate> monthDates(YearMonth month, int defaultDay) {
        List<LocalDate> dates = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate d = month.atDay(day);
            boolean dayMatch = byMonthDay.isEmpty() ? !byDay.isEmpty() || day == defaultDay : monthDayMatches(d);
            if (dayMatch && (byDay.isEmpty() || byDay.stream().anyMatch(b -> ordinalMatches(d, b)))) {
                dates.add(d);
            }
        }
        return dates;
    }

    private boolean monthDayMatches(LocalDate d) {
        return byMonthDay.contains(d.getDayOfMonth()) || byMonthDay.contains(d.getDayOfMonth() - d.lengthOfMonth() - 1);
    }

    private static boolean ordinalMatches(LocalDate d, Day b) {
        if (d.getDayOfWeek() != b.dow()) {
            return false;
        }
        int n = b.ordinal();
        return n == 0 || (n > 0 ? (d.getDayOfMonth() - 1) / 7 + 1 == n : (d.lengthOfMonth() - d.getDayOfMonth()) / 7 + 1 == -n);
    }

    private static Freq freq(String value) {
        try {
            return Freq.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("FREQ=" + value);
        }
    }

    private static int number(String value, String part, int min, int max) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n >= min && n <= max && !(min < 0 && n == 0)) {
                return n;
            }
        } catch (NumberFormatException e) {
            // falls through
        }
        throw new IllegalArgumentException(part);
    }

    private static List<Integer> numbers(String value, String part, int min, int max) {
        List<Integer> out = new ArrayList<>();
        if (value != null) {
            for (String s : value.split(",", -1)) {
                out.add(number(s, part, min, max));
            }
        }
        return out;
    }

    private static List<Day> days(String value) {
        List<Day> out = new ArrayList<>();
        for (String s : value.split(",", -1)) {
            Matcher m = DAY.matcher(s.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("BYDAY");
            }
            int ordinal = m.group(1) == null ? 0 : number(m.group(1).replace("+", ""), "BYDAY", -53, 53);
            out.add(new Day(ordinal, DayOfWeek.of(CODES.indexOf(m.group(2)) + 1)));
        }
        return out;
    }

    private static long until(String value, IcsTime start) {
        IcsTime t = IcsTime.parse(value, Map.of(), start.zone(), new HashSet<>());
        if (t == null) {
            throw new IllegalArgumentException("UNTIL");
        }
        return t.allDay() ? t.local().plusDays(1).atZone(t.zone()).toInstant().toEpochMilli() - 1 : t.epochMs();
    }
}
