package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static app.interline.app.M3BriefSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** AI brief wire contract: POST returns {status, lines:[{text,sources:[meetingId]}]}; GET exposes it as ai. */
@Timeout(180)
class M3AiBriefTest {
    @TempDir Path data;
    private FakeOpenAi stub;
    private ConfigurableApplicationContext context;
    private int port;
    private String meeting;
    private static final String REPLY = "{\"lines\":[{\"text\":\"Confirm the budget decision.\",\"sources\":[\"previous\"]}]}";

    @BeforeEach void start() {
        stub = new FakeOpenAi().reply(REPLY);
        context = boot(data, "--spring.ai.openai.base-url=" + stub.baseUrl(),
                "--spring.ai.openai.api-key=" + KEY,
                "--spring.ai.anthropic.base-url=" + stub.baseUrl(), "--spring.ai.anthropic.api-key=" + KEY,
                "--spring.ai.ollama.base-url=" + stub.baseUrl(),
                "--logging.level.app.interline=TRACE", "--logging.level.org.springframework.ai=TRACE");
        port = port(context);
        meeting = createMeetingOk(port, "Budget review", List.of(person("Alice SurnamePrivate", "alice@private.example")));
        insertMeeting(data, "previous", "Budget review", 1);
        insertNote(data, "previous-note", "previous", "a0", "user", "Budget decision from my notes");
        insertNote(data, "previous-ai", "previous", "a1", "ai", "AI-NOTE-MUST-NOT-LEAK");
        insertNote(data, "current-note", meeting, "a0", "user", "CURRENT-NOTE-MUST-NOT-LEAK");
        insertAction(data, "owed", "previous", "Send the forecast", "open", 1);
    }

    @AfterEach void stop() {
        if (context != null && context.isActive()) context.close();
        stub.close();
    }

    private Resp generate() {
        return call(port, "POST", "/meetings/" + meeting + "/brief/ai", Map.of());
    }

    private JsonNode generateOk() {
        return ok(generate(), "write AI brief").json();
    }

    private void configured() {
        provider(port, stub, Map.of("is_default", true));
    }

    @Test void noProviderReturns409AndMakesZeroOutboundRequests() {
        Resp response = generate();
        assertProblem(response, 409, "no_provider");
        assertThat(response.json().path("detail").asText()).contains("Configure a provider");
        assertThat(stub.requests).isEmpty();
        assertThat(M3BriefSupport.rows(data, "SELECT id FROM ai_runs WHERE kind = 'brief'")).isEmpty();
    }

    @Test void getNeverGeneratesAndTheSecondPostUsesThePersistedCache() {
        configured();
        briefOk(port, meeting);
        assertThat(stub.requests).isEmpty();
        JsonNode first = generateOk();
        assertThat(first.path("status").asText()).isEqualTo("ok");
        assertThat(first.path("lines")).hasSizeBetween(1, 5);
        first.path("lines").forEach(line -> {
            assertThat(line.path("text").asText()).isNotBlank();
            assertThat(line.path("sources")).extracting(JsonNode::asText).containsExactly("previous");
        });
        assertThat(generateOk().path("lines")).isEqualTo(first.path("lines"));
        assertThat(briefOk(port, meeting).path("ai").path("lines")).isEqualTo(first.path("lines"));
        assertThat(stub.chatCalls()).hasSize(1);
        assertThat(M3BriefSupport.rows(data, "SELECT id FROM ai_runs WHERE meeting_id = ? AND kind = 'brief'", meeting)).hasSize(1);
        assertThat(M3BriefSupport.rows(data, "SELECT input_hash, ai_run_id FROM briefs WHERE meeting_id = ?", meeting))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("input_hash")).isNotNull();
                    assertThat(row.get("ai_run_id")).isNotNull();
                });
        context.close();
        context = boot(data);
        port = port(context);
        assertThat(briefOk(port, meeting).path("ai").path("lines")).isEqualTo(first.path("lines"));
        assertThat(stub.chatCalls()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"participants", "notes", "actions"})
    void changedInputsHideCachedTextUntilExplicitRefresh(String changed) {
        configured();
        generateOk();
        switch (changed) {
            case "participants" -> ok(putParticipants(port, meeting,
                    List.of(person("Bob", "bob@example.com"))), "change participant");
            case "notes" -> sql(data, "UPDATE note_blocks SET plain_text = ?, updated_at = 2 WHERE id = 'previous-note'", "Revised decision");
            case "actions" -> sql(data, "UPDATE action_items SET status = 'done' WHERE id = 'owed'");
            default -> throw new AssertionError(changed);
        }
        JsonNode stale = briefOk(port, meeting).path("ai");
        assertThat(stale.path("status").asText()).isEqualTo("stale");
        assertThat(stale.path("lines")).isEmpty();
        assertThat(stale.toString()).doesNotContain("Confirm the budget decision.");
        assertThat(stub.chatCalls()).hasSize(1);
        assertThat(generateOk().path("status").asText()).isEqualTo("ok");
        assertThat(stub.chatCalls()).hasSize(2);
        assertThat(briefOk(port, meeting).path("ai").path("status").asText()).isEqualTo("ok");
    }

    @Test void invalidOutputRetriesOnceThenReturnsCleanErrorAndWritesNoCache() {
        configured();
        stub.reply("{\"lines\":[{\"text\":\"Invented\",\"sources\":[\"unrelated\"]}]}", "not JSON");
        Resp response = generate();
        assertThat(response.status()).isBetween(200, 299);
        assertThat(response.json().path("status").asText()).isEqualTo("invalid_output");
        assertThat(response.json().path("message").asText()).isEqualTo("The model did not respect the format");
        assertThat(stub.chatCalls()).hasSize(2);
        assertThat(M3BriefSupport.rows(data, "SELECT id FROM briefs WHERE meeting_id = ?", meeting)).isEmpty();
        assertThat(M3BriefSupport.rows(data, "SELECT status FROM ai_runs WHERE meeting_id = ? AND kind = 'brief'", meeting))
                .containsExactly(Map.of("status", "invalid_output"));
    }

    @Test void retryTokensAreRecordedAndPromptContainsOnlyPermittedContextWithoutChangingNotes() throws Exception {
        configured();
        List<Map<String, Object>> before = M3BriefSupport.rows(data, "SELECT * FROM note_blocks ORDER BY id");
        stub.reply("not JSON", REPLY);
        Resp response = ok(generate(), "brief");
        assertThat(response.body()).doesNotContain(KEY);
        assertThat(stub.chatCalls()).hasSize(2);
        String prompt = stub.chatCalls().get(0).prompt();
        assertThat(prompt).contains("Alice", "Budget review", "Budget decision from my notes", "Send the forecast")
                .doesNotContain("SurnamePrivate", "alice@private.example", "AI-NOTE-MUST-NOT-LEAK", "CURRENT-NOTE-MUST-NOT-LEAK", KEY);
        assertThat(M3BriefSupport.rows(data, "SELECT * FROM note_blocks ORDER BY id")).isEqualTo(before);
        assertThat(M3BriefSupport.rows(data,
                "SELECT kind, input_tokens, output_tokens, status, prompt_version FROM ai_runs WHERE meeting_id = ?", meeting))
                .singleElement().satisfies(run -> {
                    assertThat(run.get("kind")).isEqualTo("brief");
                    assertThat(run.get("status")).isEqualTo("ok");
                    assertThat(run.get("prompt_version")).isEqualTo("brief/v1");
                    assertThat(((Number) run.get("input_tokens")).intValue()).isEqualTo(2 * FakeOpenAi.PROMPT_TOKENS);
                    assertThat(((Number) run.get("output_tokens")).intValue()).isEqualTo(2 * FakeOpenAi.COMPLETION_TOKENS);
                });
        assertThat(briefOk(port, meeting).toString()).doesNotContain(KEY);
        context.close();
        assertThat(new String(Files.readAllBytes(data.resolve("db.sqlite")), StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
        try (var logs = Files.walk(data.resolve("logs"))) {
            for (Path log : logs.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(log, StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
            }
        }
    }

    @Test void providerErrorsNeverEchoCredentialsInResponseOrLogs() throws Exception {
        configured();
        stub.rejectWith(401, true);
        Resp response = generate();
        assertThat(stub.chatCalls()).isNotEmpty();
        assertThat(response.status()).isBetween(200, 299);
        assertThat(response.json().path("status").asText()).isEqualTo("error");
        assertThat(response.body()).doesNotContain(KEY);
        assertThat(M3BriefSupport.rows(data, "SELECT id FROM briefs WHERE meeting_id = ?", meeting)).isEmpty();
        context.close();
        try (var logs = Files.walk(data.resolve("logs"))) {
            for (Path log : logs.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(log, StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
            }
        }
    }
}
