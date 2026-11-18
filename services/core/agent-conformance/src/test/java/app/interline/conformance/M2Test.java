package app.interline.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC19: the agent's secret store, black box over the real executable and OS pipes. The default subject is the
 * packaged MockAgent (in-memory store); the swift-agent profile points the same checks at the Keychain-backed agent.
 */
@Timeout(40)
class M2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "provider.conformance-1";
    private static final String KEY = "sk-test-conformance-0001";
    @TempDir Path work;

    @Test void AC19_mockSecret_putThenGetReturnsTheValueAndPutOverwrites() throws Exception {
        try (Peer p = connected()) {
            JsonNode stored = p.call("secret.put", obj().put("account", ACCOUNT).put("value", KEY));
            assertThat(stored.path("t").asText()).isEqualTo("secret.put");
            assertThat(p.call("secret.get", obj().put("account", ACCOUNT)).path("body").path("value").asText()).isEqualTo(KEY);

            p.call("secret.put", obj().put("account", ACCOUNT).put("value", "sk-test-conformance-0002"));
            assertThat(p.call("secret.get", obj().put("account", ACCOUNT)).path("body").path("value").asText())
                    .isEqualTo("sk-test-conformance-0002");
            p.call("secret.delete", obj().put("account", ACCOUNT));
        }
    }

    @Test void AC19_mockSecret_getOfUnknownAccountIsSecretNotFound() throws Exception {
        try (Peer p = connected()) {
            JsonNode reply = p.call("secret.get", obj().put("account", "provider.never-stored"));

            assertThat(reply.path("t").asText()).isEqualTo("error");
            assertThat(reply.path("body").path("code").asText()).isEqualTo("secret_not_found");
        }
    }

    @Test void AC19_mockSecret_deleteRemovesTheValue() throws Exception {
        try (Peer p = connected()) {
            p.call("secret.put", obj().put("account", ACCOUNT).put("value", KEY));

            JsonNode deleted = p.call("secret.delete", obj().put("account", ACCOUNT));
            JsonNode after = p.call("secret.get", obj().put("account", ACCOUNT));

            assertThat(deleted.path("t").asText()).isEqualTo("secret.delete");
            assertThat(after.path("body").path("code").asText()).isEqualTo("secret_not_found");
        }
    }

    @Test void AC19_mockSecret_neverWritesTheValueToStderr() throws Exception {
        Path stderr;
        try (Peer p = connected()) {
            stderr = p.stderr;
            assertThat(p.call("secret.put", obj().put("account", ACCOUNT).put("value", KEY)).path("t").asText())
                    .isEqualTo("secret.put");
            p.call("secret.get", obj().put("account", ACCOUNT));
            p.call("secret.delete", obj().put("account", ACCOUNT));
        }
        assertThat(Files.readString(stderr)).doesNotContain(KEY);
    }

    private static ObjectNode obj() { return JSON.createObjectNode(); }

    private Peer connected() throws Exception {
        Peer p = launch();
        try {
            JsonNode hello = p.next(3000);
            assertThat(hello.path("t").asText()).isEqualTo("hello");
            p.send(null, "hello.ack", hello.path("id").asText(), obj().put("protocol", 1).put("core_version", "conformance"));
            return p;
        } catch (Throwable e) {
            p.close();
            throw e;
        }
    }

    private Peer launch() throws Exception {
        String executable = System.getProperty("interline.conformance.agent", "").trim();
        java.util.List<String> command = new java.util.ArrayList<>();
        if (executable.isEmpty()) {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar");
            command.add(Path.of(System.getProperty("mock.agent.jar")).toAbsolutePath().toString());
        } else {
            command.add(executable);
        }
        command.add("--protocol=1");
        command.add("--log-level=info");
        return new Peer(command);
    }

    private final class Peer implements AutoCloseable {
        final Process process;
        final OutputStream out;
        final Path stderr = work.resolve("agent-stderr.log");
        final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        private int counter;

        Peer(java.util.List<String> command) throws Exception {
            process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            out = process.getOutputStream();
            Thread.ofPlatform().daemon().name("m2-conformance-reader").start(() -> {
                try (DataInputStream in = new DataInputStream(process.getInputStream())) {
                    while (true) {
                        int length = in.readInt();
                        int kind = in.readUnsignedByte();
                        byte[] bytes = in.readNBytes(length - 1);
                        if (kind == 1) incoming.add(JSON.readTree(bytes));
                    }
                } catch (Throwable e) {
                    incoming.add(e);
                }
            });
        }

        JsonNode next(long ms) throws Exception {
            Object value = incoming.poll(ms, TimeUnit.MILLISECONDS);
            assertThat(value).as("agent frame within %d ms", ms).isNotNull();
            if (value instanceof Throwable t) throw new AssertionError("agent stdout closed", t);
            return (JsonNode) value;
        }

        /** Send a request and return the reply carrying its id, skipping heartbeats. */
        JsonNode call(String type, ObjectNode body) throws Exception {
            String id = "c-m2-" + (++counter);
            send(id, type, null, body);
            while (true) {
                JsonNode reply = next(3000);
                if (id.equals(reply.path("re").asText())) return reply;
            }
        }

        void send(String id, String type, String re, ObjectNode body) throws Exception {
            ObjectNode envelope = obj().put("v", 1).put("t", type).put("ts", System.currentTimeMillis());
            if (id != null) envelope.put("id", id);
            if (re != null) envelope.put("re", re);
            envelope.set("body", body);
            byte[] json = JSON.writeValueAsBytes(envelope);
            out.write(ByteBuffer.allocate(json.length + 5).putInt(json.length + 1).put((byte) 1).put(json).array());
            out.flush();
        }

        @Override public void close() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).as("child reaped after each test").isTrue();
        }
    }
}
