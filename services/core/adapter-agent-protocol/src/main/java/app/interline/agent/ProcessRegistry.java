package app.interline.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Durable identities for crash recovery. The lock prevents two cores opening the same data directory. */
public final class ProcessRegistry implements AutoCloseable {
    public record Identity(long pid, long startTime, String executable) { }
    public record Snapshot(Identity owner, Identity core, Identity agent) { }
    private static final int MAX_REGISTRY_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private final Identity core = identity(ProcessHandle.current());
    private final Identity owner = ProcessHandle.current().parent().map(ProcessRegistry::identity).orElse(null);

    public ProcessRegistry(Path data) throws IOException {
        file = data.resolve("run/pids.json");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(file.getParent(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        } else { Files.createDirectories(file.getParent()); }
        privatePermissions(file.getParent(), "rwx------");
        channel = FileChannel.open(file.resolveSibling("pids.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock acquired = tryLock();
            if (acquired == null) {
                recover();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while (acquired == null && System.nanoTime() < deadline) {
                    try { Thread.sleep(20); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    acquired = tryLock();
                }
            }
            if (acquired == null) throw new IOException("Another core owns this data directory");
            lock = acquired;
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Another core owns this data directory", e);
        }
        try {
            recover();
            write(null);
        } catch (IOException | RuntimeException e) {
            lock.release();
            channel.close();
            throw e;
        }
    }

    private FileLock tryLock() throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException e) { return null; }
    }

    private void recover() throws IOException {
        if (!Files.exists(file)) return;
        Snapshot old;
        try (var input = FileChannel.open(file, StandardOpenOption.READ)) {
            if (input.size() > MAX_REGISTRY_BYTES) return;
            byte[] bytes = java.nio.channels.Channels.newInputStream(input).readNBytes(MAX_REGISTRY_BYTES);
            if (input.size() > MAX_REGISTRY_BYTES) return;
            old = JSON.readValue(bytes, Snapshot.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) { return; }
        if (old == null || !complete(old.core()) || old.owner() != null && !complete(old.owner())
                || old.agent() != null && !complete(old.agent())) return;
        if (matches(old.owner()) && matches(old.core())) throw new IOException("Another core owns this data directory");
        killMatching(old.core());
        killMatching(old.agent());
    }

    private static boolean complete(Identity identity) {
        if (identity == null || identity.pid() <= 0 || identity.startTime() <= 0 || identity.executable() == null) return false;
        try { return Path.of(identity.executable()).isAbsolute(); }
        catch (java.nio.file.InvalidPathException e) { return false; }
    }

    private static void privatePermissions(Path path, String mode) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(mode));
        }
    }

    static Identity identity(ProcessHandle process) {
        var info = process.info();
        String executable = info.command().orElse("");
        try { if (!executable.isEmpty()) executable = Path.of(executable).toRealPath().toString(); }
        catch (IOException e) { executable = ""; }
        return new Identity(process.pid(), info.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L), executable);
    }

    private static boolean matches(Identity saved) {
        if (!complete(saved) || saved.pid() <= 1) return false;
        return ProcessHandle.of(saved.pid()).map(p -> saved.equals(identity(p))).orElse(false);
    }

    static boolean killMatching(Identity saved) {
        if (!matches(saved) || saved.pid() == ProcessHandle.current().pid()) return false;
        return ProcessHandle.of(saved.pid()).filter(p -> saved.equals(identity(p)))
                .map(ProcessHandle::destroyForcibly).orElse(false);
    }

    public synchronized void agent(Process process) throws IOException { write(identity(process.toHandle())); }

    private void write(Identity agent) throws IOException {
        Path temp = file.resolveSibling("pids.json.tmp");
        Files.deleteIfExists(temp);
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(temp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } else { Files.createFile(temp); }
        JSON.writeValue(temp.toFile(), new Snapshot(owner, core, agent));
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public synchronized void close() throws IOException {
        try { Files.deleteIfExists(file); }
        finally { lock.release(); channel.close(); }
    }
}
