package app.interline.agent;

import java.time.Clock;
import java.util.function.Supplier;

/** Poll-driven process supervisor; clock injection keeps deadlines deterministic. */
public final class AgentProcess implements AutoCloseable {
    public AgentProcess(Process process, Supplier<Process> restart, Clock clock) { }
    public void heartbeatReceived() { }
    public void poll() { }
    @Override public void close() { }
}
