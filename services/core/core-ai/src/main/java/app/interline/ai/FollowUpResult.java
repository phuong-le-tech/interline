package app.interline.ai;

import java.util.List;

/** A draft only: nothing in the core sends email. */
public record FollowUpResult(Status status, String subject, String body, List<Long> sources, String message) {
    public enum Status { OK, INVALID_OUTPUT }
}
