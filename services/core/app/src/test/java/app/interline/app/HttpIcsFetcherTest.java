package app.interline.app;

import app.interline.app.HttpIcsFetcher.IcsFetchException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC9: the fetcher refuses unsafe schemes and redirects before connecting, caps size and time, and never leaks the URL. */
@Timeout(60)
class HttpIcsFetcherTest {
    private static final String TOKEN = "SECRET-TOKEN-xyz";
    private static final String QUERY = "SECRET-QUERY-abc";
    private static final String BODY = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            reply(exchange, 200, BODY.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String secretUrl(String scheme) {
        return scheme + "://127.0.0.1:" + port() + "/private/" + TOKEN + ".ics?key=" + QUERY;
    }

    private void context(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                exchange.close();
            }
        });
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        reply(exchange, 302, new byte[0]);
    }

    /** The exception and its whole cause chain mention neither the path, the query nor the token. */
    private static void assertSafe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String text = String.valueOf(t.getMessage()) + " " + t;
            assertThat(text).doesNotContain(TOKEN).doesNotContain(QUERY).doesNotContain("/private").doesNotContain(".ics")
                    .doesNotContain("key=");
        }
    }

    private void assertRefused(HttpIcsFetcher fetcher, String url) {
        long before = System.nanoTime();
        assertThatThrownBy(() -> fetcher.fetch(url)).isInstanceOf(IcsFetchException.class).satisfies(HttpIcsFetcherTest::assertSafe);
        assertThat(Duration.ofNanos(System.nanoTime() - before)).as("refused without waiting on the network").isLessThan(Duration.ofSeconds(5));
    }

    // ---- scheme / host policy -------------------------------------------------------------------------------------

    @Test void AC9_httpToANonLoopbackHostIsRefusedBeforeConnecting_evenWithTheFlag() {
        assertRefused(new HttpIcsFetcher(false), "http://203.0.113.7/private/" + TOKEN + ".ics?key=" + QUERY);
        assertRefused(new HttpIcsFetcher(true), "http://203.0.113.7/private/" + TOKEN + ".ics?key=" + QUERY);
        assertRefused(new HttpIcsFetcher(true), "http://example.com/private/" + TOKEN + ".ics?key=" + QUERY);
    }

    @Test void AC9_loopbackHttpIsRefusedWithoutTheFlag_andNothingIsSent() {
        assertRefused(new HttpIcsFetcher(false), secretUrl("http"));
        assertThat(hits).hasValue(0);
    }

    @Test void AC9_loopbackHttpIsAllowedOnlyWithTheFlag() throws Exception {
        byte[] body = new HttpIcsFetcher(true).fetch(secretUrl("http"));
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(hits).hasValue(1);
    }

    @Test void AC9_fileFtpJarAndDataSchemesAreRefused_inBothModes() {
        for (boolean flag : new boolean[]{false, true}) {
            HttpIcsFetcher fetcher = new HttpIcsFetcher(flag);
            assertRefused(fetcher, "file:///private/" + TOKEN + ".ics?key=" + QUERY);
            assertRefused(fetcher, "ftp://127.0.0.1:" + port() + "/private/" + TOKEN + ".ics?key=" + QUERY);
            assertRefused(fetcher, "jar:file:///tmp/x.jar!/private/" + TOKEN + ".ics?key=" + QUERY);
            assertRefused(fetcher, "data:text/calendar,private" + TOKEN + "?key=" + QUERY);
            assertRefused(fetcher, "gopher://127.0.0.1:" + port() + "/private/" + TOKEN);
        }
        assertThat(hits).hasValue(0);
    }

    @Test void AC9_garbageUrlsAreRefusedWithAFetchException() {
        HttpIcsFetcher fetcher = new HttpIcsFetcher(true);
        assertRefused(fetcher, "");
        assertRefused(fetcher, "not a url " + TOKEN + "?key=" + QUERY);
        assertRefused(fetcher, "https:///private/" + TOKEN + ".ics?key=" + QUERY);
    }

    // ---- webcal ---------------------------------------------------------------------------------------------------

    @Test void AC9_webcalIsRewrittenToHttps_soAPlainHttpServerIsNeverServedByIt() {
        // https is attempted against a plain-http server: the TLS handshake fails, the request never reaches a handler.
        assertRefused(new HttpIcsFetcher(true, Duration.ofSeconds(3), Duration.ofSeconds(5), 1 << 20),
                "webcal://127.0.0.1:" + port() + "/private/" + TOKEN + ".ics?key=" + QUERY);
        assertThat(hits).hasValue(0);
    }

    @Test void AC9_webcalIsRefusedInTheSameWayWithoutTheFlag() {
        assertRefused(new HttpIcsFetcher(false, Duration.ofSeconds(3), Duration.ofSeconds(5), 1 << 20),
                "webcal://127.0.0.1:" + port() + "/private/" + TOKEN + ".ics?key=" + QUERY);
        assertThat(hits).hasValue(0);
    }

    // ---- redirects ------------------------------------------------------------------------------------------------

    @Test void AC9_redirectsOnLoopbackHttpAreFollowed_absoluteAndRelative() throws Exception {
        context("/r1", ex -> redirect(ex, "http://127.0.0.1:" + port() + "/r2"));
        context("/r2", ex -> redirect(ex, "/final/" + TOKEN + ".ics?key=" + QUERY));
        context("/final", ex -> reply(ex, 200, "FINAL".getBytes(StandardCharsets.UTF_8)));
        byte[] body = new HttpIcsFetcher(true).fetch("http://127.0.0.1:" + port() + "/r1");
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("FINAL");
    }

    @Test void AC9_redirectToANonLoopbackHttpUrlIsRefused() {
        context("/r", ex -> redirect(ex, "http://203.0.113.7/private/" + TOKEN + ".ics?key=" + QUERY));
        assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/r");
        assertThat(hits).as("only the first hop was requested").hasValue(1);
    }

    @Test void AC9_redirectToAnotherSchemeIsRefused() {
        for (String target : List.of("file:///etc/passwd", "ftp://127.0.0.1/private/" + TOKEN + ".ics?key=" + QUERY,
                "jar:file:///tmp/x.jar!/a", "gopher://127.0.0.1/x")) {
            hits.set(0);
            context("/s" + Math.abs(target.hashCode()), ex -> redirect(ex, target));
            assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/s" + Math.abs(target.hashCode()));
            assertThat(hits).as("redirect to %s", target).hasValue(1);
        }
    }

    @Test void AC9_redirectChainIsLimited() {
        AtomicInteger chain = new AtomicInteger();
        context("/loop", ex -> {
            chain.incrementAndGet();
            redirect(ex, "http://127.0.0.1:" + port() + "/loop?n=" + chain.get() + "&key=" + QUERY);
        });
        assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/loop");
        assertThat(chain.get()).as("at most 5 redirects are followed").isBetween(1, 7);
    }

    @Test void AC9_aShortRedirectChainWithinTheLimitSucceeds() throws Exception {
        for (int i = 1; i <= 3; i++) {
            int next = i + 1;
            context("/hop" + i, ex -> redirect(ex, next <= 3 ? "/hop" + next : "/end"));
        }
        context("/end", ex -> reply(ex, 200, "DONE".getBytes(StandardCharsets.UTF_8)));
        assertThat(new String(new HttpIcsFetcher(true).fetch("http://127.0.0.1:" + port() + "/hop1"), StandardCharsets.UTF_8))
                .isEqualTo("DONE");
    }

    @Test void AC9_aRedirectWithoutLocationIsAFetchException() {
        context("/nolocation", ex -> reply(ex, 302, new byte[0]));
        assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/nolocation");
    }

    // ---- status ---------------------------------------------------------------------------------------------------

    @Test void AC9_nonSuccessStatusIsAFetchException() {
        context("/boom", ex -> reply(ex, 500, "oops".getBytes(StandardCharsets.UTF_8)));
        context("/missing", ex -> reply(ex, 404, new byte[0]));
        assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/boom/" + TOKEN + ".ics?key=" + QUERY);
        assertRefused(new HttpIcsFetcher(true), "http://127.0.0.1:" + port() + "/missing/" + TOKEN + ".ics?key=" + QUERY);
    }

    @Test void AC9_aClosedPortIsAFetchExceptionWithoutTheUrl() {
        String url = secretUrl("http");
        server.stop(0);
        assertRefused(new HttpIcsFetcher(true, Duration.ofSeconds(2), Duration.ofSeconds(5), 1 << 20), url);
    }

    // ---- size cap -------------------------------------------------------------------------------------------------

    @Test void AC9_aBodyOverTheCapIsRefused_withAContentLength() {
        byte[] big = new byte[5_000];
        Arrays.fill(big, (byte) 'a');
        context("/big", ex -> reply(ex, 200, big));
        assertRefused(new HttpIcsFetcher(true, Duration.ofSeconds(3), Duration.ofSeconds(10), 1_000),
                "http://127.0.0.1:" + port() + "/big/" + TOKEN + ".ics?key=" + QUERY);
    }

    @Test void AC9_aBodyOverTheCapIsRefused_whenStreamedWithoutALength() {
        context("/stream", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0); // chunked, no Content-Length
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[512];
                for (int i = 0; i < 100; i++) out.write(chunk);
            }
        });
        assertRefused(new HttpIcsFetcher(true, Duration.ofSeconds(3), Duration.ofSeconds(10), 1_000),
                "http://127.0.0.1:" + port() + "/stream/" + TOKEN + ".ics?key=" + QUERY);
    }

    @Test void AC9_aBodyExactlyAtTheCapIsAccepted() throws Exception {
        byte[] exact = new byte[1_000];
        Arrays.fill(exact, (byte) 'b');
        context("/exact", ex -> reply(ex, 200, exact));
        byte[] body = new HttpIcsFetcher(true, Duration.ofSeconds(3), Duration.ofSeconds(10), 1_000)
                .fetch("http://127.0.0.1:" + port() + "/exact");
        assertThat(body).hasSize(1_000);
    }

    @Test void AC9_anEmptyBodyIsReturnedAsAnEmptyArray() throws Exception {
        context("/empty", ex -> reply(ex, 200, new byte[0]));
        assertThat(new HttpIcsFetcher(true).fetch("http://127.0.0.1:" + port() + "/empty")).isEmpty();
    }

    // ---- total timeout --------------------------------------------------------------------------------------------

    @Test void AC9_aServerThatNeverAnswersHitsTheTotalTimeout() {
        context("/slow", ex -> {
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        long before = System.nanoTime();
        assertThatThrownBy(() -> new HttpIcsFetcher(true, Duration.ofSeconds(2), Duration.ofMillis(600), 1 << 20)
                .fetch("http://127.0.0.1:" + port() + "/slow/" + TOKEN + ".ics?key=" + QUERY))
                .isInstanceOf(IcsFetchException.class).satisfies(HttpIcsFetcherTest::assertSafe);
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(5));
    }

    @Test void AC9_aServerDrippingBytesHitsTheTotalTimeout_notJustTheIdleOne() {
        context("/drip", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 80; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long before = System.nanoTime();
        assertThatThrownBy(() -> new HttpIcsFetcher(true, Duration.ofSeconds(2), Duration.ofMillis(700), 1 << 20)
                .fetch("http://127.0.0.1:" + port() + "/drip/" + TOKEN + ".ics?key=" + QUERY))
                .isInstanceOf(IcsFetchException.class).satisfies(HttpIcsFetcherTest::assertSafe);
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(5));
    }

    // ---- messages -------------------------------------------------------------------------------------------------

    @Test void AC9_exceptionMessagesNameAtMostTheHost() {
        List<Throwable> failures = new ArrayList<>();
        context("/boom", ex -> reply(ex, 500, new byte[0]));
        for (String url : List.of("http://203.0.113.7/private/" + TOKEN + ".ics?key=" + QUERY,
                "file:///private/" + TOKEN + ".ics?key=" + QUERY, secretUrl("http").replace("/private/", "/boom/"))) {
            try {
                new HttpIcsFetcher(true).fetch(url);
            } catch (IcsFetchException e) {
                failures.add(e);
            }
        }
        assertThat(failures).hasSize(3).allSatisfy(HttpIcsFetcherTest::assertSafe);
        assertThat(failures).allSatisfy(e -> assertThat(e.getMessage()).isNotBlank());
    }

    // ---- review round 1, item 13: redirect policy -------------------------------------------------------------------

    private static final String TAIL = "/private/" + TOKEN + ".ics?key=" + QUERY;
    private static final URI HTTPS_FROM = URI.create("https://example.com/start" + TAIL);
    private static final URI LOOPBACK_FROM = URI.create("http://127.0.0.1:8080/start" + TAIL);

    private static void assertCheckRefused(URI from, String to, boolean flag) {
        assertThatThrownBy(() -> HttpIcsFetcher.checkRedirect(from, URI.create(to), flag))
                .as("redirect %s -> %s (flag=%s)", from.getScheme(), to, flag)
                .isInstanceOf(IcsFetchException.class).satisfies(HttpIcsFetcherTest::assertSafe);
    }

    private static void assertCheckAllowed(URI from, String to, boolean flag) {
        assertThatCode(() -> HttpIcsFetcher.checkRedirect(from, URI.create(to), flag))
                .as("redirect %s -> %s (flag=%s)", from.getScheme(), to, flag).doesNotThrowAnyException();
    }

    private static final List<String> BANNED_HOSTS = List.of(
            "localhost", "LOCALHOST", "foo.localhost", "a.b.localhost", "printer.local", "nas.home.local", "db.internal", "svc.prod.internal",
            "127.0.0.1", "127.1.2.3", "127.255.255.254", "[::1]", "169.254.0.1", "169.254.169.254", "169.254.255.254", "[fe80::1]",
            "[fe80::abcd:1234]", "[febf::1]", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.20.1.1", "172.31.255.255", "192.168.0.1",
            "192.168.1.10", "192.168.255.255", "[fc00::1]", "[fd12:3456:789a::1]", "0.0.0.0");

    @Test void AC9r1_aRedirectFromHttpsToHttpIsRefusedAlways() {
        for (boolean flag : new boolean[]{false, true}) {
            assertCheckRefused(HTTPS_FROM, "http://example.com" + TAIL, flag);
            assertCheckRefused(HTTPS_FROM, "http://127.0.0.1:8080" + TAIL, flag);
            assertCheckRefused(URI.create("https://127.0.0.1:8443/x"), "http://127.0.0.1:8080" + TAIL, flag);
        }
    }

    @Test void AC9r1_aLoopbackHttpToLoopbackHttpRedirectIsAllowedOnlyWithTheFlag() {
        assertCheckAllowed(LOOPBACK_FROM, "http://127.0.0.1:8080/next" + TAIL, true);
        assertCheckAllowed(LOOPBACK_FROM, "http://127.0.0.1:9999/next", true);
        assertCheckRefused(LOOPBACK_FROM, "http://127.0.0.1:8080/next" + TAIL, false);
    }

    @Test void AC9r1_aRedirectToAPublicHttpsHostIsAllowed() {
        for (boolean flag : new boolean[]{false, true}) {
            assertCheckAllowed(HTTPS_FROM, "https://example.com/other" + TAIL, flag);
            assertCheckAllowed(HTTPS_FROM, "https://calendar.google.com/calendar/ical/x/basic.ics", flag);
            assertCheckAllowed(HTTPS_FROM, "https://93.184.216.34/cal" + TAIL, flag);
            assertCheckAllowed(HTTPS_FROM, "https://example.com:8443/cal" + TAIL, flag);
        }
        assertCheckAllowed(LOOPBACK_FROM, "https://example.com/cal" + TAIL, true);
        assertCheckAllowed(LOOPBACK_FROM, "https://93.184.216.34/cal" + TAIL, true);
    }

    @Test void AC9r1_theBoundariesOfThePrivateRangesAreExact() {
        for (String host : List.of("172.15.255.255", "172.32.0.1", "11.0.0.1", "9.255.255.255", "192.167.1.1", "192.169.1.1",
                "169.253.1.1", "169.255.1.1", "126.255.255.255", "128.0.0.1", "notlocalhost.com", "mylocal.com", "localhost.example.com",
                "internal.example.com")) {
            assertCheckAllowed(HTTPS_FROM, "https://" + host + "/cal" + TAIL, false);
        }
    }

    @Test void AC9r1_aRedirectTargetOnALocalOrPrivateAddressIsRefused_overHttpsInBothModes() {
        for (boolean flag : new boolean[]{false, true}) {
            for (String host : BANNED_HOSTS) {
                assertCheckRefused(HTTPS_FROM, "https://" + host + "/cal" + TAIL, flag);
                assertCheckRefused(HTTPS_FROM, "https://" + host + ":8443/cal" + TAIL, flag);
                assertCheckRefused(LOOPBACK_FROM, "https://" + host + "/cal" + TAIL, flag);
            }
        }
    }

    @Test void AC9r1_aRedirectTargetOnALocalOrPrivateAddressIsRefused_overHttpToo() {
        for (String host : BANNED_HOSTS) {
            assertCheckRefused(LOOPBACK_FROM, "http://" + host + "/cal" + TAIL, false);
            if (!host.equals("127.0.0.1")) assertCheckRefused(LOOPBACK_FROM, "http://" + host + ":8080/cal" + TAIL, true);
        }
    }

    @Test void AC9r1_withTheFlagOnly127001OverHttpStaysAllowed() {
        assertCheckAllowed(LOOPBACK_FROM, "http://127.0.0.1:8080/ok" + TAIL, true);
        for (String host : List.of("localhost", "127.0.0.2", "[::1]", "0.0.0.0", "169.254.169.254", "10.0.0.1", "192.168.1.1", "172.16.0.1",
                "x.localhost", "x.local", "x.internal", "[fe80::1]", "[fc00::1]")) {
            assertCheckRefused(LOOPBACK_FROM, "http://" + host + ":8080/no" + TAIL, true);
        }
    }

    @Test void AC9r1_refusalMessagesNeverContainThePathTheQueryOrTheToken() {
        for (String to : List.of("http://example.com" + TAIL, "https://169.254.169.254" + TAIL, "https://localhost" + TAIL,
                "https://[::1]" + TAIL, "https://10.1.2.3" + TAIL, "https://printer.local" + TAIL, "https://a.internal" + TAIL)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> HttpIcsFetcher.checkRedirect(HTTPS_FROM, URI.create(to), true));
            assertThat(failure).isInstanceOf(IcsFetchException.class);
            assertThat(failure.getMessage()).isNotBlank().doesNotContain(TOKEN).doesNotContain(QUERY).doesNotContain("/private")
                    .doesNotContain(".ics").doesNotContain("key=");
            assertSafe(failure);
        }
    }

    @Test void AC9r1_theFirstUserTypedUrlIsNotSubjectToThePrivateAddressBan() throws Exception {
        HttpIcsFetcher fetcher = new HttpIcsFetcher(false);
        assertThat(fetcher.normalize("https://192.168.1.10/cal.ics").getHost()).isEqualTo("192.168.1.10");
        assertThat(fetcher.normalize("https://10.0.0.5/cal.ics").getHost()).isEqualTo("10.0.0.5");
        assertThat(fetcher.normalize("https://nas.local/cal.ics").getHost()).isEqualTo("nas.local");
        assertThat(fetcher.normalize("https://localhost:8443/cal.ics").getHost()).isEqualTo("localhost");
    }

    @Test void AC9r1_aRedirectToTheCloudMetadataAddressFailsQuicklyEndToEnd() {
        context("/meta", ex -> redirect(ex, "https://169.254.169.254/latest/meta-data/"));
        long before = System.nanoTime();

        assertThatThrownBy(() -> new HttpIcsFetcher(true, Duration.ofSeconds(5), Duration.ofSeconds(10), 1 << 20)
                .fetch("http://127.0.0.1:" + port() + "/meta/" + TOKEN + ".ics?key=" + QUERY))
                .isInstanceOf(IcsFetchException.class).satisfies(HttpIcsFetcherTest::assertSafe);

        assertThat(Duration.ofNanos(System.nanoTime() - before)).as("refused without connecting out").isLessThan(Duration.ofSeconds(4));
        assertThat(hits).as("only the first hop was requested").hasValue(1);
    }

    @Test void AC9r1_aRedirectToAPrivateOrLocalNameFailsEndToEnd_withoutFollowing() {
        for (String target : List.of("https://10.0.0.1/cal", "https://localhost/cal", "https://printer.local/cal", "https://[::1]/cal",
                "https://192.168.1.1/cal", "https://db.internal/cal")) {
            hits.set(0);
            String path = "/p" + Math.abs(target.hashCode());
            context(path, ex -> redirect(ex, target));
            assertRefused(new HttpIcsFetcher(true, Duration.ofSeconds(5), Duration.ofSeconds(10), 1 << 20),
                    "http://127.0.0.1:" + port() + path);
            assertThat(hits).as("redirect to %s", target).hasValue(1);
        }
    }
}
