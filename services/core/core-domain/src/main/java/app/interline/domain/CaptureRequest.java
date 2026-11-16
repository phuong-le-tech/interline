package app.interline.domain;

/**
 * Start (or resume) capture for a meeting. {@code resume} is null on a fresh start; the model paths are null when
 * the agent should use the ones it was launched with.
 */
public record CaptureRequest(String sessionId, String spoolDir, ResumeState resume, String modelPath, String vadModelPath) {
    public CaptureRequest(String sessionId, String spoolDir, ResumeState resume) {
        this(sessionId, spoolDir, resume, null, null);
    }
}
