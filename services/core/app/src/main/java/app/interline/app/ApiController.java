package app.interline.app;

import app.interline.persistence.CaptureStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** The local HTTP API under /api/v1: recording control, meeting list, transcript and the event stream. */
@RestController
@RequestMapping("/api/v1")
class ApiController {
    private final RecordingService recording;
    private final DurableCaptureIngest ingest;
    private final EventBus events;

    ApiController(RecordingService recording, DurableCaptureIngest ingest, EventBus events) {
        this.recording = recording;
        this.ingest = ingest;
        this.events = events;
    }

    record StartedRecording(String meetingId) { }

    record Meeting(String id, String title, String status, Long startedAt, Long endedAt) { }

    record TranscriptLine(String stream, String seg, String speaker, long startMs, long endMs, String text) { }

    @PostMapping("/recording/start")
    StartedRecording start() {
        return new StartedRecording(call(recording::start));
    }

    @PostMapping("/recording/stop")
    Map<String, String> stop() {
        call(() -> { recording.stop(); return null; });
        return Map.of("status", "stopping");
    }

    @GetMapping("/meetings")
    List<Meeting> meetings() {
        return ingest.store().listMeetings().stream()
                .map(row -> new Meeting(row.id(), row.title(), row.status(), row.startedAt(), row.endedAt())).toList();
    }

    @GetMapping("/meetings/{id}/transcript")
    List<TranscriptLine> transcript(@PathVariable("id") String id) {
        CaptureStore store = ingest.store();
        if (store.findMeeting(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown meeting");
        return store.transcript(id).stream().map(row -> new TranscriptLine(row.stream(), row.seg(), row.speaker(),
                row.startMs(), row.endMs(), row.text())).toList();
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return events.subscribe();
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RecordingService.RecordingException e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.status()), e.getMessage());
        }
    }
}
