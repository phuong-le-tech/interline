package app.interline.agent;

/** Unsigned u32 seq is represented as a long. PCM bytes are signed s16le. */
public record AudioChunk(int stream, int flags, long seq, long firstSample,
                         long hostTimeNs, byte[] pcm) implements Inbound { }
