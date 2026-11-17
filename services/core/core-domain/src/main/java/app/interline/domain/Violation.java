package app.interline.domain;

/** One broken rule. {@code detail} names the offending id or text so a retry prompt can list it. */
public record Violation(Rule rule, String detail) {
    public enum Rule { UNKNOWN_BLOCK, UNSOURCED, UNKNOWN_SOURCE, OVER_BUDGET, TOO_MANY_SENTENCES, RESTATES_FRAGMENT, EMPTY_TEXT }
}
