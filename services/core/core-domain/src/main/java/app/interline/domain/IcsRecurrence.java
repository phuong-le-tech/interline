package app.interline.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The supported RRULE subset: FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH, WKST. */
final class IcsRecurrence {
    /** Result of parsing: a rule, or the name of the first unsupported or invalid part. */
    record Parsed(IcsRecurrence rule, String unsupported) { }

    private record Day(int ordinal, DayOfWeek dow) { }

    private enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** Parse-wide work limit: a step count and a wall-clock deadline shared by every series of one parse. */
    static final class Budget {
        private final long deadlineNanos = System.nanoTime() + IcsParser.MAX_PARSE_MILLIS * 1_000_000L;
        private long steps;
        private String exhausted;

        /** Accounts one period; false (and stays false) once the step limit or the deadline is hit. */
        boolean spend() {
            if (exhausted != null) {
                return false;
            }
            if (++steps > IcsParser.MAX_PERIOD_STEPS) {
                exhausted = "step";
            } else if ((steps & 255) == 0 && System.nanoTime() - deadlineNanos > 0) {
                exhausted = "time";
            }
            return exhausted == null;
        }

        /** "step" or "time" once exhausted, otherwise null. */
        String exhausted() {
            if (exhausted == null && System.nanoTime() - deadlineNanos > 0) {
                exhausted = "time";
            }
            return exhausted;
        }
    }

    private static final int MAX_PERIODS = 200_000;
    private static final int MAX_ORDINAL_DAYS = 64;
    private static final Pattern DAY = Pattern.compile("([+-]?\\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)");
    private static final List<String> CODES = List.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");
    private static final List<String> SUPPORTED = List.of("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST");

    private final Freq freq;
    private final int interval;
    private final int count; // 0 = unbounded
    private final long untilMs;
    private final DayOfWeek wkst;
    private final Set<DayOfWeek> weekdays; // BYDAY entries without an ordinal
    private final List<Day> ordinalDays;   // distinct BYDAY entries with an ordinal (MONTHLY only)
    private final int months;              // bit m set = month m
    private final long monthDays;          // bit d = day d from the start, bit 32 + d = d-th day from the end

    private IcsRecurrence(Map<String, String> parts, IcsTime start) {
        this.freq = freq(parts.get("FREQ"));
        this.interval = parts.containsKey("INTERVAL") ? number(parts.get("INTERVAL"), "INTERVAL", 1, 1_000_000) : 1;
        this.count = parts.containsKey("COUNT") ? number(parts.get("COUNT"), "COUNT", 1, Integer.MAX_VALUE) : 0;
        this.untilMs = parts.containsKey("UNTIL") ? until(parts.get("UNTIL"), start) : Long.MAX_VALUE;
        this.wkst = parts.containsKey("WKST") ? weekday(parts.get("WKST")) : DayOfWeek.MONDAY;
        Set<Day> days = parts.containsKey("BYDAY") ? days(parts.get("BYDAY")) : Set.of();
        this.weekdays = days.stream().filter(d -> d.ordinal() == 0).map(Day::dow).collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
        this.ordinalDays = days.stream().filter(d -> d.ordinal() != 0).toList();
        this.months = monthMask(parts.get("BYMONTH"));
        this.monthDays = monthDayMask(parts.get("BYMONTHDAY"));
        if (!ordinalDays.isEmpty() && freq != Freq.MONTHLY || ordinalDays.size() > MAX_ORDINAL_DAYS) {
            throw new IllegalArgumentException("BYDAY");
        }
        if (freq == Freq.WEEKLY && monthDays != 0) {
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
     * until the visitor returns false, the series ends, or the budget is spent. Walking starts near {@code from} and stops
     * after {@code to}; occurrences before DTSTART are skipped and a few occurrences before {@code from} may be visited.
     * Returns false only when the per-series period cap cut the walk short.
     */
    boolean forEach(IcsTime start, LocalDate from, LocalDate to, Budget budget, Predicate<LocalDateTime> visitor) {
        LocalDate first = start.local().toLocalDate();
        try {
            boolean extra = !period(0, first).contains(first); // DTSTART is always an instance, even when the rule skips it
            long k = firstPeriod(first, from);
            long seen = count > 0 && k > 0 ? seenBefore(k, first) : 0;
            if (seen < 0 || extra && count > 0) {
                k = 0;
                seen = 0;
            }
            if (k == 0 && extra) {
                if (!visitStart(start, visitor)) {
                    return true;
                }
                seen++;
            }
            return seen >= count && count > 0 || walk(start, first, to, budget, visitor, k, seen);
        } catch (DateTimeException | ArithmeticException e) {
            return true; // ran past the representable date range: the series is over
        }
    }

    /** Visits DTSTART itself; false when the walk must stop (past UNTIL, visitor done, or COUNT=1). */
    private boolean visitStart(IcsTime start, Predicate<LocalDateTime> visitor) {
        return start.epochMs() <= untilMs && visitor.test(start.local()) && count != 1;
    }

    private boolean walk(IcsTime start, LocalDate first, LocalDate to, Budget budget, Predicate<LocalDateTime> visitor, long k, long seen) {
        long seenCount = seen;
        for (int visited = 0; visited < MAX_PERIODS; visited++, k++) {
            long step = k * interval;
            if (!budget.spend() || periodStart(step, first).isAfter(to)) {
                return true;
            }
            for (LocalDate date : period(step, first)) {
                LocalDateTime at = date.atTime(start.local().toLocalTime());
                if (date.isBefore(first)) {
                    continue;
                }
                if (at.atZone(start.zone()).toInstant().toEpochMilli() > untilMs || !visitor.test(at) || ++seenCount == count) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the rule produces an occurrence at exactly this instant. */
    boolean occursAt(IcsTime start, long epochMs, Budget budget) {
        LocalDate day = Instant.ofEpochMilli(epochMs).atZone(start.zone()).toLocalDate();
        boolean[] hit = {false};
        forEach(start, day.minusDays(1), day.plusDays(1), budget, at -> {
            long ms = at.atZone(start.zone()).toInstant().toEpochMilli();
            hit[0] |= ms == epochMs;
            return ms < epochMs;
        });
        return hit[0];
    }

    /** The index of a period safely at or before the one holding {@code from}. */
    private long firstPeriod(LocalDate first, LocalDate from) {
        if (!from.isAfter(first)) {
            return 0;
        }
        long units = switch (freq) {
            case DAILY -> ChronoUnit.DAYS.between(first, from);
            case WEEKLY -> ChronoUnit.DAYS.between(weekStart(first), from) / 7;
            case MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(from));
            case YEARLY -> from.getYear() - first.getYear();
        };
        return Math.max(0, units / interval - 1);
    }

    /** Occurrences produced before period k, counted arithmetically; -1 when that needs a walk. */
    private long seenBefore(long k, LocalDate first) {
        if (freq == Freq.DAILY && months == 0 && monthDays == 0 && weekdays.isEmpty()) {
            return k;
        }
        if (freq == Freq.WEEKLY && months == 0) {
            long firstWeek = period(0, first).stream().filter(d -> !d.isBefore(first)).count();
            return firstWeek + (k - 1) * (weekdays.isEmpty() ? 1 : weekdays.size());
        }
        return -1;
    }

    private LocalDate weekStart(LocalDate first) {
        return first.with(TemporalAdjusters.previousOrSame(wkst));
    }

    private LocalDate periodStart(long step, LocalDate first) {
        return switch (freq) {
            case DAILY -> first.plusDays(step);
            case WEEKLY -> weekStart(first).plusWeeks(step);
            case MONTHLY -> YearMonth.from(first).plusMonths(step).atDay(1);
            case YEARLY -> LocalDate.of(Math.toIntExact(first.getYear() + step), 1, 1);
        };
    }

    private List<LocalDate> period(long step, LocalDate first) {
        return switch (freq) {
            case DAILY -> matches(first.plusDays(step)) ? List.of(first.plusDays(step)) : List.of();
            case WEEKLY -> week(weekStart(first).plusWeeks(step), first);
            case MONTHLY -> monthSelected(YearMonth.from(first).plusMonths(step))
                    ? monthDates(YearMonth.from(first).plusMonths(step), first.getDayOfMonth()) : List.of();
            case YEARLY -> yearDates(first.getYear() + step, first);
        };
    }

    private boolean monthSelected(YearMonth m) {
        return months == 0 || (months >> m.getMonthValue() & 1) != 0;
    }

    private boolean matches(LocalDate d) {
        return (months == 0 || (months >> d.getMonthValue() & 1) != 0)
                && (monthDays == 0 || monthDayMatches(d))
                && (weekdays.isEmpty() || weekdays.contains(d.getDayOfWeek()));
    }

    private List<LocalDate> week(LocalDate weekStart, LocalDate first) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = weekStart.plusDays(i);
            boolean day = weekdays.isEmpty() ? d.getDayOfWeek() == first.getDayOfWeek() : weekdays.contains(d.getDayOfWeek());
            if (day && (months == 0 || (months >> d.getMonthValue() & 1) != 0)) {
                dates.add(d);
            }
        }
        return dates;
    }

    private List<LocalDate> yearDates(long year, LocalDate first) {
        List<LocalDate> dates = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            if (yearMonthSelected(month, first)) {
                dates.addAll(monthDates(YearMonth.of(Math.toIntExact(year), month), first.getDayOfMonth()));
            }
        }
        return dates;
    }

    /** Without BYMONTH a YEARLY rule uses every month when BYMONTHDAY or BYDAY is given, otherwise only the start month. */
    private boolean yearMonthSelected(int month, LocalDate first) {
        if (months != 0) {
            return (months >> month & 1) != 0;
        }
        return monthDays != 0 || !weekdays.isEmpty() || month == first.getMonthValue();
    }

    private List<LocalDate> monthDates(YearMonth month, int defaultDay) {
        boolean hasDays = !weekdays.isEmpty() || !ordinalDays.isEmpty();
        List<LocalDate> dates = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate d = month.atDay(day);
            boolean dayMatch = monthDays == 0 ? hasDays || day == defaultDay : monthDayMatches(d);
            if (dayMatch && (!hasDays || weekdays.contains(d.getDayOfWeek()) || ordinalDays.stream().anyMatch(b -> ordinalMatches(d, b)))) {
                dates.add(d);
            }
        }
        return dates;
    }

    private boolean monthDayMatches(LocalDate d) {
        int dom = d.getDayOfMonth();
        return (monthDays >> dom & 1) != 0 || (monthDays >> (32 + d.lengthOfMonth() - dom + 1) & 1) != 0;
    }

    private static boolean ordinalMatches(LocalDate d, Day b) {
        if (d.getDayOfWeek() != b.dow()) {
            return false;
        }
        int n = b.ordinal();
        return n > 0 ? (d.getDayOfMonth() - 1) / 7 + 1 == n : (d.lengthOfMonth() - d.getDayOfMonth()) / 7 + 1 == -n;
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

    /** The distinct trimmed entries of a comma list, so a huge duplicate list costs one entry per distinct value. */
    private static Set<String> distinct(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : value.split(",", -1)) {
            out.add(s.trim());
        }
        return out;
    }

    private static int monthMask(String value) {
        int mask = 0;
        if (value != null) {
            for (String s : distinct(value)) {
                mask |= 1 << number(s, "BYMONTH", 1, 12);
            }
        }
        return mask;
    }

    private static long monthDayMask(String value) {
        long mask = 0;
        if (value != null) {
            for (String s : distinct(value)) {
                int n = number(s, "BYMONTHDAY", -31, 31);
                mask |= 1L << (n > 0 ? n : 32 - n);
            }
        }
        return mask;
    }

    private static DayOfWeek weekday(String value) {
        int i = CODES.indexOf(value.trim());
        if (i < 0) {
            throw new IllegalArgumentException("WKST");
        }
        return DayOfWeek.of(i + 1);
    }

    private static Set<Day> days(String value) {
        Set<Day> out = new LinkedHashSet<>();
        for (String s : distinct(value)) {
            Matcher m = DAY.matcher(s);
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
