package app.interline.domain;

/** One 100 ms PCM chunk (16 kHz mono s16le) on the session timeline. */
public record AudioFrame(StreamKind stream, int flags, long seq, long firstSample, byte[] pcm) {
    public static final int FLAG_RECOVERED = 1;
}
