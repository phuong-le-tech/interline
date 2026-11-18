package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * M2 enhancement over the real HTTP API, real database, real MockAgent secret store. The model is a loopback
 * OpenAI-compatible stub (FakeOpenAi) behind a real provider row. Golden meeting 1 is the subject.
 */
@Timeout(180)
class M2EnhanceTest {
    private static final String FORMAT_MESSAGE = "The model did not respect the format";

    @TempDir Path data;
    private FakeOpenAi stub;
    private ConfigurableApplicationContext context;
    private int port;
    private Golden golden;
    private String meeting;

    @BeforeEach void start() {
        stub = new FakeOpenAi();
        context = boot(data);
        port = port(context);
        golden = golden(1);
        meeting = golden.meetingId();
        seedMeeting(data, golden);
        putNotes(port, golden);
    }

    @AfterEach void stop() {
        context.close();
        stub.close();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private JsonNode enhanceWith(String... replies) {
        stub.reply(replies);
        return ok(enhance(port, meeting, null), "enhance").json();
    }

    private List<JsonNode> userBlocks() {
        return blocksOf(blocks(port, meeting), "user");
    }

    private List<JsonNode> aiBlocks() {
        return blocksOf(blocks(port, meeting), "ai");
    }

    /** The fields of a user block that must never change (updated_at and the like are not part of "the user's words"). */
    private static String signature(JsonNode block) {
        return String.join("|", block.path("id").asText(), block.path("kind").asText(), block.path("position").asText(),
                block.path("content").asText(), block.path("plain_text").asText(), block.path("anchor_ms").asText(),
                block.path("origin").asText(), block.path("client_rev").asText());
    }

    private static List<String> signatures(List<JsonNode> blocks) {
        return blocks.stream().map(M2EnhanceTest::signature).toList();
    }

    private JsonNode additionUnder(String userBlockId) {
        return aiBlocks().stream().filter(b -> userBlockId.equals(b.path("parent_id").asText(null))).findFirst().orElseThrow();
    }

    private static ObjectNode parse(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The golden reply plus one addition under {@code blockId} (a new block entry when the id is not in the reply). */
    private static String withExtraAddition(String reply, String blockId, String text, long... sources) {
        ObjectNode root = parse(reply);
        ArrayNode blocks = (ArrayNode) root.path("blocks");
        ObjectNode target = null;
        for (JsonNode block : blocks) if (block.path("user_block_id").asText().equals(blockId)) target = (ObjectNode) block;
        if (target == null) {
            target = blocks.addObject().put("user_block_id", blockId);
            target.putArray("additions");
        }
        ObjectNode addition = ((ArrayNode) target.path("additions")).addObject().put("text", text);
        ArrayNode cited = addition.putArray("sources");
        for (long source : sources) cited.add(source);
        return root.toString();
    }

    private record Bad(String reply, String marker, String text) { }

    private Bad bad(String kind) {
        String first = golden.notes().get(0).id();
        return switch (kind) {
            case "unknown_block" -> new Bad(withExtraAddition(golden.reply(), "ghost-block", "Ajout sous un bloc inexistant.", 1002),
                    "ghost-block", "Ajout sous un bloc inexistant.");
            case "unsourced" -> new Bad(withExtraAddition(golden.reply(), first, "Ajout sans la moindre citation."),
                    "Ajout sans la moindre citation.", "Ajout sans la moindre citation.");
            case "unknown_source" -> new Bad(withExtraAddition(golden.reply(), first, "Ajout citant un segment fantome.", 424242),
                    "424242", "Ajout citant un segment fantome.");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    // ---- AC4 ----------------------------------------------------------------------------------------------------

    @Test void AC4_enhancePreserves_userBlocksStayByteIdenticalAndAiAdditionsAreCitedChildren() {
        provider(port, stub, Map.of());
        List<String> before = signatures(userBlocks());

        JsonNode run = enhanceWith(golden.reply());

        assertThat(run.path("status").asText()).isEqualTo("ok");
        assertThat(run.path("run_id").asText()).isNotBlank();
        assertThat(signatures(userBlocks())).isEqualTo(before);
        JsonNode reply = parse(golden.reply());
        for (JsonNode expected : reply.path("blocks")) {
            JsonNode addition = additionUnder(expected.path("user_block_id").asText());
            JsonNode wanted = expected.path("additions").get(0);
            assertThat(addition.path("origin").asText()).isEqualTo("ai");
            assertThat(addition.path("ai_state").asText()).isEqualTo("proposed");
            assertThat(addition.path("plain_text").asText()).isEqualTo(wanted.path("text").asText());
            assertThat(addition.path("sources")).extracting(JsonNode::asLong)
                    .containsExactly(wanted.path("sources").get(0).asLong());
        }
    }

    @Test void AC4_enhancePreserves_otherPointsAreAiBlocksWithoutParentAndWithSources() {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        JsonNode expected = parse(golden.reply()).path("other_points").get(0);
        List<JsonNode> others = aiBlocks().stream().filter(b -> b.path("parent_id").isMissingNode() || b.path("parent_id").isNull()).toList();
        assertThat(others).singleElement().satisfies(other -> {
            assertThat(other.path("plain_text").asText()).isEqualTo(expected.path("text").asText());
            assertThat(other.path("ai_state").asText()).isEqualTo("proposed");
            assertThat(other.path("sources")).extracting(JsonNode::asLong).containsExactly(expected.path("sources").get(0).asLong());
        });
        assertThat(aiBlocks()).hasSize(4);
    }

    @Test void AC4_enhancePreserves_actionItemsAreStoredWithOwnerDueDateAndSource() {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        JsonNode actions = ok(call(port, "GET", "/meetings/" + meeting + "/actions", null), "actions").json();
        assertThat(actions).hasSize(2);
        JsonNode first = actions.get(0);
        assertThat(first.path("text").asText()).isEqualTo("Preparer la reunion de cadrage avec la finance");
        assertThat(first.path("owner").asText()).isEqualTo("Me");
        assertThat(first.path("due_date").asText()).isEqualTo("2026-11-26");
        assertThat(first.path("status").asText()).isEqualTo("open");
        assertThat(first.path("source_segment_id").asLong()).isEqualTo(1003);
        assertThat(actions.get(1).path("due_date").isMissingNode() || actions.get(1).path("due_date").isNull()).isTrue();
    }

    @Test void AC4_enhancePreserves_unicodeAndTrailingSpacesSurviveTheRun() throws Exception {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        assertThat(rows(data, "SELECT plain_text FROM note_blocks WHERE id = ?", golden.notes().get(0).id()))
                .containsExactly(Map.of("plain_text", "Budget T3 validé, réunion cadrage  "));
    }

    @Test void AC4_enhance_promptHasTheNotesAndTheTranscriptButNotSuppressedSegments() {
        provider(port, stub, Map.of());
        suppress(data, 1003);

        enhanceWith(golden.reply());

        String prompt = stub.chatCalls().get(0).prompt();
        assertThat(prompt).contains("Budget T3 validé, réunion cadrage", "Contrats clients: 3 en attente", "Nous avons aussi un retard");
        assertThat(prompt).doesNotContain("je prepare la reunion de cadrage pour jeudi prochain");
    }

    @Test void AC4_enhance_keyReachesTheProviderAsABearerTokenFetchedPerCall() {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        assertThat(stub.chatCalls().get(0).headers().get("authorization")).isEqualTo("Bearer " + KEY);
    }

    // ---- AC5 ----------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "AC5_retryOnce_{0}")
    @ValueSource(strings = {"unknown_block", "unsourced", "unknown_source"})
    void AC5_retryOnce_aViolationTriggersExactlyOneRetryAndTheRetryPromptListsIt(String kind) {
        provider(port, stub, Map.of());
        Bad bad = bad(kind);

        JsonNode run = enhanceWith(bad.reply(), golden.reply());

        assertThat(stub.chatCalls()).hasSize(2);
        assertThat(stub.chatCalls().get(1).prompt()).contains(bad.marker());
        assertThat(run.path("status").asText()).isEqualTo("ok");
        assertThat(aiBlocks()).extracting(b -> b.path("plain_text").asText()).doesNotContain(bad.text());
        assertThat(aiBlocks()).hasSize(4);
    }

    @ParameterizedTest(name = "AC5_retryOnce_violatorsNeverReachTheDb_{0}")
    @ValueSource(strings = {"unknown_block", "unsourced", "unknown_source"})
    void AC5_retryOnce_whenTheSecondAttemptStillViolatesOnlyTheViolatorIsDropped(String kind) throws Exception {
        provider(port, stub, Map.of());
        Bad bad = bad(kind);

        JsonNode run = enhanceWith(bad.reply());

        assertThat(stub.chatCalls()).hasSize(2);
        assertThat(run.path("status").asText()).isEqualTo("ok");
        assertThat(aiBlocks()).extracting(b -> b.path("plain_text").asText()).doesNotContain(bad.text()).hasSize(4);
        assertThat(rows(data, "SELECT id FROM note_blocks WHERE plain_text = ?", bad.text())).isEmpty();
    }

    @Test void AC5_retryOnce_aCleanFirstAnswerIsNotRetried() {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        assertThat(stub.chatCalls()).hasSize(1);
    }

    // ---- AC7 ----------------------------------------------------------------------------------------------------

    @Test void AC7_invalidOutput_notJsonTwiceLeavesTheNotesUntouchedAndSaysSo() throws Exception {
        provider(port, stub, Map.of());
        List<String> before = signatures(blocks(port, meeting));

        stub.reply("Sure, here are some notes!", "Sorry, here they are again.");
        var response = enhance(port, meeting, Map.of());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("status").asText()).isEqualTo("invalid_output");
        assertThat(response.json().path("message").asText()).isEqualTo(FORMAT_MESSAGE);
        assertThat(signatures(blocks(port, meeting))).isEqualTo(before);
        assertThat(rows(data, "SELECT status FROM ai_runs WHERE meeting_id = ? AND kind = 'enhance' ORDER BY started_at DESC LIMIT 1", meeting))
                .containsExactly(Map.of("status", "invalid_output"));
        assertThat(ok(call(port, "GET", "/meetings/" + meeting + "/actions", null), "actions").json()).isEmpty();
    }

    // ---- AC8 ----------------------------------------------------------------------------------------------------

    @Test void AC8_undoAll_restoresTheRawUserDocumentAndRemovesEveryAiBlockAndAction() throws Exception {
        provider(port, stub, Map.of());
        List<String> before = signatures(userBlocks());
        enhanceWith(golden.reply());
        String accepted = aiBlocks().get(0).path("id").asText();
        ok(call(port, "POST", "/meetings/" + meeting + "/blocks/" + accepted + "/accept", Map.of()), "accept");

        ok(call(port, "POST", "/meetings/" + meeting + "/enhance/undo", Map.of()), "undo");

        assertThat(signatures(blocks(port, meeting))).isEqualTo(before);
        assertThat(aiBlocks()).isEmpty();
        assertThat(ok(call(port, "GET", "/meetings/" + meeting + "/actions", null), "actions").json()).isEmpty();
        var snapshots = rows(data, "SELECT doc FROM note_snapshots WHERE meeting_id = ? AND kind = 'raw_user'", meeting);
        assertThat(snapshots).hasSize(1);
        assertThat(allStrings(JSON.readTree((String) snapshots.get(0).get("doc"))))
                .contains(golden.notes().stream().map(n -> n.text()).toArray(String[]::new));
    }

    @Test void AC8_undoAll_writesTheRawUserSnapshotBeforeTheModelIsCalled() throws Exception {
        provider(port, stub, Map.of());
        stub.reply("not json", "still not json");

        enhance(port, meeting, Map.of());

        assertThat(rows(data, "SELECT kind FROM note_snapshots WHERE meeting_id = ?", meeting))
                .containsExactly(Map.of("kind", "raw_user"));
    }

    @Test void AC8_reEnhance_keepsAcceptedAndEditedAdditionsReplacesProposedAndNeverTouchesUserBlocks() throws Exception {
        provider(port, stub, Map.of());
        List<String> userBefore = signatures(userBlocks());
        enhanceWith(golden.reply());
        JsonNode first = additionUnder(golden.notes().get(0).id());
        JsonNode second = additionUnder(golden.notes().get(1).id());
        JsonNode third = additionUnder(golden.notes().get(2).id());
        ok(call(port, "POST", "/meetings/" + meeting + "/blocks/" + first.path("id").asText() + "/accept", Map.of()), "accept");
        ok(putBlock(port, meeting, second.path("id").asText(), 1, "paragraph", second.path("position").asText(),
                "Texte edite par moi.", null), "edit");
        String replacement = "Nouvel ajout pour le troisieme bloc de la reunion.";
        String reply2 = withExtraAddition("{\"blocks\":[],\"other_points\":[],\"action_items\":[]}",
                golden.notes().get(2).id(), replacement, 1006);

        enhanceWith(reply2);

        assertThat(signatures(userBlocks())).isEqualTo(userBefore);
        List<JsonNode> ai = aiBlocks();
        assertThat(ai).extracting(b -> b.path("id").asText()).contains(first.path("id").asText(), second.path("id").asText())
                .doesNotContain(third.path("id").asText());
        assertThat(ai).filteredOn(b -> b.path("id").asText().equals(first.path("id").asText())).singleElement().satisfies(b -> {
            assertThat(b.path("ai_state").asText()).isEqualTo("accepted");
            assertThat(b.path("plain_text").asText()).isEqualTo(first.path("plain_text").asText());
        });
        assertThat(ai).filteredOn(b -> b.path("id").asText().equals(second.path("id").asText())).singleElement().satisfies(b -> {
            assertThat(b.path("ai_state").asText()).isEqualTo("edited");
            assertThat(b.path("plain_text").asText()).isEqualTo("Texte edite par moi.");
        });
        assertThat(ai).filteredOn(b -> b.path("plain_text").asText().equals(replacement)).singleElement()
                .satisfies(b -> assertThat(b.path("ai_state").asText()).isEqualTo("proposed"));
        assertThat(ai).hasSize(3);
        assertThat(rows(data, "SELECT id FROM note_snapshots WHERE meeting_id = ? AND kind = 'raw_user'", meeting)).hasSize(2);
        assertThat(rows(data, "SELECT id FROM ai_runs WHERE meeting_id = ? AND kind = 'enhance'", meeting)).hasSize(2);
    }

    // ---- AC9 ----------------------------------------------------------------------------------------------------

    @Test void AC9_additionStates_acceptEditAndDeleteEachChangeOnlyTheirAddition() throws Exception {
        provider(port, stub, Map.of());
        enhanceWith(golden.reply());
        JsonNode toAccept = additionUnder(golden.notes().get(0).id());
        JsonNode toEdit = additionUnder(golden.notes().get(1).id());
        JsonNode toDelete = additionUnder(golden.notes().get(2).id());

        ok(call(port, "POST", "/meetings/" + meeting + "/blocks/" + toAccept.path("id").asText() + "/accept", Map.of()), "accept");
        ok(putBlock(port, meeting, toEdit.path("id").asText(), 1, "paragraph", toEdit.path("position").asText(), "Version reprise a ma facon.", null), "edit");
        ok(call(port, "DELETE", "/meetings/" + meeting + "/blocks/" + toDelete.path("id").asText(), null), "delete");

        List<JsonNode> ai = aiBlocks();
        assertThat(ai).extracting(b -> b.path("id").asText()).doesNotContain(toDelete.path("id").asText());
        assertThat(ai).filteredOn(b -> b.path("id").asText().equals(toAccept.path("id").asText())).singleElement()
                .satisfies(b -> assertThat(b.path("ai_state").asText()).isEqualTo("accepted"));
        assertThat(ai).filteredOn(b -> b.path("id").asText().equals(toEdit.path("id").asText())).singleElement().satisfies(b -> {
            assertThat(b.path("ai_state").asText()).isEqualTo("edited");
            assertThat(b.path("origin").asText()).isEqualTo("ai");
            assertThat(b.path("parent_id").asText()).isEqualTo(golden.notes().get(1).id());
            assertThat(b.path("plain_text").asText()).isEqualTo("Version reprise a ma facon.");
        });
        assertThat(rows(data, "SELECT block_id FROM block_sources WHERE block_id = ?", toDelete.path("id").asText())).isEmpty();
        assertThat(userBlocks()).hasSize(3);
    }

    @Test void AC9_additionStates_acceptingAUserBlockIsRejected() {
        provider(port, stub, Map.of());

        var response = call(port, "POST", "/meetings/" + meeting + "/blocks/" + golden.notes().get(0).id() + "/accept", Map.of());

        assertThat(response.status()).isBetween(400, 409);
        assertThat(userBlocks()).hasSize(3);
    }

    // ---- AC10 ---------------------------------------------------------------------------------------------------

    @Test void AC10_windowed_smallContextTokensMeansOneCallPerBlockPlusOneForOtherPoints() {
        provider(port, stub, Map.of("context_tokens", 100));

        JsonNode run = enhanceWith("{\"blocks\":[],\"other_points\":[],\"action_items\":[]}");

        assertThat(run.path("status").asText()).isEqualTo("ok");
        assertThat(stub.chatCalls()).hasSize(4);
    }

    @Test void AC10_full_unknownContextSizeMeansASingleCall() {
        provider(port, stub, Map.of());

        enhanceWith("{\"blocks\":[],\"other_points\":[],\"action_items\":[]}");

        assertThat(stub.chatCalls()).hasSize(1);
    }

    @Test void AC10_full_contextLargeEnoughStaysInFullMode() {
        provider(port, stub, Map.of("context_tokens", 128_000));

        enhanceWith("{\"blocks\":[],\"other_points\":[],\"action_items\":[]}");

        assertThat(stub.chatCalls()).hasSize(1);
    }

    // ---- AC14 ---------------------------------------------------------------------------------------------------

    @Test void AC14_runRecord_storesProviderModelVersionTokensStatusAndCostFromUserPrices() throws Exception {
        String providerId = provider(port, stub, Map.of("price_in_per_mtok", 3.0, "price_out_per_mtok", 15.0));

        enhanceWith(golden.reply());

        var runs = rows(data, "SELECT provider_id, model, prompt_version, input_tokens, output_tokens, status, cost_estimate_usd "
                + "FROM ai_runs WHERE meeting_id = ? AND kind = 'enhance'", meeting);
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.get("provider_id")).isEqualTo(providerId);
            assertThat(run.get("model")).isEqualTo("stub-model");
            assertThat(run.get("prompt_version")).isEqualTo("enhance/v1");
            assertThat(((Number) run.get("input_tokens")).intValue()).isEqualTo(FakeOpenAi.PROMPT_TOKENS);
            assertThat(((Number) run.get("output_tokens")).intValue()).isEqualTo(FakeOpenAi.COMPLETION_TOKENS);
            assertThat(run.get("status")).isEqualTo("ok");
            assertThat(((Number) run.get("cost_estimate_usd")).doubleValue()).isCloseTo(0.006, within(1e-9));
        });
        JsonNode listed = ok(call(port, "GET", "/meetings/" + meeting + "/ai-runs", null), "ai-runs").json().get(0);
        assertThat(listed.path("kind").asText()).isEqualTo("enhance");
        assertThat(listed.path("prompt_version").asText()).isEqualTo("enhance/v1");
        assertThat(listed.path("cost_estimate_usd").asDouble()).isCloseTo(0.006, within(1e-9));
    }

    @Test void AC14_runRecord_noPricesMeansNoCostRatherThanAnInventedOne() throws Exception {
        provider(port, stub, Map.of());

        enhanceWith(golden.reply());

        assertThat(rows(data, "SELECT cost_estimate_usd FROM ai_runs WHERE meeting_id = ?", meeting))
                .singleElement().satisfies(run -> assertThat(run.get("cost_estimate_usd")).isNull());
        JsonNode listed = ok(call(port, "GET", "/meetings/" + meeting + "/ai-runs", null), "ai-runs").json().get(0);
        assertThat(listed.hasNonNull("cost_estimate_usd")).isFalse();
    }

    @Test void AC14_runRecord_aRetriedRunSumsTheTokensOfBothAttempts() throws Exception {
        provider(port, stub, Map.of());

        enhanceWith(bad("unsourced").reply(), golden.reply());

        assertThat(rows(data, "SELECT input_tokens, output_tokens FROM ai_runs WHERE meeting_id = ?", meeting))
                .singleElement().satisfies(run -> {
                    assertThat(((Number) run.get("input_tokens")).intValue()).isEqualTo(2 * FakeOpenAi.PROMPT_TOKENS);
                    assertThat(((Number) run.get("output_tokens")).intValue()).isEqualTo(2 * FakeOpenAi.COMPLETION_TOKENS);
                });
    }

    // ---- AC15 ---------------------------------------------------------------------------------------------------

    private List<JsonNode> templates() {
        List<JsonNode> templates = new ArrayList<>();
        ok(call(port, "GET", "/templates", null), "templates").json().forEach(templates::add);
        return templates;
    }

    @Test void AC15_templatePrompt_fourBuiltInTemplatesAreListed() {
        assertThat(templates()).hasSize(4).allSatisfy(t -> {
            assertThat(t.path("builtin").asBoolean()).isTrue();
            assertThat(t.path("instructions").asText()).isNotBlank();
        });
        assertThat(templates()).extracting(t -> t.path("name").asText().toLowerCase())
                .anySatisfy(n -> assertThat(n).contains("1:1")).anySatisfy(n -> assertThat(n).contains("client"))
                .anySatisfy(n -> assertThat(n).contains("interview")).anySatisfy(n -> assertThat(n).contains("team"));
    }

    @ParameterizedTest(name = "AC15_templatePrompt_{0}")
    @ValueSource(strings = {"1:1", "client", "interview", "team"})
    void AC15_templatePrompt_theChosenTemplateChangesThePromptSentToTheModel(String key) throws Exception {
        provider(port, stub, Map.of());
        List<JsonNode> all = templates();
        JsonNode chosen = all.stream().filter(t -> t.path("name").asText().toLowerCase().contains(key)).findFirst().orElseThrow();
        stub.reply(golden.reply());

        ok(enhance(port, meeting, Map.of("template_id", chosen.path("id").asText())), "enhance");

        String prompt = stub.chatCalls().get(0).prompt();
        assertThat(prompt).contains(chosen.path("instructions").asText());
        assertThat(all.stream().filter(t -> !t.path("id").asText().equals(chosen.path("id").asText()))
                .map(t -> t.path("instructions").asText()).toList()).noneSatisfy(other -> assertThat(prompt).contains(other));
        assertThat(rows(data, "SELECT template_id FROM meetings WHERE id = ?", meeting))
                .containsExactly(Map.of("template_id", chosen.path("id").asText()));
    }

    @Test void AC15_templatePrompt_unknownTemplateIsABadRequestAndCallsNoModel() {
        provider(port, stub, Map.of());

        var response = enhance(port, meeting, Map.of("template_id", "no-such-template"));

        assertThat(response.status()).isBetween(400, 404);
        assertThat(stub.chatCalls()).isEmpty();
    }

    // ---- AC16 ---------------------------------------------------------------------------------------------------

    @Test void AC16_followup_returnsADraftWithItsSourcesAndRecordsTheRun() throws Exception {
        provider(port, stub, Map.of());
        stub.reply("{\"subject\":\"Suite du point hebdo\",\"body\":\"Bonjour,\\nLe budget est valide.\",\"sources\":[1002,1004]}");

        var response = ok(call(port, "POST", "/meetings/" + meeting + "/followup-email", Map.of()), "follow-up");

        JsonNode draft = response.json();
        assertThat(draft.path("status").asText()).isEqualTo("ok");
        assertThat(draft.path("subject").asText()).isEqualTo("Suite du point hebdo");
        assertThat(draft.path("body").asText()).isEqualTo("Bonjour,\nLe budget est valide.");
        assertThat(draft.path("sources")).extracting(JsonNode::asLong).containsExactly(1002L, 1004L);
        assertThat(rows(data, "SELECT prompt_version, status FROM ai_runs WHERE meeting_id = ? AND kind = 'followup_email'", meeting))
                .containsExactly(Map.of("prompt_version", "followup_email/v1", "status", "ok"));
        assertThat(userBlocks()).hasSize(3);
        assertThat(aiBlocks()).isEmpty();
    }

    @ParameterizedTest(name = "AC16_followup_noSendRoute_{0}")
    @ValueSource(strings = {"/meetings/gold-1/followup-email/send", "/meetings/gold-1/email/send", "/meetings/gold-1/send",
            "/followup-email/send", "/email/send"})
    void AC16_followup_thereIsNoRouteThatSendsEmail(String path) {
        assertThat(call(port, "POST", path, Map.of()).status()).isIn(404, 405);
    }
}
