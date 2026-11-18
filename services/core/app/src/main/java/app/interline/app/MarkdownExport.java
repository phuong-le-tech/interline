package app.interline.app;

import app.interline.persistence.AiStore.SegmentRow;
import app.interline.persistence.NoteStore.BlockRow;

import java.util.List;

/** Renders a meeting as Markdown: the notes the user wrote or vetted, then optionally the transcript. */
final class MarkdownExport {
    private static final String UNTITLED = "Untitled meeting";
    private static final long MS_PER_SECOND = 1000;
    private static final long SECONDS_PER_MINUTE = 60;

    private MarkdownExport() { }

    static String render(String title, List<BlockRow> blocks, List<SegmentRow> segments, boolean withTranscript) {
        StringBuilder out = new StringBuilder("# ").append(title == null || title.isBlank() ? UNTITLED : title).append("\n");
        BlockRow previous = null;
        for (BlockRow block : blocks) {
            if (!exported(block)) continue;
            String separator = previous == null || !(isListItem(previous) && isListItem(block)) ? "\n" : "";
            out.append(separator).append(line(block)).append("\n");
            previous = block;
        }
        if (withTranscript) {
            out.append("\n## Transcript\n\n");
            for (SegmentRow segment : segments) {
                if (segment.suppressed()) continue;
                out.append('[').append(clock(segment.startMs())).append("] ").append(segment.speaker()).append(": ")
                        .append(segment.text()).append("\n");
            }
        }
        return out.toString();
    }

    /** A proposed AI addition is a suggestion, not part of the notes yet. */
    private static boolean exported(BlockRow block) {
        boolean vetted = !"ai".equals(block.origin()) || !"proposed".equals(block.aiState());
        return vetted && !block.plainText().isBlank();
    }

    private static boolean isListItem(BlockRow block) {
        return "bullet".equals(block.kind()) || "todo".equals(block.kind());
    }

    private static String line(BlockRow block) {
        String indent = "ai".equals(block.origin()) && block.parentId() != null ? "  " : "";
        return switch (block.kind()) {
            case "heading" -> "## " + block.plainText();
            case "bullet" -> indent + "- " + block.plainText();
            case "todo" -> indent + "- [ ] " + block.plainText();
            case "quote" -> "> " + block.plainText();
            default -> indent.isEmpty() ? block.plainText() : indent + "- " + block.plainText();
        };
    }

    private static String clock(long ms) {
        long seconds = ms / MS_PER_SECOND;
        return "%02d:%02d".formatted(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }
}
