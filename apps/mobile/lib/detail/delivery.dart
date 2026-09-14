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
  WallpaperEffect.video => '视频动态',
  WallpaperEffect.parallax => '4D 姿态视差',
};
String targetLabel(WallpaperTarget target) => switch (target) {
  WallpaperTarget.home => '桌面',
  WallpaperTarget.lock => '锁屏',
  WallpaperTarget.both => '桌面和锁屏',
};

/// Catalog variants are available content, not proof of native system support.
List<WallpaperEffect> deliveryEffects(
  Wallpaper wallpaper,
  ClientPlatform platform,
) {
  final platformName = switch (platform) {
    ClientPlatform.android => 'ANDROID',
    ClientPlatform.ios => 'IOS',
    ClientPlatform.harmonyos => 'HARMONYOS',
    _ => '',
  };
  if (platformName.isEmpty) return [];
  return wallpaper.capabilities
      .where(
        (cap) =>
            cap['platform'] == platformName || cap['platform'] == 'UNIVERSAL',
      )
      .map((cap) => effectForResource(cap['resourceType'] as String? ?? ''))
      .whereType<WallpaperEffect>()
      .toSet()
      .toList();
}
