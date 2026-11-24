package app.interline.ai;

import app.interline.domain.BriefOutput;
import app.interline.domain.InvalidEnhancementException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Only the explicit brief use case invokes this runner. */
public final class BriefRunner {
    public static final String PROMPT_VERSION = "brief/v1";
    private final LlmPort llm;
    private final String system = PromptTemplate.load("prompts/brief/v1/system.md").render(Map.of());
    private final PromptTemplate user = PromptTemplate.load("prompts/brief/v1/user.md");
    private final PromptTemplate retry = PromptTemplate.load("prompts/brief/v1/retry.md");

    public BriefRunner(LlmPort llm) { this.llm = llm; }

    public BriefResult run(BriefInput input) {
        StringBuilder context = new StringBuilder();
        input.related().forEach(m -> {
            context.append("Meeting ").append(m.meetingId()).append(" | ").append(m.title())
                    .append(" | ").append(java.time.Instant.ofEpochMilli(m.date())).append('\n');
            m.notes().forEach(n -> context.append(n).append('\n'));
        });
        input.openActions().forEach(a -> context.append("Open action from ").append(a.meetingId()).append(": ")
                .append(a.text()).append(" | ").append(a.ownerLabel() == null ? "" : a.ownerLabel())
                .append(" | ").append(a.dueDate() == null ? "" : a.dueDate()).append('\n'));
        String prompt = user.render(Map.of("title", input.title(), "participants", String.join(", ", input.participants()),
                "context", context.toString()));
        Set<String> ids = input.related().stream().map(BriefInput.RelatedMeeting::meetingId).collect(Collectors.toSet());
        int in = 0;
        int out = 0;
        String problems = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmReply reply = llm.complete(new LlmRequest(system, prompt + problems));
            in += reply.inputTokens();
            out += reply.outputTokens();
            try {
                return new BriefResult(BriefResult.Status.OK, BriefOutput.parse(reply.text(), ids).lines(), null, in, out);
            } catch (InvalidEnhancementException e) {
                problems = "\n\n" + retry.render(Map.of("problems", e.getMessage()));
            }
        }
        return new BriefResult(BriefResult.Status.INVALID_OUTPUT, List.of(), EnhancementRunner.FORMAT_MESSAGE, in, out);
    }
}
