package app.interline.ai;

import app.interline.ai.ConnectionResult.Outcome;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static org.assertj.core.api.Assertions.assertThat;

/** AC13: the connection test per provider kind, against the three HTTP shapes served by WireMock. Keys are sk-test only. */
class ConnectionTesterTest {
    private static final String KEY = "sk-test-0123456789abcdef";
    private static final String OK_BODY = "{\"object\":\"list\",\"data\":[],\"models\":[]}";

    private WireMockServer server;
    private final ConnectionTester tester = new ConnectionTester();

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private ProviderConfig config(String kind) {
        String base = switch (kind) {
            case "openai_compatible" -> server.baseUrl() + "/v1";
            default -> server.baseUrl();
        };
        return new ProviderConfig(kind, base, "stub-model", false, null);
    }

    private void respond(int status, String body) {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void AC13_connection_nothingIsSentUntilTestIsCalled() {
        respond(200, OK_BODY);

        ProviderConfig openai = config("openai_compatible");

        assertThat(openai.baseUrl()).startsWith("http://");
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void AC13_connection_openaiCompatible_okSendsOneBearerRequest() {
        respond(200, OK_BODY);

        ConnectionResult result = tester.test(config("openai_compatible"), KEY);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(server.getAllServeEvents()).hasSize(1);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertThat(request.getUrl()).startsWith("/v1/");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
    }

    @Test
    void AC13_connection_anthropic_okSendsOneRequestWithXApiKeyAndVersion() {
        respond(200, OK_BODY);

        ConnectionResult result = tester.test(config("anthropic"), KEY);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(server.getAllServeEvents()).hasSize(1);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertThat(request.getHeader("x-api-key")).isEqualTo(KEY);
        assertThat(request.getHeader("anthropic-version")).isNotBlank();
    }

    @Test
    void AC13_connection_ollama_okNeedsNoKey() {
        respond(200, OK_BODY);

        ConnectionResult result = tester.test(config("ollama"), null);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(server.getAllServeEvents()).hasSize(1);
        var request = server.getAllServeEvents().get(0).getRequest();
        assertThat(request.containsHeader("Authorization")).isFalse();
        assertThat(request.containsHeader("x-api-key")).isFalse();
    }

    @ParameterizedTest(name = "AC13_connection_{0}_401IsUnauthorized")
    @ValueSource(strings = {"openai_compatible", "anthropic"})
    void AC13_connection_rejectedKeyIsUnauthorized(String kind) {
        respond(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}");

        ConnectionResult result = tester.test(config(kind), KEY);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAUTHORIZED);
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @ParameterizedTest(name = "AC13_connection_{0}_closedPortIsUnreachable")
    @ValueSource(strings = {"openai_compatible", "anthropic", "ollama"})
    void AC13_connection_nobodyListeningIsUnreachableNotAnException(String kind) {
        ProviderConfig closed = new ProviderConfig(kind, "http://127.0.0.1:1", "stub-model", false, null);

        ConnectionResult result = tester.test(closed, KEY);

        assertThat(result.outcome()).isEqualTo(Outcome.UNREACHABLE);
    }

    @ParameterizedTest(name = "AC12_connection_{0}_resultNeverContainsTheKeyEvenWhenTheServerEchoesIt")
    @ValueSource(strings = {"openai_compatible", "anthropic"})
    void AC12_connection_detailNeverContainsTheKey(String kind) {
        respond(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");

        ConnectionResult result = tester.test(config(kind), KEY);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAUTHORIZED);
        assertThat(String.valueOf(result.detail())).doesNotContain(KEY).doesNotContain("0123456789abcdef");
        assertThat(result.toString()).doesNotContain(KEY);
    }
}
