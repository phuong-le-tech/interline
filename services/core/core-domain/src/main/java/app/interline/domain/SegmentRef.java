package app.interline.domain;

/** A transcript segment as the validator sees it: a citation is valid only for a non-suppressed segment of this meeting. */
public record SegmentRef(long id, String meetingId, boolean suppressed) { }
