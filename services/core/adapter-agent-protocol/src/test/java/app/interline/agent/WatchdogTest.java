package app.interline.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class WatchdogTest {
    @Test @Timeout(10)
    void AC8_thirdMissedHeartbeatKillsRealChildAndRestartsOnlyAfterBackoff() throws Exception {
        var clock = new FakeClock();
        var children = new ArrayList<Process>();
        Process initial = child(); children.add(initial);
        try (var supervisor = new AgentProcess(initial, () -> {
            try { Process process = child(); children.add(process); return process; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }, clock)) {
            clock.advance(5999); supervisor.poll();
            assertThat(initial.isAlive()).as("Two missed heartbeats are tolerated").isTrue();
            assertThat(children).hasSize(1);
            clock.advance(1); supervisor.poll();
            assertThat(initial.waitFor(2, TimeUnit.SECONDS)).as("Third missed heartbeat terminates the hung process").isTrue();
            assertThat(initial.isAlive()).isFalse();
            assertThat(children).hasSize(1);
            clock.advance(499); supervisor.poll();
            assertThat(children).hasSize(1);
            clock.advance(1); supervisor.poll();
            assertThat(children).hasSize(2);
            assertThat(children.get(1).isAlive()).isTrue();
        } finally { for (Process p : children) { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS); } }
    }

    @Test @Timeout(10)
    void AC8_heartbeatResetsDeadlineWithoutUsingSenderWallClock() throws Exception {
        var clock = new FakeClock();
        Process child = child();
        try (var supervisor = new AgentProcess(child, () -> { throw new AssertionError("Unexpected restart"); }, clock)) {
            clock.advance(5999); supervisor.heartbeatReceived();
            clock.advance(5999); supervisor.poll();
            assertThat(child.isAlive()).isTrue();
            clock.advance(1); supervisor.poll();
            assertThat(child.waitFor(2, TimeUnit.SECONDS)).isTrue();
        } finally { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
    }

    @Test void AC8_restartBackoffDoublesAndCapsAtFourSeconds() {
        var clock = new FakeClock();
        var policy = new RestartPolicy(clock);
        for (long delay : new long[]{500,1000,2000,4000,4000}) {
            assertThat(policy.onCrash()).isEqualTo(Duration.ofMillis(delay));
            assertThat(policy.isUnavailable()).isFalse();
        }
    }

    @Test void AC8_sixthCrashWithinSixtySecondsMarksCaptureUnavailable() {
        var clock = new FakeClock();
        var policy = new RestartPolicy(clock);
        for (int i=0;i<5;i++) {
            policy.onCrash();
            assertThat(policy.isUnavailable()).as("Crash %s must still allow capture recovery", i+1).isFalse();
            clock.advance(1000);
        }
        clock.advance(54999);
        policy.onCrash();
        assertThat(policy.isUnavailable()).as("Six crashes in 59999 ms exhaust the budget").isTrue();
    }

    @ParameterizedTest @ValueSource(longs={60000,60001})
    void AC8_oldestCrashExpiresAtSixtySecondWindowBoundary(long elapsed) {
        // Rolling window is (now - 60s, now], so exactly 60s old is expired.
        var clock = new FakeClock();
        var policy = new RestartPolicy(clock);
        policy.onCrash();
        clock.advance(1000);
        for (int i=0;i<4;i++) policy.onCrash();
        clock.advance(elapsed - 1000);
        policy.onCrash();
        assertThat(policy.isUnavailable()).isFalse();
        policy.onCrash();
        assertThat(policy.isUnavailable()).as("Five remaining recent crashes plus one exhaust the budget").isTrue();
    }

    private static Process child() throws Exception {
        Process child = new ProcessBuilder(HandshakeTest.java(), "-cp", System.getProperty("java.class.path"),
                SilentChild.class.getName()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // A readiness byte prevents testing an already-failed child launch instead of supervision.
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            int ready = executor.submit(() -> child.getInputStream().read()).get(2, TimeUnit.SECONDS);
            assertThat(ready).isEqualTo(82);
        } catch (Throwable failure) { child.destroyForcibly(); throw failure; }
        return child;
    }

    public static class SilentChild {
        public static void main(String[] args) throws Exception {
            System.out.write(82); System.out.flush();
            System.in.read();
        }
    }

    static final class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
