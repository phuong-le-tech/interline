package app.interline.app;

import app.interline.domain.BriefMatcher;
import app.interline.domain.BriefMatcher.Match;
import app.interline.domain.UuidV7;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.CalendarStore.EventRow;
import app.interline.persistence.CalendarStore.PersonRow;
import app.interline.persistence.MeetingStore;
import app.interline.persistence.MeetingStore.ActionRow;
import app.interline.persistence.MeetingStore.MeetingInfo;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Draft meetings (by hand or from a calendar event), their participants, and the brief built without AI. */
final class MeetingService {
    static final int MAX_TITLE_CHARS = 500;
    static final int MAX_PARTICIPANTS = 200;
    static final int MAX_NAME_CHARS = 200;
    static final int MAX_EMAIL_CHARS = 320;
    private static final int PAGE_SIZE = 500;
    private static final int NOTES_PER_MEETING = 5;

    /** One participant as the user typed it; either part may be blank but not both. */
    record ParticipantInput(String name, String email) { }

    record Created(String id, boolean created) { }

    record RelatedMeeting(Match match, List<String> notes) { }

    record Brief(String meetingId, List<PersonRow> participants, List<RelatedMeeting> related, List<ActionRow> openActions) { }

    private final MeetingStore meetings;
    private final CalendarStore calendar;
    private final Clock clock;

    MeetingService(MeetingStore meetings, CalendarStore calendar, Clock clock) {
        this.meetings = meetings;
        this.calendar = calendar;
        this.clock = clock;
    }

    String createManual(String title, List<ParticipantInput> participants) {
        String id = UuidV7.next(clock.millis());
        meetings.createManual(id, cleanTitle(title), people(participants), clock.millis());
        return id;
    }

    /** Idempotent per event: the first call creates the draft, later calls return the same meeting. */
    Created fromEvent(String eventId) {
        var result = meetings.createFromEvent(eventId, UuidV7.next(clock.millis()), clock.millis())
                .orElseThrow(() -> ApiProblems.notFound("calendar event"));
        return new Created(result.id(), result.created());
    }

    List<PersonRow> participants(String meetingId) {
        requireMeeting(meetingId);
        return calendar.listParticipants(meetingId);
    }

    List<PersonRow> replaceParticipants(String meetingId, List<ParticipantInput> participants) {
        return meetings.replaceParticipantsAndList(meetingId, people(participants), clock.millis())
                .orElseThrow(() -> ApiProblems.notFound("meeting"));
    }

    Brief brief(String meetingId) {
        MeetingInfo meeting = requireMeeting(meetingId);
        var subject = meetings.subject(meetingId).orElseThrow(() -> ApiProblems.notFound("meeting"));
        long before = meeting.calendarEventId() == null ? clock.millis()
                : calendar.findEvent(meeting.calendarEventId()).map(EventRow::startsAt).orElse(clock.millis());
        List<Match> matches = relatedMatches(subject, meetingId, before);
        List<RelatedMeeting> related = new ArrayList<>();
        for (Match match : matches) {
            related.add(new RelatedMeeting(match, meetings.userNotes(match.candidate().meetingId(), NOTES_PER_MEETING)));
        }
        List<ActionRow> actions = meetings.openActions(matches.stream().map(m -> m.candidate().meetingId()).toList());
        return new Brief(meetingId, calendar.listParticipants(meetingId), related, actions);
    }

    /**
     * Matches first, limits after: candidates are read page by page, most recent first, so an old related meeting is not
     * hidden behind many newer unrelated ones. The scan stops once enough matches are found (everything left is older)
     * or when there are no more meetings; no history is ever dropped silently.
     */
    private List<Match> relatedMatches(BriefMatcher.Subject subject, String meetingId, long before) {
        List<BriefMatcher.Candidate> matched = new ArrayList<>();
        for (int offset = 0; ; offset += PAGE_SIZE) {
            List<BriefMatcher.Candidate> page = meetings.candidates(meetingId, before, PAGE_SIZE, offset);
            BriefMatcher.rank(subject, page).forEach(match -> matched.add(match.candidate()));
            if (page.size() < PAGE_SIZE || matched.size() >= BriefMatcher.MAX_RELATED) break;
        }
        return BriefMatcher.rank(subject, matched);
    }

    private MeetingInfo requireMeeting(String meetingId) {
        return meetings.find(meetingId).orElseThrow(() -> ApiProblems.notFound("meeting"));
    }

    static String cleanTitle(String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty()) throw ApiProblems.badRequest("title is required");
        if (clean.length() > MAX_TITLE_CHARS) throw ApiProblems.badRequest("title must be at most " + MAX_TITLE_CHARS + " characters");
        return clean;
    }

    /** Typed input: every entry must be valid, otherwise the whole request is refused. */
    static List<ParticipantInput> parseParticipants(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw ApiProblems.badRequest("participants must be an array");
        if (list.size() > MAX_PARTICIPANTS) throw ApiProblems.badRequest("at most " + MAX_PARTICIPANTS + " participants");
        List<ParticipantInput> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw ApiProblems.badRequest("each participant must be an object");
            String name = text(map.get("name"), "name", MAX_NAME_CHARS);
            String email = text(map.get("email"), "email", MAX_EMAIL_CHARS);
            if (name.isEmpty() && email.isEmpty()) throw ApiProblems.badRequest("a participant needs a name or an email");
            if (!email.isEmpty() && !email.contains("@")) throw ApiProblems.badRequest("email must contain @");
            result.add(new ParticipantInput(name, email));
        }
        return result;
    }

    private static String text(Object value, String field, int max) {
        if (value == null) return "";
        if (!(value instanceof String s)) throw ApiProblems.badRequest(field + " must be a string");
        String clean = s.strip();
        if (clean.length() > max) throw ApiProblems.badRequest(field + " must be at most " + max + " characters");
        return clean;
    }

    private static List<MeetingStore.PersonInput> people(List<ParticipantInput> inputs) {
        return inputs.stream().map(input -> new MeetingStore.PersonInput(input.name(), input.email())).toList();
    }
}
