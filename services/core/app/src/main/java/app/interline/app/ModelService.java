package app.interline.app;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The model catalog and the only code path that downloads from it, started by an explicit user request.
 * The catalog pins every file to a commit URL, a size and a SHA-256 (ARCHITECTURE 14).
 */
public class ModelService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ModelService.class.getName());
    private static final String BUNDLED_CATALOG = "/models/catalog.json";
    private static final String DEFAULT_KIND = "stt";
    private static final String DEFAULT_FILE_SUFFIX = ".bin";
    private static final String EVENT = "model.download";

    public record Entry(String id, String name, String kind, String file, URI url, long sizeBytes, String sha256) { }

    public record Status(Entry entry, String state, long downloadedBytes, String error) { }

    private final List<Entry> catalog;
    private final Path models;
    private final ModelDownloader downloader;
    private final EventBus events;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Status> active = new ConcurrentHashMap<>();
    private final Map<String, Status> failedStates = new ConcurrentHashMap<>();

    public ModelService(Path catalogFile, Path dataDirectory, EventBus events) {
        this.catalog = loadCatalog(catalogFile);
        this.models = dataDirectory.resolve("models");
        this.downloader = new ModelDownloader(models);
        this.events = events;
    }

    public List<Status> statuses() {
        List<Status> all = new ArrayList<>();
        for (Entry entry : catalog) all.add(active.getOrDefault(entry.id(), idleStatus(entry)));
        return all;
    }

    public Optional<Entry> find(String id) { return catalog.stream().filter(e -> e.id().equals(id)).findFirst(); }

    /** Starts the download unless one is already running for this model. Returns false when it was already running. */
    public boolean startDownload(Entry entry) {
        Status running = new Status(entry, "downloading", partSize(entry), null);
        if (active.putIfAbsent(entry.id(), running) != null) return false;
        failedStates.remove(entry.id());
        worker.submit(() -> run(entry));
        return true;
    }

    /** Arguments that point the agent at the installed models, largest speech model first. */
    public List<String> agentArguments() {
        List<String> args = new ArrayList<>();
        catalog.stream().filter(e -> DEFAULT_KIND.equals(e.kind()) && Files.exists(models.resolve(e.file())))
                .reduce((smaller, larger) -> larger.sizeBytes() >= smaller.sizeBytes() ? larger : smaller)
                .ifPresent(e -> args.add("--model=" + models.resolve(e.file())));
        catalog.stream().filter(e -> "vad".equals(e.kind()) && Files.exists(models.resolve(e.file()))).findFirst()
                .ifPresent(e -> args.add("--vad-model=" + models.resolve(e.file())));
        return args;
    }

    private void run(Entry entry) {
        try {
            downloader.download(entry.url(), entry.file(), entry.sha256(), bytes -> {
                Status status = new Status(entry, "downloading", bytes, null);
                active.put(entry.id(), status);
                events.publish(EVENT, view(status));
            });
            active.remove(entry.id());
            events.publish(EVENT, view(idleStatus(entry)));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "model download " + entry.id() + " failed: " + e.getMessage());
            Status failed = new Status(entry, "failed", partSize(entry), e.getMessage());
            // Kept until the next attempt so the UI can show why the last one stopped.
            failedStates.put(entry.id(), failed);
            active.remove(entry.id());
            events.publish(EVENT, view(failed));
        }
    }

    private Status idleStatus(Entry entry) {
        if (Files.exists(models.resolve(entry.file()))) return new Status(entry, "installed", entry.sizeBytes(), null);
        Status failed = failedStates.get(entry.id());
        if (failed != null) return failed;
        long part = partSize(entry);
        return new Status(entry, part > 0 ? "partial" : "absent", part, null);
    }

    private long partSize(Entry entry) {
        try {
            Path part = models.resolve(entry.file() + ".part");
            return Files.exists(part) ? Files.size(part) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public static Map<String, Object> view(Status status) {
        Entry e = status.entry();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.id());
        view.put("name", e.name());
        view.put("kind", e.kind());
        view.put("sizeBytes", e.sizeBytes());
        view.put("state", status.state());
        view.put("downloadedBytes", status.downloadedBytes());
        if (status.error() != null) view.put("error", status.error());
        return view;
    }

    private static List<Entry> loadCatalog(Path override) {
        ObjectMapper json = new ObjectMapper();
        try (InputStream in = override != null ? Files.newInputStream(override) : ModelService.class.getResourceAsStream(BUNDLED_CATALOG)) {
            if (in == null) throw new IllegalStateException("bundled model catalog is missing");
            List<Entry> entries = new ArrayList<>();
            for (var node : json.readTree(in)) {
                String id = node.path("id").asText();
                entries.add(new Entry(id, node.path("name").asText(id), node.path("kind").asText(DEFAULT_KIND),
                        node.path("file").asText(id + DEFAULT_FILE_SUFFIX), URI.create(node.path("url").asText()),
                        node.path("sizeBytes").asLong(), node.path("sha256").asText()));
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the model catalog", e);
        }
    }

    @Override public void close() { worker.shutdownNow(); }
}
