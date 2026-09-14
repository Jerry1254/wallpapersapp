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

class CachedCredentialTransport extends FakeTransport {
  DeviceApiError error = const DeviceApiError(404, 'CREDENTIAL_NOT_FOUND');
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (path.endsWith('/session-challenges') &&
        jsonDecode(body!)['credentialKeyId'] == 'cached') {
      throw error;
    }
    return super.request(
      path,
      method: method,
      body: body,
      headers: headers,
      accepted: accepted,
    );
  }
}

class EncryptionIdentity extends FakeIdentity {
  @override
  Future<Map<String, String>> encryptionPublicKey() async => {
    'publicKeyPem': 'encryption-public-key',
    'fingerprint': 'a' * 64,
    'keyAlgorithm': 'RSA-OAEP-SHA256-MGF1-SHA1',
  };
}

class BindingTransport extends FakeTransport {
  int bindings = 0;
  bool mismatch = false;
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (path != '/device/encryption-key') {
      return super.request(
        path,
        method: method,
        body: body,
        headers: headers,
        accepted: accepted,
      );
    }
    bindings++;
    expect(method, 'PUT');
    expect(jsonDecode(body!), {'publicKeyPem': 'encryption-public-key'});
    expect(headers['X-Request-Signature'], 'signed');
    await Future<void>.delayed(Duration.zero);
    return {
      'publicKeySha256': (mismatch ? 'b' : 'a') * 64,
      'keyAlgorithm': 'RSA-OAEP-SHA256-MGF1-SHA1',
    };
  }
}

void main() {
  test('空本地 API 没有旧缓存编号时重新证明同一公钥，保留安装身份', () async {
    final identity = FakeIdentity()..id = 'cached',
        transport = CachedCredentialTransport();
    final manager = DeviceSessionManager(transport, identity: identity);
    await manager.session();
    expect(transport.registrations, 1);
    expect(identity.id, 'key');
    expect(
      identity.payloads.first,
      startsWith('QJ-ANDROID-REGISTER-V1\ntest-scope\nfingerprint\n'),
    );
  });
  test('撤销、禁用和网络不确定不会触发重新注册', () async {
    for (final error in [
      const DeviceApiError(401, 'CREDENTIAL_REVOKED'),
      const DeviceApiError(403, 'DEVICE_DISABLED'),
      const DeviceApiError(0, 'NETWORK_ERROR'),
    ]) {
      final identity = FakeIdentity()..id = 'cached',
          transport = CachedCredentialTransport()..error = error;
      final manager = DeviceSessionManager(transport, identity: identity);
      await expectLater(manager.session(), throwsA(isA<DeviceApiError>()));
      expect(transport.registrations, 0);
      expect(identity.id, 'cached');
    }
  });
  test(
    'encryption binding coalesces callers without replacing installation identity',
    () async {
      final identity = EncryptionIdentity(), transport = BindingTransport();
      final manager = DeviceSessionManager(transport, identity: identity);
      final results = await Future.wait([
        manager.ensureEncryptionKey(),
        manager.ensureEncryptionKey(),
      ]);
      expect(results, ['a' * 64, 'a' * 64]);
      expect(transport.bindings, 1);
      expect(transport.registrations, 1);
      expect(identity.id, 'key');
      expect(
        identity.payloads.last,
        contains('PUT\n/api/v1/device/encryption-key\n'),
      );
      await manager.ensureEncryptionKey();
      expect(transport.bindings, 2);
      expect(transport.registrations, 1);
    },
  );
  test(
    'wrong binding fingerprint fails and subsequent attempt can recover',
    () async {
      final transport = BindingTransport()..mismatch = true;
      final manager = DeviceSessionManager(
        transport,
        identity: EncryptionIdentity(),
      );
      await expectLater(
        manager.ensureEncryptionKey(),
        throwsA(isA<DeviceApiError>()),
      );
      transport.mismatch = false;
      expect(await manager.ensureEncryptionKey(), 'a' * 64);
    },
  );

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
