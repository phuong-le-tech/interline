package app.interline.domain;

import java.util.Optional;

/** The two capture tracks that share one session timeline. */
public enum StreamKind {
    MIC(0, "mic", "Me"),
    SYSTEM(1, "system", "Them");

    private final int wireIndex;
    private final String wireName;
    private final String speakerLabel;

    StreamKind(int wireIndex, String wireName, String speakerLabel) {
        this.wireIndex = wireIndex;
        this.wireName = wireName;
        this.speakerLabel = speakerLabel;
    }

    public int wireIndex() { return wireIndex; }
    public String wireName() { return wireName; }
    public String speakerLabel() { return speakerLabel; }

    public static Optional<StreamKind> fromWireIndex(int index) {
        for (StreamKind kind : values()) if (kind.wireIndex == index) return Optional.of(kind);
        return Optional.empty();
    }

    public static Optional<StreamKind> fromWireName(String name) {
        for (StreamKind kind : values()) if (kind.wireName.equals(name)) return Optional.of(kind);
        return Optional.empty();
    }
}
