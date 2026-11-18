package app.interline.ai;

/** One row of the providers table, minus the key. {@code kind}: openai_compatible, anthropic or ollama. */
public record ProviderConfig(String kind, String baseUrl, String model, boolean supportsJsonSchema, Integer contextTokens) { }
