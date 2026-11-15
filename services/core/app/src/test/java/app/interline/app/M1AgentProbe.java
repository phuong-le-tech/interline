package app.interline.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The M0 stdio tee with one difference: it dies the instant it would exit.
 *
 * <p>A JVM with a thread blocked reading stdin needs about two seconds to exit on macOS, and keeps its stdout pipe
 * open for that long. The core would then see a dead agent two seconds late and the restart gap would measure the
 * tee, not the product. A shutdown hook SIGKILLs the tee as soon as it starts exiting; everything else is
 * {@link PipelineAgentProbe}, which stays untouched.
 */
public final class M1AgentProbe {
    private M1AgentProbe() { }

    static Path launcher(Path dir, boolean fault) {
        try {
            Path script = dir.resolve("observing-mock-agent");
            String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Files.writeString(script, "#!/bin/sh\nexec " + quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                    + " -cp " + quote(cp) + " " + M1AgentProbe.class.getName() + " " + quote(dir.toString())
                    + " " + fault + " \"$@\"\n");
            if (!script.toFile().setExecutable(true, true)) throw new IOException("Cannot make launcher executable");
            return script;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    public static void main(String[] args) throws Exception {
        long self = ProcessHandle.current().pid();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { new ProcessBuilder("kill", "-9", Long.toString(self)).start().waitFor(); } catch (Exception ignored) { }
        }));
        PipelineAgentProbe.main(args);
    }
}
