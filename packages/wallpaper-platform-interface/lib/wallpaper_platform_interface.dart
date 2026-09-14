enum ClientPlatform { android, harmonyos, ios, unknown }

enum WallpaperEffect { staticImage, video, parallax }

enum WallpaperTarget { home, lock, both }

enum OperationStatus { completed, cancelled, unknown, unsupported }

class PlatformResult<T> {
  const PlatformResult(this.status, {this.value, this.message});
  const PlatformResult.unsupported([this.message = '当前平台尚未实现此能力'])
    : status = OperationStatus.unsupported,
      value = null;
  final OperationStatus status;
  final T? value;
  final String? message;
}

class WallpaperCapabilities {
  const WallpaperCapabilities({
    required this.platform,
    this.previewEffects = const {},
    this.targets = const {},
    this.osVersion,
    this.systemChoosesLiveTarget = false,
    this.setupMessage,
  });
  final ClientPlatform platform;
  final Set<WallpaperEffect> previewEffects;
  final Map<WallpaperEffect, Set<WallpaperTarget>> targets;
  final String? osVersion;
  final bool systemChoosesLiveTarget;
  final String? setupMessage;
  bool canApply(WallpaperEffect effect, WallpaperTarget target) =>
      targets[effect]?.contains(target) ?? false;
}

abstract interface class DeviceIdentity {
  Future<PlatformResult<String>> publicKey();
  Future<PlatformResult<String>> sign(String payload);
}

abstract interface class Preview {
  Future<PlatformResult<void>> open(String resourceId, WallpaperEffect effect);
}

abstract interface class PackageInstaller {
  Future<PlatformResult<String>> install(String downloadId);
}

abstract interface class WallpaperApply {
  Future<PlatformResult<void>> apply(
    String installedId,
    WallpaperEffect effect,
    WallpaperTarget target,
  );
}

abstract interface class PurchaseProvider {
  Future<PlatformResult<void>> purchase(String productId);
  Future<PlatformResult<void>> restore();
}

/// Explicit placeholders. Never report an unimplemented operation as success.
class UnsupportedPlatform
    implements
        DeviceIdentity,
        Preview,
        PackageInstaller,
        WallpaperApply,
        PurchaseProvider {
  const UnsupportedPlatform(this.platform);
  final ClientPlatform platform;
  WallpaperCapabilities get capabilities =>
      WallpaperCapabilities(platform: platform);
  @override
  Future<PlatformResult<String>> publicKey() async =>
      const PlatformResult.unsupported();
  @override
  Future<PlatformResult<String>> sign(String payload) async =>
      const PlatformResult.unsupported();
  @override
  Future<PlatformResult<void>> open(
    String resourceId,
    WallpaperEffect effect,
  ) async => const PlatformResult.unsupported();
  @override
  Future<PlatformResult<String>> install(String downloadId) async =>
      const PlatformResult.unsupported();
  @override
  Future<PlatformResult<void>> apply(
    String installedId,
    WallpaperEffect effect,
    WallpaperTarget target,
  ) async => const PlatformResult.unsupported();
  @override
  Future<PlatformResult<void>> purchase(String productId) async =>
      const PlatformResult.unsupported();
  @override
  Future<PlatformResult<void>> restore() async =>
      const PlatformResult.unsupported();
}
