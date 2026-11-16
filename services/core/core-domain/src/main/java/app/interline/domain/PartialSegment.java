package app.interline.domain;

/** A revisable hypothesis for a speech region that has not been finalised yet; the final replaces it in place. */
public record PartialSegment(StreamKind stream, String seg, long revision, long startSample, String text) { }
