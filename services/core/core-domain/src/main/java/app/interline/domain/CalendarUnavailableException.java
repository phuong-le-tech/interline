package app.interline.domain;

/** The calendar could not be read. The message never contains the source URL. */
public class CalendarUnavailableException extends RuntimeException {
    public CalendarUnavailableException(String message) { super(message); }
}
