package app.interline.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

class M4UiSettingsTest {
    @TempDir Path data;

    @Test void preferencesAreValidatedIdempotentAndSurviveRestart() {
        try (var context = boot(data)) {
            int port = port(context);
            assertThat(call(port, "GET", "/settings/ui", null).json().path("onboarding_done").asBoolean()).isFalse();
            for (String invalid : new String[]{"{\"language\":\"de\"}", "{\"onboarding_done\":\"true\"}", "{\"unknown\":true}"})
                assertThat(call(port, "PUT", "/settings/ui", invalid).status()).isEqualTo(400);
            for (int i = 0; i < 2; i++) assertThat(call(port, "PUT", "/settings/ui",
                    Map.of("language", "fr", "onboarding_done", true)).status()).isEqualTo(200);
            var system = ok(call(port, "GET", "/settings/system", null), "local diagnostics").json();
            assertThat(system.path("capabilities").path("capture").path("streams").get(0).asText()).isEqualTo("mic");
            assertThat(system.path("permissions").path("microphone").asText()).isEqualTo("granted");
            assertThat(system.path("free_bytes").asLong()).isPositive();
            assertThat(call(port, "POST", "/settings/permissions/microphone", null).json().path("microphone").asText()).isEqualTo("granted");
            assertThat(call(port, "POST", "/settings/permissions/invented", null).status()).isEqualTo(400);
        }
        try (var context = boot(data)) {
            var saved = call(port(context), "GET", "/settings/ui", null).json();
            assertThat(saved.path("language").asText()).isEqualTo("fr");
            assertThat(saved.path("onboarding_done").asBoolean()).isTrue();
            assertThat(call(port(context), "GET", "/settings/consent", null).json().path("reminder_enabled").asBoolean()).isTrue();
        }
    }
}
