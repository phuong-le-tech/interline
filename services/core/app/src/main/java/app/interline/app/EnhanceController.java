package app.interline.app;

import app.interline.persistence.AiStore;
import app.interline.persistence.AiStore.ActionRow;
import app.interline.persistence.AiStore.RunRow;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.TemplateStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AI enhancement of a meeting's notes, its run history and action items, the follow-up email draft and templates. */
@RestController
@RequestMapping("/api/v1")
class EnhanceController {
    private final EnhancementService enhancement;
    private final AiStore ai;
    private final CaptureStore meetings;
    private final TemplateStore templates;

    EnhanceController(EnhancementService enhancement, AiStore ai, CaptureStore meetings, TemplateStore templates) {
        this.enhancement = enhancement;
        this.ai = ai;
        this.meetings = meetings;
        this.templates = templates;
    }

    /** Blocks until the run is finished; progress is also published on the event stream as enhance.progress. */
    @PostMapping("/meetings/{meetingId}/enhance")
    Map<String, Object> enhance(@PathVariable("meetingId") String meetingId,
                                @RequestBody(required = false) Map<String, Object> request) {
        EnhancementService.Outcome outcome = enhancement.enhance(meetingId, new JsonBody(request).optionalText("template_id"));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("run_id", outcome.runId());
        reply.put("status", outcome.status());
        if (outcome.message() != null) reply.put("message", outcome.message());
        return reply;
    }

    @PostMapping("/meetings/{meetingId}/enhance/undo")
    Map<String, Object> undo(@PathVariable("meetingId") String meetingId) {
        enhancement.undo(meetingId);
        return Map.of("status", "undone");
    }

    @PostMapping("/meetings/{meetingId}/followup-email")
    Map<String, Object> followUp(@PathVariable("meetingId") String meetingId) {
        EnhancementService.Draft draft = enhancement.followUpEmail(meetingId);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("run_id", draft.runId());
        reply.put("status", draft.status());
        if (draft.subject() != null) reply.put("subject", draft.subject());
        if (draft.body() != null) reply.put("body", draft.body());
        reply.put("sources", draft.sources());
        if (draft.message() != null) reply.put("message", draft.message());
        return reply;
    }

    @GetMapping("/meetings/{meetingId}/actions")
    List<Map<String, Object>> actions(@PathVariable("meetingId") String meetingId) {
        requireMeeting(meetingId);
        return ai.actions(meetingId).stream().map(EnhanceController::view).toList();
    }

    @GetMapping("/meetings/{meetingId}/ai-runs")
    List<Map<String, Object>> runs(@PathVariable("meetingId") String meetingId) {
        requireMeeting(meetingId);
        return ai.runs(meetingId).stream().map(EnhanceController::view).toList();
    }

    @GetMapping("/templates")
    List<Map<String, Object>> templates() {
        return templates.list().stream().map(t -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", t.id());
            view.put("name", t.name());
            view.put("instructions", t.instructions());
            view.put("builtin", t.builtin());
            return view;
        }).toList();
    }

    private void requireMeeting(String meetingId) {
        if (meetings.findMeeting(meetingId).isEmpty()) throw ApiProblems.notFound("meeting");
    }

    private static Map<String, Object> view(ActionRow action) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", action.id());
        view.put("text", action.text());
        if (action.owner() != null) view.put("owner", action.owner());
        if (action.dueDate() != null) view.put("due_date", action.dueDate());
        view.put("status", action.status());
        if (action.sourceSegmentId() != null) view.put("source_segment_id", action.sourceSegmentId());
        return view;
    }

    private static Map<String, Object> view(RunRow run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id());
        view.put("kind", run.kind());
        if (run.providerId() != null) view.put("provider_id", run.providerId());
        view.put("model", run.model());
        view.put("prompt_version", run.promptVersion());
        if (run.inputTokens() != null) view.put("input_tokens", run.inputTokens());
        if (run.outputTokens() != null) view.put("output_tokens", run.outputTokens());
        view.put("status", run.status());
        if (run.costEstimateUsd() != null) view.put("cost_estimate_usd", run.costEstimateUsd());
        return view;
    }
}
