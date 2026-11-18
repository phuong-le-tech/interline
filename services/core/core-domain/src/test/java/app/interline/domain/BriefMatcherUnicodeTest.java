package app.interline.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Review round 1: normalizeTitle collapses all Unicode whitespace. */
class BriefMatcherUnicodeTest {
    @Test
    void noBreakSpaceIsCollapsedToOneSpace() {
        assertThat(BriefMatcher.normalizeTitle("Weekly Sync")).isEqualTo("weekly sync");
    }

    @Test
    void narrowNoBreakSpaceIsCollapsedToOneSpace() {
        assertThat(BriefMatcher.normalizeTitle("Weekly Sync")).isEqualTo("weekly sync");
    }

    @Test
    void ideographicSpaceIsCollapsedToOneSpace() {
        assertThat(BriefMatcher.normalizeTitle("Weekly　Sync")).isEqualTo("weekly sync");
    }

    @Test
    void tabAndNewlineAreCollapsedToOneSpace() {
        assertThat(BriefMatcher.normalizeTitle("Weekly\tSync")).isEqualTo("weekly sync");
        assertThat(BriefMatcher.normalizeTitle("Weekly\nSync")).isEqualTo("weekly sync");
        assertThat(BriefMatcher.normalizeTitle("Weekly\r\nSync")).isEqualTo("weekly sync");
    }

    @Test
    void mixedRunsOfUnicodeWhitespaceBecomeASingleSpace() {
        assertThat(BriefMatcher.normalizeTitle("a   　\t\nb")).isEqualTo("a b");
    }

    @Test
    void leadingAndTrailingUnicodeWhitespaceIsTrimmed() {
        assertThat(BriefMatcher.normalizeTitle(" 　Planning  ")).isEqualTo("planning");
        assertThat(BriefMatcher.normalizeTitle("\t\nPlanning\n\t")).isEqualTo("planning");
    }

    @Test
    void aTitleOfOnlyUnicodeWhitespaceNormalizesToEmpty() {
        assertThat(BriefMatcher.normalizeTitle("  　\t\n")).isEmpty();
    }

    @Test
    void titlesDifferingOnlyByWhitespaceKindNormalizeEqually() {
        assertThat(BriefMatcher.normalizeTitle("Team Sync")).isEqualTo(BriefMatcher.normalizeTitle("team sync"));
        assertThat(BriefMatcher.normalizeTitle("Team　Sync")).isEqualTo(BriefMatcher.normalizeTitle("Team\tSync"));
    }

    @Test
    void whitespaceKindsStillMatchAsATitleRelation() {
        var subject = new BriefMatcher.Subject("s", "Team Sync", java.util.Set.of(), null);
        var candidate = new BriefMatcher.Candidate("c", "team　sync", 1L, java.util.Set.of(), null);
        var matches = BriefMatcher.rank(subject, java.util.List.of(candidate));
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).reason()).isEqualTo(BriefMatcher.Reason.TITLE);
    }

    @Test
    void accentsAndCaseStillNormalizeAlongsideUnicodeWhitespace() {
        assertThat(BriefMatcher.normalizeTitle("Réunion ÉQUIPE")).isEqualTo("reunion equipe");
    }
}
