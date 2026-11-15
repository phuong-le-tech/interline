package app.interline.app;

import java.net.URI;
import java.nio.file.Path;

/** M1 API skeleton only: no download behavior is implemented here. */
public final class ModelDownloader {
    public ModelDownloader(Path modelsDirectory) { }

    /** Explicit user-requested download; resume a sibling .part and publish only verified bytes. */
    public Path download(URI source, String fileName, String sha256) throws java.io.IOException {
        throw new UnsupportedOperationException("M1 resumable, checksum-verified model download is not implemented");
    }
}
