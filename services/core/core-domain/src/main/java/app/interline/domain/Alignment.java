package app.interline.domain;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Aligns user blocks with the transcript by their anchors. */
public final class Alignment {
    public static final long LOOK_BACK_MS = 45_000;

    private Alignment() { }

    public record BlockAnchor(String id, long anchorMs) { }

    /** {@code toMs} is exclusive; the last block's window is open ended (Long.MAX_VALUE). */
    public record Window(String blockId, long fromMs, long toMs) {
        public boolean contains(long startMs) {
            return startMs >= fromMs && startMs < toMs;
        }
    }

    /** One window per block, ordered by anchor: {@code [anchor - 45 s, next anchor)}, never starting before 0. */
    public static List<Window> windows(List<BlockAnchor> blocks) {
        List<BlockAnchor> ordered = blocks.stream().sorted(Comparator.comparingLong(BlockAnchor::anchorMs)).toList();
        return IntStream.range(0, ordered.size()).mapToObj(i -> {
            BlockAnchor block = ordered.get(i);
            long to = i + 1 < ordered.size() ? ordered.get(i + 1).anchorMs() : Long.MAX_VALUE;
            return new Window(block.id(), Math.max(0, block.anchorMs() - LOOK_BACK_MS), to);
        }).toList();
    }
}
