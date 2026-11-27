package app.interline.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessRegistryTest {
    @TempDir Path data;

    @Test void recycledPidAndDifferentExecutableAreNeverKilled() throws Exception {
        Process child = owner();
        try {
            var identity = ProcessRegistry.identity(child.toHandle());
            assertThat(ProcessRegistry.killMatching(new ProcessRegistry.Identity(identity.pid(),
                    identity.startTime() + 1, identity.executable()))).isFalse();
            assertThat(ProcessRegistry.killMatching(new ProcessRegistry.Identity(identity.pid(),
                    identity.startTime(), "/different/executable"))).isFalse();
            assertThat(child.isAlive()).isTrue();
            assertThat(ProcessRegistry.killMatching(identity)).isTrue();
            assertThat(child.waitFor(2, TimeUnit.SECONDS)).isTrue();
        } finally { child.destroyForcibly(); }
    }

    @Test void registryTracksCoreAndAgentAndRejectsConcurrentCore() throws Exception {
        try (var registry = new ProcessRegistry(data)) {
            Process child = owner();
            try {
                registry.agent(child);
                var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data.resolve("run/pids.json").toFile());
                assertThat(saved.path("core").path("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
                assertThat(saved.path("agent").path("pid").asLong()).isEqualTo(child.pid());
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProcessRegistry(data))
                        .isInstanceOf(java.io.IOException.class);
            } finally { child.destroyForcibly(); child.waitFor(); }
        }
        assertThat(data.resolve("run/pids.json")).doesNotExist();
    }

    @Test void deadShellOwnerAllowsCoreToReapLockedOrphan() throws Exception {
        Process orphan = owner(data.toString());
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            json.writeValue(data.resolve("run/pids.json").toFile(),
                    new ProcessRegistry.Snapshot(null, ProcessRegistry.identity(orphan.toHandle()), null));
            try (var registry = new ProcessRegistry(data)) {
                assertThat(orphan.waitFor(2, TimeUnit.SECONDS)).isTrue();
            }
        } finally { orphan.destroyForcibly(); orphan.waitFor(); }
    }

    private static Process owner(String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Owner.class.getName()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).start();
        assertThat(process.getInputStream().read()).isEqualTo(82);
        return process;
    }

    public static class Owner {
        public static void main(String[] args) throws Exception {
            try (var registry = args.length == 0 ? null : new ProcessRegistry(Path.of(args[0]))) {
                System.out.write(82); System.out.flush();
                Thread.sleep(30_000);
            }
        }
    }
}
