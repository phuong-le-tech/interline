package app.interline.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One VEVENT read into typed fields. {@code length} says where it ends, in the zone of {@code start}. */
record IcsEvent(String uid, String title, String conferenceUrl, List<Attendee> attendees, IcsTime start, Length length,
                Long recurrenceMs, boolean cancelled, List<IcsProp> props) {
    private static final String NO_TITLE = "(No title)";

    /** How long an event lasts: a wall-clock span (DTEND), nominal calendar days (D, W) and elapsed time (H, M, S). */
    record Length(long days, Duration wall, Duration elapsed) {
        static final Length ZERO = new Length(0, Duration.ZERO, Duration.ZERO);

        long endMs(LocalDateTime start, ZoneId zone) {
            return start.plusDays(days).plus(wall).atZone(zone).toInstant().toEpochMilli() + elapsed.toMillis();
        }
    }

    /** Reads a VEVENT; null when it is malformed (no UID, no DTSTART, or an unparseable date). */
    static IcsEvent read(List<IcsProp> props, ZoneId zone, Set<String> warnings) {
        String uid = text(props, "UID").trim();
        IcsProp dtstart = first(props, "DTSTART");
        IcsTime start = dtstart == null ? null : IcsTime.parse(dtstart, zone, warnings);
        if (uid.isEmpty() || start == null) {
            return null;
        }
        Length length = length(props, start, zone, warnings);
        IcsProp rid = first(props, "RECURRENCE-ID");
        IcsTime recurrence = rid == null ? null : IcsTime.parse(rid, zone, warnings);
        if (length == null || rid != null && recurrence == null) {
            return null;
        }
        return new IcsEvent(uid, title(props, warnings), conferenceUrl(props, warnings), attendees(props, warnings), start, length,
                recurrence == null ? null : recurrence.epochMs(), "CANCELLED".equalsIgnoreCase(text(props, "STATUS").trim()), props);
    }

    List<IcsProp> all(String name) {
        return props.stream().filter(p -> p.name().equals(name)).toList();
    }

    long startMs() {
        return start.epochMs();
    }

    /** The event as if it started at the given wall-clock time in its zone. */
    CalendarEvent at(String externalId, LocalDateTime occurrenceStart) {
        long startMs = occurrenceStart.atZone(start.zone()).toInstant().toEpochMilli();
        return new CalendarEvent(externalId, title, startMs, length.endMs(occurrenceStart, start.zone()), conferenceUrl, attendees);
    }

    /** An override with no people and no link of its own takes the attendees and conference URL of its master. */
    IcsEvent inheritFrom(IcsEvent master) {
        boolean own = conferenceUrl != null || props.stream().anyMatch(p -> p.name().equals("ATTENDEE") || p.name().equals("ORGANIZER"));
        return own ? this : new IcsEvent(uid, title, master.conferenceUrl, master.attendees, start, length, recurrenceMs, cancelled, props);
    }

    private static Length length(List<IcsProp> props, IcsTime start, ZoneId zone, Set<String> warnings) {
        IcsProp dtend = first(props, "DTEND");
        IcsProp duration = first(props, "DURATION");
        if (dtend != null) {
            IcsTime t = IcsTime.parse(dtend, zone, warnings);
            if (t == null) {
                return null;
            }
            if (!start.allDay() && !t.allDay()) {
                return new Length(0, Duration.ZERO, Duration.ofMillis(Math.max(0, t.epochMs() - start.epochMs())));
            }
            LocalDateTime end = t.local().atZone(t.zone()).withZoneSameInstant(start.zone()).toLocalDateTime();
            return new Length(0, end.isBefore(start.local()) ? Duration.ZERO : Duration.between(start.local(), end), Duration.ZERO);
        }
        Length parsed = duration == null ? null : IcsTime.parseDuration(duration.value());
        if (parsed != null) {
            return parsed;
        }
        return start.allDay() ? new Length(1, Duration.ZERO, Duration.ZERO) : Length.ZERO;
    }

    private static String title(List<IcsProp> props, Set<String> warnings) {
        String title = text(props, "SUMMARY").trim();
        if (title.length() > IcsParser.MAX_TITLE_CHARS) {
            warnings.add("A title longer than " + IcsParser.MAX_TITLE_CHARS + " characters was cut");
            title = IcsProp.truncate(title, IcsParser.MAX_TITLE_CHARS);
        }
        return title.isEmpty() ? NO_TITLE : title;
    }

    private static String conferenceUrl(List<IcsProp> props, Set<String> warnings) {
        for (String name : List.of("URL", "X-GOOGLE-CONFERENCE", "LOCATION")) {
            String value = text(props, name).trim();
            String scheme = name.equals("LOCATION") ? "https://" : "http";
            if (!value.toLowerCase(Locale.ROOT).startsWith(scheme) || !value.contains("://")) {
                continue;
            }
            if (value.length() <= IcsParser.MAX_URL_CHARS) {
                return value;
            }
            warnings.add("A conference link longer than " + IcsParser.MAX_URL_CHARS + " characters was ignored");
        }
        return null;
    }

    private static List<Attendee> attendees(List<IcsProp> props, Set<String> warnings) {
        List<Attendee> out = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        List<IcsProp> people = new ArrayList<>(props.stream().filter(p -> p.name().equals("ORGANIZER")).toList());
        people.addAll(props.stream().filter(p -> p.name().equals("ATTENDEE")).toList());
        for (IcsProp p : people) {
            Attendee a = attendee(p, warnings);
            if (a == null || a.email() != null && !emails.add(a.email())) {
                continue;
            }
            if (out.size() == IcsParser.MAX_ATTENDEES_PER_EVENT) {
                warnings.add("More than " + IcsParser.MAX_ATTENDEES_PER_EVENT + " attendees on an event, the rest were dropped");
                break;
            }
            out.add(a);
        }
        return out;
    }

    private static Attendee attendee(IcsProp p, Set<String> warnings) {
        String value = p.value().trim();
        String address = value.regionMatches(true, 0, "mailto:", 0, 7) ? value.substring(7).trim() : value;
        String email = address.contains("@") && !address.contains(":") ? address.toLowerCase(Locale.ROOT) : null;
        if (email != null && email.length() > IcsParser.MAX_EMAIL_CHARS) {
            warnings.add("An attendee email longer than " + IcsParser.MAX_EMAIL_CHARS + " characters was dropped");
            email = null;
        }
        String cn = p.param("CN") == null ? "" : IcsProp.unescape(p.param("CN")).trim();
        if (cn.length() > IcsParser.MAX_NAME_CHARS) {
            warnings.add("An attendee name longer than " + IcsParser.MAX_NAME_CHARS + " characters was cut");
            cn = IcsProp.truncate(cn, IcsParser.MAX_NAME_CHARS);
        }
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
