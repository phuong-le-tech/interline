package app.interline.ai;

import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.ai.EnhanceInput.UserBlock;

import java.util.List;
import java.util.stream.Collectors;

/** How notes and transcript lines are written into a prompt. The model cites segments by the id in `[S12 ...]`. */
final class PromptText {
    private static final long MS_PER_SECOND = 1000;
    private static final long SECONDS_PER_MINUTE = 60;

    private PromptText() { }

    static String clock(long ms) {
        long seconds = Math.max(0, ms) / MS_PER_SECOND;
        return "%02d:%02d".formatted(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }

    static String segment(SegmentLine line) {
        return "[S%d %s %s] %s".formatted(line.id(), clock(line.startMs()), line.speaker(), oneLine(line.text()));
    }

    static String block(UserBlock block) {
        return "%s | %s | %s | %s".formatted(block.id(), block.kind(), clock(block.anchorMs()), oneLine(block.text()));
    }

    static String segments(List<SegmentLine> lines) {
        return lines.stream().map(PromptText::segment).collect(Collectors.joining("\n"));
    }

    static String blocks(List<UserBlock> blocks) {
        return blocks.stream().map(PromptText::block).collect(Collectors.joining("\n"));
    }

    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
