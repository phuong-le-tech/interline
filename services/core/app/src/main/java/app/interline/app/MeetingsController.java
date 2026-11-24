package app.interline.app;

import app.interline.persistence.CalendarStore.PersonRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Draft meetings, their participants and the brief (generation only on explicit POST). */
@RestController
@RequestMapping("/api/v1")
class MeetingsController {
    private final MeetingService meetings;

    private final BriefService briefs;

    MeetingsController(MeetingService meetings, BriefService briefs) {
        this.meetings = meetings;
        this.briefs = briefs;
    }

    @PostMapping("/meetings")
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        JsonBody body = new JsonBody(request);
        String title = MeetingService.cleanTitle(body.requiredText("title"));
        String id = meetings.createManual(title, MeetingService.parseParticipants(request.get("participants")));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/calendar/events/{eventId}/meeting")
    ResponseEntity<Map<String, Object>> fromEvent(@PathVariable("eventId") String eventId) {
        MeetingService.Created created = meetings.fromEvent(eventId);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("id", created.id()));
    }

    @GetMapping("/meetings/{id}/participants")
    List<PersonView> participants(@PathVariable("id") String id) {
        return meetings.participants(id).stream().map(MeetingsController::person).toList();
    }

    @PutMapping("/meetings/{id}/participants")
    List<PersonView> replaceParticipants(@PathVariable("id") String id, @RequestBody List<Map<String, Object>> request) {
        return meetings.replaceParticipants(id, MeetingService.parseParticipants(request)).stream().map(MeetingsController::person).toList();
    }

    @GetMapping("/meetings/{id}/brief")
    Map<String, Object> brief(@PathVariable("id") String id) {
        MeetingService.Brief brief = meetings.brief(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("meetingId", brief.meetingId());
        view.put("participants", brief.participants().stream().map(MeetingsController::person).toList());
        view.put("related", brief.related().stream().map(MeetingsController::related).toList());
        view.put("openActions", brief.openActions().stream().map(MeetingsController::action).toList());
        view.put("ai", briefs.cached(brief));
        return view;
    }

    @PostMapping("/meetings/{id}/brief/ai")
    BriefService.Outcome writeBrief(@PathVariable("id") String id) {
        return briefs.write(id);
    }

    /** {@code email} is always present, null for a person known by name only. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PersonView(String id, String name, String email) { }

    /** Nullable fields stay in the JSON as null so clients can rely on the shape. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ActionView(String id, String meetingId, String text,
                      String ownerLabel,
                      String dueDate) { }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body is missing or not valid");
        problem.setTitle("invalid_request");
        problem.setProperty("code", "invalid_request");
        return problem;
    }

    private static PersonView person(PersonRow row) {
        return new PersonView(row.id(), row.displayName(), row.email());
    }

    private static Map<String, Object> related(MeetingService.RelatedMeeting related) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("meetingId", related.match().candidate().meetingId());
        view.put("title", related.match().candidate().title());
        view.put("date", related.match().candidate().dateMs());
        view.put("matchedBy", related.match().reason().name().toLowerCase(java.util.Locale.ROOT));
        view.put("notes", related.notes());
        return view;
    }

    private static ActionView action(app.interline.persistence.MeetingStore.ActionRow row) {
        return new ActionView(row.id(), row.meetingId(), row.text(), row.ownerLabel(), row.dueDate());
    }
}
