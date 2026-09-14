import 'dart:async';
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

class SecurePackageDownload {
  const SecurePackageDownload({
    required this.requestId,
    required this.wallpaperId,
    required this.resourceType,
    required this.url,
    required this.descriptor,
  });
  final String requestId, wallpaperId, resourceType;
  final Uri url;
  final Map<String, dynamic> descriptor;
}

class PackageDownloadProgress {
  const PackageDownloadProgress(
    this.requestId,
    this.status,
    this.receivedBytes,
    this.totalBytes,
  );
  final String requestId, status;
  final int receivedBytes, totalBytes;
}

/// Installation private keys, plaintext files and storage paths stay inside Android.
class AndroidPackageInstaller implements PackageInstaller {
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  static const _events = EventChannel('qingjing/wallpaper_downloads');
  final _prepared = <String, SecurePackageDownload>{};
  late final Stream<PackageDownloadProgress> progress = _events
      .receiveBroadcastStream()
      .map((value) {
        final data = Map<String, dynamic>.from(value as Map);
        return PackageDownloadProgress(
          data['requestId'] as String,
          data['status'] as String,
          data['receivedBytes'] as int,
          data['totalBytes'] as int,
        );
      })
      .asBroadcastStream();
  void prepare(SecurePackageDownload download) {
    _prepared[download.requestId] = download;
  }

  Future<Map<String, dynamic>> information() async =>
      await _channel.invokeMapMethod<String, dynamic>('deliveryInfo') ?? {};
  Future<String?> current(String wallpaperId, String resourceType) =>
      _channel.invokeMethod<String>('installedPackage', {
        'wallpaperId': wallpaperId,
        'resourceType': resourceType,
      });
  Future<void> cancel(String requestId) =>
      _channel.invokeMethod<void>('cancelDownload', {'requestId': requestId});
  Future<int> clearUnused() async =>
      await _channel.invokeMethod<int>('clearPackageCache') ?? 0;
  @override
  Future<PlatformResult<String>> install(String downloadId) async {
    final request = _prepared.remove(downloadId);
    if (request == null)
      return const PlatformResult(
        OperationStatus.unknown,
        message: '下载请求已失效，请重新下载',
      );
    try {
      final result = await _channel
          .invokeMapMethod<String, dynamic>('installPackage', {
            'requestId': request.requestId,
            'wallpaperId': request.wallpaperId,
            'resourceType': request.resourceType,
            'url': request.url.toString(),
            'descriptor': request.descriptor,
          });
      final id = result?['installedId'] as String?;
      if (id == null ||
          result?['wallpaperId'] != request.wallpaperId ||
          result?['resourceType'] != request.resourceType) {
        return const PlatformResult(
          OperationStatus.unknown,
          message: '安装结果不可确认，请检查本地资源',
        );
      }
      return PlatformResult(OperationStatus.completed, value: id);
    } on PlatformException catch (e) {
      return PlatformResult(
        e.code == 'DOWNLOAD_CANCELLED'
            ? OperationStatus.cancelled
            : OperationStatus.unknown,
        message: e.message ?? '资源安装失败，请重试',
      );
    }
  }
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

  Future<Map<String, String>> encryptionPublicKey() async {
    final value = await _channel.invokeMapMethod<String, String>(
      'encryptionPublicKey',
    );
    if (value == null ||
        value['keyAlgorithm'] != 'RSA-OAEP-SHA256-MGF1-SHA1' ||
        value['publicKeyPem'] == null ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(value['fingerprint'] ?? '')) {
      throw StateError('Installation encryption key unavailable');
    }
    return value;
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
