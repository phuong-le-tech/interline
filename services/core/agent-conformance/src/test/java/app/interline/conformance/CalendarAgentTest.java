package app.interline.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real pipes and processes; native status never requests TCC permission or reads real events. */
@Timeout(30)
class CalendarAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path work;

    @Test void mockAdvertisesCalendarAndQueriesScriptedEventsCorrelatedByRe() throws Exception {
        Path script = work.resolve("calendar.json");
        Files.writeString(script, """
            {"sample_rate":16000,"samples":0,"segments":[],"calendar":{"events":[
              {"external_id":"series/1000","title":"Planning","starts_at":1000,"ends_at":2000,
               "attendees":[{"name":"Alice","email":"alice@example.com"}],
               "conference_url":"https://meet.example.com/a","calendar_name":"Work"}]}}
            """);
        try (Peer p = launch("--script=" + script)) {
            JsonNode hello = p.next(3000);
            assertThat(hello.path("body").path("capabilities").path("calendar").asBoolean()).isTrue();
            p.send(null, "hello.ack", hello.path("id").asText(), obj().put("protocol", 1).put("core_version", "conformance"));
            JsonNode reply = p.call("calendar.query", obj().put("from_ms", 0).put("to_ms", 3000));
            assertThat(reply.path("t").asText()).isEqualTo("calendar.events");
            assertThat(reply.path("body").path("events")).isEqualTo(JSON.readTree(script.toFile()).path("calendar").path("events"));
            assertThat(p.call("calendar.query", obj().put("from_ms", 3000).put("to_ms", 4000))
                .path("body").path("events")).isEmpty();
        }
    }

    @Test void deniedCalendarReportsStatusAndReturnsEmptyEventsWithoutStoppingAgent() throws Exception {
        try (Peer p = connected("--deny=calendar")) {
            ObjectNode body = obj();
            body.putArray("permissions").add("calendar");
            JsonNode status = p.call("permission.status", body);
            assertThat(status.path("body").path("calendar").asText()).isEqualTo("denied");
            JsonNode reply = p.call("calendar.query", obj().put("from_ms", 0).put("to_ms", 3000));
            assertThat(reply.path("t").asText()).isEqualTo("calendar.events");
            assertThat(reply.path("body").path("events").isArray()).isTrue();
            assertThat(reply.path("body").path("events")).isEmpty();
            assertThat(p.process.isAlive()).isTrue();
        }
    }

    @Test @EnabledIfSystemProperty(named = "interline.swift-agent", matches = ".+")
    void nativeAdvertisesCalendarAndStatusQueryDoesNotPrompt() throws Exception {
        try (Peer p = new Peer(java.util.List.of(System.getProperty("interline.swift-agent"), "--protocol=1"))) {
            JsonNode hello = p.next(3000);
            assertThat(hello.path("body").path("capabilities").path("calendar").asBoolean()).isTrue();
            p.send(null, "hello.ack", hello.path("id").asText(), obj().put("protocol", 1).put("core_version", "conformance"));
            ObjectNode body = obj();
            body.putArray("permissions").add("calendar");
            JsonNode status = p.call("permission.status", body);
            assertThat(status.path("t").asText()).isEqualTo("permission.status");
            assertThat(status.path("body").path("calendar").asText())
                .isIn("granted", "denied", "not_determined", "restricted", "unknown");
        }
    }

    @Test @EnabledIfSystemProperty(named = "interline.swift-agent", matches = ".+")
    void nativeTestInputAnswersCalendarQueryWithoutReadingRealEvents() throws Exception {
        Path wav = work.resolve("silence.wav");
        ByteBuffer bytes = ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(38)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(2).putShort((short) 0);
        Files.write(wav, bytes.array());
        try (Peer p = nativeConnected("--test-input=" + wav)) {
            JsonNode reply = p.call("calendar.query", obj().put("from_ms", 0).put("to_ms", 3000));
            assertThat(reply.path("t").asText()).isEqualTo("calendar.events");
            assertThat(reply.path("body").path("events").isArray()).isTrue();
            assertThat(reply.path("body").path("events")).isEmpty();
            assertThat(reply.path("body").path("complete").asBoolean()).isTrue();
        }
    }

    @Test @Timeout(180)
    @EnabledIfSystemProperty(named = "interline.calendar.native", matches = "true")
    void manualRealEventKitReadWithPermissionPrompt() throws Exception {
        try (Peer p = nativeConnected()) {
            long now = System.currentTimeMillis();
            String id = "c-real-calendar";
            p.send(id, "calendar.query", null, obj().put("from_ms", now).put("to_ms", now + 86400000));
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (System.nanoTime() < deadline) {
                JsonNode reply = p.next(125000);
                if (!id.equals(reply.path("re").asText())) continue;
                assertThat(reply.path("t").asText()).isEqualTo("calendar.events");
                assertThat(reply.path("body").path("events").isArray()).isTrue();
                return;
            }
            throw new AssertionError("No calendar reply after permission prompt");
        }
    }

    private Peer nativeConnected(String... extra) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(System.getProperty("interline.swift-agent"));
        command.add("--protocol=1");
        command.addAll(java.util.List.of(extra));
        Peer p = new Peer(command);
        try {
            JsonNode hello = p.next(3000);
            p.send(null, "hello.ack", hello.path("id").asText(), obj().put("protocol", 1).put("core_version", "conformance"));
            return p;
        } catch (Throwable e) {
            p.close();
            throw e;
        }
    }

    private static ObjectNode obj() { return JSON.createObjectNode(); }

    private Peer connected(String... extra) throws Exception {
        Peer p = launch(extra);
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

    private Peer launch(String... extra) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(Path.of(System.getProperty("mock.agent.jar")).toAbsolutePath().toString());
        command.addAll(java.util.List.of(extra));
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
            Thread.ofPlatform().daemon().name("calendar-conformance-reader").start(() -> {
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
            String id = "c-calendar-" + (++counter);
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
