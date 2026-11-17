package app.interline.app;

import app.interline.ai.ConnectionResult;
import app.interline.ai.ConnectionTester;
import app.interline.ai.LlmPort;
import app.interline.ai.ProviderConfig;
import app.interline.ai.SpringAiLlm;
import app.interline.domain.SecretStore;
import app.interline.domain.UuidV7;
import app.interline.persistence.ProviderStore;
import app.interline.persistence.ProviderStore.ProviderRow;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Providers and their API keys. A key is written to the secret store and read back for each call; it is never kept
 * here, in the database, in a log line or in an error message.
 */
final class ProviderService {
    static final Set<String> KINDS = Set.of("openai_compatible", "anthropic", "ollama");
    private static final String ACCOUNT_PREFIX = "provider.";
    private static final int MAX_KEY_LENGTH = 4096;
    private static final System.Logger LOG = System.getLogger(ProviderService.class.getName());

    private final ProviderStore providers;
    private final SecretStore secrets;
    private final ConnectionTester connectionTester;
    private final Clock clock;

    ProviderService(ProviderStore providers, SecretStore secrets, ConnectionTester connectionTester, Clock clock) {
        this.providers = providers;
        this.secrets = secrets;
        this.connectionTester = connectionTester;
        this.clock = clock;
    }

    List<ProviderRow> list() { return providers.list(); }

    ProviderRow get(String id) {
        return providers.find(id).orElseThrow(() -> ApiProblems.notFound("provider"));
    }

    Optional<ProviderRow> defaultProvider() { return providers.defaultProvider(); }

    ProviderRow create(JsonBody body) {
        ProviderRow row = read(body, null);
        providers.insert(row, Boolean.TRUE.equals(body.optionalFlag("is_default")));
        return get(row.id());
    }

    /** Fields absent from the body keep their value; a field sent as null clears it. */
    ProviderRow update(String id, JsonBody body) {
        ProviderRow row = read(body, get(id));
        providers.update(row, Boolean.TRUE.equals(body.optionalFlag("is_default")));
        return get(id);
    }

    void delete(String id) {
        ProviderRow row = get(id);
        if (row.hasKey()) forgetKey(row.secretAccount());
        providers.delete(id);
    }

    void saveKey(String id, String key) {
        ProviderRow row = get(id);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) throw ApiProblems.badRequest("key is required");
        String account = ACCOUNT_PREFIX + row.id();
        try {
            secrets.put(account, key);
        } catch (IllegalStateException e) {
            throw secretStoreUnavailable(e);
        }
        providers.setSecretAccount(row.id(), account);
    }

    void removeKey(String id) {
        ProviderRow row = get(id);
        if (row.hasKey()) {
            try {
                secrets.delete(row.secretAccount());
            } catch (IllegalStateException e) {
                throw secretStoreUnavailable(e);
            }
            providers.setSecretAccount(row.id(), null);
        }
    }

    /** One request to the provider, only because the user asked for it. */
    ConnectionResult test(String id) {
        ProviderRow row = get(id);
        return connectionTester.test(config(row), keyOf(row));
    }

    /** A model handle for one provider row; the key is fetched on every call. */
    LlmPort llm(ProviderRow row) {
        return new SpringAiLlm(config(row), () -> keyOf(row));
    }

    /** Estimated cost from the prices the user entered; unknown when either price is missing. */
    static Double cost(ProviderRow row, int inputTokens, int outputTokens) {
        if (row.priceInPerMtok() == null || row.priceOutPerMtok() == null) return null;
        return (inputTokens * row.priceInPerMtok() + outputTokens * row.priceOutPerMtok()) / 1_000_000.0;
    }

    static ProviderConfig config(ProviderRow row) {
        return new ProviderConfig(row.kind(), row.baseUrl(), row.model(), row.supportsJsonSchema(), row.contextTokens());
    }

    private String keyOf(ProviderRow row) {
        if (!row.hasKey()) return null;
        try {
            return secrets.get(row.secretAccount()).orElse(null);
        } catch (IllegalStateException e) {
            throw secretStoreUnavailable(e);
        }
    }

    private void forgetKey(String account) {
        try {
            secrets.delete(account);
        } catch (IllegalStateException e) {
            LOG.log(System.Logger.Level.WARNING, "could not delete a provider secret: {0}", e.getMessage());
        }
    }

    private static RuntimeException secretStoreUnavailable(IllegalStateException cause) {
        LOG.log(System.Logger.Level.WARNING, "secret store unavailable: {0}", cause.getMessage());
        return ApiProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "secret_store_unavailable",
                "The system secret store is not available. Check that the capture agent is running.");
    }

    private ProviderRow read(JsonBody body, ProviderRow current) {
        String kind = body.requiredChoice("kind", KINDS);
        String baseUrl = body.requiredText("base_url").strip();
        requireHttpUrl(baseUrl);
        boolean supportsJson = body.has("supports_json_schema") ? Boolean.TRUE.equals(body.optionalFlag("supports_json_schema"))
                : current != null && current.supportsJsonSchema();
        return new ProviderRow(current == null ? UuidV7.next(clock.millis()) : current.id(), kind,
                body.requiredText("name").strip(), baseUrl, body.requiredText("model").strip(),
                current == null ? null : current.secretAccount(), supportsJson, current != null && current.isDefault(),
                current == null ? clock.millis() : current.createdAt(),
                positiveInt(body, "context_tokens", current == null ? null : current.contextTokens()),
                price(body, "price_in_per_mtok", current == null ? null : current.priceInPerMtok()),
                price(body, "price_out_per_mtok", current == null ? null : current.priceOutPerMtok()));
    }

    private static Integer positiveInt(JsonBody body, String field, Integer fallback) {
        if (!body.has(field)) return fallback;
        Long value = body.optionalWholeNumber(field);
        if (value == null) return null;
        if (value <= 0 || value > Integer.MAX_VALUE) throw ApiProblems.badRequest(field + " must be positive");
        return value.intValue();
    }

    private static Double price(JsonBody body, String field, Double fallback) {
        if (!body.has(field)) return fallback;
        Double value = body.optionalNumber(field);
        if (value != null && value < 0) throw ApiProblems.badRequest(field + " must not be negative");
        return value;
    }

    private static void requireHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!http || uri.getHost() == null) throw ApiProblems.badRequest("base_url must be an http or https URL");
        } catch (URISyntaxException e) {
            throw ApiProblems.badRequest("base_url must be an http or https URL");
        }
    }
}
