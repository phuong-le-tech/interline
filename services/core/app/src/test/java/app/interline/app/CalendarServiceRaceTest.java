package app.interline.app;

import app.interline.domain.CalendarEvent;
import app.interline.domain.CalendarSnapshot;
import app.interline.domain.CalendarSource;
import app.interline.domain.SecretStore;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.ErrorResponseException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static app.interline.app.M3Support.HOUR;
import static app.interline.app.M3Support.NOW;
import static app.interline.app.M3Support.NOW_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Review round 1, item 10: a source change (setSource / removeSource) during an in-flight sync must win. The sync
 * throws 409 calendar_source_changed and writes nothing; removeSource / setSource never wait for the fetch. All
 * deterministic: a latch-controlled CalendarSource, an in-memory SecretStore and a real temp SQLite store.
 */
@Timeout(60)
class CalendarServiceRaceTest {
    private static final String URL_A = "https://example.com/private/FAKE-TOKEN-A.ics?key=FAKE-KEY-A";
    private static final String URL_B = "https://example.org/private/FAKE-TOKEN-B.ics?key=FAKE-KEY-B";
    private static final long FAR = Long.MAX_VALUE / 4;

    @TempDir Path directory;
    private SqliteDatabase database;
    private CalendarStore store;
    private MemorySecrets secrets;
    private ControlledSource source;
    private CalendarService service;
    private ExecutorService executor;

    /** In-memory secret store. */
    static final class MemorySecrets implements SecretStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public Optional<String> get(String account) { return Optional.ofNullable(values.get(account)); }
        @Override public void put(String account, String value) { values.put(account, value); }
        @Override public void delete(String account) { values.remove(account); }
    }

    /** A source whose fetch can be parked: it signals {@code entered}, then waits for {@code release} when gated. */
    static final class ControlledSource implements CalendarSource {
        volatile boolean gated;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        @Override public CalendarSnapshot fetch(long windowStartMs, long windowEndMs) {
            calls.incrementAndGet();
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                entered.countDown();
                if (gated) {
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test never released the fetch");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return new CalendarSnapshot(List.of(new CalendarEvent("uid-1", "Planning", NOW + HOUR, NOW + 2 * HOUR, null, List.of())),
                        List.of(), 0);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @BeforeEach void start() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new CalendarStore(database);
        secrets = new MemorySecrets();
        source = new ControlledSource();
        service = new CalendarService(store, secrets, new HttpIcsFetcher(false), source, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach void stop() {
        source.release.countDown();
        executor.shutdownNow();
        database.close();
    }

    private int icsRows() {
        return store.listEvents(0, FAR).stream().filter(e -> "ics".equals(e.source())).toList().size();
    }

    private void park() {
        source.gated = true;
        source.entered = new CountDownLatch(1);
        source.release = new CountDownLatch(1);
    }

    private static void awaitTrue(CountDownLatch latch, String what) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as(what).isTrue();
    }

    private static ErrorResponseException problem(Throwable failure) {
        Throwable t = failure instanceof ExecutionException ? failure.getCause() : failure;
        assertThat(t).isInstanceOf(ErrorResponseException.class);
        return (ErrorResponseException) t;
    }

    private static void assertSourceChanged(Throwable failure) {
        ErrorResponseException e = problem(failure);
        assertThat(e.getStatusCode().value()).isEqualTo(409);
        assertThat(e.getBody().getProperties()).containsEntry("code", "calendar_source_changed");
        assertThat(String.valueOf(e.getBody().getDetail())).doesNotContain("FAKE-TOKEN").doesNotContain("FAKE-KEY");
    }

    @Test void aRemoveSourceDuringAnInFlightSync_returnsPromptly_andTheSyncFailsWith409AndWritesNothing() throws Exception {
        service.setSource(URL_A);
        assertThat(service.sync().upserted()).isEqualTo(1);
        assertThat(icsRows()).isEqualTo(1);

        park();
        Future<CalendarService.SyncResult> sync = executor.submit(service::sync);
        awaitTrue(source.entered, "the sync is inside source.fetch");

        Future<?> removal = executor.submit(service::removeSource);
        removal.get(5, TimeUnit.SECONDS);   // must not wait for the parked fetch
        assertThat(sync.isDone()).as("the fetch is still parked").isFalse();
        assertThat(secrets.get(IcsCalendarSource.ACCOUNT)).isEmpty();
        assertThat(icsRows()).as("removeSource cleared the ics events").isZero();

        source.release.countDown();
        Throwable failure = catchThrowable(() -> sync.get(10, TimeUnit.SECONDS));

        assertSourceChanged(failure);
        assertThat(icsRows()).as("the late sync wrote nothing").isZero();
        assertThat(service.configured()).isFalse();
    }

    @Test void aSetSourceDuringAnInFlightSync_returnsPromptly_andTheSyncFailsWith409AndWritesNothing() throws Exception {
        service.setSource(URL_A);

        park();
        Future<CalendarService.SyncResult> sync = executor.submit(service::sync);
        awaitTrue(source.entered, "the sync is inside source.fetch");

        Future<?> change = executor.submit(() -> service.setSource(URL_B));
        change.get(5, TimeUnit.SECONDS);   // must not wait for the parked fetch
        assertThat(sync.isDone()).isFalse();
        assertThat(secrets.get(IcsCalendarSource.ACCOUNT)).contains(URL_B);

        source.release.countDown();
        Throwable failure = catchThrowable(() -> sync.get(10, TimeUnit.SECONDS));

        assertSourceChanged(failure);
        assertThat(icsRows()).as("events of the old address were not stored").isZero();
        assertThat(secrets.get(IcsCalendarSource.ACCOUNT)).as("the new address stays").contains(URL_B);
    }

    @Test void aSyncStartedAfterTheChangeWorksNormally() {
        service.setSource(URL_A);
        service.removeSource();
        service.setSource(URL_B);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(icsRows()).isEqualTo(1);
    }

    @Test void noMutationDuringTheFetch_givesTheNormalResult() throws Exception {
        service.setSource(URL_A);

        park();
        Future<CalendarService.SyncResult> sync = executor.submit(service::sync);
        awaitTrue(source.entered, "the sync is inside source.fetch");
        source.release.countDown();
        CalendarService.SyncResult result = sync.get(10, TimeUnit.SECONDS);

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(result.removed()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.warnings()).isEmpty();
        assertThat(icsRows()).isEqualTo(1);
        assertThat(store.listEvents(0, FAR)).singleElement().satisfies(e -> {
            assertThat(e.title()).isEqualTo("Planning");
            assertThat(e.source()).isEqualTo("ics");
        });
    }

    @Test void twoSyncsStillSerialise_neverFetchingAtTheSameTime() throws Exception {
        service.setSource(URL_A);

        park();
        Future<CalendarService.SyncResult> first = executor.submit(service::sync);
        awaitTrue(source.entered, "the first sync is inside source.fetch");
        Future<CalendarService.SyncResult> second = executor.submit(service::sync);

        // give the second sync every chance to (wrongly) enter the fetch while the first is parked
        Thread.sleep(500);
        assertThat(source.calls.get()).as("the second sync waits for the first").isEqualTo(1);
        assertThat(second.isDone()).isFalse();

        source.release.countDown();
        CalendarService.SyncResult one = first.get(10, TimeUnit.SECONDS);
        CalendarService.SyncResult two = second.get(10, TimeUnit.SECONDS);

        assertThat(source.calls.get()).isEqualTo(2);
        assertThat(source.maxRunning.get()).as("fetches never overlap").isEqualTo(1);
        assertThat(one.upserted() + two.upserted()).as("one inserts, the other finds it unchanged or rewrites it").isGreaterThanOrEqualTo(1);
        assertThat(icsRows()).isEqualTo(1);
    }

    @Test void aSyncQueuedBehindAnotherSeesAChangeMadeBeforeItStartsFetching() throws Exception {
        service.setSource(URL_A);

        park();
        Future<CalendarService.SyncResult> first = executor.submit(service::sync);
        awaitTrue(source.entered, "the first sync is inside source.fetch");
        Future<CalendarService.SyncResult> second = executor.submit(service::sync);
        Thread.sleep(300);

        executor.submit(service::removeSource).get(5, TimeUnit.SECONDS);
        source.release.countDown();

        assertSourceChanged(catchThrowable(() -> first.get(10, TimeUnit.SECONDS)));
        Throwable later = catchThrowable(() -> second.get(10, TimeUnit.SECONDS));
        ErrorResponseException e = problem(later);
        assertThat(e.getStatusCode().value()).isEqualTo(409);
        assertThat(e.getBody().getProperties().get("code")).isIn("calendar_source_changed", "calendar_not_configured");
        assertThat(icsRows()).isZero();
    }
}
