import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:crypto/crypto.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import '../security/security_network.dart';

String instantString(DateTime value) {
  final utc = value.toUtc();
  return DateTime.utc(
    utc.year,
    utc.month,
    utc.day,
    utc.hour,
    utc.minute,
    utc.second,
  ).toIso8601String().replaceFirst('.000Z', 'Z');
}

String requestUuid() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final text = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${text.substring(0, 8)}-${text.substring(8, 12)}-${text.substring(12, 16)}-${text.substring(16, 20)}-${text.substring(20)}';
}

class DeviceApiError implements Exception {
  const DeviceApiError(this.status, this.code);
  final int status;
  final String code;
  String get message => switch (code) {
    'ACCESS_UNAVAILABLE' => '网络异常，请稍后重试',
    'CREDENTIAL_REVOKED' || 'DEVICE_DISABLED' => '此安装凭据已停用，请联系客服',
    'TIMESTAMP_INVALID' => '手机时间与服务端不一致，请校准后重试',
    'DEVICE_PROVIDER_NOT_ALLOWED' ||
    'DEVICE_PROVIDER_UNAVAILABLE' => '当前服务尚未启用此安装包身份',
    'APP_UPDATE_REQUIRED' => '当前版本已不再支持，请更新后继续使用',
    _ => status == 0 ? '暂时无法连接服务，请重试' : '设备验证失败，请重试或联系客服',
  };
}

class DeviceInstallation {
  const DeviceInstallation(
    this.publicKeyPem,
    this.fingerprint,
    this.scope,
    this.credentialKeyId,
  );

  final String publicKeyPem, fingerprint, scope;
  final String? credentialKeyId;
}

abstract interface class InstallationIdentityProvider {
  String get platform;
  String get registrationDomain;
  String get challengeAlgorithm;
  bool get canResetInvalidIdentity;
  Future<DeviceInstallation> installation();
  Future<String> signPayload(String payload);
  Future<void> rememberCredential(String credentialKeyId);
  Future<void> reset();
  Future<Map<String, String>?> encryptionPublicKey();
}

class AndroidInstallationIdentityProvider
    implements InstallationIdentityProvider {
  AndroidInstallationIdentityProvider([AndroidDeviceIdentity? identity])
    : identity = identity ?? const AndroidDeviceIdentity();

  final AndroidDeviceIdentity identity;
  @override
  String get platform => 'ANDROID';
  @override
  String get registrationDomain => 'QJ-ANDROID-REGISTER-V1';
  @override
  String get challengeAlgorithm => 'RSA_SHA256';
  @override
  bool get canResetInvalidIdentity => false;
  @override
  Future<DeviceInstallation> installation() async {
    final value = await identity.installation();
    return DeviceInstallation(
      value.publicKeyPem,
      value.fingerprint,
      value.scope,
      value.credentialKeyId,
    );
  }

  @override
  Future<String> signPayload(String payload) => identity.signPayload(payload);
  @override
  Future<void> rememberCredential(String credentialKeyId) =>
      identity.rememberCredential(credentialKeyId);
  @override
  Future<void> reset() async =>
      throw UnsupportedError('Android identity reset');
  @override
  Future<Map<String, String>?> encryptionPublicKey() =>
      identity.encryptionPublicKey();
}

class IosInstallationIdentityProvider implements InstallationIdentityProvider {
  const IosInstallationIdentityProvider([
    this.identity = const IosDeviceIdentity(),
  ]);

  final IosDeviceIdentity identity;
  @override
  String get platform => 'IOS';
  @override
  String get registrationDomain => 'QJ-IOS-REGISTER-V1';
  @override
  String get challengeAlgorithm => 'ECDSA_P256_SHA256';
  @override
  bool get canResetInvalidIdentity => true;
  @override
  Future<DeviceInstallation> installation() async {
    final value = await identity.installation();
    return DeviceInstallation(
      value.publicKeyPem,
      value.fingerprint,
      value.scope,
      value.credentialKeyId,
    );
  }

  @override
  Future<String> signPayload(String payload) => identity.signPayload(payload);
  @override
  Future<void> rememberCredential(String credentialKeyId) =>
      identity.rememberCredential(credentialKeyId);
  @override
  Future<void> reset() => identity.reset();
  @override
  Future<Map<String, String>?> encryptionPublicKey() async => null;
}

abstract interface class DeviceTransport {
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  });
}

class HttpDeviceTransport implements DeviceTransport {
  HttpDeviceTransport(this.base, {this.versionHeaders, this.onUpdateRequired});
  final Uri base;
  final Future<Map<String, String>> Function()? versionHeaders;
  final void Function()? onUpdateRequired;
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (SecurityNetwork.blocked && !SecurityNetwork.isRecovery(path)) {
      throw const DeviceApiError(403, 'ACCESS_UNAVAILABLE');
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await (() async {
        final uri = base.resolve('${base.path}$path');
        final request = await client.openUrl(method, uri);
        request.followRedirects = false;
        request.headers.set('Accept', 'application/json');
        final appHeaders = await versionHeaders?.call() ?? <String, String>{};
        appHeaders.forEach(request.headers.set);
        headers.forEach(request.headers.set);
        if (body != null) {
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(body));
        }
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          bytes.addAll(chunk);
          if (bytes.length > 4 * 1024 * 1024) throw const FormatException();
        }
        Map<String, dynamic> data;
        try {
          data = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
        } catch (_) {
          throw DeviceApiError(response.statusCode, 'INVALID_RESPONSE');
        }
        if ((response.statusCode < 200 || response.statusCode >= 300) &&
            !accepted.contains(response.statusCode)) {
          final code =
              (data['error'] as Map<String, dynamic>?)?['code'] as String? ??
              'REQUEST_FAILED';
          if (code == 'APP_UPDATE_REQUIRED') onUpdateRequired?.call();
          if (code == 'ACCESS_UNAVAILABLE') SecurityNetwork.notifyBlocked();
          throw DeviceApiError(response.statusCode, code);
        }
        return data;
      })().timeout(
        // Apple confirmation performs several remote verification steps. Its
        // budget must outlast Apple's 15-second per-request server timeout.
        path == '/device/ios/acquisition/purchases' ||
                path == '/device/ios/acquisition/credit-restores'
            ? const Duration(seconds: 60)
            : const Duration(seconds: 15),
      );
    } on DeviceApiError {
      rethrow;
    } on TimeoutException {
      throw const DeviceApiError(0, 'NETWORK_TIMEOUT');
    } catch (_) {
      throw const DeviceApiError(0, 'NETWORK_ERROR');
    } finally {
      client.close(force: true);
    }
  }
}

class DeviceSession {
  const DeviceSession(this.token, this.expiresAt, this.credentialKeyId);
  final String token, credentialKeyId;
  final DateTime expiresAt;
}

class DeviceSessionManager {
  DeviceSessionManager(
    this.transport, {
    AndroidDeviceIdentity? identity,
    InstallationIdentityProvider? provider,
  }) : identity =
           provider ??
           (Platform.isIOS
               ? const IosInstallationIdentityProvider()
               : AndroidInstallationIdentityProvider(identity));
  final DeviceTransport transport;
  final InstallationIdentityProvider identity;
  DeviceSession? _current;
  Future<DeviceSession>? _pending;
  Future<DeviceSession> session({bool allowBlocked = false}) async {
    if (SecurityNetwork.blocked && !allowBlocked) {
      throw const DeviceApiError(403, 'ACCESS_UNAVAILABLE');
    }
    final current = _current;
    if (current != null &&
        current.expiresAt.isAfter(
          DateTime.now().toUtc().add(const Duration(seconds: 30)),
        )) {
      return current;
    }
    if (_pending != null) return _pending!;
    final operation = _create();
    _pending = operation;
    try {
      return await operation;
    } finally {
      if (identical(_pending, operation)) _pending = null;
    }
  }

  Future<DeviceSession> _create() async {
    var installation = await identity.installation();
    Future<String> register() async {
      final timestamp = instantString(DateTime.now());
      final nonce = requestUuid();
      final proof = await identity.signPayload(
        '${identity.registrationDomain}\n${installation.scope}\n${installation.fingerprint}\n$timestamp\n$nonce',
      );
      final evidence = base64Url
          .encode(
            utf8.encode(
              jsonEncode({
                'timestamp': timestamp,
                'nonce': nonce,
                'proof': proof,
              }),
            ),
          )
          .replaceAll('=', '');
      final registration = await transport.request(
        '/device/registrations',
        method: 'POST',
        body: jsonEncode({
          'platform': identity.platform,
          'appInstallScope': installation.scope,
          'credentialType': 'PLATFORM_PUBLIC_KEY',
          'publicKeyPem': installation.publicKeyPem,
          'evidenceToken': evidence,
        }),
      );
      if (registration['credentialType'] != 'PLATFORM_PUBLIC_KEY' ||
          registration['credentialSecret'] != null) {
        throw const DeviceApiError(0, 'INVALID_PROVIDER_RESPONSE');
      }
      final keyId = registration['credentialKeyId'] as String;
      await identity.rememberCredential(keyId);
      return keyId;
    }

    Future<String> registerWithRecovery() async {
      try {
        return await register();
      } on DeviceApiError catch (error) {
        if (!identity.canResetInvalidIdentity ||
            !{
              'CREDENTIAL_REVOKED',
              'CREDENTIAL_INVALID',
              'PROOF_INVALID',
            }.contains(error.code)) {
          rethrow;
        }
        await identity.reset();
        installation = await identity.installation();
        return register();
      }
    }

    var keyId = installation.credentialKeyId ?? await registerWithRecovery();
    Future<Map<String, dynamic>> challengeFor(String id) => transport.request(
      '/device/session-challenges',
      method: 'POST',
      body: jsonEncode({'credentialKeyId': id}),
    );
    Map<String, dynamic> challenge;
    try {
      challenge = await challengeFor(keyId);
    } on DeviceApiError catch (error) {
      // An empty local API has no cached server identifier. Re-prove the same installation key;
      // a revoked/disabled credential or an uncertain network response must never trigger this.
      if (error.status != 404 || error.code != 'CREDENTIAL_NOT_FOUND') rethrow;
      keyId = await registerWithRecovery();
      challenge = await challengeFor(keyId);
    }
    if (challenge['algorithm'] != identity.challengeAlgorithm) {
      throw const DeviceApiError(0, 'UNSUPPORTED_SIGNATURE');
    }
    final timestamp = instantString(DateTime.now());
    final proof = await identity.signPayload(
      'QJ-DEVICE-SESSION-V1\n$keyId\n${challenge['challengeId']}\n${challenge['nonce']}\n$timestamp',
    );
    final response = await transport.request(
      '/device/sessions',
      method: 'POST',
      body: jsonEncode({
        'credentialKeyId': keyId,
        'challengeId': challenge['challengeId'],
        'clientTimestamp': timestamp,
        'proof': proof,
      }),
    );
    if (response['platform'] != identity.platform ||
        response['tokenType'] != 'Bearer') {
      throw const DeviceApiError(0, 'INVALID_SESSION_RESPONSE');
    }
    final session = DeviceSession(
      response['accessToken'] as String,
      DateTime.parse(response['expiresAt'] as String),
      keyId,
    );
    _current = session;
    return session;
  }

  Future<String>? _encryptionBinding;

  /// Called before secure downloads. Keeps identity unchanged and coalesces callers.
  Future<String> ensureEncryptionKey() {
    return _encryptionBinding ??= _bindEncryptionKey().whenComplete(() {
      _encryptionBinding = null;
    });
  }

  Future<String> _bindEncryptionKey() async {
    // Establish the installation signing identity before generating its decrypt key.
    await session();
    final key = await identity.encryptionPublicKey();
    if (key == null) throw const DeviceApiError(0, 'ENCRYPTION_UNSUPPORTED');
    final result = await authenticated(
      '/device/encryption-key',
      method: 'PUT',
      body: jsonEncode({'publicKeyPem': key['publicKeyPem']}),
      signed: true,
    );
    if (result['publicKeySha256'] != key['fingerprint'] ||
        result['keyAlgorithm'] != key['keyAlgorithm']) {
      throw const DeviceApiError(0, 'INVALID_ENCRYPTION_BINDING');
    }
    return key['fingerprint']!;
  }

  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    for (var attempt = 0; attempt < 2; attempt++) {
      final current = await session(
        allowBlocked: SecurityNetwork.isRecovery(path),
      );
      final requestHeaders = {
        ...headers,
        'Authorization': 'Bearer ${current.token}',
      };
      if (signed) {
        final timestamp = instantString(DateTime.now()), nonce = requestUuid();
        final bodyHash = sha256.convert(utf8.encode(body ?? '')).toString();
        // API v1 canonical path excludes the query; never sign re-encoded JSON.
        final canonical = '/api/v1${path.split('?').first}';
        final proof = await identity.signPayload(
          'QJ-SIGNED-REQUEST-V1\n${method.toUpperCase()}\n$canonical\n$timestamp\n$nonce\n$bodyHash',
        );
        requestHeaders.addAll({
          'X-Request-Timestamp': timestamp,
          'X-Request-Nonce': nonce,
          'X-Request-Signature': proof,
        });
      }
      try {
        return await transport.request(
          path,
          method: method,
          body: body,
          headers: requestHeaders,
          accepted: accepted,
        );
      } on DeviceApiError catch (error) {
        if (error.status != 401 || attempt == 1) rethrow;
        if (identical(_current, current)) _current = null;
      }
    }
    throw const DeviceApiError(401, 'SESSION_EXPIRED');
  }
}
