import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'package:qingjing_wallpaper/detail/delivery.dart';
import 'detail_test.dart' show wallpaper;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('qingjing/wallpaper_android');
  const playback = AndroidWallpaperPlayback();
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('系统选择的视频目标不推导独立锁屏能力', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => {
            'osVersion': '15',
            'previewEffects': ['STATIC_IMAGE', 'VIDEO'],
            'targets': {
              'STATIC_IMAGE': ['home', 'lock', 'both'],
              'VIDEO': ['home'],
            },
            'systemChoosesLiveTarget': true,
          },
        );
    final capabilities = await playback.capabilities();
    expect(
      capabilities.canApply(WallpaperEffect.staticImage, WallpaperTarget.lock),
      true,
    );
    expect(
      capabilities.canApply(WallpaperEffect.video, WallpaperTarget.lock),
      false,
    );
    expect(capabilities.systemChoosesLiveTarget, true);
    expect(capabilities.osVersion, '15');
  });
  test('已打开系统设置、取消和未知结果均不能冒充确认成功', () async {
    for (final status in ['opened', 'cancelled', 'unknown', 'completed']) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'applyWallpaper');
            expect(call.arguments, {
              'installedId': 'opaque-id',
              'resourceType': 'VIDEO',
              'target': 'home',
            });
            return {'status': status, 'message': '系统结果'};
          });
      final result = await playback.apply(
        'opaque-id',
        WallpaperEffect.video,
        WallpaperTarget.home,
      );
      expect(
        result.status,
        status == 'completed'
            ? OperationStatus.completed
            : status == 'cancelled'
            ? OperationStatus.cancelled
            : OperationStatus.unknown,
      );
    }
  });
  test('兑换前按操作系统版本及尚不可证明的附加要求过滤变体', () {
    expect(supportsMinimumOs('15', '15.0.0'), true);
    expect(supportsMinimumOs('14.9', '15'), false);
    expect(supportsMinimumOs(null, '15'), false);
    expect(supportsMinimumOs('15-beta', '15'), false);
    final item = wallpaper([
      {'platform': 'ANDROID', 'resourceType': 'VIDEO', 'minOsVersion': '16'},
      {
        'platform': 'ANDROID',
        'resourceType': 'STATIC_IMAGE',
        'minOsVersion': '12',
      },
    ]);
    expect(deliveryEffects(item, ClientPlatform.android, osVersion: '15'), [
      WallpaperEffect.staticImage,
    ]);
  });
}
