package app.interline.ai;

/** {@code detail} is safe to show: it never contains the API key, even when the server echoes it. */
public record ConnectionResult(Outcome outcome, String detail) {
    public enum Outcome { OK, UNAUTHORIZED, UNREACHABLE }
}
