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
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
