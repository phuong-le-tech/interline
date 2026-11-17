package app.interline.app;

import app.interline.domain.SecretStore;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The core's {@link SecretStore}: whatever agent is currently connected. Without an agent there is no OS secret
 * store, and saving a key fails instead of falling back to the disk.
 */
final class AgentSecrets implements SecretStore {
    private final AtomicReference<SecretStore> agent = new AtomicReference<>();

    void bind(SecretStore store) { agent.set(store); }

    void unbind() { agent.set(null); }

    private SecretStore current() {
        SecretStore store = agent.get();
        if (store == null) throw new IllegalStateException("No secret store is available");
        return store;
    }

    @Override public Optional<String> get(String account) { return current().get(account); }

    @Override public void put(String account, String value) { current().put(account, value); }

    @Override public void delete(String account) { current().delete(account); }
}
