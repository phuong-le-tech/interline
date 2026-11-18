package app.interline.domain;

import java.util.List;

/** What a calendar source returned for a window: events ordered by start, plus warnings and a skipped count. */
public record CalendarSnapshot(List<CalendarEvent> events, List<String> warnings, int skipped) {
    public CalendarSnapshot {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }
}
