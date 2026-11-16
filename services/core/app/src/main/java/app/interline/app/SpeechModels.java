package app.interline.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The speech and voice-activity models available on this machine. */
public interface SpeechModels {
    String MODEL_ARGUMENT = "--model=";
    String VAD_MODEL_ARGUMENT = "--vad-model=";

    Optional<Path> speechModel();

    Optional<Path> vadModel();

    /** Models named on the agent command line (an operator override) win over the downloaded ones. */
    static SpeechModels overriddenBy(List<String> agentArguments, SpeechModels installed) {
        return new SpeechModels() {
            @Override public Optional<Path> speechModel() {
                return argument(agentArguments, MODEL_ARGUMENT).or(installed::speechModel);
            }

            @Override public Optional<Path> vadModel() {
                return argument(agentArguments, VAD_MODEL_ARGUMENT).or(installed::vadModel);
            }
        };
    }

    private static Optional<Path> argument(List<String> arguments, String prefix) {
        return arguments.stream().filter(arg -> arg.startsWith(prefix)).findFirst()
                .map(arg -> Path.of(arg.substring(prefix.length())));
    }
}
