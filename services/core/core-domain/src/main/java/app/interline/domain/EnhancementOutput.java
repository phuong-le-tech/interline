package app.interline.domain;

import java.util.List;

/** The strict JSON an enhancement run must produce (docs/ARCHITECTURE.md section 10). Sources are transcript segment ids. */
public record EnhancementOutput(List<BlockAdditions> blocks, List<OtherPoint> otherPoints,
                                List<ActionItem> actionItems, String titleSuggestion) {
    public record Addition(String text, List<Long> sources) { }

    public record BlockAdditions(String userBlockId, List<Addition> additions) { }

    public record OtherPoint(String text, List<Long> sources) { }

    public record ActionItem(String text, String owner, String due, List<Long> sources) { }
}
