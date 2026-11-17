package app.interline.ai;

import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.ai.EnhanceInput.UserBlock;
import app.interline.domain.Alignment;
import app.interline.domain.EnhanceMode;
import app.interline.domain.EnhancementOutput;
import app.interline.domain.EnhancementParser;
import app.interline.domain.EnhancementValidator;
import app.interline.domain.InvalidEnhancementException;
import app.interline.domain.ModeSelector;
import app.interline.domain.SegmentRef;
import app.interline.domain.ValidationContext;
import app.interline.domain.ValidationResult;
import app.interline.domain.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One enhancement run: builds the prompts, calls the model, validates the reply with the domain contract and
 * retries exactly once with the problems listed. In windowed mode each note gets its own call, plus one call for
 * the points that no note covers. The user's notes are only ever read here.
 */
public final class EnhancementRunner {
    public static final String PROMPT_VERSION = "enhance/v1";
    public static final String FORMAT_MESSAGE = "The model did not respect the format";
    private static final String RESOURCE_ROOT = "prompts/enhance/v1/";
    private static final String TASK_FULL = "Complete every note below.";
    private static final String TASK_BLOCK = "This is one part of a longer meeting. Complete only the note below. "
            + "Leave other_points empty.";
    private static final String TASK_OTHER = "This is the part of a longer meeting that the notes below do not cover. "
            + "Give only other_points and action_items. Leave blocks empty.";

    private final LlmPort llm;
    private final String system = PromptTemplate.load(RESOURCE_ROOT + "system.md").render(Map.of());
    private final PromptTemplate user = PromptTemplate.load(RESOURCE_ROOT + "user.md");
    private final PromptTemplate retry = PromptTemplate.load(RESOURCE_ROOT + "retry.md");

    public EnhancementRunner(LlmPort llm) { this.llm = llm; }

    /** One model conversation: the notes it may annotate and the transcript lines it may cite. */
    private record Task(String instruction, List<UserBlock> blocks, List<SegmentLine> segments) { }

    /** Result of one task; {@code output} is null when the reply stayed unusable after the retry. */
    private record TaskResult(EnhancementOutput output, List<Violation> dropped) { }

    private static final class Meter {
        int inputTokens;
        int outputTokens;
        int calls;
    }

    public EnhanceResult run(EnhanceInput input) {
        List<SegmentLine> citable = input.segments().stream().filter(s -> !s.suppressed()).toList();
        Meter meter = new Meter();
        List<TaskResult> results = new ArrayList<>();
        for (Task task : plan(input, citable)) {
            TaskResult result = runTask(input, task, meter);
            if (result.output() == null) {
                return new EnhanceResult(EnhanceResult.Status.INVALID_OUTPUT, null, List.of(), meter.inputTokens,
                        meter.outputTokens, meter.calls, PROMPT_VERSION, FORMAT_MESSAGE);
            }
            results.add(result);
        }
        return new EnhanceResult(EnhanceResult.Status.OK, merge(results), results.stream()
                .flatMap(r -> r.dropped().stream()).toList(), meter.inputTokens, meter.outputTokens, meter.calls,
                PROMPT_VERSION, null);
    }

    private List<Task> plan(EnhanceInput input, List<SegmentLine> citable) {
        long chars = citable.stream().mapToLong(s -> s.text().length()).sum()
                + input.blocks().stream().mapToLong(b -> b.text().length()).sum();
        if (ModeSelector.select(chars, input.contextTokens()) == EnhanceMode.FULL || input.blocks().isEmpty()) {
            return List.of(new Task(TASK_FULL, input.blocks(), citable));
        }
        List<Alignment.Window> windows = Alignment.windows(input.blocks().stream()
                .map(b -> new Alignment.BlockAnchor(b.id(), b.anchorMs())).toList());
        Map<String, UserBlock> byId = new LinkedHashMap<>();
        input.blocks().forEach(b -> byId.put(b.id(), b));
        List<Task> tasks = new ArrayList<>();
        for (Alignment.Window window : windows) {
            List<SegmentLine> inside = citable.stream().filter(s -> window.contains(s.startMs())).toList();
            tasks.add(new Task(TASK_BLOCK, List.of(byId.get(window.blockId())), inside));
        }
        List<SegmentLine> uncovered = citable.stream()
                .filter(s -> windows.stream().noneMatch(w -> w.contains(s.startMs()))).toList();
        if (!uncovered.isEmpty()) tasks.add(new Task(TASK_OTHER, input.blocks(), uncovered));
        return tasks;
    }

    private TaskResult runTask(EnhanceInput input, Task task, Meter meter) {
        EnhancementValidator validator = new EnhancementValidator(contextFor(input, task));
        String prompt = user.render(Map.of("title", input.title(), "language", input.language(),
                "template", input.templateInstructions(), "task", task.instruction(),
                "blocks", PromptText.blocks(task.blocks()), "transcript", PromptText.segments(task.segments())));

        Attempt first = attempt(prompt, validator, meter);
        if (first.problems().isEmpty()) return new TaskResult(first.result().valid(), List.of());

        String retryPrompt = prompt + "\n\n" + retry.render(Map.of("problems", String.join("\n", first.problems())));
        Attempt second = attempt(retryPrompt, validator, meter);
        if (second.result() == null) return new TaskResult(null, List.of());
        return new TaskResult(second.result().valid(), second.result().violations());
    }

    /** {@code result} is null when the reply was not the JSON document; {@code problems} lists what to fix. */
    private record Attempt(ValidationResult result, List<String> problems) { }

    private Attempt attempt(String prompt, EnhancementValidator validator, Meter meter) {
        LlmReply reply = llm.complete(new LlmRequest(system, prompt));
        meter.calls++;
        meter.inputTokens += reply.inputTokens();
        meter.outputTokens += reply.outputTokens();
        EnhancementOutput output;
        try {
            output = EnhancementParser.parse(reply.text());
        } catch (InvalidEnhancementException e) {
            return new Attempt(null, List.of("- The reply is not the required JSON document (" + e.getMessage() + ")."));
        }
        ValidationResult result = validator.validate(output);
        return new Attempt(result, result.violations().stream().map(v -> "- " + v.detail()).toList());
    }

    private static ValidationContext contextFor(EnhanceInput input, Task task) {
        Map<String, String> blockTexts = new LinkedHashMap<>();
        task.blocks().forEach(b -> blockTexts.put(b.id(), b.text()));
        List<SegmentRef> refs = task.segments().stream()
                .map(s -> new SegmentRef(s.id(), input.meetingId(), s.suppressed())).toList();
        return new ValidationContext(input.meetingId(), blockTexts, refs);
    }

    private static EnhancementOutput merge(List<TaskResult> results) {
        if (results.size() == 1) return results.get(0).output();
        List<EnhancementOutput.BlockAdditions> blocks = new ArrayList<>();
        List<EnhancementOutput.OtherPoint> points = new ArrayList<>();
        Map<String, EnhancementOutput.ActionItem> actions = new LinkedHashMap<>();
        String title = null;
        for (TaskResult result : results) {
            EnhancementOutput output = result.output();
            blocks.addAll(output.blocks());
            points.addAll(output.otherPoints());
            output.actionItems().forEach(a -> actions.putIfAbsent(a.text().strip().toLowerCase(), a));
            if (title == null) title = output.titleSuggestion();
        }
        return new EnhancementOutput(blocks, points, List.copyOf(actions.values()), title);
    }
}
