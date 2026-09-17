import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';

WallpaperEffect? effectForResource(String type) => switch (type) {
  'STATIC_IMAGE' => WallpaperEffect.staticImage,
  'VIDEO' => WallpaperEffect.video,
  'LAYER_PARALLAX' => WallpaperEffect.parallax,
  _ => null,
};

String effectLabel(WallpaperEffect effect) => switch (effect) {
  WallpaperEffect.staticImage => '静态壁纸',
  WallpaperEffect.video => '动态壁纸',
  WallpaperEffect.parallax => '4D 壁纸',
};

String targetLabel(WallpaperTarget target) => switch (target) {
  WallpaperTarget.home => '桌面',
  WallpaperTarget.lock => '锁屏',
  WallpaperTarget.both => '桌面和锁屏',
};

class WallpaperDeliveryOption {
  const WallpaperDeliveryOption(this.capability, this.effect);
  final AvailableCapability capability;
  final WallpaperEffect effect;
  String get deliveryPlatform => capability.deliveryPlatform;
  String get resourceType => capability.resourceType;
  Set<String> get placements => capability.placements;
}

/// Rechecks the API-provided device intersection against the latest local probe.
List<WallpaperDeliveryOption> deliveryOptions(
  Wallpaper wallpaper,
  WallpaperCapabilities local,
) {
  final platformName = switch (local.platform) {
    ClientPlatform.android => 'ANDROID',
    ClientPlatform.ios => 'IOS',
    ClientPlatform.harmonyos => 'HARMONYOS',
    _ => '',
  };
  if (platformName.isEmpty) return [];
  final options = <WallpaperDeliveryOption>[];
  for (final capability in wallpaper.availableCapabilities) {
    if (capability.deliveryPlatform != platformName &&
        capability.deliveryPlatform != 'UNIVERSAL') {
      continue;
    }
    final effect = effectForResource(capability.resourceType);
    if (effect == null || !local.previewEffects.contains(effect)) continue;
    final placements = capability.placements.where((placement) {
      final target = switch (placement) {
        'HOME' => WallpaperTarget.home,
        'LOCK' => WallpaperTarget.lock,
        _ => null,
      };
      return target != null && _canApply(local, effect, target);
    }).toSet();
    if (placements.isEmpty) continue;
    options.add(
      WallpaperDeliveryOption(
        AvailableCapability(
          deliveryPlatform: capability.deliveryPlatform,
          resourceType: capability.resourceType,
          placements: placements,
        ),
        effect,
      ),
    );
  }
  const priority = {
    WallpaperEffect.parallax: 0,
    WallpaperEffect.video: 1,
    WallpaperEffect.staticImage: 2,
  };
  options.sort(
    (left, right) => priority[left.effect]!.compareTo(priority[right.effect]!),
  );
  return options;
}

List<WallpaperEffect> deliveryEffects(
  Wallpaper wallpaper,
  ClientPlatform platform, {
  String? osVersion,
}) {
  final targets = {
    for (final effect in WallpaperEffect.values)
      effect: {
        WallpaperTarget.home,
        WallpaperTarget.lock,
        WallpaperTarget.both,
      },
  };
  return deliveryOptions(
    wallpaper,
    WallpaperCapabilities(
      platform: platform,
      osVersion: osVersion,
      previewEffects: WallpaperEffect.values.toSet(),
      targets: targets,
    ),
  ).map((item) => item.effect).toList(growable: false);
}

WallpaperCapabilities capabilitiesForOption(
  WallpaperCapabilities local,
  WallpaperDeliveryOption option,
) {
  final targets = <WallpaperTarget>{};
  if (option.placements.contains('HOME') &&
      _canApply(local, option.effect, WallpaperTarget.home)) {
    targets.add(WallpaperTarget.home);
  }
  if (option.placements.contains('LOCK') &&
      _canApply(local, option.effect, WallpaperTarget.lock)) {
    targets.add(WallpaperTarget.lock);
  }
  if (targets.contains(WallpaperTarget.home) &&
      targets.contains(WallpaperTarget.lock) &&
      local.canApply(option.effect, WallpaperTarget.both)) {
    targets.add(WallpaperTarget.both);
  }
  return WallpaperCapabilities(
    platform: local.platform,
    previewEffects: {option.effect},
    targets: {option.effect: targets},
    osVersion: local.osVersion,
    sdkInt: local.sdkInt,
    manufacturer: local.manufacturer,
    model: local.model,
    hostOsFamily: local.hostOsFamily,
    executionMode: local.executionMode,
    parallaxSensorAvailable: local.parallaxSensorAvailable,
    systemChoosesLiveTarget: local.systemChoosesLiveTarget,
    setupMessage: local.setupMessage,
  );
}

bool _canApply(
  WallpaperCapabilities capabilities,
  WallpaperEffect effect,
  WallpaperTarget target,
) =>
    capabilities.canApply(effect, target) ||
    capabilities.canApply(effect, WallpaperTarget.both);

bool supportsMinimumOs(String? current, String? minimum) {
  if (minimum == null || minimum.isEmpty) return true;
  List<int>? parts(String? value) =>
      value != null && RegExp(r'^\d+(\.\d+){0,3}$').hasMatch(value)
      ? value.split('.').map(int.parse).toList()
      : null;
  final actual = parts(current), required = parts(minimum);
  if (actual == null || required == null) return false;
  for (var i = 0; i < 4; i++) {
    final a = i < actual.length ? actual[i] : 0;
    final b = i < required.length ? required[i] : 0;
    if (a != b) return a > b;
  }
  return true;
}
