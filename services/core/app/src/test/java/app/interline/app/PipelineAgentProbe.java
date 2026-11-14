package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test-only stdio tee. Never supplies capture, persistence, HTTP or SSE behavior. */
public final class PipelineAgentProbe implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path dir;
    PipelineAgentProbe(Path dir) { this.dir = dir; }

    static Path launcher(Path dir, boolean fault) {
        try {
            Path script = dir.resolve("observing-mock-agent");
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Files.writeString(script, "#!/bin/sh\nexec " + quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                    + " -cp " + quote(cp) + " " + PipelineAgentProbe.class.getName() + " " + quote(dir.toString())
                    + " " + fault + " \"$@\"\n");
            if (!script.toFile().setExecutable(true, true)) throw new IOException("Cannot make launcher executable");
            return script;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    List<JsonNode> observations() throws IOException {
        List<JsonNode> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().startsWith("probe-")).sorted().toList()) {
                String content = Files.readString(p);
                int complete = content.lastIndexOf('\n');
                if (complete < 0) continue;
                for (String line : content.substring(0, complete).lines().toList()) {
                    if (!line.isBlank()) result.add(JSON.readTree(line));
                }
            }
        }
        return result;
    }
    @Override public void close() throws Exception {
        for (JsonNode observation : observations()) {
            if (observation.path("type").asText().equals("launch")) {
                ProcessHandle.of(observation.path("pid").asLong()).filter(ProcessHandle::isAlive)
                        .filter(p -> p.info().commandLine().orElse("").contains(dir.toString())).ifPresent(p -> {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long pid = ProcessHandle.current().pid();
        Path log = dir.resolve("probe-" + System.currentTimeMillis() + "-" + pid + ".jsonl");
        write(log, Map.of("type", "launch", "pid", pid, "nano", System.nanoTime()));
        if (Boolean.parseBoolean(args[1])) {
            JsonNode hello = JSON.readTree(Files.readAllLines(AppTestSupport.ROOT.resolve("protocol/agent/v1/golden/m0.ndjson")).getFirst());
            frame(System.out, 1, JSON.writeValueAsBytes(hello));
            // A genuine silent process: no --hang-at flag, synthetic heartbeat or fake core event.
            // Core must terminate it after three missed intervals, not rely on spontaneous EOF.
            copy(System.in, OutputStream.nullOutputStream(), log, "core", dir, new ConcurrentHashMap<>(), new String[]{""});
            return;
        }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                AppTestSupport.ROOT.resolve("services/core/mock-agent/target/mock-agent-0.1.0-SNAPSHOT.jar").toString()));
        command.addAll(Arrays.asList(args).subList(2, args.length));
        Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroyForcibly));
        Map<String, byte[]> audio = new ConcurrentHashMap<>();
        String[] meeting = {""};
        try {
            Thread input = Thread.ofVirtual().start(() -> {
                try { copy(System.in, child.getOutputStream(), log, "core", dir, audio, meeting); }
                catch (Exception e) { child.destroyForcibly(); }
                finally { try { child.getOutputStream().close(); } catch (IOException ignored) { } }
            });
            copy(child.getInputStream(), System.out, log, "agent", dir, audio, meeting);
            child.waitFor(); input.interrupt();
            System.exit(child.exitValue());
        } finally { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
    }

    private static void copy(InputStream source, OutputStream target, Path log, String direction, Path dir,
                             Map<String, byte[]> audio, String[] meeting) throws Exception {
        DataInputStream in = new DataInputStream(source);
        while (true) {
            int length;
            try { length = in.readInt(); } catch (EOFException eof) { return; }
            if (length < 1 || length > 1048577) throw new IOException("Invalid observed frame length " + length);
            int kind = in.readUnsignedByte();
            byte[] payload = in.readNBytes(length - 1);
            if (payload.length != length - 1) throw new EOFException("Truncated observed frame");
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("direction", direction); observation.put("pid", ProcessHandle.current().pid());
            observation.put("nano", System.nanoTime());
            if (kind == 1) {
                JsonNode message = JSON.readTree(payload);
                observation.put("type", message.path("t").asText()); observation.put("message", message);
                if (message.path("t").asText().equals("session.start")) meeting[0] = message.path("body").path("session_id").asText();
                if (message.path("t").asText().equals("audio.ack")) {
                    JsonNode body = message.path("body");
                    String stream = body.path("stream").asText();
                    long seq = body.path("seq").asLong();
                    byte[] captured = audio.get(stream + ":" + seq);
                    observation.put("durable", durable(dir, meeting[0], stream, captured));
                }
            } else if (kind == 2) {
                ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                String stream = List.of("mic", "system").get(Byte.toUnsignedInt(b.get()));
                int flags = Byte.toUnsignedInt(b.get()); b.getShort();
                long seq = Integer.toUnsignedLong(b.getInt());
                observation.put("type", "audio.chunk"); observation.put("stream", stream);
                observation.put("seq", seq); observation.put("flags", flags); observation.put("first_sample", b.getLong());
                audio.put(stream + ":" + seq, payload);
            }
            write(log, observation);
            frame(target, kind, payload);
        }
    }
    private static boolean durable(Path dir, String meeting, String stream, byte[] payload) throws IOException {
        if (payload == null) return false;
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long sample = b.getLong(8);
        int remaining = payload.length - 24;
        int offset = 24;
        while (remaining > 0) {
            Path file = dir.resolve("audio").resolve(meeting).resolve(stream).resolve(sample / 160000 + ".pcm");
            int count = (int) Math.min(remaining, (160000 - sample % 160000) * 2);
            if (!Files.exists(file)) return false;
            ByteBuffer bytes = ByteBuffer.allocate(count);
            try (FileChannel channel = FileChannel.open(file)) {
                channel.position(sample % 160000 * 2);
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            }
            if (!Arrays.equals(bytes.array(), Arrays.copyOfRange(payload, offset, offset + count)) || bytes.hasRemaining()) return false;
            remaining -= count; offset += count; sample += count / 2;
        }
        return true;
    }
    private static synchronized void write(Path log, Object value) throws IOException {
        Files.writeString(log, JSON.writeValueAsString(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void frame(OutputStream out, int kind, byte[] payload) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length + 1); data.writeByte(kind); data.write(payload); data.flush();
    }
}
