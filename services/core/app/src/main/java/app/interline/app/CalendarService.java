package app.interline.app;

import app.interline.app.HttpIcsFetcher.IcsFetchException;
import app.interline.domain.CalendarSnapshot;
import app.interline.domain.CalendarSource;
import app.interline.domain.CalendarUnavailableException;
import app.interline.domain.SecretStore;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.CalendarStore.EventRow;
import app.interline.persistence.CalendarStore.SyncCounts;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The ICS calendar use cases. The subscription URL is a secret: it lives only in the {@link SecretStore} and is never
 * written to the database, a log line, an error or a response; the host is the most that is ever reported.
 */
final class CalendarService {
    static final Duration WINDOW_BEHIND = Duration.ofDays(1);
    static final Duration WINDOW_AHEAD = Duration.ofDays(14);
    static final Duration MAX_QUERY_SPAN = Duration.ofDays(31);
    private static final int MAX_WARNINGS = 100;
    private static final System.Logger LOG = System.getLogger(CalendarService.class.getName());

    record SyncResult(int upserted, int removed, int skipped, List<String> warnings, boolean complete) { }

    private final CalendarStore store;
    private final SecretStore secrets;
    private final HttpIcsFetcher fetcher;
    private final CalendarSource source;
    /** Serialises syncs. */
    private final Object syncLock = new Object();
    /** Guards the source generation and every write that depends on it. */
    private final Object stateLock = new Object();
    private long generation;
    private final Clock clock;

    CalendarService(CalendarStore store, SecretStore secrets, HttpIcsFetcher fetcher, CalendarSource source, Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.fetcher = fetcher;
        this.source = source;
        this.clock = clock;
    }

    void setSource(String url) {
        URI uri;
        try {
            uri = fetcher.normalize(url);
        } catch (IcsFetchException e) {
            throw ApiProblems.badRequest(e.getMessage());
        }
        synchronized (stateLock) {
            try {
                secrets.put(IcsCalendarSource.ACCOUNT, url.strip());
            } catch (IllegalStateException e) {
                throw secretStoreUnavailable(e);
            }
            generation++;
        }
        LOG.log(System.Logger.Level.INFO, "calendar source configured for host {0}", uri.getHost());
    }

    /** The host of the configured address, or empty when none is configured. */
    Optional<String> host() {
        return address().map(url -> {
            try {
                return fetcher.normalize(url).getHost();
            } catch (IcsFetchException e) {
                return null;
            }
        });
    }

    boolean configured() { return address().isPresent(); }

    void removeSource() {
        synchronized (stateLock) {
            try {
                secrets.delete(IcsCalendarSource.ACCOUNT);
            } catch (IllegalStateException e) {
                throw secretStoreUnavailable(e);
            }
            generation++;
            store.deleteIcsEvents();
        }
    }

    /**
     * Fetches without holding the state lock, then writes only if the source was not replaced or removed meanwhile:
     * a stale snapshot must never bring back events of a source the user just dropped.
     */
    SyncResult sync() {
        synchronized (syncLock) {
            if (!configured()) throw ApiProblems.of(HttpStatus.CONFLICT, "calendar_not_configured", "No calendar address is configured");
            long startedWith;
            synchronized (stateLock) { startedWith = generation; }
            long now = clock.millis();
            long from = now - WINDOW_BEHIND.toMillis();
            long to = now + WINDOW_AHEAD.toMillis();
            CalendarSnapshot snapshot;
            try {
                snapshot = source.fetch(from, to);
            } catch (CalendarUnavailableException e) {
                LOG.log(System.Logger.Level.WARNING, "calendar sync failed: {0}", e.getMessage());
                throw ApiProblems.of(HttpStatus.BAD_GATEWAY, "calendar_fetch_failed", e.getMessage());
            }
            SyncCounts counts;
            synchronized (stateLock) {
                if (generation != startedWith) {
                    throw ApiProblems.of(HttpStatus.CONFLICT, "calendar_source_changed",
                            "The calendar address changed during the sync; run it again");
                }
                // A partial snapshot only adds and updates: what it does not mention may simply not have been read.
                counts = store.replaceIcsWindow(snapshot.events(), from, to, now, snapshot.complete());
            }
            List<String> warnings = snapshot.warnings().size() <= MAX_WARNINGS ? snapshot.warnings()
                    : snapshot.warnings().subList(0, MAX_WARNINGS);
            if (!snapshot.complete() && warnings.stream().noneMatch(w -> w.toLowerCase(java.util.Locale.ROOT).contains("incomplete"))) {
                warnings = new java.util.ArrayList<>(warnings);
                warnings.add(0, "The calendar was only partly read (incomplete): nothing was removed");
            }
            return new SyncResult(counts.upserted(), counts.removed(), snapshot.skipped(), warnings, snapshot.complete());
        }
    }

    List<EventRow> events(Long from, Long to) {
        long now = clock.millis();
        long start = from != null ? from : now - WINDOW_BEHIND.toMillis();
        long end = to != null ? to : now + WINDOW_AHEAD.toMillis();
        if (end <= start) throw ApiProblems.badRequest("to must be after from");
        long span;
        try {
            span = Math.subtractExact(end, start);
        } catch (ArithmeticException e) {
            throw ApiProblems.badRequest("the window must not exceed 31 days");
        }
        if (span > MAX_QUERY_SPAN.toMillis()) throw ApiProblems.badRequest("the window must not exceed 31 days");
        return store.listEvents(start, end);
    }

    private Optional<String> address() {
        try {
            return secrets.get(IcsCalendarSource.ACCOUNT).filter(url -> !url.isBlank());
        } catch (IllegalStateException e) {
            throw secretStoreUnavailable(e);
        }
    }

    private static RuntimeException secretStoreUnavailable(IllegalStateException cause) {
        LOG.log(System.Logger.Level.WARNING, "secret store unavailable: {0}", cause.getMessage());
        return ApiProblems.of(HttpStatus.SERVICE_UNAVAILABLE, "secret_store_unavailable",
                "The system secret store is not available. Check that the capture agent is running.");
    }
}
