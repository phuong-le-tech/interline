package app.interline.app;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class M1NativeContractTest {
    private static final String BASELINE =
            "la documentation est d être prite marisidi rélye l aughexemples avant fondrédie";
    private static final String RECOVERED =
            "l aud documentation est d être pride marisidi relire l exemples de vant vendredi";

    @Test void emptyOrBlankRecoveredRegionFails() {
        for (String recovered : new String[]{"", "   "}) {
            assertThatThrownBy(() -> M1NativeContract.assertTranscriptSimilar(BASELINE, recovered))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test void halfMissingRegionFails() {
        String half = BASELINE.substring(0, BASELINE.length() / 2);
        assertThat(M1NativeContract.transcriptSimilarity(BASELINE, half)).isLessThan(0.5);
        assertThatThrownBy(() -> M1NativeContract.assertTranscriptSimilar(BASELINE, half))
                .isInstanceOf(AssertionError.class);
    }

    @Test void observedRegionThreeVariationPasses() {
        assertThat(M1NativeContract.transcriptSimilarity(BASELINE, RECOVERED)).isEqualTo(0.775);
        assertThatCode(() -> M1NativeContract.assertTranscriptSimilar(BASELINE, RECOVERED))
                .doesNotThrowAnyException();
    }

    @Test void identicalRegionPasses() {
        assertThat(M1NativeContract.transcriptSimilarity(BASELINE, BASELINE)).isEqualTo(1.0);
        assertThatCode(() -> M1NativeContract.assertTranscriptSimilar(BASELINE, BASELINE))
                .doesNotThrowAnyException();
    }
}
