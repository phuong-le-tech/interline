package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The shell's pipe to the core. The first line carries the token; afterwards a shutdown command or the end of
 * the stream (the shell died) means "exit now", which works even when the shell was killed.
 */
final class Lifeline {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BufferedReader lines;

    Lifeline(InputStream input) {
        this.lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /** Block until the shell sends {@code {"token":"..."}}. */
    String readToken() throws IOException {
        String line = lines.readLine();
        if (line == null) throw new IOException("stdin closed before the token was supplied");
        JsonNode message = JSON.readTree(line);
        String token = message.path("token").asText("");
        if (token.isEmpty()) throw new IOException("first stdin line must be {\"token\":\"...\"}");
        return token;
    }

    /** Run {@code onExit} once when the shell asks for shutdown or goes away. */
    void watch(Runnable onExit) {
        Thread.ofPlatform().name("lifeline").daemon().start(() -> {
            try {
                String line;
                while ((line = lines.readLine()) != null) {
                    if ("shutdown".equals(commandOf(line))) break;
                }
            } catch (IOException e) {
                // A broken pipe means the shell is gone, which is the same as asking for shutdown.
            }
            onExit.run();
        });
    }

    private static String commandOf(String line) {
        try {
            return JSON.readTree(line).path("cmd").asText("");
        } catch (IOException e) {
            return "";
        }
    }
}
