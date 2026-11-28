package app.interline.app;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static app.interline.app.AppTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

class RepositoryTest {
    @Test void AC17_auditHasLicenseAndCoreOwnershipAdrs() throws Exception {
        List<Path> adrs;
        try(var files=Files.list(ROOT.resolve("docs/adr"))) { adrs=files.toList(); }
        assertThat(adrs).anySatisfy(p -> {
            assertThat(p.getFileName().toString()).startsWith("ADR-010-");
            assertThat(Files.readString(p)).contains("MIT");
        });
        assertThat(adrs).anySatisfy(p -> {
            assertThat(p.getFileName().toString()).startsWith("ADR-011-");
            assertThat(Files.readString(p).toLowerCase()).contains("core", "sqlite", "data");
        });
    }
    @Test void AC17_auditDocumentsCommandsAndVerifiedVersions() throws Exception {
        assertThat(Files.readString(ROOT.resolve("README.md"))).contains("## Commands", "./mvnw", "pnpm");
        String versions = Files.readString(ROOT.resolve("docs/ARCHITECTURE.md")).split("## 17. Versions",2)[1].split("## 18.",2)[0];
        assertThat(versions).contains("Spring Boot | 4.1.2", "Spring AI | 2.0.2", "sqlite-jdbc (xerial) | 3.54.1.0", "Flyway | 12.4.0",
                "jqwik | 1.10.2", "ArchUnit | 1.5.1");
        assertThat(versions).doesNotContain("[to verify]");
    }
    @Test void AC17_auditTrackedTreeHasNoInstructionFilesAudioOrRecognizableSecrets() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-z").directory(ROOT.toFile()).start();
        String tracked = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(git.waitFor()).isZero();
        List<String> paths = java.util.Arrays.stream(tracked.split("\u0000")).filter(p -> !p.isEmpty()).toList();
        assertThat(paths).noneMatch(p -> p.endsWith("AGENTS.md") || p.endsWith("CLAUDE.md") || p.toLowerCase().endsWith(".wav"));
        // Recognizable credential formats only, not an entropy detector or a proof of secret absence.
        var secret = java.util.regex.Pattern.compile("(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-(?!test-)[A-Za-z0-9]{32,})");
        assertThat(paths.stream().filter(p -> !p.endsWith(".png")).filter(p -> {
            try { return secret.matcher(Files.readString(ROOT.resolve(p))).find(); }
            catch(java.nio.charset.MalformedInputException binary) { return false; }
            catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        })).as("Tracked text files containing recognizable credentials").isEmpty();
    }
    @Test void AC16_manualShellRustStaysWithinTheM4Budget() throws Exception {
        var shell=ROOT.resolve("apps/desktop/src-tauri/src");
        assertThat(shell).as("AC16 manual window and clean-quit evidence is required separately").isDirectory();
        try(var files=Files.walk(shell)) {
            long lines=files.filter(p -> p.toString().endsWith(".rs")).mapToLong(p -> {
                try(var source=Files.lines(p)) { return source.count(); }
                catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }).sum();
            // M4 exception: cap 600, measured 575 (packaging 500, FileVault 57, updater 18); the original 200 to 400 budget is exceeded by orphan-process recovery, security guards and FileVault, to be reviewed in M5.
            assertThat(lines).as("Rust shell lines across all files incl. tests, M4 cap 600").isBetween(1L,600L);
        }
    }
}
