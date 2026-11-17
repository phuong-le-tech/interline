package app.interline.ai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** The production LlmPort against the three provider HTTP shapes served by WireMock. Keys are sk-test only. */
class SpringAiLlmTest {
    private static final String KEY = "sk-tes...cdef";
    private static final LlmRequest REQUEST = new LlmRequest("Be brief.", "Say hi in JSON.");

    private WireMockServer server;

    @BeforeEach void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach void stop() { server.stop(); }

    private void stub(String path, String body) {
        server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test void openAiCompatibleUsesChatCompletionsWithBearerAndReportsUsage() {
        stub("/v1/chat/completions", """
                {"id":"c1","object":"chat.completion","created":1,"model":"stub-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"ok\\":true}"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}""");
        var llm = new SpringAiLlm(new ProviderConfig("openai_compatible", server.baseUrl() + "/v1", "stub-model", false, null),
                () -> KEY);

        LlmReply reply = llm.complete(REQUEST);

        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(11);
        assertThat(reply.outputTokens()).isEqualTo(7);
        var sent = server.getAllServeEvents().get(0).getRequest();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
        assertThat(sent.getBodyAsString()).contains("Be brief.", "Say hi in JSON.", "stub-model");
    }

    @Test void anthropicUsesMessagesWithXApiKey() {
        stub("/v1/messages", """
                {"id":"m1","type":"message","role":"assistant","model":"stub-model",
                 "content":[{"type":"text","text":"{\\"ok\\":true}"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":13,"output_tokens":5}}""");
        var llm = new SpringAiLlm(new ProviderConfig("anthropic", server.baseUrl(), "stub-model", false, null), () -> KEY);

        LlmReply reply = llm.complete(REQUEST);

        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(13);
        assertThat(reply.outputTokens()).isEqualTo(5);
        assertThat(server.getAllServeEvents().get(0).getRequest().getHeader("x-api-key")).isEqualTo(KEY);
    }

    @Test void ollamaUsesApiChatWithoutAnyKey() {
        stub("/api/chat", """
                {"model":"stub-model","created_at":"2026-11-17T10:00:00Z",
                 "message":{"role":"assistant","content":"{\\"ok\\":true}"},"done":true,"done_reason":"stop",
                 "prompt_eval_count":9,"eval_count":4}""");
        var llm = new SpringAiLlm(new ProviderConfig("ollama", server.baseUrl(), "stub-model", false, 8192), () -> null);

        LlmReply reply = llm.complete(REQUEST);

        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(9);
        assertThat(reply.outputTokens()).isEqualTo(4);
        var sent = server.getAllServeEvents().get(0).getRequest();
        assertThat(sent.containsHeader("Authorization")).isFalse();
        assertThat(sent.getBodyAsString()).contains("8192");
    }
}
