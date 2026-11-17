package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC12: an API key put through the API is write-only. The only place it appears outside the OS secret store is the
 * secret.put / secret.get exchange with the agent (observed by a stdio tee) and the Authorization header sent to the
 * provider. Never in the database file, the logs, an API response or an error body.
 */
@Timeout(180)
class M2KeySafetyTest {
    @TempDir Path data;
    private FakeOpenAi stub;
    private ConfigurableApplicationContext context;
    private PipelineAgentProbe probe;
    private int port;
    private final List<Resp> responses = new ArrayList<>();

    @BeforeEach void start() {
        stub = new FakeOpenAi();
        probe = new PipelineAgentProbe(data);
        context = boot(data, M1AgentProbe.launcher(data, false),
                "--logging.level.app.interline=TRACE", "--logging.level.org.springframework.web=TRACE",
                "--logging.level.org.springframework.ai=TRACE");
        port = port(context);
    }

    @AfterEach void stop() throws Exception {
        if (context.isActive()) context.close();
        probe.close();
        stub.close();
    }

    private Resp record(Resp response) {
        responses.add(response);
        return response;
    }

    private String createProvider() {
        Resp created = record(call(port, "POST", "/providers", Map.of("kind", "openai_compatible", "name", "Stub",
                "base_url", stub.baseUrl(), "model", "stub-model", "supports_json_schema", false)));
        return ok(created, "create provider").json().path("id").asText();
    }

    private List<JsonNode> coreRequests(String type) throws IOException {
        return probe.observations().stream().filter(o -> o.path("direction").asText().equals("core"))
                .filter(o -> o.path("type").asText().equals(type)).map(o -> o.path("message")).toList();
    }

    @Test void AC12_keyNeverLeaks_notInTheDatabaseFileTheLogsOrAnyApiResponse() throws Exception {
        stub.rejectWith(401, true);
        String id = createProvider();
        record(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)));
        record(call(port, "GET", "/providers", null));
        record(call(port, "GET", "/providers/" + id, null));
        Resp rejected = record(call(port, "POST", "/providers/" + id + "/test", Map.of()));
        assertThat(rejected.json().path("status").asText()).isEqualTo("unauthorized");
        stub.accept();
        Resp accepted = record(call(port, "POST", "/providers/" + id + "/test", Map.of()));
        assertThat(accepted.json().path("status").asText()).isEqualTo("ok");
        record(call(port, "POST", "/providers/no-such-provider/test", Map.of()));
        record(call(port, "PUT", "/providers/no-such-provider/key", Map.of("key", KEY)));
        record(call(port, "PUT", "/providers/" + id + "/key", Map.of("wrong", "shape")));
        context.close();

        assertThat(responses).isNotEmpty().allSatisfy(r -> assertThat(r.body()).doesNotContain(KEY));
        assertThat(new String(Files.readAllBytes(data.resolve("db.sqlite")), StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
        Path wal = data.resolve("db.sqlite-wal");
        if (Files.exists(wal)) assertThat(new String(Files.readAllBytes(wal), StandardCharsets.ISO_8859_1)).doesNotContain(KEY);
        try (Stream<Path> logs = Files.walk(data.resolve("logs"))) {
            assertThat(logs.filter(Files::isRegularFile).toList()).isNotEmpty().allSatisfy(file ->
                    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).doesNotContain(KEY));
        }
    }

    @Test void AC12_keyNeverLeaks_itTravelsOnlyOnTheAgentPipeAndTheAuthorizationHeader() throws Exception {
        String id = createProvider();
        record(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)));
        record(call(port, "POST", "/providers/" + id + "/test", Map.of()));
        record(call(port, "POST", "/providers/" + id + "/test", Map.of()));
        context.close();

        List<JsonNode> puts = coreRequests("secret.put");
        assertThat(puts).singleElement().satisfies(put -> {
            assertThat(put.path("body").path("value").asText()).isEqualTo(KEY);
            assertThat(put.path("body").path("account").asText()).isNotBlank().doesNotContain(KEY);
        });
        assertThat(coreRequests("secret.get")).as("the key is fetched on every call, not cached").hasSize(2)
                .allSatisfy(get -> assertThat(get.path("body").path("account").asText())
                        .isEqualTo(puts.get(0).path("body").path("account").asText()));
        assertThat(probe.observations().stream().filter(o -> o.toString().contains(KEY)).map(o -> o.path("type").asText()).toList())
                .isNotEmpty().allSatisfy(type -> assertThat(type).isIn("secret.put", "secret.get"));
        assertThat(stub.requests).hasSize(2).allSatisfy(r -> assertThat(r.headers().get("authorization")).isEqualTo("Bearer " + KEY));
    }

    @Test void AC12_writeOnly_providerViewsNeverExposeTheKeyOrItsAccount() {
        String id = createProvider();
        assertThat(ok(call(port, "GET", "/providers/" + id, null), "get").json().path("has_key").asBoolean()).isFalse();

        ok(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)), "put key");

        JsonNode single = ok(call(port, "GET", "/providers/" + id, null), "get").json();
        JsonNode listed = ok(call(port, "GET", "/providers", null), "list").json().get(0);
        for (JsonNode view : List.of(single, listed)) {
            assertThat(view.path("has_key").asBoolean()).isTrue();
            assertThat(view.path("id").asText()).isEqualTo(id);
            assertThat(view.path("base_url").asText()).isEqualTo(stub.baseUrl());
            assertThat(fieldNames(view)).doesNotContain("key", "api_key", "apiKey", "secret", "secret_account", "value");
        }
    }

    @Test void AC12_writeOnly_deletingTheKeyRemovesTheSecretFromTheAgent() throws Exception {
        String id = createProvider();
        ok(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)), "put key");

        assertThat(call(port, "DELETE", "/providers/" + id + "/key", null).status()).isEqualTo(204);

        assertThat(ok(call(port, "GET", "/providers/" + id, null), "get").json().path("has_key").asBoolean()).isFalse();
        String account = coreRequests("secret.put").get(0).path("body").path("account").asText();
        assertThat(coreRequests("secret.delete")).singleElement()
                .satisfies(d -> assertThat(d.path("body").path("account").asText()).isEqualTo(account));
    }

    @Test void AC12_writeOnly_deletingTheProviderRemovesItsSecretToo() throws Exception {
        String id = createProvider();
        ok(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)), "put key");

        assertThat(call(port, "DELETE", "/providers/" + id, null).status()).isBetween(200, 204);

        assertThat(coreRequests("secret.delete")).hasSize(1);
        assertThat(ok(call(port, "GET", "/providers", null), "list").json()).isEmpty();
    }

    @Test void AC12_writeOnly_noRouteReturnsTheKey() {
        String id = createProvider();
        ok(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)), "put key");

        assertThat(call(port, "GET", "/providers/" + id + "/key", null).status()).isIn(404, 405);
        assertThat(call(port, "GET", "/secrets/" + id, null).status()).isIn(404, 405);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
