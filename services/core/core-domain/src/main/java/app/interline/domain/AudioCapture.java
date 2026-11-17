package app.interline.domain;

/** Port: capture audio and transcribe it, wherever the OS work happens. */
public interface AudioCapture {
    void start(CaptureRequest request);

    void stop(String sessionId);

    /** Tell the agent that frames up to {@code seq} are durable. Returns false when it could not be delivered. */
    boolean acknowledge(StreamKind stream, long seq, int window);

    /** Ask the agent to transcribe an audio file offline; results arrive through the capture listener. */
    void transcribeFile(String jobId, String path, StreamKind stream, String language);

    /** Stop supervising and terminate the agent for good (no restart). */
    void terminate();
}
