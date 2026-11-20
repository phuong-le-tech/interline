package app.interline.app;

import app.interline.ai.BriefInput;
import app.interline.ai.BriefRunner;
import app.interline.ai.BriefResult;
import app.interline.domain.BriefOutput.Line;
import app.interline.persistence.AiStore;
import app.interline.persistence.BriefStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** User-triggered generation with a persistent cache of the exact raw brief inputs. */
final class BriefService {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Outcome(String status, List<Line> lines, String runId, String message) { }
    private final MeetingService meetings;
    private final BriefStore cache;
    private final AiStore ai;
    private final ProviderService providers;
    private final Clock clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    BriefService(MeetingService meetings, BriefStore cache, AiStore ai, ProviderService providers, Clock clock) {
        this.meetings = meetings;
        this.cache = cache;
        this.ai = ai;
        this.providers = providers;
        this.clock = clock;
    }

    Outcome cached(MeetingService.Brief brief) {
        return cached(brief, hash(brief));
    }

    private Outcome cached(MeetingService.Brief brief, String hash) {
        return cache.find(brief.meetingId()).map(c -> c.inputHash().equals(hash)
                ? new Outcome("ok", readLines(c.text()), c.aiRunId(), null)
                : new Outcome("stale", List.of(), null, null))
                .orElseGet(() -> new Outcome("missing", List.of(), null, null));
    }

    Outcome write(String meetingId) {
        MeetingService.Brief brief = meetings.brief(meetingId);
        var provider = providers.defaultProvider().orElseThrow(() -> ApiProblems.of(HttpStatus.CONFLICT,
                "no_provider", "Configure a provider in Settings before writing a brief."));
        if (!running.add(meetingId)) throw ApiProblems.of(HttpStatus.CONFLICT, "brief_running", "A brief is already being written.");
        try {
            String hash = hash(brief);
            Outcome cached = cached(brief, hash);
            if (cached.status().equals("ok")) return cached;
            var input = new BriefInput(meetingId, ai.meetingTitle(meetingId).orElse(""),
                    brief.participants().stream().map(p -> firstName(p.displayName())).filter(n -> !n.isBlank()).toList(),
                    related(brief),
                    brief.openActions().stream().map(a -> new BriefInput.OpenAction(a.id(), a.meetingId(), a.text(),
                            firstName(a.ownerLabel()), a.dueDate())).toList());
            String runId = ai.startRun(new AiStore.RunStart(meetingId, "brief", provider.id(), provider.model(),
                    BriefRunner.PROMPT_VERSION), clock.millis());
            BriefResult result;
            try {
                result = new BriefRunner(providers.llm(provider)).run(input);
            } catch (RuntimeException e) {
                // Provider exceptions can echo credentials; persist and return only a fixed error.
                ai.finishRun(runId, new AiStore.RunEnd("error", null, null, "provider_call_failed", null), clock.millis());
                return new Outcome("error", List.of(), runId, "The provider call failed. Check the provider settings.");
            }
            String status = result.status() == BriefResult.Status.OK ? "ok" : "invalid_output";
            ai.finishRun(runId, new AiStore.RunEnd(status, result.inputTokens(), result.outputTokens(), null,
                    ProviderService.cost(provider, result.inputTokens(), result.outputTokens())), clock.millis());
            if (result.status() == BriefResult.Status.OK) {
                cache.save(meetingId, new BriefStore.Cached(json(result.lines()), hash, runId, clock.millis()));
                // Inputs may change while the provider is running; never expose an obsolete result as fresh.
                return cached(meetings.brief(meetingId));
            }
            return new Outcome(status, List.of(), runId, result.message());
        } finally {
            running.remove(meetingId);
        }
    }

    private String hash(MeetingService.Brief brief) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json(List.of(brief.meetingId(), brief.participants(), related(brief),
                            brief.related().stream().map(r -> r.match().reason()).toList(), brief.openActions(),
                            ai.meetingTitle(brief.meetingId()).orElse(""))).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<BriefInput.RelatedMeeting> related(MeetingService.Brief brief) {
        return brief.related().stream().map(r -> new BriefInput.RelatedMeeting(r.match().candidate().meetingId(),
                r.match().candidate().title(), r.match().candidate().dateMs(), r.notes())).toList();
    }

    private static String firstName(String name) {
        if (name == null || name.contains("@")) return "";
        return name.strip().split("[\\s\\p{Z}]+", 2)[0];
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot serialize brief", e); }
    }

    private static List<Line> readLines(String text) {
        try { return JSON.readValue(text, new TypeReference<List<Line>>() { }); }
        catch (JsonProcessingException e) { throw new IllegalStateException("cannot read cached brief", e); }
    }
}
