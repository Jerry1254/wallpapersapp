import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

/// Host adb drives native Activity/system controls after each A07_HOST marker.
/// Use only a disposable emulator: this test changes its wallpaper and Flutter uninstalls on teardown.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  const origin = String.fromEnvironment(
    'NATIVE_FIXTURE_ORIGIN',
    defaultValue: 'http://127.0.0.1:8082',
  );
  const channel = MethodChannel('qingjing/wallpaper_android');
  const playback = AndroidWallpaperPlayback();
  final installer = AndroidPackageInstaller();
  Future<String> install(String type) async {
    const identity = AndroidDeviceIdentity();
    await identity.installation();
    final key = await identity.encryptionPublicKey();
    final client = HttpClient();
    try {
      final request = await client.postUrl(Uri.parse('$origin/fixture'));
      final bytes = utf8.encode(
        jsonEncode({
          'resourceType': type,
          'mode': 'valid',
          'publicKeyPem': key['publicKeyPem'],
        }),
      );
      request.headers.contentType = ContentType.json;
      request.contentLength = bytes.length;
      request.add(bytes);
      final response = await request.close();
      expect(response.statusCode, 200);
      final descriptor =
          jsonDecode(await utf8.decoder.bind(response).join())
              as Map<String, dynamic>;
      final id = requestUuid();
      installer.prepare(
        SecurePackageDownload(
          requestId: id,
          wallpaperId: '101',
          resourceType: type,
          url: Uri.parse('$origin/api/v1/delivery/files'),
          descriptor: descriptor,
        ),
      );
      final result = await installer.install(id);
      expect(result.status, OperationStatus.completed, reason: result.message);
      return result.value!;
    } finally {
      client.close(force: true);
    }
  }

  Future<Map<String, dynamic>> state() async =>
      await channel
          .invokeMapMethod<String, dynamic>('debugPlaybackState')
          .timeout(const Duration(seconds: 10)) ??
      {};
  testWidgets('模拟器静态三目标、视频取消/确认、独立预览与不可见暂停', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: Center(child: Text('WP-A07 原生互通测试'))),
      ),
    );
    await tester.runAsync(() async {
      final staticId = await install('STATIC_IMAGE'),
          videoId = await install('VIDEO');
      final capabilities = await playback.capabilities();
      expect(capabilities.previewEffects.contains(WallpaperEffect.video), true);
      expect(capabilities.systemChoosesLiveTarget, true);
      expect(
        (await playback.open('../escape', WallpaperEffect.staticImage)).status,
        OperationStatus.unknown,
      );
      debugPrint('A07_HOST STATIC_PREVIEW');
      final preview = await playback.open(
        staticId,
        WallpaperEffect.staticImage,
      );
      expect(
        preview.status,
        OperationStatus.completed,
        reason: preview.message,
      );
      for (final target in WallpaperTarget.values) {
        final before = await state();
        final result = await playback.apply(
          staticId,
          WallpaperEffect.staticImage,
          target,
        );
        final after = await state();
        debugPrint(
          'A07_STATIC ${target.name} status=${result.status.name} before=${before['homeId']}/${before['lockId']} after=${after['homeId']}/${after['lockId']}',
        );
        expect(
          result.status,
          OperationStatus.completed,
          reason: result.message,
        );
        if (target != WallpaperTarget.lock) {
          expect(after['homeId'], isNot(before['homeId']));
        }
        if (target != WallpaperTarget.home) {
          expect(after['lockId'], isNot(before['lockId']));
        }
      }
      debugPrint('A07_HOST VIDEO_PICKER_CANCEL');
      final cancelled = await playback.apply(
        videoId,
        WallpaperEffect.video,
        WallpaperTarget.home,
      );
      expect(
        cancelled.status,
        OperationStatus.cancelled,
        reason: cancelled.message,
      );
      expect((await state())['liveHome'], false);
      debugPrint('A07_HOST VIDEO_PICKER_ACCEPT');
      final accepted = await playback.apply(
        videoId,
        WallpaperEffect.video,
        WallpaperTarget.home,
      );
      expect(
        accepted.status,
        OperationStatus.completed,
        reason: accepted.message,
      );
      debugPrint('A07_LIVE ${accepted.message}');
      expect((await state())['liveHome'], true);
      debugPrint('A07_HOST HOME_VISIBLE');
      await Future<void>.delayed(const Duration(seconds: 5));
      debugPrint('A07_HOME_TIMER_FIRED');
      final home = await state();
      debugPrint('A07_ENGINES_HOME ${jsonEncode(home['engines'])}');
      expect(
        (home['engines'] as List).any(
          (value) =>
              value['preview'] == false &&
              value['visible'] == true &&
              value['playing'] == true &&
              value['installedId'] == videoId,
        ),
        true,
      );
      debugPrint('A07_HOST REOPEN_APP');
      await Future<void>.delayed(const Duration(seconds: 5));
      final foreground = await state();
      debugPrint('A07_ENGINES_APP ${jsonEncode(foreground['engines'])}');
      expect(
        (foreground['engines'] as List).any(
          (value) => value['preview'] == false && value['playing'] == true,
        ),
        false,
      );
      debugPrint('A07_HOST APP_VIDEO_PREVIEW');
      final videoPreview = await playback.open(videoId, WallpaperEffect.video);
      expect(
        videoPreview.status,
        OperationStatus.completed,
        reason: videoPreview.message,
      );
      await installer.clearUnused();
      expect(await installer.current('101', 'VIDEO'), videoId);
      debugPrint('A07_PLAYBACK_ALL_PASSED');
    });
  }, timeout: const Timeout(Duration(minutes: 8)));
}
