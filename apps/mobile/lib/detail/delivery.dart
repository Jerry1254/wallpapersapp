import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';

WallpaperEffect? effectForResource(String type) => switch (type) {
  'STATIC_IMAGE' => WallpaperEffect.staticImage,
  'VIDEO' || 'LIVE_PHOTO' || 'THEME_PACKAGE' => WallpaperEffect.video,
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
  String get key => '$deliveryPlatform/$resourceType';
  String get label => capability.label;

  bool get canApplyOnAndroid =>
      (deliveryPlatform == 'ANDROID' &&
          {'LAYER_PARALLAX', 'VIDEO'}.contains(resourceType)) ||
      (deliveryPlatform == 'UNIVERSAL' && resourceType == 'STATIC_IMAGE');
}

/// The detail page reflects resources published by the backend. Device
/// probing must never remove a resource tab before the user tries it.
List<WallpaperDeliveryOption> deliveryOptions(Wallpaper wallpaper) {
  final options = wallpaper.availableCapabilities
      .map((capability) {
        final effect = effectForResource(capability.resourceType);
        return effect == null
            ? null
            : WallpaperDeliveryOption(capability, effect);
      })
      .whereType<WallpaperDeliveryOption>()
      .toList(growable: false);
  const priority = {
    'ANDROID/LAYER_PARALLAX': 0,
    'ANDROID/VIDEO': 1,
    'IOS/LIVE_PHOTO': 2,
    'HARMONYOS/THEME_PACKAGE': 3,
    'UNIVERSAL/STATIC_IMAGE': 4,
  };
  options.sort(
    (left, right) =>
        (priority[left.key] ?? 99).compareTo(priority[right.key] ?? 99),
  );
  return options;
}

List<WallpaperEffect> deliveryEffects(
  Wallpaper wallpaper,
  ClientPlatform _, {
  String? osVersion,
}) => deliveryOptions(wallpaper).map((item) => item.effect).toSet().toList();

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
