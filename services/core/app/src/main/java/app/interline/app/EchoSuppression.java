package app.interline.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a microphone final is the speakers' output leaking back into the microphone (ARCHITECTURE 9.2).
 * Echo cancellation in the agent lowers the leak, so a tighter overlap is demanded of the heuristic when it is on.
 */
final class EchoSuppression {
    /** A mic final and a system final this close in start time may be the same utterance. */
    static final long WINDOW_MS = 2000;
    private static final double OVERLAP_WITHOUT_AEC = 0.70;
    private static final double OVERLAP_WITH_AEC = 0.85;

    private EchoSuppression() { }

    /** True when more than the threshold share of the mic tokens also occurs in the system text. */
    static boolean isEcho(String micText, String systemText, boolean aecActive) {
        List<String> mic = tokens(micText);
        if (mic.isEmpty()) return false;
        List<String> remaining = tokens(systemText);
        int shared = 0;
        for (String token : mic) if (remaining.remove(token)) shared++;
        double threshold = aecActive ? OVERLAP_WITH_AEC : OVERLAP_WITHOUT_AEC;
        return (double) shared / mic.size() > threshold;
    }

    private static List<String> tokens(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : folded.split("[^\\p{L}\\p{N}]+")) if (!token.isEmpty()) tokens.add(token);
        return tokens;
    }
}
