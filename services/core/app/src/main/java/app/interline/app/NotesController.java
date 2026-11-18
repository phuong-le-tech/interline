package app.interline.app;

import app.interline.persistence.AiStore;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.NoteStore;
import app.interline.persistence.NoteStore.BlockRow;
import app.interline.persistence.NoteStore.BlockWrite;
import app.interline.persistence.NoteStore.Upsert;
import app.interline.persistence.SearchStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Note blocks (synced from the editor journal), search and Markdown export. */
@RestController
@RequestMapping("/api/v1")
class NotesController {
    private static final Set<String> KINDS = Set.of("heading", "paragraph", "bullet", "todo", "quote");
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);
    private static final String FORMAT_MARKDOWN = "markdown";

    private final NoteStore notes;
    private final AiStore ai;
    private final CaptureStore meetings;
    private final SearchStore search;
    private final Clock clock;

    NotesController(NoteStore notes, AiStore ai, CaptureStore meetings, SearchStore search, Clock clock) {
        this.notes = notes;
        this.ai = ai;
        this.meetings = meetings;
        this.search = search;
        this.clock = clock;
    }

    @PutMapping("/meetings/{meetingId}/blocks/{blockId}")
    Map<String, Object> putBlock(@PathVariable("meetingId") String meetingId, @PathVariable("blockId") String blockId,
                                 @RequestBody Map<String, Object> request) {
        requireMeeting(meetingId);
        JsonBody body = new JsonBody(request);
        long rev = body.requiredWholeNumber("client_rev");
        if (rev <= 0) throw ApiProblems.badRequest("client_rev must be positive");
        Long anchor = body.optionalWholeNumber("anchor_ms");
        if (anchor != null && anchor < 0) throw ApiProblems.badRequest("anchor_ms must not be negative");
        BlockWrite write = new BlockWrite(blockId, body.requiredChoice("kind", KINDS), body.requiredText("position"),
                body.text("content"), body.text("plain_text"), anchor, rev);
        Upsert result;
        try {
            result = notes.putUserBlock(meetingId, write, clock.millis());
        } catch (DataIntegrityViolationException e) {
            throw ApiProblems.of(HttpStatus.CONFLICT, "block_id_conflict", "This block id belongs to another meeting.");
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", result.block().id());
        reply.put("client_rev", result.block().clientRev());
        reply.put("applied", result.applied());
        return reply;
    }

    @GetMapping("/meetings/{meetingId}/blocks")
    List<Map<String, Object>> blocks(@PathVariable("meetingId") String meetingId) {
        requireMeeting(meetingId);
        Map<String, List<Long>> sources = notes.sources(meetingId);
        return notes.blocks(meetingId).stream().map(block -> view(block, sources.getOrDefault(block.id(), List.of()))).toList();
    }

    @DeleteMapping("/meetings/{meetingId}/blocks/{blockId}")
    ResponseEntity<Void> deleteBlock(@PathVariable("meetingId") String meetingId, @PathVariable("blockId") String blockId) {
        requireMeeting(meetingId);
        if (!notes.delete(meetingId, blockId)) throw ApiProblems.notFound("block");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/meetings/{meetingId}/blocks/{blockId}/accept")
    Map<String, Object> accept(@PathVariable("meetingId") String meetingId, @PathVariable("blockId") String blockId) {
        requireMeeting(meetingId);
        switch (notes.accept(meetingId, blockId, clock.millis())) {
            case NOT_FOUND -> throw ApiProblems.notFound("block");
            case NOT_AN_ADDITION -> throw ApiProblems.of(HttpStatus.CONFLICT, "not_an_addition", "Only an AI addition can be accepted.");
            case ACCEPTED -> { }
        }
        return Map.of("id", blockId, "ai_state", notes.blocks(meetingId).stream()
                .filter(b -> b.id().equals(blockId)).findFirst().orElseThrow().aiState());
    }

    @GetMapping("/search")
    Map<String, Object> search(@RequestParam(name = "q", required = false) String query) {
        if (query == null || query.isBlank()) throw ApiProblems.badRequest("q is required");
        List<Map<String, Object>> results = search.search(query).stream().map(hit -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("type", hit.type());
            view.put("meeting_id", hit.meetingId());
            view.put("id", hit.id());
            view.put("snippet", hit.snippet());
            return view;
        }).toList();
        return Map.of("results", results);
    }

    @GetMapping("/meetings/{meetingId}/export")
    ResponseEntity<String> export(@PathVariable("meetingId") String meetingId,
                                  @RequestParam(name = "format", defaultValue = FORMAT_MARKDOWN) String format,
                                  @RequestParam(name = "transcript", defaultValue = "false") boolean transcript) {
        var meeting = meetings.findMeeting(meetingId).orElseThrow(() -> ApiProblems.notFound("meeting"));
        if (!FORMAT_MARKDOWN.equals(format)) throw ApiProblems.badRequest("format must be markdown");
        String markdown = MarkdownExport.render(meeting.title(), notes.blocks(meetingId), ai.segments(meetingId), transcript);
        return ResponseEntity.ok().contentType(MARKDOWN).body(markdown);
    }

    private void requireMeeting(String meetingId) {
        if (meetings.findMeeting(meetingId).isEmpty()) throw ApiProblems.notFound("meeting");
    }

    private static Map<String, Object> view(BlockRow block, List<Long> sources) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", block.id());
        view.put("kind", block.kind());
        view.put("origin", block.origin());
        if (block.parentId() != null) view.put("parent_id", block.parentId());
        view.put("position", block.position());
        view.put("content", block.content());
        view.put("plain_text", block.plainText());
        if (block.anchorMs() != null) view.put("anchor_ms", block.anchorMs());
        view.put("client_rev", block.clientRev());
        if (block.aiState() != null) view.put("ai_state", block.aiState());
        view.put("sources", sources);
        return view;
    }
}
