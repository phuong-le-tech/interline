package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(120)
class M3ConsentApiTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() { context = boot(data); port = port(context); }
    @AfterEach void stop() { if (context != null) context.close(); }

    private String create() {
        Resp response = call(port, "POST", "/meetings", Map.of("title", "Consent test"));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().path("id").asText();
    }

    private JsonNode meeting(String id) {
        JsonNode meetings = ok(call(port, "GET", "/meetings", null), "meetings").json();
        for (JsonNode meeting : meetings) if (meeting.path("id").asText().equals(id)) return meeting;
        throw new AssertionError("Missing meeting " + id);
    }

    @Test void reminderDefaultsTrueAndRejectsNonBooleanWrites() {
        JsonNode settings = ok(call(port, "GET", "/settings/consent", null), "default consent settings").json();
        assertThat(settings.path("reminder_enabled").isBoolean()).isTrue();
        assertThat(settings.path("reminder_enabled").booleanValue()).isTrue();
        for (String invalid : new String[]{"{}", "{\"reminder_enabled\":\"false\"}",
                "{\"reminder_enabled\":null}", "{\"reminder_enabled\":0}"}) {
            assertThat(call(port, "PUT", "/settings/consent", invalid).status()).as(invalid).isEqualTo(400);
        }
        assertThat(ok(call(port, "GET", "/settings/consent", null), "unchanged settings").json()
                .path("reminder_enabled").booleanValue()).isTrue();
    }

    @Test void acknowledgementIsIdempotentAndSurvivesCoreRestart() {
        String id = create();
        assertThat(meeting(id).has("consentAckAt")).isTrue();
        assertThat(meeting(id).path("consentAckAt").isNull()).isTrue();
        assertThat(call(port, "POST", "/meetings/" + id + "/consent-ack", null).status()).isBetween(200, 299);
        JsonNode first = meeting(id).path("consentAckAt");
        assertThat(first.isIntegralNumber()).isTrue();
        assertThat(first.asLong()).isPositive();
        assertThat(call(port, "POST", "/meetings/" + id + "/consent-ack", null).status()).isBetween(200, 299);
        assertThat(meeting(id).path("consentAckAt")).isEqualTo(first);
        context.close();
        context = boot(data); port = port(context);
        assertThat(meeting(id).path("consentAckAt")).isEqualTo(first);
        assertThat(call(port, "POST", "/meetings/" + id + "/consent-ack", null).status()).isBetween(200, 299);
        assertThat(meeting(id).path("consentAckAt")).isEqualTo(first);
        assertThat(meeting(create()).path("consentAckAt").isNull()).isTrue();
    }

    @Test void disabledReminderSurvivesRestartAndAppliesToNewMeetings() throws Exception {
        assertThat(call(port, "PUT", "/settings/consent", Map.of("reminder_enabled", false)).status()).isBetween(200, 299);
        context.close();
        context = boot(data); port = port(context);
        JsonNode saved = ok(call(port, "GET", "/settings/consent", null), "saved opt out").json().path("reminder_enabled");
        assertThat(saved.isBoolean()).isTrue();
        assertThat(saved.booleanValue()).isFalse();
        String id = create();
        assertThat(meeting(id).path("consentAckAt").isNull()).isTrue();
        assertThat(AppTestSupport.rows(data, "SELECT value FROM settings WHERE key = 'consent.reminder_enabled'"))
                .containsExactly(Map.of("value", "false"));
        Resp recording = call(port, "POST", "/recording/start", Map.of("meetingId", id));
        assertThat(recording.status()).as(recording.body()).isEqualTo(200);
        assertThat(recording.json().path("meetingId").asText()).isEqualTo(id);
    }

    @Test void directCoreRecordingStillWorksWithoutConsentOrRequestBody() {
        Resp recording = call(port, "POST", "/recording/start", null);
        assertThat(recording.status()).as(recording.body()).isEqualTo(200);
        String id = recording.json().path("meetingId").asText();
        assertThat(id).isNotBlank();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(meeting(id).path("status").asText()).isEqualTo("recording"));
    }

    @Test void unknownAcknowledgementIs404AndCannotCreateAMeeting() {
        assertThat(call(port, "POST", "/meetings/missing/consent-ack", null).status()).isEqualTo(404);
        assertThat(ok(call(port, "GET", "/meetings", null), "meetings").json()).isEmpty();
    }
}
