import CryptoKit
import DeviceCheck
import Flutter
import StoreKit

/// This bridge never grants wallpaper access. The server must validate the
/// signed transaction or the App Attest assertion before issuing delivery.
final class IosAcquisitionBridge: NSObject, FlutterPlugin {
  private let channel: FlutterMethodChannel
  private var updates: Task<Void, Never>?
  private var storefrontUpdates: Task<Void, Never>?
  private let cacheKey = "qingjing.ios.acquisition.cache.v1"
  private let keyIdKey = "qingjing.ios.app-attest.key.v1"
  private let registeredKey = "qingjing.ios.app-attest.registered.v1"

  private init(channel: FlutterMethodChannel) { self.channel = channel }
  static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "qingjing/ios_acquisition",
                                      binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(IosAcquisitionBridge(channel: channel), channel: channel)
  }

  func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let args = call.arguments as? [String: Any] ?? [:]
    Task { @MainActor in
      do {
        switch call.method {
        case "products":
          guard let ids = args["ids"] as? [String], ids.count <= 10_000 else {
            throw Failure.invalidArguments
          }
          let products = try await Product.products(for: ids)
          result(products.filter { $0.type == .nonConsumable || $0.type == .consumable }.map {
            ["id": $0.id, "displayPrice": $0.displayPrice,
             "currencyCode": $0.priceFormatStyle.currencyCode,
             "price": NSDecimalNumber(decimal: $0.price).stringValue,
             "productType": $0.type == .consumable ? "CONSUMABLE" : "NON_CONSUMABLE"]
          })
        case "appIdentity":
          guard #available(iOS 16.0, *) else { throw Failure.proofUnavailable }
          let app: VerificationResult<AppTransaction>
          if args["refresh"] as? Bool == true { app = try await AppTransaction.refresh() }
          else { app = try await AppTransaction.shared }
          guard case .verified = app, let id = AppStore.deviceVerificationID else { throw Failure.proofUnavailable }
          result(["signedAppTransaction": app.jwsRepresentation,
                  "deviceVerificationId": id.uuidString.lowercased()])
        case "purchase":
          if args["testOnly"] as? Bool == true {
#if DEBUG
            guard #available(iOS 16.0, *),
                  case .verified(let app) = try await AppTransaction.shared,
                  app.environment == .xcode else { throw Failure.purchaseUnavailable }
#else
            throw Failure.purchaseUnavailable
#endif
          }
          guard let id = args["productId"] as? String,
                let tokenString = args["accountToken"] as? String,
                let token = UUID(uuidString: tokenString),
                let product = try await Product.products(for: [id]).first else { throw Failure.productUnavailable }
          let quantity = args["quantity"] as? Int ?? 1
          var options: Set<Product.PurchaseOption> = [.appAccountToken(token)]
          if let packCredits = args["packCredits"] as? Int {
            guard product.type == .consumable, (1...3).contains(packCredits), (1...10).contains(quantity),
                  product.priceFormatStyle.currencyCode == "CNY", product.price == Decimal(packCredits) else {
              throw Failure.creditPriceUnavailable
            }
            options.insert(.quantity(quantity))
          } else if product.type != .nonConsumable || quantity != 1 { throw Failure.productUnavailable }
          switch try await product.purchase(options: options) {
          case .success(let verification):
            result(["status": "PURCHASED", "transaction": try await transaction(verification)])
          case .userCancelled: result(["status": "CANCELLED"])
          case .pending: result(["status": "PENDING"])
          @unknown default: throw Failure.purchaseUnavailable
          }
        case "transactions", "restore":
          // Only the explicit Restore button calls sync(), which can prompt for
          // authentication. Startup only reads locally maintained StoreKit data.
          if call.method == "restore" { try await AppStore.sync() }
          var found: [String: [String: Any]] = [:]
          for await verification in Transaction.currentEntitlements {
            let value = try await transaction(verification)
            found[value["id"] as! String] = value
          }
          for await verification in Transaction.unfinished {
            let value = try await transaction(verification)
            found[value["id"] as! String] = value
          }
          result(Array(found.values))
        case "finish":
          guard let id = args["transactionId"] as? String else { throw Failure.invalidArguments }
          for await verification in Transaction.unfinished {
            if case .verified(let item) = verification, String(item.id) == id {
              await item.finish()
              break
            }
          }
          result(nil)
        case "observe":
          if updates == nil {
            updates = Task { @MainActor [weak self] in
              for await verification in Transaction.updates {
                if Task.isCancelled { break }
                guard let self else { break }
                do {
                  self.channel.invokeMethod("transactionUpdated",
                                            arguments: try await self.transaction(verification))
                } catch {
                  // An unverified update cannot confer access or be finished.
                }
              }
            }
          }
          if storefrontUpdates == nil {
            storefrontUpdates = Task { @MainActor [weak self] in
              for await _ in Storefront.updates {
                if Task.isCancelled { break }
                guard let self else { break }
                self.channel.invokeMethod("storefrontUpdated", arguments: nil)
              }
            }
          }
          result(nil)
        case "stopObserving":
          updates?.cancel(); updates = nil
          storefrontUpdates?.cancel(); storefrontUpdates = nil
          result(nil)
        case "readCache": result(UserDefaults.standard.string(forKey: try scopedCacheKey(args)))
        case "writeCache":
          guard let value = args["value"] as? String, value.utf8.count <= 2_000_000,
                let bytes = value.data(using: .utf8),
                (try? JSONSerialization.jsonObject(with: bytes)) is [String: Any] else {
            throw Failure.invalidArguments
          }
          UserDefaults.standard.set(value, forKey: try scopedCacheKey(args)); result(nil)
        case "attestationKey":
          let service = DCAppAttestService.shared
          guard service.isSupported else { throw Failure.proofUnavailable }
          var id = UserDefaults.standard.string(forKey: keyIdKey)
          if id == nil {
            id = try await service.generateKey()
            UserDefaults.standard.set(id, forKey: keyIdKey)
            UserDefaults.standard.set(false, forKey: registeredKey)
          }
          result(["keyId": id!, "registered": UserDefaults.standard.bool(forKey: registeredKey)])
        case "rememberAttestation":
          guard let id = args["keyId"] as? String,
                id == UserDefaults.standard.string(forKey: keyIdKey) else { throw Failure.invalidArguments }
          UserDefaults.standard.set(true, forKey: registeredKey); result(nil)
        case "resetAttestationKey":
          UserDefaults.standard.removeObject(forKey: keyIdKey)
          UserDefaults.standard.removeObject(forKey: registeredKey)
          result(nil)
        case "attest", "assertion":
          guard let id = args["keyId"] as? String,
                id == UserDefaults.standard.string(forKey: keyIdKey),
                let data = args["clientData"] as? String,
                data.utf8.count <= 32_768 else { throw Failure.invalidArguments }
          let hash = Data(SHA256.hash(data: Data(data.utf8)))
          let service = DCAppAttestService.shared
          guard service.isSupported else { throw Failure.proofUnavailable }
          let proof: Data
          if call.method == "attest" { proof = try await service.attestKey(id, clientDataHash: hash) }
          else { proof = try await service.generateAssertion(id, clientDataHash: hash) }
          result(proof.base64EncodedString())
        case "deviceCheckToken":
          guard DCDevice.current.isSupported else { throw Failure.proofUnavailable }
          let data = try await DCDevice.current.generateToken()
          result(data.base64EncodedString())
        default: result(FlutterMethodNotImplemented)
        }
      } catch let failure as Failure {
        result(FlutterError(code: failure.rawValue, message: failure.message, details: nil))
      } catch {
        result(FlutterError(code: "IOS_ACQUISITION_UNAVAILABLE", message: "暂时无法完成验证或购买，请重试", details: nil))
      }
    }
  }

  private func transaction(_ verification: VerificationResult<Transaction>) async throws -> [String: Any] {
    guard case .verified(let item) = verification,
          item.productType == .nonConsumable || item.productType == .consumable else {
      throw Failure.unverifiedTransaction
    }
    guard #available(iOS 16.0, *) else { throw Failure.proofUnavailable }
    let appTransaction = try await AppTransaction.shared
    guard case .verified = appTransaction,
          let deviceVerificationId = AppStore.deviceVerificationID else {
      throw Failure.proofUnavailable
    }
    var environment = "UNKNOWN"
    if #available(iOS 16.0, *) { environment = item.environment.rawValue.uppercased() }
    return ["id": String(item.id), "productId": item.productID,
            "signedTransaction": verification.jwsRepresentation,
            "signedAppTransaction": appTransaction.jwsRepresentation,
            "deviceVerificationId": deviceVerificationId.uuidString.lowercased(),
            "environment": environment, "revoked": item.revocationDate != nil,
            "productType": item.productType == .consumable ? "CONSUMABLE" : "NON_CONSUMABLE",
            "quantity": item.purchasedQuantity, "accountToken": item.appAccountToken?.uuidString.lowercased() ?? ""]
  }
  private func scopedCacheKey(_ args: [String: Any]) throws -> String {
    let scope = args["cacheScope"] as? String ?? "prod"
    guard !scope.isEmpty, scope.utf8.count <= 2_048 else { throw Failure.invalidArguments }
    let hash = SHA256.hash(data: Data(scope.utf8)).map { String(format: "%02x", $0) }.joined()
    return cacheKey + "." + hash
  }
  deinit { updates?.cancel(); storefrontUpdates?.cancel() }
  private enum Failure: String, Error {
    case invalidArguments = "INVALID_ARGUMENTS"
    case productUnavailable = "PRODUCT_UNAVAILABLE"
    case purchaseUnavailable = "PURCHASE_UNAVAILABLE"
    case unverifiedTransaction = "UNVERIFIED_TRANSACTION"
    case proofUnavailable = "DEVICE_PROOF_UNAVAILABLE"
    case creditPriceUnavailable = "CREDIT_PRICE_UNAVAILABLE"
    var message: String {
      switch self {
      case .productUnavailable: return "此壁纸暂不可购买，请稍后重试"
      case .proofUnavailable: return "暂时无法验证免费资格，请稍后重试"
      case .unverifiedTransaction: return "购买结果未通过验证，请尝试恢复购买"
      case .creditPriceUnavailable: return "下载积分仅支持中国大陆商店，请确认商店账号及积分价格"
      default: return "暂时无法完成购买，请重试"
      }
    }
  }
}
