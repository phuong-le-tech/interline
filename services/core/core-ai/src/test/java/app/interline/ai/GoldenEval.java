package app.interline.ai;

import java.nio.file.Path;
import java.util.List;

/**
 * Golden evaluation (test scope, run by scripts/eval-enhance.sh). Replays each fixtures/golden/meeting-N through an
 * {@link EnhancementRunner} and reports whether the user's words survive and every addition is sourced.
 * Skeleton: specified by GoldenEvalTest.
 */
final class GoldenEval {
    private GoldenEval() { }

    /**
     * @param preservedPercent  share of user blocks byte-identical after applying the run, 0 to 100
     * @param sourcedPercent    share of additions and action items citing a valid segment, 0 to 100
     * @param budgetViolations  additions breaking the US-3.9 budget in the final output
     * @param maxLatencyMs      slowest run over the meetings
     * @param actionItems       every action item text, for manual review of relevance
     */
    record GoldenReport(int meetings, double preservedPercent, double sourcedPercent, int budgetViolations,
                        long maxLatencyMs, List<String> actionItems) { }

    static GoldenReport run(LlmPort llm, Path goldenDirectory) {
        throw new UnsupportedOperationException("M2 skeleton");
    }
}
