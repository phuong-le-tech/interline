package app.interline.app;

import app.interline.domain.Attendee;
import app.interline.persistence.CalendarStore.EventRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The ICS calendar over the local API. Views never carry the subscription URL, only its host. */
@RestController
@RequestMapping("/api/v1/calendar")
class CalendarController {
    private final CalendarService calendar;

    CalendarController(CalendarService calendar) { this.calendar = calendar; }

    /** The calendar address in a request body. Its text form is redacted so request logging can never print it. */
    record SourceRequest(String url) {
        @Override public String toString() { return "SourceRequest[redacted]"; }
    }

    @PutMapping("/ics-source")
    ResponseEntity<Void> putSource(@RequestBody SourceRequest request) {
        if (request.url() == null || request.url().isBlank()) throw ApiProblems.badRequest("url is required");
        calendar.setSource(request.url());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ics-source")
    Map<String, Object> getSource() {
        Map<String, Object> view = new LinkedHashMap<>();
        boolean configured = calendar.configured();
        view.put("configured", configured);
        if (configured) view.put("host", calendar.host().orElse(null));
        return view;
    }

    @DeleteMapping("/ics-source")
    ResponseEntity<Void> deleteSource() {
        calendar.removeSource();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sync")
    Map<String, Object> sync() {
        CalendarService.SyncResult result = calendar.sync();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("upserted", result.upserted());
        view.put("removed", result.removed());
        view.put("skipped", result.skipped());
        view.put("warnings", result.warnings());
        return view;
    }

    @GetMapping("/events")
    List<Map<String, Object>> events(@RequestParam(name = "from", required = false) String from,
                                     @RequestParam(name = "to", required = false) String to) {
        return calendar.events(epoch("from", from), epoch("to", to)).stream().map(CalendarController::view).toList();
    }

    private static Long epoch(String name, String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw ApiProblems.badRequest(name + " must be epoch milliseconds");
        }
    }

    private static Map<String, Object> view(EventRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("source", row.source());
        view.put("external_id", row.externalId());
        view.put("title", row.title());
        view.put("starts_at", row.startsAt());
        view.put("ends_at", row.endsAt());
        view.put("conference_url", row.conferenceUrl());
        view.put("attendees", row.attendees().stream().map(CalendarController::attendee).toList());
        return view;
    }

    private static Map<String, Object> attendee(Attendee attendee) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", attendee.name());
        view.put("email", attendee.email());
        return view;
    }
}
