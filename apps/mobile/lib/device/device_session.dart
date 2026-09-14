import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:crypto/crypto.dart';
import 'package:wallpaper_android/wallpaper_android.dart';

String instantString(
  DateTime value,
) => value.toUtc().toIso8601String().replaceFirstMapped(RegExp(r'\.(\d+)Z$'), (
  match,
) {
  final fraction = match.group(1)!;
  if (RegExp(r'^0+$').hasMatch(fraction)) return 'Z';
  return '.${fraction.length == 6 && fraction.endsWith("000") ? fraction.substring(0, 3) : fraction}Z';
});
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
    'CREDENTIAL_REVOKED' || 'DEVICE_DISABLED' => '此安装凭据已停用，请联系客服',
    'TIMESTAMP_INVALID' => '手机时间与服务端不一致，请校准后重试',
    'DEVICE_PROVIDER_NOT_ALLOWED' ||
    'DEVICE_PROVIDER_UNAVAILABLE' => '当前服务尚未启用安卓身份',
    _ => status == 0 ? '暂时无法连接服务，请重试' : '设备验证失败，请重试或联系客服',
  };
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
  HttpDeviceTransport(this.base);
  final Uri base;
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await (() async {
        final uri = base.resolve('${base.path}$path');
        final request = await client.openUrl(method, uri);
        request.followRedirects = false;
        request.headers.set('Accept', 'application/json');
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
          throw DeviceApiError(
            response.statusCode,
            (data['error'] as Map<String, dynamic>?)?['code'] as String? ??
                'REQUEST_FAILED',
          );
        }
        return data;
      })().timeout(const Duration(seconds: 15));
    } on DeviceApiError {
      rethrow;
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
  DeviceSessionManager(this.transport, {AndroidDeviceIdentity? identity})
    : identity = identity ?? const AndroidDeviceIdentity();
  final DeviceTransport transport;
  final AndroidDeviceIdentity identity;
  DeviceSession? _current;
  Future<DeviceSession>? _pending;
  Future<DeviceSession> session() async {
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
    final installation = await identity.installation();
    String? keyId = installation.credentialKeyId;
    if (keyId == null) {
      final timestamp = instantString(DateTime.now());
      final nonce = requestUuid();
      final proof = await identity.signPayload(
        'QJ-ANDROID-REGISTER-V1\n${installation.scope}\n${installation.fingerprint}\n$timestamp\n$nonce',
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
          'platform': 'ANDROID',
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
      keyId = registration['credentialKeyId'] as String;
      await identity.rememberCredential(keyId);
    }
    final challenge = await transport.request(
      '/device/session-challenges',
      method: 'POST',
      body: jsonEncode({'credentialKeyId': keyId}),
    );
    if (challenge['algorithm'] != 'RSA_SHA256') {
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
    if (response['platform'] != 'ANDROID' ||
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

  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    for (var attempt = 0; attempt < 2; attempt++) {
      final current = await session();
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
