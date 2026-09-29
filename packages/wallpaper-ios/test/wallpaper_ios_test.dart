import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('qingjing/wallpaper_ios');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'identity' => <String, dynamic>{
              'publicKeyPem': 'pem',
              'fingerprint': 'fingerprint',
              'scope': 'com.qingjing.bizhi',
              'credentialKeyId': 'credential',
            },
            'sign' => 'signature',
            'readPendingRedemption' => '{"pending":true}',
            'savedMedia' => 'photos-id',
            'saveMedia' => 'new-photos-id',
            'prepareLivePhotoPreview' => '/tmp/preview.mov',
            _ => null,
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test(
    'installation identity and signatures use the fixed native channel',
    () async {
      final identity = await const IosDeviceIdentity().installation();
      expect(identity.scope, 'com.qingjing.bizhi');
      expect(identity.credentialKeyId, 'credential');
      expect(
        await const IosDeviceIdentity().signPayload('payload'),
        'signature',
      );
      expect(calls.map((call) => call.method), ['identity', 'sign']);
      expect(calls.last.arguments, {'payload': 'payload'});
    },
  );

  test(
    'pending redemption and media operations preserve exact arguments',
    () async {
      const pending = IosPendingRedemptionStore();
      expect(await pending.read(), '{"pending":true}');
      await pending.write('value');
      expect(calls.last.arguments, {'value': 'value'});

      const media = IosMediaInstaller();
      expect(await media.current('7', 'LIVE_PHOTO'), 'photos-id');
      expect(
        await media.save(
          requestId: 'b1bb19db-bb94-4e7a-b128-7004e41fcce7',
          wallpaperId: '7',
          resourceType: 'LIVE_PHOTO',
          apiOrigin: Uri.parse('https://wallpaper.biguo66.top/api/v1'),
          descriptor: const {'deliveryMode': 'LIVE_PHOTO'},
        ),
        'new-photos-id',
      );
      expect(calls.last.arguments, {
        'requestId': 'b1bb19db-bb94-4e7a-b128-7004e41fcce7',
        'wallpaperId': '7',
        'resourceType': 'LIVE_PHOTO',
        'apiOrigin': 'https://wallpaper.biguo66.top',
        'descriptor': {'deliveryMode': 'LIVE_PHOTO'},
      });
    },
  );

  test('live photo preview keeps the fixed verified native boundary', () async {
    const preview = IosLivePhotoPreview();
    expect(
      await preview.prepare(
        requestId: 'b1bb19db-bb94-4e7a-b128-7004e41fcce7',
        wallpaperId: '7',
        apiOrigin: Uri.parse('https://wallpaper.biguo66.top/api/v1'),
        descriptor: const {'deliveryMode': 'LIVE_PHOTO_PREVIEW'},
      ),
      '/tmp/preview.mov',
    );
    expect(calls.last.arguments, {
      'requestId': 'b1bb19db-bb94-4e7a-b128-7004e41fcce7',
      'wallpaperId': '7',
      'apiOrigin': 'https://wallpaper.biguo66.top',
      'descriptor': {'deliveryMode': 'LIVE_PHOTO_PREVIEW'},
    });
    await preview.release('b1bb19db-bb94-4e7a-b128-7004e41fcce7');
    expect(calls.last.method, 'releaseLivePhotoPreview');
  });
}
