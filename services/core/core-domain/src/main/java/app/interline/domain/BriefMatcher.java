package app.interline.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Picks the past meetings worth showing in the brief of a meeting, without AI: same recurring series, a shared
 * participant, or the same title once case, accents and spacing are ignored.
 */
public final class BriefMatcher {
    public static final int MAX_RELATED = 10;

    /** Why a past meeting is related; the first rule that applies wins. */
    public enum Reason { SERIES, PARTICIPANT, TITLE }

    public record Subject(String meetingId, String title, Set<String> personIds, String seriesUid) { }

    public record Candidate(String meetingId, String title, long dateMs, Set<String> personIds, String seriesUid) { }

    public record Match(Candidate candidate, Reason reason) { }

    private static final Comparator<Match> RECENT_FIRST = Comparator
            .comparingLong((Match match) -> match.candidate().dateMs()).reversed()
            .thenComparing(match -> match.candidate().meetingId());

    private BriefMatcher() { }

    /** Lower case, no accents, single spaces; empty for null. */
    public static String normalizeTitle(String title) {
        if (title == null) return "";
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        // \p{Z} covers no-break and ideographic spaces that \s and strip() leave alone.
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    /** The UID part of a recurring occurrence's external id ({@code UID#start}); null when there is none. */
    public static String seriesUid(String externalId) {
        if (externalId == null || externalId.isBlank()) return null;
        int hash = externalId.indexOf('#');
        String uid = hash < 0 ? externalId : externalId.substring(0, hash);
        return uid.isBlank() ? null : uid;
    }

    public static List<Match> rank(Subject subject, List<Candidate> candidates) {
        List<Match> matches = new ArrayList<>();
        if (subject == null || candidates == null) return matches;
        String title = normalizeTitle(subject.title());
        for (Candidate candidate : candidates) {
            if (candidate == null || Objects.equals(candidate.meetingId(), subject.meetingId())) continue;
            Reason reason = reason(subject, title, candidate);
            if (reason != null) matches.add(new Match(candidate, reason));
        }
        matches.sort(RECENT_FIRST);
        return matches.size() > MAX_RELATED ? new ArrayList<>(matches.subList(0, MAX_RELATED)) : matches;
    }

    private static Reason reason(Subject subject, String normalizedTitle, Candidate candidate) {
        if (subject.seriesUid() != null && subject.seriesUid().equals(candidate.seriesUid())) return Reason.SERIES;
        if (sharesPerson(subject.personIds(), candidate.personIds())) return Reason.PARTICIPANT;
        if (!normalizedTitle.isEmpty() && normalizedTitle.equals(normalizeTitle(candidate.title()))) return Reason.TITLE;
        return null;
    }

    private static boolean sharesPerson(Set<String> a, Set<String> b) {
        if (a == null || b == null) return false;
        for (String id : a) if (b.contains(id)) return true;
        return false;
    }
}
