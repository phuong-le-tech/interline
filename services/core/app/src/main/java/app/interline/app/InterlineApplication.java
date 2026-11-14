package app.interline.app;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootApplication
public class InterlineApplication {
    private static final long HARD_EXIT_DEADLINE_MS = 5500;
    private static final int EXIT_FORCED = 1;

    public static void main(String[] args) throws IOException {
        Map<String, Object> properties = new LinkedHashMap<>(CoreArguments.parse(args));
        Lifeline lifeline = null;
        if ("stdio".equals(properties.get("interline.parent"))) {
            lifeline = new Lifeline(System.in);
            properties.put("interline.token", lifeline.readToken());
        }
        SpringApplication application = new SpringApplication(InterlineApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        // Command-line sources outrank application.properties, so the shell's choices always win.
        String[] springArgs = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        ConfigurableApplicationContext context = application.run(springArgs);
        if (lifeline != null) lifeline.watch(() -> exit(context));
    }

    /** Close the context (recording, agent, database), then leave. A watchdog guarantees the 6 s budget. */
    private static void exit(ConfigurableApplicationContext context) {
        Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.sleep(HARD_EXIT_DEADLINE_MS);
            } catch (InterruptedException e) {
                return;
            }
            Runtime.getRuntime().halt(EXIT_FORCED);
        });
        context.close();
        System.exit(0);
    }
}
