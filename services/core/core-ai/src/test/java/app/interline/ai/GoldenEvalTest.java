package app.interline.ai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** AC20: the golden evaluation on the stub model reports 100 % preserved and 100 % sourced for the 5 golden meetings. */
class GoldenEvalTest {
    private static final Path GOLDEN = Path.of(System.getProperty("repo.root", "../..")).resolve("fixtures/golden");
    private static final Pattern FIRST_BLOCK_ID = Pattern.compile("\"user_block_id\"\\s*:\\s*\"([^\"]+)\"");

    /** Stub model: returns the golden meeting's recorded reply, chosen by the first user block id found in the prompt. */
    private static LlmPort stub() {
        return request -> {
            try {
                String prompt = request.system() + "\n" + request.user();
                for (int n = 1; n <= 5; n++) {
                    String reply = Files.readString(GOLDEN.resolve("meeting-" + n + "/stub-reply.json"), StandardCharsets.UTF_8);
                    Matcher id = FIRST_BLOCK_ID.matcher(reply);
                    if (id.find() && prompt.contains(id.group(1))) return new LlmReply(reply, 1_000, 200);
                }
                throw new AssertionError("no golden meeting matches the prompt");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    @Test
    void AC20_goldenFixturesExistForFiveMeetings() {
        for (int n = 1; n <= 5; n++) {
            assertThat(GOLDEN.resolve("meeting-" + n + "/meeting.json")).as("meeting-%d", n).isRegularFile();
            assertThat(GOLDEN.resolve("meeting-" + n + "/stub-reply.json")).as("meeting-%d", n).isRegularFile();
        }
    }

    @Test
    void AC20_goldenEval_stubKeepsEveryUserBlockByteIdentical() {
        var report = GoldenEval.run(stub(), GOLDEN);

        assertThat(report.meetings()).isEqualTo(5);
        assertThat(report.preservedPercent()).isEqualTo(100.0);
    }

    @Test
    void AC20_goldenEval_stubAdditionsAndActionsAreAllSourced() {
        var report = GoldenEval.run(stub(), GOLDEN);

        assertThat(report.sourcedPercent()).isEqualTo(100.0);
        assertThat(report.budgetViolations()).isZero();
    }

    @Test
    void AC20_goldenEval_listsTheActionItemsForManualReview() {
        var report = GoldenEval.run(stub(), GOLDEN);

        assertThat(report.actionItems()).hasSize(9).contains("Relancer le client principal", "Automatiser le deploiement");
        assertThat(report.maxLatencyMs()).isNotNegative();
    }
}
