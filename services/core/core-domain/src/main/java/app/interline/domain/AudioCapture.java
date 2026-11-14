package app.interline.domain;

/** Port: capture audio and transcribe it, wherever the OS work happens. */
public interface AudioCapture {
    void start(CaptureRequest request);

    void stop(String sessionId);

    /** Tell the agent that frames up to {@code seq} are durable. Best effort, never throws. */
    void acknowledge(StreamKind stream, long seq, int window);

    /** Stop supervising and terminate the agent for good (no restart). */
    void terminate();
}
