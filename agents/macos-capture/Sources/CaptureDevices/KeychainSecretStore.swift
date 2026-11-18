import Foundation
import Security

/// Errors of the OS secret store. `notFound` is the only expected failure of `get`.
public enum SecretStoreError: Error, Equatable {
    case notFound
    case failure(Int32)
}

/// Keychain generic password store for provider API keys (service `app.interline`, one item per account).
/// Values are never logged and never leave this type except through `get`.
public final class KeychainSecretStore {
    public static let defaultService = "app.interline"
    private let service: String

    public init(service: String = KeychainSecretStore.defaultService) {
        self.service = service
    }

    private func identity(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    /// Stores the value, replacing any previous one for the account.
    public func put(account: String, value: String) throws {
        let data = Data(value.utf8)
        let status = SecItemUpdate(identity(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw SecretStoreError.failure(status) }
        var item = identity(account)
        item[kSecValueData as String] = data
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else { throw SecretStoreError.failure(added) }
    }

    public func get(account: String) throws -> String {
        var query = identity(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { throw SecretStoreError.notFound }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
            throw SecretStoreError.failure(status)
        }
        return value
    }

    /// Deleting an unknown account is not an error.
    public func delete(account: String) throws {
        let status = SecItemDelete(identity(account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw SecretStoreError.failure(status) }
    }
}
