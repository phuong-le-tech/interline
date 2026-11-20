package app.interline.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Runner contract follows FollowUpEmailRunner: one validation retry, token totals, no network. */
class BriefRunnerTest {
    private static final String VALID = "{\"lines\":[{\"text\":\"Confirm the budget.\",\"sources\":[\"old\"]}]}";

    private static BriefInput input() {
        return new BriefInput("next", "Budget review", List.of("Alice"),
                List.of(new BriefInput.RelatedMeeting("old", "Earlier budget review", 1,
                        List.of("Budget approved"))),
                List.of(new BriefInput.OpenAction("owed", "old", "Send forecast", "Alice", "2026-12-01")));
    }

    @Test void acceptsAtMostFiveNonBlankLinesEachCitingARelatedMeeting() {
        String five = "{\"lines\":[" + String.join(",", IntStream.range(0, 5)
                .mapToObj(i -> "{\"text\":\"Point " + i + "\",\"sources\":[\"old\"]}").toList()) + "]}";
        var llm = RecordingLlm.replying(five);
        var result = new BriefRunner(llm).run(input());
        assertThat(result.status()).isEqualTo(BriefResult.Status.OK);
        assertThat(result.lines()).hasSize(5).allSatisfy(line -> {
            assertThat(line.text()).isNotBlank();
            assertThat(line.sources()).containsExactly("old");
        });
        assertThat(llm.requests).hasSize(1);
        assertThat(llm.prompt(0)).contains("Alice", "Earlier budget review", "Budget approved", "Send forecast", "old");
        assertThat(result.inputTokens()).isEqualTo(RecordingLlm.INPUT_TOKENS);
        assertThat(result.outputTokens()).isEqualTo(RecordingLlm.OUTPUT_TOKENS);
        assertThat(BriefRunner.PROMPT_VERSION).isEqualTo("brief/v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON",
            "{\"lines\":[{\"text\":\"Point\",\"sources\":[]}]}",
            "{\"lines\":[{\"text\":\"Point\",\"sources\":[\"unrelated\"]}]}",
            "{\"lines\":[{\"text\":\"Point\",\"sources\":[\"old\",\"unrelated\"]}]}",
            "{\"lines\":[{\"text\":\"  \",\"sources\":[\"old\"]}]}",
            "{\"lines\":\"not an array\"}"
    })
    void invalidOutputRetriesExactlyOnceAndAccountsForBothCalls(String invalid) {
        var llm = RecordingLlm.replying(invalid, VALID);
        var result = new BriefRunner(llm).run(input());
        assertThat(result.status()).isEqualTo(BriefResult.Status.OK);
        assertThat(result.lines()).singleElement().satisfies(line -> assertThat(line.sources()).containsExactly("old"));
        assertThat(llm.requests).hasSize(2);
        assertThat(result.inputTokens()).isEqualTo(2 * RecordingLlm.INPUT_TOKENS);
        assertThat(result.outputTokens()).isEqualTo(2 * RecordingLlm.OUTPUT_TOKENS);
    }

    @Test void sixLinesAreInvalidInsteadOfSilentlyTruncating() {
        String six = "{\"lines\":[" + String.join(",", IntStream.range(0, 6)
                .mapToObj(i -> "{\"text\":\"Point " + i + "\",\"sources\":[\"old\"]}").toList()) + "]}";
        var llm = RecordingLlm.replying(six, VALID);
        var result = new BriefRunner(llm).run(input());
        assertThat(llm.requests).hasSize(2);
        assertThat(result.status()).isEqualTo(BriefResult.Status.OK);
        assertThat(result.lines()).hasSize(1);
    }

    @Test void twoInvalidRepliesReturnCleanErrorWithoutUsableLinesAndNeverTryAThirdCall() {
        var llm = RecordingLlm.replying("not JSON", "still invalid", VALID);
        var result = new BriefRunner(llm).run(input());
        assertThat(result.status()).isEqualTo(BriefResult.Status.INVALID_OUTPUT);
        assertThat(result.message()).isEqualTo("The model did not respect the format");
        assertThat(result.lines()).isEmpty();
        assertThat(llm.requests).hasSize(2);
        assertThat(result.inputTokens()).isEqualTo(2 * RecordingLlm.INPUT_TOKENS);
        assertThat(result.outputTokens()).isEqualTo(2 * RecordingLlm.OUTPUT_TOKENS);
    }
}
