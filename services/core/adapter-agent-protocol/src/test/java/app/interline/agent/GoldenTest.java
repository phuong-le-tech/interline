package app.interline.agent;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TYPES = List.of("hello", "hello.ack", "hello.reject", "heartbeat",
            "session.start", "session.started", "session.stop", "session.stopped", "audio.chunk", "audio.ack",
            "vad.segment", "stt.final", "error", "shutdown", "shutdown.ok");

    @Test void AC3_exactlyFifteenM0TypesHaveGoldenExamples() throws Exception {
        var rows = goldens();
        assertThat(rows).hasSizeGreaterThanOrEqualTo(15);
        assertThat(rows.stream().map(row -> row.path("t").asText()).distinct().toList()).containsExactlyInAnyOrderElementsOf(TYPES);
    }

    @org.junit.jupiter.params.ParameterizedTest(name="decode {0}")
    @org.junit.jupiter.params.provider.MethodSource("controlGoldens")
    void AC3_everyControlGoldenDecodesAllEnvelopeFieldsAndBody(JsonNode golden) throws Exception {
        var codec = new FrameCodec();
        byte[] payload = JSON.writeValueAsBytes(golden);
        Frame frame = codec.read(new ByteArrayInputStream(CodecTest.wire(1, payload)));
        assertThat(frame).as("Golden %s", golden.path("t")).isNotNull();
        assertThat(frame.kind()).isEqualTo(1);
        ControlMessage message = codec.decodeControl(frame.payload());
        assertThat(message).isNotNull();
        assertThat(message.version()).isEqualTo(golden.path("v").asInt());
        assertThat(message.type()).isEqualTo(golden.path("t").asText());
        assertThat(message.id()).isEqualTo(golden.has("id") ? golden.path("id").asText() : null);
        assertThat(message.replyTo()).isEqualTo(golden.has("re") ? golden.path("re").asText() : null);
        assertThat(message.timestamp()).isEqualTo(golden.path("ts").asLong());
        assertThat(message.body()).isEqualTo(golden.path("body"));
    }

    static java.util.stream.Stream<JsonNode> controlGoldens() throws Exception {
        return goldens().stream().filter(row -> !row.path("t").asText().equals("audio.chunk"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("binaryGoldens")
    void AC3_binaryAudioGoldenDecodesLittleEndianHeaderAndSignedPcm(Path file) throws Exception {
        byte[] wire = Files.readAllBytes(file);
        var codec = new FrameCodec();
        Frame frame = codec.read(new ByteArrayInputStream(wire));
        assertThat(frame).isNotNull();
        assertThat(frame.kind()).isEqualTo(2);
        AudioChunk audio = codec.decodeAudio(frame.payload());
        assertThat(audio).isNotNull();
        assertThat(audio.stream()).isEqualTo(1);
        assertThat(audio.flags()).isEqualTo(3);
        assertThat(audio.seq()).isEqualTo(4294967295L);
        assertThat(audio.firstSample()).isEqualTo(1600);
        assertThat(audio.hostTimeNs()).isEqualTo(987654321);
        assertThat(audio.pcm()).containsExactly((byte)0, (byte)0x80, (byte)0xff, (byte)0xff, (byte)0, (byte)0, (byte)0xff, (byte)0x7f);
        var output = new ByteArrayOutputStream();
        codec.write(output, new Frame(2, codec.encodeAudio(audio)));
        assertThat(output.toByteArray()).containsExactly(wire);
    }

    @Test void AC3_allFifteenGoldenMessagesValidateAgainstSharedSchema() throws Exception {
        JsonSchema schema = sharedSchema();
        for (JsonNode golden : goldens())
            assertThat(schema.validate(golden)).as("Schema validation for %s", golden.path("t")).isEmpty();
    }

    @Test void AC3_schemaRejectsMissingTypeAndIncorrectEnvelopeVersion() throws Exception {
        JsonSchema schema = sharedSchema();
        var missing = JSON.createObjectNode().put("v", 1).put("ts", 0).set("body", JSON.createObjectNode());
        assertThat(schema.validate(missing)).isNotEmpty();
        var invalid = goldens().getFirst().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("v", "not-an-integer");
        assertThat(schema.validate(invalid)).isNotEmpty();
    }

    private static JsonSchema sharedSchema() throws Exception {
        Path schema = Path.of(System.getProperty("repo.root")).resolve("protocol/agent/v1/schema.json");
        assertThat(schema).as("Shared Agent Protocol v1 schema").isRegularFile();
        JsonNode document = JSON.readTree(schema.toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
    }

    private static List<JsonNode> goldens() throws Exception {
        var rows = new ArrayList<JsonNode>();
        try (var files = Files.list(goldenDirectory())) {
            for (Path file : files.filter(p -> p.getFileName().toString().equals("m0.ndjson")).toList()) {
                for (String line : Files.readAllLines(file)) rows.add(JSON.readTree(line));
            }
        }
        return rows;
    }

    static java.util.stream.Stream<Path> binaryGoldens() throws IOException {
        try (var files = Files.list(goldenDirectory())) {
            return files.filter(p -> p.toString().endsWith(".bin")).sorted().toList().stream();
        }
    }

    @Test void AC3_readableHexGoldenMatchesAndDecodesTheBinaryGolden() throws Exception {
        byte[] wire = HexFormat.of().parseHex(resource("/golden/audio.chunk.hex").strip());
        assertThat(wire).isEqualTo(Files.readAllBytes(goldenDirectory().resolve("audio.chunk.bin")));
        Frame frame = new FrameCodec().read(new ByteArrayInputStream(wire));
        assertThat(frame).isNotNull();
        assertThat(frame.kind()).isEqualTo(2);
        assertThat(frame.payload()).hasSize(32);
    }

    private static Path goldenDirectory() {
        return Path.of(System.getProperty("repo.root")).resolve("protocol/agent/v1/golden");
    }

    private static String resource(String path) throws IOException {
        Path file = Path.of(System.getProperty("repo.root")).resolve("protocol/agent/v1" + path);
        assertThat(file).as("Shared golden %s", path).isRegularFile();
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
