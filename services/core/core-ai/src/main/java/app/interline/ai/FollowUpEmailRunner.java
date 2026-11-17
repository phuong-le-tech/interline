package app.interline.ai;

import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.domain.FollowUpDraft;
import app.interline.domain.InvalidEnhancementException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Drafts a follow-up email. Nothing here or anywhere in the core sends it. */
public final class FollowUpEmailRunner {
    public static final String PROMPT_VERSION = "followup_email/v1";
    private static final String RESOURCE_ROOT = "prompts/followup_email/v1/";

    private final LlmPort llm;
    private final String system = PromptTemplate.load(RESOURCE_ROOT + "system.md").render(Map.of());
    private final PromptTemplate user = PromptTemplate.load(RESOURCE_ROOT + "user.md");
    private final PromptTemplate retry = PromptTemplate.load(RESOURCE_ROOT + "retry.md");

    public FollowUpEmailRunner(LlmPort llm) { this.llm = llm; }

    public FollowUpResult run(FollowUpInput input) {
        List<SegmentLine> citable = input.segments().stream().filter(s -> !s.suppressed()).toList();
        String prompt = user.render(Map.of("title", input.title(), "language", input.language(),
                "blocks", PromptText.blocks(input.blocks()), "transcript", PromptText.segments(citable)));
        Set<Long> citableIds = citable.stream().map(SegmentLine::id).collect(Collectors.toSet());

        LlmReply first = llm.complete(new LlmRequest(system, prompt));
        Optional<FollowUpDraft> draft = parse(first.text());
        if (draft.isPresent()) return accepted(draft.get(), citableIds, first.inputTokens(), first.outputTokens());

        String problem = "- The reply is not the required JSON document.";
        LlmReply second = llm.complete(new LlmRequest(system, prompt + "\n\n" + retry.render(Map.of("problems", problem))));
        int inputTokens = first.inputTokens() + second.inputTokens();
        int outputTokens = first.outputTokens() + second.outputTokens();
        return parse(second.text())
                .map(d -> accepted(d, citableIds, inputTokens, outputTokens))
                .orElseGet(() -> new FollowUpResult(FollowUpResult.Status.INVALID_OUTPUT, null, null, List.of(),
                        EnhancementRunner.FORMAT_MESSAGE, inputTokens, outputTokens));
    }

    private static Optional<FollowUpDraft> parse(String text) {
        try {
            return Optional.of(FollowUpDraft.parse(text));
        } catch (InvalidEnhancementException e) {
            return Optional.empty();
        }
    }

    private static FollowUpResult accepted(FollowUpDraft draft, Set<Long> citableIds, int inputTokens, int outputTokens) {
        List<Long> sources = draft.sources().stream().filter(citableIds::contains).distinct().toList();
        return new FollowUpResult(FollowUpResult.Status.OK, draft.subject(), draft.body(), sources, null,
                inputTokens, outputTokens);
    }
}
