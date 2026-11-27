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
            ok(call(port,"DELETE","/trash/"+id,null),"erase retry");
            assertThat(call(port,"GET","/trash",null).json()).isEmpty();
            assertThat(M3Support.callWithAuth(port,"GET","/privacy",null,null).status()).isEqualTo(401);
            String privacy = ok(call(port,"GET","/privacy",null),"privacy").body();
            assertThat(privacy).contains("model_download", "not available", "eventkit").doesNotContain("secret_account", "sk-test");
        }
        try (var context = boot(data)) {
            assertThat(call(port(context),"GET","/settings/retention",null).json().path("audio_retention").asText()).isEqualTo("30d");
        }
    }
}
