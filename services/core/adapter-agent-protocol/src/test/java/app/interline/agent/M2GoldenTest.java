package app.interline.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC19: secret.get / secret.put / secret.delete are part of protocol v1 (schema and golden corpus). */
class M2GoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("repo.root"));

    @Test
    void AC19_goldensM2CorpusCoversEverySecretMessage() throws Exception {
        assertThat(corpus().stream().map(n -> n.path("t").asText()).distinct().toList())
                .contains("secret.get", "secret.put", "secret.delete", "error");
    }

    @Test
    void AC19_goldensM2EveryMessageValidatesAndDecodesWithoutLosingFields() throws Exception {
        var schema = schema();
        var codec = new FrameCodec();
        assertThat(corpus()).isNotEmpty().allSatisfy(golden -> {
            assertThat(schema.validate(golden)).as("shared schema: %s", golden).isEmpty();
            var message = codec.decodeControl(JSON.writeValueAsBytes(golden));
            assertThat(message.type()).isEqualTo(golden.path("t").asText());
            assertThat(message.body()).isEqualTo(golden.path("body"));
        });
    }

    @ParameterizedTest(name = "AC19_schemaRejectsRequestWithout_{1}_{0}")
    @CsvSource({"secret.get,account", "secret.put,account", "secret.put,value", "secret.delete,account"})
    void AC19_schemaRejectsSecretRequestMissingARequiredField(String type, String field) throws Exception {
        ObjectNode request = (ObjectNode) corpus().stream()
                .filter(n -> n.path("t").asText().equals(type) && n.has("id")).findFirst().orElseThrow().deepCopy();
        assertThat(schema().validate(request)).as("the unmodified golden request is valid").isEmpty();
        ((ObjectNode) request.path("body")).remove(field);

        assertThat(schema().validate(request)).as("%s without %s", type, field).isNotEmpty();
    }

    @Test
    void AC19_schemaRejectsNonStringSecretValue() throws Exception {
        ObjectNode put = (ObjectNode) corpus().stream()
                .filter(n -> n.path("t").asText().equals("secret.put") && n.has("id")).findFirst().orElseThrow().deepCopy();
        ((ObjectNode) put.path("body")).put("value", 42);
        ObjectNode valid = corpus().stream().filter(n -> n.path("t").asText().equals("secret.put") && n.has("id"))
                .findFirst().orElseThrow().deepCopy();
        assertThat(schema().validate(valid)).as("the unmodified golden put is valid").isEmpty();

        assertThat(schema().validate(put)).isNotEmpty();
    }

    private static List<JsonNode> corpus() throws Exception {
        Path file = ROOT.resolve("protocol/agent/v1/golden/m2/m2.ndjson");
        assertThat(file).as("Required m2 protocol corpus").isRegularFile();
        var rows = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file)) rows.add(JSON.readTree(line));
        return rows;
    }

    private static JsonSchema schema() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(JSON.readTree(ROOT.resolve("protocol/agent/v1/schema.json").toFile()));
    }
}
