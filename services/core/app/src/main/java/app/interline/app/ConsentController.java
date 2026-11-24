package app.interline.app;

import app.interline.persistence.MeetingStore;
import app.interline.persistence.SettingsStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.Map;

/** A local reminder preference and acknowledgement, never a recording authorization gate. */
@RestController
@RequestMapping("/api/v1")
class ConsentController {
    private final SettingsStore settings;
    private final MeetingStore meetings;
    private final Clock clock;

    ConsentController(SettingsStore settings, MeetingStore meetings, Clock clock) {
        this.settings = settings;
        this.meetings = meetings;
        this.clock = clock;
    }

    @GetMapping("/settings/consent")
    Map<String, Boolean> settings() {
        return Map.of("reminder_enabled", settings.consentReminderEnabled());
    }

    @PutMapping("/settings/consent")
    Map<String, Boolean> update(@RequestBody Map<String, Object> request) {
        Boolean enabled = new JsonBody(request).optionalFlag("reminder_enabled");
        if (enabled == null) throw ApiProblems.badRequest("reminder_enabled is required");
        settings.setConsentReminderEnabled(enabled, clock.millis());
        return Map.of("reminder_enabled", enabled);
    }

    @PostMapping("/meetings/{id}/consent-ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(@PathVariable("id") String id) {
        if (!meetings.acknowledgeConsent(id, clock.millis())) throw ApiProblems.notFound("meeting");
    }
}
