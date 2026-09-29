import 'package:flutter/services.dart';

class IosInstallationIdentity {
  const IosInstallationIdentity({
    required this.publicKeyPem,
    required this.fingerprint,
    required this.scope,
    this.credentialKeyId,
  });

  final String publicKeyPem;
  final String fingerprint;
  final String scope;
  final String? credentialKeyId;
}

class IosDeviceIdentity {
  const IosDeviceIdentity();

  static const _channel = MethodChannel('qingjing/wallpaper_ios');

  Future<IosInstallationIdentity> installation() async {
    final value = await _channel.invokeMapMethod<String, dynamic>('identity');
    if (value == null) {
      throw PlatformException(
        code: 'IDENTITY_UNAVAILABLE',
        message: '无法创建安装身份',
      );
    }
    return IosInstallationIdentity(
      publicKeyPem: value['publicKeyPem'] as String,
      fingerprint: value['fingerprint'] as String,
      scope: value['scope'] as String,
      credentialKeyId: value['credentialKeyId'] as String?,
    );
  }

  Future<String> signPayload(String payload) async {
    final signature = await _channel.invokeMethod<String>('sign', {
      'payload': payload,
    });
    if (signature == null || signature.isEmpty) {
      throw PlatformException(
        code: 'IDENTITY_UNAVAILABLE',
        message: '无法完成设备签名',
      );
    }
    return signature;
  }

  Future<void> rememberCredential(String credentialKeyId) =>
      _channel.invokeMethod<void>('rememberCredential', {
        'credentialKeyId': credentialKeyId,
      });

  Future<void> reset() => _channel.invokeMethod<void>('resetIdentity');
}

class IosPendingRedemptionStore {
  const IosPendingRedemptionStore();

  static const _channel = MethodChannel('qingjing/wallpaper_ios');

  Future<String?> read() =>
      _channel.invokeMethod<String>('readPendingRedemption');

  Future<void> write(String? value) =>
      _channel.invokeMethod<void>('writePendingRedemption', {'value': value});
}

class IosMediaInstaller {
  const IosMediaInstaller();

  static const _channel = MethodChannel('qingjing/wallpaper_ios');

  Future<String?> current(String wallpaperId, String resourceType) =>
      _channel.invokeMethod<String>('savedMedia', {
        'wallpaperId': wallpaperId,
        'resourceType': resourceType,
      });

  Future<String> save({
    required String requestId,
    required String wallpaperId,
    required String resourceType,
    required Uri apiOrigin,
    required Map<String, dynamic> descriptor,
  }) async {
    final result = await _channel.invokeMethod<String>('saveMedia', {
      'requestId': requestId,
      'wallpaperId': wallpaperId,
      'resourceType': resourceType,
      'apiOrigin': apiOrigin.origin,
      'descriptor': descriptor,
    });
    if (result == null || result.isEmpty) {
      throw PlatformException(code: 'SAVE_FAILED', message: '壁纸没有保存成功');
    }
    return result;
  }

  Future<void> cancel(String requestId) =>
      _channel.invokeMethod<void>('cancelSave', {'requestId': requestId});

  Future<int> clearUnused() async => 0;
}

class IosLivePhotoPreview {
  const IosLivePhotoPreview();

  static const _channel = MethodChannel('qingjing/wallpaper_ios');

  Future<String> prepare({
    required String requestId,
    required String wallpaperId,
    required Uri apiOrigin,
    required Map<String, dynamic> descriptor,
  }) async {
    final path = await _channel
        .invokeMethod<String>('prepareLivePhotoPreview', {
          'requestId': requestId,
          'wallpaperId': wallpaperId,
          'apiOrigin': apiOrigin.origin,
          'descriptor': descriptor,
        });
    if (path == null || path.isEmpty) {
      throw PlatformException(code: 'PREVIEW_FAILED', message: '动态预览准备失败');
    }
    return path;
  }

  Future<void> release(String requestId) => _channel.invokeMethod<void>(
    'releaseLivePhotoPreview',
    {'requestId': requestId},
  );
}
