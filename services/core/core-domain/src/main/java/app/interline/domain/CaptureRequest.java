package app.interline.domain;

/** Start (or resume) capture for a meeting. {@code resume} is null on a fresh start. */
public record CaptureRequest(String sessionId, String spoolDir, ResumeState resume) { }
