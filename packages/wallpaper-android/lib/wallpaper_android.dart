import 'package:flutter/services.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';

class InstallationIdentity {
  const InstallationIdentity(
    this.publicKeyPem,
    this.fingerprint,
    this.scope,
    this.credentialKeyId,
  );
  final String publicKeyPem, fingerprint, scope;
  final String? credentialKeyId;
}

class AndroidDeviceIdentity implements DeviceIdentity {
  const AndroidDeviceIdentity();
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  Future<InstallationIdentity> installation() async {
    final data = await _channel.invokeMapMethod<String, dynamic>('identity');
    if (data == null) throw StateError('Installation identity unavailable');
    return InstallationIdentity(
      data['publicKeyPem'] as String,
      data['fingerprint'] as String,
      data['scope'] as String,
      data['credentialKeyId'] as String?,
    );
  }

  Future<void> rememberCredential(String id) => _channel.invokeMethod<void>(
    'rememberCredential',
    {'credentialKeyId': id},
  );
  Future<String> signPayload(String payload) async {
    final proof = await _channel.invokeMethod<String>('sign', {
      'payload': payload,
    });
    if (proof == null) throw StateError('Signature unavailable');
    return proof;
  }

  @override
  Future<PlatformResult<String>> publicKey() async => PlatformResult(
    OperationStatus.completed,
    value: (await installation()).publicKeyPem,
  );
  @override
  Future<PlatformResult<String>> sign(String payload) async => PlatformResult(
    OperationStatus.completed,
    value: await signPayload(payload),
  );
}
