package app.interline.domain;

import app.interline.domain.EnhancementOutput.ActionItem;
import app.interline.domain.EnhancementOutput.Addition;
import app.interline.domain.EnhancementOutput.BlockAdditions;
import app.interline.domain.EnhancementOutput.OtherPoint;
import app.interline.domain.Violation.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AC5, AC6, AC7: the provider-agnostic validation rules of docs/ARCHITECTURE.md section 10 and US-3.9. */
class EnhancementValidatorTest {
    private static final String MEETING = "m1";
    private static final String BLOCK = "b1";
    /** The budget limit of an addition under it is its length plus 80. */
    private static final String FRAGMENT = "Budget Q3 valide, reunion de cadrage ok";
    private static final List<SegmentRef> SEGMENTS = List.of(
            new SegmentRef(1, MEETING, false),
            new SegmentRef(2, MEETING, false),
            new SegmentRef(3, MEETING, true),
            new SegmentRef(900, "other-meeting", false));

    private final EnhancementValidator validator = new EnhancementValidator(
            new ValidationContext(MEETING, Map.of(BLOCK, FRAGMENT, "b2", ""), SEGMENTS));

    private static EnhancementOutput additions(String blockId, Addition... additions) {
        return new EnhancementOutput(List.of(new BlockAdditions(blockId, List.of(additions))), List.of(), List.of(), null);
    }

    private static Addition add(String text, Long... sources) {
        return new Addition(text, List.of(sources));
    }

    private static String padded(int length) {
        return "x".repeat(length);
    }

    private static List<String> additionTexts(EnhancementOutput output) {
        return output.blocks().stream().flatMap(b -> b.additions().stream()).map(Addition::text).toList();
    }

    @Test
    void AC4_validOutputPassesUntouchedWithNoViolation() {
        var output = new EnhancementOutput(
                List.of(new BlockAdditions(BLOCK, List.of(add("La finance participe a la reunion.", 1L)))),
                List.of(new OtherPoint("Vacances de fin d'annee.", List.of(2L))),
                List.of(new ActionItem("Relancer le client", "Me", "2026-11-26", List.of(1L, 2L))), "Titre");

        var result = validator.validate(output);

        assertThat(result.violations()).isEmpty();
        assertThat(result.valid()).isEqualTo(output);
    }

    // ---- AC5: blocks and sources -------------------------------------------------------------------------------

    @Test
    void AC5_sourceRules_additionUnderAnUnknownBlockIsRejected() {
        var result = validator.validate(additions("ghost-block", add("Un ajout.", 1L)));

        assertThat(result.valid().blocks()).isEmpty();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo(Rule.UNKNOWN_BLOCK);
            assertThat(v.detail()).contains("ghost-block");
        });
    }

    @Test
    void AC5_sourceRules_additionWithoutSourcesIsRejectedAsUnsourced() {
        var result = validator.validate(additions(BLOCK, add("Sans citation.")));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo(Rule.UNSOURCED);
            assertThat(v.detail()).contains("Sans citation.");
        });
    }

    @Test
    void AC5_sourceRules_additionCitingAnUnknownSegmentIsRejected() {
        var result = validator.validate(additions(BLOCK, add("Citation fantome.", 4242L)));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo(Rule.UNKNOWN_SOURCE);
            assertThat(v.detail()).contains("4242");
        });
    }

    @Test
    void AC5_sourceRules_suppressedSegmentIsNotACitableSource() {
        var result = validator.validate(additions(BLOCK, add("Cite un echo supprime.", 3L)));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.UNKNOWN_SOURCE);
    }

    @Test
    void AC5_sourceRules_segmentOfAnotherMeetingIsNotACitableSource() {
        var result = validator.validate(additions(BLOCK, add("Cite un autre meeting.", 900L)));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.UNKNOWN_SOURCE);
    }

    @Test
    void AC5_sourceRules_oneValidSourceAmongInvalidOnesIsEnough() {
        var result = validator.validate(additions(BLOCK, add("Une bonne citation parmi d'autres.", 4242L, 1L)));

        assertThat(result.violations()).isEmpty();
        assertThat(additionTexts(result.valid())).containsExactly("Une bonne citation parmi d'autres.");
    }

    @Test
    void AC5_sourceRules_actionItemWithoutValidSourceIsRejected() {
        var output = new EnhancementOutput(List.of(), List.of(), List.of(
                new ActionItem("Sans source", "Me", null, List.of()),
                new ActionItem("Source suppressed", "Me", null, List.of(3L)),
                new ActionItem("Source ok", "Me", null, List.of(2L))), null);

        var result = validator.validate(output);

        assertThat(result.valid().actionItems()).extracting(ActionItem::text).containsExactly("Source ok");
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.UNSOURCED, Rule.UNKNOWN_SOURCE);
    }

    @Test
    void AC5_sourceRules_otherPointWithoutValidSourceIsRejected() {
        var output = new EnhancementOutput(List.of(),
                List.of(new OtherPoint("Orphelin", List.of()), new OtherPoint("Cite bien", List.of(1L))), List.of(), null);

        var result = validator.validate(output);

        assertThat(result.valid().otherPoints()).extracting(OtherPoint::text).containsExactly("Cite bien");
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.UNSOURCED);
    }

    // ---- AC6: US-3.9 budget ------------------------------------------------------------------------------------

    @ParameterizedTest(name = "AC6_budget_length_fragmentPlus80Plus_{0}")
    @CsvSource({"0,true", "1,false"})
    void AC6_budget_lengthIsAtMostFragmentPlusEightyCharacters(int overBy, boolean accepted) {
        var result = validator.validate(additions(BLOCK, add(padded(FRAGMENT.length() + 80 + overBy), 1L)));

        assertThat(additionTexts(result.valid())).hasSize(accepted ? 1 : 0);
        assertThat(result.violations()).extracting(Violation::rule)
                .isEqualTo(accepted ? List.of() : List.of(Rule.OVER_BUDGET));
    }

    @Test
    void AC6_budget_emptyFragmentAllowsEightyCharactersExactly() {
        assertThat(validator.validate(additions("b2", add(padded(80), 1L))).violations()).isEmpty();
        assertThat(validator.validate(additions("b2", add(padded(81), 1L))).violations())
                .extracting(Violation::rule).containsExactly(Rule.OVER_BUDGET);
    }

    @ParameterizedTest(name = "AC6_budget_sentences_{0}")
    @CsvSource(delimiter = '|', value = {
            "1|Une phrase.|true",
            "3|Une. Deux. Trois.|true",
            "4|Une. Deux. Trois. Quatre.|false",
            "4 mixed terminators|Une ! Deux ? Trois. Quatre.|false"})
    void AC6_budget_atMostThreeSentences(String name, String text, boolean accepted) {
        var result = validator.validate(additions(BLOCK, add(text, 1L)));

        assertThat(additionTexts(result.valid())).hasSize(accepted ? 1 : 0);
        assertThat(result.violations()).extracting(Violation::rule)
                .isEqualTo(accepted ? List.of() : List.of(Rule.TOO_MANY_SENTENCES));
    }

    @ParameterizedTest(name = "AC6_budget_restatement_{0}")
    @CsvSource(delimiter = '|', value = {
            "verbatim|Budget Q3 valide, reunion de cadrage ok",
            "case and punctuation|BUDGET Q3 valide reunion de cadrage ok.",
            "extra whitespace|  budget   q3 valide,   reunion de cadrage ok  ",
            "strict substring|reunion de cadrage"})
    void AC6_budget_additionContainedInItsFragmentAfterNormalisationIsRejected(String name, String text) {
        var result = validator.validate(additions(BLOCK, add(text, 1L)));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.RESTATES_FRAGMENT);
    }

    @Test
    void AC6_budget_additionThatOnlyContainsTheFragmentAndAddsMeaningIsAccepted() {
        var result = validator.validate(additions(BLOCK, add("Budget Q3 valide, reunion de cadrage ok, finance invitee.", 1L)));

        assertThat(result.violations()).isEmpty();
    }

    @Test
    void AC6_budget_blankAdditionIsRejectedAsEmpty() {
        var result = validator.validate(additions(BLOCK, add("   ", 1L)));

        assertThat(additionTexts(result.valid())).isEmpty();
        assertThat(result.violations()).extracting(Violation::rule).containsExactly(Rule.EMPTY_TEXT);
    }

    // ---- AC7: second attempt keeps the valid remainder ---------------------------------------------------------

    @Test
    void AC7_dropViolators_keepsEveryValidItemAndListsEveryViolation() {
        var output = new EnhancementOutput(
                List.of(new BlockAdditions(BLOCK, List.of(
                        add("Bon ajout un.", 1L), add("Sans source."), add("Bon ajout deux.", 2L))),
                        new BlockAdditions("ghost-block", List.of(add("Perdu.", 1L)))),
                List.of(new OtherPoint("Point valide", List.of(2L))),
                List.of(new ActionItem("Action sans source", "Me", null, List.of())), "Titre");

        var result = validator.validate(output);

        assertThat(additionTexts(result.valid())).containsExactly("Bon ajout un.", "Bon ajout deux.");
        assertThat(result.valid().otherPoints()).extracting(OtherPoint::text).containsExactly("Point valide");
        assertThat(result.valid().actionItems()).isEmpty();
        assertThat(result.valid().titleSuggestion()).isEqualTo("Titre");
        assertThat(result.violations()).extracting(Violation::rule)
                .containsExactlyInAnyOrder(Rule.UNSOURCED, Rule.UNKNOWN_BLOCK, Rule.UNSOURCED);
    }
}
