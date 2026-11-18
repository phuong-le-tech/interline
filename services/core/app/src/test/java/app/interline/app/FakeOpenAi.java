package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The stub model of the @SpringBootTest layer: a loopback OpenAI-compatible endpoint that records every request and
 * answers chat completions from a queue. The core talks to it through a real provider row, so the whole path
 * (provider config, secret fetch, HTTP shape, parsing) is exercised without any production seam.
 */
final class FakeOpenAi implements AutoCloseable {
    static final int PROMPT_TOKENS = 1000;
    static final int COMPLETION_TOKENS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Recorded(String method, String path, Map<String, String> headers, String body) {
        boolean isChat() { return method.equals("POST") && path.endsWith("/chat/completions"); }

        /** The text of every message of a chat request, in order, as the model would read it. */
        String prompt() {
            try {
                StringBuilder text = new StringBuilder();
                for (JsonNode message : JSON.readTree(body).path("messages")) {
                    JsonNode content = message.path("content");
                    if (content.isTextual()) text.append(content.asText()).append('\n');
                    else for (JsonNode part : content) text.append(part.path("text").asText()).append('\n');
                }
                return text.toString();
            } catch (IOException e) {
                throw new IllegalStateException("not a chat request: " + body, e);
            }
        }
    }

    final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final Deque<String> replies = new ArrayDeque<>();
    private volatile int errorStatus;
    private volatile boolean echoAuthorizationInError;

    FakeOpenAi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
            requests.add(recorded);
            int status = 200;
            String response;
            if (errorStatus != 0) {
                status = errorStatus;
                response = "{\"error\":{\"message\":\"rejected"
                        + (echoAuthorizationInError ? ": " + headers.getOrDefault("authorization", "") + headers.getOrDefault("x-api-key", "") : "")
                        + "\"}}";
            } else if (recorded.isChat()) {
                response = completion(nextReply());
            } else {
                response = "{\"object\":\"list\",\"data\":[]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** OpenAI-compatible base URL, as a user would type it into Settings. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Queue the model's answers; the last one repeats when the queue runs dry. */
    synchronized FakeOpenAi reply(String... contents) {
        replies.clear();
        replies.addAll(List.of(contents));
        errorStatus = 0;
        return this;
    }

    /** Reject every request with this status; the error body optionally echoes the credentials it received. */
    FakeOpenAi rejectWith(int status, boolean echoCredentials) {
        errorStatus = status;
        echoAuthorizationInError = echoCredentials;
        return this;
    }

    FakeOpenAi accept() {
        errorStatus = 0;
        return this;
    }

    List<Recorded> chatCalls() {
        return requests.stream().filter(Recorded::isChat).toList();
    }

    private synchronized String nextReply() {
        if (replies.isEmpty()) throw new IllegalStateException("no reply queued for the stub model");
        return replies.size() > 1 ? replies.poll() : replies.peek();
    }

    private static String completion(String content) {
        ObjectNode root = JSON.createObjectNode().put("id", "chatcmpl-stub").put("object", "chat.completion")
                .put("created", 0).put("model", "stub-model");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject().put("index", 0).put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        root.putObject("usage").put("prompt_tokens", PROMPT_TOKENS).put("completion_tokens", COMPLETION_TOKENS)
                .put("total_tokens", PROMPT_TOKENS + COMPLETION_TOKENS);
        return root.toString();
    }

    @Override public void close() {
        server.stop(0);
    }
}
