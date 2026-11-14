package app.interline.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Rolling (now - 60s, now] crash budget and capped exponential restart delay. */
public final class RestartPolicy {
    static final Duration WINDOW = Duration.ofSeconds(60);
    static final int MAX_CRASHES_IN_WINDOW = 5;
    private static final Duration FIRST_DELAY = Duration.ofMillis(500);
    private static final Duration MAX_DELAY = Duration.ofSeconds(4);

    private final Clock clock;
    private final Deque<Instant> crashes = new ArrayDeque<>();

    public RestartPolicy(Clock clock) { this.clock = clock; }

    /** Record a crash and return how long to wait before relaunching. */
    public synchronized Duration onCrash() {
        Instant now = clock.instant();
        expire(now);
        crashes.addLast(now);
        Duration delay = FIRST_DELAY.multipliedBy(1L << Math.min(crashes.size() - 1, 16));
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    /** More than five crashes within the window: stop restarting. */
    public synchronized boolean isUnavailable() {
        expire(clock.instant());
        return crashes.size() > MAX_CRASHES_IN_WINDOW;
    }

    private void expire(Instant now) {
        Instant oldestKept = now.minus(WINDOW);
        while (!crashes.isEmpty() && !crashes.peekFirst().isAfter(oldestKept)) crashes.removeFirst();
    }
}
