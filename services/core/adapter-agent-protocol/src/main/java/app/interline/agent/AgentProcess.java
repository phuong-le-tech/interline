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
    private static final Runnable NO_NOTIFICATION = () -> { };

    /** Observer of supervision decisions; all methods run on the polling thread. */
    public interface Listener {
        Listener NONE = new Listener() { };
        default void crashed(String reason, Duration restartIn, boolean unavailable) { }
        default void restarted(Process process) { }
    }

    private final Supplier<Process> restart;
    private final Clock clock;
    private final Duration deadline;
    private final Duration startupGrace;
    private final Listener listener;
    private final RestartPolicy policy;
    private Process process;
    private Instant lastHeartbeat;
    private Instant restartAt;
    private boolean unavailable;
    private boolean heartbeatSeen;
    private boolean outputClosed;
    private boolean closed;

    public AgentProcess(Process process, Supplier<Process> restart, Clock clock) {
        this(process, restart, clock, DEFAULT_HEARTBEAT_INTERVAL, Duration.ZERO, Listener.NONE);
    }

    public AgentProcess(Process process, Supplier<Process> restart, Clock clock,
                        Duration heartbeatInterval, Duration startupGrace, Listener listener) {
        this.process = process;
        this.restart = restart;
        this.clock = clock;
        this.deadline = heartbeatInterval.multipliedBy(MISSED_HEARTBEATS_BEFORE_KILL);
        this.startupGrace = startupGrace;
        this.listener = listener;
        this.policy = new RestartPolicy(clock);
        this.lastHeartbeat = clock.instant();
    }

    public synchronized void heartbeatReceived() {
        lastHeartbeat = clock.instant();
        heartbeatSeen = true;
    }

    /**
     * The agent's stdout reached end of stream. The link is dead whatever the process does next (a launcher
     * wrapper can linger for seconds after its child is killed), so the next poll treats it as exited and
     * reaps the whole tree instead of waiting for the process to vanish on its own.
     */
    public synchronized void outputClosed(Process source) {
        if (source == process) outputClosed = true;
    }

    public synchronized boolean isUnavailable() { return unavailable; }

    public void poll() {
        Runnable notification;
        synchronized (this) {
            notification = step();
        }
        // Observers run outside the lock so they can call back into the supervisor or take their own locks.
        notification.run();
    }

    private Runnable step() {
        if (closed || unavailable) return NO_NOTIFICATION;
        Instant now = clock.instant();
        if (process == null) {
            return now.isBefore(restartAt) ? NO_NOTIFICATION : relaunch(now);
        }
        if (outputClosed) {
            ProcessTree.destroy(process);
            return onExit("agent_exited", now);
        }
        if (!process.isAlive()) return onExit("agent_exited", now);
        if (Duration.between(lastHeartbeat, now).compareTo(heartbeatSeen ? deadline : deadline.plus(startupGrace)) >= 0) {
            ProcessTree.destroy(process);
            return onExit("heartbeat_timeout", now);
        }
        return NO_NOTIFICATION;
    }

    private Runnable relaunch(Instant now) {
        try {
            process = restart.get();
            lastHeartbeat = now;
            heartbeatSeen = false;
            outputClosed = false;
            Process launched = process;
            return () -> listener.restarted(launched);
        } catch (RuntimeException launchFailure) {
            process = null;
            return onExit("launch_failed", now);
        }
    }

    private Runnable onExit(String reason, Instant now) {
        process = null;
        Duration delay = policy.onCrash();
        boolean exhausted = policy.isUnavailable();
        unavailable = exhausted;
        restartAt = now.plus(delay);
        return () -> listener.crashed(reason, delay, exhausted);
    }

    /** Stop supervising and terminate the current child and its descendants. */
    @Override public synchronized void close() {
        closed = true;
        if (process != null) ProcessTree.destroy(process);
        process = null;
    }
}
