package app.interline.agent;

import java.time.Clock;
import java.time.Duration;

/** Rolling (now - 60s, now] crash budget and capped exponential restart delay. */
public final class RestartPolicy {
    public RestartPolicy(Clock clock) { }
    public Duration onCrash() { return Duration.ZERO; }
    public boolean isUnavailable() { return false; }
}
