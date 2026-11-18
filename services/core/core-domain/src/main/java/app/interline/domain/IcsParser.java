package app.interline.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Hand-written RFC 5545 subset: reads an ICS body into the events overlapping a window. Never throws. */
public final class IcsParser {
    public static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    public static final int MAX_LINE_CHARS = 64 * 1024;
    public static final int MAX_VEVENTS = 20_000;
    public static final int MAX_OCCURRENCES_PER_SERIES = 500;
    public static final int MAX_EVENTS_PER_SYNC = 5_000;
    public static final int MAX_PERIOD_STEPS = 2_000_000;
    public static final long MAX_PARSE_MILLIS = 10_000;
    public static final int MAX_ATTENDEES_PER_EVENT = 200;
    public static final int MAX_TITLE_CHARS = 500;
    public static final int MAX_NAME_CHARS = 200;
    public static final int MAX_EMAIL_CHARS = 320;
    public static final int MAX_URL_CHARS = 2048;

    private static final long CLAMP_MS = 100_000_000_000_000_000L;
    private static final long SLACK_MS = 2L * 24 * 3_600_000;

    private static final Comparator<CalendarEvent> ORDER =
            Comparator.comparingLong(CalendarEvent::startsAt).thenComparing(CalendarEvent::externalId);

    private final ZoneId zone;
    private final long windowStart;
    private final long windowEnd;
    private final Set<String> warnings = new LinkedHashSet<>();
    /** Largest-first, so the latest event is evicted when more than MAX_EVENTS_PER_SYNC are kept. */
    private final PriorityQueue<CalendarEvent> kept = new PriorityQueue<>(ORDER.reversed());
    private final IcsRecurrence.Budget budget = new IcsRecurrence.Budget();
    private boolean cut;
    private int skipped;

    private IcsParser(ZoneId zone, long windowStart, long windowEnd) {
        this.zone = zone;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public static CalendarSnapshot parse(byte[] body, ZoneId localZone, long windowStartMs, long windowEndMs) {
        IcsParser parser = new IcsParser(localZone, windowStartMs, windowEndMs);
        try {
            parser.run(body);
        } catch (RuntimeException e) {
            parser.warnings.add("The calendar could not be fully read");
        }
        return parser.snapshot();
    }

    /** True when the body has a BEGIN:VCALENDAR line followed later by an END:VCALENDAR line (names case-insensitive). */
    public static boolean isCompleteCalendar(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        boolean begun = false;
        for (String line : new IcsParser(ZoneOffset.UTC, 0, 0).unfold(text)) {
            String name = line.trim();
            if (!begun) {
                begun = name.equalsIgnoreCase("BEGIN:VCALENDAR");
            } else if (name.equalsIgnoreCase("END:VCALENDAR")) {
                return true;
            }
        }
        return false;
    }

    private void run(byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            warnings.add("Calendar larger than 10 MB ignored");
            return;
        }
        List<IcsEvent> events = new ArrayList<>();
        for (List<IcsProp> props : components(unfold(new String(body, StandardCharsets.UTF_8)))) {
            IcsEvent event = IcsEvent.read(props, zone, warnings);
            if (event == null) {
                skipped++;
            } else {
                events.add(event);
            }
        }
        expand(events);
    }

    private CalendarSnapshot snapshot() {
        Map<String, CalendarEvent> byId = new LinkedHashMap<>();
        kept.stream().sorted(ORDER).forEach(e -> byId.putIfAbsent(e.externalId(), e));
        if (budget.exhausted() != null) {
            warnings.add(budget.exhausted().equals("time")
                    ? "The calendar took too long to expand (over " + MAX_PARSE_MILLIS + " ms), some recurring events were cut"
                    : "The calendar is too complex to expand (over " + MAX_PERIOD_STEPS + " steps), some recurring events were cut");
        }
        if (cut) {
            warnings.add("More than " + MAX_EVENTS_PER_SYNC + " events in the window, the latest were dropped");
        }
        return new CalendarSnapshot(new ArrayList<>(byId.values()), new ArrayList<>(warnings), skipped);
    }

    // ---------------------------------------------------------------- lines and components

    /** Splits into logical lines (CRLF or LF, folded continuations joined); a line over MAX_LINE_CHARS is dropped with a warning. */
    private List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean tooLong = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                line = line.substring(1);
            } else {
                finishLine(lines, current, tooLong);
                current.setLength(0);
                tooLong = false;
            }
            if (!tooLong) {
                current.append(line);
                tooLong = current.length() > MAX_LINE_CHARS;
            }
        }
        finishLine(lines, current, tooLong);
        return lines;
    }

    private void finishLine(List<String> lines, StringBuilder line, boolean tooLong) {
        if (tooLong) {
            warnings.add("A line longer than 64 KB was skipped");
        } else if (line.length() > 0) {
            lines.add(line.toString());
        }
    }

    /** The properties of each complete VEVENT; other components (and sub-components such as VALARM) are ignored. */
    private List<List<IcsProp>> components(List<String> lines) {
        List<List<IcsProp>> events = new ArrayList<>();
        List<IcsProp> current = null;
        int depth = 0;
        for (String line : lines) {
            IcsProp p = IcsProp.parse(line);
            if (p == null) {
                continue;
            }
            boolean vevent = p.value().trim().equalsIgnoreCase("VEVENT");
            if (p.name().equals("BEGIN") && vevent) {
                if (events.size() >= MAX_VEVENTS) {
                    warnings.add("More than " + MAX_VEVENTS + " events, the rest were ignored");
                    return events;
                }
                current = new ArrayList<>();
                depth = 0;
            } else if (p.name().equals("END") && vevent && current != null) {
                events.add(current);
                current = null;
            } else if (current != null && (p.name().equals("BEGIN") || p.name().equals("END"))) {
                depth += p.name().equals("BEGIN") ? 1 : -1;
            } else if (current != null && depth == 0) {
                current.add(p);
            }
        }
        return events;
    }

    // ---------------------------------------------------------------- events and series

    private void expand(List<IcsEvent> events) {
        Map<String, Map<Long, IcsEvent>> overrides = new HashMap<>();
        Set<String> masters = new HashSet<>();
        for (IcsEvent e : events) {
            if (e.recurrenceMs() == null) {
                masters.add(e.uid());
            } else {
                overrides.computeIfAbsent(e.uid(), u -> new HashMap<>()).put(e.recurrenceMs(), e);
            }
        }
        for (IcsEvent e : events) {
            try {
                if (e.recurrenceMs() == null) {
                    series(e, overrides.getOrDefault(e.uid(), Map.of()));
                } else if (!masters.contains(e.uid())) {
                    keep(occurrence(e, e.uid() + "#" + e.recurrenceMs(), e.start().local()));
                }
            } catch (RuntimeException ex) {
                skipped++;
            }
        }
    }

    /** One series being expanded: counts what it emitted against the per-series cap. */
    private final class SeriesRun {
        final IcsEvent master;
        final Map<Long, IcsEvent> overrides;
        final Set<Long> excluded;
        int emitted;
        boolean full;

        SeriesRun(IcsEvent master, Map<Long, IcsEvent> overrides, Set<Long> excluded) {
            this.master = master;
            this.overrides = overrides;
            this.excluded = excluded;
        }

        String id(long originalMs) {
            return master.uid() + "#" + originalMs;
        }

        /** Keeps the event (null is ignored); false once the per-series cap is reached. */
        boolean emit(CalendarEvent ev) {
            if (ev == null) {
                return true;
            }
            if (emitted == MAX_OCCURRENCES_PER_SERIES) {
                warnings.add("A series has more than " + MAX_OCCURRENCES_PER_SERIES + " occurrences in the window, the rest were dropped");
                full = true;
                return false;
            }
            emitted++;
            keep(ev);
            return true;
        }

        /** The occurrence of the series at this original start: its override when there is one, else the master. */
        CalendarEvent instance(long originalMs, LocalDateTime at) {
            IcsEvent override = overrides.get(originalMs);
            return override == null ? occurrence(master, id(originalMs), at)
                    : occurrence(override.inheritFrom(master), id(originalMs), override.start().local());
        }
    }

    private void series(IcsEvent e, Map<Long, IcsEvent> overrides) {
        if (e.cancelled()) {
            return;
        }
        List<IcsProp> rrule = e.all("RRULE");
        boolean rdate = !e.all("RDATE").isEmpty();
        if (rrule.isEmpty() && !rdate) {
            keep(occurrence(e, e.uid(), e.start().local()));
            return;
        }
        SeriesRun run = new SeriesRun(e, overrides, exdates(e));
        IcsRecurrence.Parsed parsed = rrule.isEmpty() ? null : IcsRecurrence.parse(rrule.get(0).value(), e.start());
        String unsupported = rdate ? "RDATE" : parsed.unsupported();
        if (unsupported != null) {
            warnings.add("Unsupported recurrence part " + unsupported + ": only the first occurrence is kept");
            firstOccurrenceOnly(run);
            return;
        }
        if (!parsed.rule().forEach(e.start(), windowDate(e, windowStart, true), windowDate(e, windowEnd, false), budget, at -> visit(run, at))) {
            warnings.add("A series is too long to expand completely, the rest was dropped");
        }
        movedInstances(run, parsed.rule());
    }

    private void firstOccurrenceOnly(SeriesRun run) {
        long ms = run.master.start().epochMs();
        if (!run.excluded.contains(ms)) {
            run.emit(run.instance(ms, run.master.start().local()));
        }
    }

    /** Visits one generated occurrence; overridden ones are left to {@link #movedInstances}. */
    private boolean visit(SeriesRun run, LocalDateTime at) {
        long ms = at.atZone(run.master.start().zone()).toInstant().toEpochMilli();
        if (ms >= windowEnd) {
            return false;
        }
        return run.excluded.contains(ms) || run.overrides.containsKey(ms) || run.emit(occurrence(run.master, run.id(ms), at));
    }

    /** Overrides are selected by their own DTSTART, so one whose RECURRENCE-ID is outside the window can still show up. */
    private void movedInstances(SeriesRun run, IcsRecurrence rule) {
        for (Map.Entry<Long, IcsEvent> entry : run.overrides.entrySet()) {
            long original = entry.getKey();
            if (run.full || run.excluded.contains(original) || !rule.occursAt(run.master.start(), original, budget)) {
                continue;
            }
            IcsEvent override = entry.getValue().inheritFrom(run.master);
            run.emit(occurrence(override, run.id(original), override.start().local()));
        }
    }

    /** The series-zone date to start walking from (window start minus the event length) or to stop at (window end), with slack. */
    private LocalDate windowDate(IcsEvent e, long ms, boolean before) {
        long length = Math.max(0, e.length().endMs(e.start().local(), e.start().zone()) - e.startMs());
        long shifted = before ? ms - length - SLACK_MS : ms + SLACK_MS;
        long clamped = Math.max(-CLAMP_MS, Math.min(CLAMP_MS, shifted));
        return Instant.ofEpochMilli(clamped).atZone(e.start().zone()).toLocalDate();
    }

    /** The event at the given start, or null when cancelled or outside the window. */
    private CalendarEvent occurrence(IcsEvent source, String id, LocalDateTime at) {
        if (source.cancelled()) {
            return null;
        }
        CalendarEvent ev = source.at(id, at);
        return ev.startsAt() < windowEnd && Math.max(ev.endsAt(), ev.startsAt() + 1) > windowStart ? ev : null;
    }

    private void keep(CalendarEvent ev) {
        if (ev == null) {
            return;
        }
        kept.add(ev);
        if (kept.size() > MAX_EVENTS_PER_SYNC) {
            kept.poll();
            cut = true;
        }
    }

    private Set<Long> exdates(IcsEvent e) {
        Set<Long> out = new HashSet<>();
        for (IcsProp p : e.all("EXDATE")) {
            for (String value : p.value().split(",")) {
                IcsTime t = IcsTime.parse(value, p.params(), zone, warnings);
                if (t != null) {
                    out.add(t.epochMs());
                }
            }
        }
        return out;
    }
}
