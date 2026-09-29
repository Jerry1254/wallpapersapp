import CryptoKit
import Flutter
import Photos
import Security
import UIKit

public final class WallpaperIosPlugin: NSObject, FlutterPlugin {
  private static let channelName = "qingjing/wallpaper_ios"
  private static let keyTag = Data("com.qingjing.bizhi.installation.p256.v1".utf8)
  private static let keychainService = "com.qingjing.bizhi.installation.v1"
  private static let initializedKey = "qingjing.installation.initialized.v1"
  private static let savedMediaPrefix = "qingjing.saved-media.v1."
  private static let credentialAccount = "credentialKeyId"
  private static let pendingAccount = "pendingRedemption"
  private var saves: [String: Task<Void, Never>] = [:]
  private var previewTasks: [String: Task<Void, Never>] = [:]
  private var previewDirectories: [String: URL] = [:]

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(WallpaperIosPlugin(), channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    do {
      switch call.method {
      case "identity":
        result(try identity())
      case "sign":
        let arguments = try dictionary(call.arguments)
        result(try sign(requiredString(arguments, "payload", maxLength: 16_384)))
      case "rememberCredential":
        let value = try requiredCanonicalUUID(try dictionary(call.arguments), "credentialKeyId")
        try writeKeychain(account: Self.credentialAccount, value: value)
        result(nil)
      case "resetIdentity":
        try resetIdentity()
        result(nil)
      case "readPendingRedemption":
        try prepareInstallation()
        result(readKeychain(account: Self.pendingAccount))
      case "writePendingRedemption":
        let arguments = try dictionary(call.arguments)
        if let value = arguments["value"] as? String {
          try validatePendingRedemption(value)
          try writeKeychain(account: Self.pendingAccount, value: value)
        } else {
          deleteKeychain(account: Self.pendingAccount)
        }
        result(nil)
      case "savedMedia":
        result(UserDefaults.standard.string(forKey: try savedMediaKey(dictionary(call.arguments))))
      case "saveMedia":
        startSave(try dictionary(call.arguments), result: result)
      case "cancelSave":
        let requestId = try requiredCanonicalUUID(try dictionary(call.arguments), "requestId")
        saves.removeValue(forKey: requestId)?.cancel()
        result(nil)
      case "prepareLivePhotoPreview":
        startLivePhotoPreview(try dictionary(call.arguments), result: result)
      case "releaseLivePhotoPreview":
        try releaseLivePhotoPreview(try dictionary(call.arguments))
        result(nil)
      default:
        result(FlutterMethodNotImplemented)
      }
    } catch let failure as PluginFailure {
      result(failure.flutterError)
    } catch {
      result(PluginFailure.identityUnavailable.flutterError)
    }
  }

  private func identity() throws -> [String: Any] {
    let privateKey = try installationKey()
    guard let publicKey = SecKeyCopyPublicKey(privateKey),
          let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
          raw.count == 65, raw.first == 0x04 else {
      throw PluginFailure.identityUnavailable
    }
    let spki = try p256Spki(raw)
    guard let scope = Bundle.main.bundleIdentifier,
          ["com.qingjing.bizhi", "com.qingjing.livephotolab"].contains(scope) else {
      throw PluginFailure.bundleNotAllowed
    }
    var response: [String: Any] = [
      "publicKeyPem": pem(spki),
      "fingerprint": SHA256.hash(data: spki).hex,
      "scope": scope,
    ]
    if let credential = readKeychain(account: Self.credentialAccount) {
      response["credentialKeyId"] = credential
    }
    return response
  }

  private func sign(_ payload: String) throws -> String {
    let data = Data(payload.utf8)
    guard data.count <= 16_384 else { throw PluginFailure.invalidArguments }
    var error: Unmanaged<CFError>?
    guard let signature = SecKeyCreateSignature(
      try installationKey(), .ecdsaSignatureMessageX962SHA256, data as CFData, &error
    ) as Data? else { throw PluginFailure.identityUnavailable }
    return signature.base64Url
  }

  private func prepareInstallation() throws {
    let defaults = UserDefaults.standard
    if !defaults.bool(forKey: Self.initializedKey) {
      deleteInstallationKey()
      deleteKeychain(account: Self.credentialAccount)
      deleteKeychain(account: Self.pendingAccount)
      defaults.set(true, forKey: Self.initializedKey)
    }
  }

  private func installationKey() throws -> SecKey {
    try prepareInstallation()
    let query: [CFString: Any] = [
      kSecClass: kSecClassKey,
      kSecAttrApplicationTag: Self.keyTag,
      kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
      kSecReturnRef: true,
      kSecMatchLimit: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecSuccess, let item {
      return item as! SecKey
    }
    guard status == errSecItemNotFound else { throw PluginFailure.identityUnavailable }

    deleteKeychain(account: Self.credentialAccount)
    deleteKeychain(account: Self.pendingAccount)
    var attributes: [CFString: Any] = [
      kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
      kSecAttrKeySizeInBits: 256,
    ]
    var privateAttributes: [CFString: Any] = [
      kSecAttrIsPermanent: true,
      kSecAttrApplicationTag: Self.keyTag,
    ]
#if targetEnvironment(simulator)
    privateAttributes[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
#else
    var accessError: Unmanaged<CFError>?
    guard let access = SecAccessControlCreateWithFlags(
      nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .privateKeyUsage, &accessError
    ) else { throw PluginFailure.identityUnavailable }
    attributes[kSecAttrTokenID] = kSecAttrTokenIDSecureEnclave
    privateAttributes[kSecAttrAccessControl] = access
#endif
    attributes[kSecPrivateKeyAttrs] = privateAttributes
    var createError: Unmanaged<CFError>?
    guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &createError) else {
      throw PluginFailure.identityUnavailable
    }
    return key
  }

  private func resetIdentity() throws {
    deleteInstallationKey()
    deleteKeychain(account: Self.credentialAccount)
    deleteKeychain(account: Self.pendingAccount)
    _ = try installationKey()
  }

  private func deleteInstallationKey() {
    SecItemDelete([
      kSecClass: kSecClassKey,
      kSecAttrApplicationTag: Self.keyTag,
      kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
    ] as CFDictionary)
  }

  private func p256Spki(_ raw: Data) throws -> Data {
    let prefix = Data([
      0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D,
      0x02, 0x01, 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01,
      0x07, 0x03, 0x42, 0x00,
    ])
    guard raw.count == 65 else { throw PluginFailure.identityUnavailable }
    return prefix + raw
  }

  private func pem(_ data: Data) -> String {
    let encoded = data.base64EncodedString()
    let lines = stride(from: 0, to: encoded.count, by: 64).map { offset -> String in
      let start = encoded.index(encoded.startIndex, offsetBy: offset)
      let end = encoded.index(start, offsetBy: min(64, encoded.distance(from: start, to: encoded.endIndex)))
      return String(encoded[start..<end])
    }
    return "-----BEGIN PUBLIC KEY-----\n\(lines.joined(separator: "\n"))\n-----END PUBLIC KEY-----"
  }

  private func startSave(_ arguments: [String: Any], result: @escaping FlutterResult) {
    do {
      let requestId = try requiredCanonicalUUID(arguments, "requestId")
      guard saves[requestId] == nil else { throw PluginFailure.invalidArguments }
      let task = Task { @MainActor [weak self] in
        guard let self else { return }
        defer { self.saves.removeValue(forKey: requestId) }
        do {
          result(try await self.saveMedia(arguments))
        } catch is CancellationError {
          result(PluginFailure.cancelled.flutterError)
        } catch let error as URLError where error.code == .cancelled {
          result(PluginFailure.cancelled.flutterError)
        } catch let failure as PluginFailure {
          result(failure.flutterError)
        } catch {
          result(PluginFailure.saveFailed.flutterError)
        }
      }
      saves[requestId] = task
    } catch let failure as PluginFailure {
      result(failure.flutterError)
    } catch {
      result(PluginFailure.invalidArguments.flutterError)
    }
  }

  private func startLivePhotoPreview(_ arguments: [String: Any], result: @escaping FlutterResult) {
    do {
      let requestId = try requiredCanonicalUUID(arguments, "requestId")
      guard previewTasks[requestId] == nil, previewDirectories[requestId] == nil else {
        throw PluginFailure.invalidArguments
      }
      let task = Task { @MainActor [weak self] in
        guard let self else { return }
        defer { self.previewTasks.removeValue(forKey: requestId) }
        do {
          result(try await self.prepareLivePhotoPreview(arguments, requestId: requestId))
        } catch is CancellationError {
          result(PluginFailure.cancelled.flutterError)
        } catch let error as URLError where error.code == .cancelled {
          result(PluginFailure.cancelled.flutterError)
        } catch let failure as PluginFailure {
          result(failure.flutterError)
        } catch {
          result(PluginFailure.downloadInvalid.flutterError)
        }
      }
      previewTasks[requestId] = task
    } catch let failure as PluginFailure {
      result(failure.flutterError)
    } catch {
      result(PluginFailure.invalidArguments.flutterError)
    }
  }

  @MainActor
  private func prepareLivePhotoPreview(
    _ arguments: [String: Any], requestId: String
  ) async throws -> String {
    let wallpaperId = try requiredDigits(arguments, "wallpaperId")
    let origin = try requiredHttpsOrigin(arguments, "apiOrigin")
    guard let descriptor = arguments["descriptor"] as? [String: Any],
          descriptor["deliveryMode"] as? String == "LIVE_PHOTO_PREVIEW",
          descriptor["purpose"] as? String == "APP_PREVIEW",
          (descriptor["durationSeconds"] as? NSNumber)?.intValue == 1,
          descriptor["wallpaperId"] as? String == wallpaperId,
          let version = descriptor["resourceVersion"] as? [String: Any],
          version["platform"] as? String == "IOS",
          version["resourceType"] as? String == "LIVE_PHOTO",
          let ticket = descriptor["ticket"] as? String,
          ticket.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else {
      throw PluginFailure.invalidDescriptor
    }
    let video = try deliveryFile(
      descriptor, key: "video", path: "/api/v1/preview/live-photo/video",
      mime: "video/quicktime", origin: origin
    )
    let directory = try temporaryDirectory(prefix: "qingjing-live-preview")
    let destination = directory.appendingPathComponent("preview.mov")
    do {
      try await download(video, ticket: ticket, to: destination)
      try Task.checkCancellation()
      previewDirectories[requestId] = directory
      return destination.path
    } catch {
      try? FileManager.default.removeItem(at: directory)
      throw error
    }
  }

  private func releaseLivePhotoPreview(_ arguments: [String: Any]) throws {
    let requestId = try requiredCanonicalUUID(arguments, "requestId")
    previewTasks.removeValue(forKey: requestId)?.cancel()
    if let directory = previewDirectories.removeValue(forKey: requestId) {
      try? FileManager.default.removeItem(at: directory)
    }
  }

  @MainActor
  private func saveMedia(_ arguments: [String: Any]) async throws -> String {
    let wallpaperId = try requiredDigits(arguments, "wallpaperId")
    let resourceType = try requiredString(arguments, "resourceType", maxLength: 32)
    let origin = try requiredHttpsOrigin(arguments, "apiOrigin")
    guard let descriptor = arguments["descriptor"] as? [String: Any],
          descriptor["wallpaperId"] as? String == wallpaperId,
          let ticket = descriptor["ticket"] as? String,
          ticket.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else {
      throw PluginFailure.invalidDescriptor
    }
    let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
    guard authorization == .authorized || authorization == .limited else {
      throw PluginFailure.photoPermissionDenied
    }

    let localIdentifier: String
    if resourceType == "LIVE_PHOTO", descriptor["deliveryMode"] as? String == "LIVE_PHOTO" {
      let photo = try deliveryFile(descriptor, key: "photo", path: "/api/v1/delivery/live-photo/image", mime: "image/heic", origin: origin)
      let video = try deliveryFile(descriptor, key: "video", path: "/api/v1/delivery/live-photo/video", mime: "video/quicktime", origin: origin)
      let directory = try temporaryDirectory()
      defer { try? FileManager.default.removeItem(at: directory) }
      let photoURL = directory.appendingPathComponent("photo.heic")
      let videoURL = directory.appendingPathComponent("paired.mov")
      async let photoDownload: Void = download(photo, ticket: ticket, to: photoURL)
      async let videoDownload: Void = download(video, ticket: ticket, to: videoURL)
      _ = try await (photoDownload, videoDownload)
      try Task.checkCancellation()
      try await validateLivePhoto(photoURL: photoURL, videoURL: videoURL)
      localIdentifier = try await saveLivePhoto(photoURL: photoURL, videoURL: videoURL)
    } else if resourceType == "STATIC_IMAGE", descriptor["deliveryMode"] as? String == "STATIC_IMAGE" {
      guard let image = descriptor["image"] as? [String: Any],
            let mime = image["mimeType"] as? String,
            ["image/jpeg", "image/png", "image/webp"].contains(mime) else {
        throw PluginFailure.invalidDescriptor
      }
      let file = try deliveryFile(descriptor, key: "image", path: "/api/v1/delivery/static-image", mime: mime, origin: origin)
      let directory = try temporaryDirectory()
      defer { try? FileManager.default.removeItem(at: directory) }
      let fileURL = directory.appendingPathComponent("wallpaper.\(fileExtension(mime))")
      try await download(file, ticket: ticket, to: fileURL)
      localIdentifier = try await saveStaticImage(fileURL: fileURL, mime: mime)
    } else {
      throw PluginFailure.invalidDescriptor
    }
    UserDefaults.standard.set(localIdentifier, forKey: Self.savedMediaPrefix + "\(wallpaperId).\(resourceType)")
    return localIdentifier
  }

  private func deliveryFile(
    _ descriptor: [String: Any], key: String, path: String, mime: String, origin: URL
  ) throws -> DeliveryFile {
    guard let value = descriptor[key] as? [String: Any],
          value["url"] as? String == path,
          value["mimeType"] as? String == mime,
          let sha = value["sha256"] as? String,
          sha.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          let sizeNumber = value["sizeBytes"] as? NSNumber,
          sizeNumber.int64Value > 0,
          let url = URL(string: path, relativeTo: origin)?.absoluteURL,
          url.scheme == origin.scheme, url.host == origin.host, url.port == origin.port,
          url.path == path else { throw PluginFailure.invalidDescriptor }
    return DeliveryFile(url: url, sha256: sha, size: sizeNumber.int64Value, mime: mime)
  }

  private func download(_ file: DeliveryFile, ticket: String, to destination: URL) async throws {
    var request = URLRequest(url: file.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 90)
    request.httpMethod = "GET"
    request.setValue("Bearer \(ticket)", forHTTPHeaderField: "Authorization")
    request.setValue(file.mime, forHTTPHeaderField: "Accept")
    let (temporary, response) = try await URLSession.shared.download(
      for: request, delegate: NoRedirectDelegate.shared
    )
    try Task.checkCancellation()
    guard let http = response as? HTTPURLResponse,
          http.statusCode == 200,
          http.mimeType?.lowercased() == file.mime else { throw PluginFailure.downloadInvalid }
    try validateDownloadedFile(temporary, expectedSize: file.size, expectedHash: file.sha256)
    try FileManager.default.moveItem(at: temporary, to: destination)
  }

  private func validateDownloadedFile(_ url: URL, expectedSize: Int64, expectedHash: String) throws {
    let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
    guard (attributes[.size] as? NSNumber)?.int64Value == expectedSize else {
      throw PluginFailure.downloadInvalid
    }
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while true {
      let data = try handle.read(upToCount: 64 * 1024) ?? Data()
      if data.isEmpty { break }
      hasher.update(data: data)
    }
    guard hasher.finalize().hex == expectedHash else { throw PluginFailure.downloadInvalid }
  }

  private func temporaryDirectory(prefix: String = "qingjing-live-photo") throws -> URL {
    let url = FileManager.default.temporaryDirectory
      .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  @MainActor
  private func validateLivePhoto(photoURL: URL, videoURL: URL) async throws {
    let valid = await withCheckedContinuation { continuation in
      var resolved = false
      PHLivePhoto.request(
        withResourceFileURLs: [photoURL, videoURL], placeholderImage: nil,
        targetSize: .zero, contentMode: .aspectFit
      ) { livePhoto, info in
        let degraded = (info[PHLivePhotoInfoIsDegradedKey] as? NSNumber)?.boolValue ?? false
        guard !degraded, !resolved else { return }
        resolved = true
        continuation.resume(returning: livePhoto != nil)
      }
    }
    guard valid else { throw PluginFailure.livePhotoInvalid }
  }

  private func saveLivePhoto(photoURL: URL, videoURL: URL) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      var identifier: String?
      PHPhotoLibrary.shared().performChanges {
        let request = PHAssetCreationRequest.forAsset()
        let photoOptions = PHAssetResourceCreationOptions()
        photoOptions.originalFilename = "Qingjing-LivePhoto.heic"
        let videoOptions = PHAssetResourceCreationOptions()
        videoOptions.originalFilename = "Qingjing-LivePhoto.mov"
        request.addResource(with: .photo, fileURL: photoURL, options: photoOptions)
        request.addResource(with: .pairedVideo, fileURL: videoURL, options: videoOptions)
        identifier = request.placeholderForCreatedAsset?.localIdentifier
      } completionHandler: { success, _ in
        if success, let identifier { continuation.resume(returning: identifier) }
        else { continuation.resume(throwing: PluginFailure.saveFailed) }
      }
    }
  }

  private func saveStaticImage(fileURL: URL, mime: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      var identifier: String?
      PHPhotoLibrary.shared().performChanges {
        let request = PHAssetCreationRequest.forAsset()
        let options = PHAssetResourceCreationOptions()
        options.originalFilename = "Qingjing-Wallpaper.\(self.fileExtension(mime))"
        request.addResource(with: .photo, fileURL: fileURL, options: options)
        identifier = request.placeholderForCreatedAsset?.localIdentifier
      } completionHandler: { success, _ in
        if success, let identifier { continuation.resume(returning: identifier) }
        else { continuation.resume(throwing: PluginFailure.saveFailed) }
      }
    }
  }

  private func fileExtension(_ mime: String) -> String {
    mime == "image/png" ? "png" : mime == "image/webp" ? "webp" : "jpg"
  }

  private func savedMediaKey(_ arguments: [String: Any]) throws -> String {
    let id = try requiredDigits(arguments, "wallpaperId")
    let type = try requiredString(arguments, "resourceType", maxLength: 32)
    guard ["LIVE_PHOTO", "STATIC_IMAGE"].contains(type) else { throw PluginFailure.invalidArguments }
    return Self.savedMediaPrefix + "\(id).\(type)"
  }

  private func validatePendingRedemption(_ value: String) throws {
    guard value.utf8.count <= 256,
          let data = value.data(using: .utf8),
          let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          object.count == 3,
          let key = object["key"] as? String,
          UUID(uuidString: key)?.uuidString.lowercased() == key,
          let wallpaperId = object["wallpaperId"] as? String,
          wallpaperId.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil,
          let hash = object["bodyHash"] as? String,
          hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
      throw PluginFailure.invalidArguments
    }
  }

  private func readKeychain(account: String) -> String? {
    var item: CFTypeRef?
    let status = SecItemCopyMatching([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: Self.keychainService,
      kSecAttrAccount: account,
      kSecReturnData: true,
      kSecMatchLimit: kSecMatchLimitOne,
    ] as CFDictionary, &item)
    guard status == errSecSuccess, let data = item as? Data else { return nil }
    return String(data: data, encoding: .utf8)
  }

  private func writeKeychain(account: String, value: String) throws {
    let key: [CFString: Any] = [
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: Self.keychainService,
      kSecAttrAccount: account,
    ]
    let attributes: [CFString: Any] = [
      kSecValueData: Data(value.utf8),
      kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    let status = SecItemUpdate(key as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      var add = key
      attributes.forEach { add[$0.key] = $0.value }
      guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else {
        throw PluginFailure.identityUnavailable
      }
    } else if status != errSecSuccess {
      throw PluginFailure.identityUnavailable
    }
  }

  private func deleteKeychain(account: String) {
    SecItemDelete([
      kSecClass: kSecClassGenericPassword,
      kSecAttrService: Self.keychainService,
      kSecAttrAccount: account,
    ] as CFDictionary)
  }

  private func dictionary(_ value: Any?) throws -> [String: Any] {
    guard let value = value as? [String: Any] else { throw PluginFailure.invalidArguments }
    return value
  }

  private func requiredString(_ arguments: [String: Any], _ key: String, maxLength: Int) throws -> String {
    guard let value = arguments[key] as? String,
          !value.isEmpty, value.utf8.count <= maxLength else { throw PluginFailure.invalidArguments }
    return value
  }

  private func requiredCanonicalUUID(_ arguments: [String: Any], _ key: String) throws -> String {
    let value = try requiredString(arguments, key, maxLength: 36)
    guard UUID(uuidString: value)?.uuidString.lowercased() == value else { throw PluginFailure.invalidArguments }
    return value
  }

  private func requiredDigits(_ arguments: [String: Any], _ key: String) throws -> String {
    let value = try requiredString(arguments, key, maxLength: 19)
    guard value.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil else {
      throw PluginFailure.invalidArguments
    }
    return value
  }

  private func requiredHttpsOrigin(_ arguments: [String: Any], _ key: String) throws -> URL {
    let value = try requiredString(arguments, key, maxLength: 512)
    guard let url = URL(string: value), url.scheme == "https", url.host != nil,
          url.user == nil, url.password == nil, url.path.isEmpty || url.path == "/",
          url.query == nil, url.fragment == nil else { throw PluginFailure.invalidArguments }
    return url
  }
}

private struct DeliveryFile {
  let url: URL
  let sha256: String
  let size: Int64
  let mime: String
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  static let shared = NoRedirectDelegate()

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    completionHandler(nil)
  }
}

private enum PluginFailure: Error {
  case invalidArguments, bundleNotAllowed, identityUnavailable, invalidDescriptor
  case downloadInvalid, photoPermissionDenied, livePhotoInvalid, saveFailed, cancelled

  var flutterError: FlutterError {
    switch self {
    case .invalidArguments:
      FlutterError(code: "INVALID_ARGUMENTS", message: "请求参数无效", details: nil)
    case .bundleNotAllowed:
      FlutterError(code: "BUNDLE_NOT_ALLOWED", message: "当前安装包标识未开放", details: nil)
    case .identityUnavailable:
      FlutterError(code: "IDENTITY_UNAVAILABLE", message: "安装凭据暂时不可用，请重试或联系客服", details: nil)
    case .invalidDescriptor:
      FlutterError(code: "DELIVERY_INVALID", message: "壁纸下载信息无效，请重新下载", details: nil)
    case .downloadInvalid:
      FlutterError(code: "DOWNLOAD_INVALID", message: "壁纸文件下载或校验失败，请重试", details: nil)
    case .photoPermissionDenied:
      FlutterError(code: "PHOTO_PERMISSION_DENIED", message: "请允许添加照片后再下载壁纸", details: nil)
    case .livePhotoInvalid:
      FlutterError(code: "LIVE_PHOTO_INVALID", message: "动态壁纸文件不兼容，请联系客服", details: nil)
    case .saveFailed:
      FlutterError(code: "SAVE_FAILED", message: "壁纸保存失败，请重试", details: nil)
    case .cancelled:
      FlutterError(code: "DOWNLOAD_CANCELLED", message: "下载已取消", details: nil)
    }
  }
}

private extension Data {
  var base64Url: String {
    base64EncodedString()
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }
}

private extension Digest {
  var hex: String { map { String(format: "%02x", $0) }.joined() }
}
