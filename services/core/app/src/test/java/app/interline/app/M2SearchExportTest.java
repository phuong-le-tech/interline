package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static app.interline.app.AppTestSupport.ROOT;
import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** AC17 search over both FTS indexes and AC18 Markdown export against golden files. */
@Timeout(120)
class M2SearchExportTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;
    private Golden golden;

    @BeforeEach void start() {
        context = boot(data);
        port = port(context);
        golden = golden(1);
        seedMeeting(data, golden);
        putNotes(port, golden);
    }

    @AfterEach void stop() {
        context.close();
    }

    private List<JsonNode> results(String query) {
        var response = ok(call(port, "GET", "/search?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8), null), "search");
        List<JsonNode> results = new java.util.ArrayList<>();
        response.json().path("results").forEach(results::add);
        return results;
    }

    private static List<String> ids(List<JsonNode> results, String type) {
        return results.stream().filter(r -> r.path("type").asText().equals(type)).map(r -> r.path("id").asText()).toList();
    }

    @ParameterizedTest(name = "AC17_search_{0}")
    @ValueSource(strings = {"reunion", "réunion", "REUNION"})
    void AC17_search_findsTheAccentedBlockAndTheSegmentsWhateverTheAccentOrCase(String query) {
        List<JsonNode> results = results(query);

        assertThat(ids(results, "block")).containsExactly(golden.notes().get(0).id());
        assertThat(ids(results, "segment")).containsExactlyInAnyOrder("1002", "1003");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.path("meeting_id").asText()).isEqualTo(golden.meetingId());
            assertThat(r.path("snippet").asText()).isNotBlank();
        });
    }

    @Test void AC17_search_noMatchGivesAnEmptyResultList() {
        assertThat(results("zzzabsentzzz")).isEmpty();
    }

    @Test void AC17_search_followsEditsOfABlock() {
        putBlock(port, golden.meetingId(), golden.notes().get(0).id(), 2, "bullet", "a0", "Budget annuel", golden.notes().get(0).anchorMs());

        assertThat(ids(results("reunion"), "block")).isEmpty();
        assertThat(ids(results("budget"), "block")).contains(golden.notes().get(0).id());
    }

    @Test void AC17_search_doesNotReturnSuppressedEchoes() {
        suppress(data, 1002);

        assertThat(ids(results("reunion"), "segment")).containsExactly("1003");
    }

    @ParameterizedTest(name = "AC17_search_badQuery_{0}")
    @ValueSource(strings = {"/search", "/search?q=", "/search?q=%20%20"})
    void AC17_search_blankOrMissingQueryIsABadRequest(String path) {
        assertThat(call(port, "GET", path, null).status()).isEqualTo(400);
    }

    @Test void AC17_search_operatorsInTheQueryAreTreatedAsPlainTextNotAsFts5Syntax() {
        assertThat(call(port, "GET", "/search?q=" + java.net.URLEncoder.encode("budget AND (\"", StandardCharsets.UTF_8), null).status())
                .isEqualTo(200);
    }

    @Test void AC18_exportGolden_markdownWithoutTranscriptMatchesTheGoldenFile() throws Exception {
        var response = ok(call(port, "GET", "/meetings/" + golden.meetingId() + "/export?format=markdown&transcript=false", null), "export");

        assertThat(response.contentType()).startsWith("text/markdown");
        assertThat(response.body()).isEqualTo(Files.readString(ROOT.resolve("fixtures/golden/meeting-1/export.md"), StandardCharsets.UTF_8));
    }

    @Test void AC18_exportGolden_markdownWithTranscriptMatchesTheGoldenFile() throws Exception {
        var response = ok(call(port, "GET", "/meetings/" + golden.meetingId() + "/export?format=markdown&transcript=true", null), "export");

        assertThat(response.body())
                .isEqualTo(Files.readString(ROOT.resolve("fixtures/golden/meeting-1/export-with-transcript.md"), StandardCharsets.UTF_8));
    }

    @Test void AC18_exportGolden_suppressedEchoesAreNotExportedInTheTranscript() throws Exception {
        suppress(data, 1003);

        var response = ok(call(port, "GET", "/meetings/" + golden.meetingId() + "/export?format=markdown&transcript=true", null), "export");

        assertThat(response.body()).doesNotContain("je prepare la reunion de cadrage pour jeudi prochain");
        assertThat(response.body()).contains("Nous avons aussi un retard");
    }

    @Test void AC18_exportGolden_unknownMeetingIsNotFound() {
        assertThat(call(port, "GET", "/meetings/nope/export?format=markdown&transcript=false", null).status()).isEqualTo(404);
    }
}
