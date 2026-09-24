import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'device_session_test.dart' show FakeIdentity;
import 'package:qingjing_wallpaper/entitlements/redemption.dart';
import 'package:qingjing_wallpaper/entitlements/redemption_dialog.dart';

class MemoryStore implements PendingStore {
  PendingRedemption? value;
  bool failWrite = false;
  @override
  Future<PendingRedemption?> read() async => value;
  @override
  Future<void> write(PendingRedemption? next) async {
    if (failWrite) throw StateError('disk full');
    value = next;
  }
}

class FakeApi implements RedemptionApi {
  final keys = <String>[];
  bool timeout = true;
  DeviceApiError? error;
  String result = 'GRANTED';
  @override
  Future<Map<String, dynamic>> submit(
    PendingRedemption pending,
    String body,
  ) async {
    keys.add(pending.key);
    if (error != null) throw error!;
    if (timeout) throw StateError('lost response after commit');
    return {'idempotencyKey': pending.key, 'result': result};
  }

  @override
  Future<Map<String, dynamic>> confirm(String key) async => {
    'idempotencyKey': key,
    'result': result,
  };
}

class ContractTransport implements DeviceTransport {
  final calls = <String>[];
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (path == '/device/registrations') {
      return {
        'credentialType': 'PLATFORM_PUBLIC_KEY',
        'credentialKeyId': 'key',
      };
    }
    if (path == '/device/session-challenges') {
      return {
        'algorithm': 'RSA_SHA256',
        'challengeId': 'challenge',
        'nonce': 'nonce',
      };
    }
    if (path == '/device/sessions') {
      return {
        'platform': 'ANDROID',
        'tokenType': 'Bearer',
        'accessToken': 'token',
        'expiresAt': DateTime.now()
            .toUtc()
            .add(const Duration(hours: 1))
            .toIso8601String(),
      };
    }
    calls.add('$method $path');
    if (method == 'POST') {
      expect(headers['Idempotency-Key'], isNotEmpty);
      expect(headers['X-Request-Signature'], 'signed');
      expect(accepted, contains(422));
    }
    return {};
  }
}

void main() {
  test(
    'API adapter uses contract routes and signed POST with 422 body handling',
    () async {
      final transport = ContractTransport();
      final api = SessionRedemptionApi(
        DeviceSessionManager(transport, identity: FakeIdentity()),
      );
      final pending = PendingRedemption(requestUuid(), '1', 'a' * 64);
      await api.submit(pending, '{}');
      await api.confirm(pending.key);
      expect(transport.calls, [
        'POST /device/redemptions',
        'GET /device/redemptions/${pending.key}',
      ]);
    },
  );
  const code = 'ABCDEFGHIJKLMNOPQRST';
  test(
    'timeout and process restart recover original key without storing code',
    () async {
      final store = MemoryStore(), api = FakeApi();
      await expectLater(
        RedemptionCoordinator(api, store).redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      expect(store.value!.toJson().keys, ['key', 'wallpaperId', 'bodyHash']);
      expect(store.value!.toJson().toString(), isNot(contains(code)));
      final key = store.value!.key;
      final restarted = RedemptionCoordinator(api, store);
      expect(await restarted.confirm(), contains('兑换成功'));
      expect(api.keys, [key]);
      expect(store.value, isNull);
    },
  );
  test(
    'different intent cannot replace pending request; exact retry preserves key',
    () async {
      final store = MemoryStore(),
          api = FakeApi(),
          coordinator = RedemptionCoordinator(FakeApi(), MemoryStore());
      // A separate instance models a restarted app, sharing only durable storage.
      await expectLater(
        RedemptionCoordinator(api, store).redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      final key = store.value!.key;
      await expectLater(
        RedemptionCoordinator(api, store).redeem('2', code),
        throwsA(isA<RedemptionNotice>()),
      );
      expect(api.keys.length, 1);
      api.timeout = false;
      await RedemptionCoordinator(
        api,
        store,
      ).redeem('1', 'abcd-efgh-ijkl-mnop-qrst');
      expect(api.keys, [key, key]);
      expect(store.value, isNull);
      expect(await coordinator.confirm(), '没有待确认的兑换');
    },
  );
  test(
    'unrecognized response retains intent; definitive rejection clears it',
    () async {
      final store = MemoryStore(),
          api = FakeApi()
            ..timeout = false
            ..result = 'FUTURE_VALUE';
      final coordinator = RedemptionCoordinator(api, store);
      await expectLater(
        coordinator.redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      expect(store.value, isNotNull);
      api.result = 'CODE_EXHAUSTED';
      expect(await coordinator.confirm(), contains('额度已用尽'));
      expect(store.value, isNull);
    },
  );
  test(
    'first rejection clears intent; retry rejection retains uncertain original',
    () async {
      final store = MemoryStore(),
          api = FakeApi()..error = const DeviceApiError(429, 'RATE_LIMITED');
      final coordinator = RedemptionCoordinator(api, store);
      await expectLater(
        coordinator.redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      expect(store.value, isNull);
      api.error = null;
      await expectLater(
        coordinator.redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      final original = store.value!.key;
      api.error = const DeviceApiError(429, 'RATE_LIMITED');
      await expectLater(
        coordinator.redeem('1', code),
        throwsA(isA<RedemptionNotice>()),
      );
      expect(store.value!.key, original);
    },
  );
  test('cannot dispatch without durable intent', () async {
    final store = MemoryStore()..failWrite = true, api = FakeApi();
    await expectLater(
      RedemptionCoordinator(api, store).redeem('1', code),
      throwsStateError,
    );
    expect(api.keys, isEmpty);
  });
  test('免费作品兼容结果不扣额度并清除待确认请求', () async {
    final store = MemoryStore(),
        api = FakeApi()
          ..timeout = false
          ..result = 'WALLPAPER_FREE';
    final message = await RedemptionCoordinator(api, store).redeem('1', code);
    expect(message, contains('无需兑换'));
    expect(store.value, isNull);
  });

  testWidgets('兑换成功后弹层立即返回成功结果', (tester) async {
    final coordinator = RedemptionCoordinator(
      FakeApi()
        ..timeout = false
        ..result = 'GRANTED',
      MemoryStore(),
    );
    bool? granted;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => FilledButton(
            onPressed: () async {
              granted = await showModalBottomSheet<bool>(
                context: context,
                isScrollControlled: true,
                builder: (_) => RedemptionDialog(
                  coordinator: coordinator,
                  wallpaperId: '1',
                ),
              );
            },
            child: const Text('兑换'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('兑换'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), code);
    await tester.tap(find.text('验证并兑换'));
    await tester.pumpAndSettle();

    expect(granted, isTrue);
    expect(find.byType(RedemptionDialog), findsNothing);
  });
}
