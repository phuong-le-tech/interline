package app.interline.app;

import app.interline.agent.AudioChunk;
import app.interline.domain.MeetingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;

import static app.interline.app.AppTestSupport.*;
import static app.interline.app.LiveTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * The STT language stops being a constant in the agent adapter: it comes from the request or from
 * {@code interline.stt.language}, it is stored on the meeting, and it reaches every request about that meeting.
 * Real MockAgent process observed on the protocol wire through {@link PipelineAgentProbe}.
 */
class LanguageTest {
    @TempDir Path data;

    private static Path mockAgent() { return ROOT.resolve("services/core/mock-agent/bin/mock-agent"); }

    private List<String> agentArgs() { return AppTestSupport.agentArgs(fixture(data), null); }

    private static List<JsonNode> observed(List<JsonNode> observations, String type) {
        return observations.stream().filter(o -> o.path("type").asText().equals(type)).toList();
    }

    private static JsonNode stt(JsonNode observation) {
        return observation.path("message").path("body").path("stt");
    }

    private static String language(JsonNode observation) {
        return stt(observation).path("language").asText();
    }

    private static void awaitObservation(PipelineAgentProbe probe, String type) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(observed(probe.observations(), type)).as("agent protocol message " + type).isNotEmpty());
    }

    @Test void AC6_noBodyUsesDefaultFrench() throws Exception {
        Path launcher = PipelineAgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data); var context = boot(data, launcher, agentArgs())) {
            String id = post(port(context), "/recording/start", null).path("meetingId").asText();

            awaitObservation(probe, "session.start");

            assertThat(language(observed(probe.observations(), "session.start").getFirst()))
                    .as("session.start.stt.language without a request body").isEqualTo("fr");
            assertThat(rows(data, "SELECT language FROM meetings WHERE id=?", id))
                    .as("the effective language is stored on the meeting").singleElement()
                    .extracting(row -> row.get("language")).isEqualTo("fr");
        }
    }

    @Test void AC7_languageEnReachesSessionStartResumeAndTranscribeFile() throws Exception {
        seedOrphan("en");
        Path launcher = PipelineAgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data); var context = boot(data, launcher, agentArgs())) {
            String id = post(port(context), "/recording/start", "{\"language\":\"en\"}").path("meetingId").asText();
            awaitObservation(probe, "session.start");
            awaitObservation(probe, "transcribe.file");

            assertThat(language(observed(probe.observations(), "session.start").getFirst()))
                    .as("the fresh session.start carries the requested language").isEqualTo("en");
            assertThat(observed(probe.observations(), "transcribe.file"))
                    .as("the recovered meeting transcribes in the language stored on it")
                    .allSatisfy(request -> assertThat(language(request)).isEqualTo("en"));
            assertThat(rows(data, "SELECT language FROM meetings WHERE id=?", id)).singleElement()
                    .extracting(row -> row.get("language")).isEqualTo("en");

            // A real agent crash makes the core resume the same meeting; the resumed start must keep the language.
            var crashed = ProcessHandle.of(observed(probe.observations(), "session.start").getFirst().path("pid").asLong())
                    .orElseThrow().descendants().findFirst().orElseThrow();
            assertThat(crashed.destroyForcibly()).as("SIGKILL the real capture process").isTrue();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                    observed(probe.observations(), "session.start").size()).as("a resumed session.start")
                    .isGreaterThanOrEqualTo(2));

            List<JsonNode> starts = observed(probe.observations(), "session.start");
            assertThat(starts.size()).as("one fresh start and one resumed start").isGreaterThanOrEqualTo(2);
            assertThat(language(starts.getLast())).as("the resumed session.start keeps the meeting language")
                    .isEqualTo("en");
            assertThat(starts.getLast().path("message").path("body").path("resume").isNull())
                    .as("the later start resumes instead of starting over").isFalse();
        }
    }

    @Test void AC8_autoIsExplicitJsonNull() throws Exception {
        Path launcher = PipelineAgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data); var context = boot(data, launcher, agentArgs())) {
            String id = post(port(context), "/recording/start", "{\"language\":\"auto\"}").path("meetingId").asText();

            awaitObservation(probe, "session.start");

            JsonNode stt = stt(observed(probe.observations(), "session.start").getFirst());
            assertThat(stt.has("language")).as("an explicit null, not an omitted key").isTrue();
            assertThat(stt.path("language").isNull()).as("auto means detect, encoded as JSON null").isTrue();
            assertThat(rows(data, "SELECT language FROM meetings WHERE id=?", id)).singleElement()
                    .extracting(row -> row.get("language")).isNull();
        }
    }

    @ParameterizedTest(name = "AC9 language {0} is rejected")
    @ValueSource(strings = {"EN", "english", "e", "fr-FR", "5"})
    void AC9_invalidLanguageIs400AndCreatesNoMeeting(String language) throws Exception {
        try (var context = boot(data, mockAgent(), agentArgs())) {
            long meetings = count(data, "SELECT count(*) FROM meetings");
            long runs = count(data, "SELECT count(*) FROM capture_runs");

            var response = exchange(port(context), "POST", "/recording/start",
                    "{\"language\":\"" + language + "\"}");

            assertThat(response.statusCode()).as("POST language=%s: %s", language, response.body()).isEqualTo(400);
            assertThat(count(data, "SELECT count(*) FROM meetings")).as("no meeting for a rejected language")
                    .isEqualTo(meetings);
            assertThat(count(data, "SELECT count(*) FROM capture_runs")).isEqualTo(runs);
        }
    }

    @Test void AC10_configLanguageUsedWithoutBody() throws Exception {
        Path launcher = PipelineAgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data);
             var context = boot(data, launcher, agentArgs(), "--interline.stt.language=en")) {
            post(port(context), "/recording/start", null);

            awaitObservation(probe, "session.start");

            assertThat(language(observed(probe.observations(), "session.start").getFirst()))
                    .as("interline.stt.language applies when the request carries none").isEqualTo("en");
        }
    }

    /** A meeting left recording by a crash, spooled but never persisted, with a language already decided. */
    private void seedOrphan(String language) throws Exception {
        try (var ingest = new DurableCaptureIngest(data)) {
            ingest.openMeeting("orphan");
            ingest.audio("orphan", new AudioChunk(0, 0, 0, 0, 0, pcm(0, 0, 1600)));
            ingest.store().setStatus("orphan", MeetingStatus.RECORDING, 1L);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE capture_runs SET started_at=1 WHERE meeting_id='orphan'");
            statement.executeUpdate("UPDATE meetings SET started_at=1,created_at=1,updated_at=1 WHERE id='orphan'");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"));
             var statement = connection.prepareStatement("UPDATE meetings SET language=? WHERE id='orphan'")) {
            statement.setString(1, language);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        Path spool = Files.createDirectories(data.resolve("spool/orphan/run-1"));
        byte[] payload = pcm(0, 320000, 1600);
        ByteBuffer wav = ByteBuffer.allocate(44 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(0).put(payload);
        Files.write(spool.resolve("mic-1200-1920000.wav"), wav.array());
    }
}
