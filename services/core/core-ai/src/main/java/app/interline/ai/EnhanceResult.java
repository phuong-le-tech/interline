package app.interline.ai;

import app.interline.domain.EnhancementOutput;
import app.interline.domain.Violation;

import java.util.List;

/** {@code output} is null for INVALID_OUTPUT. {@code dropped} lists the violations still present after the retry. */
public record EnhanceResult(Status status, EnhancementOutput output, List<Violation> dropped, int inputTokens,
                            int outputTokens, int calls, String promptVersion, String message) {
    public enum Status { OK, INVALID_OUTPUT }
}
