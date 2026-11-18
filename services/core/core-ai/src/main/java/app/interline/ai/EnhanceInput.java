package app.interline.ai;

import java.util.List;

/** Everything one enhancement run needs. Suppressed segments must never reach a prompt. */
public record EnhanceInput(String meetingId, String title, String language, String templateInstructions,
                           List<UserBlock> blocks, List<SegmentLine> segments, Integer contextTokens) {
    public record UserBlock(String id, String kind, String text, long anchorMs) { }

    public record SegmentLine(long id, long startMs, String speaker, String text, boolean suppressed) { }
}
