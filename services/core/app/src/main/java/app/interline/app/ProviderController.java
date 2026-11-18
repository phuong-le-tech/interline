package app.interline.app;

import app.interline.ai.ConnectionResult;
import app.interline.persistence.ProviderStore.ProviderRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model providers. Views report whether a key is stored ({@code has_key}) and never the key or its account. */
@RestController
@RequestMapping("/api/v1/providers")
class ProviderController {
    private final ProviderService providers;

    ProviderController(ProviderService providers) { this.providers = providers; }

    /** The API key in a request body. Its text form is redacted so request logging can never print it. */
    record KeyRequest(String key) {
        @Override public String toString() { return "KeyRequest[redacted]"; }
    }

    @GetMapping
    List<Map<String, Object>> list() {
        return providers.list().stream().map(ProviderController::view).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable("id") String id) { return view(providers.get(id)); }

    @PostMapping
    Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return view(providers.create(new JsonBody(request)));
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable("id") String id, @RequestBody Map<String, Object> request) {
        return view(providers.update(id, new JsonBody(request)));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") String id) {
        providers.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/key")
    ResponseEntity<Void> saveKey(@PathVariable("id") String id, @RequestBody KeyRequest request) {
        providers.saveKey(id, request.key());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/key")
    ResponseEntity<Void> removeKey(@PathVariable("id") String id) {
        providers.removeKey(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    Map<String, Object> test(@PathVariable("id") String id) {
        ConnectionResult result = providers.test(id);
        return Map.of("status", result.outcome().name().toLowerCase(), "detail", result.detail());
    }

    private static Map<String, Object> view(ProviderRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("kind", row.kind());
        view.put("name", row.name());
        view.put("base_url", row.baseUrl());
        view.put("model", row.model());
        view.put("supports_json_schema", row.supportsJsonSchema());
        view.put("is_default", row.isDefault());
        view.put("has_key", row.hasKey());
        if (row.contextTokens() != null) view.put("context_tokens", row.contextTokens());
        if (row.priceInPerMtok() != null) view.put("price_in_per_mtok", row.priceInPerMtok());
        if (row.priceOutPerMtok() != null) view.put("price_out_per_mtok", row.priceOutPerMtok());
        return view;
    }
}
