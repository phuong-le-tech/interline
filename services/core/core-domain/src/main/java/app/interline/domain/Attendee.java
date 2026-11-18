package app.interline.domain;

/** A calendar attendee. {@code email} is trimmed and lower-cased, or null when the source gave none. */
public record Attendee(String name, String email) { }
