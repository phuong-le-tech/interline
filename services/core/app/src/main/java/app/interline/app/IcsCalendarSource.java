package app.interline.app;

import app.interline.app.HttpIcsFetcher.IcsFetchException;
import app.interline.domain.CalendarSnapshot;
import app.interline.domain.CalendarSource;
import app.interline.domain.CalendarUnavailableException;
import app.interline.domain.IcsParser;
import app.interline.domain.SecretStore;

import java.time.ZoneId;

/** The portable calendar: an ICS subscription URL kept in the secret store, fetched and parsed on demand. */
final class IcsCalendarSource implements CalendarSource {
    static final String ACCOUNT = "calendar.ics";

    private final SecretStore secrets;
    private final HttpIcsFetcher fetcher;
    private final ZoneId zone;

    IcsCalendarSource(SecretStore secrets, HttpIcsFetcher fetcher, ZoneId zone) {
        this.secrets = secrets;
        this.fetcher = fetcher;
        this.zone = zone;
    }

    @Override public CalendarSnapshot fetch(long windowStartMs, long windowEndMs) {
        String url = secrets.get(ACCOUNT).orElseThrow(() -> new CalendarUnavailableException("No calendar address is configured"));
        try {
            return IcsParser.parse(fetcher.fetch(url), zone, windowStartMs, windowEndMs);
        } catch (IcsFetchException e) {
            throw new CalendarUnavailableException(e.getMessage());
        }
    }
}
