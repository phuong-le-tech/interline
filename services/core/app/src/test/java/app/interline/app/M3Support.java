package app.interline.app;

import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static app.interline.app.AppTestSupport.HTTP;
import static app.interline.app.AppTestSupport.ROOT;
import static app.interline.app.AppTestSupport.TOKEN;
import static app.interline.app.M2Support.call;
import static app.interline.app.M2Support.port;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Shared helpers of the M3 (ICS calendar) @SpringBootTest layer: fixed-clock boot, ICS stub server, ICS builders, DB helpers. */
final class M3Support {
    /** The fixed "now" of every M3 integration test. */
    static final Instant NOW_INSTANT = Instant.parse("2026-06-15T12:00:00Z");
    static final long NOW = NOW_INSTANT.toEpochMilli();
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;
    /** The sync window: [now - 1 day, now + 14 days]. */
    static final long WINDOW_START = NOW - DAY;
    static final long WINDOW_END = NOW + 14 * DAY;

    /** Distinctive fragments of the secret URL (AC8). */
    static final String SECRET_PATH_TOKEN = "SECRET-TOKEN-xyz";
    static final String SECRET_QUERY = "SECRET-QUERY-abc";

    private static final DateTimeFormatter ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private M3Support() { }

    // ---- boot ---------------------------------------------------------------------------------------------------

    /**
     * Extra Spring source (deliberately NOT annotated, so component scanning in other tests never picks it up) that
     * replaces the Clock with a fixed one.
     */
    static class FixedClockConfig {
        @Bean @Primary Clock fixedTestClock() {
            return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        }
    }

    /** The real core with the real MockAgent, a fixed clock, loopback http allowed and zone UTC. */
    static ConfigurableApplicationContext boot(Path data, String... extra) {
        return boot(data, ROOT.resolve("services/core/mock-agent/bin/mock-agent"), extra);
    }

    static ConfigurableApplicationContext boot(Path data, Path agent, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                "--interline.token=" + TOKEN, "--interline.data-dir=" + data, "--interline.log-dir=" + data.resolve("logs"),
                "--spring.main.banner-mode=off", "--interline.agent=" + agent,
                "--interline.calendar.allow-loopback-http=true", "--interline.calendar.zone=UTC"));
        List<String> agentArgs = AppTestSupport.agentArgs(AppTestSupport.fixture(data), null);
        for (int i = 0; i < agentArgs.size(); i++) args.add("--interline.agent-args[" + i + "]=" + agentArgs.get(i));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(InterlineApplication.class, FixedClockConfig.class)
                .run(args.toArray(String[]::new));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(call(port(context), "GET", "/meetings", null).status()).isEqualTo(200));
        return context;
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    /** A call to /api/v1/calendar{path} with an arbitrary Authorization header value (null = no header at all). */
    static M2Support.Resp callWithAuth(int port, String method, String path, String authorization, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            if (authorization != null) request.header("Authorization", authorization);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new M2Support.Resp(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(""));
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " " + path + " failed", e);
        }
    }

    static M2Support.Resp putSource(int port, String url) {
        return call(port, "PUT", "/calendar/ics-source", Map.of("url", url));
    }

    static M2Support.Resp sync(int port) {
        return call(port, "POST", "/calendar/sync", Map.of());
    }

    static M2Support.Resp events(int port) {
        return call(port, "GET", "/calendar/events", null);
    }

    // ---- ICS builders -------------------------------------------------------------------------------------------

    static String icsTime(long epochMs) {
        return ICS_UTC.format(Instant.ofEpochMilli(epochMs));
    }

    /** One VEVENT with UTC start and end; {@code extra} are complete property lines (no line break). */
    static String vevent(String uid, String summary, long startMs, long endMs, String... extra) {
        StringBuilder sb = new StringBuilder("BEGIN:VEVENT\r\nUID:").append(uid).append("\r\nDTSTAMP:20260101T000000Z\r\n")
                .append("DTSTART:").append(icsTime(startMs)).append("\r\nDTEND:").append(icsTime(endMs)).append("\r\n")
                .append("SUMMARY:").append(summary).append("\r\n");
        for (String line : extra) sb.append(line).append("\r\n");
        return sb.append("END:VEVENT\r\n").toString();
    }

    static String calendar(String... vevents) {
        StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//interline-test//EN\r\n");
        for (String vevent : vevents) sb.append(vevent);
        return sb.append("END:VCALENDAR\r\n").toString();
    }

    // ---- database -----------------------------------------------------------------------------------------------

    /** Insert a non-ics row straight into the core's database (source 'manual'). */
    static void insertManualEvent(Path data, String id, String title, long startsAt, long endsAt) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            try (PreparedStatement busy = c.prepareStatement("PRAGMA busy_timeout = 5000")) {
                busy.execute();
            }
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at) VALUES (?,'manual',?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, id);
                statement.setString(3, title);
                statement.setLong(4, startsAt);
                statement.setLong(5, endsAt);
                statement.execute();
            }
        } catch (Exception e) {
            throw new AssertionError("cannot insert manual event " + id, e);
        }
    }

    static long countEvents(Path data, String source) throws Exception {
        return ((Number) AppTestSupport.rows(data, "SELECT COUNT(*) AS n FROM calendar_events WHERE source = ?", source)
                .get(0).get("n")).longValue();
    }

    // ---- ICS stub server ----------------------------------------------------------------------------------------

    /** A JDK HttpServer on 127.0.0.1 serving a mutable ICS body (or status) at a secret-looking path. */
    static final class IcsStub implements AutoCloseable {
        static final String PATH = "/private/" + SECRET_PATH_TOKEN + ".ics";
        static final String QUERY = "key=" + SECRET_QUERY;

        private final HttpServer server;
        private volatile byte[] body = new byte[0];
        private volatile int status = 200;
        final AtomicInteger hits = new AtomicInteger();
        /** Raw request URIs (path and query) the stub has seen. */
        final List<String> requestUris = new CopyOnWriteArrayList<>();

        IcsStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                requestUris.add(exchange.getRequestURI().toString());
                exchange.getRequestBody().readAllBytes();
                byte[] payload = body;
                exchange.getResponseHeaders().set("Content-Type", "text/calendar; charset=utf-8");
                exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
                if (payload.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                }
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        /** The secret URL: loopback http, a token in the path and a token in the query. */
        String url() {
            return "http://127.0.0.1:" + port() + PATH + "?" + QUERY;
        }

        IcsStub serve(String ics) {
            return serveBytes(ics.getBytes(StandardCharsets.UTF_8));
        }

        IcsStub serveBytes(byte[] bytes) {
            this.body = bytes;
            this.status = 200;
            return this;
        }

        IcsStub failWith(int httpStatus) {
            this.status = httpStatus;
            return this;
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
