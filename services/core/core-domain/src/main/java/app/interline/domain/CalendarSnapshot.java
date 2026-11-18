package app.interline.domain;

import java.util.List;

/**
 * What a calendar source returned for a window: events ordered by start, plus warnings and a skipped count.
 * {@code complete} is false when parsing stopped early (a budget, a deadline or a cap): the events are then a
 * partial view and must not be used to delete what is already stored.
 */
public record CalendarSnapshot(List<CalendarEvent> events, List<String> warnings, int skipped, boolean complete) {
    public CalendarSnapshot {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }

    public CalendarSnapshot(List<CalendarEvent> events, List<String> warnings, int skipped) {
        this(events, warnings, skipped, true);
    }
}
