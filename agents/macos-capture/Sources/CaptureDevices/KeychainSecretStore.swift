import Foundation

/// Errors of the OS secret store. `notFound` is the only expected failure of `get`.
public enum SecretStoreError: Error, Equatable {
    case notFound
    case unimplemented
    case failure(Int32)
}

/// Keychain generic password store for provider API keys (service `app.interline`, one item per account).
/// Skeleton: behaviour is specified by the AC19 cases in CaptureSelfTest.
public final class KeychainSecretStore {
    public static let defaultService = "app.interline"
    private let service: String

    public init(service: String = KeychainSecretStore.defaultService) {
        self.service = service
    }

    public func put(account: String, value: String) throws {
        throw SecretStoreError.unimplemented
    }

    public func get(account: String) throws -> String {
        throw SecretStoreError.unimplemented
    }

    /// Deleting an unknown account is not an error.
    public func delete(account: String) throws {
        throw SecretStoreError.unimplemented
    }
}
