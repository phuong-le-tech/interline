package app.interline.domain;

/** Full transcript when it fits in 75 % of the context (chars / 4 estimates tokens), windowed otherwise. */
public final class ModeSelector {
    private static final long CHARS_PER_TOKEN = 4;
    private static final long FULL_PERCENT = 75;
    private static final long PERCENT = 100;

    private ModeSelector() { }

    /** @param contextTokens the provider's context size, or null when unknown (always FULL) */
    public static EnhanceMode select(long transcriptChars, Integer contextTokens) {
        if (contextTokens == null) return EnhanceMode.FULL;
        // transcriptChars / 4 <= 75 % of the context, kept in integers
        boolean fits = transcriptChars * PERCENT <= contextTokens * CHARS_PER_TOKEN * FULL_PERCENT;
        return fits ? EnhanceMode.FULL : EnhanceMode.WINDOWED;
    }
}
