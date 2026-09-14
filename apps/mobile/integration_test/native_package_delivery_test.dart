import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  const origin = String.fromEnvironment(
    'NATIVE_FIXTURE_ORIGIN',
    defaultValue: 'http://127.0.0.1:8082',
  );
  final installer = AndroidPackageInstaller();
  const identity = AndroidDeviceIdentity();
  Future<Map<String, dynamic>> fixture(
    String type, {
    String mode = 'valid',
  }) async {
    await identity.installation();
    final key = await identity.encryptionPublicKey();
    final client = HttpClient();
    try {
      final request = await client.postUrl(Uri.parse('$origin/fixture'));
      request.headers.contentType = ContentType.json;
      final body = utf8.encode(
        jsonEncode({
          'resourceType': type,
          'mode': mode,
          'publicKeyPem': key['publicKeyPem'],
        }),
      );
      request.contentLength = body.length;
      request.add(body);
      final response = await request.close();
      expect(response.statusCode, 200);
      return jsonDecode(await utf8.decoder.bind(response).join())
          as Map<String, dynamic>;
    } finally {
      client.close(force: true);
    }
  }

  Future<PlatformResult<String>> install(
    String type, {
    String mode = 'valid',
  }) async {
    final descriptor = await fixture(type, mode: mode);
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
    return installer.install(id);
  }

  testWidgets(
    '真实 Android Keystore 包装解密、三类媒体安装、失败保留和取消',
    (tester) async {
      final originalIdentity = await identity.installation();
      for (final type in ['STATIC_IMAGE', 'VIDEO', 'LAYER_PARALLAX']) {
        final result = await install(type);
        expect(
          result.status,
          OperationStatus.completed,
          reason: result.message,
        );
        expect(result.value, isNotNull);
        expect(await installer.current('101', type), result.value);
      }
      final original = await installer.current('101', 'STATIC_IMAGE');
      for (final mode in ['wrong-version', 'tamper']) {
        final result = await install('STATIC_IMAGE', mode: mode);
        expect(result.status, OperationStatus.unknown);
        expect(await installer.current('101', 'STATIC_IMAGE'), original);
      }
      final descriptor = await fixture('STATIC_IMAGE', mode: 'slow');
      final id = requestUuid();
      final downloading = Completer<void>();
      final subscription = installer.progress.listen((event) {
        if (event.requestId == id &&
            event.status == 'downloading' &&
            !downloading.isCompleted) {
          downloading.complete();
        }
      });
      installer.prepare(
        SecurePackageDownload(
          requestId: id,
          wallpaperId: '101',
          resourceType: 'STATIC_IMAGE',
          url: Uri.parse('$origin/api/v1/delivery/files'),
          descriptor: descriptor,
        ),
      );
      final pending = installer.install(id);
      await downloading.future.timeout(const Duration(seconds: 30));
      await installer.cancel(id);
      final cancelled = await pending;
      expect(
        cancelled.status,
        OperationStatus.cancelled,
        reason: cancelled.message,
      );
      await subscription.cancel();
      expect(await installer.current('101', 'STATIC_IMAGE'), original);
      final preserved = await identity.installation();
      expect(preserved.fingerprint, originalIdentity.fingerprint);
      expect(preserved.credentialKeyId, originalIdentity.credentialKeyId);
    },
    timeout: const Timeout(Duration(minutes: 3)),
  );
}
