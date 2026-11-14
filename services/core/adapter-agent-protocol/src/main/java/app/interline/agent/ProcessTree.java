package app.interline.agent;

import java.util.concurrent.TimeUnit;

/** Kills a child and everything it spawned, so a launcher script cannot leave an orphan behind. */
final class ProcessTree {
    private static final long REAP_WAIT_MS = 2000;

    private ProcessTree() { }

    static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
