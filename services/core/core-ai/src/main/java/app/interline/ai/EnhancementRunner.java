package app.interline.ai;

/** Prompts, one retry, windowing. Skeleton: specified by EnhancementRunnerTest. */
public final class EnhancementRunner {
    public static final String PROMPT_VERSION = "enhance/v1";
    public static final String FORMAT_MESSAGE = "The model did not respect the format";

    public EnhancementRunner(LlmPort llm) { }

    public EnhanceResult run(EnhanceInput input) {
        throw new UnsupportedOperationException("M2 skeleton");
    }
}
