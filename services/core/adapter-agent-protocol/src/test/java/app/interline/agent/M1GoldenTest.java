package app.interline.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Additive protocol contracts, independent of the unchanged M0 golden test. */
class M1GoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("repo.root"));
    private static final List<String> NEW_TYPES = List.of("level", "stt.partial", "device.changed",
            "permission.status", "permission.request", "permission.denied", "transcribe.file", "transcribe.file.done");

    @Test void AC3_goldensM1CorpusCoversEveryAdditiveMessage() throws Exception {
        var rows = corpus("m1");
        assertThat(rows.stream().map(n -> n.path("t").asText()).toList()).containsAll(NEW_TYPES);
    }

    @ParameterizedTest(name = "AC3_goldens_{0}") @ValueSource(strings = {"m0", "m1"})
    void AC3_goldensEveryMessageValidatesAndDecodesWithoutLosingFields(String milestone) throws Exception {
        var schema = schema();
        var codec = new FrameCodec();
        assertThat(corpus(milestone)).isNotEmpty().allSatisfy(golden -> {
            assertThat(schema.validate(golden)).as("shared schema: %s", golden).isEmpty();
            if (!golden.path("t").asText().equals("audio.chunk")) {
                var message = codec.decodeControl(JSON.writeValueAsBytes(golden));
                assertThat(message.type()).isEqualTo(golden.path("t").asText());
                assertThat(message.body()).isEqualTo(golden.path("body"));
                assertThat(message.version()).isEqualTo(golden.path("v").asInt());
                assertThat(message.timestamp()).isEqualTo(golden.path("ts").asLong());
            }
        });
    }

    @ParameterizedTest(name = "AC3_goldens_invalid_{0}") @ValueSource(strings = {"level", "stt.partial", "device.changed", "permission.denied", "transcribe.file"})
    void AC3_goldensRejectMissingRequiredBodyAndIncorrectType(String type) throws Exception {
        var valid = corpus("m1").stream().filter(n -> n.path("t").asText().equals(type)).findFirst();
        assertThat(valid).as("M1 corpus example of %s", type).isPresent();
        ObjectNode missingBody = (ObjectNode) valid.orElseThrow().deepCopy();
        missingBody.remove("body");
        assertThat(schema().validate(missingBody)).isNotEmpty();
        assertThatThrownBy(() -> new FrameCodec().decodeControl(JSON.writeValueAsBytes(missingBody)))
                .isInstanceOf(BadFrameException.class);
        ObjectNode wrongType = (ObjectNode) valid.orElseThrow().deepCopy();
        wrongType.put("t", 42);
        assertThat(schema().validate(wrongType)).isNotEmpty();
        assertThatThrownBy(() -> new FrameCodec().decodeControl(JSON.writeValueAsBytes(wrongType)))
                .isInstanceOf(BadFrameException.class);
    }

    @Test void AC3_goldensM0IsByteIdenticalToMilestoneBase() throws Exception {
        var process = new ProcessBuilder("git", "show", "7ecff48:protocol/agent/v1/golden/m0.ndjson").directory(ROOT.toFile()).start();
        byte[] baseline = process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        assertThat(Files.readAllBytes(ROOT.resolve("protocol/agent/v1/golden/m0.ndjson"))).isEqualTo(baseline);
    }

    private static List<JsonNode> corpus(String milestone) throws Exception {
        Path file = ROOT.resolve("protocol/agent/v1/golden/" + milestone + ".ndjson");
        assertThat(file).as("Required %s protocol corpus", milestone).isRegularFile();
        var rows = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file)) rows.add(JSON.readTree(line));
        return rows;
    }
    private static JsonSchema schema() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(JSON.readTree(ROOT.resolve("protocol/agent/v1/schema.json").toFile()));
    }
}
