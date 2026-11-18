package app.interline.app;

import app.interline.app.AppTestSupport.Events;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Duration;

import static app.interline.app.AppTestSupport.request;
import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC11: with no provider configured the core never talks to a model. The loopback stub stands in for "any host":
 * every spring-ai style endpoint property points at it, so any outbound attempt shows up as a recorded request.
 */
@Timeout(180)
class M2NoProviderTest {
    @TempDir Path data;
    private FakeOpenAi stub;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        stub = new FakeOpenAi();
        context = boot(data,
                "--spring.ai.openai.base-url=" + stub.baseUrl(), "--spring.ai.openai.api-key=" + KEY,
                "--spring.ai.anthropic.base-url=" + stub.baseUrl(), "--spring.ai.anthropic.api-key=" + KEY,
                "--spring.ai.ollama.base-url=" + stub.baseUrl());
        port = port(context);
    }

    @AfterEach void stop() {
        context.close();
        stub.close();
    }

    @Test void AC11_noStartupCalls_idleCoreSendsNothingToAnyConfiguredEndpoint() {
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(stub.requests).isEmpty());
    }

    @Test void AC11_noProvider_providerListIsEmptyAndEnhanceSaysConfigureAProvider() {
        Golden golden = golden(1);
        seedMeeting(data, golden);
        putNotes(port, golden);

        assertThat(ok(call(port, "GET", "/providers", null), "list providers").json()).isEmpty();
        var enhance = enhance(port, golden.meetingId(), null);

        assertThat(enhance.status()).isEqualTo(409);
        assertThat(enhance.json().path("detail").asText()).contains("Configure a provider");
        assertThat(enhance.json().path("code").asText()).isEqualTo("no_provider");
        assertThat(stub.requests).isEmpty();
    }

    @Test void AC11_noOutbound_recordingAndEditingMakeZeroModelRequests() throws Exception {
        try (Events events = new Events(port)) {
            String id = request(port, "POST", "/recording/start").path("meetingId").asText();
            events.state(id, "recording");
            ok(putBlock(port, id, "01900000-0000-7000-8000-000000000001", 1, "paragraph", "a0", "Typed while recording", 1_000L), "edit");
            request(port, "POST", "/recording/stop");
            events.state(id, "done");
            ok(putBlock(port, id, "01900000-0000-7000-8000-000000000001", 2, "paragraph", "a0", "Edited after recording", 1_000L), "edit");

            assertThat(blocks(port, id)).singleElement()
                    .satisfies(b -> assertThat(b.path("plain_text").asText()).isEqualTo("Edited after recording"));
        }
        assertThat(stub.requests).isEmpty();
    }

    @Test void AC11_noProvider_followUpEmailAlsoNeedsAProvider() {
        Golden golden = golden(1);
        seedMeeting(data, golden);

        var response = call(port, "POST", "/meetings/" + golden.meetingId() + "/followup-email", java.util.Map.of());

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().path("detail").asText()).contains("Configure a provider");
        assertThat(stub.requests).isEmpty();
    }

    @Test void AC11_connectionTestOnlyOnClick_savingAProviderAndItsKeySendsNothing() {
        provider(port, stub, java.util.Map.of());

        assertThat(stub.requests).isEmpty();
    }
}
