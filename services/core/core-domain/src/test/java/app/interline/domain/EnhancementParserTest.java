package app.interline.domain;

import app.interline.domain.EnhancementOutput.ActionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC7: what counts as "invalid JSON" for the retry rule. */
class EnhancementParserTest {
    private static final Path ROOT = Path.of(System.getProperty("repo.root", "../.."));

    @Test
    void AC4_parsesTheContractDocumentOfAGoldenMeeting() throws Exception {
        String json = Files.readString(ROOT.resolve("fixtures/golden/meeting-1/stub-reply.json"), StandardCharsets.UTF_8);

        EnhancementOutput output = EnhancementParser.parse(json);

        assertThat(output.blocks()).hasSize(3);
        assertThat(output.blocks().get(0).userBlockId()).isEqualTo("01900001-0001-7011-8008-0000000186a1");
        assertThat(output.blocks().get(0).additions()).singleElement().satisfies(addition -> {
            assertThat(addition.text()).isEqualTo("La finance doit participer: une reunion de cadrage est prevue jeudi prochain.");
            assertThat(addition.sources()).containsExactly(1002L);
        });
        assertThat(output.otherPoints()).singleElement().satisfies(point -> assertThat(point.sources()).containsExactly(1008L));
        assertThat(output.actionItems()).extracting(ActionItem::owner).containsExactly("Me", "Me");
        assertThat(output.actionItems().get(0).due()).isEqualTo("2026-11-26");
        assertThat(output.actionItems().get(1).due()).isNull();
        assertThat(output.titleSuggestion()).isEqualTo("Point produit: budget, contrats et facturation");
    }

    @Test
    void AC7_emptyMembersAreAValidDocument() {
        EnhancementOutput output = EnhancementParser.parse("{\"blocks\":[],\"other_points\":[],\"action_items\":[]}");

        assertThat(output.blocks()).isEmpty();
        assertThat(output.otherPoints()).isEmpty();
        assertThat(output.actionItems()).isEmpty();
        assertThat(output.titleSuggestion()).isNull();
    }

    @Test
    void AC7_unknownMembersAreIgnored() {
        EnhancementOutput output = EnhancementParser.parse(
                "{\"blocks\":[],\"other_points\":[],\"action_items\":[],\"confidence\":0.9}");

        assertThat(output.blocks()).isEmpty();
    }

    @ParameterizedTest(name = "AC7_invalidDocument_{index}")
    @ValueSource(strings = {
            "",
            "not json at all",
            "{\"blocks\": [",
            "[]",
            "null",
            "{\"other_points\":[],\"action_items\":[]}",
            "{\"blocks\":\"nope\",\"other_points\":[],\"action_items\":[]}",
            "{\"blocks\":[{\"additions\":[]}],\"other_points\":[],\"action_items\":[]}",
            "{\"blocks\":[{\"user_block_id\":\"b\",\"additions\":[{\"sources\":[1]}]}],\"other_points\":[],\"action_items\":[]}",
            "{\"blocks\":[{\"user_block_id\":\"b\",\"additions\":[{\"text\":\"x\",\"sources\":[\"not-a-number\"]}]}],\"other_points\":[],\"action_items\":[]}"})
    void AC7_malformedOrIncompleteDocumentIsRejected(String text) {
        assertThatThrownBy(() -> EnhancementParser.parse(text)).isInstanceOf(InvalidEnhancementException.class);
    }

    @Test
    void AC7_parserDoesNotInventMissingSources() {
        EnhancementOutput output = EnhancementParser.parse(
                "{\"blocks\":[{\"user_block_id\":\"b\",\"additions\":[{\"text\":\"x\"}]}],\"other_points\":[],\"action_items\":[]}");

        assertThat(output.blocks().get(0).additions().get(0).sources()).isEmpty();
    }
}
