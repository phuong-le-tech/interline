package app.interline.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class HandshakeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test @Timeout(8)
    void AC4_protocolTwoMockAgentIsRejectedOnWireAndExitsThree() throws Exception {
        Path jar = Path.of(System.getProperty("mock.agent.jar", "../mock-agent/target/mock-agent-0.1.0-SNAPSHOT.jar"));
        assertThat(jar).as("Build the MockAgent jar before running this contract test").isRegularFile();
        Process agent = new ProcessBuilder(java(), "-jar", jar.toAbsolutePath().toString(), "--protocol=2", "--log-level=info")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            // Production handshake must read hello and write the rejection over the actual child pipes.
            var connection = new AgentConnection(agent.getInputStream(), agent.getOutputStream());
            ControlMessage response = connection.handshake(1, 1, "0.1.0");
            assertThat(response).isNotNull();
            assertThat(response.type()).isEqualTo("hello.reject");
            assertThat(response.replyTo()).isEqualTo("a-1");
            assertThat(response.body().path("reason").asText()).isEqualTo("protocol_version_unsupported");
            assertThat(response.body().path("core_supports").path("min").asInt()).isEqualTo(1);
            assertThat(response.body().path("core_supports").path("max").asInt()).isEqualTo(1);
            assertThat(agent.waitFor(2, TimeUnit.SECONDS)).as("Rejected agent exits within two seconds").isTrue();
            assertThat(agent.exitValue()).isEqualTo(3);
        } finally { agent.destroyForcibly(); agent.waitFor(2, TimeUnit.SECONDS); }
    }

    @Test void AC4_rejectionIsActuallyWrittenAndCorrelatedOnTransport() throws Exception {
        var hello = JSON.readTree("{\"v\":2,\"t\":\"hello\",\"id\":\"a-version-probe\",\"ts\":0,\"body\":{\"protocol\":{\"min\":2,\"max\":2},\"agent\":{\"name\":\"probe\",\"version\":\"0.1.0\",\"os\":\"linux\",\"os_version\":\"test\",\"arch\":\"x86_64\"},\"capabilities\":{}}}");
        var output = new ByteArrayOutputStream();
        byte[] payload = JSON.writeValueAsBytes(hello);
        byte[] wireHello = ByteBuffer.allocate(5 + payload.length).putInt(1 + payload.length).put((byte)1).put(payload).array();
        new AgentConnection(new ByteArrayInputStream(wireHello), output)
                .handshake(1, 1, "0.1.0");
        byte[] wire = output.toByteArray();
        assertThat(wire).hasSizeGreaterThan(5);
        ByteBuffer frame = ByteBuffer.wrap(wire);
        int length = frame.getInt();
        assertThat(length).isEqualTo(wire.length - 4);
        assertThat(frame.get()).isEqualTo((byte)1);
        var response = JSON.readTree(wire, 5, wire.length - 5);
        assertThat(response.path("t").asText()).isEqualTo("hello.reject");
        assertThat(response.path("re").asText()).isEqualTo("a-version-probe");
        assertThat(response.path("body").path("reason").asText()).isEqualTo("protocol_version_unsupported");
        assertThat(response.path("body").path("core_supports")).isEqualTo(JSON.readTree("{\"min\":1,\"max\":1}"));
    }

    static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
}
