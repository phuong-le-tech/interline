package app.interline.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Test double for the model: records every request, answers from a queue or a function. */
final class RecordingLlm implements LlmPort {
    static final int INPUT_TOKENS = 100;
    static final int OUTPUT_TOKENS = 20;

    final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private final Function<LlmRequest, String> responder;

    private RecordingLlm(List<String> replies, Function<LlmRequest, String> responder) {
        queue.addAll(replies);
        this.responder = responder;
    }

    /** Answers with the given texts in order; the last one repeats when the queue runs dry. */
    static RecordingLlm replying(String... replies) {
        return new RecordingLlm(List.of(replies), null);
    }

    static RecordingLlm responding(Function<LlmRequest, String> responder) {
        return new RecordingLlm(List.of(), responder);
    }

    @Override public synchronized LlmReply complete(LlmRequest request) {
        requests.add(request);
        String text = responder != null ? responder.apply(request) : queue.size() > 1 ? queue.poll() : queue.peek();
        return new LlmReply(text, INPUT_TOKENS, OUTPUT_TOKENS);
    }

    /** System and user text of request {@code index}, as one string. */
    String prompt(int index) {
        LlmRequest request = requests.get(index);
        return request.system() + "\n" + request.user();
    }
}
