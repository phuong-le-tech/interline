package app.interline.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One VEVENT read into typed fields. {@code end} is a wall-clock time in the zone of {@code start}. */
record IcsEvent(String uid, String title, String conferenceUrl, List<Attendee> attendees, IcsTime start, LocalDateTime end,
                Long recurrenceMs, boolean cancelled, List<IcsProp> props) {
    private static final String NO_TITLE = "(No title)";

    /** Reads a VEVENT; null when it is malformed (no UID, no DTSTART, or an unparseable date). */
    static IcsEvent read(List<IcsProp> props, ZoneId zone, Set<String> warnings) {
        String uid = text(props, "UID").trim();
        IcsProp dtstart = first(props, "DTSTART");
        IcsTime start = dtstart == null ? null : IcsTime.parse(dtstart, zone, warnings);
        if (uid.isEmpty() || start == null) {
            return null;
        }
        LocalDateTime end = end(props, start, zone, warnings);
        IcsProp rid = first(props, "RECURRENCE-ID");
        IcsTime recurrence = rid == null ? null : IcsTime.parse(rid, zone, warnings);
        if (end == null || rid != null && recurrence == null) {
            return null;
        }
        String title = text(props, "SUMMARY").trim();
        return new IcsEvent(uid, title.isEmpty() ? NO_TITLE : title, conferenceUrl(props), attendees(props), start, end,
                recurrence == null ? null : recurrence.epochMs(), "CANCELLED".equalsIgnoreCase(text(props, "STATUS").trim()), props);
    }

    List<IcsProp> all(String name) {
        return props.stream().filter(p -> p.name().equals(name)).toList();
    }

    long startMs() {
        return start.epochMs();
    }

    /** The event as if it started at the given wall-clock time in its zone, keeping the wall-clock length. */
    CalendarEvent at(String externalId, LocalDateTime occurrenceStart) {
        Duration span = Duration.between(start.local(), end);
        long startMs = occurrenceStart.atZone(start.zone()).toInstant().toEpochMilli();
        long endMs = occurrenceStart.plus(span).atZone(start.zone()).toInstant().toEpochMilli();
        return new CalendarEvent(externalId, title, startMs, endMs, conferenceUrl, attendees);
    }

    private static LocalDateTime end(List<IcsProp> props, IcsTime start, ZoneId zone, Set<String> warnings) {
        IcsProp dtend = first(props, "DTEND");
        IcsProp duration = first(props, "DURATION");
        LocalDateTime end;
        if (dtend != null) {
            IcsTime t = IcsTime.parse(dtend, zone, warnings);
            end = t == null ? null : t.local().atZone(t.zone()).withZoneSameInstant(start.zone()).toLocalDateTime();
        } else if (duration != null && IcsTime.parseDuration(duration.value()) != null) {
            end = start.local().plus(IcsTime.parseDuration(duration.value()));
        } else {
            end = start.allDay() ? start.local().plusDays(1) : start.local();
        }
        return end != null && end.isBefore(start.local()) ? start.local() : end;
    }

    private static String conferenceUrl(List<IcsProp> props) {
        for (String name : List.of("URL", "X-GOOGLE-CONFERENCE", "LOCATION")) {
            String value = text(props, name).trim();
            String scheme = name.equals("LOCATION") ? "https://" : "http";
            if (value.toLowerCase(Locale.ROOT).startsWith(scheme) && value.contains("://")) {
                return value;
            }
        }
        return null;
    }

    private static List<Attendee> attendees(List<IcsProp> props) {
        List<Attendee> out = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        List<IcsProp> people = new ArrayList<>(props.stream().filter(p -> p.name().equals("ORGANIZER")).toList());
        people.addAll(props.stream().filter(p -> p.name().equals("ATTENDEE")).toList());
        for (IcsProp p : people) {
            Attendee a = attendee(p);
            if (a != null && (a.email() == null || emails.add(a.email()))) {
                out.add(a);
            }
        }
        return out;
    }

    private static Attendee attendee(IcsProp p) {
        String value = p.value().trim();
        String address = value.regionMatches(true, 0, "mailto:", 0, 7) ? value.substring(7).trim() : value;
        String email = address.contains("@") && !address.contains(":") ? address.toLowerCase(Locale.ROOT) : null;
        String cn = p.param("CN") == null ? "" : IcsProp.unescape(p.param("CN")).trim();
        String name = cn.isEmpty() ? null : cn;
        return email == null && name == null ? null : new Attendee(name, email);
    }

    private static IcsProp first(List<IcsProp> props, String name) {
        return props.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    private static String text(List<IcsProp> props, String name) {
        IcsProp p = first(props, name);
        return p == null ? "" : IcsProp.unescape(p.value());
    }
}
