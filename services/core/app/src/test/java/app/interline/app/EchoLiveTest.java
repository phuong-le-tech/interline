package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * ADR-009 section 2: the core tells the live panel which microphone row is an echo of the system stream, and does it
 * after the {@code stt.final} that row already produced. Real MockAgent process, real SQLite, real SSE.
 *
 * <p>{@code fixtures/audio/echo-live.transcript.json} fixes the delivery order with {@code end_sample}:
 * {@code s-echo-1} before {@code m-echo-1} (system final first), {@code m-echo-2} before {@code s-echo-2}
 * (mic final first), an unpaired {@code m-solo}, and {@code m-dup}/{@code s-dup} sent twice.
 */
class EchoLiveTest {
    private static final String ECHO = "echo-live.transcript.json";
    private static final Duration PLAYBACK = Duration.ofSeconds(20);

    @TempDir Path data;

    private static Path mockAgent() { return ROOT.resolve("services/core/mock-agent/bin/mock-agent"); }

    private List<String> echoArgs() { return agentArgs(data.resolve("echo-live.wav"), ECHO, null); }

    private String start(ConfigurableApplicationContext context) throws Exception {
        return post(port(context), "/recording/start", null).path("meetingId").asText();
    }

    private static void awaitTranscript(Events events, String id, String expected) {
        await().atMost(PLAYBACK).untilAsserted(() ->
                assertThat(events.transcript(id)).as("transcript events of " + id).contains(expected));
    }

    private static List<JsonNode> agentFinals(List<JsonNode> observations, String seg) {
        return observations.stream()
                .filter(o -> o.path("type").asText().equals("stt.final"))
                .filter(o -> o.path("direction").asText().equals("agent"))
                .filter(o -> o.path("message").path("body").path("seg").asText().equals(seg))
                .toList();
    }

    @Test void AC1_micFinalThenSystemFinalPublishesSuppressedAfterBoth() throws Exception {
        try (var context = boot(data, mockAgent(), echoArgs()); var events = new Events(port(context))) {
            String id = start(context);

            awaitTranscript(events, id, "transcript.suppressed:mic:m-echo-2");

            assertThat(events.transcript(id)).as("mic final, then the mirroring system final, then the suppression")
                    .containsSubsequence("stt.final:mic:m-echo-2", "stt.final:system:s-echo-2",
                            "transcript.suppressed:mic:m-echo-2");
        }
    }

    @Test void AC2_systemFinalFirstThenMirroredMicFinalSuppressed() throws Exception {
        try (var context = boot(data, mockAgent(), echoArgs()); var events = new Events(port(context))) {
            String id = start(context);

            awaitTranscript(events, id, "transcript.suppressed:mic:m-echo-1");

            assertThat(events.transcript(id)).as("the mirrored mic final is published, then its suppression")
                    .containsSubsequence("stt.final:system:s-echo-1", "stt.final:mic:m-echo-1",
                            "transcript.suppressed:mic:m-echo-1");
        }
    }

    @Test void AC3_nonEchoMicFinalPublishesNoSuppression() throws Exception {
        try (var context = boot(data, mockAgent(), echoArgs()); var events = new Events(port(context))) {
            String id = start(context);

            // The duplicated mic row is the last scripted final, so this waits for the whole fixture.
            awaitTranscript(events, id, "stt.final:mic:m-dup");

            assertThat(events.transcript(id)).as("the unpaired mic final still reaches the panel")
                    .contains("stt.final:mic:m-solo");
            assertThat(events.suppressed(id)).as("a mic final with no system twin is never suppressed")
                    .doesNotContain("mic:m-solo");
        }
    }

    @Test void AC3_standupTranscriptPublishesNoSuppression() throws Exception {
        try (var context = boot(data, mockAgent(), agentArgs(fixture(data), null));
             var events = new Events(port(context))) {
            String id = start(context);

            awaitTranscript(events, id, "stt.final:system:s-8");

            assertThat(events.transcript(id)).as("every scripted final of the ordinary meeting is published")
                    .hasSize(16);
            assertThat(events.of("transcript.suppressed")).as("no echo in the ordinary meeting").isEmpty();
        }
    }

    @Test void AC4_duplicateFinalPublishesNothingTwice() throws Exception {
        Path launcher = PipelineAgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data);
             var context = boot(data, launcher, echoArgs()); var events = new Events(port(context))) {
            String id = start(context);

            awaitTranscript(events, id, "transcript.suppressed:mic:m-dup");

            // The fixture delivers both duplicate pairs, so a second publish would be observable here.
            await().atMost(PLAYBACK).untilAsserted(() ->
                    assertThat(agentFinals(probe.observations(), "m-dup")).as("the agent really sent m-dup twice")
                            .hasSize(2));
            assertThat(events.transcript(id)).as("one stt.final per (stream,seg), one suppression per seg")
                    .filteredOn("stt.final:mic:m-dup"::equals).hasSize(1);
            assertThat(events.transcript(id)).filteredOn("stt.final:system:s-dup"::equals).hasSize(1);
            assertThat(events.transcript(id)).filteredOn("transcript.suppressed:mic:m-dup"::equals).hasSize(1);
        }
    }
}
