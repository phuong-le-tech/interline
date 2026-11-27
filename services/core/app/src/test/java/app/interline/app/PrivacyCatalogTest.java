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
    @Test void onlyHostCanLeaveCatalogForCredentialBearingUrls() {
        assertThat(PrivacyCatalog.host("https://user:sk-test-secret@example.com/private-token?key=sk-test-query")).isEqualTo("example.com");
        assertThat(PrivacyCatalog.host("not a url sk-test-secret")).isNull();
    }
}
