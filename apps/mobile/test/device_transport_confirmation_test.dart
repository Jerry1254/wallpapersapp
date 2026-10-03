import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

void main() {
  test(
    'Apple confirmation can finish after the ordinary HTTP deadline',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      final responses = <Future<void>>[];
      server.listen((request) {
        responses.add(() async {
          await Future<void>.delayed(const Duration(seconds: 16));
          try {
            request.response.headers.contentType = ContentType.json;
            request.response.write('{"confirmed":true}');
            await request.response.close();
          } catch (_) {
            // The ordinary request has already closed its connection on timeout.
          }
        }());
      });
      final transport = HttpDeviceTransport(
        Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      );
      final ordinary = expectLater(
        transport.request('/device/ios/products'),
        throwsA(
          isA<DeviceApiError>().having(
            (error) => error.code,
            'code',
            'NETWORK_TIMEOUT',
          ),
        ),
      );
      final confirmation = transport.request(
        '/device/ios/acquisition/purchases',
        method: 'POST',
      );
      final restore = transport.request(
        '/device/ios/acquisition/credit-restores',
        method: 'POST',
      );
      await ordinary;
      expect(await confirmation, {'confirmed': true});
      expect(await restore, {'confirmed': true});
      await Future.wait(responses);
    },
  );
}
