package app.interline.app;

import app.interline.persistence.LifecycleStore;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local-only maintenance; one daemon, with a clock-driven tick for tests. */
final class LifecyclePurger implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(LifecyclePurger.class.getName());
    private final LifecycleStore store;
    private final Clock clock;
    private ScheduledExecutorService scheduler;
    LifecyclePurger(LifecycleStore store, Clock clock) { this.store = store; this.clock = clock; }
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon().name("lifecycle-purge").unstarted(task));
        scheduler.scheduleWithFixedDelay(() -> {
            try { tick(); }
            catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "Lifecycle purge failed; will retry", e); }
        }, 0, 60, TimeUnit.SECONDS);
    }
    void tick() {
        try { store.purgeAudio(clock.millis()); }
        finally { store.purgeTrash(clock.millis()); }
    }
    @Override public void close() { if (scheduler != null) scheduler.shutdownNow(); }
}
