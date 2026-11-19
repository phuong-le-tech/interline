package app.interline.app;

import app.interline.persistence.CalendarStore;
import app.interline.persistence.CalendarStore.EventRow;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Announces each calendar event once, in the minute before it starts. It only announces: it never starts a
 * recording. It reads the local database, so it makes no network call.
 */
final class UpcomingNotifier implements AutoCloseable {
    static final Duration LEAD = Duration.ofMinutes(1);
    private static final Duration TICK = Duration.ofSeconds(10);
    private static final System.Logger LOG = System.getLogger(UpcomingNotifier.class.getName());

    private final CalendarStore store;
    private final Clock clock;
    private final Consumer<Map<String, Object>> announce;
    /** Event id and start time to the start time, so a moved event is announced again; pruned once started. */
    private final Map<String, Long> announced = new HashMap<>();
    private ScheduledExecutorService scheduler;

    UpcomingNotifier(CalendarStore store, Clock clock, Consumer<Map<String, Object>> announce) {
        this.store = store;
        this.clock = clock;
        this.announce = announce;
    }

    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "upcoming-notifier");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "upcoming check failed: {0}", e.getMessage());
            }
        }, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized void tick() {
        long now = clock.millis();
        announced.values().removeIf(startsAt -> startsAt < now);
        for (EventRow event : store.listEvents(now, now + LEAD.toMillis())) {
            if (event.startsAt() < now || event.startsAt() > now + LEAD.toMillis()) continue;
            if (announced.put(event.id() + "@" + event.startsAt(), event.startsAt()) != null) continue;
            announce.accept(Map.of("eventId", event.id(), "title", event.title(), "startsAt", event.startsAt()));
        }
    }

    @Override public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
