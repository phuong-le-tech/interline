package app.interline.mockagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The scripted transcript that stands in for a speech recognizer: VAD regions and their final text. */
record Script(int sampleRate, long samples, List<Region> regions) {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Script load(Path file) throws IOException {
        JsonNode root = JSON.readTree(file.toFile());
        List<Region> regions = new ArrayList<>();
        for (JsonNode segment : root.path("segments")) {
            regions.add(new Region(segment.path("stream").asText(), segment.path("seg").asText(),
                    segment.path("start_sample").asLong(), segment.path("end_sample").asLong(),
                    segment.path("text").asText()));
        }
        return new Script(root.path("sample_rate").asInt(), root.path("samples").asLong(), List.copyOf(regions));
    }

    List<Region> forStream(String stream) {
        return regions.stream().filter(region -> region.stream().equals(stream)).toList();
    }
}
