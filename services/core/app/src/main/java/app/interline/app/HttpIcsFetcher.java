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
            try {
                uri = new URI("https", uri.getRawAuthority(), uri.getRawPath(), uri.getRawQuery(), null);
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
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(checked.getScheme())) {
            throw new IcsFetchException("Refused a redirect from https to http");
        }
        return checked;
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
