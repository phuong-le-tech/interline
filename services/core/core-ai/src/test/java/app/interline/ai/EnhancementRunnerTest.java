package app.interline.ai;

import app.interline.ai.EnhanceInput.SegmentLine;
import app.interline.ai.EnhanceInput.UserBlock;
import app.interline.ai.EnhanceResult.Status;
import app.interline.domain.EnhancementOutput.Addition;
import app.interline.domain.Violation.Rule;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC4, AC5, AC7, AC10, AC14, AC15 at the core-ai level: prompts, the single retry, windowing. No network. */
class EnhancementRunnerTest {
    private static final String EMPTY = "{\"blocks\":[],\"other_points\":[],\"action_items\":[]}";
    private static final String VALID = doc("b1", "Le budget du troisieme trimestre est valide par la finance.", "1");
    private static final String GHOST_BLOCK = doc("ghost-block", "Un ajout perdu sous un bloc inconnu.", "1");
    private static final String UNSOURCED = doc("b1", "Un ajout sans aucune citation du tout.", "");
    private static final String UNKNOWN_SOURCE = doc("b1", "Un ajout qui cite un segment inexistant.", "4242");

    private static String doc(String blockId, String text, String sources) {
        return "{\"blocks\":[{\"user_block_id\":\"" + blockId + "\",\"additions\":[{\"text\":\"" + text
                + "\",\"sources\":[" + sources + "]}]}],\"other_points\":[],\"action_items\":[]}";
    }

    private static EnhanceInput fullInput() {
        return new EnhanceInput("m1", "Point hebdo", "fr", "Focus on decisions and owners.",
                List.of(new UserBlock("b1", "bullet", "Budget Q3 valide", 10_000),
                        new UserBlock("b2", "bullet", "Contrats clients", 70_000)),
                List.of(new SegmentLine(1, 8_000, "Them", "Le budget est valide", false),
                        new SegmentLine(2, 30_000, "Me", "Je relance le client", false),
                        new SegmentLine(3, 31_000, "Me", "ECHO-SUPPRIME-XYZ", true),
                        new SegmentLine(4, 72_000, "Them", "Trois contrats attendent", false)),
                null);
    }

    @Test
    void AC4_fullMode_sendsOneCallWithBlocksAndNonSuppressedTranscriptOnly() {
        var llm = RecordingLlm.replying(VALID);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(1);
        assertThat(llm.prompt(0)).contains("Budget Q3 valide", "Contrats clients", "b1", "b2",
                "Le budget est valide", "Je relance le client", "Trois contrats attendent");
        assertThat(llm.prompt(0)).doesNotContain("ECHO-SUPPRIME-XYZ");
        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.output().blocks()).singleElement().satisfies(block -> {
            assertThat(block.userBlockId()).isEqualTo("b1");
            assertThat(block.additions()).extracting(Addition::sources).containsExactly(List.of(1L));
        });
        assertThat(result.calls()).isEqualTo(1);
    }

    @Test
    void AC4_fullMode_promptShowsSegmentsAsIdTimeSpeakerText() {
        var llm = RecordingLlm.replying(EMPTY);

        new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.prompt(0)).contains("[S1 00:08 Them] Le budget est valide", "[S2 00:30 Me] Je relance le client");
    }

    @Test
    void AC5_retryOnce_unknownBlockTriggersExactlyOneRetryListingTheViolation() {
        var llm = RecordingLlm.replying(GHOST_BLOCK, VALID);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(2);
        assertThat(llm.prompt(1)).contains("ghost-block");
        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.calls()).isEqualTo(2);
        assertThat(result.output().blocks()).singleElement().satisfies(b -> assertThat(b.userBlockId()).isEqualTo("b1"));
    }

    @Test
    void AC5_retryOnce_unsourcedAdditionTriggersExactlyOneRetryListingTheText() {
        var llm = RecordingLlm.replying(UNSOURCED, VALID);

        new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(2);
        assertThat(llm.prompt(1)).contains("Un ajout sans aucune citation du tout.");
    }

    @Test
    void AC5_retryOnce_unknownSourceTriggersExactlyOneRetryListingTheSegmentId() {
        var llm = RecordingLlm.replying(UNKNOWN_SOURCE, VALID);

        new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(2);
        assertThat(llm.prompt(1)).contains("4242");
    }

    @Test
    void AC5_retryOnce_validFirstAttemptIsNotRetried() {
        var llm = RecordingLlm.replying(VALID);

        new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void AC5_retryOnce_neverMakesAThirdCallEvenWhenTheSecondAttemptStillViolates() {
        var llm = RecordingLlm.replying(GHOST_BLOCK, UNSOURCED);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(2);
        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.output().blocks()).isEmpty();
    }

    @Test
    void AC7_dropViolators_secondAttemptKeepsTheValidRemainderAndReportsTheRest() {
        String mixed = "{\"blocks\":[{\"user_block_id\":\"b1\",\"additions\":["
                + "{\"text\":\"Le budget du troisieme trimestre est valide par la finance.\",\"sources\":[1]},"
                + "{\"text\":\"Cet ajout n'a aucune citation du tout.\",\"sources\":[]}]}],"
                + "\"other_points\":[],\"action_items\":[]}";
        var llm = RecordingLlm.replying(GHOST_BLOCK, mixed);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.output().blocks().get(0).additions()).extracting(Addition::text)
                .containsExactly("Le budget du troisieme trimestre est valide par la finance.");
        assertThat(result.dropped()).extracting(v -> v.rule()).containsExactly(Rule.UNSOURCED);
    }

    @Test
    void AC7_invalidOutput_notJsonTwiceGivesInvalidOutputAndTheFormatMessage() {
        var llm = RecordingLlm.replying("Sure! Here are my notes.", "I am sorry, here they are again.");

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.requests).hasSize(2);
        assertThat(result.status()).isEqualTo(Status.INVALID_OUTPUT);
        assertThat(result.output()).isNull();
        assertThat(result.message()).isEqualTo("The model did not respect the format");
    }

    @Test
    void AC7_invalidOutput_notJsonThenValidJsonIsAccepted() {
        var llm = RecordingLlm.replying("not json", VALID);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.calls()).isEqualTo(2);
    }

    @Test
    void AC7_invalidOutput_violationsThenNotJsonIsStillInvalidOutput() {
        var llm = RecordingLlm.replying(GHOST_BLOCK, "{ truncated");

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(result.status()).isEqualTo(Status.INVALID_OUTPUT);
        assertThat(result.output()).isNull();
    }

    @Test
    void AC14_runRecord_reportsPromptVersionAndTokensSummedOverAttempts() {
        var llm = RecordingLlm.replying(GHOST_BLOCK, VALID);

        var result = new EnhancementRunner(llm).run(fullInput());

        assertThat(result.promptVersion()).isEqualTo("enhance/v1");
        assertThat(result.inputTokens()).isEqualTo(2 * RecordingLlm.INPUT_TOKENS);
        assertThat(result.outputTokens()).isEqualTo(2 * RecordingLlm.OUTPUT_TOKENS);
    }

    @Test
    void AC14_promptsAreVersionedFilesOnTheClasspath() throws Exception {
        for (String path : List.of("prompts/enhance/v1/system.md", "prompts/enhance/v1/user.md", "prompts/enhance/v1/retry.md",
                "prompts/followup_email/v1/system.md", "prompts/followup_email/v1/user.md")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertThat(in).as(path).isNotNull();
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).as(path).isNotBlank();
            }
        }
    }

    @Test
    void AC15_templateInstructionsAreSentToTheModel() {
        var llm = RecordingLlm.replying(EMPTY);

        new EnhancementRunner(llm).run(fullInput());

        assertThat(llm.prompt(0)).contains("Focus on decisions and owners.");
    }

    @Test
    void AC15_differentTemplateInstructionsChangeThePrompt() {
        var first = RecordingLlm.replying(EMPTY);
        var second = RecordingLlm.replying(EMPTY);
        EnhanceInput base = fullInput();
        EnhanceInput other = new EnhanceInput(base.meetingId(), base.title(), base.language(), "Ask who owns each risk.",
                base.blocks(), base.segments(), base.contextTokens());

        new EnhancementRunner(first).run(base);
        new EnhancementRunner(second).run(other);

        assertThat(second.prompt(0)).contains("Ask who owns each risk.").doesNotContain("Focus on decisions and owners.");
        assertThat(first.prompt(0)).doesNotContain("Ask who owns each risk.");
    }

    // ---- AC10: windowing ---------------------------------------------------------------------------------------

    private static EnhanceInput windowedInput(Integer contextTokens) {
        return new EnhanceInput("m1", "Long meeting", "fr", "",
                List.of(new UserBlock("a", "bullet", "BLOCK-A text", 100_000),
                        new UserBlock("b", "bullet", "BLOCK-B text", 400_000),
                        new UserBlock("c", "bullet", "BLOCK-C text", 800_000)),
                List.of(new SegmentLine(10, 54_999, "Me", "ZEBRA-0 before A", false),
                        new SegmentLine(11, 55_000, "Me", "ZEBRA-1 first of A", false),
                        new SegmentLine(12, 399_999, "Them", "ZEBRA-2 last of A", false),
                        new SegmentLine(13, 400_000, "Them", "ZEBRA-3 first after A", false),
                        new SegmentLine(14, 754_999, "Me", "ZEBRA-4 before C", false),
                        new SegmentLine(15, 755_000, "Me", "ZEBRA-5 first of C", false),
                        new SegmentLine(16, 900_000, "Them", "ZEBRA-6 end", false)),
                contextTokens);
    }

    private static int markers(String prompt) {
        return prompt.split("BLOCK-", -1).length - 1;
    }

    /** The call dedicated to one block: it mentions that block's marker and no other block's. */
    private static int callFor(RecordingLlm llm, String blockMarker) {
        List<Integer> hits = new java.util.ArrayList<>();
        for (int i = 0; i < llm.requests.size(); i++) {
            if (llm.prompt(i).contains(blockMarker) && markers(llm.prompt(i)) == 1) hits.add(i);
        }
        assertThat(hits).as("calls dedicated to %s", blockMarker).hasSize(1);
        return hits.get(0);
    }

    @Test
    void AC10_windowPrompts_smallContextMakesOneCallPerBlockPlusOneForOtherPoints() {
        var llm = RecordingLlm.responding(request -> EMPTY);

        var result = new EnhancementRunner(llm).run(windowedInput(10));

        assertThat(llm.requests).hasSize(4);
        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.calls()).isEqualTo(4);
    }

    @Test
    void AC10_windowPrompts_firstBlockCarriesOnlySegmentsFromFortyFiveSecondsBeforeItsAnchorToTheNextAnchor() {
        var llm = RecordingLlm.responding(request -> EMPTY);

        new EnhancementRunner(llm).run(windowedInput(10));

        String prompt = llm.prompt(callFor(llm, "BLOCK-A"));
        assertThat(prompt).contains("ZEBRA-1", "ZEBRA-2");
        assertThat(prompt).doesNotContain("ZEBRA-0", "ZEBRA-3", "ZEBRA-4", "ZEBRA-5", "ZEBRA-6");
    }

    @Test
    void AC10_windowPrompts_middleBlockOverlapsThePreviousByTheLookBack() {
        var llm = RecordingLlm.responding(request -> EMPTY);

        new EnhancementRunner(llm).run(windowedInput(10));

        String prompt = llm.prompt(callFor(llm, "BLOCK-B"));
        assertThat(prompt).contains("ZEBRA-2", "ZEBRA-3", "ZEBRA-4", "ZEBRA-5");
        assertThat(prompt).doesNotContain("ZEBRA-0", "ZEBRA-1", "ZEBRA-6");
    }

    @Test
    void AC10_windowPrompts_lastBlockRunsToTheEndOfTheTranscript() {
        var llm = RecordingLlm.responding(request -> EMPTY);

        new EnhancementRunner(llm).run(windowedInput(10));

        String prompt = llm.prompt(callFor(llm, "BLOCK-C"));
        assertThat(prompt).contains("ZEBRA-5", "ZEBRA-6");
        assertThat(prompt).doesNotContain("ZEBRA-0", "ZEBRA-1", "ZEBRA-2", "ZEBRA-3", "ZEBRA-4");
    }

    @Test
    void AC10_windowPrompts_mergesTheAdditionsOfEveryCall() {
        var llm = RecordingLlm.responding(request -> {
            String prompt = request.system() + "\n" + request.user();
            if (markers(prompt) != 1) return EMPTY;
            if (prompt.contains("BLOCK-A")) return doc("a", "Ajout pour le premier bloc de la reunion.", "11");
            if (prompt.contains("BLOCK-C")) return doc("c", "Ajout pour le dernier bloc de la reunion.", "15");
            return EMPTY;
        });

        var result = new EnhancementRunner(llm).run(windowedInput(10));

        assertThat(result.output().blocks()).extracting(b -> b.userBlockId()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void AC10_windowPrompts_largeContextStaysInFullMode() {
        var llm = RecordingLlm.responding(request -> EMPTY);

        new EnhancementRunner(llm).run(windowedInput(1_000_000));

        assertThat(llm.requests).hasSize(1);
        assertThat(llm.prompt(0)).contains("ZEBRA-0", "ZEBRA-6", "BLOCK-A", "BLOCK-B", "BLOCK-C");
    }
}
