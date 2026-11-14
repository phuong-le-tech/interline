package app.interline.app;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static app.interline.app.AppTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

class BuildTest {
    @Test void AC1_verifyUsesJava21AndBuildsBothExecutableJars() {
        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(ROOT.resolve("services/core/mvnw")).isExecutable();
        assertThat(ROOT.resolve("services/core/app/target/interline-core.jar")).isRegularFile();
        assertThat(ROOT.resolve("services/core/mock-agent/target/mock-agent-0.1.0-SNAPSHOT.jar")).isRegularFile();
    }
}
