package app.interline.app;

import app.interline.agent.AgentPlatformCapabilities;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Wires the core: database ingress, recording use case, and the capture agent's lifecycle. */
@Configuration
@EnableConfigurationProperties(InterlineProperties.class)
class CoreConfiguration {
    private static final String DEV_VERSION = "dev";

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close")
    DurableCaptureIngest ingest(InterlineProperties properties) {
        return new DurableCaptureIngest(properties.dataDir());
    }

    @Bean RecordingService recordingService(DurableCaptureIngest ingest, EventBus events, Clock clock,
                                            InterlineProperties properties) {
        return new RecordingService(ingest, events, clock, properties.dataDir());
    }

    @Bean String coreVersion(ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        return properties == null ? DEV_VERSION : properties.getVersion();
    }

    /** Starts the agent after the context is up and stops recording, then the agent, on the way down. */
    @Bean SmartLifecycle captureLifecycle(InterlineProperties properties, RecordingService recording, Clock clock,
                                          String coreVersion) {
        return new SmartLifecycle() {
            private AgentPlatformCapabilities capture;

            @Override public synchronized void start() {
                if (!properties.hasAgent()) return;
                List<String> command = new ArrayList<>();
                command.add(properties.agent());
                command.add("--protocol=1");
                command.add("--log-level=info");
                command.addAll(properties.agentArgs());
                capture = new AgentPlatformCapabilities(new AgentPlatformCapabilities.Config(command,
                        properties.logDir().resolve("agent.log"), properties.agentHeartbeatInterval(), coreVersion),
                        clock, recording);
                recording.bind(capture);
                capture.start();
            }

            @Override public synchronized void stop() {
                recording.shutdown();
                if (capture != null) capture.close();
                capture = null;
            }

            @Override public synchronized boolean isRunning() { return capture != null; }
        };
    }
}
