package app.interline.app;

import app.interline.persistence.CaptureStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ModelService models;
    private final InterlineProperties properties;

    ApiController(RecordingService recording, DurableCaptureIngest ingest, EventBus events, ModelService models,
                  InterlineProperties properties) {
        this.models = models;
        this.recording = recording;
        this.ingest = ingest;
        this.events = events;
        this.properties = properties;
    }

    record StartedRecording(String meetingId) { }

    record StartRequest(String language) { }

    record Meeting(String id, String title, String status, Long startedAt, Long endedAt) { }

    record TranscriptLine(long id, String stream, String seg, String speaker, long startMs, long endMs, String text) { }

    @PostMapping("/recording/start")
    StartedRecording start(@RequestBody(required = false) StartRequest request) {
        String requested = request != null ? request.language() : null;
        String effective = requested != null ? requested : properties.stt().language();
        return new StartedRecording(call(() -> recording.start(effective)));
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
        return store.transcript(id).stream().map(row -> new TranscriptLine(row.id(), row.stream(), row.seg(), row.speaker(),
                row.startMs(), row.endMs(), row.text())).toList();
    }

    @GetMapping("/models")
    List<Map<String, Object>> models() {
        return models.statuses().stream().map(ModelService::view).toList();
    }

    /** The one place a model is fetched: only on this explicit request, never at boot or while recording. */
    @PostMapping("/models/{id}/download")
    ResponseEntity<Map<String, Object>> downloadModel(@PathVariable("id") String id) {
        var entry = models.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown model"));
        boolean started = models.startDownload(entry);
        return ResponseEntity.accepted().body(Map.of("id", id, "started", started));
    }

    @GetMapping("/meetings/{id}/stats")
    Map<String, Object> stats(@PathVariable("id") String id) {
        if (ingest.store().findMeeting(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown meeting");
        var latency = recording.sttLatencyStats(id);
        Long t0 = recording.sessionT0WallMs(id);
        return t0 == null ? Map.of("sttLatencyMs", latency) : Map.of("sttLatencyMs", latency, "t0WallMs", t0);
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        return events.subscribe();
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RecordingService.RecordingException e) {
            HttpStatus status = HttpStatus.valueOf(e.status());
            if (e.code() == null) throw new ResponseStatusException(status, e.getMessage());
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
            problem.setTitle(e.code());
            problem.setProperty("code", e.code());
            throw new ErrorResponseException(status, problem, e);
        }
    }
}
