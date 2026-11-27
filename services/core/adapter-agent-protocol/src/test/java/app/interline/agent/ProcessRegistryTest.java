package app.interline.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessRegistryTest {
    @TempDir Path data;

    @Test void recycledPidAndDifferentExecutableAreNeverKilled() throws Exception {
        Process child = new ProcessBuilder("/bin/sleep", "30").start();
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
            Process child = new ProcessBuilder("/bin/sleep", "30").start();
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
}
