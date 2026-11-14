package app.interline.domain;

/** A closed speech region with its final text, as reported by a capture agent. */
public record FinalSegment(StreamKind stream, String seg, long startSample, long endSample,
                           String text, String model, Double avgLogprob) { }
