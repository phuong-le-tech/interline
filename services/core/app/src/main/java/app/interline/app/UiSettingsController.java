package app.interline.app;

import app.interline.agent.AgentPlatformCapabilities;
import app.interline.persistence.SettingsStore;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Local UI preferences and diagnostics. Reading this surface never prompts or sends HTTP. */
@RestController
@RequestMapping("/api/v1/settings")
class UiSettingsController {
    private final SettingsStore settings;
    private final InterlineProperties properties;
    private final Clock clock;
    private final String version;
    private volatile AgentPlatformCapabilities agent;

    UiSettingsController(SettingsStore settings, InterlineProperties properties, Clock clock, String coreVersion) {
        this.settings = settings; this.properties = properties; this.clock = clock; this.version = coreVersion;
    }

    void bind(AgentPlatformCapabilities agent) { this.agent = agent; }

    @GetMapping("/ui") Map<String, Object> preferences() {
        return Map.of("onboarding_done", "true".equals(settings.uiPreference("onboarding.done", "false")),
                "language", settings.uiPreference("ui.language", ""));
    }

    @PutMapping("/ui") Map<String, Object> save(@RequestBody Map<String, Object> request) {
        if (!Set.of("onboarding_done", "language").containsAll(request.keySet())) throw ApiProblems.badRequest("unknown UI preference");
        JsonBody body = new JsonBody(request);
        Map<String, String> values = new LinkedHashMap<>();
        if (body.has("onboarding_done")) {
            Boolean done = body.optionalFlag("onboarding_done");
            if (done == null) throw ApiProblems.badRequest("onboarding_done must be boolean");
            values.put("onboarding.done", done.toString());
        }
        if (body.has("language")) values.put("ui.language", body.requiredChoice("language", Set.of("fr", "en")));
        settings.saveUiPreferences(values, clock.millis());
        return preferences();
    }

    @GetMapping("/system") Map<String, Object> system() throws java.io.IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        AgentPlatformCapabilities current = agent;
        try {
            if (current != null) {
                var capabilities = current.capabilities();
                var streams = new java.util.ArrayList<String>();
                capabilities.path("capture").path("streams").forEach(value -> streams.add(value.asText()));
                result.put("capabilities", Map.of("capture", Map.of("streams", streams), "calendar", capabilities.path("calendar").asBoolean(false)));
                result.put("permissions", permissionView(current.permissions(null)));
            }
        } catch (IllegalStateException e) { result.put("agent_unavailable", true); }
        result.put("audio_bytes", size(properties.dataDir().resolve("audio"))
                + size(properties.dataDir().resolve("spool")) + size(properties.dataDir().resolve("recovery")));
        result.put("model_bytes", size(properties.dataDir().resolve("models")));
        result.put("free_bytes", Files.getFileStore(properties.dataDir()).getUsableSpace());
        // The bundled minimal JVM may omit extended OS metrics; unknown is never a guessed RAM value.
        try {
            result.put("ram_bytes", java.lang.management.ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(new javax.management.ObjectName("java.lang:type=OperatingSystem"), "TotalMemorySize"));
        } catch (Exception e) { result.put("ram_bytes", null); }
        return result;
    }

    @PostMapping("/permissions/{permission}") Object request(@PathVariable String permission) {
        if (!Set.of("microphone", "system_audio", "calendar").contains(permission)) throw ApiProblems.badRequest("unknown permission");
        AgentPlatformCapabilities current = agent;
        if (current == null) throw ApiProblems.badRequest("capture agent unavailable");
        var capabilities = current.capabilities();
        boolean advertised = permission.equals("calendar") ? capabilities.path("calendar").asBoolean(false)
                : capabilities.path("capture").path("streams").toString().contains(permission.equals("microphone") ? "\"mic\"" : "\"system\"");
        if (!advertised) throw ApiProblems.badRequest("permission not advertised by agent");
        return permissionView(current.permissions(permission));
    }

    private static Map<String, String> permissionView(com.fasterxml.jackson.databind.JsonNode states) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String kind : Set.of("microphone", "system_audio", "calendar")) result.put(kind, states.path(kind).asText("unknown"));
        return result;
    }

    private static long size(Path root) throws java.io.IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (var paths = Files.walk(root)) {
            long total = 0;
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) total += Files.size(path);
            return total;
        }
    }
}
