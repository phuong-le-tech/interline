package app.interline.domain;

import java.util.List;

/** Aligns user blocks with the transcript by their anchors. Skeleton: specified by AlignmentTest. */
public final class Alignment {
    public static final long LOOK_BACK_MS = 45_000;

    private Alignment() { }

    public record BlockAnchor(String id, long anchorMs) { }

    /** {@code toMs} is exclusive; the last block's window is open ended (Long.MAX_VALUE). */
    public record Window(String blockId, long fromMs, long toMs) {
        public boolean contains(long startMs) {
            throw new UnsupportedOperationException("M2 skeleton");
        }
    }

    /** One window per block, ordered by anchor: {@code [anchor - 45 s, next anchor)}, never starting before 0. */
    public static List<Window> windows(List<BlockAnchor> blocks) {
        throw new UnsupportedOperationException("M2 skeleton");
    }
}
