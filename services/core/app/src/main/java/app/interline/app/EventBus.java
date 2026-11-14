package app.interline.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fan-out of server events to every open SSE connection. */
@Component
public class EventBus {
    private static final System.Logger LOG = System.getLogger(EventBus.class.getName());
    private static final long NO_TIMEOUT = 0L;
    private final ObjectMapper json = new ObjectMapper();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        try {
            // An early comment flushes the response headers so clients know the stream is open.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    public void publish(String name, Object payload) {
        String data;
        try {
            data = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot serialize event " + name, e);
            return;
        }
        for (SseEmitter subscriber : subscribers) {
            try {
                subscriber.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(subscriber);
            }
        }
    }
}
