package app.interline.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Poll-driven supervisor of one agent child: kills it after three missed heartbeats, relaunches it with
 * backoff, and gives up when the restart budget is exhausted. The clock is injected so deadlines are testable.
 */
public final class AgentProcess implements AutoCloseable {
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(2);
    private static final int MISSED_HEARTBEATS_BEFORE_KILL = 3;

    /** Observer of supervision decisions; all methods run on the polling thread. */
    public interface Listener {
        Listener NONE = new Listener() { };
        default void crashed(String reason, Duration restartIn, boolean unavailable) { }
        default void restarted(Process process) { }
    }

    private final Supplier<Process> restart;
    private final Clock clock;
    private final Duration deadline;
    private final Listener listener;
    private final RestartPolicy policy;
    private Process process;
    private Instant lastHeartbeat;
    private Instant restartAt;
    private boolean unavailable;
    private boolean closed;

    public AgentProcess(Process process, Supplier<Process> restart, Clock clock) {
        this(process, restart, clock, DEFAULT_HEARTBEAT_INTERVAL, Listener.NONE);
    }

    public AgentProcess(Process process, Supplier<Process> restart, Clock clock,
                        Duration heartbeatInterval, Listener listener) {
        this.process = process;
        this.restart = restart;
        this.clock = clock;
        this.deadline = heartbeatInterval.multipliedBy(MISSED_HEARTBEATS_BEFORE_KILL);
        this.listener = listener;
        this.policy = new RestartPolicy(clock);
        this.lastHeartbeat = clock.instant();
    }

    public synchronized void heartbeatReceived() { lastHeartbeat = clock.instant(); }

    public synchronized boolean isUnavailable() { return unavailable; }

    public synchronized void poll() {
        if (closed || unavailable) return;
        Instant now = clock.instant();
        if (process == null) {
            if (!now.isBefore(restartAt)) relaunch(now);
        } else if (!process.isAlive()) {
            onExit("agent_exited", now);
        } else if (Duration.between(lastHeartbeat, now).compareTo(deadline) >= 0) {
            ProcessTree.destroy(process);
            onExit("heartbeat_timeout", now);
        }
    }

    private void relaunch(Instant now) {
        try {
            process = restart.get();
            lastHeartbeat = now;
            listener.restarted(process);
        } catch (RuntimeException launchFailure) {
            process = null;
            onExit("launch_failed", now);
        }
    }

    private void onExit(String reason, Instant now) {
        process = null;
        Duration delay = policy.onCrash();
        unavailable = policy.isUnavailable();
        restartAt = now.plus(delay);
        listener.crashed(reason, delay, unavailable);
    }

    /** Stop supervising and terminate the current child and its descendants. */
    @Override public synchronized void close() {
        closed = true;
        if (process != null) ProcessTree.destroy(process);
        process = null;
    }
}
