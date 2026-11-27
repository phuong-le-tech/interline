package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/** Speech and assertions for native AC5 only. No checked-in model, transcript oracle or network download. */
final class M1NativeContract {
    /* Native AC5, 2026-11-27, tiny + Silero, Samantha, default GPU, three consecutive runs:
     * mic regions 0/1/3/4: 1/1/0.772152/1; system regions 0/1/3/4: 1/1/1/1 in every run.
     * Use 0.75, a 0.022152 margin below the measured minimum, for chunk-boundary decode variation;
     * normalized Levenshtein similarity still rejects half missing text (at most 0.5).
     */
    static final double MIN_TRANSCRIPT_SIMILARITY = 0.75;
    static final long TOTAL_SAMPLES = 60L * 16000;
    static final long KILL_SAMPLE = 26L * 16000;
    record Run(Path directory, String id, List<JsonNode> observations, JsonNode transcript) { }

    static Path speech(Path dir) throws Exception {
        Files.createDirectories(dir);
        String[][] phrases = {
                {"Bonjour, voici le bilan du projet. Les tests de sécurité sont terminés.",
                 "Le serveur de développement fonctionne. Nous préparons la prochaine livraison.",
                 "Il reste un problème de connexion. Nous allons examiner les journaux ensemble.",
                 "La documentation est prête. Merci de relire les exemples avant vendredi.",
                 "La réunion se termine. Nous publierons le rapport demain matin."},
                {"Bonjour à toute l'équipe. Le client a validé la nouvelle interface.",
                 "Les résultats des mesures sont bons. La consommation de mémoire reste stable.",
                 "Nous devons vérifier la sauvegarde. Les données doivent rester disponibles.",
                 "Le calendrier est confirmé. La démonstration aura lieu lundi après-midi.",
                 "Merci pour votre travail. La prochaine réunion est prévue mardi matin."}
        };
        byte[][] channels = {new byte[(int) TOTAL_SAMPLES * 2], new byte[(int) TOTAL_SAMPLES * 2]};
        for (int stream = 0; stream < 2; stream++) for (int region = 0; region < 5; region++) {
            Path aiff = dir.resolve("speech-" + stream + "-" + region + ".aiff");
            Path mono = dir.resolve("speech-" + stream + "-" + region + ".wav");
            command(dir, "/usr/bin/say", "-v", System.getProperty("interline.swift-agent.test-voice", "Thomas"),
                    "-r", "165", "-o", aiff.toString(), phrases[stream][region]);
            command(dir, "/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff.toString(), mono.toString());
            byte[] pcm = wavePcm(mono);
            assertThat(pcm.length).as("Generated real speech fits within its 12 second region").isBetween(16000, 10 * 16000 * 2);
            System.arraycopy(pcm, 0, channels[stream], (region * 12 + 1) * 16000 * 2, pcm.length);
        }
        Path stereo = dir.resolve("native-speech-16k-stereo.wav");
        ByteBuffer wav = ByteBuffer.allocate(44 + (int) TOTAL_SAMPLES * 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(wav.capacity() - 8)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(16000).putInt(64000).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) TOTAL_SAMPLES * 4);
        for (int sample = 0; sample < TOTAL_SAMPLES; sample++) for (byte[] channel : channels)
            wav.put(channel[sample * 2]).put(channel[sample * 2 + 1]);
        Files.write(stereo, wav.array());
        return stereo;
    }
    private static void command(Path dir, String... command) throws Exception {
        Path output = dir.resolve("speech-tools.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(output.toFile())).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Speech generation must complete: %s", Arrays.toString(command)).isTrue();
        assertThat(process.exitValue()).as("Speech tool: %s\n%s", Arrays.toString(command), Files.readString(output)).isZero();
    }
    private static byte[] wavePcm(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        boolean format = false;
        for (int offset = 12; offset + 8 <= bytes.length;) {
            String kind = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = b.getInt(offset + 4), start = offset + 8;
            if (size < 0 || (long) start + size > bytes.length) throw new IOException("Truncated WAV chunk");
            if (kind.equals("fmt ")) {
                assertThat(b.getShort(start)).isEqualTo((short) 1);
                assertThat(b.getShort(start + 2)).isEqualTo((short) 1);
                assertThat(b.getInt(start + 4)).isEqualTo(16000);
                assertThat(b.getShort(start + 14)).isEqualTo((short) 16);
                format = true;
            }
            if (kind.equals("data")) {
                assertThat(format).isTrue();
                return Arrays.copyOfRange(bytes, start, start + size);
            }
            offset = start + size + (size & 1);
        }
        throw new IOException("No PCM data in " + file);
    }

    static List<JsonNode> ofType(List<JsonNode> observations, String type) {
        return observations.stream().filter(n -> n.path("type").asText().equals(type)).toList();
    }
    static List<JsonNode> frames(List<JsonNode> observations, String stream) {
        return ofType(observations, "audio.chunk").stream().filter(n -> n.path("stream").asText().equals(stream)).toList();
    }
    static void compare(Run baseline, Run recovery) throws Exception {
        var kills = ofType(recovery.observations, "kill");
        assertThat(kills).hasSize(1);
        JsonNode kill = kills.getFirst();
        assertThat(kill.path("sigkill").asBoolean()).isTrue();
        assertThat(kill.path("first_sample").asLong()).isEqualTo(KILL_SAMPLE);
        long pid = kill.path("pid").asLong();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(ofType(recovery.observations, "exit").stream().filter(n -> n.path("pid").asLong() == pid).toList())
                .singleElement().satisfies(n -> assertThat(n.path("code").asInt()).as("Real native child died from SIGKILL").isEqualTo(137));
        var starts = ofType(recovery.observations, "session.start");
        assertThat(starts).hasSize(2);
        assertThat(starts.getFirst().path("pid").asLong()).isEqualTo(pid);
        assertThat(starts.getLast().path("pid").asLong()).isNotEqualTo(pid);
        assertThat(starts.getLast().path("message").path("body").path("resume").isObject()).isTrue();
        var replay = frames(recovery.observations, "mic").stream()
                .filter(n -> n.path("seq").equals(kill.path("seq"))).toList();
        assertThat(replay).anySatisfy(n -> {
            assertThat(n.path("flags").asInt() & 1).as("Unacked checkpoint frame is replayed as recovered").isEqualTo(1);
            assertThat(n.path("pcm_sha256")).isEqualTo(kill.path("pcm_sha256"));
            assertThat(n.path("first_sample")).isEqualTo(kill.path("first_sample"));
        });
        long firstNewAudio = ofType(recovery.observations, "audio.chunk").stream()
                .filter(n -> n.path("pid").asLong() != pid).mapToLong(n -> n.path("nano").asLong()).min().orElseThrow();
        assertThat(firstNewAudio - kill.path("nano").asLong()).as("SIGKILL to actual resumed audio, not process launch").isBetween(0L, 1_999_999_999L);

        for (String stream : List.of("mic", "system")) {
            var reference = uniqueAndPersisted(baseline, stream);
            var actual = uniqueAndPersisted(recovery, stream);
            var resumedFrames = frames(recovery.observations, stream).stream()
                    .filter(n -> n.path("pid").asLong() != pid).toList();
            boolean liveSeen = false;
            for (JsonNode n : resumedFrames) {
                if ((n.path("flags").asInt() & 1) != 0) {
                    assertThat(liveSeen).as("Recovered spool frames precede new live audio").isFalse();
                } else {
                    if (!liveSeen) assertThat(n.path("flags").asInt() & 2)
                            .as("First resumed live frame has discontinuity_before").isEqualTo(2);
                    liveSeen = true;
                }
            }
            assertThat(liveSeen).as("Replacement native process produces live %s audio", stream).isTrue();
            assertThat(reference.firstKey()).isZero();
            assertThat(actual.firstKey()).isZero();
            Map<Long, JsonNode> bySample = new HashMap<>();
            reference.values().forEach(n -> bySample.put(n.path("first_sample").asLong(), n));
            long previousEnd = 0, gapStart = -1, gapEnd = -1;
            for (JsonNode n : actual.values()) {
                long first = n.path("first_sample").asLong();
                assertThat(first).as("No overlapping or duplicated PCM samples").isGreaterThanOrEqualTo(previousEnd);
                if (first > previousEnd) {
                    assertThat(gapStart).as("Only one actual sample gap per stream").isEqualTo(-1);
                    assertThat(n.path("flags").asInt() & 2).as("Discontinuity before actual gap").isEqualTo(2);
                    gapStart = previousEnd; gapEnd = first;
                    assertThat(gapEnd - gapStart).as("Actual missing audio is less than two seconds").isLessThan(32000);
                }
                JsonNode expected = bySample.get(first);
                assertThat(expected).as("Resume retains original input timeline at sample %s", first).isNotNull();
                assertThat(n.path("samples")).isEqualTo(expected.path("samples"));
                assertThat(n.path("pcm_sha256")).as("Native PCM matches uninterrupted baseline outside actual loss").isEqualTo(expected.path("pcm_sha256"));
                previousEnd = first + n.path("samples").asLong();
            }
            assertThat(previousEnd).isEqualTo(TOTAL_SAMPLES);
            assertThat(actual.values()).anySatisfy(n -> assertThat(n.path("flags").asInt() & 2).isEqualTo(2));
            // The gap can be zero samples (all audio recovered). Use observed kill sample in that case.
            long from = gapStart < 0 ? KILL_SAMPLE : gapStart;
            long to = gapEnd < 0 ? KILL_SAMPLE : gapEnd;
            int before = 0, after = 0;
            for (int region = 0; region < 5; region++) {
                long start = region * 12000L, end = start + 12000;
                if (end * 16 > from - 16000 && start * 16 < to + 16000) continue;
                String expected = regionText(baseline.transcript, stream, start, end);
                assertThat(expected).as("Baseline must transcribe speech in %s region %s", stream, region).isNotBlank();
                String recovered = regionText(recovery.transcript, stream, start, end);
                double similarity = transcriptSimilarity(expected, recovered);
                System.out.printf(Locale.ROOT, "AC5 transcript similarity: %s region %d = %.6f%n", stream, region, similarity);
                assertTranscriptSimilar(expected, recovered);
                if (end * 16 <= from) before++; else after++;
            }
            assertThat(before).as("Compared speech before actual gap").isPositive();
            assertThat(after).as("Compared speech after actual gap").isPositive();
        }
        assertThat(rows(recovery.directory, "SELECT * FROM timeline_markers WHERE meeting_id=? AND kind='gap'", recovery.id)).hasSize(1);
        for (Run run : List.of(baseline, recovery)) {
            assertThat(rows(run.directory, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=? GROUP BY speaker_id,agent_seg HAVING count(*)>1", run.id)).isEmpty();
            assertThat(rows(run.directory, "SELECT text FROM transcript_segments WHERE meeting_id=? GROUP BY speaker_id,start_ms,end_ms,text HAVING count(*)>1", run.id)).isEmpty();
            assertThat(rows(run.directory, "SELECT * FROM transcript_segments WHERE meeting_id=?", run.id)).hasSize(run.transcript.size());
        }
        assertThat(rows(baseline.directory, "SELECT * FROM timeline_markers WHERE meeting_id=? AND kind='gap'", baseline.id)).isEmpty();
    }

    static void assertTranscriptSimilar(String baseline, String recovered) {
        assertThat(recovered).as("Recovered transcript outside actual gap").isNotBlank();
        assertThat(transcriptSimilarity(baseline, recovered))
                .as("Normalized transcript similarity outside actual gap: baseline=%s recovered=%s", baseline, recovered)
                .isGreaterThanOrEqualTo(MIN_TRANSCRIPT_SIMILARITY);
    }

    static double transcriptSimilarity(String baseline, String recovered) {
        int length = Math.max(baseline.length(), recovered.length());
        if (length == 0) return 1.0;
        int[] previous = new int[recovered.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= baseline.length(); i++) {
            int[] current = new int[previous.length];
            current[0] = i;
            for (int j = 1; j < current.length; j++) {
                int substitution = previous[j - 1] + (baseline.charAt(i - 1) == recovered.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            previous = current;
        }
        return 1.0 - (double) previous[recovered.length()] / length;
    }

    private static SortedMap<Long, JsonNode> uniqueAndPersisted(Run run, String stream) throws Exception {
        SortedMap<Long, JsonNode> unique = new TreeMap<>();
        for (JsonNode n : frames(run.observations, stream)) {
            JsonNode previous = unique.putIfAbsent(n.path("seq").asLong(), n);
            if (previous != null) {
                assertThat(n.path("first_sample")).isEqualTo(previous.path("first_sample"));
                assertThat(n.path("pcm_sha256")).isEqualTo(previous.path("pcm_sha256"));
                assertThat(n.path("flags").asInt() & 1).as("Repeated delivery must be recovered").isEqualTo(1);
            }
        }
        assertThat(unique).isNotEmpty();
        long seq = 0, lastEnd = 0;
        var bytes = new ByteArrayOutputStream();
        Path root = run.directory.resolve("audio").resolve(run.id).resolve(stream);
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".pcm"))
                    .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().replace(".pcm", "")))).toList())
                bytes.write(Files.readAllBytes(file));
        }
        byte[] track = bytes.toByteArray();
        for (var e : unique.entrySet()) {
            assertThat(e.getKey()).as("Native per-stream seq remains contiguous").isEqualTo(seq++);
            JsonNode n = e.getValue();
            int first = n.path("first_sample").asInt(), samples = n.path("samples").asInt();
            assertThat(track.length).isGreaterThanOrEqualTo((first + samples) * 2);
            assertThat(M1NativeProbe.hash(Arrays.copyOfRange(track, first * 2, (first + samples) * 2)))
                    .as("Persisted PCM at original sample offset, without append-on-replay").isEqualTo(n.path("pcm_sha256").asText());
            lastEnd = Math.max(lastEnd, first + samples);
        }
        assertThat(track.length).as("PCM replay adds no bytes beyond original timeline").isEqualTo(lastEnd * 2);
        return unique;
    }
    private static String regionText(JsonNode transcript, String stream, long start, long end) {
        List<JsonNode> segments = new ArrayList<>();
        transcript.forEach(n -> { if (n.path("stream").asText().equals(stream)
                && n.path("startMs").asLong() >= start && n.path("endMs").asLong() <= end) segments.add(n); });
        segments.sort(Comparator.comparingLong(n -> n.path("startMs").asLong()));
        String text = String.join(" ", segments.stream().map(n -> n.path("text").asText()).toList());
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
