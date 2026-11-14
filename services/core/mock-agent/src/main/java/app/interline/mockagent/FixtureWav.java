package app.interline.mockagent;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Deterministic synthetic stand-in for a recorded meeting: stereo 16 kHz s16le, left = mic, right = system.
 * A sawtooth tone fills every scripted region of its channel and everything else is exact silence, so the
 * repository never needs a real recording.
 */
public final class FixtureWav {
    private static final int HEADER_BYTES = 44;
    private static final int CHANNELS = 2;
    private static final int BYTES_PER_FRAME = 4;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int TONE_PERIOD = 20001;
    private static final int TONE_OFFSET = 10000;
    private static final long MIC_STEP = 37;
    private static final long SYSTEM_STEP = 53;

    private FixtureWav() { }

    /** Create {@code wav} from the scripted regions of {@code script} unless it already exists. */
    public static Path generateIfMissing(Path wav, Path script) throws IOException {
        if (Files.exists(wav)) return wav;
        Script scripted = Script.load(script);
        Files.createDirectories(wav.toAbsolutePath().getParent());
        Path partial = Files.createTempFile(wav.toAbsolutePath().getParent(), "fixture-", ".wav.part");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
            out.write(header(scripted.sampleRate(), scripted.samples()));
            write(out, scripted);
        }
        Files.move(partial, wav, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return wav;
    }

    /** The two channels of a canonical PCM WAV as little-endian mono byte arrays: {mic, system}. */
    static byte[][] readChannels(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        int frames = (bytes.length - HEADER_BYTES) / BYTES_PER_FRAME;
        byte[] mic = new byte[frames * 2];
        byte[] system = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int at = HEADER_BYTES + i * BYTES_PER_FRAME;
            mic[i * 2] = bytes[at];
            mic[i * 2 + 1] = bytes[at + 1];
            system[i * 2] = bytes[at + 2];
            system[i * 2 + 1] = bytes[at + 3];
        }
        return new byte[][] {mic, system};
    }

    private static byte[] header(int sampleRate, long samples) {
        long dataBytes = samples * BYTES_PER_FRAME;
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes()).putInt((int) (HEADER_BYTES - 8 + dataBytes)).put("WAVEfmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) CHANNELS).putInt(sampleRate)
                .putInt(sampleRate * BYTES_PER_FRAME).putShort((short) BYTES_PER_FRAME).putShort((short) BITS_PER_SAMPLE)
                .put("data".getBytes()).putInt((int) dataBytes).array();
    }

    private static void write(OutputStream out, Script script) throws IOException {
        boolean[] mic = voiced(script.forStream("mic"), script.samples());
        boolean[] system = voiced(script.forStream("system"), script.samples());
        ByteBuffer frame = ByteBuffer.allocate(BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < script.samples(); i++) {
            frame.clear();
            frame.putShort(mic[i] ? tone(i, MIC_STEP) : 0).putShort(system[i] ? tone(i, SYSTEM_STEP) : 0);
            out.write(frame.array());
        }
    }

    private static boolean[] voiced(List<Region> regions, long samples) {
        boolean[] voiced = new boolean[(int) samples];
        for (Region region : regions) {
            java.util.Arrays.fill(voiced, (int) region.startSample(), (int) region.endSample(), true);
        }
        return voiced;
    }

    private static short tone(long sample, long step) {
        return (short) ((sample * step) % TONE_PERIOD - TONE_OFFSET);
    }
}
