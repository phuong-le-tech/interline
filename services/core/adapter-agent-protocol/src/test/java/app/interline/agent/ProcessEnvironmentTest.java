package app.interline.agent;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessEnvironmentTest {
    @Test void agentLaunchStripsJvmAndDynamicLoaderInjection() {
        var environment = new HashMap<>(Map.of("JAVA_TOOL_OPTIONS", "bad", "JDK_JAVA_OPTIONS", "bad",
                "_JAVA_OPTIONS", "bad", "DYLD_INSERT_LIBRARIES", "bad", "DYLD_LIBRARY_PATH", "bad", "PATH", "kept"));
        AgentPlatformCapabilities.sanitizeEnvironment(environment);
        assertThat(environment).containsExactlyEntriesOf(Map.of("PATH", "kept"));
    }
}
