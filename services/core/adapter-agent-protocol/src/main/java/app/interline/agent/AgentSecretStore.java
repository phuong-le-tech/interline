package app.interline.agent;

import app.interline.domain.SecretStore;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Optional;

/**
 * {@link SecretStore} over the agent's {@code secret.*} messages. Nothing is cached: every call is a round trip,
 * so the key lives in the core only for the duration of one provider call. Failure messages never carry the value.
 */
public final class AgentSecretStore implements SecretStore {
    static final Duration REPLY_TIMEOUT = Duration.ofSeconds(5);
    private static final String ACCOUNT = "account";
    private static final String NOT_FOUND = "secret_not_found";

    /** One request/reply exchange with the connected agent. */
    @FunctionalInterface
    interface Exchange {
        ControlMessage call(String type, ObjectNode body, Duration timeout);
    }

    private final Exchange exchange;

    AgentSecretStore(Exchange exchange) { this.exchange = exchange; }

    @Override public Optional<String> get(String account) {
        ControlMessage reply = exchange.call("secret.get", account(account), REPLY_TIMEOUT);
        if (isError(reply)) {
            if (NOT_FOUND.equals(reply.body().path("code").asText())) return Optional.empty();
            throw failure("get", reply);
        }
        return Optional.of(reply.body().path("value").asText());
    }

    @Override public void put(String account, String value) {
        ControlMessage reply = exchange.call("secret.put", account(account).put("value", value), REPLY_TIMEOUT);
        if (isError(reply)) throw failure("put", reply);
    }

    @Override public void delete(String account) {
        ControlMessage reply = exchange.call("secret.delete", account(account), REPLY_TIMEOUT);
        if (isError(reply) && !NOT_FOUND.equals(reply.body().path("code").asText())) throw failure("delete", reply);
    }

    private static ObjectNode account(String account) {
        return ControlMessage.nodes().objectNode().put(ACCOUNT, account);
    }

    private static boolean isError(ControlMessage reply) { return "error".equals(reply.type()); }

    private static IllegalStateException failure(String operation, ControlMessage reply) {
        return new IllegalStateException("secret " + operation + " failed: " + reply.body().path("code").asText("internal"));
    }
}
