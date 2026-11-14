package app.interline.agent;

import java.io.IOException;

/** A frame violated the wire contract (unknown kind, length over the limit, truncation or bad payload). */
public final class BadFrameException extends IOException {
    public BadFrameException(String message) { super(message); }
    public BadFrameException(String message, Throwable cause) { super(message, cause); }
}
