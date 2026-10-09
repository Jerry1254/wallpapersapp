import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/security/app_security.dart';
import 'package:qingjing_wallpaper/security/security_network.dart';
import 'device_session_test.dart' show FakeIdentity;

class RealHttpOverrides extends HttpOverrides {}

class MemorySecurityStore implements SecurityLocalStore {
  bool value = false;
  @override
  Future<bool> blocked(String scope) async => value;
  @override
  Future<void> save(String scope, bool blocked) async {
    value = blocked;
  }
}

class SecurityTransport implements DeviceTransport {
  bool allowed = true, fail = false;
  List<String> checks = [];
  final requests = <String>[];
  Map<String, dynamic>? report;
  Completer<Map<String, dynamic>>? delayed;
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    requests.add(path);
    if (path.endsWith('/registrations')) {
      return {
        'credentialType': 'PLATFORM_PUBLIC_KEY',
        'credentialKeyId': 'key',
      };
    }
    if (path.endsWith('/session-challenges')) {
      return {
        'algorithm': 'RSA_SHA256',
        'challengeId': 'challenge',
        'nonce': 'nonce',
      };
    }
    if (path.endsWith('/sessions')) {
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
    if (fail) throw const DeviceApiError(0, 'NETWORK_ERROR');
    if (path.endsWith('/reports')) {
      report = jsonDecode(body!) as Map<String, dynamic>;
      allowed = false;
    }
    if (delayed != null) return delayed!.future;
    return {'allowed': allowed, 'checks': checks};
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tearDown(() {
    SecurityNetwork.blocked = false;
    SecurityNetwork.onBlocked = null;
  });
  test(
    'disabled checks do not invoke native detectors; enabled positive signals send signed reports',
    () async {
      final transport = SecurityTransport(),
          identity = FakeIdentity(),
          store = MemorySecurityStore();
      int calls = 0;
      final controller = AppSecurityController(
        DeviceSessionManager(transport, identity: identity),
        'local',
        store: store,
        checks: (keys) async {
          calls++;
          return {for (final key in keys) key: 'RISK'};
        },
      );
      await controller.start();
      expect(controller.ready, true);
      expect(calls, 0);
      transport.checks = ['ROOT_JAILBREAK'];
      await controller.check();
      expect(calls, 1);
      expect(transport.report, {
        'signals': {'ROOT_JAILBREAK': 'RISK'},
      });
      expect(
        identity.payloads.last,
        contains('POST\n/api/v1/device/security/reports'),
      );
      expect(controller.blocked, true);
      expect(store.value, true);
      controller.dispose();
    },
  );
  test(
    'cached bans survive network failures and only a successful server state clears them',
    () async {
      final transport = SecurityTransport()..fail = true,
          store = MemorySecurityStore()..value = true;
      final controller = AppSecurityController(
        DeviceSessionManager(transport, identity: FakeIdentity()),
        'local',
        store: store,
      );
      await controller.start();
      expect(controller.blocked, true);
      expect(controller.ready, false);
      expect(store.value, true);
      transport.fail = false;
      transport.allowed = false;
      await controller.check();
      expect(controller.blocked, true);
      transport.allowed = true;
      await controller.check();
      expect(controller.blocked, false);
      expect(store.value, false);
      controller.dispose();
    },
  );
  test(
    'late permissive response cannot undo a newer business denial',
    () async {
      final transport = SecurityTransport(), store = MemorySecurityStore();
      final controller = AppSecurityController(
        DeviceSessionManager(transport, identity: FakeIdentity()),
        'local',
        store: store,
      );
      await controller.start();
      transport.delayed = Completer();
      final checking = controller.check();
      await Future<void>.delayed(Duration.zero);
      SecurityNetwork.notifyBlocked();
      transport.delayed!.complete({'allowed': true, 'checks': <String>[]});
      await checking;
      expect(controller.blocked, true);
      expect(SecurityNetwork.blocked, true);
      controller.dispose();
    },
  );
  test(
    'blocked HTTP transport never sends business requests but keeps the recovery endpoint reachable',
    () => HttpOverrides.runWithHttpOverrides(() async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      int requests = 0;
      final subscription = server.listen((request) {
        requests++;
        request.response.headers.contentType = ContentType.json;
        request.response.write('{"allowed":false,"checks":[]}');
        request.response.close();
      });
      final transport = HttpDeviceTransport(
        Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      );
      try {
        SecurityNetwork.blocked = true;
        await expectLater(
          transport.request('/public/wallpapers'),
          throwsA(isA<DeviceApiError>()),
        );
        expect(requests, 0);
        await transport.request('/device/security/state');
        expect(requests, 1);
      } finally {
        await subscription.cancel();
        await server.close(force: true);
      }
    }, RealHttpOverrides()),
  );
  testWidgets(
    'generic page covers nested routes, exposes no ban reason and refresh only checks state',
    (tester) async {
      final transport = SecurityTransport();
      final actual = AppSecurityController(
        DeviceSessionManager(transport, identity: FakeIdentity()),
        'local',
        store: MemorySecurityStore(),
      );
      await actual.start();
      await tester.pumpWidget(
        MaterialApp(
          builder: (_, child) =>
              AppSecurityOverlay(controller: actual, child: child!),
          home: Builder(
            builder: (context) => Scaffold(
              body: Column(
                children: [
                  const Text('用户首页'),
                  TextButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => const Scaffold(body: Text('壁纸详情')),
                      ),
                    ),
                    child: const Text('进入详情'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      expect(find.text('用户首页'), findsOneWidget);
      await tester.tap(find.text('进入详情'));
      await tester.pumpAndSettle();
      expect(find.text('壁纸详情'), findsOneWidget);
      transport.allowed = false;
      await actual.check();
      await tester.pump();
      expect(find.text('网络异常'), findsOneWidget);
      expect(find.text('用户首页'), findsNothing);
      expect(find.text('壁纸详情'), findsNothing);
      expect(find.textContaining('拉黑'), findsNothing);
      expect(find.textContaining('ROOT'), findsNothing);
      final before = transport.requests.length;
      await tester.tap(find.text('刷新'));
      await tester.pumpAndSettle();
      expect(transport.requests.sublist(before), ['/device/security/state']);
      expect(find.text('网络异常'), findsOneWidget);
      transport.allowed = true;
      await tester.tap(find.text('刷新'));
      await tester.pumpAndSettle();
      expect(find.text('用户首页'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      actual.dispose();
    },
  );
}
