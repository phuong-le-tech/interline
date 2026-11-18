package app.interline.domain;

import java.util.Optional;

/**
 * Port for the OS secret store (Keychain, Credential Manager, libsecret), reached through the capture agent with
 * the {@code secret.get}, {@code secret.put} and {@code secret.delete} messages of Agent Protocol v1.
 * A key is fetched per call, never cached on disk and never logged.
 */
public interface SecretStore {
    /** The stored value, or empty when the account has no secret. */
    Optional<String> get(String account);

    void put(String account, String value);

    /** Removing an unknown account is not an error. */
    void delete(String account);
}
