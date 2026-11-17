package app.interline.ai;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link LlmPort} over Spring AI, with the ChatModel built programmatically for one provider row. The key is read
 * from {@code apiKey} on every call and lives only in the model object of that call: nothing is cached or logged.
 * No Spring AI starter is on the classpath, so no auto-configuration can reach out at startup.
 */
public final class SpringAiLlm implements LlmPort {
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int NO_RETRIES = 0;
    private static final int ANTHROPIC_MAX_TOKENS = 8192;
    /** Local OpenAI-compatible servers accept any bearer; the SDK refuses an empty one. */
    private static final String KEYLESS_PLACEHOLDER = "not-needed";

    private final ProviderConfig provider;
    private final Supplier<String> apiKey;

    /** @param apiKey returns the key for this call, or null when the provider needs none */
    public SpringAiLlm(ProviderConfig provider, Supplier<String> apiKey) {
        this.provider = provider;
        this.apiKey = apiKey;
    }

    @Override public LlmReply complete(LlmRequest request) {
        ChatModel model = chatModel(apiKey.get());
        ChatResponse response = model.call(new Prompt(List.of(
                new SystemMessage(request.system()), new UserMessage(request.user()))));
        if (response == null || response.getResult() == null || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("The model returned no text");
        }
        Usage usage = response.getMetadata().getUsage();
        return new LlmReply(response.getResult().getOutput().getText(), count(usage.getPromptTokens()),
                count(usage.getCompletionTokens()));
    }

    private ChatModel chatModel(String key) {
        return switch (provider.kind()) {
            case "anthropic" -> AnthropicChatModel.builder().options(AnthropicChatOptions.builder()
                    .baseUrl(provider.baseUrl()).apiKey(key).model(provider.model())
                    .maxTokens(ANTHROPIC_MAX_TOKENS).maxRetries(NO_RETRIES).timeout(CALL_TIMEOUT).build()).build();
            case "ollama" -> OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder().baseUrl(provider.baseUrl()).restClientBuilder(ollamaRestClient()).build())
                    .options(ollamaOptions()).build();
            default -> OpenAiChatModel.builder().options(openAiOptions(key)).build();
        };
    }

    private OpenAiChatOptions openAiOptions(String key) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().baseUrl(provider.baseUrl())
                .apiKey(key == null || key.isBlank() ? KEYLESS_PLACEHOLDER : key).model(provider.model())
                .maxRetries(NO_RETRIES).timeout(CALL_TIMEOUT);
        if (provider.supportsJsonSchema()) {
            builder.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build());
        }
        return builder.build();
    }

    private OllamaChatOptions ollamaOptions() {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder().model(provider.model());
        if (provider.supportsJsonSchema()) builder.format("json");
        if (provider.contextTokens() != null) builder.numCtx(provider.contextTokens());
        return builder.build();
    }

    /** Plain HTTP/1.1: the JDK client's cleartext h2c upgrade stalls against some local servers. */
    private static RestClient.Builder ollamaRestClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(CALL_TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }

    private static int count(Integer tokens) { return tokens == null ? 0 : tokens; }
}
