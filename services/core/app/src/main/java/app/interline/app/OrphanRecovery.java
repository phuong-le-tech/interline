package app.interline.app;

import app.interline.domain.MeetingStatus;
import app.interline.domain.StreamKind;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.CaptureStore.RecoveryJob;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Startup repair of meetings that were still recording when the app died (ARCHITECTURE 5.8): frames the agent had
 * spooled but the core never persisted are copied into the chunk files, the hole is marked on the timeline, the
 * meeting becomes {@code recovered} and a clean WAV per span waits for the next agent to transcribe it.
 */
public final class OrphanRecovery {
    private static final System.Logger LOG = System.getLogger(OrphanRecovery.class.getName());
    /** {@code <stream>-<firstSeq>-<firstSample>.wav}, see ARCHITECTURE 5.8. */
    private static final Pattern SPOOL_FILE = Pattern.compile("(mic|system)-(\\d+)-(\\d+)\\.wav");
    private static final int WAV_HEADER_BYTES = 44;
    private static final int FRAME_BYTES = 3200;
    private static final long FRAME_MS = 100;
    private static final String CORE_CRASH = "core_crash";

    private record SpoolSpan(StreamKind stream, long firstSeq, long firstSample, Path file) { }

    private final DurableCaptureIngest ingest;
    private final CaptureStore store;
    private final Path spoolRoot;
    private final Path recoveryRoot;

    public OrphanRecovery(DurableCaptureIngest ingest, Path dataDirectory) {
        this.ingest = ingest;
        this.store = ingest.store();
        this.spoolRoot = dataDirectory.resolve("spool");
        this.recoveryRoot = dataDirectory.resolve("recovery");
    }

    /** Repairs every orphaned meeting; safe to call again, a repaired meeting is no longer an orphan. */
    public void run() {
        for (String meetingId : store.meetingsWithStatus(MeetingStatus.RECORDING, MeetingStatus.DEGRADED)) {
            try {
                recover(meetingId);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot recover meeting " + meetingId, e);
            }
        }
    }

    public List<RecoveryJob> pendingJobs() { return store.pendingRecoveryJobs(); }

    public void complete(String jobId) { store.completeRecoveryJob(jobId); }

    private void recover(String meetingId) throws IOException {
        long now = System.currentTimeMillis();
        for (SpoolSpan span : spoolSpans(meetingId)) importSpan(meetingId, span);
        long resumeAtMs = (Math.min(ingest.watermark(meetingId, StreamKind.MIC),
                ingest.watermark(meetingId, StreamKind.SYSTEM)) + 1) * FRAME_MS;
        if (store.gapMarkerCount(meetingId) == 0) store.addGapMarker(meetingId, resumeAtMs, "app_crash");
        store.endRun(meetingId, CORE_CRASH, now);
        store.setStatus(meetingId, MeetingStatus.RECOVERED, now);
        LOG.log(System.Logger.Level.INFO, "recovered meeting {0}", meetingId);
    }

    private void importSpan(String meetingId, SpoolSpan span) throws IOException {
        byte[] file = Files.readAllBytes(span.file());
        // The declared data length is whatever the last header refresh saw: only the physical bytes count.
        int frames = Math.max(0, file.length - WAV_HEADER_BYTES) / FRAME_BYTES;
        long persisted = ingest.watermark(meetingId, span.stream());
        int firstNew = (int) Math.max(0, persisted + 1 - span.firstSeq());
        if (firstNew >= frames) return;
        byte[] pcm = Arrays.copyOfRange(file, WAV_HEADER_BYTES + firstNew * FRAME_BYTES, WAV_HEADER_BYTES + frames * FRAME_BYTES);
        long firstSample = span.firstSample() + (long) firstNew * (FRAME_BYTES / 2);
        ingest.importRecoveredAudio(meetingId, span.stream(), firstSample, pcm);
        Path clean = recoveryRoot.resolve(meetingId).resolve(span.stream().wireName() + "-" + firstSample + ".wav");
        Files.createDirectories(clean.getParent());
        Files.write(clean, wav(pcm));
        store.addRecoveryJob(new RecoveryJob(meetingId + "/" + span.stream().wireName() + "-" + firstSample,
                meetingId, span.stream(), clean.toString(), firstSample));
    }

    private List<SpoolSpan> spoolSpans(String meetingId) throws IOException {
        Path meetingDir = spoolRoot.resolve(meetingId);
        if (!Files.isDirectory(meetingDir)) return List.of();
        List<SpoolSpan> spans = new ArrayList<>();
        try (Stream<Path> runs = Files.list(meetingDir)) {
            for (Path run : runs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(run)) {
                    for (Path file : files.toList()) {
                        Matcher match = SPOOL_FILE.matcher(file.getFileName().toString());
                        if (!match.matches()) continue;
                        StreamKind stream = StreamKind.fromWireName(match.group(1)).orElseThrow();
                        spans.add(new SpoolSpan(stream, Long.parseLong(match.group(2)), Long.parseLong(match.group(3)), file));
                    }
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        spans.sort(Comparator.comparing(SpoolSpan::stream).thenComparingLong(SpoolSpan::firstSeq));
        return spans;
    }

    /** A canonical mono 16 kHz s16le WAV around {@code pcm}, with a header that tells the truth. */
    static byte[] wav(byte[] pcm) {
        ByteBuffer wav = ByteBuffer.allocate(WAV_HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return wav.array();
    }
}
