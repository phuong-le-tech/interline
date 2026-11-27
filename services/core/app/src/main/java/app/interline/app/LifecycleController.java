package app.interline.app;

import app.interline.persistence.LifecycleStore;
import app.interline.persistence.SettingsStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class LifecycleController {
    private final LifecycleStore lifecycle;
    private final SettingsStore settings;
    private final Clock clock;
    LifecycleController(LifecycleStore lifecycle, SettingsStore settings, Clock clock) {
        this.lifecycle = lifecycle; this.settings = settings; this.clock = clock;
    }
    @GetMapping("/settings/retention")
    Map<String,String> retention() { return Map.of("audio_retention", settings.audioRetention()); }
    @PutMapping("/settings/retention")
    Map<String,String> retention(@RequestBody Map<String,Object> body) {
        String value = new JsonBody(body).requiredText("audio_retention");
        try { settings.setAudioRetention(value, clock.millis()); }
        catch (IllegalArgumentException e) { throw ApiProblems.badRequest("Unknown audio retention"); }
        return retention();
    }
    @DeleteMapping("/meetings/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void trash(@PathVariable("id") String id) {
        if (!lifecycle.trash(id,clock.millis())) throw ApiProblems.of(HttpStatus.CONFLICT,"meeting_not_deletable","Meeting is missing, recording or processing");
    }
    @GetMapping("/trash") List<LifecycleStore.Trashed> trash() { return lifecycle.trash(); }
    @PostMapping("/meetings/{id}/restore") @ResponseStatus(HttpStatus.NO_CONTENT)
    void restore(@PathVariable("id") String id) {
        if (!lifecycle.restore(id,clock.millis())) throw ApiProblems.of(HttpStatus.CONFLICT,"meeting_not_restorable","Meeting is missing or permanent deletion has started");
    }
    @DeleteMapping("/trash/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void erase(@PathVariable("id") String id) { lifecycle.hardDelete(id); }
}
