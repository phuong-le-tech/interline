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

import java.nio.file.Path;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Provider settings API: validation, defaults, and the connection test (AC13 end to end, AC11 "only on click"). */
@Timeout(120)
class M2ProvidersTest {
    @TempDir Path data;
    private FakeOpenAi stub;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        stub = new FakeOpenAi();
        context = boot(data);
        port = port(context);
    }

    @AfterEach void stop() {
        context.close();
        stub.close();
    }

    @Test void AC13_connection_okAfterTheUserClicksTest() {
        String id = provider(port, stub, Map.of());
        assertThat(stub.requests).as("saving a provider and its key is not a connection test").isEmpty();

        JsonNode result = ok(call(port, "POST", "/providers/" + id + "/test", Map.of()), "test").json();

        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(stub.requests).hasSize(1);
        assertThat(stub.requests.get(0).headers().get("authorization")).isEqualTo("Bearer " + KEY);
    }

    @Test void AC13_connection_rejectedKeyIsReportedAsUnauthorized() {
        String id = provider(port, stub, Map.of());
        stub.rejectWith(401, false);

        JsonNode result = ok(call(port, "POST", "/providers/" + id + "/test", Map.of()), "test").json();

        assertThat(result.path("status").asText()).isEqualTo("unauthorized");
    }

    @Test void AC13_connection_closedPortIsReportedAsUnreachable() {
        String id = provider(port, stub, Map.of("base_url", "http://127.0.0.1:1/v1"));

        JsonNode result = ok(call(port, "POST", "/providers/" + id + "/test", Map.of()), "test").json();

        assertThat(result.path("status").asText()).isEqualTo("unreachable");
    }

    @Test void AC13_connection_unknownProviderIsNotFound() {
        assertThat(call(port, "POST", "/providers/nope/test", Map.of()).status()).isEqualTo(404);
    }

    @Test void AC13_providers_firstSavedProviderIsTheDefaultAndFieldsRoundTrip() {
        String id = provider(port, stub, Map.of("context_tokens", 32_000, "price_in_per_mtok", 3.0, "price_out_per_mtok", 15.0));

        JsonNode saved = ok(call(port, "GET", "/providers/" + id, null), "get").json();

        assertThat(saved.path("kind").asText()).isEqualTo("openai_compatible");
        assertThat(saved.path("model").asText()).isEqualTo("stub-model");
        assertThat(saved.path("is_default").asBoolean()).isTrue();
        assertThat(saved.path("context_tokens").asInt()).isEqualTo(32_000);
        assertThat(saved.path("price_in_per_mtok").asDouble()).isEqualTo(3.0);
        assertThat(saved.path("price_out_per_mtok").asDouble()).isEqualTo(15.0);
    }

    @ParameterizedTest(name = "AC13_providers_kind_{0}")
    @ValueSource(strings = {"openai_compatible", "anthropic", "ollama"})
    void AC13_providers_theThreeDocumentedKindsAreAccepted(String kind) {
        var response = call(port, "POST", "/providers", Map.of("kind", kind, "name", "P", "base_url", "http://127.0.0.1:9", "model", "m"));

        assertThat(response.status()).isBetween(200, 201);
        assertThat(response.json().path("kind").asText()).isEqualTo(kind);
    }

    @ParameterizedTest(name = "AC13_providers_invalid_{0}")
    @ValueSource(strings = {
            "{\"kind\":\"gemini\",\"name\":\"P\",\"base_url\":\"http://x\",\"model\":\"m\"}",
            "{\"kind\":\"ollama\",\"name\":\"P\",\"model\":\"m\"}",
            "{\"kind\":\"ollama\",\"name\":\"P\",\"base_url\":\"http://x\"}",
            "{\"kind\":\"ollama\",\"base_url\":\"http://x\",\"model\":\"m\"}"})
    void AC13_providers_incompleteOrUnknownKindIsABadRequest(String body) {
        assertThat(call(port, "POST", "/providers", body).status()).isEqualTo(400);
        assertThat(ok(call(port, "GET", "/providers", null), "list").json()).isEmpty();
    }

    @Test void AC13_providers_theModelCanBeChangedAfterSaving() {
        String id = provider(port, stub, Map.of());

        ok(call(port, "PUT", "/providers/" + id, Map.of("kind", "openai_compatible", "name", "Stub", "base_url", stub.baseUrl(),
                "model", "other-model")), "update");

        assertThat(ok(call(port, "GET", "/providers/" + id, null), "get").json().path("model").asText()).isEqualTo("other-model");
    }
}
