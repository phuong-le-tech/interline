package app.interline.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads an ICS body. The URL is a secret (private calendar addresses embed a token), so nothing here puts the
 * URL, its path or its query in a message: errors name the host at most. https only; {@code webcal://} is rewritten
 * to https; redirects are followed by hand (never HTTPS to HTTP); the body is streamed and capped; the whole
 * exchange has a deadline.
 */
final class HttpIcsFetcher {
    static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_REDIRECTS = 5;
    private static final String LOOPBACK = "127.0.0.1";

    /** Why a fetch failed. The message never contains the URL, its path or its query. */
    static final class IcsFetchException extends Exception {
        IcsFetchException(String message) { super(message); }
    }

    private final boolean allowLoopbackHttp;
    private final Duration totalTimeout;
    private final long maxBytes;
    private final HttpClient client;

    HttpIcsFetcher(boolean allowLoopbackHttp) {
        this(allowLoopbackHttp, Duration.ofSeconds(10), Duration.ofSeconds(30), 10L * 1024 * 1024);
    }

    HttpIcsFetcher(boolean allowLoopbackHttp, Duration connectTimeout, Duration totalTimeout, long maxBytes) {
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.totalTimeout = totalTimeout;
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The URI that will be requested, or an exception saying what is wrong (without echoing the URL). */
    URI normalize(String url) throws IcsFetchException {
        if (url == null || url.isBlank()) throw new IcsFetchException("The calendar address is empty");
        if (url.length() > MAX_URL_LENGTH) throw new IcsFetchException("The calendar address is too long");
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IcsFetchException("The calendar address is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("webcal")) {
            // Keep the raw text after the scheme: rebuilding from decoded parts would encode it twice.
            try {
                uri = new URI("https" + url.strip().substring("webcal".length()));
            } catch (URISyntaxException e) {
                throw new IcsFetchException("The calendar address is not a valid URL");
            }
            scheme = "https";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IcsFetchException("The calendar address has no host");
        boolean loopbackHttp = scheme.equals("http") && allowLoopbackHttp && LOOPBACK.equals(uri.getHost());
        if (!scheme.equals("https") && !loopbackHttp) throw new IcsFetchException("Only https calendar addresses are supported");
        return uri;
    }

    byte[] fetch(String url) throws IcsFetchException {
        URI start = normalize(url);
        String host = start.getHost();
        AtomicReference<InputStream> open = new AtomicReference<>();
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                outcome.set(download(start, open));
            } catch (IcsFetchException e) {
                outcome.set(e);
            } catch (IOException | RuntimeException e) {
                outcome.set(new IcsFetchException("Could not fetch the calendar from " + host));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome.set(new IcsFetchException("Could not fetch the calendar from " + host));
            }
        });
        worker.start();
        try {
            worker.join(totalTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IcsFetchException("Fetching the calendar from " + host + " was interrupted");
        }
        if (worker.isAlive()) {
            InputStream stream = open.get();
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // the deadline already passed; closing only frees the socket
                }
            }
            worker.interrupt();
            throw new IcsFetchException("Fetching the calendar from " + host + " timed out");
        }
        Object result = outcome.get();
        if (result instanceof IcsFetchException e) throw e;
        return (byte[]) result;
    }

    private byte[] download(URI first, AtomicReference<InputStream> open) throws IcsFetchException, IOException, InterruptedException {
        URI current = first;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(totalTimeout)
                    .header("Accept", "text/calendar, text/plain, */*").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            open.set(response.body());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                current = redirectTarget(current, response);
                continue;
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IcsFetchException("The calendar server " + first.getHost() + " answered HTTP " + status);
            }
            return readCapped(response.body(), first.getHost());
        }
        throw new IcsFetchException("Too many redirects from " + first.getHost());
    }

    private URI redirectTarget(URI from, HttpResponse<?> response) throws IcsFetchException {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) throw new IcsFetchException("Redirect without a target from " + from.getHost());
        URI target;
        try {
            target = from.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IcsFetchException("Invalid redirect from " + from.getHost());
        }
        URI checked = normalize(target.toString());
        checkRedirect(from, checked, allowLoopbackHttp);
        return checked;
    }

    /**
     * Policy for a redirect hop (the address the user typed is not subject to the address ban): never from https
     * to http, https otherwise, and no hop to a local or private destination. Messages never carry the URL.
     * DNS rebinding (a public name resolving to a private address) is out of scope.
     */
    static void checkRedirect(URI from, URI to, boolean allowLoopbackHttp) throws IcsFetchException {
        String scheme = to.getScheme() == null ? "" : to.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equalsIgnoreCase(from.getScheme()) && !scheme.equals("https")) {
            throw new IcsFetchException("Refused a redirect from https to http");
        }
        String host = to.getHost() == null ? "" : to.getHost();
        boolean testLoopback = allowLoopbackHttp && scheme.equals("http") && LOOPBACK.equals(host);
        if (!scheme.equals("https") && !testLoopback) throw new IcsFetchException("Refused a redirect to a non-https address");
        if (!testLoopback && isLocalOrPrivate(host)) throw new IcsFetchException("Refused a redirect to a local or private address");
    }

    private static boolean isLocalOrPrivate(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return true;
        }
        if (numericLabelsOnly(host) && !canonicalDottedQuad(host)) return true; // integer, octal or hex IPv4 spellings
        boolean ipv4 = canonicalDottedQuad(host);
        if (!ipv4 && !host.contains(":")) return false; // a name: only IP literals are resolved here, never DNS
        try {
            return isReserved(java.net.InetAddress.getByName(host));
        } catch (java.net.UnknownHostException e) {
            return true;
        }
    }

    private static boolean numericLabelsOnly(String host) {
        for (String label : host.split("\\.", -1)) {
            if (!label.matches("0x[0-9a-f]+|\\d+")) return false;
        }
        return true;
    }

    private static boolean canonicalDottedQuad(String host) {
        return host.matches("(0|[1-9]\\d{0,2})(\\.(0|[1-9]\\d{0,2})){3}")
                && java.util.Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
    }

    private static boolean isReserved(java.net.InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            boolean sharedAddressSpace = b0 == 100 && (b1 & 0xC0) == 64;      // 100.64.0.0/10
            boolean benchmarking = b0 == 198 && (b1 & 0xFE) == 18;            // 198.18.0.0/15
            boolean protocolAssignments = b0 == 192 && b1 == 0 && b[2] == 0;  // 192.0.0.0/24
            return sharedAddressSpace || benchmarking || protocolAssignments;
        }
        boolean uniqueLocal = (b[0] & 0xFE) == 0xFC;                           // fc00::/7
        boolean nat64 = b[0] == 0 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && b[3] == (byte) 0x9B && zeros(b, 4, 12); // 64:ff9b::/96
        boolean ipv4Compatible = zeros(b, 0, 12);                              // ::a.b.c.d
        return uniqueLocal || nat64 || ipv4Compatible;
    }

    private static boolean zeros(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) if (bytes[i] != 0) return false;
        return true;
    }

    private byte[] readCapped(InputStream in, String host) throws IOException, IcsFetchException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IcsFetchException("The calendar from " + host + " is larger than the allowed size");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
