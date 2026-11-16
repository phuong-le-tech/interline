package app.interline.domain;

import java.util.List;

/** Events a capture adapter reports to the core. Callbacks run on adapter threads and must not block on the adapter. */
public interface CaptureListener {
    void agentConnected(AgentInfo agent);
    void agentLost(String reason);
    void captureUnavailable(String code, String message);
    void sessionStarted(String sessionId, long t0WallMs);
    void sessionFailed(String sessionId, String code, String message);
    void audio(List<AudioFrame> frames);
    void finalSegment(FinalSegment segment);
    void sessionStopped(String sessionId);

    /** Live meter reading, 0..1, roughly ten per second and stream. */
    void level(String sessionId, StreamKind stream, double rms);

    void partialSegment(String sessionId, PartialSegment partial);

    /** The capture device of a stream changed; {@code atSample} is on the session timeline. */
    void deviceChanged(String sessionId, StreamKind stream, String device, long atSample);

    /** The OS refuses (or silently mutes) a capture source; {@code settingsUri} opens the matching settings pane. */
    void permissionDenied(String sessionId, String permission, String detection, String settingsUri);

    /** A final segment of an offline import, positioned relative to the start of the imported file. */
    void importedSegment(String jobId, FinalSegment segment);

    void importFinished(String jobId, int segments);

    /** The agent refused or failed an import; the job stays pending for the next agent. */
    void importFailed(String jobId, String code, String message);
}
