import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {
  private static let privacyConsentChannel = "qingjing/privacy_consent"
  private static let acceptedPolicyVersionKey = "accepted_policy_version"

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    if let controller = window?.rootViewController as? FlutterViewController {
      let channel = FlutterMethodChannel(
        name: Self.privacyConsentChannel,
        binaryMessenger: controller.binaryMessenger
      )
      channel.setMethodCallHandler { call, result in
        switch call.method {
        case "cachedPolicies":
          result(UserDefaults.standard.string(forKey: "cached_online_policies"))
        case "savePolicies":
          guard let text = call.arguments as? String, text.utf8.count <= 1_100_000 else {
            result(FlutterError(code: "INVALID_POLICIES", message: "Invalid policies", details: nil))
            return
          }
          UserDefaults.standard.set(text, forKey: "cached_online_policies")
          result(nil)
        case "acceptedVersion":
          result(UserDefaults.standard.string(forKey: Self.acceptedPolicyVersionKey))
        case "acceptVersion":
          guard let version = call.arguments as? String,
                !version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            result(FlutterError(
              code: "INVALID_VERSION",
              message: "Policy version is required",
              details: nil
            ))
            return
          }
          UserDefaults.standard.set(version, forKey: Self.acceptedPolicyVersionKey)
          result(nil)
        default:
          result(FlutterMethodNotImplemented)
        }
      }
      let updates = FlutterMethodChannel(
        name: "qingjing/app_updates",
        binaryMessenger: controller.binaryMessenger
      )
      updates.setMethodCallHandler { call, result in
        switch call.method {
        case "installedVersion":
          let info = Bundle.main.infoDictionary ?? [:]
          result([
            "versionName": info["CFBundleShortVersionString"] as? String ?? "0.0.0",
            "versionCode": Int(info["CFBundleVersion"] as? String ?? "0") ?? 0,
            "packageName": Bundle.main.bundleIdentifier ?? ""
          ])
        case "cachedRequirement":
          guard let scope = call.arguments as? String else {
            result(FlutterError(code: "INVALID_SCOPE", message: "Invalid scope", details: nil))
            return
          }
          result(UserDefaults.standard.string(forKey: "app_update_requirement_" + scope))
        case "saveRequirement":
          guard let value = call.arguments as? [String: Any],
                let scope = value["scope"] as? String else {
            result(FlutterError(code: "INVALID_SCOPE", message: "Invalid scope", details: nil))
            return
          }
          let key = "app_update_requirement_" + scope
          if let json = value["value"] as? String {
            UserDefaults.standard.set(json, forKey: key)
          } else {
            UserDefaults.standard.removeObject(forKey: key)
          }
          result(nil)
        case "openStore":
          guard let text = call.arguments as? String,
                var url = URLComponents(string: text),
                url.scheme == "https", url.user == nil, url.password == nil,
                url.fragment == nil, url.port == nil || url.port == 443,
                ["apps.apple.com", "itunes.apple.com"].contains(url.host?.lowercased() ?? ""),
                url.path.range(of: "id[0-9]+", options: .regularExpression) != nil else {
            result(FlutterError(code: "INVALID_STORE_URL", message: "Invalid App Store URL", details: nil))
            return
          }
          url.scheme = "itms-apps"
          guard let destination = url.url else {
            result(FlutterError(code: "INVALID_STORE_URL", message: "Invalid App Store URL", details: nil))
            return
          }
          UIApplication.shared.open(destination, options: [:]) { opened in
            if opened { result(nil) }
            else { result(FlutterError(code: "STORE_UNAVAILABLE", message: "Unable to open App Store", details: nil)) }
          }
        default:
          result(FlutterMethodNotImplemented)
        }
      }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
