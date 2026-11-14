package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** AC11 tests the executable core, not Spring's in-process test bootstrap. */
class LifecycleTest {
    @TempDir Path data;

    @ParameterizedTest(name = "AC11 {0} while recording={1}")
    @CsvSource({"shutdown,false", "eof,false", "shutdown,true", "eof,true"})
    void AC11_stdinLifelineHasOneReadyAndExitsCleanlyWithinSixSeconds(String command, boolean recording) throws Exception {
        Path jar = ROOT.resolve("services/core/app/target/interline-core.jar");
        assertThat(jar).as("Build executable artifact with mvn package before running AC11").isRegularFile();
        Path wav = fixture(data);
        List<String> argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--data-dir=" + data, "--log-dir=" + data.resolve("logs"),
                "--agent=" + ROOT.resolve("services/core/mock-agent/bin/mock-agent"), "--parent=stdio"));
        for (String arg : agentArgs(wav, null)) argv.add("--agent-arg=" + arg);
        try (CoreChild child = new CoreChild(argv, data.resolve("stderr.log"))) {
            // A launch must not claim readiness before the shell supplies its token on stdin.
            await().pollInterval(Duration.ofMillis(20)).during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(child.stdout).isEmpty());
            assertThat(child.process.isAlive()).as("Core must wait for stdin token; stderr: %s", child.stderr()).isTrue();
            assertThatCode(() -> child.send("{\"token\":\"" + TOKEN + "\"}"))
                    .as("Core keeps its stdin open to accept the token").doesNotThrowAnyException();
            await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(20))
                    .until(() -> !child.stdout.isEmpty() || !child.process.isAlive());
            assertThat(child.stdout).as("Core must emit READY, stderr: %s", child.stderr()).hasSize(1);
            JsonNode ready = JSON.readTree(child.stdout.getFirst());
            Set<String> fields = new HashSet<>(); ready.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("event", "port", "pid", "version");
            assertThat(ready.path("event").asText()).isEqualTo("ready");
            assertThat(ready.path("pid").asLong()).isEqualTo(child.process.pid());
            assertThat(ready.path("version").asText()).isNotBlank();
            int port = ready.path("port").asInt(); assertThat(port).isBetween(1, 65535);
            child.snapshot();
            // READY does not wait for capture. Wait separately for the actual child agent to exist.
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                child.snapshot(); assertThat(child.descendants).as("Real capture agent must be launched").isNotEmpty();
            });
            if (recording) {
                try (Events events = new Events(port)) {
                    String id = request(port, "POST", "/recording/start").path("meetingId").asText();
                    assertThat(id).isNotBlank(); events.state(id, "recording"); child.snapshot();
                    shutdown(child, command);
                    assertThat(rows(data, "SELECT status FROM meetings WHERE id=?", id)).containsExactly(Map.of("status", "done"));
                    assertThat(rows(data, "SELECT ended_at,end_reason FROM capture_runs WHERE meeting_id=?", id))
                            .isNotEmpty().allSatisfy(run -> {
                                assertThat(run.get("ended_at")).isNotNull();
                                assertThat(run.get("end_reason")).isEqualTo("stopped");
                            });
                }
            } else shutdown(child, command);
            child.reader.join(1000);
            assertThat(child.stdout).as("No banners, logs or repeated READY on stdout").hasSize(1);
            assertThat(child.process.exitValue()).as("Normal lifeline exit; stderr: %s", child.stderr()).isZero();
            assertThat(child.descendants).noneMatch(ProcessHandle::isAlive);
        }
    }

    private static void shutdown(CoreChild child, String command) throws Exception {
        child.snapshot();
        long began = System.nanoTime();
        if (command.equals("eof")) child.stdin.close(); else child.send("{\"cmd\":\"shutdown\"}");
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            child.snapshot();
            assertThat(child.process.isAlive()).isFalse();
            assertThat(child.descendants).noneMatch(ProcessHandle::isAlive);
        });
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(6));
    }

    private static final class CoreChild implements AutoCloseable {
        final Process process;
        final BufferedWriter stdin;
        final List<String> stdout = new CopyOnWriteArrayList<>();
        final Set<ProcessHandle> descendants = new HashSet<>();
        final Thread reader;
        final Path errors;
        CoreChild(List<String> argv, Path errors) throws IOException {
            this.errors = errors;
            process = new ProcessBuilder(argv).redirectError(errors.toFile()).start();
            stdin = process.outputWriter(StandardCharsets.UTF_8);
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = process.inputReader(StandardCharsets.UTF_8)) { String line; while ((line = lines.readLine()) != null) stdout.add(line); }
                catch (IOException ignored) { /* Process teardown closes the pipe. */ }
            });
        }
        void send(String line) throws IOException { stdin.write(line); stdin.newLine(); stdin.flush(); }
        void snapshot() { process.descendants().forEach(descendants::add); }
        String stderr() throws IOException { return Files.readString(errors); }
        @Override public void close() throws Exception {
            snapshot();
            try { stdin.close(); } catch (IOException ignored) { }
            descendants.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            await().atMost(Duration.ofSeconds(3)).until(() -> !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive));
            reader.join(1000);
        }
    }
}
