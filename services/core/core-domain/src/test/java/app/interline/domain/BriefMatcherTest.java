package app.interline.domain;

import app.interline.domain.BriefMatcher.Candidate;
import app.interline.domain.BriefMatcher.Match;
import app.interline.domain.BriefMatcher.Reason;
import app.interline.domain.BriefMatcher.Subject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 slice 2: BriefMatcher, pure matching of related meetings. */
class BriefMatcherTest {
    private static Subject subject(String id, String title, Set<String> people, String series) {
        return new Subject(id, title, people, series);
    }

    private static Candidate cand(String id, String title, long date, Set<String> people, String series) {
        return new Candidate(id, title, date, people, series);
    }

    private static List<String> ids(List<Match> matches) {
        return matches.stream().map(m -> m.candidate().meetingId()).toList();
    }

    // ---- constants ----

    @Test
    void maxRelatedIsTen() {
        assertThat(BriefMatcher.MAX_RELATED).isEqualTo(10);
    }

    @Test
    void reasonEnumHasTheThreeValuesInPrecedenceOrder() {
        assertThat(Reason.values()).containsExactly(Reason.SERIES, Reason.PARTICIPANT, Reason.TITLE);
    }

    // ---- normalizeTitle ----

    @Test
    void normalizeTitleNullIsEmpty() {
        assertThat(BriefMatcher.normalizeTitle(null)).isEmpty();
    }

    @Test
    void normalizeTitleBlankIsEmpty() {
        assertThat(BriefMatcher.normalizeTitle("   \t \n ")).isEmpty();
        assertThat(BriefMatcher.normalizeTitle("")).isEmpty();
    }

    @Test
    void normalizeTitleLowerCases() {
        assertThat(BriefMatcher.normalizeTitle("Weekly SYNC")).isEqualTo("weekly sync");
    }

    @Test
    void normalizeTitleStripsAccents() {
        assertThat(BriefMatcher.normalizeTitle("Réunion Équipe")).isEqualTo("reunion equipe");
        assertThat(BriefMatcher.normalizeTitle("Café über ñ")).isEqualTo("cafe uber n");
    }

    @Test
    void normalizeTitleStripsPrecomposedAndDecomposedAccentsAlike() {
        assertThat(BriefMatcher.normalizeTitle("é"))
                .isEqualTo(BriefMatcher.normalizeTitle("é"))
                .isEqualTo("e");
    }

    @Test
    void normalizeTitleTrims() {
        assertThat(BriefMatcher.normalizeTitle("  hello  ")).isEqualTo("hello");
    }

    @Test
    void normalizeTitleCollapsesWhitespaceRuns() {
        assertThat(BriefMatcher.normalizeTitle("a   b\t\tc\n\nd")).isEqualTo("a b c d");
    }

    @Test
    void normalizeTitleCombinesAllRules() {
        assertThat(BriefMatcher.normalizeTitle("  ÉQUIPE \t Planning   "))
                .isEqualTo("equipe planning");
    }

    @Test
    void normalizeTitleKeepsPunctuationAndDigits() {
        assertThat(BriefMatcher.normalizeTitle("Q3: Plan #2")).isEqualTo("q3: plan #2");
    }

    @Test
    void normalizeTitleLowerCasesWithRootLocale() {
        // a Turkish dotted capital I must not turn into a dotless i
        assertThat(BriefMatcher.normalizeTitle("TITLE")).isEqualTo("title");
    }

    // ---- seriesUid ----

    @Test
    void seriesUidTakesThePartBeforeTheFirstHash() {
        assertThat(BriefMatcher.seriesUid("abc@host#20260101T090000Z")).isEqualTo("abc@host");
        assertThat(BriefMatcher.seriesUid("a#b#c")).isEqualTo("a");
    }

    @Test
    void seriesUidWithoutHashIsTheWholeValue() {
        assertThat(BriefMatcher.seriesUid("uid")).isEqualTo("uid");
    }

    @Test
    void seriesUidNullOrBlankIsNull() {
        assertThat(BriefMatcher.seriesUid(null)).isNull();
        assertThat(BriefMatcher.seriesUid("")).isNull();
        assertThat(BriefMatcher.seriesUid("   ")).isNull();
    }

    // ---- rank: reasons ----

    @Test
    void seriesMatchWhenBothSeriesUidsAreEqual() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "uid"),
                List.of(cand("c", "y", 10, Set.of(), "uid")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isEqualTo(Reason.SERIES);
        assertThat(out.get(0).candidate().meetingId()).isEqualTo("c");
    }

    @Test
    void differentSeriesUidsDoNotMatchBySeries() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), "u1"),
                List.of(cand("c", "y", 10, Set.of(), "u2")))).isEmpty();
    }

    @Test
    void nullSeriesOnBothSidesIsNotASeriesMatch() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), null),
                List.of(cand("c", "y", 10, Set.of(), null)))).isEmpty();
    }

    @Test
    void nullSeriesOnOneSideIsNotASeriesMatch() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), null),
                List.of(cand("c", "y", 10, Set.of(), "u")))).isEmpty();
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), "u"),
                List.of(cand("c", "y", 10, Set.of(), null)))).isEmpty();
    }

    @Test
    void participantMatchWhenPersonIdsIntersect() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of("p1", "p2"), null),
                List.of(cand("c", "y", 10, Set.of("p2", "p3"), null)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isEqualTo(Reason.PARTICIPANT);
    }

    @Test
    void disjointPersonIdsDoNotMatch() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of("p1"), null),
                List.of(cand("c", "y", 10, Set.of("p2"), null)))).isEmpty();
    }

    @Test
    void titleMatchWhenNormalizedTitlesAreEqual() {
        List<Match> out = BriefMatcher.rank(subject("s", "  Weekly   SYNC ", Set.of(), null),
                List.of(cand("c", "weekly sync", 10, Set.of(), null)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).reason()).isEqualTo(Reason.TITLE);
    }

    @Test
    void titleMatchIgnoresAccents() {
        List<Match> out = BriefMatcher.rank(subject("s", "Réunion", Set.of(), null),
                List.of(cand("c", "reunion", 10, Set.of(), null)));
        assertThat(out).extracting(Match::reason).containsExactly(Reason.TITLE);
    }

    @Test
    void differentTitlesDoNotMatch() {
        assertThat(BriefMatcher.rank(subject("s", "alpha", Set.of(), null),
                List.of(cand("c", "beta", 10, Set.of(), null)))).isEmpty();
    }

    @Test
    void emptyNormalizedTitlesNeverMatch() {
        assertThat(BriefMatcher.rank(subject("s", "   ", Set.of(), null),
                List.of(cand("c", "", 10, Set.of(), null)))).isEmpty();
        assertThat(BriefMatcher.rank(subject("s", null, Set.of(), null),
                List.of(cand("c", null, 10, Set.of(), null)))).isEmpty();
    }

    @Test
    void oneEmptyTitleNeverMatchesANonEmptyOne() {
        assertThat(BriefMatcher.rank(subject("s", "", Set.of(), null),
                List.of(cand("c", "x", 10, Set.of(), null)))).isEmpty();
    }

    // ---- rank: precedence ----

    @Test
    void seriesWinsOverParticipantAndTitle() {
        List<Match> out = BriefMatcher.rank(subject("s", "Sync", Set.of("p"), "u"),
                List.of(cand("c", "sync", 10, Set.of("p"), "u")));
        assertThat(out).extracting(Match::reason).containsExactly(Reason.SERIES);
    }

    @Test
    void participantWinsOverTitle() {
        List<Match> out = BriefMatcher.rank(subject("s", "Sync", Set.of("p"), "u1"),
                List.of(cand("c", "sync", 10, Set.of("p"), "u2")));
        assertThat(out).extracting(Match::reason).containsExactly(Reason.PARTICIPANT);
    }

    @Test
    void titleAppliesWhenSeriesAndParticipantsDoNot() {
        List<Match> out = BriefMatcher.rank(subject("s", "Sync", Set.of("p1"), "u1"),
                List.of(cand("c", "SYNC", 10, Set.of("p2"), "u2")));
        assertThat(out).extracting(Match::reason).containsExactly(Reason.TITLE);
    }

    @Test
    void eachCandidateGetsItsOwnReason() {
        List<Match> out = BriefMatcher.rank(subject("s", "Sync", Set.of("p"), "u"), List.of(
                cand("a", "other", 40, Set.of(), "u"),
                cand("b", "other", 30, Set.of("p"), null),
                cand("c", "sync", 20, Set.of(), null),
                cand("d", "nothing", 10, Set.of(), null)));
        assertThat(out).extracting(m -> m.candidate().meetingId() + ":" + m.reason())
                .containsExactly("a:SERIES", "b:PARTICIPANT", "c:TITLE");
    }

    @Test
    void matchCarriesTheCandidateUnchanged() {
        Candidate c = cand("c", "y", 10, Set.of("p"), "u");
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of("p"), null), List.of(c));
        assertThat(out.get(0).candidate()).isEqualTo(c);
    }

    // ---- rank: self exclusion ----

    @Test
    void theSubjectsOwnMeetingIsDropped() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of("p"), "u"), List.of(
                cand("s", "x", 99, Set.of("p"), "u"),
                cand("c", "x", 10, Set.of("p"), "u")));
        assertThat(ids(out)).containsExactly("c");
    }

    // ---- rank: ordering ----

    @Test
    void orderedByDateDescending() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), List.of(
                cand("a", "1", 10, Set.of(), "u"),
                cand("b", "2", 30, Set.of(), "u"),
                cand("c", "3", 20, Set.of(), "u")));
        assertThat(ids(out)).containsExactly("b", "c", "a");
    }

    @Test
    void equalDatesBreakTiesByMeetingIdAscending() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), List.of(
                cand("m3", "1", 10, Set.of(), "u"),
                cand("m1", "2", 10, Set.of(), "u"),
                cand("m2", "3", 10, Set.of(), "u")));
        assertThat(ids(out)).containsExactly("m1", "m2", "m3");
    }

    @Test
    void orderIgnoresTheReason() {
        List<Match> out = BriefMatcher.rank(subject("s", "t", Set.of("p"), "u"), List.of(
                cand("a", "z", 10, Set.of(), "u"),
                cand("b", "t", 20, Set.of(), null),
                cand("c", "z", 15, Set.of("p"), null)));
        assertThat(ids(out)).containsExactly("b", "c", "a");
    }

    @Test
    void orderIsIndependentOfInputOrder() {
        List<Candidate> input = new ArrayList<>(List.of(
                cand("a", "1", 10, Set.of(), "u"),
                cand("b", "2", 10, Set.of(), "u"),
                cand("c", "3", 30, Set.of(), "u")));
        List<String> forward = ids(BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input));
        java.util.Collections.reverse(input);
        List<String> backward = ids(BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input));
        assertThat(forward).containsExactly("c", "a", "b").isEqualTo(backward);
    }

    // ---- rank: limit ----

    @Test
    void resultIsCappedAtTenKeepingTheMostRecent() {
        List<Candidate> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) input.add(cand("m" + i, "t", i, Set.of(), "u"));
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input);
        assertThat(out).hasSize(10);
        assertThat(out.get(0).candidate().meetingId()).isEqualTo("m11");
        assertThat(out.get(9).candidate().meetingId()).isEqualTo("m2");
    }

    @Test
    void exactlyTenAreAllKept() {
        List<Candidate> input = new ArrayList<>();
        for (int i = 0; i < 10; i++) input.add(cand("m" + i, "t", i, Set.of(), "u"));
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input)).hasSize(10);
    }

    @Test
    void theCapAppliesAfterDroppingNonMatchesAndSelf() {
        List<Candidate> input = new ArrayList<>();
        input.add(cand("s", "t", 1000, Set.of(), "u"));
        for (int i = 0; i < 5; i++) input.add(cand("n" + i, "nope", 500 + i, Set.of(), "other"));
        for (int i = 0; i < 11; i++) input.add(cand("m" + i, "t", i, Set.of(), "u"));
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input);
        assertThat(out).hasSize(10);
        assertThat(ids(out)).doesNotContain("s", "n0", "n4");
        assertThat(out.get(0).candidate().meetingId()).isEqualTo("m10");
    }

    @Test
    void tieBreakDecidesWhichOnesSurviveTheCap() {
        List<Candidate> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) input.add(cand(String.format("m%02d", i), "t", 5, Set.of(), "u"));
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), input);
        assertThat(ids(out)).containsExactly("m00", "m01", "m02", "m03", "m04", "m05", "m06", "m07", "m08", "m09");
    }

    // ---- rank: edges and null safety ----

    @Test
    void emptyCandidatesGiveAnEmptyList() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), "u"), List.of())).isEmpty();
    }

    @Test
    void nullCandidatesGiveAnEmptyList() {
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of(), "u"), null)).isEmpty();
    }

    @Test
    void nullPersonIdSetsAreTreatedAsEmpty() {
        assertThat(BriefMatcher.rank(subject("s", "x", null, null),
                List.of(cand("c", "y", 10, null, null)))).isEmpty();
        assertThat(BriefMatcher.rank(subject("s", "x", null, null),
                List.of(cand("c", "y", 10, Set.of("p"), null)))).isEmpty();
        assertThat(BriefMatcher.rank(subject("s", "x", Set.of("p"), null),
                List.of(cand("c", "y", 10, null, null)))).isEmpty();
    }

    @Test
    void nullTitlesDoNotThrowAndStillAllowOtherMatches() {
        List<Match> out = BriefMatcher.rank(subject("s", null, Set.of("p"), null),
                List.of(cand("c", null, 10, Set.of("p"), null)));
        assertThat(out).extracting(Match::reason).containsExactly(Reason.PARTICIPANT);
    }

    @Test
    void rankDoesNotMutateItsInputs() {
        Set<String> people = new HashSet<>(Set.of("p"));
        List<Candidate> input = new ArrayList<>(List.of(
                cand("a", "t", 1, people, null), cand("b", "t", 2, people, null)));
        List<Candidate> copy = List.copyOf(input);
        BriefMatcher.rank(subject("s", "t", people, null), input);
        assertThat(input).isEqualTo(copy);
        assertThat(people).containsExactly("p");
    }

    @Test
    void negativeAndZeroDatesOrderNumerically() {
        List<Match> out = BriefMatcher.rank(subject("s", "x", Set.of(), "u"), List.of(
                cand("a", "1", -5, Set.of(), "u"),
                cand("b", "2", 0, Set.of(), "u"),
                cand("c", "3", 5, Set.of(), "u")));
        assertThat(ids(out)).containsExactly("c", "b", "a");
    }
}
