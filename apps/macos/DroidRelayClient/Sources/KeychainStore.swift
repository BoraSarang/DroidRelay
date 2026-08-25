import Foundation
import Security

/// Basic Auth 비밀번호 등 민감값 저장 (UserDefaults 금지, Keychain 사용)
enum KeychainStore {
    private static let service = "com.borasarang.DroidRelayClient"

    static func set(_ value: String?, account: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(base as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var attrs = base
        attrs[kSecValueData as String] = Data(value.utf8)
        let status = SecItemAdd(attrs as CFDictionary, nil)
        if status != errSecSuccess {
            DebugLog.shared.e("E-MAC-STOR-1003", "Keychain 저장 실패(\(account)): \(status)")
        }
    }

    static func get(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
