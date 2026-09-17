import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_capabilities.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';

class NoCapabilityTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw UnimplementedError();
}

class CapabilitySessions extends DeviceSessionManager {
  CapabilitySessions() : super(NoCapabilityTransport());
  final reports = <Map<String, dynamic>>[];

  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    expect(path, '/device/me/capabilities');
    expect(method, 'PUT');
    expect(signed, true);
    reports.add(jsonDecode(body!) as Map<String, dynamic>);
    final capabilities = (reports.last['capabilities'] as List)
        .map(
          (item) => Map<String, dynamic>.from(item as Map)..remove('evidence'),
        )
        .toList();
    return {
      'version': reports.length,
      'profileHash': 'a' * 64,
      'effectiveCapabilities': capabilities,
      'recheckRequired': false,
    };
  }
}

class FixedProbe implements DeviceCapabilityProbe {
  const FixedProbe(this.value);
  final WallpaperCapabilities value;
  @override
  Future<WallpaperCapabilities> probe() async => value;
}

void main() {
  const detected = WallpaperCapabilities(
    platform: ClientPlatform.android,
    osVersion: '15',
    sdkInt: 35,
    manufacturer: 'Xiaomi',
    model: 'Redmi Test',
    hostOsFamily: 'ANDROID',
    executionMode: 'NATIVE',
    parallaxSensorAvailable: true,
    previewEffects: {
      WallpaperEffect.parallax,
      WallpaperEffect.video,
      WallpaperEffect.staticImage,
    },
    targets: {
      WallpaperEffect.parallax: {WallpaperTarget.home},
      WallpaperEffect.video: {WallpaperTarget.home},
      WallpaperEffect.staticImage: {
        WallpaperTarget.home,
        WallpaperTarget.lock,
        WallpaperTarget.both,
      },
    },
  );

  test('按 API-015 签名上报原生探测结果并只使用服务端有效能力', () async {
    final sessions = CapabilitySessions();
    final manager = DeviceCapabilityManager(
      sessions,
      probe: const FixedProbe(detected),
    );
    final profile = await manager.ensureCurrent();
    expect(sessions.reports, hasLength(1));
    final report = sessions.reports.single;
    expect(report['hostOsFamily'], 'ANDROID');
    expect(report['sdkInt'], 35);
    expect(report['manufacturer'], 'Xiaomi');
    expect(report['probeVersion'], 1);
    expect(report['featureFlags'], ['PARALLAX_SENSOR']);
    expect(report['capabilities'], [
      {
        'deliveryPlatform': 'ANDROID',
        'resourceType': 'LAYER_PARALLAX',
        'runtimeOsVersion': '35',
        'placements': ['HOME'],
        'evidence': 'SYSTEM_PROBE',
      },
      {
        'deliveryPlatform': 'ANDROID',
        'resourceType': 'VIDEO',
        'runtimeOsVersion': '35',
        'placements': ['HOME'],
        'evidence': 'SYSTEM_PROBE',
      },
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'runtimeOsVersion': '35',
        'placements': ['HOME', 'LOCK'],
        'evidence': 'SYSTEM_PROBE',
      },
    ]);
    expect(profile.effectiveCapabilities, hasLength(3));
    await manager.ensureCurrent();
    expect(sessions.reports, hasLength(1));
  });

  test('真实设置成功升级证据，系统明确不支持时删除对应能力', () async {
    final sessions = CapabilitySessions();
    final manager = DeviceCapabilityManager(
      sessions,
      probe: const FixedProbe(detected),
    );
    await manager.recordSuccessfulSet('ANDROID', 'VIDEO');
    expect(
      (sessions.reports.last['capabilities'] as List).singleWhere(
        (item) => item['resourceType'] == 'VIDEO',
      )['evidence'],
      'SUCCESSFUL_SET',
    );
    await manager.recordUnsupported('ANDROID', 'VIDEO');
    expect(
      (sessions.reports.last['capabilities'] as List).any(
        (item) => item['resourceType'] == 'VIDEO',
      ),
      false,
    );
  });

  test('目录返回 428 时强制重新上报并只重试原请求一次', () async {
    final sessions = CapabilitySessions();
    final manager = DeviceCapabilityManager(
      sessions,
      probe: const FixedProbe(detected),
    );
    var calls = 0;
    final result = await manager.withProfile(() async {
      calls++;
      if (calls == 1) {
        throw const DeviceApiError(428, 'DEVICE_CAPABILITY_PROFILE_REQUIRED');
      }
      return 'ok';
    });
    expect(result, 'ok');
    expect(calls, 2);
    expect(sessions.reports, hasLength(2));
  });
}
