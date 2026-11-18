package app.interline.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static app.interline.app.AppTestSupport.HTTP;
import static app.interline.app.M2Support.port;
import static app.interline.app.M3BriefSupport.*;
import static app.interline.app.M3Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Every new slice 2 route rejects a missing or wrong bearer token with 401 and writes nothing. */
@Timeout(180)
class M3Round1SecurityTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach void start() {
        context = boot(data);
        port = port(context);
    }

    @AfterEach void stop() {
        if (context.isActive()) context.close();
    }

    /** Status of a call with the given Authorization header (null = no header at all). */
    private int status(String method, String path, String authorization, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (authorization != null) request.header("Authorization", authorization);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " " + path + " failed", e);
        }
    }

    private long meetingCount() {
        return ((Number) rows(data, "SELECT COUNT(*) AS n FROM meetings").get(0).get("n")).longValue();
    }

    @Test void postMeetingsWithoutATokenIs401AndCreatesNothing() {
        assertThat(status("POST", "/meetings", null, "{\"title\":\"Sneaky\",\"participants\":[{\"name\":\"Mallory\"}]}")).isEqualTo(401);
        assertThat(meetingCount()).isZero();
        assertThat(people(data)).isEmpty();
    }

    @Test void postMeetingFromEventWithoutATokenIs401AndCreatesNothing() {
        insertEventWithAttendees(data, "ev-sec", "Secure", NOW + HOUR, "[{\"name\":\"Alice\",\"email\":\"alice@example.com\"}]");
        assertThat(status("POST", "/calendar/events/ev-sec/meeting", null, null)).isEqualTo(401);
        assertThat(meetingCount()).isZero();
        assertThat(people(data)).isEmpty();
    }

    @Test void getParticipantsWithoutATokenIs401() {
        String id = createMeetingOk(port, "Guarded", null);
        assertThat(status("GET", "/meetings/" + id + "/participants", null, null)).isEqualTo(401);
    }

    @Test void putParticipantsWithoutATokenIs401AndChangesNothing() {
        String id = createMeetingOk(port, "Guarded", java.util.List.of(person("Alice", "alice@example.com")));
        assertThat(status("PUT", "/meetings/" + id + "/participants", null, "[{\"name\":\"Mallory\"}]")).isEqualTo(401);
        assertThat(texts(M2Support.ok(participants(port, id), "get").json(), "name")).containsExactly("Alice");
        assertThat(people(data)).hasSize(1);
    }

    @Test void getBriefWithoutATokenIs401() {
        String id = createMeetingOk(port, "Guarded", null);
        assertThat(status("GET", "/meetings/" + id + "/brief", null, null)).isEqualTo(401);
    }

    @Test void getBriefWithAWrongTokenIs401() {
        String id = createMeetingOk(port, "Guarded", null);
        assertThat(status("GET", "/meetings/" + id + "/brief", "Bearer not-the-right-token", null)).isEqualTo(401);
    }
}
