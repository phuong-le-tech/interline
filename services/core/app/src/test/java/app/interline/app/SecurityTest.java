package app.interline.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** AC10 acceptance tests against the real embedded HTTP listener, without replacement controllers. */
@SpringBootTest(
        classes = InterlineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {

            "server.port=0",
            "interline.token=" + SecurityTest.TEST_TOKEN,
            "interline.allowed-origins=tauri://localhost,http://tauri.localhost,http://localhost:1420"
        })
class SecurityTest {
    // Public, deterministic 256-bit fixture. Never use this token outside tests.
    static final String TEST_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String VALID_AUTHORIZATION = "Bearer " + TEST_TOKEN;
    private static final String DEV_ORIGIN = "http://localhost:1420";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final List<Route> ROUTES = List.of(
            new Route("POST", "/api/v1/recording/start"),
            new Route("POST", "/api/v1/recording/stop"),
            new Route("GET", "/api/v1/meetings"),
            new Route("GET", "/api/v1/meetings/00000000-0000-0000-0000-000000000001/transcript"),
            new Route("GET", "/api/v1/events"));
    private static final List<String> ALLOWED_ORIGINS =
            List.of("tauri://localhost", "http://tauri.localhost", DEV_ORIGIN);

    @Value("${local.server.port}")
    private int port;

    static Stream<Arguments> AC10_invalidAuthorizations() {
        List<String> malformed = List.of(
                "Bearer",
                "Bearer not-a-256-bit-token",
                "Basic " + TEST_TOKEN,
                TEST_TOKEN,
                VALID_AUTHORIZATION + " extra");
        return ROUTES.stream().flatMap(route -> Stream.concat(
                Stream.of(
                        Arguments.of(route, "missing", null),
                        Arguments.of(route, "wrong", "Bearer " + "f".repeat(64))),
                malformed.stream().map(value -> Arguments.of(route, "malformed", value))));
    }

    @ParameterizedTest(name = "AC10 {0}: {1} authorization returns 401 problem JSON [{index}]")
    @MethodSource("AC10_invalidAuthorizations")
    void AC10_rejectsMissingWrongAndMalformedTokens(Route route, String kind, String authorization)
            throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }

        Response response = request(route, validHost(), headers);

        assertThat(response.status()).as("AC10 %s token on %s", kind, route).isEqualTo(401);
        assertThat(response.header("content-type"))
                .as("AC10 unauthorized errors use RFC 9457 problem JSON")
                .matches("(?i)application/problem\\+json(?:\\s*;.*)?");
    }

    static Stream<Arguments> AC10_foreignOrigins() {
        return ROUTES.stream().flatMap(route -> Stream.of(
                        "https://attacker.example", "null", "http://localhost:9999",
                        "http://tauri.localhost.attacker.example")
                .map(origin -> Arguments.of(route, origin)));
    }

    @ParameterizedTest(name = "AC10 {0}: foreign Origin {1} returns 403")
    @MethodSource("AC10_foreignOrigins")
    void AC10_rejectsForeignOriginsBeforeControllers(Route route, String origin) throws Exception {
        Response response = request(route, validHost(), Map.of(
                "Authorization", VALID_AUTHORIZATION, "Origin", origin));

        assertThat(response.status()).as("AC10 foreign Origin on %s", route).isEqualTo(403);
        assertThat(response.header("access-control-allow-origin")).isNull();
    }

    static Stream<Arguments> AC10_invalidHosts() {
        return ROUTES.stream().flatMap(route -> Stream.of(
                        "attacker.example", "localhost", "ipv6", "wrong-port", "missing-port")
                .map(host -> Arguments.of(route, host)));
    }

    @ParameterizedTest(name = "AC10 {0}: invalid Host {1} returns 403")
    @MethodSource("AC10_invalidHosts")
    void AC10_rejectsRebindingLocalhostIpv6AndWrongPortHosts(Route route, String hostCase)
            throws Exception {
        String host = switch (hostCase) {
            case "localhost" -> "localhost:" + port;
            case "ipv6" -> "[::1]:" + port;
            case "wrong-port" -> "127.0.0.1:" + (port == 65535 ? 65534 : port + 1);
            case "missing-port" -> "127.0.0.1";
            default -> "attacker.example:" + port;
        };

        Response response = request(route, host, Map.of("Authorization", VALID_AUTHORIZATION));

        assertThat(response.status()).as("AC10 reject Host %s on %s", host, route).isEqualTo(403);
    }

    static Stream<Arguments> AC10_allowedPreflights() {
        return ROUTES.stream().flatMap(route -> ALLOWED_ORIGINS.stream()
                .map(origin -> Arguments.of(route, origin)));
    }

    @ParameterizedTest(name = "AC10 {0}: unauthenticated preflight for {1} returns 204")
    @MethodSource("AC10_allowedPreflights")
    void AC10_allowsConfiguredPreflightWithoutToken(Route route, String origin) throws Exception {
        Response response = request(new Route("OPTIONS", route.path()), validHost(), Map.of(
                "Origin", origin,
                "Access-Control-Request-Method", route.method(),
                "Access-Control-Request-Headers", "authorization,content-type"));

        assertThat(response.status()).isEqualTo(204);
        assertThat(response.header("access-control-allow-origin")).isEqualTo(origin);
        assertThat(commaSeparated(response.header("access-control-allow-methods")))
                .contains(route.method().toLowerCase(Locale.ROOT));
        assertThat(commaSeparated(response.header("access-control-allow-headers")))
                .contains("authorization", "content-type");
        assertThat(response.header("access-control-allow-credentials")).isNull();
    }

    static Stream<Arguments> AC10_authenticatedOrigins() {
        return Stream.concat(Stream.of(Arguments.of((String) null)),
                ALLOWED_ORIGINS.stream().map(Arguments::of));
    }

    @ParameterizedTest(name = "AC10 valid token with Origin {0} can list meetings")
    @MethodSource("AC10_authenticatedOrigins")
    void AC10_allowsValidTokenWithAbsentOrAllowedOrigin(String origin) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", VALID_AUTHORIZATION);
        if (origin != null) {
            headers.put("Origin", origin);
        }

        Response response = request(new Route("GET", "/api/v1/meetings"), validHost(), headers);

        assertThat(response.status()).isEqualTo(200);
        if (origin != null) {
            assertThat(response.header("access-control-allow-origin")).isEqualTo(origin);
        }
        assertThat(response.header("access-control-allow-credentials")).isNull();
    }

    @Test
    void AC10_actualListeningSocketBindsOnlyIpv4Loopback() throws Exception {
        // Inspect the live OS socket, not server.address or a mocked server factory.
        Process process = new ProcessBuilder(
                "lsof", "-nP", "-a", "-p", Long.toString(ProcessHandle.current().pid()),
                "-iTCP:" + port, "-sTCP:LISTEN", "-Fn")
                .redirectErrorStream(true)
                .start();
        try {
            assertThat(process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("AC10 live listener inspection must complete")
                    .isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("AC10 lsof output: %s", output).isZero();
            List<String> listeners = output.lines().filter(line -> line.startsWith("n")).toList();
            assertThat(listeners)
                    .as("AC10 all live listeners on the actual random HTTP port")
                    .isNotEmpty()
                    .allSatisfy(listener -> assertThat(listener).isEqualTo("n127.0.0.1:" + port));
        } finally {
            process.destroyForcibly();
        }
    }

    private String validHost() {
        return "127.0.0.1:" + port;
    }

    private static List<String> commaSeparated(String header) {
        return header == null ? List.of() : Stream.of(header.split(","))
                .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    private Response request(Route route, String host, Map<String, String> headers) throws Exception {
        // A raw HTTP connection preserves the exact Host and Origin, including for POST requests.
        // Read headers only and close: an accepted SSE response must not hang the test suite.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            StringBuilder wire = new StringBuilder()
                    .append(route.method()).append(' ').append(route.path()).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: 0\r\n");
            headers.forEach((name, value) -> wire.append(name).append(": ").append(value).append("\r\n"));
            wire.append("\r\n");
            socket.getOutputStream().write(wire.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            assertThat(statusLine).as("AC10 real HTTP response for %s", route).isNotNull();
            assertThat(statusLine).startsWith("HTTP/1.1 ");
            int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                int colon = line.indexOf(':');
                assertThat(colon).as("AC10 valid response header: %s", line).isPositive();
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                responseHeaders.merge(name, value, (left, right) -> left + "," + right);
            }
            return new Response(status, responseHeaders);
        }
    }

    private record Route(String method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private record Response(int status, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name);
        }
    }
}
