package app.interline.domain;

import app.interline.domain.Alignment.BlockAnchor;
import app.interline.domain.Alignment.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC10: window selection and block/transcript alignment (docs: windowed mode, 45 s look-back). */
class AlignmentTest {
    private static final BlockAnchor A = new BlockAnchor("a", 100_000);
    private static final BlockAnchor B = new BlockAnchor("b", 400_000);
    private static final BlockAnchor C = new BlockAnchor("c", 800_000);

    @ParameterizedTest(name = "AC10_modeSelection_{0}_chars_context_{1}")
    @CsvSource({
            "0,1000,FULL",
            "3000,1000,FULL",
            "3004,1000,WINDOWED",
            "300000,100000,FULL",
            "300004,100000,WINDOWED"})
    void AC10_modeSelection_fullUpToSeventyFivePercentOfContextThenWindowed(long chars, int context, EnhanceMode expected) {
        assertThat(ModeSelector.select(chars, context)).isEqualTo(expected);
    }

    @Test
    void AC10_modeSelection_unknownContextIsAlwaysFull() {
        assertThat(ModeSelector.select(50_000_000L, null)).isEqualTo(EnhanceMode.FULL);
    }

    @Test
    void AC10_windows_startFortyFiveSecondsBeforeTheAnchorAndEndAtTheNextAnchor() {
        List<Window> windows = Alignment.windows(List.of(A, B, C));

        assertThat(windows).containsExactly(
                new Window("a", 55_000, 400_000),
                new Window("b", 355_000, 800_000),
                new Window("c", 755_000, Long.MAX_VALUE));
    }

    @Test
    void AC10_windows_neverStartBeforeTheMeetingStart() {
        assertThat(Alignment.windows(List.of(new BlockAnchor("early", 10_000))))
                .containsExactly(new Window("early", 0, Long.MAX_VALUE));
    }

    @Test
    void AC10_windows_areOrderedByAnchorWhateverTheInputOrder() {
        assertThat(Alignment.windows(List.of(C, A, B))).extracting(Window::blockId).containsExactly("a", "b", "c");
    }

    @Test
    void AC10_windows_emptyInputGivesNoWindow() {
        assertThat(Alignment.windows(List.of())).isEmpty();
    }

    @ParameterizedTest(name = "AC10_windowContains_{0}ms_{1}")
    @CsvSource({"54999,false", "55000,true", "399999,true", "400000,false"})
    void AC10_windows_lowerBoundIsInclusiveAndUpperBoundExclusive(long segmentStartMs, boolean inside) {
        Window first = Alignment.windows(List.of(A, B, C)).get(0);

        assertThat(first.contains(segmentStartMs)).isEqualTo(inside);
    }

    @Test
    void AC10_windows_consecutiveWindowsOverlapByTheLookBack() {
        List<Window> windows = Alignment.windows(List.of(A, B));

        assertThat(windows.get(0).contains(380_000)).isTrue();
        assertThat(windows.get(1).contains(380_000)).isTrue();
        assertThat(windows.get(0).contains(400_000)).isFalse();
        assertThat(windows.get(1).contains(400_000)).isTrue();
    }
}
