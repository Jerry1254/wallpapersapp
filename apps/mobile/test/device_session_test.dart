import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';

class FakeIdentity extends AndroidDeviceIdentity {
  String? id;
  final payloads = <String>[];
  @override
  Future<InstallationIdentity> installation() async =>
      InstallationIdentity('public-key', 'fingerprint', 'test-scope', id);
  @override
  Future<void> rememberCredential(String value) async {
    id = value;
  }

  @override
  Future<String> signPayload(String payload) async {
    payloads.add(payload);
    return 'signed';
  }
}

class FakeTransport implements DeviceTransport {
  int sessions = 0, registrations = 0, protectedRequests = 0;
  bool reject = false;
  Map<String, String>? signedHeaders;
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (path.endsWith('/registrations')) {
      registrations++;
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
      sessions++;
      return {
        'platform': 'ANDROID',
        'tokenType': 'Bearer',
        'accessToken': 'token$sessions',
        'expiresAt': DateTime.now()
            .toUtc()
            .add(const Duration(hours: 1))
            .toIso8601String(),
      };
    }
    protectedRequests++;
    signedHeaders = headers;
    if (reject || headers['Authorization'] == 'Bearer token1') {
      throw const DeviceApiError(401, 'SESSION_EXPIRED');
    }
    return {'items': []};
  }
}

void main() {
  test('UTC 时间字符串与 Java Instant 分组格式一致', () {
    expect(
      instantString(DateTime.parse('2026-09-14T00:00:00.000Z')),
      '2026-09-14T00:00:00Z',
    );
    expect(
      instantString(DateTime.parse('2026-09-14T00:00:00.123000Z')),
      '2026-09-14T00:00:00.123Z',
    );
    expect(
      instantString(DateTime.parse('2026-09-14T00:00:00.123450Z')),
      '2026-09-14T00:00:00.123450Z',
    );
  });
  test('并发会话合并，401 后最多续期一次且复用持久化凭据', () async {
    final identity = FakeIdentity(), transport = FakeTransport();
    final manager = DeviceSessionManager(transport, identity: identity);
    await Future.wait([
      manager.session(),
      manager.session(),
      manager.session(),
    ]);
    expect(transport.sessions, 1);
    expect(transport.registrations, 1);
    await Future.wait([
      manager.authenticated('/device/me/entitlements'),
      manager.authenticated('/device/me/entitlements'),
    ]);
    expect(transport.sessions, 2);
    expect(transport.registrations, 1);
    transport.reject = true;
    final before = transport.protectedRequests;
    await expectLater(
      manager.authenticated('/device/me/entitlements'),
      throwsA(isA<DeviceApiError>()),
    );
    expect(transport.protectedRequests - before, 2);
  });
  test('签名覆盖准确 body 字节，使用契约头和新 nonce', () async {
    final identity = FakeIdentity(), transport = FakeTransport();
    final manager = DeviceSessionManager(transport, identity: identity);
    final body = jsonEncode({'wallpaperId': '1', 'code': 'test-only'});
    await manager.authenticated(
      '/device/redemptions',
      method: 'POST',
      body: body,
      signed: true,
    );
    expect(transport.signedHeaders!['X-Request-Signature'], 'signed');
    final requests = identity.payloads
        .where((s) => s.startsWith('QJ-SIGNED'))
        .toList();
    expect(requests.length, 2);
    expect(requests[0], isNot(requests[1]));
    expect(requests.last, contains('POST\n/api/v1/device/redemptions\n'));
    expect(requests.last, isNot(contains('test-only')));
  });
}
