package app.interline.app;

import app.interline.ai.EnhanceInput;
import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.ai.EnhanceInput.UserBlock;
import app.interline.ai.EnhanceResult;
import app.interline.ai.EnhancementRunner;
import app.interline.ai.FollowUpEmailRunner;
import app.interline.ai.FollowUpInput;
import app.interline.ai.FollowUpResult;
import app.interline.ai.LlmPort;
import app.interline.domain.EnhancementOutput;
import app.interline.persistence.AiStore;
import app.interline.persistence.AiStore.ActionWrite;
import app.interline.persistence.AiStore.AdditionWrite;
import app.interline.persistence.AiStore.RunEnd;
import app.interline.persistence.AiStore.RunStart;
import app.interline.persistence.AiStore.SegmentRow;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.NoteStore;
import app.interline.persistence.NoteStore.BlockRow;
import app.interline.persistence.ProviderStore.ProviderRow;
import app.interline.persistence.TemplateStore;
import app.interline.persistence.TemplateStore.TemplateRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The enhancement use case: snapshot the user's notes, ask the model, store only validated additions as AI blocks.
 * It reads user blocks and never writes them. A failure leaves the notes exactly as they were.
 */
final class EnhancementService {
    private static final System.Logger LOG = System.getLogger(EnhancementService.class.getName());
    private static final String NO_PROVIDER_DETAIL = "Configure a provider in Settings before enhancing your notes.";
    private static final String SNAPSHOT_RAW_USER = "raw_user";
    private static final String KIND_ENHANCE = "enhance";
    private static final String KIND_FOLLOWUP = "followup_email";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_INVALID = "invalid_output";
    private static final String STATUS_ERROR = "error";
    private static final String ERROR_PROVIDER_CALL = "provider_call_failed";
    private static final String DEFAULT_LANGUAGE = "the language of the notes";
    private static final String NO_TEMPLATE = "";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Result of an enhancement request, as the API reports it. */
    record Outcome(String runId, String status, String message) { }

    /** Result of a follow-up email request. */
    record Draft(String runId, String status, String subject, String body, List<Long> sources, String message) { }

    private final NoteStore notes;
    private final AiStore ai;
    private final TemplateStore templates;
    private final CaptureStore meetings;
    private final ProviderService providers;
    private final EventBus events;
    private final Clock clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    EnhancementService(NoteStore notes, AiStore ai, TemplateStore templates, CaptureStore meetings,
                       ProviderService providers, EventBus events, Clock clock) {
        this.notes = notes;
        this.ai = ai;
        this.templates = templates;
        this.meetings = meetings;
        this.providers = providers;
        this.events = events;
        this.clock = clock;
    }

    Outcome enhance(String meetingId, String templateId) {
        requireMeeting(meetingId);
        ProviderRow provider = providers.defaultProvider()
                .orElseThrow(() -> ApiProblems.of(HttpStatus.CONFLICT, "no_provider", NO_PROVIDER_DETAIL));
        String instructions = chooseTemplate(meetingId, templateId);
        try (Claim ignored = claim(meetingId)) {
            List<BlockRow> userBlocks = notes.userBlocks(meetingId);
            ai.writeSnapshot(meetingId, SNAPSHOT_RAW_USER, snapshotOf(userBlocks), clock.millis());
            String runId = ai.startRun(new RunStart(meetingId, KIND_ENHANCE, provider.id(), provider.model(),
                    EnhancementRunner.PROMPT_VERSION), clock.millis());
            progress(meetingId, runId, "started");
            List<SegmentRow> segments = ai.segments(meetingId);
            EnhanceInput input = new EnhanceInput(meetingId, ai.meetingTitle(meetingId).orElse(""),
                    ai.meetingLanguage(meetingId).orElse(DEFAULT_LANGUAGE), instructions,
                    promptBlocks(userBlocks), segmentLines(segments), provider.contextTokens());
            EnhanceResult result = runModel(runId, provider, () -> new EnhancementRunner(providers.llm(provider)).run(input));
            if (result.status() == EnhanceResult.Status.INVALID_OUTPUT) {
                ai.finishRun(runId, new RunEnd(STATUS_INVALID, result.inputTokens(), result.outputTokens(), null,
                        ProviderService.cost(provider, result.inputTokens(), result.outputTokens())), clock.millis());
                progress(meetingId, runId, STATUS_INVALID);
                return new Outcome(runId, STATUS_INVALID, result.message());
            }
            store(meetingId, runId, result.output(), segments);
            ai.finishRun(runId, new RunEnd(STATUS_OK, result.inputTokens(), result.outputTokens(), null,
                    ProviderService.cost(provider, result.inputTokens(), result.outputTokens())), clock.millis());
            progress(meetingId, runId, STATUS_OK);
            return new Outcome(runId, STATUS_OK, null);
        }
    }

    /** Deletes every AI block and AI action item of the meeting. The user's blocks are not touched. */
    void undo(String meetingId) {
        requireMeeting(meetingId);
        ai.undoEnhancement(meetingId);
    }

    Draft followUpEmail(String meetingId) {
        requireMeeting(meetingId);
        ProviderRow provider = providers.defaultProvider()
                .orElseThrow(() -> ApiProblems.of(HttpStatus.CONFLICT, "no_provider", NO_PROVIDER_DETAIL));
        List<BlockRow> vetted = notes.blocks(meetingId).stream()
                .filter(b -> !"ai".equals(b.origin()) || !"proposed".equals(b.aiState())).toList();
        FollowUpInput input = new FollowUpInput(meetingId, ai.meetingTitle(meetingId).orElse(""),
                ai.meetingLanguage(meetingId).orElse(DEFAULT_LANGUAGE), promptBlocks(vetted), segmentLines(ai.segments(meetingId)));
        String runId = ai.startRun(new RunStart(meetingId, KIND_FOLLOWUP, provider.id(), provider.model(),
                FollowUpEmailRunner.PROMPT_VERSION), clock.millis());
        FollowUpResult result = runModel(runId, provider, () -> new FollowUpEmailRunner(providers.llm(provider)).run(input));
        String status = result.status() == FollowUpResult.Status.OK ? STATUS_OK : STATUS_INVALID;
        ai.finishRun(runId, new RunEnd(status, result.inputTokens(), result.outputTokens(), null,
                ProviderService.cost(provider, result.inputTokens(), result.outputTokens())), clock.millis());
        return new Draft(runId, status, result.subject(), result.body(), result.sources(), result.message());
    }

    private <T> T runModel(String runId, ProviderRow provider, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            // The exception text may quote the provider's answer, which can echo credentials: only its type is kept.
            LOG.log(System.Logger.Level.WARNING, "model call failed ({0}) for provider {1}", e.getClass().getSimpleName(), provider.id());
            ai.finishRun(runId, new RunEnd(STATUS_ERROR, null, null, ERROR_PROVIDER_CALL, null), clock.millis());
            if (e instanceof org.springframework.web.ErrorResponseException problem) throw problem;
            throw ApiProblems.of(HttpStatus.BAD_GATEWAY, "provider_error", "The provider call failed. Check the provider settings.");
        }
    }

    private void store(String meetingId, String runId, EnhancementOutput output, List<SegmentRow> segments) {
        Set<Long> citable = segments.stream().filter(s -> !s.suppressed()).map(SegmentRow::id).collect(Collectors.toSet());
        List<AdditionWrite> additions = new ArrayList<>();
        for (EnhancementOutput.BlockAdditions block : output.blocks()) {
            for (EnhancementOutput.Addition addition : block.additions()) {
                additions.add(new AdditionWrite(block.userBlockId(), addition.text(), cited(addition.sources(), citable)));
            }
        }
        output.otherPoints().forEach(p -> additions.add(new AdditionWrite(null, p.text(), cited(p.sources(), citable))));
        List<ActionWrite> actions = output.actionItems().stream().map(a -> new ActionWrite(a.text(), a.owner(), a.due(),
                cited(a.sources(), citable).get(0))).toList();
        ai.applyEnhancement(meetingId, runId, additions, actions, clock.millis());
    }

    private static List<Long> cited(List<Long> sources, Set<Long> citable) {
        return sources.stream().filter(citable::contains).distinct().toList();
    }

    private String chooseTemplate(String meetingId, String templateId) {
        if (templateId == null) return ai.meetingTemplate(meetingId).flatMap(templates::find)
                .map(TemplateRow::instructions).orElse(NO_TEMPLATE);
        TemplateRow template = templates.find(templateId)
                .orElseThrow(() -> ApiProblems.badRequest("Unknown template " + templateId));
        ai.setMeetingTemplate(meetingId, template.id());
        return template.instructions();
    }

    private void requireMeeting(String meetingId) {
        if (meetings.findMeeting(meetingId).isEmpty()) throw ApiProblems.notFound("meeting");
    }

    private Claim claim(String meetingId) {
        if (!running.add(meetingId)) {
            throw ApiProblems.of(HttpStatus.CONFLICT, "enhance_running", "An enhancement is already running for this meeting.");
        }
        return () -> running.remove(meetingId);
    }

    /** Releases the per-meeting claim without a checked exception. */
    private interface Claim extends AutoCloseable {
        @Override void close();
    }

    private void progress(String meetingId, String runId, String stage) {
        events.publish("enhance.progress", Map.of("meetingId", meetingId, "runId", runId, "stage", stage));
    }

    private static List<UserBlock> promptBlocks(List<BlockRow> blocks) {
        return blocks.stream().filter(b -> !b.plainText().isBlank())
                .map(b -> new UserBlock(b.id(), b.kind(), b.plainText(), b.anchorMs() == null ? 0 : b.anchorMs())).toList();
    }

    private static List<SegmentLine> segmentLines(List<SegmentRow> segments) {
        return segments.stream().map(s -> new SegmentLine(s.id(), s.startMs(), s.speaker(), s.text(), s.suppressed())).toList();
    }

    private static String snapshotOf(List<BlockRow> blocks) {
        ObjectNode doc = JSON.createObjectNode();
        ArrayNode array = doc.putArray("blocks");
        for (BlockRow block : blocks) {
            ObjectNode node = array.addObject().put("id", block.id()).put("kind", block.kind()).put("position", block.position())
                    .put("content", block.content()).put("plain_text", block.plainText());
            if (block.anchorMs() != null) node.put("anchor_ms", block.anchorMs());
        }
        try {
            return JSON.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the notes snapshot", e);
        }
    }
}
