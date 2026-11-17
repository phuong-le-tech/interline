package app.interline.domain;

import app.interline.domain.EnhancementOutput.ActionItem;
import app.interline.domain.EnhancementOutput.Addition;
import app.interline.domain.EnhancementOutput.BlockAdditions;
import app.interline.domain.EnhancementOutput.OtherPoint;
import app.interline.domain.Violation.Rule;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provider-agnostic validation of enhancement output (docs/ARCHITECTURE.md section 10, US-3.9). Every rule is checked
 * per item; the result keeps the valid items and lists every violation, so the caller can retry or drop.
 */
public final class EnhancementValidator {
    /** An addition may be at most this many characters longer than the fragment it sits under. */
    static final int BUDGET_EXTRA_CHARS = 80;
    static final int MAX_SENTENCES = 3;
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]+(\\s+|$)");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final ValidationContext context;
    private final Set<Long> citableSegments;

    public EnhancementValidator(ValidationContext context) {
        this.context = context;
        this.citableSegments = context.segments().stream()
                .filter(s -> s.meetingId().equals(context.meetingId()) && !s.suppressed())
                .map(SegmentRef::id).collect(Collectors.toUnmodifiableSet());
    }

    public ValidationResult validate(EnhancementOutput output) {
        List<Violation> violations = new ArrayList<>();
        List<BlockAdditions> blocks = new ArrayList<>();
        for (BlockAdditions block : output.blocks()) {
            validateBlock(block, violations).ifPresent(blocks::add);
        }
        List<OtherPoint> points = output.otherPoints().stream()
                .filter(p -> check(p.text(), p.sources(), violations)).toList();
        List<ActionItem> actions = output.actionItems().stream()
                .filter(a -> check(a.text(), a.sources(), violations)).toList();
        return new ValidationResult(new EnhancementOutput(List.copyOf(blocks), points, actions, output.titleSuggestion()),
                List.copyOf(violations));
    }

    private Optional<BlockAdditions> validateBlock(BlockAdditions block, List<Violation> violations) {
        String fragment = context.userBlockTexts().get(block.userBlockId());
        if (fragment == null) {
            violations.add(new Violation(Rule.UNKNOWN_BLOCK, "user_block_id " + block.userBlockId()));
            return Optional.empty();
        }
        List<Addition> kept = block.additions().stream()
                .filter(a -> check(a.text(), a.sources(), violations) && withinBudget(a.text(), fragment, violations))
                .toList();
        return kept.isEmpty() ? Optional.empty() : Optional.of(new BlockAdditions(block.userBlockId(), kept));
    }

    /** Text present and at least one citable source. */
    private boolean check(String text, List<Long> sources, List<Violation> violations) {
        if (text.isBlank() || normalise(text).isEmpty()) {
            violations.add(new Violation(Rule.EMPTY_TEXT, "empty text"));
            return false;
        }
        if (sources.isEmpty()) {
            violations.add(new Violation(Rule.UNSOURCED, "no source for \"" + text + "\""));
            return false;
        }
        if (sources.stream().noneMatch(citableSegments::contains)) {
            violations.add(new Violation(Rule.UNKNOWN_SOURCE, "unknown source " + sources + " for \"" + text + "\""));
            return false;
        }
        return true;
    }

    private boolean withinBudget(String text, String fragment, List<Violation> violations) {
        if (text.length() > fragment.length() + BUDGET_EXTRA_CHARS) {
            violations.add(new Violation(Rule.OVER_BUDGET, "over budget: \"" + text + "\""));
            return false;
        }
        if (sentenceCount(text) > MAX_SENTENCES) {
            violations.add(new Violation(Rule.TOO_MANY_SENTENCES, "more than " + MAX_SENTENCES + " sentences: \"" + text + "\""));
            return false;
        }
        if (normalise(fragment).contains(normalise(text))) {
            violations.add(new Violation(Rule.RESTATES_FRAGMENT, "restates the user's text: \"" + text + "\""));
            return false;
        }
        return true;
    }

    private static int sentenceCount(String text) {
        String trimmed = text.strip();
        int count = 0;
        int from = 0;
        var matcher = SENTENCE_END.matcher(trimmed);
        while (matcher.find()) {
            if (matcher.start() > from) count++;
            from = matcher.end();
        }
        return from < trimmed.length() ? count + 1 : count;
    }

    /** Lower case, no accents, no punctuation, single spaces: so a restatement cannot hide behind formatting. */
    private static String normalise(String text) {
        String plain = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return NOT_WORD.matcher(plain.toLowerCase()).replaceAll(" ").strip();
    }
}
