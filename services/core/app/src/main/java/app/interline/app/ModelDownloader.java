package app.interline.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * Fetches one model file on explicit user request. The bytes go to {@code <name>.part}; an interrupted download keeps
 * them and the next attempt resumes with an HTTP {@code Range}. Only SHA-256 verified bytes are renamed into place.
 * Nothing here runs unless {@link #download} is called: the core never contacts a model origin by itself.
 */
public final class ModelDownloader {
    private static final int HTTP_OK = 200;
    private static final int HTTP_PARTIAL = 206;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final String PART_SUFFIX = ".part";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final Path models;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public ModelDownloader(Path modelsDirectory) { this.models = modelsDirectory; }

    public Path download(URI source, String fileName, String sha256) throws IOException {
        return download(source, fileName, sha256, bytes -> { });
    }

    /** @param progress receives the total bytes on disk after every chunk, including the resumed prefix */
    public Path download(URI source, String fileName, String sha256, LongConsumer progress) throws IOException {
        Files.createDirectories(models);
        Path part = models.resolve(fileName + PART_SUFFIX);
        Path target = models.resolve(fileName);
        long have = Files.exists(part) ? Files.size(part) : 0;
        HttpRequest.Builder request = HttpRequest.newBuilder(source).GET();
        if (have > 0) request.header("Range", "bytes=" + have + "-");
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == HTTP_RANGE_NOT_SATISFIABLE && have > 0) return publish(part, target, sha256);
            if (status != HTTP_OK && status != HTTP_PARTIAL) throw new IOException("model origin answered HTTP " + status);
            // A server that ignores Range sends the whole file with 200: start over rather than append to it.
            boolean resumed = status == HTTP_PARTIAL;
            long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long received = copy(body, part, resumed, resumed ? have : 0, progress);
            if (expected >= 0 && received != expected) {
                throw new IOException("model download ended after " + received + " of " + expected + " bytes");
            }
        }
        return publish(part, target, sha256);
    }

    private static long copy(InputStream body, Path part, boolean append, long already, LongConsumer progress) throws IOException {
        long received = 0;
        var options = append
                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
        try (OutputStream out = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                received += read;
                progress.accept(already + received);
            }
        }
        return received;
    }

    /** Verify the finished {@code .part}; a mismatch deletes it so a corrupt file is never resumed or installed. */
    private Path publish(Path part, Path target, String expectedSha256) throws IOException {
        String actual = sha256(part);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(part);
            throw new IOException("checksum mismatch for " + target.getFileName() + ": expected " + expectedSha256 + " but got " + actual);
        }
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }
}
