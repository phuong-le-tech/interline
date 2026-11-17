package app.interline.domain;

/** Full transcript when it fits in 75 % of the context (chars / 4 estimates tokens), windowed otherwise. */
public final class ModeSelector {
    private ModeSelector() { }

    /** @param contextTokens the provider's context size, or null when unknown (always FULL) */
    public static EnhanceMode select(long transcriptChars, Integer contextTokens) {
        throw new UnsupportedOperationException("M2 skeleton");
    }
}
