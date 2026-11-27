package app.interline.app;

import app.interline.persistence.ProviderStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All real outbound clients must map to an entry here; PrivacyCatalogTest guards that inventory. */
final class PrivacyCatalog {
    static final Map<String,String> OUTBOUND_CLIENTS = Map.of(
            "HttpIcsFetcher.java", "ics", "ModelDownloader.java", "model_download",
            "SpringAiLlm.java", "llm", "ConnectionTester.java", "provider_test");
    static final String ACTION = "only after your action";
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Item(String id, String what, String when, String destinationHost, String state) { }

    static List<Item> items(ProviderStore providers, CalendarService calendar, ModelService models) {
        List<Item> items = new ArrayList<>();
        var configuredProviders = providers.list();
        for (var provider : configuredProviders) {
            String host = host(provider.baseUrl());
            items.add(new Item("llm:" + provider.id(), "Selected notes, transcript excerpts, instructions and API authentication", ACTION, host, "configured"));
            items.add(new Item("provider_test:" + provider.id(), "Model-list request and API authentication", ACTION, host, "configured"));
        }
        if (configuredProviders.isEmpty()) {
            items.add(new Item("llm", "Selected notes, transcript excerpts, instructions and API authentication", ACTION, null, "off"));
            items.add(new Item("provider_test", "Model-list request and API authentication", ACTION, null, "off"));
        }
        // Only EventKit is polled. ICS fetches occur exclusively on the explicit Sync request.
        try {
            var host = calendar.host();
            items.add(new Item("ics", "Calendar subscription request (including its private address)", ACTION, host.orElse(null), host.isPresent() ? "configured" : "off"));
        } catch (org.springframework.web.ErrorResponseException e) {
            items.add(new Item("ics", "Calendar subscription request; configuration unavailable while the secret store is unavailable", ACTION, null, "unknown"));
        }
        for (var status : models.statuses()) {
            items.add(new Item("model_download:" + status.entry().id(), "Model file request, IP address and optional resume offset", ACTION,
                    host(status.entry().url().toString()), "configured"));
        }
        if (models.statuses().isEmpty()) items.add(new Item("model_download", "Model file request", ACTION, null, "off"));
        items.add(new Item("auto_update", "not available", ACTION, null, "off"));
        items.add(new Item("eventkit", "Local calendar access through the agent; no network call", "continuously", null, "off"));
        items.add(new Item("agent", "Local capture, transcription and secret storage; no network call", ACTION, null, "off"));
        return List.copyOf(items);
    }

    static String host(String address) {
        try { return URI.create(address).getHost(); }
        catch (IllegalArgumentException e) { return null; }
    }
}
