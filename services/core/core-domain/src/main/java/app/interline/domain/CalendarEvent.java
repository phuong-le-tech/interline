package app.interline.domain;

import java.util.List;

/** One calendar event or one occurrence of a recurring series, times in epoch milliseconds (UTC). */
public record CalendarEvent(String externalId, String title, long startsAt, long endsAt, String conferenceUrl,
                            List<Attendee> attendees) {
    public CalendarEvent {
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
    }
}
