import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/updates/app_updates.dart';

void main() {
  test('业务请求携带真实版本和兼容性信息，426通知根门禁', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    server.listen((request) async {
      expect(request.headers.value('X-App-Version-Name'), '1.0.1');
      expect(request.headers.value('X-App-Version-Code'), '10023');
      expect(request.headers.value('X-App-Android-Sdk'), '36');
      expect(request.headers.value('X-App-ABI'), 'arm64-v8a');
      request.response.statusCode = 426;
      request.response.write(
        jsonEncode({
          'error': {'code': 'APP_UPDATE_REQUIRED'},
        }),
      );
      await request.response.close();
    });
    var notifications = 0;
    final transport = HttpDeviceTransport(
      Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      versionHeaders: () async => const InstalledAppVersion(
        'android',
        '1.0.1',
        10023,
        androidSdk: 36,
        abi: 'arm64-v8a',
      ).headers,
      onUpdateRequired: () => notifications++,
    );
    await expectLater(
      transport.request('/device/redemptions', method: 'POST'),
      throwsA(
        isA<DeviceApiError>().having(
          (error) => error.code,
          'code',
          'APP_UPDATE_REQUIRED',
        ),
      ),
    );
    expect(notifications, 1);
  });

  test('匿名检查传真实包名，读取外层data并拒绝残缺或平台错配决策', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    var mode = 0;
    server.listen((request) async {
      expect(request.headers.value('Authorization'), isNull);
      expect(request.uri.queryParameters['packageName'], 'com.qingjing.bizhi');
      final body = mode == 0
          ? {
              'platform': 'ios',
              'mandatory': false,
              'updateAvailable': false,
              'minimumVersion': null,
              'latestVersion': null,
            }
          : mode == 1
          ? <String, Object?>{}
          : {
              'platform': 'android',
              'mandatory': false,
              'updateAvailable': false,
            };
      request.response.write(jsonEncode({'data': body}));
      await request.response.close();
    });
    final api = HttpAppUpdateApi(
      Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
    );
    const version = InstalledAppVersion(
      'ios',
      '1.0.1',
      10023,
      packageName: 'com.qingjing.bizhi',
    );
    expect((await api.check(version)).mandatory, false);
    mode = 1;
    await expectLater(api.check(version), throwsFormatException);
    mode = 2;
    await expectLater(api.check(version), throwsFormatException);
  });
}
