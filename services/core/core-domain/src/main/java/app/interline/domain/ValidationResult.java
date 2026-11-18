package app.interline.domain;

import java.util.List;

/** {@code valid} is the input minus every violating item; {@code violations} lists what was removed and why. */
public record ValidationResult(EnhancementOutput valid, List<Violation> violations) { }
