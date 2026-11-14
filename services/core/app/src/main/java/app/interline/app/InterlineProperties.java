package app.interline.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
        String parent) {

    public boolean hasAgent() { return agent != null && !agent.isBlank(); }

    public boolean parentIsStdio() { return "stdio".equals(parent); }
}
