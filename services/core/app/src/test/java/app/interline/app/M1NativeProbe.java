package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Test-only framing tee. The executable is an argument, never a mock or a replacement protocol producer. */
public final class M1NativeProbe implements AutoCloseable {
    private final Path dir;
    M1NativeProbe(Path dir) { this.dir = dir; }

    static Path launcher(Path dir, Path executable, long killSample) throws IOException {
        Files.createDirectories(dir);
        Path script = dir.resolve("native-tee");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(script, "#!/bin/sh\nexec " + quote(Path.of(System.getProperty("java.home"), "bin/java").toString())
                + " -cp " + quote(cp) + " " + M1NativeProbe.class.getName() + " " + quote(dir.toString())
                + " " + killSample + " " + quote(executable.toString()) + " \"$@\"\n");
        if (!script.toFile().setExecutable(true, true)) throw new IOException("Cannot make native tee executable");
        return script;
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    List<JsonNode> observations() throws IOException {
        List<JsonNode> result = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("native-observed-")).toList()) {
                String text = Files.readString(file);
                int end = text.lastIndexOf('\n');
                if (end >= 0) for (String line : text.substring(0, end).lines().toList())
                    result.add(AppTestSupport.JSON.readTree(line));
            }
        }
        result.sort(Comparator.comparingLong(n -> n.path("nano").asLong()));
        return result;
    }
    @Override public void close() throws Exception {
        for (JsonNode o : observations()) if (o.path("type").asText().equals("launch")) {
            ProcessHandle.of(o.path("tee_pid").asLong()).filter(ProcessHandle::isAlive)
                    .filter(p -> p.info().commandLine().orElse("").contains(dir.toString())).ifPresent(p -> {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    });
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        long checkpoint = Long.parseLong(args[1]);
        Process child = new ProcessBuilder(Arrays.asList(args).subList(2, args.length))
                .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("native-stderr.log").toFile())).start();
        long tee = ProcessHandle.current().pid();
        Path log = dir.resolve("native-observed-" + tee + ".jsonl");
        write(log, Map.of("type", "launch", "pid", child.pid(), "tee_pid", tee, "nano", System.nanoTime(), "executable", args[2]));
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroyForcibly));
        try {
            Thread input = Thread.ofVirtual().start(() -> {
                try { copy(System.in, child.getOutputStream(), log, "core", child, dir, -1); }
                catch (Exception e) { child.destroyForcibly(); }
                finally { try { child.getOutputStream().close(); } catch (IOException ignored) { } }
            });
            copy(child.getInputStream(), System.out, log, "agent", child, dir, checkpoint);
            int exit = child.waitFor();
            write(log, Map.of("type", "exit", "pid", child.pid(), "nano", System.nanoTime(), "code", exit));
            input.interrupt();
            // A JVM with a thread blocked on stdin takes ~2 s to exit on macOS and holds the stdout pipe open that
            // long, so the supervisor would see the dead agent late. SIGKILL ourselves instead of exiting.
            new ProcessBuilder("kill", "-9", Long.toString(tee)).start().waitFor();
            Runtime.getRuntime().halt(exit);
        } finally { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
    }

    private static void copy(InputStream source, OutputStream target, Path log, String direction,
                             Process child, Path dir, long checkpoint) throws Exception {
        DataInputStream in = new DataInputStream(source);
        while (true) {
            int length;
            try { length = in.readInt(); } catch (EOFException eof) { return; }
            if (length < 1 || length > 1_048_577) throw new IOException("Invalid observed frame length " + length);
            int kind = in.readUnsignedByte();
            byte[] payload = in.readNBytes(length - 1);
            if (payload.length != length - 1) throw new EOFException("Truncated observed frame");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("direction", direction); o.put("pid", child.pid()); o.put("nano", System.nanoTime());
            if (kind == 1) {
                JsonNode message = AppTestSupport.JSON.readTree(payload);
                o.put("type", message.path("t").asText()); o.put("message", message);
            } else if (kind == 2) {
                if (payload.length < 24 || (payload.length - 24) % 2 != 0) throw new IOException("Invalid audio header/PCM");
                ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                o.put("type", "audio.chunk"); o.put("stream", List.of("mic", "system").get(Byte.toUnsignedInt(b.get())));
                o.put("flags", Byte.toUnsignedInt(b.get())); b.getShort();
                o.put("seq", Integer.toUnsignedLong(b.getInt())); long first = b.getLong();
                o.put("first_sample", first); o.put("samples", (payload.length - 24) / 2);
                o.put("pcm_sha256", hash(Arrays.copyOfRange(payload, 24, payload.length)));
                // Drop exactly one already-produced frame before delivery/ack, making replay observable.
                // Atomic marker survives tee restarts. Kill is at a sample checkpoint, never a polling sleep.
                if (checkpoint >= 0 && first >= checkpoint && o.get("stream").equals("mic")) {
                    try {
                        Files.createFile(dir.resolve("kill-once"));
                        o.put("type", "kill"); o.put("nano", System.nanoTime());
                        o.put("sigkill", child.destroyForcibly().pid() == child.pid());
                        write(log, o);
                        return;
                    } catch (FileAlreadyExistsException alreadyKilled) { /* restart must pass frames unchanged */ }
                }
            } else throw new IOException("Unknown observed kind " + kind);
            frame(target, kind, payload);
            write(log, o);
        }
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static synchronized void write(Path log, Object value) throws IOException {
        Files.writeString(log, AppTestSupport.JSON.writeValueAsString(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void frame(OutputStream out, int kind, byte[] payload) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length + 1); data.writeByte(kind); data.write(payload); data.flush();
    }
}
