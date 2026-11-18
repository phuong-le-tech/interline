package app.interline.ai;

import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.ai.EnhanceInput.UserBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC16: the follow-up email is a draft with its sources, built from the notes and the non-suppressed transcript. */
class FollowUpEmailRunnerTest {
    private static FollowUpInput input() {
        return new FollowUpInput("m1", "Point hebdo", "fr",
                List.of(new UserBlock("b1", "bullet", "Budget Q3 valide", 10_000)),
                List.of(new SegmentLine(1, 8_000, "Them", "Le budget est valide", false),
                        new SegmentLine(2, 30_000, "Me", "Je relance le client", false),
                        new SegmentLine(3, 31_000, "Me", "ECHO-SUPPRIME-XYZ", true)));
    }

    @Test
    void AC16_followup_returnsSubjectBodyAndSources() {
        var llm = RecordingLlm.replying("{\"subject\":\"Suite du point hebdo\",\"body\":\"Bonjour,\\nLe budget est valide.\","
                + "\"sources\":[1,2]}");

        var result = new FollowUpEmailRunner(llm).run(input());

        assertThat(result.status()).isEqualTo(FollowUpResult.Status.OK);
        assertThat(result.subject()).isEqualTo("Suite du point hebdo");
        assertThat(result.body()).isEqualTo("Bonjour,\nLe budget est valide.");
        assertThat(result.sources()).containsExactly(1L, 2L);
    }

    @Test
    void AC16_followup_dropsSourcesThatAreNotCitableSegments() {
        var llm = RecordingLlm.replying("{\"subject\":\"S\",\"body\":\"B\",\"sources\":[1,3,4242]}");

        var result = new FollowUpEmailRunner(llm).run(input());

        assertThat(result.sources()).containsExactly(1L);
    }

    @Test
    void AC16_followup_promptHoldsNotesAndTranscriptButNotSuppressedEchoes() {
        var llm = RecordingLlm.replying("{\"subject\":\"S\",\"body\":\"B\",\"sources\":[1]}");

        new FollowUpEmailRunner(llm).run(input());

        assertThat(llm.requests).hasSize(1);
        assertThat(llm.prompt(0)).contains("Budget Q3 valide", "[S1 00:08 Them] Le budget est valide")
                .doesNotContain("ECHO-SUPPRIME-XYZ");
    }

    @Test
    void AC16_followup_notJsonTwiceIsInvalidOutputWithTheFormatMessage() {
        var llm = RecordingLlm.replying("Voici un email.", "Encore un email.");

        var result = new FollowUpEmailRunner(llm).run(input());

        assertThat(result.status()).isEqualTo(FollowUpResult.Status.INVALID_OUTPUT);
        assertThat(result.message()).isEqualTo("The model did not respect the format");
        assertThat(llm.requests).hasSize(2);
    }

    @Test
    void AC16_followup_promptVersionIsFollowupEmailV1() {
        assertThat(FollowUpEmailRunner.PROMPT_VERSION).isEqualTo("followup_email/v1");
    }
}
