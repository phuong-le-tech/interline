package app.interline.app;

import app.interline.persistence.ProviderStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
class PrivacyController {
    private final ProviderStore providers;
    private final CalendarService calendar;
    private final ModelService models;
    PrivacyController(ProviderStore providers, CalendarService calendar, ModelService models) {
        this.providers = providers; this.calendar = calendar; this.models = models;
    }
    @GetMapping("/api/v1/privacy") List<PrivacyCatalog.Item> privacy() { return PrivacyCatalog.items(providers,calendar,models); }
}
