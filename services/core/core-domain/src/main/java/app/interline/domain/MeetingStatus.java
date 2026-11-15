package app.interline.domain;

/** Meeting lifecycle, persisted as lower-case text. */
public enum MeetingStatus {
    DRAFT, RECORDING, DEGRADED, PROCESSING, DONE, FAILED, RECOVERED;

    public String dbValue() { return name().toLowerCase(java.util.Locale.ROOT); }
}
