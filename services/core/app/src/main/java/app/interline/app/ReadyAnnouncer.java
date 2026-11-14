package app.interline.app;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/** Prints the single READY line the shell waits for. Nothing else is ever written to stdout. */
@Component
class ReadyAnnouncer {
    private final InterlineProperties properties;
    private final Environment environment;
    private final String version;

    ReadyAnnouncer(InterlineProperties properties, Environment environment, String coreVersion) {
        this.properties = properties;
        this.environment = environment;
        this.version = coreVersion;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        if (!properties.parentIsStdio()) return;
        long pid = ManagementFactory.getRuntimeMXBean().getPid();
        System.out.println("{\"event\":\"ready\",\"port\":" + environment.getProperty("local.server.port")
                + ",\"pid\":" + pid + ",\"version\":\"" + version + "\"}");
        System.out.flush();
    }
}
