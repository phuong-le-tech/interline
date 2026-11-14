package app.interline.app;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.util.Map;
import static app.interline.app.AppTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTest {
    @Test void AC15_jobsRunCoreAndUiOnUbuntu2404() throws Exception {
        var file = ROOT.resolve(".github/workflows/ci.yml");
        assertThat(file).as("Required PR workflow").isRegularFile();
        Map<String,Object> document = new Yaml().load(Files.readString(file));
        Map<?,?> jobs = (Map<?,?>) document.get("jobs");
        assertThat(jobs.containsKey("core")).isTrue();
        assertThat(jobs.containsKey("ui")).isTrue();
        assertThat(((Map<?,?>)jobs.get("core")).get("runs-on")).isEqualTo("ubuntu-24.04");
        assertThat(((Map<?,?>)jobs.get("ui")).get("runs-on")).isEqualTo("ubuntu-24.04");
        String source = Files.readString(file);
        assertThat(source).contains("pull_request:", "./mvnw", "verify", "pnpm lint", "pnpm typecheck", "pnpm test:e2e");
    }
    @Test void AC15_releaseKeepsThreePlatformPlaceholderJobs() throws Exception {
        var file = ROOT.resolve(".github/workflows/release.yml");
        assertThat(file).as("Release skeleton").isRegularFile();
        Map<String,Object> document = new Yaml().load(Files.readString(file));
        Map<?,?> jobs = (Map<?,?>)document.get("jobs");
        assertThat(jobs.containsKey("macos")).isTrue();
        assertThat(jobs.containsKey("windows")).isTrue();
        assertThat(jobs.containsKey("linux")).isTrue();
    }
}
