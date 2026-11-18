package app.interline.ai;

import app.interline.domain.EnhancementOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Golden evaluation (test scope, run by scripts/eval-enhance.sh). Replays each fixtures/golden/meeting-N through an
 * {@link EnhancementRunner} and reports whether the user's words survive and every addition is sourced.
 */
final class GoldenEval {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        int totalBlocks = 0;
        int preservedBlocks = 0;
        int totalCitable = 0;
        int sourcedCitable = 0;
        int budgetViolations = 0;
        long maxLatencyMs = 0;
        List<String> actionItems = new ArrayList<>();

        EnhancementRunner runner = new EnhancementRunner(llm);

        for (int n = 1; n <= 5; n++) {
            Path dir = goldenDirectory.resolve("meeting-" + n);
            Meeting meeting = load(dir.resolve("meeting.json"));
            EnhanceInput input = new EnhanceInput(
                    "meeting-" + n,
                    meeting.title,
                    meeting.language,
                    "",
                    meeting.blocks,
                    meeting.segments,
                    null);

            long start = System.currentTimeMillis();
            EnhanceResult result = runner.run(input);
            long latency = System.currentTimeMillis() - start;
            maxLatencyMs = Math.max(maxLatencyMs, latency);

            EnhancementOutput output = result.output();
            if (output == null) {
                throw new AssertionError("meeting-" + n + " produced no enhancement output: " + result.message());
            }

            Set<String> outputBlockIds = new HashSet<>();
            for (EnhancementOutput.BlockAdditions ba : output.blocks()) {
                outputBlockIds.add(ba.userBlockId());
            }
            totalBlocks += meeting.blocks.size();
            for (EnhanceInput.UserBlock b : meeting.blocks) {
                if (outputBlockIds.contains(b.id())) {
                    preservedBlocks++;
                }
            }

            Set<Long> segmentIds = new HashSet<>();
            for (EnhanceInput.SegmentLine s : meeting.segments) {
                segmentIds.add(s.id());
            }

            Map<String, Integer> blockLengthById = new HashMap<>();
            for (EnhanceInput.UserBlock b : meeting.blocks) {
                blockLengthById.put(b.id(), b.text().length());
            }

            for (EnhancementOutput.BlockAdditions ba : output.blocks()) {
                int fragmentLen = blockLengthById.getOrDefault(ba.userBlockId(), 0);
                for (EnhancementOutput.Addition a : ba.additions()) {
                    totalCitable++;
                    if (sourced(a.sources(), segmentIds)) {
                        sourcedCitable++;
                    }
                    if (overBudget(a.text(), fragmentLen)) {
                        budgetViolations++;
                    }
                }
            }
            for (EnhancementOutput.OtherPoint p : output.otherPoints()) {
                totalCitable++;
                if (sourced(p.sources(), segmentIds)) {
                    sourcedCitable++;
                }
                if (overBudget(p.text(), 0)) {
                    budgetViolations++;
                }
            }
            for (EnhancementOutput.ActionItem a : output.actionItems()) {
                totalCitable++;
                if (sourced(a.sources(), segmentIds)) {
                    sourcedCitable++;
                }
                actionItems.add(a.text());
            }
        }

        double preservedPercent = totalBlocks == 0 ? 0.0 : (preservedBlocks * 100.0) / totalBlocks;
        double sourcedPercent = totalCitable == 0 ? 0.0 : (sourcedCitable * 100.0) / totalCitable;
        return new GoldenReport(5, preservedPercent, sourcedPercent, budgetViolations, maxLatencyMs, actionItems);
    }

    private static boolean sourced(List<Long> sources, Set<Long> valid) {
        return !sources.isEmpty() && sources.stream().allMatch(valid::contains);
    }

    private static boolean overBudget(String text, int fragmentLength) {
        if (text == null || text.isBlank()) return false;
        String stripped = text.strip();
        int sentences = countSentences(stripped);
        return stripped.length() > fragmentLength + 80 || sentences > 3;
    }

    private static int countSentences(String text) {
        int count = 0;
        boolean inSentence = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                inSentence = true;
            }
            if (c == '.' || c == '!' || c == '?') {
                if (inSentence) count++;
                inSentence = false;
            }
        }
        if (inSentence && count == 0) count = 1;
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Meeting load(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> root = MAPPER.readValue(json, Map.class);
            String title = (String) root.get("title");
            String language = (String) root.get("language");

            List<EnhanceInput.SegmentLine> segments = new ArrayList<>();
            List<Map<String, Object>> segArray = (List<Map<String, Object>>) root.get("segments");
            for (Map<String, Object> s : segArray) {
                segments.add(new EnhanceInput.SegmentLine(
                        ((Number) s.get("id")).longValue(),
                        ((Number) s.get("start_ms")).longValue(),
                        (String) s.get("speaker"),
                        (String) s.get("text"),
                        Boolean.TRUE.equals(s.get("suppressed"))));
            }

            List<EnhanceInput.UserBlock> blocks = new ArrayList<>();
            List<Map<String, Object>> noteArray = (List<Map<String, Object>>) root.get("notes");
            for (Map<String, Object> b : noteArray) {
                blocks.add(new EnhanceInput.UserBlock(
                        (String) b.get("id"),
                        (String) b.get("kind"),
                        (String) b.get("text"),
                        ((Number) b.get("anchor_ms")).longValue()));
            }
            return new Meeting(title, language, segments, blocks);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private record Meeting(String title, String language, List<EnhanceInput.SegmentLine> segments,
                           List<EnhanceInput.UserBlock> blocks) { }
}
