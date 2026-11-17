package app.interline.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The "Test connection" button: one cheap authenticated GET, only when {@link #test} is called. The result never
 * carries the key nor any part of the server's answer, which may echo it.
 */
public final class ConnectionTester {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int FIRST_ERROR_STATUS = 300;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT).build();

    /** @param apiKey null for providers that need none (Ollama, some local OpenAI-compatible servers) */
    public ConnectionResult test(ProviderConfig provider, String apiKey) {
        HttpRequest request;
        try {
            request = request(provider, apiKey);
        } catch (IllegalArgumentException e) {
            return new ConnectionResult(ConnectionResult.Outcome.UNREACHABLE, "The base URL is not valid");
        }
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == UNAUTHORIZED || status == FORBIDDEN) {
                return new ConnectionResult(ConnectionResult.Outcome.UNAUTHORIZED, "The provider rejected the key (HTTP " + status + ")");
            }
            if (status >= FIRST_ERROR_STATUS) {
                return new ConnectionResult(ConnectionResult.Outcome.UNREACHABLE, "The provider answered HTTP " + status);
            }
            return new ConnectionResult(ConnectionResult.Outcome.OK, "Connected");
        } catch (IOException e) {
            return new ConnectionResult(ConnectionResult.Outcome.UNREACHABLE, "Nothing answered at the base URL");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionResult(ConnectionResult.Outcome.UNREACHABLE, "Interrupted");
        }
    }

    private static HttpRequest request(ProviderConfig provider, String apiKey) {
        String base = provider.baseUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(REQUEST_TIMEOUT).GET();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        switch (provider.kind()) {
            case "anthropic" -> {
                builder.uri(URI.create(base + "/v1/models")).header("anthropic-version", ANTHROPIC_VERSION);
                if (hasKey) builder.header("x-api-key", apiKey);
            }
            case "ollama" -> builder.uri(URI.create(base + "/api/tags"));
            default -> {
                builder.uri(URI.create(base + "/models"));
                if (hasKey) builder.header("Authorization", "Bearer " + apiKey);
            }
        }
        return builder.build();
    }
}
