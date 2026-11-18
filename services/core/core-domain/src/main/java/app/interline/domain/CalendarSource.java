package app.interline.domain;

/** Port for a calendar provider (ICS subscription now, EventKit through the agent later). */
public interface CalendarSource {
    /** Events overlapping {@code [windowStartMs, windowEndMs)}. */
    CalendarSnapshot fetch(long windowStartMs, long windowEndMs);
}
