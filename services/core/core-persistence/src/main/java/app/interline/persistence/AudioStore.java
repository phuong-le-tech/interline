package app.interline.persistence;

import app.interline.domain.StreamKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** PCM chunk files: {@code audio/<meeting>/<stream>/<n>.pcm}, one file per ten seconds of 16 kHz mono s16le. */
public final class AudioStore {
    static final long SAMPLES_PER_FILE = 160_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private final Path root;

    public AudioStore(Path root) { this.root = root; }

    private Path meetingDirectory(String meetingId) {
        if (!meetingId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("unsafe meeting id");
        Path directory = root.toAbsolutePath().normalize().resolve(meetingId);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory))
            throw new IllegalStateException("audio directory must not be a symbolic link");
        return directory;
    }

    public Path directory(String meetingId, StreamKind stream) {
        Path directory = meetingDirectory(meetingId).resolve(stream.wireName());
        if (Files.isSymbolicLink(directory)) throw new IllegalStateException("audio stream must not be a symbolic link");
        return directory;
    }

    /** Never follow links or a stored path: a meeting owns only its canonical directory under this root. */
    public void deleteMeeting(String meetingId) {
        Path directory = meetingDirectory(meetingId);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove meeting audio", e);
        }
    }

    /** Write at the position implied by {@code firstSample}, so replaying a frame rewrites the same bytes. */
    public void write(String meetingId, StreamKind stream, long firstSample, byte[] pcm, Set<Path> touched) {
        long sample = firstSample;
        int offset = 0;
        while (offset < pcm.length) {
            long fileIndex = sample / SAMPLES_PER_FILE;
            long inFile = sample % SAMPLES_PER_FILE;
            int count = (int) Math.min(pcm.length - offset, (SAMPLES_PER_FILE - inFile) * BYTES_PER_SAMPLE);
            Path file = directory(meetingId, stream).resolve(fileIndex + ".pcm");
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    ByteBuffer slice = ByteBuffer.wrap(pcm, offset, count);
                    long position = inFile * BYTES_PER_SAMPLE;
                    while (slice.hasRemaining()) position += channel.write(slice, position);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + file, e);
            }
            touched.add(file);
            offset += count;
            sample += count / BYTES_PER_SAMPLE;
        }
    }

    /** Make the written bytes durable. Callers acknowledge audio only after this returns. */
    public void sync(Set<Path> files) {
        for (Path file : files) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot fsync " + file, e);
            }
        }
    }
}
