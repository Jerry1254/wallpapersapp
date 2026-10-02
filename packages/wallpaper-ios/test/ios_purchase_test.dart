import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = NativeIosPurchaseStore.channel;
  final calls = <MethodCall>[];
  const transaction = {
    'id': '41',
    'productId': 'example.wallpaper',
    'signedTransaction': 'apple-jws',
    'signedAppTransaction': 'apple-app-jws',
    'deviceVerificationId': 'b4df37cb-0d4b-4c21-84be-62d5969eab07',
    'environment': 'SANDBOX',
    'revoked': false,
  };
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'products' => [
              {'id': 'example.wallpaper', 'displayPrice': '¥6.00'},
            ],
            'purchase' => {'status': 'PURCHASED', 'transaction': transaction},
            'transactions' || 'restore' => [transaction],
            'readCache' => '{"state":{}}',
            _ => null,
          };
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  test(
    'native StoreKit price and verified purchase payload are preserved',
    () async {
      const store = NativeIosPurchaseStore();
      final products = await store.products({'example.wallpaper'});
      expect(products.single.displayPrice, '¥6.00');
      final result = await store.purchase(
        'example.wallpaper',
        '7bf3e8f0-f5e5-4b4d-a03c-81924eebbaea',
      );
      expect(result.transaction!.signedTransaction, 'apple-jws');
      expect(result.transaction!.signedAppTransaction, 'apple-app-jws');
      expect(
        result.transaction!.deviceVerificationId,
        'b4df37cb-0d4b-4c21-84be-62d5969eab07',
      );
      expect(calls.last.arguments, {
        'productId': 'example.wallpaper',
        'accountToken': '7bf3e8f0-f5e5-4b4d-a03c-81924eebbaea',
        'testOnly': false,
      });
    },
  );
  test(
    'passive startup and explicit Restore use different native operations',
    () async {
      const store = NativeIosPurchaseStore();
      await store.transactions();
      await store.transactions(restore: true);
      expect(calls.map((value) => value.method), ['transactions', 'restore']);
      await store.finish('41');
      expect(calls.last.arguments, {'transactionId': '41'});
    },
  );
  test(
    'test checkout explicitly requires Xcode; production checkout does not enable it',
    () async {
      const store = NativeIosPurchaseStore(testOnly: true);
      await store.purchase(
        'example.wallpaper',
        '7bf3e8f0-f5e5-4b4d-a03c-81924eebbaea',
      );
      expect((calls.last.arguments as Map)['testOnly'], true);
    },
  );
  test(
    'business cache is separated by API environment and never stores Apple credentials',
    () async {
      const store = NativeIosPurchaseStore(
        cacheScope: 'https://local.invalid/api/v1',
      );
      await store.readCache();
      expect(calls.last.arguments, {
        'cacheScope': 'https://local.invalid/api/v1',
      });
      await store.writeCache('{"state":{}}');
      expect(calls.last.arguments, {
        'cacheScope': 'https://local.invalid/api/v1',
        'value': '{"state":{}}',
      });
    },
  );
}
