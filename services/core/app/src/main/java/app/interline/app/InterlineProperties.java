package app.interline.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Everything the shell or a test can configure. The token is never read from argv or the environment:
 * the executable receives it on stdin and hands it over as {@code interline.token}.
 */
@ConfigurationProperties("interline")
public record InterlineProperties(
        String token,
        Path dataDir,
        Path logDir,
        String agent,
        @DefaultValue List<String> agentArgs,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("2s") Duration agentHeartbeatInterval,
        String parent,
        @DefaultValue Echo echo,
        @DefaultValue Stt stt,
        @DefaultValue Models models,
        @DefaultValue Calendar calendar) {

    /** {@code aec}: forces the echo threshold for tests; unset means "ask the agent's capabilities". */
    public record Echo(Boolean aec) { }

    /** {@code language}: default STT language; {@code "auto"} means detect (sent as JSON {@code null}). */
    public record Stt(@DefaultValue("fr") String language) { }

    /** {@code catalog}: a catalog file replacing the bundled one, for tests against a local origin. */
    public record Models(Path catalog) { }

    /**
     * {@code allowLoopbackHttp}: test and QA only, lets the ICS fetcher use {@code http://127.0.0.1}.
     * {@code zone}: IANA id for floating and unknown-zone times; unset means the system zone.
     */
    public record Calendar(@DefaultValue("false") boolean allowLoopbackHttp, String zone) { }

    /** The constructor Spring binds when the class has several. */
    @ConstructorBinding
    public InterlineProperties { }

    /** Without calendar settings (callers that predate M3). */
    public InterlineProperties(String token, Path dataDir, Path logDir, String agent, List<String> agentArgs,
                               List<String> allowedOrigins, Duration agentHeartbeatInterval, String parent,
                               Echo echo, Stt stt, Models models) {
        this(token, dataDir, logDir, agent, agentArgs, allowedOrigins, agentHeartbeatInterval, parent, echo, stt, models,
                new Calendar(false, null));
    }

    public boolean hasAgent() { return agent != null && !agent.isBlank(); }

    public boolean parentIsStdio() { return "stdio".equals(parent); }
}
