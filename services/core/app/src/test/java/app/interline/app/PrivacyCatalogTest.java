package app.interline.app;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class PrivacyCatalogTest {
    @Test void everyOutboundHttpClientIsDeclared() throws Exception {
        Set<String> clients = new HashSet<>();
        try (var paths = Files.walk(AppTestSupport.ROOT.resolve("services/core"))) {
            for (Path file : paths.filter(p -> p.toString().contains("/src/main/java/") && p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.matches("(?s).*import (java\\.net\\.http\\.|java\\.net\\.(URL|HttpURLConnection)|org\\.springframework\\.(web\\.client|web\\.reactive\\.function\\.client|ai\\.)|okhttp3\\.|org\\.apache\\.hc\\.).*"))
                    clients.add(file.getFileName().toString());
            }
        }
        assertThat(clients).as("Declare new network clients and their catalog entries").containsExactlyInAnyOrderElementsOf(PrivacyCatalog.OUTBOUND_CLIENTS.keySet());
    }
    @Test void everyShellNetworkCrateIsDeclaredAndHitsTheDeclaredHost() throws Exception {
        var tauri = AppTestSupport.ROOT.resolve("apps/desktop/src-tauri");
        Set<String> crates = new HashSet<>();
        for (String line : Files.readAllLines(tauri.resolve("Cargo.toml")))
            if (line.matches("^(tauri-plugin-updater|reqwest|ureq|hyper|isahc|surf|attohttpc|curl)\\s*=.*")) crates.add(line.split("\\s*=")[0]);
        assertThat(crates).as("Declare new shell network crates in PrivacyCatalog.OUTBOUND_SHELL_CRATES").containsExactlyInAnyOrderElementsOf(PrivacyCatalog.OUTBOUND_SHELL_CRATES.keySet());
        var endpoints = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tauri.resolve("tauri.conf.json").toFile()).at("/plugins/updater/endpoints");
        assertThat(endpoints).isNotEmpty();
        for (var endpoint : endpoints) assertThat(PrivacyCatalog.host(endpoint.asText())).isEqualTo(PrivacyCatalog.UPDATE_HOST);
        assertThat(Files.readString(tauri.resolve("capabilities/default.json"))).as("the updater needs no webview permission").doesNotContain("updater");
    }
    @Test void onlyHostCanLeaveCatalogForCredentialBearingUrls() {
        assertThat(PrivacyCatalog.host("https://user:sk-test-secret@example.com/private-token?key=sk-test-query")).isEqualTo("example.com");
        assertThat(PrivacyCatalog.host("not a url sk-test-secret")).isNull();
    }
}
