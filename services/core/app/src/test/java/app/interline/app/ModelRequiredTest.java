package app.interline.app;

import app.interline.domain.AgentInfo;
import app.interline.domain.AudioCapture;
import app.interline.domain.CaptureRequest;
import app.interline.domain.StreamKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision 9a: a real speech engine without an installed model must refuse to record instead of recording silence. */
class ModelRequiredTest {
    @TempDir Path data;

    private static final class StubCapture implements AudioCapture {
        final List<CaptureRequest> started = new ArrayList<>();
        @Override public void start(CaptureRequest request) { started.add(request); }
        @Override public void stop(String sessionId) { }
        @Override public boolean acknowledge(StreamKind stream, long seq, int window) { return true; }
        @Override public void transcribeFile(String jobId, String path, StreamKind stream, String language) { }
        @Override public void terminate() { }
    }

    private RecordingService service(SpeechModels models, StubCapture capture, String engine) throws Exception {
        var ingest = new DurableCaptureIngest(data);
        var properties = new InterlineProperties(null, data, data.resolve("logs"), null, List.of(), List.of(),
                java.time.Duration.ofSeconds(2), null, new InterlineProperties.Echo(null),
                new InterlineProperties.Stt("fr"), new InterlineProperties.Models(null));
        var service = new RecordingService(ingest, new EventBus(), Clock.systemUTC(), data, new OrphanRecovery(ingest, data), models, properties);
        service.bind(capture);
        service.agentConnected(new AgentInfo("agent", "1", 1, false, engine));
        return service;
    }

    private static SpeechModels models(Path speech, Path vad) {
        return new SpeechModels() {
            @Override public Optional<Path> speechModel() { return Optional.ofNullable(speech); }
            @Override public Optional<Path> vadModel() { return Optional.ofNullable(vad); }
        };
    }

    @Test void refusesToRecordWithoutAModelWhenTheAgentTranscribesWithAFileModel() throws Exception {
        var capture = new StubCapture();
        var service = service(models(null, null), capture, "whisper.cpp");
        assertThatThrownBy(service::start).isInstanceOfSatisfying(RecordingService.RecordingException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("model_missing");
        });
        assertThat(capture.started).isEmpty();
    }

    @Test void scriptedEngineNeedsNoModel() throws Exception {
        var capture = new StubCapture();
        service(models(null, null), capture, "scripted").start();
        assertThat(capture.started).hasSize(1);
        assertThat(capture.started.getFirst().modelPath()).isNull();
    }

    @Test void sessionStartCarriesTheModelsInstalledAfterTheAgentLaunched() throws Exception {
        var capture = new StubCapture();
        Path speech = Files.createFile(data.resolve("ggml-small.bin"));
        Path vad = Files.createFile(data.resolve("ggml-silero.bin"));
        service(models(speech, vad), capture, "whisper.cpp").start();
        assertThat(capture.started.getFirst().modelPath()).isEqualTo(speech.toString());
        assertThat(capture.started.getFirst().vadModelPath()).isEqualTo(vad.toString());
    }

    @Test void agentCommandLineModelOverridesTheInstalledOnes() {
        var override = SpeechModels.overriddenBy(List.of("--model=/x/m.bin", "--vad-model=/x/v.bin"), models(null, null));
        assertThat(override.speechModel()).contains(Path.of("/x/m.bin"));
        assertThat(override.vadModel()).contains(Path.of("/x/v.bin"));
    }
}
