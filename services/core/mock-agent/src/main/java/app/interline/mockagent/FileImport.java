package app.interline.mockagent;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** The scripted stand-in for {@code transcribe.file}: the file is measured, never decoded. */
final class FileImport {
    private static final int SESSION_SAMPLE_RATE = 16000;
    private static final String WHOLE_FILE_SEGMENT = "f-0";

    private FileImport() { }

    /** Length of the file in 16 kHz samples, whatever its own rate. */
    static long durationSamples(Path wav) throws IOException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            return Math.round(audio.getFrameLength() * (double) SESSION_SAMPLE_RATE / audio.getFormat().getSampleRate());
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("unsupported audio file " + wav.getFileName(), e);
        }
    }

    /**
     * Script regions of {@code stream} that end inside the file. A file too short for any of them gets a single
     * region spanning it, with the first scripted text, so an import always yields something to store.
     */
    static List<Region> regionsFor(Script script, String stream, long durationSamples) {
        List<Region> fitting = script.forStream(stream).stream().filter(r -> r.endSample() <= durationSamples).toList();
        if (!fitting.isEmpty() || script.regions().isEmpty()) return fitting;
        return List.of(new Region(stream, WHOLE_FILE_SEGMENT, 0, durationSamples, script.regions().getFirst().text()));
    }
}
