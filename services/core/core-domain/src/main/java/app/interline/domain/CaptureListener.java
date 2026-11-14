package app.interline.domain;

import java.util.List;

/** Events a capture adapter reports to the core. Callbacks run on adapter threads and must not block on the adapter. */
public interface CaptureListener {
    void agentConnected(AgentInfo agent);
    void agentLost(String reason);
    void captureUnavailable(String code, String message);
    void sessionStarted(String sessionId);
    void sessionFailed(String sessionId, String code, String message);
    void audio(List<AudioFrame> frames);
    void finalSegment(FinalSegment segment);
    void sessionStopped(String sessionId);
}
