package app.interline.ai;

import app.interline.domain.BriefOutput.Line;
import java.util.List;

public record BriefResult(Status status, List<Line> lines, String message, int inputTokens, int outputTokens) {
    public enum Status { OK, INVALID_OUTPUT }
}
