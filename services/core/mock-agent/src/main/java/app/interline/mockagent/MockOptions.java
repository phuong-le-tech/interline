package app.interline.mockagent;

import java.nio.file.Path;

/**
 * Command line of the MockAgent. Unknown options are an error so a typo cannot silently disable a fault.
 * {@code wav} and {@code script} are optional until a session starts; {@code deviceChangeAtSample} and
 * {@code crashAtSample} are negative when the fault is off.
 */
record MockOptions(int protocol, Path wav, Path script, double speed, long crashAtSample, boolean denySystemAudio,
                   long deviceChangeAtSample, boolean silentSystem) {
    private static final int SAMPLE_RATE = 16000;

    static MockOptions parse(String[] args) {
        int protocol = 1;
        Path wav = null;
        Path script = null;
        double speed = 1;
        long crashAt = -1;
        long deviceChangeAt = -1;
        boolean deny = false;
        boolean silent = false;
        for (String arg : args) {
            int equals = arg.indexOf('=');
            String key = equals < 0 ? arg : arg.substring(0, equals);
            String value = equals < 0 ? "" : arg.substring(equals + 1);
            switch (key) {
                case "--protocol" -> protocol = Integer.parseInt(value);
                case "--log-level" -> { /* accepted for contract compatibility, logs always go to stderr */ }
                case "--wav" -> wav = Path.of(value);
                case "--script" -> script = Path.of(value);
                case "--speed" -> speed = Double.parseDouble(value);
                case "--crash-at" -> crashAt = samples(value);
                case "--device-change-at" -> deviceChangeAt = samples(value);
                case "--deny" -> deny = value.equals("system_audio");
                case "--silent-system" -> silent = true;
                default -> throw new IllegalArgumentException("unknown option " + arg);
            }
        }
        if (speed <= 0) throw new IllegalArgumentException("--speed must be positive");
        return new MockOptions(protocol, wav, script, speed, crashAt, deny, deviceChangeAt, silent);
    }

    private static long samples(String seconds) {
        return (long) (Double.parseDouble(seconds.replaceAll("s$", "")) * SAMPLE_RATE);
    }
}
