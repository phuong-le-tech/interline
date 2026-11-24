package app.interline.domain;

/** A denied calendar is a usable manual-meeting mode, not a capture failure. */
public final class CalendarDeniedException extends CalendarUnavailableException {
    public CalendarDeniedException() { super("Calendar access denied"); }
}
