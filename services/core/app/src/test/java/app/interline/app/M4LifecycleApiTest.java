package app.interline.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

class M4LifecycleApiTest {
    @TempDir Path data;
    @Test void retentionSurvivesRestartAndTrashCanBeRestoredOrPermanentlyDeleted() {
        try (var context = boot(data)) {
            int port = port(context);
            assertThat(call(port,"GET","/settings/retention",null).json().path("audio_retention").asText()).isEqualTo("7d");
            assertThat(call(port,"PUT","/settings/retention",Map.of("audio_retention","typo")).status()).isEqualTo(400);
            ok(call(port,"PUT","/settings/retention",Map.of("audio_retention","30d")),"retention");
            String id = ok(call(port,"POST","/meetings",Map.of("title","Original")),"create").json().path("id").asText();
            ok(call(port,"DELETE","/meetings/"+id,null),"trash");
            assertThat(call(port,"GET","/meetings",null).json()).isEmpty();
            assertThat(call(port,"GET","/trash",null).json().get(0).path("id").asText()).isEqualTo(id);
            ok(call(port,"POST","/meetings/"+id+"/restore",null),"restore");
            assertThat(call(port,"GET","/meetings",null).json()).hasSize(1);
            ok(call(port,"DELETE","/meetings/"+id,null),"trash again");
            ok(call(port,"DELETE","/trash/"+id,null),"erase");
            assertThat(call(port,"DELETE","/trash/"+id,null).status()).isEqualTo(404);
            assertThat(call(port,"GET","/trash",null).json()).isEmpty();
            assertThat(M3Support.callWithAuth(port,"GET","/privacy",null,null).status()).isEqualTo(401);
            String privacy = ok(call(port,"GET","/privacy",null),"privacy").body();
            assertThat(privacy).contains("model_download", "update_check", "github.com", "eventkit").doesNotContain("secret_account", "sk-test");
        }
        try (var context = boot(data)) {
            assertThat(call(port(context),"GET","/settings/retention",null).json().path("audio_retention").asText()).isEqualTo("30d");
        }
    }
    @Test void privacyReportsConfiguredHostsWithoutKeysAddressesOrUserInfo() {
        try (var context = boot(data)) {
            int port = port(context);
            var values = new java.util.HashMap<String,String>();
            values.put("calendar.ics_url", "https://calendar.example.com/sk-test-private?key=sk-test-query");
            context.getBean(AgentSecrets.class).bind(new app.interline.domain.SecretStore() {
                public java.util.Optional<String> get(String account) { return java.util.Optional.ofNullable(values.get(account)); }
                public void put(String account,String value) { values.put(account,value); }
                public void delete(String account) { values.remove(account); }
            });
            context.getBean(CalendarService.class).setSource("https://calendar.example.com/sk-test-private?key=sk-test-query");
            context.getBean(app.interline.persistence.ProviderStore.class).insert(new app.interline.persistence.ProviderStore.ProviderRow(
                    "test-provider","openai_compatible","Name","https://user:sk-test-password@llm.example.com/sk-test-path?key=sk-test-query",
                    "model","sk-test-account",false,true,0,null,null,null),true);
            String response = ok(call(port,"GET","/privacy",null),"privacy catalog").body();
            assertThat(response).contains("calendar.example.com","llm.example.com","provider_test:test-provider","configured")
                    .doesNotContain("sk-test", "user:", "https://", "secret_account", "base_url");
            context.getBean(AgentSecrets.class).unbind();
            assertThat(call(port,"GET","/privacy",null).status()).isEqualTo(503);
        }
    }

    @Test void deleteMissingOrNotTrashedMeetingsReturns404() {
        try (var context = boot(data)) {
            int port = port(context);
            assertThat(call(port,"DELETE","/meetings/unknown",null).status()).isEqualTo(404);
            assertThat(call(port,"DELETE","/trash/unknown",null).status()).isEqualTo(404);
            String id = ok(call(port,"POST","/meetings",Map.of("title","Live")),"create").json().path("id").asText();
            assertThat(call(port,"DELETE","/trash/"+id,null).status()).isEqualTo(404);
            assertThat(call(port,"GET","/meetings",null).json()).hasSize(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"GET,brief", "GET,blocks", "POST,enhance", "GET,export?format=markdown",
            "GET,transcript", "GET,actions", "GET,ai-runs", "POST,brief/ai", "POST,followup-email"})
    void trashedMeetingReadersAndGeneratorsReturn404(String method,String route) {
        try (var context = boot(data)) {
            int port = port(context);
            String id = ok(call(port,"POST","/meetings",Map.of("title","Private")),"create").json().path("id").asText();
            ok(call(port,"DELETE","/meetings/"+id,null),"trash");
            assertThat(call(port,method,"/meetings/"+id+"/"+route,method.equals("POST") ? Map.of() : null).status()).as(route).isEqualTo(404);
        }
    }

}
